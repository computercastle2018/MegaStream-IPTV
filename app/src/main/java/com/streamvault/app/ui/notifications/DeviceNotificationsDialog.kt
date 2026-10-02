package com.MegaStream.app.ui.notifications

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.MegaStream.app.R
import com.MegaStream.app.controlplane.DeviceNotice
import com.MegaStream.app.ui.components.dialogs.rememberDialogOpenGestureBlocker
import com.MegaStream.app.ui.design.requestFocusSafely
import com.MegaStream.app.ui.interaction.TvButton
import com.MegaStream.app.ui.interaction.TvClickableSurface
import com.MegaStream.app.ui.interaction.TvIconButton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun DeviceNotificationsDialog(
    notices: List<DeviceNotice>,
    readIds: Set<String>,
    inboxOpen: Boolean,
    refreshing: Boolean,
    refreshFailed: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var canInteract by remember { mutableStateOf(false) }
    var selectedId by remember(inboxOpen) { mutableStateOf<String?>(null) }
    val blockOpenGesture = rememberDialogOpenGestureBlocker(canInteract)
    val listFocus = remember { FocusRequester() }
    val detailFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val locale = LocalConfiguration.current.locales[0]
    val unreadCount = notices.count { it.id !in readIds }
    LaunchedEffect(Unit) { delay(500); canInteract = true }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = canInteract,
            dismissOnClickOutside = canInteract,
            usePlatformDefaultWidth = false,
        )
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            val wide = maxWidth >= 700.dp
            val selected = notices.firstOrNull { it.id == selectedId }
                ?: if (wide || !inboxOpen) notices.firstOrNull { it.id !in readIds } ?: notices.firstOrNull() else null
            val showList = inboxOpen && (wide || selected == null)
            LaunchedEffect(wide, inboxOpen, notices) {
                if ((selectedId == null && (wide || !inboxOpen)) ||
                    (selectedId != null && notices.none { it.id == selectedId })) {
                    selectedId = selected?.id
                }
            }
            LaunchedEffect(canInteract, selectedId, showList, notices.isEmpty()) {
                if (canInteract) {
                    val target = when {
                        notices.isEmpty() -> closeFocus
                        showList && selectedId == null -> listFocus
                        else -> detailFocus
                    }
                    target.requestFocusSafely(tag = "Notifications", target = "Inbox focus")
                }
            }

            Column(
                Modifier.widthIn(max = 1000.dp).fillMaxWidth().height(maxHeight * 0.94f)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                    .onPreviewKeyEvent(blockOpenGesture).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!wide && inboxOpen && selected != null) {
                        TvIconButton(onClick = { selectedId = null }, enabled = canInteract) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.notification_inbox_back))
                        }
                    } else {
                        Icon(Icons.Default.Notifications, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.device_notifications),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.semantics { heading() })
                        Text(pluralStringResource(R.plurals.notification_inbox_messages, notices.size, notices.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(pluralStringResource(R.plurals.notification_inbox_unread, unreadCount, unreadCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (unreadCount > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                    TvIconButton(onClick = onDismiss, enabled = canInteract,
                        modifier = Modifier.focusRequester(closeFocus)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.device_notifications_close))
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)))
                if (refreshFailed) {
                    Text(stringResource(R.string.notification_inbox_refresh_failed),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (notices.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Notifications, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(if (refreshing) R.string.notification_inbox_refreshing else R.string.device_notifications_empty),
                            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                } else {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (showList) {
                            LazyColumn(
                                state = listState,
                                modifier = (if (wide) Modifier.width(280.dp) else Modifier.weight(1f))
                                    .fillMaxHeight().testTag("notification_list"),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                itemsIndexed(notices, key = { _, notice -> notice.id }) { index, notice ->
                                    NotificationPreview(
                                        notice = notice,
                                        unread = notice.id !in readIds,
                                        isSelected = selected?.id == notice.id,
                                        date = formatNoticeDate(notice.createdAt, locale),
                                        enabled = canInteract,
                                        onClick = { selectedId = notice.id },
                                        modifier = (if (index == listState.firstVisibleItemIndex)
                                            Modifier.focusRequester(listFocus) else Modifier).focusProperties {
                                                if (wide) end = detailFocus
                                                if (index == 0) up = closeFocus
                                            },
                                    )
                                }
                            }
                        }
                        if (wide && showList) {
                            Box(Modifier.width(1.dp).fillMaxHeight()
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)))
                        }
                        selected?.let { notice ->
                            NotificationDetail(
                                notice = notice,
                                unread = notice.id !in readIds,
                                date = formatNoticeDate(notice.createdAt, locale),
                                modifier = Modifier.weight(1f).fillMaxHeight().focusRequester(detailFocus)
                                    .focusProperties {
                                        up = closeFocus
                                        if (wide && showList) start = listFocus
                                    },
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (inboxOpen) {
                        TvButton(onClick = onRefresh, enabled = canInteract && !refreshing,
                            scale = ButtonDefaults.scale(focusedScale = 1f)) {
                            if (refreshing) CircularProgressIndicator(Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                            else Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(if (refreshing) R.string.notification_inbox_refreshing else R.string.device_notifications_refresh))
                        }
                    }
                    TvButton(onClick = onDismiss, enabled = canInteract,
                        scale = ButtonDefaults.scale(focusedScale = 1f)) {
                        Text(stringResource(R.string.device_notifications_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationPreview(
    notice: DeviceNotice, unread: Boolean, isSelected: Boolean, date: String,
    enabled: Boolean, onClick: () -> Unit, modifier: Modifier,
) {
    val status = stringResource(if (unread) R.string.notification_inbox_unread_status else R.string.notification_inbox_read_status)
    TvClickableSurface(
        onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().testTag("notification_preview_${notice.id}")
            .semantics { selected = isSelected; stateDescription = status },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(
                    if (unread) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    CircleShape))
                Text(notice.title, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Text(notice.message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NotificationDetail(notice: DeviceNotice, unread: Boolean, date: String, modifier: Modifier) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val scrollStep = with(LocalDensity.current) { 96.dp.toPx() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(notice.id) { scroll.scrollTo(0) }
    Column(modifier.testTag("notification_message_detail")
        .border(BorderStroke(2.dp, if (focused) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent),
            RoundedCornerShape(8.dp))
        .onFocusChanged { focused = it.isFocused }
        .onPreviewKeyEvent {
            val event = it.nativeKeyEvent
            val delta = when {
                event.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN && scroll.canScrollForward -> scrollStep
                event.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP && scroll.canScrollBackward -> -scrollStep
                else -> return@onPreviewKeyEvent false
            }
            if (event.action == AndroidKeyEvent.ACTION_DOWN) scope.launch { scroll.animateScrollTo((scroll.value + delta.toInt()).coerceIn(0, scroll.maxValue)) }
            true
        }.focusable().verticalScroll(scroll).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(if (unread) R.string.notification_inbox_unread_status else R.string.notification_inbox_read_status),
            style = MaterialTheme.typography.labelMedium,
            color = if (unread) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(notice.title, style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
        Text(date, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(notice.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

internal fun formatNoticeDate(createdAt: String, locale: Locale): String = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
    .withZone(ZoneId.systemDefault()).format(Instant.parse(createdAt))
