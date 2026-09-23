package com.virjar.tk.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** 判定一个附件/文件是否按 APK 安装处理；扩展名与 MIME 任一命中即可。 */
internal fun looksLikeApk(fileName: String, contentType: String): Boolean =
    fileName.endsWith(".apk", ignoreCase = true) ||
        contentType.equals(AndroidApkInstaller.APK_MIME, ignoreCase = true)

/**
 * APK 安装边界（应用内升级与聊天 APK 消息共用）：统一经 FileProvider content:// URI
 * 拉起系统安装器，并在「安装未知应用」授权缺失时先把用户引到系统开关。
 * Android 8+ 起安装行为受该开关控制；系统引导一次后长期有效。
 */
internal object AndroidApkInstaller {
    const val APK_MIME = "application/vnd.android.package-archive"

    fun canRequestInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** 本应用「安装未知应用」系统开关入口；API 26 之前没有该开关，返回 null。 */
    fun installPermissionSettingsIntent(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            null
        }

    /** 打开本应用「安装未知应用」系统开关；返回 false 表示系统无此入口（老版本）。 */
    fun openInstallPermissionSettings(context: Context): Boolean {
        val intent = installPermissionSettingsIntent(context) ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** 直接拉起系统安装器；调用方应先用 [canRequestInstall] 确认授权，避免死路弹窗。 */
    fun install(context: Context, file: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (fileOpenRequiresNewTask(context.containsActivity())) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    enum class InstallOutcome { STARTED, NEED_PERMISSION, FAILED }

    /**
     * 安装一步式入口：已授权直接安装；未授权先跳系统开关并给出提示，
     * 用户开启返回后重试同一入口即可（聊天卡片场景无 Activity Result 回调可依赖）。
     */
    fun installOrGuide(context: Context, file: File, fileNameHint: String): InstallOutcome = when {
        canRequestInstall(context) ->
            if (install(context, file)) InstallOutcome.STARTED else InstallOutcome.FAILED
        openInstallPermissionSettings(context) -> {
            Toast.makeText(
                context,
                "安装 $fileNameHint 需先允许 TeamTalk「安装未知应用」，开启后重新点按即可安装",
                Toast.LENGTH_LONG,
            ).show()
            InstallOutcome.NEED_PERMISSION
        }
        else -> InstallOutcome.FAILED
    }
}
