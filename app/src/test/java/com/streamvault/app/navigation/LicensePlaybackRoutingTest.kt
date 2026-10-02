package com.MegaStream.app.navigation

import com.MegaStream.app.playback.gate.PlaybackGateVerdict
import com.MegaStream.domain.licensing.LicenseAccessState
import org.junit.Assert.*
import org.junit.Test

class LicensePlaybackRoutingTest {
    private val denied = PlaybackGateVerdict.Blocked(LicenseAccessState.UNLICENSED, true)
    private val allowed = PlaybackGateVerdict.Allowed
    private fun player(id: Long = 7) = PlaybackNavigationIntent.Player(
        PlayerNavigationRequest("https://provider.invalid/secret/$id", "Channel", internalId = id),
    )

    @Test fun allowedPlayerBecomesActiveWithoutPendingIntent() {
        val routing = LicensePlaybackRouting()
        val intent = player()
        assertTrue(routing.request(intent, allowed))
        assertSame(intent.request, routing.activePlayer)
        assertNull(routing.pending)
    }

    @Test fun deniedPlayerIsRetainedButCannotRender() {
        val routing = LicensePlaybackRouting()
        val intent = player()
        assertFalse(routing.request(intent, denied))
        assertSame(intent, routing.pending)
        assertNull(routing.activePlayer)
    }

    @Test fun allowedMultiviewDropsPreviousSinglePlayer() {
        val routing = LicensePlaybackRouting()
        routing.request(player(), allowed)
        assertTrue(routing.request(PlaybackNavigationIntent.MultiView, allowed))
        assertNull(routing.activePlayer)
        assertNull(routing.pending)
    }

    @Test fun deniedMultiviewRetriesAsMultiviewNotPlayer() {
        val routing = LicensePlaybackRouting()
        assertFalse(routing.request(PlaybackNavigationIntent.MultiView, denied))
        assertSame(PlaybackNavigationIntent.MultiView, routing.retry(allowed))
        assertNull(routing.activePlayer)
    }

    @Test fun latestExternalRequestSupersedesOlderPendingRequest() {
        val routing = LicensePlaybackRouting()
        routing.request(player(1), denied)
        val newest = player(2)
        routing.request(newest, denied)
        assertSame(newest, routing.retry(allowed))
        assertSame(newest.request, routing.activePlayer)
    }

    @Test fun denialDuringRetryKeepsIntentWithoutAuthorizingPlayback() {
        val routing = LicensePlaybackRouting()
        val intent = player()
        routing.request(intent, denied)
        assertNull(routing.retry(PlaybackGateVerdict.Blocked(LicenseAccessState.EXPIRED, false)))
        assertSame(intent, routing.pending)
        assertNull(routing.activePlayer)
    }

    @Test fun successfulRetryIsConsumedExactlyOnce() {
        val routing = LicensePlaybackRouting()
        val intent = player()
        routing.request(intent, denied)
        assertSame(intent, routing.retry(allowed))
        assertNull(routing.retry(allowed))
        assertNull(routing.pending)
    }

    @Test fun cancelDropsPendingAndPreventsLaterAutomaticPlayback() {
        val routing = LicensePlaybackRouting()
        routing.request(player(), denied)
        routing.cancel()
        assertNull(routing.retry(allowed))
        assertNull(routing.pending)
        assertNull(routing.activePlayer)
    }

    @Test fun globalBlockSuspendsActivePlayerAndKeepsItsExactRequest() {
        val routing = LicensePlaybackRouting()
        val intent = player()
        routing.request(intent, allowed)
        routing.suspendActive(Routes.PLAYER)
        assertNull(routing.activePlayer)
        assertSame(intent.request, (routing.retry(allowed) as PlaybackNavigationIntent.Player).request)
    }

    @Test fun repeatedBlockCannotOverwriteNewPendingPlayerWithMultiview() {
        val routing = LicensePlaybackRouting()
        val intent = player()
        routing.request(intent, denied)
        routing.suspendActive(Routes.MULTI_VIEW)
        assertSame(intent, routing.pending)
    }

    @Test fun globalMultiviewBlockPreservesMultiviewRetry() {
        val routing = LicensePlaybackRouting()
        routing.suspendActive(Routes.MULTI_VIEW)
        assertSame(PlaybackNavigationIntent.MultiView, routing.retry(allowed))
    }

    @Test fun processRecreationCannotRecoverAnOldMediaCapability() {
        val previousProcess = LicensePlaybackRouting()
        previousProcess.request(player(), denied)
        val restored = LicensePlaybackRouting()
        restored.suspendActive(Routes.PLAYER)
        assertNull(restored.retry(allowed))
        assertNull(restored.activePlayer)
    }

    @Test fun singleTopReplacementPublishesTheNewestPlayerRequest() {
        val routing = LicensePlaybackRouting()
        routing.request(player(1), allowed)
        val newer = player(2)
        routing.request(newer, allowed)
        assertSame(newer.request, routing.activePlayerFlow.value)
    }

    @Test fun staleHomeObservationCannotEraseNewPlayerRequest() {
        val routing = LicensePlaybackRouting()
        val newest = player(44)
        routing.request(newest, allowed)
        routing.clearActive(Routes.HOME, Routes.PLAYER, true)
        assertSame(newest.request, routing.activePlayerFlow.value)
    }

    @Test fun currentPlayerObservationCannotEraseAnActiveRequest() {
        val routing = LicensePlaybackRouting()
        val intent = player()
        routing.request(intent, allowed)
        routing.clearActive(Routes.PLAYER, Routes.PLAYER, true)
        assertSame(intent.request, routing.activePlayer)
    }

    @Test fun confirmedHomeDepartureClearsTheOldPlayer() {
        val routing = LicensePlaybackRouting()
        routing.request(player(), allowed)
        routing.clearActive(Routes.HOME, Routes.HOME, false)
        assertNull(routing.activePlayer)
    }

    @Test fun suspendedPlayerSurvivesStaleCleanupButCannotRetryWhileBlocked() {
        val routing = LicensePlaybackRouting()
        val intent = player()
        routing.request(intent, allowed)
        routing.suspendActive(Routes.PLAYER)
        routing.clearActive(Routes.HOME, Routes.PLAYER, true)
        assertNull(routing.activePlayer)
        assertNull(routing.retry(denied))
        assertSame(intent.request, (routing.retry(allowed) as PlaybackNavigationIntent.Player).request)
    }

    @Test fun guideVisitKeepsTheRetainedPlayerRequestForBackNavigation() {
        val routing = LicensePlaybackRouting()
        val intent = player()
        routing.request(intent, allowed)
        routing.clearActive(Routes.EPG, Routes.EPG, true)
        assertSame(intent.request, routing.activePlayer)
    }

    @Test fun protectedRouteVariantsCannotBypassClassification() {
        listOf("player", "player?x=1", "player/extra", "multi_view", "multi_view?x=1").forEach {
            assertTrue(it, isProtectedPlaybackRoute(it))
        }
        listOf(null, "home", "settings", "license_activation", "playerish").forEach {
            assertFalse(it, isProtectedPlaybackRoute(it))
        }
    }

    @Test fun everyDomainDenialKeepsPlaybackBlockedRegardlessOfActionability() {
        LicenseAccessState.entries.filter { it != LicenseAccessState.ALLOWED }.forEach { reason ->
            val routing = LicensePlaybackRouting()
            val blocked = PlaybackGateVerdict.Blocked(reason, false)
            assertFalse(reason.name, routing.request(player(), blocked))
            assertNull(routing.retry(blocked))
            assertNull(routing.activePlayer)
        }
    }

    @Test fun navigationDiagnosticsNeverExposeProviderCredentials() {
        val intent = player()
        assertFalse(intent.toString().contains(intent.request.streamUrl))
        assertFalse(intent.request.toString().contains(intent.request.streamUrl))
    }
}
