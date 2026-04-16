package com.virjar.tk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.virjar.tk.ui.component.Avatar
import com.virjar.tk.ui.component.buildAvatarUrl

// ──────────────────────────── Sidebar ────────────────────────────

@Composable
internal fun DesktopSidebar() {
    val appState = LocalDesktopState.current
    val selectedTab = appState.selectedTab
    val currentUser = appState.currentUser

    Column(
        modifier = Modifier.fillMaxHeight().width(72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Menu items — top-aligned, no top spacer
        SidebarMenuItems.forEachIndexed { index, item ->
            val isSelected = selectedTab == index
            val iconColor = if (isSelected) IconSelectedColor else IconUnselectedColor
            val bgColor = if (isSelected) SelectedItemBgColor else Color.Transparent

            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(bgColor)
                    .clickable { appState.selectTab(index) }
                    .padding(vertical = 8.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                    contentDescription = item.label,
                    tint = iconColor,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = iconColor,
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        Spacer(Modifier.weight(1f))

        // User avatar at bottom — click to open Me page
        Box(
            modifier = Modifier
                .padding(bottom = 16.dp)
                .clip(RoundedCornerShape(20.dp))
                .clickable { appState.onAvatarClick() },
        ) {
            Avatar(
                url = buildAvatarUrl(appState.userContext.baseUrl, currentUser.avatar),
                name = currentUser.name,
                size = 40.dp,
            )
        }
    }
}
