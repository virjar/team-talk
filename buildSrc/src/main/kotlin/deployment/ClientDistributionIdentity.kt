package deployment

import java.util.Locale
import java.util.UUID

/** 安装身份与展示名称分开：改名、换服务器或升级版本都不能把既有资料变成另一份安装。 */
data class ClientDistributionIdentity(
    val applicationId: String = "com.virjar.tk",
    val displayName: String = "TeamTalk",
    val desktopName: String = "TeamTalk",
    /** Final Android installation ID; omitted values preserve the established deployment convention. */
    val androidApplicationId: String = "$applicationId.android",
) {
    init {
        require(applicationId.length <= 128 && applicationId.split('.').all { it.length <= 63 } &&
            applicationId.matches(Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+"))) {
            "client.applicationId must be a lower-case reverse-DNS identifier, for example com.example.teamtalk"
        }
        require(displayName.isNotBlank() && displayName == displayName.trim() &&
            displayName.length <= 80 && displayName.none(Char::isISOControl)) {
            "client.displayName must be a non-blank, single-line name of at most 80 characters"
        }
        require(androidApplicationId.length <= 256 && androidApplicationId.split('.').all { it.length <= 63 } &&
            androidApplicationId.matches(Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+"))) {
            "client.androidApplicationId must be a lower-case reverse-DNS Android installation identifier"
        }
        require(desktopName.matches(Regex("[A-Za-z][A-Za-z0-9]{0,47}")) &&
            !desktopName.matches(Regex("(?i:con|prn|aux|nul|com[0-9]|lpt[0-9])"))) {
            "client.desktopName must be a portable English installation name (letters and digits, starting with a letter)"
        }
        require((applicationId == "com.virjar.tk") == desktopName.equals("TeamTalk", ignoreCase = true)) {
            "A private client must change both applicationId and desktopName; TeamTalk is reserved for the public installation"
        }
        require(applicationId != "com.virjar.tk" || desktopName == "TeamTalk") {
            "Keep the public desktopName exactly TeamTalk to preserve installed applications"
        }
        require((applicationId == "com.virjar.tk") == (androidApplicationId == "com.virjar.tk.android")) {
            "Keep com.virjar.tk.android reserved for the public installation; private clients need their own Android ID"
        }
    }

    val desktopFsName: String get() = desktopName.lowercase(Locale.ROOT)
    val desktopDataDirectoryName: String get() = if (applicationId == "com.virjar.tk") "TeamTalk" else applicationId
    val linuxDataDirectoryName: String get() = if (applicationId == "com.virjar.tk") "teamtalk" else applicationId
    val windowsUpgradeUuid: String get() = if (applicationId == "com.virjar.tk") {
        "d5e8f9a0-1b2c-3d4e-5f6a-7b8c9d0e1f2a"
    } else {
        UUID.nameUUIDFromBytes("teamtalk:windows-installer:$applicationId".toByteArray(Charsets.UTF_8)).toString()
    }
}
