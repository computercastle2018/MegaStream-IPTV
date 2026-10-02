package com.MegaStream.app.controlplane.integration

import com.MegaStream.app.update.RemoteUpdateCommandRecord
import com.MegaStream.app.update.RemoteUpdateCommandStatus
import com.MegaStream.app.update.RemoteUpdateCommandStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Shares the runtime's checksummed, locked, fsynced no-backup storage; receipts contain no URLs. */
class DurableUpdateCommandStore(
    directory: File,
    private val installationId: String,
    directorySync: (File) -> Unit = ::strictNioDirectorySync,
) : RemoteUpdateCommandStore {
    private val json = Json { encodeDefaults = true }
    private val storage = DurableStateFile(directory, "update-commands", "[]", directorySync)

    init { require(UUID.fromString(installationId).toString() == installationId) }

    override suspend fun claim(record: RemoteUpdateCommandRecord): Boolean = withContext(Dispatchers.IO) {
        storage.locked {
            validate(record)
            val records = read()
            records.firstOrNull { it.commandId == record.commandId }?.let {
                check(it.payloadFingerprint == record.payloadFingerprint) { "Conflicting update command" }
                return@locked false
            }
            check(records.none { it.status !in TERMINAL }) { "Another update command is active" }
            storage.write(json.encodeToString(records + record))
            true
        }
    }

    override suspend fun get(commandId: String): RemoteUpdateCommandRecord? = records().firstOrNull { it.commandId == commandId }

    override suspend fun records(): List<RemoteUpdateCommandRecord> = withContext(Dispatchers.IO) {
        storage.locked { read() }
    }

    override suspend fun compareAndSet(previous: RemoteUpdateCommandRecord, next: RemoteUpdateCommandRecord): Boolean =
        withContext(Dispatchers.IO) {
            storage.locked {
                validate(previous)
                validate(next)
                require(previous.copy(status = next.status, downloadId = next.downloadId,
                    downloadIdentified = next.downloadIdentified, installAttempted = next.installAttempted,
                    failure = next.failure, statusReported = next.statusReported) == next) {
                    "Immutable update command changed"
                }
                val records = read()
                val index = records.indexOf(previous)
                if (index < 0) return@locked false
                records[index] = next
                storage.write(json.encodeToString(records))
                true
            }
        }

    private fun read(): MutableList<RemoteUpdateCommandRecord> {
        val records = json.decodeFromString<List<RemoteUpdateCommandRecord>>(storage.read())
        records.forEach(::validate)
        check(records.map { it.commandId }.distinct().size == records.size)
        check(records.count { it.status !in TERMINAL } <= 1)
        return records.toMutableList()
    }

    private fun validate(record: RemoteUpdateCommandRecord) {
        check(record.installationId == installationId) { "Installation mismatch" }
        check(UUID.fromString(record.commandId).toString() == record.commandId)
        check(record.payloadFingerprint.matches(Regex("[0-9a-f]{64}")))
        check(record.downloadFingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    private companion object {
        val TERMINAL = setOf(RemoteUpdateCommandStatus.Installed, RemoteUpdateCommandStatus.Failed)
    }
}
