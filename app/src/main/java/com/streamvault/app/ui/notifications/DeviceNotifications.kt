package com.MegaStream.app.ui.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.MegaStream.app.R
import com.MegaStream.app.controlplane.DeviceExperienceViewModel
import com.MegaStream.app.ui.components.dialogs.PremiumDialog
import com.MegaStream.app.ui.components.dialogs.PremiumDialogFooterButton
import com.MegaStream.app.ui.design.AppColors
import com.MegaStream.app.ui.interaction.TvIconButton

@Composable
fun DeviceNotificationsAction() {
    val viewModel: DeviceExperienceViewModel = hiltViewModel()
    val state by viewModel.repository.state.collectAsState()
    val readIds by viewModel.repository.readIds.collectAsState()
    val unread = state.notifications.count { it.id !in readIds }
    val label = stringResource(R.string.device_notifications)
    TvIconButton(onClick = { viewModel.repository.openInbox(); viewModel.refresh() }) {
        Icon(Icons.Default.Notifications,
            contentDescription = if (unread > 0) "$label ($unread)" else label,
            tint = if (unread > 0) AppColors.Brand else AppColors.TextPrimary)
    }
}

@Composable
fun DeviceNotificationsHost() {
    val viewModel: DeviceExperienceViewModel = hiltViewModel()
    val state by viewModel.repository.state.collectAsState()
    val readIds by viewModel.repository.readIds.collectAsState()
    val inboxOpen by viewModel.repository.inboxOpen.collectAsState()
    val unread = state.notifications.filter { it.id !in readIds }
    val visible = if (inboxOpen) state.notifications else unread.takeLast(1)
    val label = stringResource(R.string.device_notifications)
    if (inboxOpen || visible.isNotEmpty()) {
        val dismiss = {
            viewModel.repository.markRead(visible.map { it.id }.toSet())
            viewModel.repository.closeInbox()
        }
        PremiumDialog(
            title = label,
            widthFraction = 0.65f,
            onDismissRequest = dismiss,
            footer = {
                if (inboxOpen) PremiumDialogFooterButton(stringResource(R.string.device_notifications_refresh), onClick = viewModel::refresh)
                PremiumDialogFooterButton(stringResource(R.string.device_notifications_close), onClick = dismiss)
            },
            content = {
                if (state.notifications.isEmpty()) Text(stringResource(R.string.device_notifications_empty), color = AppColors.TextSecondary)
                visible.forEach { notice ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(notice.title, style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary)
                        Text(notice.message, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
                        Text(notice.createdAt, style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
                    }
                }
            }
        )
    }
}
