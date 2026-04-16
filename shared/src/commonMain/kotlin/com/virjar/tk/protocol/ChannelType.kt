package com.virjar.tk.protocol

enum class ChannelType(val code: Int) {
    PERSONAL(1),
    GROUP(2),
    SYSTEM(3);

    companion object {
        fun fromCode(code: Int): ChannelType =
            entries.find { it.code == code } ?: PERSONAL
    }
}
