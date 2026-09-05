package com.virjar.tk.shared.testkit

import com.virjar.tk.shared.client.ProjectionSnapshotLease
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.model.User

internal fun mergeFakeContact(current: List<Contact>, contact: Contact): List<Contact> {
    val list = current.toMutableList()
    val index = list.indexOfFirst { it.friendUid == contact.friendUid }
    if (index >= 0) list[index] = contact else list.add(contact)
    return list
}

internal fun projectFakeContacts(contacts: List<Contact>, users: List<User>): List<Contact> {
    val usersByUid = users.associateBy(User::uid)
    return contacts.map { contact ->
        val user = usersByUid[contact.friendUid]
        if (user != null && user != contact.user) contact.copy(user = user) else contact
    }
}

internal fun projectFakeMembers(members: List<Member>, users: List<User>): List<Member> {
    val usersByUid = users.associateBy(User::uid)
    return members.map { member ->
        val user = usersByUid[member.uid] ?: member.user
        if (user != member.user) member.copy(user = user) else member
    }
}

internal fun removeCurrentFakeLease(
    leases: MutableMap<String, ProjectionSnapshotLease>,
    lease: ProjectionSnapshotLease,
) {
    leases.entries.removeAll { (_, currentLease) -> currentLease === lease }
}
