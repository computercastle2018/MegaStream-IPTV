package com.MegaStream.app.di

import com.MegaStream.app.controlplane.integration.ProductionRuntime
import com.MegaStream.app.playback.gate.PlaybackGate
import com.MegaStream.app.playback.gate.RuntimePlaybackGate
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object PlaybackGateModule {
    @Provides
    @Singleton
    fun providePlaybackGate(runtime: ProductionRuntime): PlaybackGate = RuntimePlaybackGate(
        gate = runtime::gate,
        observations = runtime.snapshot,
        // Trusted-time evaluation may persist encrypted state; UI consumers choose their own lane.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )
}
