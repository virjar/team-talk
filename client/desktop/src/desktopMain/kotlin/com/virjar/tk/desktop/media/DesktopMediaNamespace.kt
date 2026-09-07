package com.virjar.tk.desktop.media

/** 构造与封禁清理共享同一稳定目录计算，令牌轮换不会改变账号媒体归属。 */
internal fun desktopMediaNamespace(deploymentFingerprint: String, datasetId: String, ownerUid: String): String =
    desktopSha256("teamtalk-media-v2\u0000$deploymentFingerprint\u0000dataset\u0000$datasetId\u0000uid\u0000$ownerUid")
