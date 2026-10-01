package com.MegaStream.app.controlplane.integration

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.MegaStream.app.controlplane.InstallationCredentialStore
import com.MegaStream.app.controlplane.InstallationCredentials
import com.MegaStream.app.licensing.EncryptedLocalEntitlementStateStore
import com.MegaStream.data.licensing.LocalAppEntitlement
import com.MegaStream.domain.licensing.ClockReading
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Singleton

/** Blocking providers are resolved lazily by the runtime factory, exclusively on Dispatchers.IO. */
@Module
@InstallIn(SingletonComponent::class)
object RuntimeFeatureModule {
    @Provides
    @Singleton
    fun credentials(@ApplicationContext context: Context): InstallationCredentials =
        (InstallationCredentialStore(context).loadOrCreate() as? InstallationCredentialStore.State.Ready)
            ?.credentials ?: error("Installation credentials unavailable")

    @Provides
    @Singleton
    fun entitlementStore(@ApplicationContext context: Context) = EncryptedLocalEntitlementStateStore(context)

    @Provides
    @Singleton
    fun entitlement(
        @ApplicationContext context: Context,
        credentials: InstallationCredentials,
        store: EncryptedLocalEntitlementStateStore,
    ): LocalAppEntitlement {
        val raw = Base64.getUrlDecoder().decode(credentials.credential)
        val binding = try {
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(raw))
        } finally {
            raw.fill(0)
        }
        return LocalAppEntitlement(RuntimeLeasePins.verifier(), credentials.installationId, binding, store) {
            val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            check(boot >= 0) { "Trusted boot observation unavailable" }
            ClockReading(System.currentTimeMillis() / 1_000, SystemClock.elapsedRealtime(), boot.toString())
        }
    }
}
