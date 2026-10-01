package com.MegaStream.app.di

import android.app.Activity
import com.MegaStream.app.kiosk.ManagedKioskController
import com.MegaStream.app.kiosk.integration.DefaultKioskPolicySource
import com.MegaStream.app.kiosk.integration.KioskEngine
import com.MegaStream.app.kiosk.integration.KioskPolicySource
import com.MegaStream.app.kiosk.integration.ManagedKioskEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.scopes.ActivityScoped

@Module
@InstallIn(ActivityComponent::class)
object KioskIntegrationModule {
    @Provides
    @ActivityScoped
    fun provideKioskEngine(activity: Activity): KioskEngine =
        ManagedKioskEngine(ManagedKioskController(activity))

    @Provides
    @ActivityScoped
    fun provideKioskPolicySource(source: com.MegaStream.app.kiosk.integration.RuntimeKioskPolicySource): KioskPolicySource = source
}
