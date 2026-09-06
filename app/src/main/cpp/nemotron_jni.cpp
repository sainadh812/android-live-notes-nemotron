/*
 * nemotron_jni.cpp
 *
 * JNI bridge between Kotlin (com.sainadh.livenotes.stt.NemotronTranscriber)
 * and transcribe.cpp's public C API, for on-device streaming ASR using
 * NVIDIA Nemotron and Useful Sensors Moonshine Streaming GGUF models.
 * The loaded model capabilities select the language and family extension.
 *
 * Model of use from Kotlin:
 *   val handle = nativeInit(modelPath, "en-US")   // loads model + opens stream
 *   ...
 *   val text = nativeFeedPcm(handle, floatArrayOf(...))   // 16kHz mono float32 PCM
 *   val finalText = nativeFinalizeStream(handle)
 *   nativeDestroy(handle)
 *
 * All heavy lifting (model load, session, stream lifecycle) is hidden behind
 * an opaque jlong handle so Kotlin never touches raw pointers.
 */

#include <jni.h>
#include <string>
#include <memory>
#include <cstdio>
#include <new>
#include <cstring>
#include <vector>
#include <limits>
#include <android/log.h>

#include "transcribe.h"
#include "transcribe/parakeet.h"
#include "transcribe/moonshine_streaming.h"

#define LOG_TAG "NemotronJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Bundles everything one active transcription needs. The jlong handle
// Kotlin holds is just a pointer to one of these, cast back and forth.
struct NativeSession {
    transcribe_model *   model   = nullptr;
    transcribe_session * session = nullptr;
    bool                 stream_active = false;

    ~NativeSession() {
        if (session) {
            // transcribe_session_free also frees the model when the
            // session owns it (i.e. it was NOT created via the two-step
            // model_load_file + session_init path). We use the two-step
            // path below so we free session then model explicitly.
            transcribe_session_free(session);
        }
        if (model) {
            transcribe_model_free(model);
        }
    }
};

void throwJavaError(JNIEnv * env, const char * message,
                    const char * type = "java/lang/IllegalStateException") {
    LOGE("%s", message);
    if (env->ExceptionCheck()) return;
    jclass exceptionClass = env->FindClass(type);
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
        env->DeleteLocalRef(exceptionClass);
    }
}

void throwNativeError(JNIEnv * env, const char * operation, transcribe_status status) {
    char message[384];
    std::snprintf(message, sizeof(message), "%s failed: %s (status=%d)",
                  operation, transcribe_status_string(status), static_cast<int>(status));
    throwJavaError(env, message);
}

// Engine text is standard UTF-8. NewStringUTF accepts Modified UTF-8 and
// CheckJNI can abort on supplementary code points, so construct UTF-16 instead.
jstring makeJString(JNIEnv * env, const char * text) {
    if (text == nullptr) text = "";
    const size_t length = std::strlen(text);
    if (length > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        throwJavaError(env, "Speech transcript exceeds the Java string limit");
        return nullptr;
    }
    try {
        std::vector<jchar> units;
        units.reserve(length);
        const auto * bytes = reinterpret_cast<const unsigned char *>(text);
        for (size_t position = 0; position < length;) {
            const auto first = bytes[position];
            uint32_t codepoint = first;
            size_t count = 1;
            uint32_t minimum = 0;
            bool valid = first < 0x80;
            if (first >= 0xC2 && first <= 0xDF) {
                count = 2; minimum = 0x80; codepoint = first & 0x1F; valid = true;
            } else if (first >= 0xE0 && first <= 0xEF) {
                count = 3; minimum = 0x800; codepoint = first & 0x0F; valid = true;
            } else if (first >= 0xF0 && first <= 0xF4) {
                count = 4; minimum = 0x10000; codepoint = first & 0x07; valid = true;
            }
            valid = valid && count <= length - position;
            for (size_t i = 1; valid && i < count; ++i) {
                const auto next = bytes[position + i];
                if ((next & 0xC0) != 0x80) valid = false;
                else codepoint = (codepoint << 6) | (next & 0x3F);
            }
            valid = valid && codepoint >= minimum && codepoint <= 0x10FFFF &&
                !(codepoint >= 0xD800 && codepoint <= 0xDFFF);
            if (!valid) {
                // Consume one invalid byte at a time, preserving following text.
                units.push_back(0xFFFD);
                ++position;
                continue;
            }
            position += count;
            if (codepoint <= 0xFFFF) {
                units.push_back(static_cast<jchar>(codepoint));
            } else {
                codepoint -= 0x10000;
                units.push_back(static_cast<jchar>(0xD800 + (codepoint >> 10)));
                units.push_back(static_cast<jchar>(0xDC00 + (codepoint & 0x3FF)));
            }
        }
        const jchar empty = 0;
        return env->NewString(units.empty() ? &empty : units.data(), static_cast<jsize>(units.size()));
    } catch (const std::bad_alloc &) {
        throwJavaError(env, "Unable to allocate speech transcript", "java/lang/OutOfMemoryError");
        return nullptr;
    }
}

// Locale tags differ between model families: multilingual Nemotron accepts
// en-US; the English checkpoints publish en. Prefer an exact match, then the
// explicitly advertised language subtag. Unknown languages remain errors.
const char * modelLanguage(const transcribe_capabilities & caps, const char * requested) {
    if (requested == nullptr || caps.languages == nullptr) return requested;
    for (int i = 0; i < caps.n_languages; ++i) {
        if (caps.languages[i] && std::strcmp(caps.languages[i], requested) == 0) return caps.languages[i];
    }
    const char * separator = std::strchr(requested, '-');
    if (separator != nullptr) {
        const auto length = static_cast<size_t>(separator - requested);
        for (int i = 0; i < caps.n_languages; ++i) {
            const char * candidate = caps.languages[i];
            if (candidate && std::strlen(candidate) == length && std::strncmp(candidate, requested, length) == 0) {
                return candidate;
            }
        }
    }
    return requested;
}

transcribe_status beginStream(NativeSession * ns, const char * language, int attContextRight) {
    transcribe_capabilities caps;
    transcribe_capabilities_init(&caps);
    auto status = transcribe_model_get_capabilities(ns->model, &caps);
    if (status != TRANSCRIBE_OK) return status;
    if (!caps.supports_streaming || caps.native_sample_rate != 16000) return TRANSCRIBE_ERR_NOT_IMPLEMENTED;

    transcribe_run_params run_params;
    transcribe_run_params_init(&run_params);
    run_params.language = modelLanguage(caps, language);
    transcribe_stream_params stream_params;
    transcribe_stream_params_init(&stream_params);

    transcribe_parakeet_stream_ext parakeet;
    transcribe_moonshine_streaming_stream_ext moonshine;
    if (transcribe_model_accepts_ext_kind(ns->model, TRANSCRIBE_EXT_SLOT_STREAM,
                                          TRANSCRIBE_EXT_KIND_PARAKEET_STREAM)) {
        transcribe_parakeet_stream_ext_init(&parakeet);
        parakeet.att_context_right = attContextRight;
        stream_params.family = &parakeet.ext;
    } else if (transcribe_model_accepts_ext_kind(ns->model, TRANSCRIBE_EXT_SLOT_STREAM,
                                                 TRANSCRIBE_EXT_KIND_MOONSHINE_STREAMING_STREAM)) {
        transcribe_moonshine_streaming_stream_ext_init(&moonshine);
        // Match the microphone feed cadence; avoid extra autoregressive decodes
        // within each half-second chunk on a CPU-only phone.
        moonshine.min_decode_interval_ms = 500;
        stream_params.family = &moonshine.ext;
        // Moonshine can revise early punctuation/words. AUTO may then retain only
        // a stale committed prefix at finalize, discarding the rest of the audio.
        // Store one revisable live hypothesis until its final decode is available.
        stream_params.commit_policy = TRANSCRIBE_STREAM_COMMIT_ON_FINALIZE;
    } else {
        return TRANSCRIBE_ERR_NOT_IMPLEMENTED;
    }
    return transcribe_stream_begin(ns->session, &run_params, &stream_params);
}

} // namespace

extern "C" {

/*
 * nativeInit: load the GGUF model from modelPath, open a session, and
 * begin a streaming run configured for `language` (e.g. "en-US").
 *
 * attContextRight selects the cache-aware streaming latency/accuracy
 * tradeoff for nemotron-3.5-asr-streaming-0.6b (menu is {0,3,6,13} per
 * the model's docs; -1 == model default). Pass -1 from Kotlin unless you
 * want to tune it explicitly.
 *
 * Throws IllegalStateException on a native failure, including its operation
 * and status. Kotlin must catch it on the owning transcription worker.
 */
JNIEXPORT jlong JNICALL
Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeInit(
        JNIEnv * env, jobject /* thiz */,
        jstring jModelPath, jstring jLanguage, jint attContextRight) {

    const char * modelPath = env->GetStringUTFChars(jModelPath, nullptr);
    if (modelPath == nullptr) return 0; // JVM has already raised an exception.
    const char * language  = env->GetStringUTFChars(jLanguage, nullptr);
    if (language == nullptr) {
        env->ReleaseStringUTFChars(jModelPath, modelPath);
        return 0;
    }

    auto * ns = new (std::nothrow) NativeSession();
    if (ns == nullptr) {
        env->ReleaseStringUTFChars(jModelPath, modelPath);
        env->ReleaseStringUTFChars(jLanguage, language);
        throwJavaError(env, "Unable to allocate native ASR session", "java/lang/OutOfMemoryError");
        return 0;
    }

    // 1. Load the model (CPU backend on-device; AUTO picks CPU when no
    //    Vulkan/CUDA device is registered, which is the default build
    //    profile shipped in jniLibs for phones).
    struct transcribe_model_load_params load_params;
    transcribe_model_load_params_init(&load_params);

    transcribe_status st = transcribe_model_load_file(modelPath, &load_params, &ns->model);
    if (st != TRANSCRIBE_OK || ns->model == nullptr) {
        env->ReleaseStringUTFChars(jModelPath, modelPath);
        env->ReleaseStringUTFChars(jLanguage, language);
        delete ns;
        throwNativeError(env, "Loading speech model", st);
        return 0;
    }

    // 2. Open a session against the loaded model.
    transcribe_session_params session_params;
    transcribe_session_params_init(&session_params);
    // Leave scheduling room for continuous microphone capture and the Android UI.
    // A fixed upper budget also avoids large host/core counts oversubscribing CPU.
    session_params.n_threads = 4;
    st = transcribe_session_init(ns->model, &session_params, &ns->session);
    if (st != TRANSCRIBE_OK || ns->session == nullptr) {
        env->ReleaseStringUTFChars(jModelPath, modelPath);
        env->ReleaseStringUTFChars(jLanguage, language);
        delete ns;
        throwNativeError(env, "Creating speech session", st);
        return 0;
    }

    // Use only an extension that this loaded model explicitly accepts.
    st = beginStream(ns, language, attContextRight);

    // language string was only needed for the duration of stream_begin
    // (the API copies it into session-owned storage - see the header's
    // "Params lifetime" note on transcribe_stream_begin).
    env->ReleaseStringUTFChars(jModelPath, modelPath);
    env->ReleaseStringUTFChars(jLanguage, language);

    if (st != TRANSCRIBE_OK) {
        delete ns;
        throwNativeError(env, "Starting speech stream", st);
        return 0;
    }

    ns->stream_active = true;
    LOGI("On-device speech stream initialized successfully");
    return reinterpret_cast<jlong>(ns);
}

/*
 * nativeFeedPcm: push one chunk of 16kHz mono float32 PCM into the active
 * stream and return the current UI-facing text snapshot as
 * "<committed_text>\u0001<tentative_text>" (0x01 is a separator Kotlin
 * splits on - avoids needing two separate JNI calls per chunk).
 */
JNIEXPORT jstring JNICALL
Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeFeedPcm(
        JNIEnv * env, jobject /* thiz */, jlong handle, jfloatArray pcm) {

    auto * ns = reinterpret_cast<NativeSession *>(handle);
    if (ns == nullptr || !ns->stream_active) {
        throwJavaError(env, "Cannot feed audio: Nemotron stream is not active");
        return nullptr;
    }

    jsize n = env->GetArrayLength(pcm);
    if (n <= 0) {
        throwJavaError(env, "Cannot feed an empty audio chunk");
        return nullptr;
    }

    jfloat * samples = env->GetFloatArrayElements(pcm, nullptr);
    if (samples == nullptr) return nullptr; // Preserve the JVM allocation exception.

    struct transcribe_stream_update update;
    transcribe_stream_update_init(&update);
    transcribe_status st = transcribe_stream_feed(ns->session, samples, (int) n, &update);

    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);

    if (st != TRANSCRIBE_OK) {
        ns->stream_active = false;
        throwNativeError(env, "Transcribing microphone audio", st);
        return nullptr;
    }

    struct transcribe_stream_text text;
    transcribe_stream_text_init(&text);
    st = transcribe_stream_get_text(ns->session, &text);
    if (st != TRANSCRIBE_OK) {
        ns->stream_active = false;
        throwNativeError(env, "Reading speech transcript", st);
        return nullptr;
    }

    try {
        std::string combined;
        combined += (text.committed_text ? text.committed_text : "");
        combined += '\x01';
        combined += (text.tentative_text ? text.tentative_text : "");
        return makeJString(env, combined.c_str());
    } catch (const std::bad_alloc &) {
        throwJavaError(env, "Unable to allocate transcript text", "java/lang/OutOfMemoryError");
        return nullptr;
    }
}

/*
 * nativeFinalizeStream: flush the stream (satisfies right-context /
 * lookahead) and return the final committed text. After this call the
 * session is FINISHED; call nativeRestartStream (or nativeDestroy +
 * nativeInit again) before feeding more audio.
 */
JNIEXPORT jstring JNICALL
Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeFinalizeStream(
        JNIEnv * env, jobject /* thiz */, jlong handle) {

    auto * ns = reinterpret_cast<NativeSession *>(handle);
    if (ns == nullptr || !ns->stream_active) {
        return makeJString(env, "");
    }

    struct transcribe_stream_update update;
    transcribe_stream_update_init(&update);
    transcribe_status st = transcribe_stream_finalize(ns->session, &update);
    ns->stream_active = false;

    if (st != TRANSCRIBE_OK) {
        throwNativeError(env, "Finalizing speech transcript", st);
        return nullptr;
    }

    // Raw full_text may revise a prefix Moonshine already committed. The public
    // stream snapshot preserves the append-only contract consumed by Kotlin.
    transcribe_stream_text text;
    transcribe_stream_text_init(&text);
    st = transcribe_stream_get_text(ns->session, &text);
    if (st != TRANSCRIBE_OK) {
        throwNativeError(env, "Reading final speech transcript", st);
        return nullptr;
    }
    return makeJString(env, text.committed_text);
}

/*
 * nativeRestartStream: begin a fresh streaming run on the same
 * model/session (mirrors SpeechTranscriber's restart-after-result loop).
 * Re-uses attContextRight/language from the original nativeInit call by
 * having Kotlin pass them again explicitly - keeps this bridge stateless
 * about config.
 */
JNIEXPORT jboolean JNICALL
Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeRestartStream(
        JNIEnv * env, jobject /* thiz */, jlong handle, jstring jLanguage, jint attContextRight) {

    auto * ns = reinterpret_cast<NativeSession *>(handle);
    if (ns == nullptr || ns->session == nullptr) {
        throwJavaError(env, "Cannot restart: speech session is unavailable");
        return JNI_FALSE;
    }

    const char * language = env->GetStringUTFChars(jLanguage, nullptr);
    if (language == nullptr) return JNI_FALSE;

    transcribe_status st = beginStream(ns, language, attContextRight);
    env->ReleaseStringUTFChars(jLanguage, language);

    if (st != TRANSCRIBE_OK) {
        ns->stream_active = false;
        throwNativeError(env, "Restarting speech stream", st);
        return JNI_FALSE;
    }

    ns->stream_active = true;
    return JNI_TRUE;
}

// Streaming status may be OK even when an autoregressive model reaches its
// output window. Kotlin checks this after storing each returned snapshot.
JNIEXPORT jboolean JNICALL
Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeWasTruncated(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    const auto * ns = reinterpret_cast<NativeSession *>(handle);
    return ns != nullptr && transcribe_was_truncated(ns->session) ? JNI_TRUE : JNI_FALSE;
}

/*
 * nativeDestroy: free the session and model. Must be called exactly once
 * per successful nativeInit; the handle is invalid afterward.
 */
JNIEXPORT void JNICALL
Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeDestroy(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {

    auto * ns = reinterpret_cast<NativeSession *>(handle);
    delete ns; // destructor frees session then model
}

} // extern "C"
