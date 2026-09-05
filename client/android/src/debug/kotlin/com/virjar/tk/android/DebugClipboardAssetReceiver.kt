package com.virjar.tk.android

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.File

/** 仅用于调试的夹具，用于在真机上可重复地接受剪贴板 URI。 */
class DebugClipboardAssetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_IMAGE) return
        val file = File(context.cacheDir, "teamtalk-media/attachments/debug-clipboard.png")
        file.parentFile?.mkdirs()
        file.writeBytes(Base64.decode(DEBUG_IMAGE_PNG, Base64.DEFAULT))
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newUri(context.contentResolver, "TeamTalk debug image", uri),
        )
    }

    companion object {
        const val ACTION_SET_IMAGE = "com.virjar.tk.android.DEBUG_SET_CLIPBOARD_IMAGE"

        private const val DEBUG_IMAGE_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAIAAAAlC+aJAAAA/klEQVR42u2aSw7CMAxE01HvRG9Fj8KxuBXsACFoaGKntnhesajEvMjjT9rpdL6VzKGSPAAAAAAAAAAAAAD+GWD+/dHrZaiyZSWFAAjmgYYE3RsNNiOFAOgs0HMQ9c0Mynv2eCDA8R8JYDVZKY76tuao1OqjmLhnMNHhx985VmmkFz1WIg1rorapbwDwKqjK4KS+C+Dt7/emh9VGIcPq8Y3B3LiWJq4yeN9lyLyKV71hu47KoxNtVCfzZVpO3XSMeuNOvK3P6SJDcaaaKLPQRwY/MPlNl8v6/JFvpXyI9k4qlvqS83J38MsOUggAv5j45AwAAAAAAAAAAAAgb9wBwJg/0VNNYxQAAAAASUVORK5CYII="
    }
}
