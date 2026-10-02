package com.MegaStream.app.ui.components.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.MegaStream.app.R
import com.MegaStream.app.ui.interaction.TvButton

@Composable
internal fun StudioCatalogHero(
    title: String,
    imageUrl: String?,
    metadata: String?,
    onClick: () -> Unit,
    eyebrow: String? = null,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 320.dp)) {
        val compact = maxWidth < 600.dp
        Box(Modifier.fillMaxWidth().height(if (compact) 240.dp else 290.dp)) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                MaterialTheme.colorScheme.background.copy(alpha = 0.15f),
                MaterialTheme.colorScheme.background.copy(alpha = 0.94f)
            ))))
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .padding(horizontal = if (compact) 20.dp else 32.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(eyebrow ?: stringResource(R.string.studio_latest_added),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge)
                Text(title, style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!metadata.isNullOrBlank()) Text(metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                TvButton(onClick = onClick, modifier = modifier) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.studio_open_details))
                }
            }
        }
    }
}
