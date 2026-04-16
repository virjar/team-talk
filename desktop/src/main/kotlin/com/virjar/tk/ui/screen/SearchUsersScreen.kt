package com.virjar.tk.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.dto.UserDto
import com.virjar.tk.viewmodel.ContactsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchUsersScreen(
    contactsVm: ContactsViewModel,
    onUserClick: (uid: String) -> Unit,
    onBack: () -> Unit,
    onSendApply: (uid: String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var hasSearched by remember { mutableStateOf(false) }
    val searchState by contactsVm.searchState.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search Users") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
            )
        }
    ) { paddingVal ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingVal).padding(horizontal = 16.dp)) {
            // Search input + button row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        hasSearched = false
                    },
                    label = { Text("Username or phone") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (query.isNotBlank()) {
                            hasSearched = true
                            scope.launch { contactsVm.search(query) }
                        }
                    },
                    enabled = query.isNotBlank() && !searchState.isSearching,
                ) {
                    if (searchState.isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Results
            when {
                searchState.isSearching -> {
                    Box(
                        Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                searchState.error.isNotEmpty() && hasSearched -> {
                    Text(
                        searchState.error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                hasSearched && searchState.results.isEmpty() -> {
                    Box(
                        Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("No users found", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                else -> {
                    LazyColumn {
                        items(searchState.results, key = { it.uid }) { user ->
                            UserSearchResultItem(
                                user = user,
                                onClick = { onUserClick(user.uid) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserSearchResultItem(user: UserDto, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(user.name) },
        supportingContent = {
            user.username?.let { Text("@$it") }
        },
        leadingContent = {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Person,
                        null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
