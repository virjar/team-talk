package com.virjar.tk.dto

import kotlinx.serialization.Serializable

@Serializable
data class ApiError(
    val code: Int = 0,
    val message: String,
    val error: String = message,
)
