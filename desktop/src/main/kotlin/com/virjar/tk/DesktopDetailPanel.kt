package com.virjar.tk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

// ──────────────────────────── Detail Panel ────────────────────────────

@Composable
internal fun DesktopDetailPanel(
    modifier: Modifier = Modifier,
    panelBg: Color,
    onLogout: () -> Unit,
) {
    val appState = LocalDesktopState.current
    val selectedChat = appState.selectedChat
    val overlay = appState.overlayDestination

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(PanelShape)
            .background(panelBg),
    ) {
        when {
            // Priority 1: Chat with overlay → render chat + overlay as floating dialog
            selectedChat != null && overlay != null -> {
                DesktopChatPanel(target = selectedChat)
                Dialog(onDismissRequest = { appState.clearOverlay() }) {
                    Surface(
                        modifier = Modifier
                            .widthIn(min = 400.dp, max = overlayMaxWidth(overlay))
                            .heightIn(min = 300.dp, max = 600.dp),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        DesktopOverlayPanel(
                            destination = overlay,
                            chatTarget = selectedChat,
                            onLogout = onLogout,
                        )
                    }
                }
            }

            // Priority 2: Active chat (no overlay)
            selectedChat != null ->
                DesktopChatPanel(target = selectedChat)

            // Priority 3: Overlay without chat (Me, EditProfile, SearchUsers, etc.)
            overlay != null ->
                DesktopOverlayPanel(
                    destination = overlay,
                    onLogout = onLogout,
                )

            // Priority 4: Empty state
            else -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Select a conversation to start chatting",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Or pick a contact from the list",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Determine max dialog width based on overlay destination type. */
private fun overlayMaxWidth(destination: com.virjar.tk.navigation.NavDestination): Dp =
    when (destination) {
        is com.virjar.tk.navigation.NavDestination.SearchMessages -> 600.dp
        is com.virjar.tk.navigation.NavDestination.GroupDetail -> 480.dp
        else -> 400.dp
    }
