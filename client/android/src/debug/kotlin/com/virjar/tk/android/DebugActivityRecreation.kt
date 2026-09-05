package com.virjar.tk.android

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.ref.WeakReference

private const val DEBUG_LOG_TAG = "TeamTalkDebug"

/** 仅用于调试的生命周期夹具，用于复现 Activity 重建后系统选择器返回的场景。 */
class DebugActivityRecreationProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is MainActivity) {
            DebugActivityRecreationTarget.activity = WeakReference(activity)
            Log.i(DEBUG_LOG_TAG, "Observed MainActivity instance=${System.identityHashCode(activity)}")
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (DebugActivityRecreationTarget.activity.get() === activity) {
            DebugActivityRecreationTarget.activity = WeakReference(null)
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}

class DebugActivityRecreationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RECREATE_ACTIVITY) return
        val pendingResult = goAsync()
        Handler(Looper.getMainLooper()).post {
            try {
                val activity = DebugActivityRecreationTarget.activity.get()
                if (activity == null) {
                    Log.w(DEBUG_LOG_TAG, "No MainActivity is available for recreation")
                } else {
                    Log.i(DEBUG_LOG_TAG, "Recreating MainActivity instance=${System.identityHashCode(activity)}")
                    activity.recreate()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_RECREATE_ACTIVITY = "com.virjar.tk.android.DEBUG_RECREATE_ACTIVITY"
    }
}

private object DebugActivityRecreationTarget {
    var activity: WeakReference<MainActivity> = WeakReference(null)
}
