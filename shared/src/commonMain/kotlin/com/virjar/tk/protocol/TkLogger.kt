package com.virjar.tk.protocol

interface TkLogger {
    fun log(msgProvider: () -> String, throwable: Throwable? = null)
    fun log(msgProvider: () -> String) {
        log(msgProvider, null)
    }
}