package com.virjar.tk.navigation

import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.protocol.payload.Message

/**
 * Type-safe navigation destinations.
 * Each route carries the data needed to render the screen.
 */
sealed class NavDestination {
    data object Login : NavDestination()
    data object Register : NavDestination()

    /** Main screen with bottom tabs */
    data class Main(val initialTab: Int = 0) : NavDestination()

    /** Chat with a specific channel */
    data class Chat(
        val channelId: String,
        val channelType: ChannelType,
        val channelName: String = "",
        val readSeq: Long = 0,
        val scrollToSeq: Long = 0,
        /** For personal chats: the other user's uid, used to navigate to UserProfile */
        val otherUid: String? = null,
    ) : NavDestination()

    /** Search for users */
    data object SearchUsers : NavDestination()

    /** View another user's profile and add as friend */
    data class UserProfile(
        val uid: String,
        val backTo: NavDestination? = null,
    ) : NavDestination()

    /** Friend apply / accept list */
    data object FriendApplies : NavDestination()

    /** Create a new group chat */
    data object CreateGroup : NavDestination()

    /** Group detail / settings */
    data class GroupDetail(
        val channelId: String,
        val channelType: ChannelType,
    ) : NavDestination()

    /** Group members list */
    data class GroupMembers(
        val channelId: String,
    ) : NavDestination()

    /** Invite friends into a group */
    data class InviteMembers(
        val channelId: String,
    ) : NavDestination()

    /** Edit own profile */
    data object EditProfile : NavDestination()

    /** Change password */
    data object ChangePassword : NavDestination()

    /** Blacklist management */
    data object Blacklist : NavDestination()

    /** Device management — view online devices and kick */
    data object DeviceManagement : NavDestination()

    /** My profile / settings page (used in desktop sidebar avatar click) */
    data object Me : NavDestination()

    /** Forward a message to another conversation */
    data class Forward(
        val payload: Message,
    ) : NavDestination()

    /** Search messages globally or within a specific channel */
    data class SearchMessages(
        val channelId: String? = null,
        val channelName: String = "",
    ) : NavDestination()

    /** Group invite links management */
    data class InviteLinks(
        val channelId: String,
    ) : NavDestination()

    /** Join group via invite link */
    data class JoinByLink(
        val token: String,
    ) : NavDestination()
}

/** Bottom navigation tabs */
enum class MainTab(val label: String) {
    Conversations("Chats"),
    Contacts("Contacts"),
    Me("Me"),
}
