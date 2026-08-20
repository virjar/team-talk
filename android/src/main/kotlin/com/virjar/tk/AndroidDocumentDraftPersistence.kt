package com.virjar.tk

import android.content.Context
import android.util.AtomicFile
import com.virjar.tk.navigation.feature.DocumentDraftPersistence
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Private, no-backup storage for unsaved document bodies.
 *
 * AtomicFile writes through a temporary file and renames only after the complete UTF-8 payload
 * is synced. A uid hash is used as the filename, so account identifiers never become paths.
 */
internal class AndroidDocumentDraftPersistence(context: Context) : DocumentDraftPersistence {
    private val appContext = context.applicationContext
    private val directory = File(appContext.noBackupFilesDir, DIRECTORY_NAME)
    private val ownerPreferences = appContext.getSharedPreferences(OWNER_PREFERENCES, Context.MODE_PRIVATE)
    private val stateLock = Any()
    private val ioLock = Any()
    private val pendingWrites = mutableMapOf<String, PendingWrite>()
    private val uidGenerations = mutableMapOf<String, Long>()
    private var nextGeneration = 0L
    private var drainScheduled = false
    @Volatile
    private var selectedOwnerHash: String? = null
    private val executor = ThreadPoolExecutor(
        0,
        1,
        5,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
        ThreadFactory { task -> Thread(task, "document-draft-io").apply { isDaemon = true } },
    )

    override fun read(uid: String): String? {
        flush()
        return synchronized(ioLock) {
            selectOwnerLocked(uid)
            val base = fileFor(uid)
            if (!base.exists()) return@synchronized null
            val atomicFile = AtomicFile(base)
            if (base.length() > MAX_PAYLOAD_BYTES) {
                atomicFile.delete()
                return@synchronized null
            }
            try {
                val bytes = atomicFile.readFully()
                if (bytes.size > MAX_PAYLOAD_BYTES) {
                    atomicFile.delete()
                    null
                } else {
                    bytes.toString(Charsets.UTF_8)
                }
            } catch (_: Exception) {
                // A truncated/corrupt draft is not safe to deserialize and must not poison login.
                atomicFile.delete()
                null
            }
        }
    }

    override fun write(uid: String, payload: () -> String): Boolean {
        ensureOwner(uid)
        synchronized(stateLock) {
            val generation = ++nextGeneration
            uidGenerations[uid] = generation
            pendingWrites[uid] = PendingWrite(uid, generation, payload)
            if (!drainScheduled) {
                drainScheduled = true
                executor.execute(::drainWrites)
            }
        }
        return true
    }

    override fun delete(uid: String): Boolean = synchronized(ioLock) {
        synchronized(stateLock) {
            uidGenerations[uid] = ++nextGeneration
            pendingWrites.remove(uid)
        }
        val atomicFile = AtomicFile(fileFor(uid))
        try {
            atomicFile.delete()
            if (ownerPreferences.getString(ACTIVE_OWNER_KEY, null) == draftFileName(uid)) {
                ownerPreferences.edit().remove(ACTIVE_OWNER_KEY).commit()
                selectedOwnerHash = null
            }
            !atomicFile.baseFile.exists()
        } catch (_: Exception) {
            false
        }
    }

    override fun clearAll(): Boolean = synchronized(ioLock) {
        synchronized(stateLock) {
            nextGeneration++
            uidGenerations.clear()
            pendingWrites.clear()
        }
        try {
            val filesCleared = clearDraftFiles()
            val ownerCleared = ownerPreferences.edit().remove(ACTIVE_OWNER_KEY).commit()
            selectedOwnerHash = null
            filesCleared && ownerCleared
        } catch (_: Exception) {
            false
        }
    }

    override fun flush(): Boolean = try {
        executor.submit { }.get(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        true
    } catch (_: Exception) {
        false
    }

    private fun fileFor(uid: String): File = File(directory, draftFileName(uid))

    /** One installation retains drafts for at most the currently active uid. */
    private fun ensureOwner(uid: String) {
        val requested = draftFileName(uid)
        if (selectedOwnerHash == requested) return
        synchronized(ioLock) {
            if (selectedOwnerHash != requested) selectOwnerLocked(uid)
        }
    }

    private fun selectOwnerLocked(uid: String) {
        val requested = draftFileName(uid)
        val current = ownerPreferences.getString(ACTIVE_OWNER_KEY, null)
        if (current != null && current != requested) clearDraftFiles()
        if (current != requested) ownerPreferences.edit().putString(ACTIVE_OWNER_KEY, requested).commit()
        selectedOwnerHash = requested
    }

    private fun clearDraftFiles(): Boolean {
        if (!directory.exists()) return true
        directory.listFiles().orEmpty().forEach(File::delete)
        directory.delete()
        return !directory.exists()
    }

    private fun drainWrites() {
        while (true) {
            val pending = synchronized(stateLock) {
                val entry = pendingWrites.entries.firstOrNull()
                if (entry == null) {
                    drainScheduled = false
                    null
                } else {
                    pendingWrites.remove(entry.key)
                    entry.value
                }
            } ?: return

            val payload = try {
                pending.payload()
            } catch (_: Exception) {
                continue
            }
            val bytes = payload.toByteArray(Charsets.UTF_8)
            synchronized(ioLock) {
                val isCurrent = synchronized(stateLock) {
                    uidGenerations[pending.uid] == pending.generation
                }
                if (!isCurrent) return@synchronized

                val atomicFile = AtomicFile(fileFor(pending.uid))
                if (bytes.size > MAX_PAYLOAD_BYTES) {
                    // Never keep an older snapshot that could masquerade as the current draft.
                    atomicFile.delete()
                } else {
                    writeAtomic(atomicFile, bytes)
                }
            }
        }
    }

    private fun writeAtomic(atomicFile: AtomicFile, bytes: ByteArray): Boolean {
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) return false
        var stream: FileOutputStream? = null
        return try {
            val output = atomicFile.startWrite()
            stream = output
            output.write(bytes)
            atomicFile.finishWrite(output)
            true
        } catch (_: Exception) {
            stream?.let { output ->
                try {
                    atomicFile.failWrite(output)
                } catch (_: Exception) {
                    // The original failure is sufficient; the next read also validates payload.
                }
            }
            false
        }
    }

    private data class PendingWrite(
        val uid: String,
        val generation: Long,
        val payload: () -> String,
    )

    companion object {
        /** Existing + draft Markdown can each reach 4 MiB UTF-8; leave room for JSON metadata. */
        internal const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024
        private const val DIRECTORY_NAME = "document-drafts-v1"
        private const val OWNER_PREFERENCES = "teamtalk_document_drafts"
        private const val ACTIVE_OWNER_KEY = "active_owner_hash"
        private const val FLUSH_TIMEOUT_SECONDS = 5L

        internal fun draftFileName(uid: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
            return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) } + ".json"
        }
    }
}
