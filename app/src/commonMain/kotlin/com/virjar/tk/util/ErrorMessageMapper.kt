package com.virjar.tk.util

fun Throwable.toUserMessage(): String = toAppError().message
