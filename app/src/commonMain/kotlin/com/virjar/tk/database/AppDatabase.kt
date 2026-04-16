package com.virjar.tk.database

expect class AppDatabase() {
    val queries: DatabaseQueries
    fun close()
}
