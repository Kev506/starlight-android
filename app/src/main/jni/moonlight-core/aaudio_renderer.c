
/**
 * With thanks to olibols
 * Created by https://github.com/olibols
 * Updated by Kevin Andrews on 11/07/2026.
 * Added multi-channel support.
 * Added AAudio <-> AudioTrack hot swap for bluetooth devices.
 */

#include <aaudio/AAudio.h>
#include <android/api-level.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "StarlightAAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef AAUDIO_USAGE_GAME
#define AAUDIO_USAGE_GAME 14
#endif

#ifndef AAUDIO_CONTENT_TYPE_MUSIC
#define AAUDIO_CONTENT_TYPE_MUSIC 2
#endif

#ifndef AAUDIO_CHANNEL_5POINT1
#define AAUDIO_CHANNEL_5POINT1 0x0000003F
#endif

#ifndef AAUDIO_CHANNEL_7POINT1
#define AAUDIO_CHANNEL_7POINT1 0x000000FF
#endif

#ifndef AAUDIO_CHANNEL_STEREO
#define AAUDIO_CHANNEL_STEREO 0x00000003
#endif

typedef aaudio_result_t (*AAudio_createStreamBuilder_fn)(AAudioStreamBuilder **builder);

typedef void (*AAudioStreamBuilder_delete_fn)(AAudioStreamBuilder *builder);

typedef aaudio_result_t (*AAudioStreamBuilder_openStream_fn)(AAudioStreamBuilder *builder,
                                                             AAudioStream **stream);

typedef void (*AAudioStreamBuilder_setDirection_fn)(AAudioStreamBuilder *builder,
                                                    aaudio_direction_t direction);

typedef void (*AAudioStreamBuilder_setChannelMask_fn)(AAudioStreamBuilder *builder,
                                                      aaudio_channel_mask_t channelMask);

typedef void (*AAudioStreamBuilder_setFormat_fn)(AAudioStreamBuilder *builder,
                                                 aaudio_format_t format);

typedef void (*AAudioStreamBuilder_setSampleRate_fn)(AAudioStreamBuilder *builder,
                                                     int32_t sampleRate);

typedef void (*AAudioStreamBuilder_setChannelCount_fn)(AAudioStreamBuilder *builder,
                                                       int32_t channelCount);

typedef void (*AAudioStreamBuilder_setPerformanceMode_fn)(AAudioStreamBuilder *builder,
                                                          aaudio_performance_mode_t mode);

typedef void (*AAudioStreamBuilder_setSharingMode_fn)(AAudioStreamBuilder *builder,
                                                      aaudio_sharing_mode_t mode);

typedef void (*AAudioStreamBuilder_setDataCallback_fn)(AAudioStreamBuilder *builder,
                                                       AAudioStream_dataCallback callback,
                                                       void *userData);

typedef void (*AAudioStreamBuilder_setUsage_fn)(AAudioStreamBuilder *builder, aaudio_usage_t usage);

typedef void (*AAudioStreamBuilder_setContentType_fn)(AAudioStreamBuilder *builder,
                                                      aaudio_content_type_t contentType);

typedef aaudio_result_t (*AAudioStream_requestStart_fn)(AAudioStream *stream);

typedef aaudio_result_t (*AAudioStream_requestStop_fn)(AAudioStream *stream);

typedef aaudio_result_t (*AAudioStream_close_fn)(AAudioStream *stream);

typedef int32_t (*AAudioStream_getFramesPerBurst_fn)(AAudioStream *stream);

typedef int32_t (*AAudioStream_getBufferCapacityInFrames_fn)(AAudioStream *stream);

typedef int32_t (*AAudioStream_getBufferSizeInFrames_fn)(AAudioStream *stream);

typedef int32_t (*AAudioStream_setBufferSizeInFrames_fn)(AAudioStream *stream, int32_t numFrames);

typedef aaudio_performance_mode_t (*AAudioStream_getPerformanceMode_fn)(AAudioStream *stream);

typedef aaudio_sharing_mode_t (*AAudioStream_getSharingMode_fn)(AAudioStream *stream);

typedef int32_t (*AAudioStream_getChannelCount_fn)(AAudioStream *stream);

typedef int32_t (*AAudioStream_getSampleRate_fn)(AAudioStream *stream);

typedef aaudio_stream_state_t (*AAudioStream_getState_fn)(AAudioStream *stream);

typedef const char *(*AAudio_convertResultToText_fn)(aaudio_result_t returnCode);

static void *aaudioLib;
static bool symbolsLoaded;
static AAudio_createStreamBuilder_fn p_AAudio_createStreamBuilder;
static AAudioStreamBuilder_delete_fn p_AAudioStreamBuilder_delete;
static AAudioStreamBuilder_openStream_fn p_AAudioStreamBuilder_openStream;
static AAudioStreamBuilder_setDirection_fn p_AAudioStreamBuilder_setDirection;
static AAudioStreamBuilder_setChannelMask_fn p_AAudioStreamBuilder_setChannelMask;
static AAudioStreamBuilder_setFormat_fn p_AAudioStreamBuilder_setFormat;
static AAudioStreamBuilder_setSampleRate_fn p_AAudioStreamBuilder_setSampleRate;
static AAudioStreamBuilder_setChannelCount_fn p_AAudioStreamBuilder_setChannelCount;
static AAudioStreamBuilder_setPerformanceMode_fn p_AAudioStreamBuilder_setPerformanceMode;
static AAudioStreamBuilder_setSharingMode_fn p_AAudioStreamBuilder_setSharingMode;
static AAudioStreamBuilder_setDataCallback_fn p_AAudioStreamBuilder_setDataCallback;
static AAudioStreamBuilder_setUsage_fn p_AAudioStreamBuilder_setUsage;
static AAudioStreamBuilder_setContentType_fn p_AAudioStreamBuilder_setContentType;
static AAudioStream_requestStart_fn p_AAudioStream_requestStart;
static AAudioStream_requestStop_fn p_AAudioStream_requestStop;
static AAudioStream_close_fn p_AAudioStream_close;
static AAudioStream_getFramesPerBurst_fn p_AAudioStream_getFramesPerBurst;
static AAudioStream_getBufferCapacityInFrames_fn p_AAudioStream_getBufferCapacityInFrames;
static AAudioStream_getBufferSizeInFrames_fn p_AAudioStream_getBufferSizeInFrames;
static AAudioStream_setBufferSizeInFrames_fn p_AAudioStream_setBufferSizeInFrames;
static AAudioStream_getPerformanceMode_fn p_AAudioStream_getPerformanceMode;
static AAudioStream_getSharingMode_fn p_AAudioStream_getSharingMode;
static AAudioStream_getChannelCount_fn p_AAudioStream_getChannelCount;
static AAudioStream_getSampleRate_fn p_AAudioStream_getSampleRate;
static AAudioStream_getState_fn p_AAudioStream_getState;
static AAudio_convertResultToText_fn p_AAudio_convertResultToText;

static pthread_mutex_t streamMutex = PTHREAD_MUTEX_INITIALIZER;
static AAudioStream *stream;
static int channelCount;
static int g_sampleRate;
static int g_samplesPerFrame;
static int16_t *ringBuffer;
static int ringCapacitySamples;
static int ringReadIndex;
static int ringWriteIndex;
static int ringUsedSamples;

static const char *resultText(aaudio_result_t result) {
    if (p_AAudio_convertResultToText != NULL) {
        return p_AAudio_convertResultToText(result);
    }
    return "unknown";
}

static void *loadRequiredSymbol(const char *name) {
    void *symbol = dlsym(aaudioLib, name);
    if (symbol == NULL) {
        LOGE("Missing AAudio symbol: %s", name);
    }
    return symbol;
}

static void *loadOptionalSymbol(const char *name) {
    return dlsym(aaudioLib, name);
}

static bool loadAaudioSymbols(void) {
    if (symbolsLoaded) {
        return true;
    }

    aaudioLib = dlopen("libaaudio.so", RTLD_NOW | RTLD_LOCAL);
    if (aaudioLib == NULL) {
        LOGW("libaaudio.so is unavailable: %s", dlerror());
        return false;
    }

#define LOAD_REQUIRED(name) do { \
    p_##name = (name##_fn)loadRequiredSymbol(#name); \
    if (p_##name == NULL) return false; \
} while (0)
#define LOAD_OPTIONAL(name) p_##name = (name##_fn)loadOptionalSymbol(#name)

    LOAD_REQUIRED(AAudio_createStreamBuilder);
    LOAD_REQUIRED(AAudioStreamBuilder_delete);
    LOAD_REQUIRED(AAudioStreamBuilder_openStream);
    LOAD_REQUIRED(AAudioStreamBuilder_setDirection);
    LOAD_REQUIRED(AAudioStreamBuilder_setFormat);
    LOAD_REQUIRED(AAudioStreamBuilder_setSampleRate);
    LOAD_REQUIRED(AAudioStreamBuilder_setChannelCount);
    LOAD_REQUIRED(AAudioStreamBuilder_setPerformanceMode);
    LOAD_REQUIRED(AAudioStreamBuilder_setSharingMode);
    LOAD_REQUIRED(AAudioStreamBuilder_setDataCallback);
    LOAD_REQUIRED(AAudioStream_requestStart);
    LOAD_REQUIRED(AAudioStream_requestStop);
    LOAD_REQUIRED(AAudioStream_close);
    LOAD_REQUIRED(AAudioStream_getFramesPerBurst);
    LOAD_REQUIRED(AAudioStream_getBufferCapacityInFrames);
    LOAD_REQUIRED(AAudioStream_getBufferSizeInFrames);
    LOAD_REQUIRED(AAudioStream_setBufferSizeInFrames);
    LOAD_REQUIRED(AAudioStream_getState);
    LOAD_OPTIONAL(AAudioStream_getChannelCount);
    LOAD_OPTIONAL(AAudioStream_getSampleRate);
    LOAD_OPTIONAL(AAudioStreamBuilder_setUsage);
    LOAD_OPTIONAL(AAudioStreamBuilder_setContentType);
    LOAD_OPTIONAL(AAudioStream_getPerformanceMode);
    LOAD_OPTIONAL(AAudioStream_getSharingMode);
    LOAD_OPTIONAL(AAudio_convertResultToText);
    LOAD_OPTIONAL(AAudioStreamBuilder_setChannelMask);

#undef LOAD_REQUIRED
#undef LOAD_OPTIONAL

    symbolsLoaded = true;
    return true;
}

static int maxInt(int a, int b) {
    return a > b ? a : b;
}

static int minInt(int a, int b) {
    return a < b ? a : b;
}

static void clearRingLocked(void) {
    ringReadIndex = 0;
    ringWriteIndex = 0;
    ringUsedSamples = 0;
    if (ringBuffer != NULL) {
        memset(ringBuffer, 0, (size_t) ringCapacitySamples * sizeof(*ringBuffer));
    }
}

static void freeRingLocked(void) {
    free(ringBuffer);
    ringBuffer = NULL;
    ringCapacitySamples = 0;
    clearRingLocked();
}

static aaudio_data_callback_result_t
dataCallback(AAudioStream *ignoredStream, void *userData, void *audioData, int32_t numFrames) {
    (void) ignoredStream;
    (void) userData;

    int16_t *out = (int16_t *) audioData;
    int requestedSamples = numFrames * channelCount;
    int copiedSamples = 0;

    if (pthread_mutex_trylock(&streamMutex) == 0) {
        int samplesToCopy = minInt(requestedSamples, ringUsedSamples);
        while (samplesToCopy > 0) {
            int chunk = minInt(samplesToCopy, ringCapacitySamples - ringReadIndex);
            memcpy(out + copiedSamples, ringBuffer + ringReadIndex, (size_t) chunk * sizeof(*out));
            ringReadIndex = (ringReadIndex + chunk) % ringCapacitySamples;
            ringUsedSamples -= chunk;
            copiedSamples += chunk;
            samplesToCopy -= chunk;
        }
        pthread_mutex_unlock(&streamMutex);
    }

    if (copiedSamples < requestedSamples) {
        memset(out + copiedSamples, 0, (size_t) (requestedSamples - copiedSamples) * sizeof(*out));
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static aaudio_result_t
openAaudioStream(int requestedChannelCount, int sampleRate, aaudio_sharing_mode_t sharingMode) {
    AAudioStreamBuilder *builder = NULL;
    AAudioStream *newStream = NULL;
    aaudio_result_t result = p_AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) {
        LOGE("AAudio_createStreamBuilder failed: %s", resultText(result));
        return result;
    }

    p_AAudioStreamBuilder_setSampleRate(builder, sampleRate);
    p_AAudioStreamBuilder_setSharingMode(builder, sharingMode);
    p_AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    p_AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    p_AAudioStreamBuilder_setDataCallback(builder, dataCallback, NULL);
    p_AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    p_AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
    p_AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);

    /* Setting the channel mask is not available below API 32 switch to use channel count instead */
    if (android_get_device_api_level() >= 32 && p_AAudioStreamBuilder_setChannelMask != NULL) {
        if (requestedChannelCount == 2) {
            p_AAudioStreamBuilder_setChannelMask(builder, AAUDIO_CHANNEL_STEREO);
        } else if (requestedChannelCount == 6) {
            p_AAudioStreamBuilder_setChannelMask(builder, AAUDIO_CHANNEL_5POINT1);
        } else if (requestedChannelCount == 8) {
            p_AAudioStreamBuilder_setChannelMask(builder, AAUDIO_CHANNEL_7POINT1);
        }
    } else {
        p_AAudioStreamBuilder_setChannelCount(builder, requestedChannelCount);
    }

    result = p_AAudioStreamBuilder_openStream(builder, &newStream);
    p_AAudioStreamBuilder_delete(builder);

    if (result != AAUDIO_OK) {
        LOGW("AAudio open failed for sharing mode %d: %s", sharingMode, resultText(result));
        return result;
    }

    stream = newStream;
    return AAUDIO_OK;
}

JNIEXPORT jboolean JNICALL
Java_com_starlight_binding_audio_AAudioRenderer_aAudioIsSupported(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return loadAaudioSymbols() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_starlight_binding_audio_AAudioRenderer_aAudioSetup(JNIEnv *env, jclass clazz,
                                                            jint requestedChannelCount,
                                                            jint sampleRate,
                                                            jint samplesPerFrame) {
    (void) env;
    (void) clazz;

    if (!loadAaudioSymbols()) {
        return -1;
    }

    pthread_mutex_lock(&streamMutex);
    if (stream != NULL) {
        p_AAudioStream_requestStop(stream);
        p_AAudioStream_close(stream);
        stream = NULL;
    }
    freeRingLocked();
    channelCount = requestedChannelCount;
    g_sampleRate = sampleRate;
    g_samplesPerFrame = samplesPerFrame;
    pthread_mutex_unlock(&streamMutex);

    pthread_mutex_lock(&streamMutex);
    aaudio_result_t result = openAaudioStream(requestedChannelCount, sampleRate,
                                              AAUDIO_SHARING_MODE_EXCLUSIVE);
    if (result != AAUDIO_OK) {
        result = openAaudioStream(requestedChannelCount, sampleRate,
                                  AAUDIO_SHARING_MODE_SHARED);
        if (result != AAUDIO_OK) {
            pthread_mutex_unlock(&streamMutex);
            return -2;
        }
    }
    pthread_mutex_unlock(&streamMutex);

    int burstFrames = p_AAudioStream_getFramesPerBurst(stream);
    int multiplier = (samplesPerFrame * 2 + burstFrames - 1) / burstFrames;
    if (multiplier < 2) multiplier = 2;
    int targetBufferFrames = multiplier * burstFrames;
    int actualBufferFrames = p_AAudioStream_setBufferSizeInFrames(stream, targetBufferFrames);
    int capacityFrames = p_AAudioStream_getBufferCapacityInFrames(stream);
    int bufferFrames = p_AAudioStream_getBufferSizeInFrames(stream);
    int ringCapacityFrames = maxInt(samplesPerFrame * 6,
                                    maxInt(burstFrames * 4, targetBufferFrames * 2));

    pthread_mutex_lock(&streamMutex);
    ringCapacitySamples = ringCapacityFrames * channelCount;
    ringBuffer = (int16_t *) calloc((size_t) ringCapacitySamples, sizeof(*ringBuffer));
    clearRingLocked();
    pthread_mutex_unlock(&streamMutex);

    if (ringBuffer == NULL) {
        LOGE("Failed to allocate AAudio ring buffer");
        pthread_mutex_lock(&streamMutex);
        p_AAudioStream_close(stream);
        stream = NULL;
        pthread_mutex_unlock(&streamMutex);
        return -3;
    }

    int performanceMode =
            p_AAudioStream_getPerformanceMode != NULL ? p_AAudioStream_getPerformanceMode(
                    stream) : -1;
    int sharingMode =
            p_AAudioStream_getSharingMode != NULL ? p_AAudioStream_getSharingMode(stream) : -1;
    LOGI("AAudio config: channels=%d sampleRate=%d packetFrames=%d burst=%d targetBuffer=%d actualSet=%d buffer=%d capacity=%d ringFrames=%d perf=%d sharing=%d",
         requestedChannelCount, sampleRate, samplesPerFrame, burstFrames, targetBufferFrames,
         actualBufferFrames, bufferFrames, capacityFrames, ringCapacityFrames, performanceMode,
         sharingMode);

    return 0;
}

JNIEXPORT void JNICALL
Java_com_starlight_binding_audio_AAudioRenderer_aAudioStart(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;

    pthread_mutex_lock(&streamMutex);
    clearRingLocked();
    AAudioStream *localStream = stream;
    pthread_mutex_unlock(&streamMutex);

    if (localStream != NULL) {
        aaudio_result_t result = p_AAudioStream_requestStart(localStream);
        if (result != AAUDIO_OK) {
            LOGE("AAudio requestStart failed: %s", resultText(result));
        }
    }
}

JNIEXPORT void JNICALL
Java_com_starlight_binding_audio_AAudioRenderer_aAudioStop(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;

    pthread_mutex_lock(&streamMutex);
    AAudioStream *localStream = stream;
    clearRingLocked();
    pthread_mutex_unlock(&streamMutex);

    if (localStream != NULL) {
        aaudio_stream_state_t state = p_AAudioStream_getState(localStream);
        if (state != AAUDIO_STREAM_STATE_STOPPING &&
            state != AAUDIO_STREAM_STATE_STOPPED &&
            state != AAUDIO_STREAM_STATE_CLOSING &&
            state != AAUDIO_STREAM_STATE_CLOSED &&
            state != AAUDIO_STREAM_STATE_DISCONNECTED) {
            p_AAudioStream_requestStop(localStream);
        }
    }
}

static aaudio_result_t rebuildStreamLocked() {
    if (stream != NULL) {
        p_AAudioStream_close(stream);
        stream = NULL;
    }

    ringReadIndex = 0;
    ringWriteIndex = 0;
    ringUsedSamples = 0;

    aaudio_result_t result = openAaudioStream(channelCount, g_sampleRate, AAUDIO_SHARING_MODE_EXCLUSIVE);
    if (result != AAUDIO_OK) {
        result = openAaudioStream(channelCount, g_sampleRate, AAUDIO_SHARING_MODE_SHARED);
    }

    if (result == AAUDIO_OK && stream != NULL) {
        int burstFrames = p_AAudioStream_getFramesPerBurst(stream);
        int multiplier = (g_samplesPerFrame * 2 + burstFrames - 1) / burstFrames;
        if (multiplier < 2) multiplier = 2;
        p_AAudioStream_setBufferSizeInFrames(stream, multiplier * burstFrames);

        result = p_AAudioStream_requestStart(stream);
        if (result != AAUDIO_OK) {
            LOGE("Failed to start rebuilt AAudio stream: %s", resultText(result));
        } else {
            int actualRate = p_AAudioStream_getSampleRate != NULL ? p_AAudioStream_getSampleRate(stream) : 0;
            int actualChannels = p_AAudioStream_getChannelCount != NULL ? p_AAudioStream_getChannelCount(stream) : 0;
            LOGI("Stream rebuilt and started. channels=%d rate=%d (actual: %d ch, %d Hz)",
                 channelCount, g_sampleRate, actualChannels, actualRate);
        }
    } else {
        LOGE("Failed to rebuild AAudio stream: %s", resultText(result));
    }
    return result;
}

int getState(AAudioStream *newStream) {
    if (newStream == NULL) {
        return AAUDIO_STREAM_STATE_UNINITIALIZED;
    }
    aaudio_stream_state_t state = p_AAudioStream_getState(newStream);
    return state;
}

void JNICALL
Java_com_starlight_binding_audio_AAudioRenderer_aAudioWrite(JNIEnv *env, jclass clazz,
                                                            jshortArray audioData,
                                                            jint sampleCount) {
    (void) clazz;

    if (audioData == NULL || sampleCount <= 0) {
        return;
    }

    jsize arrayLength = (*env)->GetArrayLength(env, audioData);
    if (sampleCount > arrayLength) {
        sampleCount = arrayLength;
    }

    jboolean isCopy;
    jshort *samples = (*env)->GetShortArrayElements(env, audioData, &isCopy);
    if (samples == NULL) {
        return;
    }

    pthread_mutex_lock(&streamMutex);
    if (stream != NULL && getState(stream) == AAUDIO_STREAM_STATE_DISCONNECTED) {
        rebuildStreamLocked();
    }

    if (stream == NULL) {
        pthread_mutex_unlock(&streamMutex);
        (*env)->ReleaseShortArrayElements(env, audioData, samples, JNI_ABORT);
        return;
    }

    if (ringBuffer != NULL && ringCapacitySamples > 0) {

        int inputOffset = 0;
        int samplesToWrite = sampleCount;

        if (samplesToWrite > ringCapacitySamples) {
            inputOffset = samplesToWrite - ringCapacitySamples;
            samplesToWrite = ringCapacitySamples;
        }

        int freeSamples = ringCapacitySamples - ringUsedSamples;
        if (samplesToWrite > freeSamples) {
            int samplesToAdvance = samplesToWrite - freeSamples;
            ringReadIndex = (ringReadIndex + samplesToAdvance) % ringCapacitySamples;
            ringUsedSamples -= samplesToAdvance;
        }

        while (samplesToWrite > 0) {
            int chunk = minInt(samplesToWrite, ringCapacitySamples - ringWriteIndex);
            memcpy(ringBuffer + ringWriteIndex, samples + inputOffset,
                   (size_t) chunk * sizeof(*ringBuffer));
            ringWriteIndex = (ringWriteIndex + chunk) % ringCapacitySamples;
            ringUsedSamples += chunk;
            inputOffset += chunk;
            samplesToWrite -= chunk;
        }
    }
    pthread_mutex_unlock(&streamMutex);

    (*env)->ReleaseShortArrayElements(env, audioData, samples, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_starlight_binding_audio_AAudioRenderer_aAudioCleanup(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;

    pthread_mutex_lock(&streamMutex);
    AAudioStream *localStream = stream;
    stream = NULL;
    freeRingLocked();
    pthread_mutex_unlock(&streamMutex);

    if (localStream != NULL) {
        aaudio_stream_state_t state = p_AAudioStream_getState(localStream);
        if (state != AAUDIO_STREAM_STATE_STOPPING &&
            state != AAUDIO_STREAM_STATE_STOPPED &&
            state != AAUDIO_STREAM_STATE_CLOSING &&
            state != AAUDIO_STREAM_STATE_CLOSED &&
            state != AAUDIO_STREAM_STATE_DISCONNECTED) {
            p_AAudioStream_requestStop(localStream);
        }
        if (state != AAUDIO_STREAM_STATE_CLOSING &&
            state != AAUDIO_STREAM_STATE_CLOSED) {
            p_AAudioStream_close(localStream);
        }
    }
}
