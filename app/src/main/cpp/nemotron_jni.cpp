/*
 * nemotron_jni.cpp
 *
 * JNI bridge between Kotlin (com.sainadh.livenotes.stt.NemotronTranscriber)
 * and transcribe.cpp's public C API, for on-device streaming ASR using
 * NVIDIA's nemotron-3.5-asr-streaming-0.6b (or any other transcribe.cpp
 * "parakeet family" streaming GGUF model - nemotron-speech-streaming-en-0.6b
 * works identically).
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
#include <android/log.h>

#include "transcribe.h"
#include "transcribe/parakeet.h"

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

jstring makeJString(JNIEnv * env, const char * s) {
    if (!s) s = "";
    return env->NewStringUTF(s);
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
 * Returns 0 on failure (check logcat tag NemotronJNI for the reason).
 */
JNIEXPORT jlong JNICALL
Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeInit(
        JNIEnv * env, jobject /* thiz */,
        jstring jModelPath, jstring jLanguage, jint attContextRight) {

    const char * modelPath = env->GetStringUTFChars(jModelPath, nullptr);
    const char * language  = env->GetStringUTFChars(jLanguage, nullptr);

    auto * ns = new NativeSession();

    // 1. Load the model (CPU backend on-device; AUTO picks CPU when no
    //    Vulkan/CUDA device is registered, which is the default build
    //    profile shipped in jniLibs for phones).
    struct transcribe_model_load_params load_params;
    transcribe_model_load_params_init(&load_params);

    transcribe_status st = transcribe_model_load_file(modelPath, &load_params, &ns->model);
    if (st != TRANSCRIBE_OK || ns->model == nullptr) {
        LOGE("transcribe_model_load_file failed: status=%d path=%s", (int) st, modelPath);
        env->ReleaseStringUTFChars(jModelPath, modelPath);
        env->ReleaseStringUTFChars(jLanguage, language);
        delete ns;
        return 0;
    }

    // 2. Open a session against the loaded model.
    st = transcribe_session_init(ns->model, nullptr, &ns->session);
    if (st != TRANSCRIBE_OK || ns->session == nullptr) {
        LOGE("transcribe_session_init failed: status=%d", (int) st);
        env->ReleaseStringUTFChars(jModelPath, modelPath);
        env->ReleaseStringUTFChars(jLanguage, language);
        delete ns;
        return 0;
    }

    // 3. Configure the run: language is mandatory for nemotron-3.5 (no
    //    implicit default - see docs/models/nemotron-3.5-asr-streaming-0.6b.md).
    struct transcribe_run_params run_params;
    transcribe_run_params_init(&run_params);
    run_params.language = language;

    // 4. Configure streaming: att_context_right picks the cache-aware
    //    latency/accuracy tradeoff. NULL family extension is fine too
    //    (uses model default = highest accuracy / highest latency).
    struct transcribe_parakeet_stream_ext stream_ext;
    transcribe_parakeet_stream_ext_init(&stream_ext);
    stream_ext.att_context_right = attContextRight; // -1 = model default

    struct transcribe_stream_params stream_params;
    transcribe_stream_params_init(&stream_params);
    stream_params.family = &stream_ext.ext;

    st = transcribe_stream_begin(ns->session, &run_params, &stream_params);

    // language string was only needed for the duration of stream_begin
    // (the API copies it into session-owned storage - see the header's
    // "Params lifetime" note on transcribe_stream_begin).
    env->ReleaseStringUTFChars(jModelPath, modelPath);
    env->ReleaseStringUTFChars(jLanguage, language);

    if (st != TRANSCRIBE_OK) {
        LOGE("transcribe_stream_begin failed: status=%d", (int) st);
        delete ns;
        return 0;
    }

    ns->stream_active = true;
    LOGI("Nemotron stream initialized successfully");
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
        return makeJString(env, "");
    }

    jsize n = env->GetArrayLength(pcm);
    if (n <= 0) {
        return makeJString(env, "");
    }

    jfloat * samples = env->GetFloatArrayElements(pcm, nullptr);

    struct transcribe_stream_update update{};
    transcribe_status st = transcribe_stream_feed(ns->session, samples, (int) n, &update);

    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);

    if (st != TRANSCRIBE_OK) {
        LOGE("transcribe_stream_feed failed: status=%d", (int) st);
        return makeJString(env, "");
    }

    struct transcribe_stream_text text;
    transcribe_stream_text_init(&text);
    st = transcribe_stream_get_text(ns->session, &text);
    if (st != TRANSCRIBE_OK) {
        return makeJString(env, "");
    }

    std::string combined;
    combined += (text.committed_text ? text.committed_text : "");
    combined += '\x01';
    combined += (text.tentative_text ? text.tentative_text : "");
    return env->NewStringUTF(combined.c_str());
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

    struct transcribe_stream_update update{};
    transcribe_status st = transcribe_stream_finalize(ns->session, &update);
    ns->stream_active = false;

    if (st != TRANSCRIBE_OK) {
        LOGE("transcribe_stream_finalize failed: status=%d", (int) st);
        return makeJString(env, "");
    }

    const char * full = transcribe_full_text(ns->session);
    return makeJString(env, full);
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
        return JNI_FALSE;
    }

    const char * language = env->GetStringUTFChars(jLanguage, nullptr);

    struct transcribe_run_params run_params;
    transcribe_run_params_init(&run_params);
    run_params.language = language;

    struct transcribe_parakeet_stream_ext stream_ext;
    transcribe_parakeet_stream_ext_init(&stream_ext);
    stream_ext.att_context_right = attContextRight;

    struct transcribe_stream_params stream_params;
    transcribe_stream_params_init(&stream_params);
    stream_params.family = &stream_ext.ext;

    transcribe_status st = transcribe_stream_begin(ns->session, &run_params, &stream_params);
    env->ReleaseStringUTFChars(jLanguage, language);

    if (st != TRANSCRIBE_OK) {
        LOGE("nativeRestartStream: transcribe_stream_begin failed: status=%d", (int) st);
        return JNI_FALSE;
    }

    ns->stream_active = true;
    return JNI_TRUE;
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
