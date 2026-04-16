package com.virjar.tk.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.dto.FriendApplyDto
import com.virjar.tk.viewmodel.ContactsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendAppliesScreen(
    contactsVm: ContactsViewModel,
    onBack: () -> Unit,
    onAccept: (token: String) -> Unit,
) {
    val state by contactsVm.appliesState.collectAsState()

    LaunchedEffect(Unit) { contactsVm.loadApplies() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Friends") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
            )
        }
    ) { padding ->
        when {
            state.isLoading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.applies.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("No friend requests", style = MaterialTheme.typography.bodyLarge)
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                    items(state.applies, key = { it.token }) { apply ->
                        FriendApplyItem(
                            apply = apply,
                            onAccept = { onAccept(apply.token) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendApplyItem(apply: FriendApplyDto, onAccept: () -> Unit) {
    val displayName = apply.fromName.ifEmpty { apply.fromUid.take(12) }

    ListItem(
        headlineContent = { Text(displayName) },
        supportingContent = {
            if (apply.remark.isNotEmpty()) {
                Text(apply.remark, style = MaterialTheme.typography.bodySmall)
            }
        },
        leadingContent = {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        },
        trailingContent = {
            when (apply.status) {
                0 -> FilledTonalButton(onClick = onAccept) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Accept")
                }
                1 -> Text("Accepted", style = MaterialTheme.typography.bodySmall)
                2 -> Text("Rejected", style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}
