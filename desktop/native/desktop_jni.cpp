// Include the tested Android bridge unchanged. Rebind its Java class and logger;
// the desktop initializer below adds standard UTF-8 paths for Windows filenames.
#include <cstdint>
#define Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeInit shared_android_nativeInit
#define Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeFeedPcm Java_com_sainadh_livenotes_desktop_stt_NativeSpeech_nativeFeedPcm
#define Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeFinalizeStream Java_com_sainadh_livenotes_desktop_stt_NativeSpeech_nativeFinalizeStream
#define Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeWordTimings Java_com_sainadh_livenotes_desktop_stt_NativeSpeech_nativeWordTimings
#define Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeRestartStream Java_com_sainadh_livenotes_desktop_stt_NativeSpeech_nativeRestartStream
#define Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeWasTruncated Java_com_sainadh_livenotes_desktop_stt_NativeSpeech_nativeWasTruncated
#define Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeDestroy Java_com_sainadh_livenotes_desktop_stt_NativeSpeech_nativeDestroy
#include "../../app/src/main/cpp/nemotron_jni.cpp"

namespace {
// Windows usernames/model folders may contain supplementary characters.
// GetStringUTFChars uses Modified UTF-8; transcribe.cpp requires real UTF-8.
bool javaUtf8(JNIEnv * env, jstring input, std::string & output) {
    if (input == nullptr) {
        throwJavaError(env, "Missing model path or language");
        return false;
    }
    const jsize length = env->GetStringLength(input);
    const jchar * units = env->GetStringChars(input, nullptr);
    if (units == nullptr) return false;
    bool valid = true;
    try {
        output.clear();
        for (jsize index = 0; index < length; ++index) {
            uint32_t point = units[index];
            if (point >= 0xD800 && point <= 0xDBFF) {
                if (index + 1 >= length || units[index + 1] < 0xDC00 || units[index + 1] > 0xDFFF) { valid = false; break; }
                point = 0x10000 + ((point - 0xD800) << 10) + (units[++index] - 0xDC00);
            } else if (point == 0 || (point >= 0xDC00 && point <= 0xDFFF)) { valid = false; break; }
            if (point < 0x80) output += static_cast<char>(point);
            else if (point < 0x800) {
                output += static_cast<char>(0xC0 | (point >> 6));
                output += static_cast<char>(0x80 | (point & 0x3F));
            } else if (point < 0x10000) {
                output += static_cast<char>(0xE0 | (point >> 12));
                output += static_cast<char>(0x80 | ((point >> 6) & 0x3F));
                output += static_cast<char>(0x80 | (point & 0x3F));
            } else {
                output += static_cast<char>(0xF0 | (point >> 18));
                output += static_cast<char>(0x80 | ((point >> 12) & 0x3F));
                output += static_cast<char>(0x80 | ((point >> 6) & 0x3F));
                output += static_cast<char>(0x80 | (point & 0x3F));
            }
        }
    } catch (const std::bad_alloc &) {
        env->ReleaseStringChars(input, units);
        throwJavaError(env, "Unable to encode model path", "java/lang/OutOfMemoryError");
        return false;
    }
    env->ReleaseStringChars(input, units);
    if (!valid) throwJavaError(env, "Model path or language contains invalid Unicode");
    return valid;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_sainadh_livenotes_desktop_stt_NativeSpeech_nativeInit(
        JNIEnv * env, jobject, jstring jPath, jstring jLanguage, jint rightContext) {
    std::string path, language;
    if (!javaUtf8(env, jPath, path) || !javaUtf8(env, jLanguage, language)) return 0;
    std::unique_ptr<NativeSession> session(new (std::nothrow) NativeSession());
    if (!session) { throwJavaError(env, "Unable to allocate speech session", "java/lang/OutOfMemoryError"); return 0; }
    transcribe_model_load_params load;
    transcribe_model_load_params_init(&load);
    auto status = transcribe_model_load_file(path.c_str(), &load, &session->model);
    if (status != TRANSCRIBE_OK || session->model == nullptr) {
        throwNativeError(env, "Loading speech model", status); return 0;
    }
    transcribe_session_params parameters;
    transcribe_session_params_init(&parameters);
    parameters.n_threads = 4;
    status = transcribe_session_init(session->model, &parameters, &session->session);
    if (status != TRANSCRIBE_OK || session->session == nullptr) {
        throwNativeError(env, "Creating speech session", status); return 0;
    }
    status = beginStream(session.get(), language.c_str(), rightContext);
    if (status != TRANSCRIBE_OK) { throwNativeError(env, "Starting speech stream", status); return 0; }
    session->stream_active = true;
    LOGI("Desktop speech stream initialized with four CPU threads");
    return reinterpret_cast<jlong>(session.release());
}
