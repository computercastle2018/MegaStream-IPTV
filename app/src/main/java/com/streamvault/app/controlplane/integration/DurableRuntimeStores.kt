package com.MegaStream.app.controlplane.integration

import com.MegaStream.app.controlplane.DeviceAbi
import com.MegaStream.app.controlplane.DiagnosticRejectionCode
import com.MegaStream.app.controlplane.ReleaseChannel
import com.MegaStream.app.controlplane.runtime.DiagnosticsQuarantine
import com.MegaStream.app.controlplane.runtime.DiagnosticsStorageStage
import com.MegaStream.app.controlplane.runtime.DiagnosticsUploadResult
import com.MegaStream.app.controlplane.runtime.HeartbeatSequenceStore
import com.MegaStream.app.controlplane.runtime.RegistrationAttempt
import com.MegaStream.app.controlplane.runtime.RegistrationAttemptStore
import com.MegaStream.app.controlplane.runtime.RuntimeMetadata
import com.MegaStream.domain.diagnostics.DiagnosticsOutbox
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Durable installation-scoped registration and sequence ledger. Keep the entire directory, including
 * initialization witnesses, across upgrades. Loss of ALL files is indistinguishable from first use;
 * restoring stale backups is unsupported. No credentials or raw installation IDs are stored here.
 * Supply a dedicated child of Context.noBackupFilesDir and construct off the UI thread (canonical
 * path resolution performs IO). Requires Android API 27+ or a JVM filesystem with atomic rename
 * and directory fsync; unsupported storage fails closed rather than weakening durability.
 * directorySync must force the directory entry or throw; Android composition supplies Os.fsync
 * while the default uses a strict JVM NIO directory channel. Never supply a best-effort/no-op callback.
 */
class DurableRuntimeStores(
    directory: File,
    directorySync: (File) -> Unit = ::strictNioDirectorySync,
) : RegistrationAttemptStore, HeartbeatSequenceStore {
    private val storage = DurableStateFile(directory, "runtime", "R2\n", directorySync)

    /** Pins the registration payload to its first attempt, even after application/device upgrades. */
    suspend fun registrationMetadata(installationId: String, current: RuntimeMetadata): RuntimeMetadata =
        withContext(Dispatchers.IO) {
            safeStorage {
                val id = installationHash(installationId)
                storage.locked {
                    val state = readRuntime()
                    val previous = state[id] ?: RuntimeEntry()
                    if (previous.metadata != null) return@locked previous.metadata
                    validateMetadata(current)
                    state[id] = previous.copy(key = previous.key ?: UUID.randomUUID().toString(), metadata = current)
                    storage.write(runtimePayload(state))
                    current
                }
            }
        }

    override suspend fun getOrCreate(installationId: String): RegistrationAttempt = withContext(Dispatchers.IO) {
        safeStorage {
            val id = installationHash(installationId)
            storage.locked {
                val state = readRuntime()
                val previous = state[id] ?: RuntimeEntry()
                val entry = if (previous.key == null) previous.copy(key = UUID.randomUUID().toString()) else previous
                if (entry != previous) {
                    state[id] = entry
                    storage.write(runtimePayload(state))
                }
                RegistrationAttempt(installationId, entry.key ?: storageFailure(), entry.registered)
            }
        }
    }

    override suspend fun markRegistered(installationId: String, idempotencyKey: String) = withContext(Dispatchers.IO) {
        safeStorage {
            val id = installationHash(installationId)
            canonicalUuid(idempotencyKey)
            storage.locked {
                val state = readRuntime()
                val entry = state[id] ?: storageFailure()
                check(entry.key == idempotencyKey)
                if (!entry.registered) {
                    state[id] = entry.copy(registered = true)
                    storage.write(runtimePayload(state))
                }
            }
        }
    }

    override suspend fun allocateNext(installationId: String): Long = withContext(Dispatchers.IO) {
        safeStorage {
            val id = installationHash(installationId)
            storage.locked {
                val state = readRuntime()
                val previous = state[id] ?: RuntimeEntry()
                check(previous.sequence < Long.MAX_VALUE)
                val next = previous.sequence + 1
                state[id] = previous.copy(sequence = next)
                storage.write(runtimePayload(state))
                next
            }
        }
    }

    private fun readRuntime(): MutableMap<String, RuntimeEntry> {
        val payload = storage.read()
        val lines = payload.lines()
        check(lines.first() == "R2" && lines.last().isEmpty())
        val entries = sortedMapOf<String, RuntimeEntry>()
        for (line in lines.drop(1).dropLast(1)) {
            val fields = line.split('\t')
            check(fields.size == 5 && fields[0].matches(Regex("[0-9a-f]{64}")))
            val key = fields[1].takeUnless { it == "-" }?.also(::canonicalUuid)
            check(fields[2] == "0" || fields[2] == "1")
            val sequence = fields[3].toLong()
            val metadata = fields[4].takeUnless { it == "-" }?.let(::decodeMetadata)
            check(sequence >= -1 && (fields[2] == "0" || key != null) && (metadata == null || key != null))
            check(entries.put(fields[0], RuntimeEntry(key, fields[2] == "1", sequence, metadata)) == null)
        }
        check(runtimePayload(entries) == payload)
        return entries
    }
}

/**
 * Durable rejection markers for ONE outbox/installation; never edits the originals.
 * Use the same private no-backup directory and strict directorySync contract as DurableRuntimeStores;
 * construct off the UI thread.
 * All uploads MUST run as `quarantine.withUploadIntent { uploader.upload() }` on an IO thread.
 * contains/record are only available synchronously inside this instance's active upload intent.
 * The wrapper holds its cross-process lock through transport and acknowledgement, and commits the
 * intent before invoking any uploader code. An interrupted upload remains blocked across restart.
 * The owner must serialize outbox mutations and supply truthful complete snapshots below 50 events.
 * FileDiagnosticsOutbox can mask failed reads/pruning as empty: that implementation cannot prove
 * absence during storage failure. Do not use its masked empty snapshot as a recovery guarantee;
 * repair its storage first. UUID reuse is distinguishable only when absence was observed in between.
 */
class DurableDiagnosticsQuarantine(
    directory: File,
    private val outbox: DiagnosticsOutbox,
    directorySync: (File) -> Unit = ::strictNioDirectorySync,
) : DiagnosticsQuarantine {
    private val storage = DurableStateFile(directory, "diagnostics", "Q1\n0\n", directorySync)
    private val activeIntent = ThreadLocal<Boolean>()

    fun withUploadIntent(block: () -> DiagnosticsUploadResult): DiagnosticsUploadResult = safeStorage {
        storage.locked {
            val state = readQuarantine()
            if (state.pending) return@locked DiagnosticsUploadResult.BlockedUntilRecovery
            // This commit is deliberately before outbox inspection, marker reconciliation or network.
            storage.write(quarantinePayload(state.copy(pending = true)))
            activeIntent.set(true)
            try {
                reconcile(readQuarantine())
                val result = block()
                val keepPending = result == DiagnosticsUploadResult.BlockedUntilRecovery ||
                    result == DiagnosticsUploadResult.StorageFailure(DiagnosticsStorageStage.WRITE_QUARANTINE)
                if (!keepPending) storage.write(quarantinePayload(readQuarantine().copy(pending = false)))
                result
            } finally {
                // No catch clears the durable intent, including exceptions/errors/cancellation.
                activeIntent.remove()
            }
        }
    }

    override fun contains(id: UUID): Boolean = safeStorage {
        check(activeIntent.get() == true)
        storage.locked {
            val state = readQuarantine()
            check(state.pending)
            id.toString() in state.markers
        }
    }

    override fun record(id: UUID, reason: DiagnosticRejectionCode): Boolean = safeStorage {
        check(activeIntent.get() == true)
        storage.locked {
            val state = readQuarantine()
            check(state.pending)
            val key = id.toString()
            // An existing marker is already durable; preserve its original rejection reason.
            if (key !in state.markers) {
                state.markers[key] = reason
                storage.write(quarantinePayload(state))
            }
            true
        }
    }

    /**
     * EXPLICIT OPERATOR RECOVERY ONLY, never an automatic retry action. Before calling, the operator
     * must reconcile unknown rejected originals (for example, explicitly repair/clear the outbox).
     * This method cannot infer server rejections lost in the upload/write gap. It preserves every
     * original-present marker, clears the durable intent only after successful reconciliation, and
     * does not repair corrupt/missing ledger files. Also reset the uploader's in-memory recovery latch
     * after this succeeds. Neither this method nor this class clears/acknowledges the outbox.
     */
    fun repairAfterStorageFailure(): Unit = safeStorage {
        check(activeIntent.get() != true)
        storage.locked {
            val state = reconcile(readQuarantine())
            storage.write(quarantinePayload(state.copy(pending = false)))
        }
    }

    private fun reconcile(state: QuarantineState): QuarantineState {
        val snapshot = outbox.batch(50).toList()
        check(snapshot.size <= 50)
        val ids = snapshot.map { it.id.toString() }.toSet()
        check(ids.size == snapshot.size)
        // Exactly 50 might be only a prefix. Absence is proven ONLY by a complete shorter snapshot.
        if (snapshot.size < 50 && state.markers.keys.any { it !in ids }) {
            state.markers.keys.retainAll(ids)
            storage.write(quarantinePayload(state))
        }
        return state
    }

    private fun readQuarantine(): QuarantineState {
        val payload = storage.read()
        val lines = payload.lines()
        check(lines.size >= 3 && lines[0] == "Q1" && lines.last().isEmpty())
        check(lines[1] == "0" || lines[1] == "1")
        val markers = sortedMapOf<String, DiagnosticRejectionCode>()
        for (line in lines.drop(2).dropLast(1)) {
            val fields = line.split('\t')
            check(fields.size == 2)
            canonicalUuid(fields[0])
            check(markers.put(fields[0], DiagnosticRejectionCode.valueOf(fields[1])) == null)
        }
        val state = QuarantineState(lines[1] == "1", markers)
        check(quarantinePayload(state) == payload)
        return state
    }
}

private data class RuntimeEntry(
    val key: String? = null,
    val registered: Boolean = false,
    val sequence: Long = -1,
    val metadata: RuntimeMetadata? = null,
)
private data class QuarantineState(val pending: Boolean, val markers: MutableMap<String, DiagnosticRejectionCode>)

private fun runtimePayload(entries: Map<String, RuntimeEntry>) = buildString {
    append("R2\n")
    for ((id, entry) in entries.toSortedMap()) {
        append(id).append('\t').append(entry.key ?: "-").append('\t')
        append(if (entry.registered) '1' else '0').append('\t').append(entry.sequence).append('\t')
        append(entry.metadata?.let(::encodeMetadata) ?: "-").append('\n')
    }
}

private fun quarantinePayload(state: QuarantineState) = buildString {
    append("Q1\n").append(if (state.pending) '1' else '0').append('\n')
    for ((id, reason) in state.markers.toSortedMap()) append(id).append('\t').append(reason.name).append('\n')
}

private fun validateMetadata(metadata: RuntimeMetadata) {
    require(metadata.appVersionCode >= 0 && metadata.androidApi > 0)
    val textFields = listOf(metadata.appVersionName, metadata.manufacturer, metadata.model,
        metadata.androidRelease, metadata.locale, metadata.packageName)
    require(textFields.all { it.isNotBlank() && it.length <= 1024 && it.none(Char::isISOControl) })
}

private fun encodeMetadata(metadata: RuntimeMetadata): String {
    validateMetadata(metadata)
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output ->
        output.writeLong(metadata.appVersionCode)
        output.writeUTF(metadata.appVersionName)
        output.writeUTF(metadata.manufacturer)
        output.writeUTF(metadata.model)
        output.writeInt(metadata.androidApi)
        output.writeUTF(metadata.androidRelease)
        output.writeUTF(metadata.abi.name)
        output.writeUTF(metadata.locale)
        output.writeBoolean(metadata.managedDevice)
        output.writeUTF(metadata.packageName)
        output.writeUTF(metadata.channel.name)
    }
    return Base64.getEncoder().encodeToString(bytes.toByteArray())
}

private fun decodeMetadata(encoded: String): RuntimeMetadata {
    check(encoded.length <= 32768)
    val bytes = Base64.getDecoder().decode(encoded)
    val metadata = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val decoded = RuntimeMetadata(
            appVersionCode = input.readLong(), appVersionName = input.readUTF(),
            manufacturer = input.readUTF(), model = input.readUTF(), androidApi = input.readInt(),
            androidRelease = input.readUTF(), abi = DeviceAbi.valueOf(input.readUTF()),
            locale = input.readUTF(), managedDevice = input.readBoolean(), packageName = input.readUTF(),
            channel = ReleaseChannel.valueOf(input.readUTF()),
        )
        check(input.read() == -1)
        decoded
    }
    check(encodeMetadata(metadata) == encoded)
    return metadata
}

private fun installationHash(id: String): String {
    require(id.isNotBlank() && id.length <= 1024 && id.none { it.isISOControl() || it.isSurrogate() })
    return sha256(id.toByteArray(Charsets.UTF_8))
}

private fun canonicalUuid(value: String) {
    check(value.length == 36 && UUID.fromString(value).toString() == value)
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun storageFailure(): Nothing = throw IllegalStateException("Durable runtime storage unavailable")

private inline fun <T> safeStorage(block: () -> T): T = try {
    block()
} catch (_: CancellationException) {
    throw CancellationException("Durable runtime operation cancelled")
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    throw InterruptedException("Durable runtime operation interrupted")
} catch (_: Exception) {
    storageFailure()
}

private fun strictNioDirectorySync(directory: File) {
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
}

/** Minimal checksummed atomic file with a separate permanent initialization witness. */
private class DurableStateFile(
    directory: File,
    name: String,
    private val initialPayload: String,
    private val syncDirectory: (File) -> Unit,
) {
    private val root = safeStorage { directory.canonicalFile }
    private val state = File(root, "$name.state")
    private val witness = File(root, "$name.initialized")
    private val lockFile = File(root, "$name.lock")
    private val lock = safeStorage { locks.computeIfAbsent(lockFile.canonicalPath) { ReentrantLock() } }
    private val witnessText = "MEGASTREAM-INITIALIZED-1\n$name\n"
    private var observedInitialized = false

    fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            if (lock.holdCount > 1) return block() // Same canonical file already OS-locked by this thread.
            ensureDirectory(root)
            RandomAccessFile(lockFile, "rw").use { file ->
                file.channel.lock().use { return block() }
            }
        } finally {
            lock.unlock()
        }
    }

    fun read(): String {
        check(lock.isHeldByCurrentThread)
        if (attributesOrMissing(witness) == null) {
            check(!observedInitialized && attributesOrMissing(state) == null &&
                attributesOrMissing(File(root, "${state.name}.next")) == null)
            atomicWrite(witness, witnessText.toByteArray(Charsets.UTF_8))
            observedInitialized = true
            write(initialPayload)
        }
        check(readBounded(witness).contentEquals(witnessText.toByteArray(Charsets.UTF_8)))
        observedInitialized = true
        val bytes = readBounded(state)
        val text = bytes.toString(Charsets.UTF_8)
        check(text.toByteArray(Charsets.UTF_8).contentEquals(bytes))
        val first = text.indexOf('\n')
        val second = text.indexOf('\n', first + 1)
        check(first > 0 && second > first && text.substring(0, first) == "MEGASTREAM-DURABLE-1")
        val payload = text.substring(second + 1)
        check(text.substring(first + 1, second) == sha256(payload.toByteArray(Charsets.UTF_8)))
        return payload
    }

    fun write(payload: String) {
        check(lock.isHeldByCurrentThread)
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val envelope = "MEGASTREAM-DURABLE-1\n${sha256(bytes)}\n$payload".toByteArray(Charsets.UTF_8)
        check(envelope.size <= MAX_BYTES)
        atomicWrite(state, envelope)
    }

    private fun readBounded(file: File): ByteArray {
        val attributes = attributesOrMissing(file) ?: storageFailure()
        check(attributes.isRegularFile && attributes.size() in 1..MAX_BYTES.toLong())
        return file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= MAX_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temporary = File(root, "${target.name}.next")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        // Never delete the original or fall back to a non-atomic move on unsupported filesystems.
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(root)
    }

    private fun attributesOrMissing(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
    } catch (_: NoSuchFileException) {
        null // Permission and IO failures must not masquerade as a new installation.
    }

    private fun ensureDirectory(directory: File) {
        val attributes = attributesOrMissing(directory)
        if (attributes == null) {
            val parent = directory.parentFile ?: storageFailure()
            ensureDirectory(parent)
            try {
                Files.createDirectory(directory.toPath())
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                check(attributesOrMissing(directory)?.isDirectory == true)
            }
        } else check(attributes.isDirectory)
        // Also force an existing directory's parent: a concurrent creator may not have synced it yet.
        directory.parentFile?.let(syncDirectory)
    }

    companion object {
        private const val MAX_BYTES = 4 * 1024 * 1024
        // Retained for process lifetime; removing an entry could create two locks for one OS file.
        private val locks = ConcurrentHashMap<String, ReentrantLock>()
    }
}
