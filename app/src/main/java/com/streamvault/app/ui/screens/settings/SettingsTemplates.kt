package com.MegaStream.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.MegaStream.app.R
import com.MegaStream.app.ui.interaction.TvClickableSurface
import com.MegaStream.app.ui.model.AppUiStyle

@Composable
internal fun StudioSettingsTemplates(
    selectedStyle: AppUiStyle,
    managed: Boolean,
    onStyleSelected: (AppUiStyle) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (managed) Text(stringResource(R.string.settings_template_managed),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 560.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppUiStyle.entries.forEach { style ->
                        AppUiStyleOption(style, style == selectedStyle, !managed,
                            onClick = { if (!managed) onStyleSelected(style) }, modifier = Modifier.weight(1f))
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppUiStyle.entries.forEach { style ->
                        AppUiStyleOption(style, style == selectedStyle, !managed,
                            onClick = { if (!managed) onStyleSelected(style) })
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppUiStyleOption(
    style: AppUiStyle,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvClickableSurface(
        onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().testTag("template_option_${style.storageValue}")
            .semantics { selected = isSelected; role = Role.RadioButton },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppUiStyleMiniPreview(style)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(style.labelResId), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                if (isSelected) Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.a11y_selected),
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
internal fun AppUiStyleMiniPreview(style: AppUiStyle, modifier: Modifier = Modifier) {
    val canvas = when (style) {
        AppUiStyle.CLASSIC -> Color(0xFF07111B)
        AppUiStyle.MODERN -> Color(0xFF111822)
        AppUiStyle.STUDIO -> Color(0xFF101414)
    }
    val accent = when (style) {
        AppUiStyle.CLASSIC -> Color(0xFF69A8FF)
        AppUiStyle.MODERN -> Color(0xFF64D2FF)
        AppUiStyle.STUDIO -> Color(0xFF69DCB1)
    }
    val tile = Color.White.copy(alpha = 0.15f)
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f)
        .testTag("template_preview_${style.storageValue}")
        .background(canvas, RoundedCornerShape(4.dp)).padding(8.dp)) {
        when (style) {
            AppUiStyle.CLASSIC -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(5) { Box(Modifier.weight(1f).height(7.dp).background(if (it == 0) accent else tile)) }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(4) { Box(Modifier.fillMaxWidth().weight(1f).background(tile)) }
                    }
                    Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(4) { Box(Modifier.fillMaxWidth().weight(1f).background(if (it == 0) accent.copy(alpha = 0.4f) else tile)) }
                    }
                }
            }
            AppUiStyle.MODERN -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth(0.65f).height(6.dp).background(accent))
                Box(Modifier.fillMaxWidth().weight(1.2f).background(tile))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(5) { Box(Modifier.weight(1f).fillMaxHeight().background(if (it == 0) accent.copy(alpha = 0.45f) else tile)) }
                }
            }
            AppUiStyle.STUDIO -> Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Column(Modifier.width(13.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(5) { Box(Modifier.fillMaxWidth().height(8.dp).background(if (it == 1) accent else tile)) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth(0.55f).height(6.dp).background(accent))
                    Row(Modifier.weight(1.1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.weight(2f).fillMaxHeight().background(tile))
                        Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFECCB7B).copy(alpha = 0.4f)))
                    }
                    repeat(2) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            repeat(3) { Box(Modifier.weight(1f).fillMaxHeight().background(tile)) }
                        }
                    }
                }
            }
        }
    }
}
