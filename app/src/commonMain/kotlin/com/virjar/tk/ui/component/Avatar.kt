package com.virjar.tk.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virjar.tk.util.ImageCache
import com.virjar.tk.util.buildFileUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A reusable avatar component that loads an image from URL or shows a placeholder.
 * @param url The full URL of the avatar image (empty string shows placeholder)
 * @param name The display name used for generating a text placeholder
 * @param isGroup Whether this is a group avatar (shows Group icon instead of Person)
 * @param size The size of the avatar
 */
@Composable
fun Avatar(
    url: String,
    name: String = "",
    isGroup: Boolean = false,
    size: Dp = 48.dp,
) {
    var imageBitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url) {
        if (url.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                imageBitmap = ImageCache.loadOrFetch(url)
            }
        }
    }

    Surface(
        modifier = Modifier.size(size),
        shape = MaterialTheme.shapes.medium,
        color = if (isGroup)
            MaterialTheme.colorScheme.tertiaryContainer
        else
            MaterialTheme.colorScheme.primaryContainer,
    ) {
        val bitmap = imageBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = name,
                modifier = Modifier.clip(MaterialTheme.shapes.medium),
                contentScale = ContentScale.Crop,
            )
        } else if (name.isNotEmpty()) {
            // Text placeholder: first character of name
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = (size.value * 0.4f).sp),
                    color = if (isGroup)
                        MaterialTheme.colorScheme.onTertiaryContainer
                    else
                        MaterialTheme.colorScheme.onPrimaryContainer,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            // Icon placeholder
            Box(contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(
                    if (isGroup) Icons.Default.Group else Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(size * 0.6f),
                    tint = if (isGroup)
                        MaterialTheme.colorScheme.onTertiaryContainer
                    else
                        MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * Build the full avatar URL from a relative path.
 */
fun buildAvatarUrl(baseUrl: String, avatarPath: String): String = buildFileUrl(baseUrl, avatarPath)
