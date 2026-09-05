/*
 * JNI registration bridge derived from ComposeMediaPlayer.
 * Copyright (c) 2025 Elie G.
 * SPDX-License-Identifier: MIT
 *
 * The Java/Kotlin contract intentionally remains byte-for-byte compatible with
 * ComposeMediaPlayer 0.9.0's MacNativeBridge.
 */

#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>

extern void* createVideoPlayer(void);
extern int32_t openLocalFile(void* context, const char* uri, char** error_out);
extern void playVideo(void* context);
extern void pauseVideo(void* context);
extern void setVolume(void* context, float volume);
extern float getVolume(void* context);
extern void seekTo(void* context, double time);
extern void disposeVideoPlayer(void* context);
extern void setPlaybackSpeed(void* context, float speed);
extern float getPlaybackSpeed(void* context);
extern void* lockLatestFrame(void* context, int32_t* out_info);
extern void unlockLatestFrame(void* context);
extern int32_t getFrameWidth(void* context);
extern int32_t getFrameHeight(void* context);
extern int32_t setOutputSize(void* context, int32_t width, int32_t height);
extern float getVideoFrameRate(void* context);
extern float getScreenRefreshRate(void* context);
extern float getCaptureFrameRate(void* context);
extern double getVideoDuration(void* context);
extern double getCurrentTime(void* context);
extern const char* getVideoTitle(void* context);
extern int64_t getVideoBitrate(void* context);
extern const char* getVideoMimeType(void* context);
extern int32_t getAudioChannels(void* context);
extern int32_t getAudioSampleRate(void* context);
extern int32_t consumeDidPlayToEnd(void* context);

/*
 * Kotlin clears its AtomicLong before calling dispose, but a worker can already
 * have observed the old value and enter JNI later. A raw Swift pointer therefore
 * cannot be a safe jlong handle. The registry gives every player a monotonic ID
 * and keeps its owner alive until all admitted calls and cross-JNI frame leases
 * have drained.
 */
typedef struct player_entry {
    uint64_t id;
    void* swift_player;
    uint32_t active_calls;
    uint32_t frame_leases;
    bool disposing;
    pthread_cond_t drained;
    struct player_entry* next;
} player_entry;

typedef struct player_call {
    player_entry* entry;
    void* swift_player;
} player_call;

static pthread_mutex_t registry_mutex = PTHREAD_MUTEX_INITIALIZER;
static player_entry* registry_head = NULL;
static uint64_t next_player_id = 1;

static player_entry* find_entry_locked(uint64_t id) {
    player_entry* current = registry_head;
    while (current != NULL) {
        if (current->id == id) return current;
        current = current->next;
    }
    return NULL;
}

static jlong register_player(void* swift_player) {
    if (swift_player == NULL) return 0L;
    player_entry* entry = (player_entry*)calloc(1, sizeof(player_entry));
    if (entry == NULL) return 0L;
    if (pthread_cond_init(&entry->drained, NULL) != 0) {
        free(entry);
        return 0L;
    }

    pthread_mutex_lock(&registry_mutex);
    /* IDs are never reused during a realistic process lifetime. Zero stays invalid. */
    uint64_t candidate = next_player_id++;
    if (candidate == 0 || candidate > (uint64_t)INT64_MAX) {
        next_player_id = 2;
        candidate = 1;
        while (find_entry_locked(candidate) != NULL) candidate++;
    }
    entry->id = candidate;
    entry->swift_player = swift_player;
    entry->next = registry_head;
    registry_head = entry;
    pthread_mutex_unlock(&registry_mutex);
    return (jlong)candidate;
}

static player_call acquire_player(jlong handle) {
    player_call call = {NULL, NULL};
    if (handle <= 0L) return call;

    pthread_mutex_lock(&registry_mutex);
    player_entry* entry = find_entry_locked((uint64_t)handle);
    if (entry != NULL && !entry->disposing && entry->swift_player != NULL) {
        entry->active_calls++;
        call.entry = entry;
        call.swift_player = entry->swift_player;
    }
    pthread_mutex_unlock(&registry_mutex);
    return call;
}

static void release_player(player_call* call) {
    if (call == NULL || call->entry == NULL) return;
    pthread_mutex_lock(&registry_mutex);
    if (call->entry->active_calls > 0) call->entry->active_calls--;
    if (call->entry->disposing &&
        call->entry->active_calls == 0 &&
        call->entry->frame_leases == 0) {
        pthread_cond_broadcast(&call->entry->drained);
    }
    pthread_mutex_unlock(&registry_mutex);
    call->entry = NULL;
    call->swift_player = NULL;
}

/* Converts an admitted lock call into ownership spanning nLockFrame/nUnlockFrame. */
static void promote_to_frame_lease(player_call* call) {
    if (call == NULL || call->entry == NULL) return;
    pthread_mutex_lock(&registry_mutex);
    call->entry->frame_leases++;
    if (call->entry->active_calls > 0) call->entry->active_calls--;
    pthread_mutex_unlock(&registry_mutex);
    call->entry = NULL;
    call->swift_player = NULL;
}

/*
 * Unlock is the only operation admitted for a retiring entry. The frame lease
 * itself keeps the Swift owner and entry alive while this function drops the
 * registry mutex to call Swift, so dispose can wait without blocking unlock.
 */
static bool release_frame_lease(jlong handle) {
    if (handle <= 0L) return false;
    pthread_mutex_lock(&registry_mutex);
    player_entry* entry = find_entry_locked((uint64_t)handle);
    if (entry == NULL || entry->frame_leases == 0 || entry->swift_player == NULL) {
        pthread_mutex_unlock(&registry_mutex);
        return false;
    }
    /* Reserve the sole lease before dropping the mutex; a duplicate unlock now no-ops. */
    entry->frame_leases--;
    entry->active_calls++;
    void* swift_player = entry->swift_player;
    pthread_mutex_unlock(&registry_mutex);

    unlockLatestFrame(swift_player);

    pthread_mutex_lock(&registry_mutex);
    if (entry->active_calls > 0) entry->active_calls--;
    if (entry->disposing && entry->active_calls == 0 && entry->frame_leases == 0) {
        pthread_cond_broadcast(&entry->drained);
    }
    pthread_mutex_unlock(&registry_mutex);
    return true;
}

/* First disposer owns retirement; duplicate/late dispose is an idempotent no-op. */
static void* retire_player(jlong handle) {
    if (handle <= 0L) return NULL;
    pthread_mutex_lock(&registry_mutex);
    player_entry* entry = find_entry_locked((uint64_t)handle);
    if (entry == NULL || entry->disposing) {
        pthread_mutex_unlock(&registry_mutex);
        return NULL;
    }
    entry->disposing = true;
    while (entry->active_calls != 0 || entry->frame_leases != 0) {
        pthread_cond_wait(&entry->drained, &registry_mutex);
    }

    player_entry** link = &registry_head;
    while (*link != NULL && *link != entry) link = &(*link)->next;
    if (*link == entry) *link = entry->next;
    void* swift_player = entry->swift_player;
    entry->swift_player = NULL;
    pthread_mutex_unlock(&registry_mutex);

    pthread_cond_destroy(&entry->drained);
    free(entry);
    return swift_player;
}

static void throw_java(JNIEnv* env, const char* class_name, const char* message) {
    jclass exception_class = (*env)->FindClass(env, class_name);
    if (exception_class != NULL) {
        (*env)->ThrowNew(env, exception_class, message != NULL ? message : "Native video operation failed");
    }
}

static jlong JNICALL jni_create_player(JNIEnv* env, jclass cls) {
    (void)env;
    (void)cls;
    void* swift_player = createVideoPlayer();
    jlong handle = register_player(swift_player);
    if (handle == 0L && swift_player != NULL) disposeVideoPlayer(swift_player);
    return handle;
}

static void JNICALL jni_open_uri(JNIEnv* env, jclass cls, jlong handle, jstring uri) {
    (void)cls;
    if (uri == NULL) {
        throw_java(env, "java/lang/IllegalArgumentException", "Local video source is required");
        return;
    }
    player_call call = acquire_player(handle);
    if (call.entry == NULL) return;

    const char* source = (*env)->GetStringUTFChars(env, uri, NULL);
    if (source == NULL) {
        release_player(&call);
        return;
    }
    char* error_message = NULL;
    int32_t status = openLocalFile(call.swift_player, source, &error_message);
    (*env)->ReleaseStringUTFChars(env, uri, source);
    release_player(&call);
    if (status != 0) {
        const char* exception_class = status == 1 ? "java/lang/IllegalArgumentException" : "java/io/IOException";
        throw_java(env, exception_class, error_message);
    }
    free(error_message);
}

static void JNICALL jni_play(JNIEnv* env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    player_call call = acquire_player(handle);
    if (call.entry != NULL) playVideo(call.swift_player);
    release_player(&call);
}

static void JNICALL jni_pause(JNIEnv* env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    player_call call = acquire_player(handle);
    if (call.entry != NULL) pauseVideo(call.swift_player);
    release_player(&call);
}

static void JNICALL jni_set_volume(JNIEnv* env, jclass cls, jlong handle, jfloat volume) {
    (void)env; (void)cls;
    player_call call = acquire_player(handle);
    if (call.entry != NULL) setVolume(call.swift_player, (float)volume);
    release_player(&call);
}

static jfloat JNICALL jni_get_volume(JNIEnv* env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    player_call call = acquire_player(handle);
    jfloat result = call.entry != NULL ? getVolume(call.swift_player) : 0.0f;
    release_player(&call);
    return result;
}

static void JNICALL jni_seek_to(JNIEnv* env, jclass cls, jlong handle, jdouble time) {
    (void)env; (void)cls;
    player_call call = acquire_player(handle);
    if (call.entry != NULL) seekTo(call.swift_player, (double)time);
    release_player(&call);
}

static void JNICALL jni_dispose_player(JNIEnv* env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    void* swift_player = retire_player(handle);
    if (swift_player != NULL) disposeVideoPlayer(swift_player);
}

static void JNICALL jni_set_playback_speed(JNIEnv* env, jclass cls, jlong handle, jfloat speed) {
    (void)env; (void)cls;
    player_call call = acquire_player(handle);
    if (call.entry != NULL) setPlaybackSpeed(call.swift_player, (float)speed);
    release_player(&call);
}

static jfloat JNICALL jni_get_playback_speed(JNIEnv* env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    player_call call = acquire_player(handle);
    jfloat result = call.entry != NULL ? getPlaybackSpeed(call.swift_player) : 1.0f;
    release_player(&call);
    return result;
}

static jlong JNICALL jni_lock_frame(JNIEnv* env, jclass cls, jlong handle, jintArray out_info) {
    (void)cls;
    if (out_info == NULL || (*env)->GetArrayLength(env, out_info) < 3) return 0L;
    player_call call = acquire_player(handle);
    if (call.entry == NULL) return 0L;

    int32_t native_info[3] = {0, 0, 0};
    void* address = lockLatestFrame(call.swift_player, native_info);
    if (address == NULL) {
        release_player(&call);
        return 0L;
    }
    (*env)->SetIntArrayRegion(env, out_info, 0, 3, (const jint*)native_info);
    if ((*env)->ExceptionCheck(env)) {
        unlockLatestFrame(call.swift_player);
        release_player(&call);
        return 0L;
    }
    promote_to_frame_lease(&call);
    return (jlong)(uintptr_t)address;
}

static void JNICALL jni_unlock_frame(JNIEnv* env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    release_frame_lease(handle);
}

static jobject JNICALL jni_wrap_pointer(JNIEnv* env, jclass cls, jlong address, jlong size) {
    (void)cls;
    if (address == 0L || size <= 0L) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)(uintptr_t)(uint64_t)address, size);
}

static jint JNICALL jni_get_frame_width(JNIEnv* env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    player_call call = acquire_player(handle);
    jint result = call.entry != NULL ? (jint)getFrameWidth(call.swift_player) : 0;
    release_player(&call);
    return result;
}

static jint JNICALL jni_get_frame_height(JNIEnv* env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    player_call call = acquire_player(handle);
    jint result = call.entry != NULL ? (jint)getFrameHeight(call.swift_player) : 0;
    release_player(&call);
    return result;
}

static jint JNICALL jni_set_output_size(JNIEnv* env, jclass cls, jlong handle, jint width, jint height) {
    (void)env; (void)cls;
    player_call call = acquire_player(handle);
    jint result = call.entry != NULL
        ? (jint)setOutputSize(call.swift_player, (int32_t)width, (int32_t)height)
        : 0;
    release_player(&call);
    return result;
}

#define FLOAT_GETTER(jni_name, swift_name)                                      \
    static jfloat JNICALL jni_name(JNIEnv* env, jclass cls, jlong handle) {     \
        (void)env; (void)cls;                                                   \
        player_call call = acquire_player(handle);                              \
        jfloat result = call.entry != NULL ? swift_name(call.swift_player) : 0; \
        release_player(&call);                                                  \
        return result;                                                          \
    }

FLOAT_GETTER(jni_get_video_frame_rate, getVideoFrameRate)
FLOAT_GETTER(jni_get_screen_refresh_rate, getScreenRefreshRate)
FLOAT_GETTER(jni_get_capture_frame_rate, getCaptureFrameRate)

static jdouble JNICALL jni_get_video_duration(JNIEnv* env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    player_call call = acquire_player(handle);
    jdouble result = call.entry != NULL ? getVideoDuration(call.swift_player) : 0;
    release_player(&call);
    return result;
}

static jdouble JNICALL jni_get_current_time(JNIEnv* env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    player_call call = acquire_player(handle);
    jdouble result = call.entry != NULL ? getCurrentTime(call.swift_player) : 0;
    release_player(&call);
    return result;
}

static jstring JNICALL jni_get_video_title(JNIEnv* env, jclass cls, jlong handle) {
    (void)cls;
    player_call call = acquire_player(handle);
    const char* value = call.entry != NULL ? getVideoTitle(call.swift_player) : NULL;
    release_player(&call);
    if (value == NULL) return NULL;
    jstring result = (*env)->NewStringUTF(env, value);
    free((void*)value);
    return result;
}

static jlong JNICALL jni_get_video_bitrate(JNIEnv* env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    player_call call = acquire_player(handle);
    jlong result = call.entry != NULL ? (jlong)getVideoBitrate(call.swift_player) : 0L;
    release_player(&call);
    return result;
}

static jstring JNICALL jni_get_video_mime_type(JNIEnv* env, jclass cls, jlong handle) {
    (void)cls;
    player_call call = acquire_player(handle);
    const char* value = call.entry != NULL ? getVideoMimeType(call.swift_player) : NULL;
    release_player(&call);
    if (value == NULL) return NULL;
    jstring result = (*env)->NewStringUTF(env, value);
    free((void*)value);
    return result;
}

#define INT_GETTER(jni_name, swift_name)                                      \
    static jint JNICALL jni_name(JNIEnv* env, jclass cls, jlong handle) {     \
        (void)env; (void)cls;                                                 \
        player_call call = acquire_player(handle);                            \
        jint result = call.entry != NULL ? (jint)swift_name(call.swift_player) : 0; \
        release_player(&call);                                                \
        return result;                                                        \
    }

INT_GETTER(jni_get_audio_channels, getAudioChannels)
INT_GETTER(jni_get_audio_sample_rate, getAudioSampleRate)

static jboolean JNICALL jni_consume_did_play_to_end(JNIEnv* env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    player_call call = acquire_player(handle);
    jboolean result = call.entry != NULL && consumeDidPlayToEnd(call.swift_player) != 0
        ? JNI_TRUE
        : JNI_FALSE;
    release_player(&call);
    return result;
}

static const JNINativeMethod METHODS[] = {
    {"nCreatePlayer", "()J", (void*)jni_create_player},
    {"nOpenUri", "(JLjava/lang/String;)V", (void*)jni_open_uri},
    {"nPlay", "(J)V", (void*)jni_play},
    {"nPause", "(J)V", (void*)jni_pause},
    {"nSetVolume", "(JF)V", (void*)jni_set_volume},
    {"nGetVolume", "(J)F", (void*)jni_get_volume},
    {"nSeekTo", "(JD)V", (void*)jni_seek_to},
    {"nDisposePlayer", "(J)V", (void*)jni_dispose_player},
    {"nSetPlaybackSpeed", "(JF)V", (void*)jni_set_playback_speed},
    {"nGetPlaybackSpeed", "(J)F", (void*)jni_get_playback_speed},
    {"nLockFrame", "(J[I)J", (void*)jni_lock_frame},
    {"nUnlockFrame", "(J)V", (void*)jni_unlock_frame},
    {"nWrapPointer", "(JJ)Ljava/nio/ByteBuffer;", (void*)jni_wrap_pointer},
    {"nGetFrameWidth", "(J)I", (void*)jni_get_frame_width},
    {"nGetFrameHeight", "(J)I", (void*)jni_get_frame_height},
    {"nSetOutputSize", "(JII)I", (void*)jni_set_output_size},
    {"nGetVideoFrameRate", "(J)F", (void*)jni_get_video_frame_rate},
    {"nGetScreenRefreshRate", "(J)F", (void*)jni_get_screen_refresh_rate},
    {"nGetCaptureFrameRate", "(J)F", (void*)jni_get_capture_frame_rate},
    {"nGetVideoDuration", "(J)D", (void*)jni_get_video_duration},
    {"nGetCurrentTime", "(J)D", (void*)jni_get_current_time},
    {"nGetVideoTitle", "(J)Ljava/lang/String;", (void*)jni_get_video_title},
    {"nGetVideoBitrate", "(J)J", (void*)jni_get_video_bitrate},
    {"nGetVideoMimeType", "(J)Ljava/lang/String;", (void*)jni_get_video_mime_type},
    {"nGetAudioChannels", "(J)I", (void*)jni_get_audio_channels},
    {"nGetAudioSampleRate", "(J)I", (void*)jni_get_audio_sample_rate},
    {"nConsumeDidPlayToEnd", "(J)Z", (void*)jni_consume_did_play_to_end},
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;

    jclass bridge_class = (*env)->FindClass(
        env,
        "io/github/kdroidfilter/composemediaplayer/mac/MacNativeBridge"
    );
    if (bridge_class == NULL) return JNI_ERR;

    const jint method_count = (jint)(sizeof(METHODS) / sizeof(METHODS[0]));
    if ((*env)->RegisterNatives(env, bridge_class, METHODS, method_count) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
