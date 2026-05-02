package com.virjar.tk.database

expect class AppDatabase(uid: String) {
    val queries: DatabaseQueries
    fun close()
}
