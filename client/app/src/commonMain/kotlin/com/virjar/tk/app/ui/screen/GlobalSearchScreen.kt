package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.User
import com.virjar.tk.app.ui.component.AvatarPlaceholder
import com.virjar.tk.app.ui.component.ChatAvatar
import com.virjar.tk.app.ui.component.ScreenHeader
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.app.ui.component.MessagePreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** 全局搜索的产品域。文件与服务先保留入口，待索引能力接入后直接补结果源。 */
enum class GlobalSearchScope(val label: String) {
    ALL("全部"),
    MESSAGES("消息"),
    PEOPLE("联系人"),
    FILES("文件"),
    SERVICES("服务"),
}

internal fun filterConversationsForSearch(
    conversations: List<Conversation>,
    query: String,
    peerUsers: Map<String, User> = emptyMap(),
): List<Conversation> {
    val term = query.trim()
    if (term.isBlank()) return emptyList()
    return conversations.filter { conversation ->
        val peer = currentConversationPeerUser(conversation, conversation.peerUid?.let(peerUsers::get))
        val identityTerms = if (peer != null) {
            listOf(peer.name, peer.username)
        } else {
            listOfNotNull(conversation.chatName)
        }
        (identityTerms + listOfNotNull(conversation.lastMessage, conversation.chatId))
            .any { it.contains(term, ignoreCase = true) }
    }
}

internal fun filterContactsForSearch(contacts: List<Contact>, query: String): List<User> {
    val term = query.trim()
    if (term.isBlank()) return emptyList()
    return contacts.mapNotNull { it.user }.filter { user ->
        listOf(user.name, user.username, user.phone.orEmpty())
            .any { it.contains(term, ignoreCase = true) }
    }
}

internal fun mergeUsersForSearch(
    localUsers: List<User>,
    remoteUsers: List<User>,
    canonicalUsers: Map<String, User?> = emptyMap(),
): List<User> = (
    localUsers + remoteUsers.mapNotNull { remote ->
        if (canonicalUsers.containsKey(remote.uid)) canonicalUsers[remote.uid] else remote
    }
).distinctBy { it.uid }

internal fun displayedNonContactUserUidsForSearch(
    scope: GlobalSearchScope,
    people: List<User>,
    contactUids: Set<String>,
): List<String> {
    if (scope != GlobalSearchScope.ALL && scope != GlobalSearchScope.PEOPLE) return emptyList()
    val limit = if (scope == GlobalSearchScope.ALL) 6 else 40
    return people.asSequence().take(limit).map(User::uid).filterNot(contactUids::contains).toList()
}

/** 实时会话对端是权威来源；联系人只提供冷启动兜底。 */
internal fun conversationPeerUsersForSearch(
    contacts: List<Contact>,
    conversationPeerUsers: Map<String, User>,
): Map<String, User> = buildMap {
    contacts.mapNotNull(Contact::user).forEach { user -> put(user.uid, user) }
    putAll(conversationPeerUsers)
}

/**
 * 全局搜索输入框。Desktop 放在应用壳层顶栏，Android 放在搜索页顶部。
 * 搜索属于应用级能力，不再挂在“会话”或“通讯录”的局部标题栏。
 */
@Composable
fun GlobalSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索消息、联系人、文件和服务",
    shortcutLabel: String? = null,
    height: Dp = 36.dp,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f) else Tk.colors.divider,
        ),
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .testTag("global.search.input")
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }
                .padding(horizontal = Tk.spacing.md),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = Tk.colors.metaText,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(Tk.spacing.sm))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Tk.colors.metaText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                    when {
                        query.isNotEmpty() -> Icon(
                            Icons.Filled.Close,
                            contentDescription = "清空搜索",
                            tint = Tk.colors.secondaryText,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { onQueryChange("") }
                                .testTag("global.search.clear"),
                        )
                        shortcutLabel != null -> Text(
                            shortcutLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = Tk.colors.metaText,
                        )
                    }
                }
            },
        )
    }
}

/**
 * 聚合消息、会话和用户的全局搜索结果页。
 *
 * 文件与服务搜索目前没有底层索引，界面保留稳定信息架构并明确标注能力缺口，
 * 不伪造结果，也不让暂未实现的功能挤回各业务页面的标题栏。
 */
@Composable
fun GlobalSearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    conversations: List<Conversation>,
    contacts: List<Contact>,
    conversationPeerUsers: Map<String, User> = emptyMap(),
    canonicalSearchUsers: Map<String, User?> = emptyMap(),
    onDisplayedSearchUserUidsChange: (List<String>) -> Unit = {},
    searchMessages: suspend (String) -> List<Message>,
    searchUsers: suspend (String) -> List<User>,
    onConversationClick: (Conversation) -> Unit,
    onMessageClick: (Message) -> Unit,
    onUserClick: (User) -> Unit,
    excludedUserUid: String? = null,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    showHeader: Boolean = true,
    showSearchField: Boolean = true,
) {
    var scope by remember { mutableStateOf(GlobalSearchScope.ALL) }
    var remoteMessages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var remoteUsers by remember { mutableStateOf<List<User>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val term = query.trim()

    LaunchedEffect(term) {
        if (term.isBlank()) {
            remoteMessages = emptyList()
            remoteUsers = emptyList()
            searching = false
            return@LaunchedEffect
        }
        delay(280)
        searching = true
        val (messages, users) = coroutineScope {
            val messageRequest = async { searchMessages(term) }
            val userRequest = async { searchUsers(term) }
            messageRequest.await() to userRequest.await()
        }
        remoteMessages = messages
        remoteUsers = users
        searching = false
    }

    val peerUsers = remember(contacts, conversationPeerUsers) {
        conversationPeerUsersForSearch(contacts, conversationPeerUsers)
    }
    val localConversations = remember(conversations, term, peerUsers) {
        filterConversationsForSearch(conversations, term, peerUsers)
    }
    val localUsers = remember(contacts, term) {
        filterContactsForSearch(contacts, term)
    }
    // 让观察身份与稳定的 RPC 结果集绑定。若从可空的规范行推导，就会解绑被删除的用户、
    // 丢失其 null 墓碑，并在下一次重组时意外复活旧的 RPC 快照。
    val searchCandidatePeople = remember(localUsers, remoteUsers, excludedUserUid) {
        mergeUsersForSearch(localUsers, remoteUsers).filterNot { it.uid == excludedUserUid }
    }
    val people = remember(localUsers, remoteUsers, canonicalSearchUsers, excludedUserUid) {
        mergeUsersForSearch(localUsers, remoteUsers, canonicalSearchUsers)
            .filterNot { it.uid == excludedUserUid }
    }
    val displayedSearchUserUids = remember(scope, searchCandidatePeople, contacts) {
        displayedNonContactUserUidsForSearch(
            scope,
            searchCandidatePeople,
            contacts.map(Contact::friendUid).toSet(),
        )
    }
    val currentDisplayedUsersCallback by rememberUpdatedState(onDisplayedSearchUserUidsChange)
    LaunchedEffect(displayedSearchUserUids) {
        currentDisplayedUsersCallback(displayedSearchUserUids)
    }
    DisposableEffect(Unit) {
        onDispose { currentDisplayedUsersCallback(emptyList()) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (showHeader) ScreenHeader(title = "搜索", onBack = onBack)
        if (showSearchField) {
            GlobalSearchField(
                query = query,
                onQueryChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.lg, vertical = Tk.spacing.md),
                height = Tk.dimens.globalSearchHeight,
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Tk.spacing.lg, vertical = Tk.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Tk.spacing.sm),
        ) {
            items(GlobalSearchScope.entries) { item ->
                FilterChip(
                    selected = scope == item,
                    onClick = { scope = item },
                    label = { Text(item.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    modifier = Modifier.testTag("global.search.scope.${item.name.lowercase()}"),
                )
            }
        }
        HorizontalDivider(color = Tk.colors.divider)

        when {
            term.isBlank() -> SearchLanding(modifier = Modifier.weight(1f))
            scope == GlobalSearchScope.FILES -> MissingSearchCapability(
                icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                title = "文件搜索尚未接入",
                detail = "需要服务端附件索引与权限过滤；入口已保留，接入后无需调整导航结构。",
                modifier = Modifier.weight(1f),
            )
            scope == GlobalSearchScope.SERVICES -> MissingSearchCapability(
                icon = Icons.Filled.Apps,
                title = "服务搜索尚未接入",
                detail = "未来用于搜索机器人、应用与工作台服务。",
                modifier = Modifier.weight(1f),
            )
            else -> SearchResults(
                scope = scope,
                conversations = localConversations,
                peerUsers = peerUsers,
                messages = remoteMessages,
                people = people,
                conversationNames = conversations.associate { conversation ->
                    val peer = conversation.peerUid?.let(peerUsers::get)
                    conversation.chatId to
                        (conversationIdentityPresentation(conversation, peer).name ?: conversation.chatId.take(12))
                },
                searching = searching,
                onConversationClick = onConversationClick,
                onMessageClick = onMessageClick,
                onUserClick = onUserClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SearchLanding(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(Tk.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Tk.colors.metaText, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(Tk.spacing.md))
        Text("从一个入口找到需要的内容", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Tk.spacing.xs))
        Text("当前支持消息、会话与用户；文件和服务索引将后续接入。", color = Tk.colors.secondaryText)
    }
}

@Composable
private fun SearchResults(
    scope: GlobalSearchScope,
    conversations: List<Conversation>,
    peerUsers: Map<String, User>,
    messages: List<Message>,
    people: List<User>,
    conversationNames: Map<String, String>,
    searching: Boolean,
    onConversationClick: (Conversation) -> Unit,
    onMessageClick: (Message) -> Unit,
    onUserClick: (User) -> Unit,
    modifier: Modifier = Modifier,
) {
    val showConversations = scope == GlobalSearchScope.ALL
    val showMessages = scope == GlobalSearchScope.ALL || scope == GlobalSearchScope.MESSAGES
    val showPeople = scope == GlobalSearchScope.ALL || scope == GlobalSearchScope.PEOPLE
    val hasResults = (showConversations && conversations.isNotEmpty()) ||
        (showMessages && messages.isNotEmpty()) || (showPeople && people.isNotEmpty())

    if (searching && !hasResults) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
        }
        return
    }
    if (!hasResults) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("没有找到匹配结果", color = Tk.colors.secondaryText)
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        if (showConversations && conversations.isNotEmpty()) {
            item("conversation.header") { SearchSectionHeader("会话") }
            items(conversations.take(if (scope == GlobalSearchScope.ALL) 5 else 30), key = { "conv.${it.chatId}" }) { conversation ->
                SearchConversationRow(
                    conversation,
                    conversation.peerUid?.let(peerUsers::get),
                    onConversationClick,
                )
            }
        }
        if (showPeople && people.isNotEmpty()) {
            item("people.header") { SearchSectionHeader("联系人与用户") }
            items(people.take(if (scope == GlobalSearchScope.ALL) 6 else 40), key = { "user.${it.uid}" }) { user ->
                SearchUserRow(user, onUserClick)
            }
        }
        if (showMessages && messages.isNotEmpty()) {
            item("message.header") { SearchSectionHeader("消息") }
            items(
                messages.take(if (scope == GlobalSearchScope.ALL) 10 else 50),
                key = ::messageSearchResultKey,
            ) { message ->
                SearchMessageRow(message, conversationNames[message.chatId], onMessageClick)
            }
        }
        if (scope == GlobalSearchScope.ALL) {
            item("missing.capabilities") {
                Text(
                    "文件与服务搜索尚未接入",
                    style = MaterialTheme.typography.labelMedium,
                    color = Tk.colors.metaText,
                    modifier = Modifier.fillMaxWidth().padding(Tk.spacing.lg),
                )
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = Tk.colors.secondaryText,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.lg, vertical = Tk.spacing.sm),
    )
}

@Composable
private fun SearchConversationRow(
    conversation: Conversation,
    peerUser: User?,
    onClick: (Conversation) -> Unit,
) {
    val identity = conversationIdentityPresentation(conversation, peerUser)
    val displayName = identity.name
    ListItem(
        headlineContent = { Text(displayName ?: conversation.chatId.take(16), maxLines = 1) },
        supportingContent = { Text(conversation.lastMessage.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            ChatAvatar(
                chatType = conversation.chatType,
                chatName = displayName,
                avatar = identity.avatar,
                size = 36,
            )
        },
        modifier = Modifier
            .clickable { onClick(conversation) }
            .testTag("global.search.conversation.${conversation.chatId.take(12)}"),
    )
}

@Composable
private fun SearchUserRow(user: User, onClick: (User) -> Unit) {
    ListItem(
        headlineContent = { Text(user.name) },
        supportingContent = { Text("@${user.username}") },
        leadingContent = { AvatarPlaceholder(name = user.name, avatar = user.avatar, size = 36) },
        trailingContent = { Text("查看资料", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable { onClick(user) }.testTag("global.search.user.${user.uid.take(8)}"),
    )
}

@Composable
private fun SearchMessageRow(message: Message, conversationName: String?, onClick: (Message) -> Unit) {
    ListItem(
        headlineContent = { Text(MessagePreview.preview(message), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(conversationName ?: message.chatId.take(12), maxLines = 1) },
        leadingContent = { Icon(Icons.Filled.Search, contentDescription = null, tint = Tk.colors.secondaryText) },
        modifier = Modifier
            .clickable { onClick(message) }
            .testTag("global.search.message.${message.chatId.take(10)}.${message.serverSeq}"),
    )
}

@Composable
private fun MissingSearchCapability(
    icon: ImageVector,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(Tk.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Tk.colors.metaText, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(Tk.spacing.md))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Tk.spacing.xs))
        Text(detail, color = Tk.colors.secondaryText, style = MaterialTheme.typography.bodySmall)
    }
}
