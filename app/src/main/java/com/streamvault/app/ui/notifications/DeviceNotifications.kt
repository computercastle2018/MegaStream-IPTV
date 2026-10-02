package com.MegaStream.app.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import com.MegaStream.app.R
import com.MegaStream.app.controlplane.DeviceExperienceViewModel
import com.MegaStream.app.controlplane.DeviceNotice
import com.MegaStream.app.ui.interaction.TvIconButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DeviceNotificationsAction() {
    val viewModel: DeviceExperienceViewModel = hiltViewModel()
    val state by viewModel.repository.state.collectAsState()
    val readIds by viewModel.repository.readIds.collectAsState()
    val unread = state.notifications.count { it.id !in readIds }
    val label = stringResource(R.string.device_notifications)
    val description = if (unread > 0)
        stringResource(R.string.notification_inbox_action_unread, unread) else label
    TvIconButton(onClick = viewModel.repository::openInbox) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Notifications, contentDescription = description,
                tint = MaterialTheme.colorScheme.onBackground)
            if (unread > 0) {
                Box(Modifier.size(8.dp).align(Alignment.TopEnd)
                    .background(MaterialTheme.colorScheme.secondary, CircleShape))
            }
        }
    }
}

@Composable
fun DeviceNotificationsHost() {
    val viewModel: DeviceExperienceViewModel = hiltViewModel()
    val state by viewModel.repository.state.collectAsState()
    val readIds by viewModel.repository.readIds.collectAsState()
    val inboxOpen by viewModel.repository.inboxOpen.collectAsState()
    val visible = visibleDeviceNotices(state.notifications, readIds, inboxOpen)
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var refreshFailed by remember { mutableStateOf(false) }
    suspend fun refreshInbox() {
        if (refreshing) return
        refreshing = true
        refreshFailed = false
        try {
            refreshFailed = !viewModel.repository.refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            refreshFailed = true
        } finally {
            refreshing = false
        }
    }
    LaunchedEffect(inboxOpen) {
        if (inboxOpen) refreshInbox()
    }
    if (inboxOpen || visible.isNotEmpty()) {
        val dismiss = {
            viewModel.repository.markRead(visible.map { it.id }.toSet())
            viewModel.repository.closeInbox()
        }
        DeviceNotificationsDialog(
            notices = visible,
            readIds = readIds,
            inboxOpen = inboxOpen,
            refreshing = refreshing,
            refreshFailed = refreshFailed,
            onRefresh = { scope.launch { refreshInbox() } },
            onDismiss = dismiss,
        )
    }
}

internal fun visibleDeviceNotices(
    notices: List<DeviceNotice>, readIds: Set<String>, inboxOpen: Boolean,
): List<DeviceNotice> {
    val newestFirst = notices.sortedByDescending { java.time.Instant.parse(it.createdAt) }
    return if (inboxOpen) newestFirst else newestFirst.filter { it.id !in readIds }.take(1)
}
