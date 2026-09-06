// Exercise the actual bridge with the pinned public headers and minimal JVM /
// engine doubles. No model weights, Android device, or recognition inference.
#include <cassert>
#include <cstring>
#include <iostream>
#include <vector>

#include "../nemotron_jni.cpp"

struct transcribe_model {};
struct transcribe_session {};

namespace {
std::string pendingException;
std::string exceptionType;
transcribe_status loadStatus = TRANSCRIBE_OK;
transcribe_status sessionStatus = TRANSCRIBE_OK;
transcribe_status beginStatus = TRANSCRIBE_OK;
transcribe_status feedStatus = TRANSCRIBE_OK;
transcribe_status textStatus = TRANSCRIBE_OK;
transcribe_status finalizeStatus = TRANSCRIBE_OK;
int fedSamples = 0;
int finalizeCalls = 0;
int freedModels = 0;
int freedSessions = 0;
int releasedPcmArrays = 0;

std::string & javaString(jstring value) { return *reinterpret_cast<std::string *>(value); }
jstring newString(JNIEnv *, const char * value) {
    return reinterpret_cast<jstring>(new std::string(value));
}
const char * stringChars(JNIEnv *, jstring value, jboolean *) { return javaString(value).c_str(); }
void releaseChars(JNIEnv *, jstring, const char *) {}
jboolean hasException(JNIEnv *) { return !pendingException.empty(); }
jclass findClass(JNIEnv *, const char * name) {
    exceptionType = name;
    return reinterpret_cast<jclass>(1);
}
jint throwException(JNIEnv *, jclass, const char * message) {
    pendingException = message;
    return 0;
}
void deleteLocalRef(JNIEnv *, jobject) {}
jsize arrayLength(JNIEnv *, jarray array) {
    return static_cast<jsize>(reinterpret_cast<std::vector<float> *>(array)->size());
}
jfloat * arrayElements(JNIEnv *, jfloatArray array, jboolean *) {
    return reinterpret_cast<std::vector<float> *>(array)->data();
}
void releaseElements(JNIEnv *, jfloatArray, jfloat *, jint mode) {
    assert(mode == JNI_ABORT);
    ++releasedPcmArrays;
}
void expectError(const char * operation, int status) {
    assert(exceptionType == "java/lang/IllegalStateException");
    assert(pendingException.find(operation) != std::string::npos);
    assert(pendingException.find("status=" + std::to_string(status)) != std::string::npos);
    pendingException.clear();
    exceptionType.clear();
}
void deleteString(jstring value) { delete reinterpret_cast<std::string *>(value); }
} // namespace

extern "C" {
const char * transcribe_status_string(int status) {
    switch (status) {
        case TRANSCRIBE_ERR_BAD_STRUCT_SIZE: return "bad struct_size";
        case TRANSCRIBE_ERR_BACKEND: return "backend failure";
        case TRANSCRIBE_ERR_GGUF: return "invalid GGUF model";
        default: return "test status";
    }
}

void transcribe_model_load_params_init(transcribe_model_load_params * params) {
    std::memset(params, 0, sizeof(*params));
    params->struct_size = sizeof(*params);
}
void transcribe_run_params_init(transcribe_run_params * params) {
    std::memset(params, 0, sizeof(*params));
    params->struct_size = sizeof(*params);
}
void transcribe_stream_params_init(transcribe_stream_params * params) {
    std::memset(params, 0, sizeof(*params));
    params->struct_size = sizeof(*params);
}
void transcribe_parakeet_stream_ext_init(transcribe_parakeet_stream_ext * extension) {
    std::memset(extension, 0, sizeof(*extension));
    extension->att_context_right = -1;
}
void transcribe_stream_update_init(transcribe_stream_update * update) {
    std::memset(update, 0, sizeof(*update));
    update->struct_size = sizeof(*update);
}
void transcribe_stream_text_init(transcribe_stream_text * text) {
    std::memset(text, 0, sizeof(*text));
    text->struct_size = sizeof(*text);
}
transcribe_status transcribe_model_load_file(const char *, const transcribe_model_load_params * params,
                                             transcribe_model ** model) {
    assert(params->struct_size == sizeof(*params));
    *model = loadStatus == TRANSCRIBE_OK ? new transcribe_model : nullptr;
    return loadStatus;
}
transcribe_status transcribe_session_init(transcribe_model *, const transcribe_session_params *,
                                          transcribe_session ** session) {
    *session = sessionStatus == TRANSCRIBE_OK ? new transcribe_session : nullptr;
    return sessionStatus;
}
transcribe_status transcribe_stream_begin(transcribe_session *, const transcribe_run_params * params,
                                          const transcribe_stream_params * stream) {
    assert(params->struct_size == sizeof(*params));
    assert(stream->struct_size == sizeof(*stream));
    assert(std::string(params->language) == "en-US");
    return beginStatus;
}
transcribe_status transcribe_stream_feed(transcribe_session *, const float *, int count,
                                         transcribe_stream_update * update) {
    // Mirror the pinned ABI preflight, not a recognition implementation.
    if (update && update->struct_size < 40) return TRANSCRIBE_ERR_BAD_STRUCT_SIZE;
    if (feedStatus == TRANSCRIBE_OK) fedSamples += count;
    return feedStatus;
}
transcribe_status transcribe_stream_get_text(const transcribe_session *, transcribe_stream_text * text) {
    assert(text->struct_size == sizeof(*text));
    text->committed_text = "Hello ";
    text->tentative_text = "world";
    return textStatus;
}
transcribe_status transcribe_stream_finalize(transcribe_session *, transcribe_stream_update * update) {
    if (update && update->struct_size < 40) return TRANSCRIBE_ERR_BAD_STRUCT_SIZE;
    ++finalizeCalls;
    return finalizeStatus;
}
const char * transcribe_full_text(const transcribe_session *) { return "Hello world."; }
void transcribe_session_free(transcribe_session * session) { ++freedSessions; delete session; }
void transcribe_model_free(transcribe_model * model) { ++freedModels; delete model; }
} // extern "C"

int main() {
    JNINativeInterface_ functions{};
    functions.NewStringUTF = newString;
    functions.GetStringUTFChars = stringChars;
    functions.ReleaseStringUTFChars = releaseChars;
    functions.ExceptionCheck = hasException;
    functions.FindClass = findClass;
    functions.ThrowNew = throwException;
    functions.DeleteLocalRef = deleteLocalRef;
    functions.GetArrayLength = arrayLength;
    functions.GetFloatArrayElements = arrayElements;
    functions.ReleaseFloatArrayElements = releaseElements;
    JNIEnv env{&functions};
    auto init = Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeInit;
    auto feed = Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeFeedPcm;
    auto finalize = Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeFinalizeStream;
    auto restart = Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeRestartStream;
    auto destroy = Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeDestroy;
    std::string modelPath = "/test/model.gguf", language = "en-US";
    auto path = reinterpret_cast<jstring>(&modelPath);
    auto locale = reinterpret_cast<jstring>(&language);
    std::vector<float> pcm{0.1f, -0.2f, 0.3f};
    auto samples = reinterpret_cast<jfloatArray>(&pcm);

    const jlong handle = init(&env, nullptr, path, locale, -1);
    assert(handle != 0 && pendingException.empty());
    auto partial = feed(&env, nullptr, handle, samples);
    assert(partial != nullptr && pendingException.empty());
    assert(javaString(partial) == std::string("Hello ") + '\x01' + "world");
    assert(fedSamples == 3 && releasedPcmArrays == 1);
    deleteString(partial);
    auto finalText = finalize(&env, nullptr, handle);
    assert(finalText != nullptr && pendingException.empty());
    assert(javaString(finalText) == "Hello world." && finalizeCalls == 1);
    deleteString(finalText);
    assert(restart(&env, nullptr, handle, locale, -1) == JNI_TRUE);

    feedStatus = TRANSCRIBE_ERR_BACKEND;
    assert(feed(&env, nullptr, handle, samples) == nullptr);
    expectError("Transcribing microphone audio", TRANSCRIBE_ERR_BACKEND);
    assert(releasedPcmArrays == 2); // JNI releases PCM even when the engine fails.
    feedStatus = TRANSCRIBE_OK;
    assert(restart(&env, nullptr, handle, locale, -1) == JNI_TRUE);

    textStatus = TRANSCRIBE_ERR_BACKEND;
    assert(feed(&env, nullptr, handle, samples) == nullptr);
    expectError("Reading Nemotron transcript", TRANSCRIBE_ERR_BACKEND);
    textStatus = TRANSCRIBE_OK;
    assert(restart(&env, nullptr, handle, locale, -1) == JNI_TRUE);

    finalizeStatus = TRANSCRIBE_ERR_BACKEND;
    assert(finalize(&env, nullptr, handle) == nullptr);
    expectError("Finalizing Nemotron transcript", TRANSCRIBE_ERR_BACKEND);
    finalizeStatus = TRANSCRIBE_OK;
    destroy(&env, nullptr, handle);
    assert(freedModels == 1 && freedSessions == 1);

    loadStatus = TRANSCRIBE_ERR_GGUF;
    assert(init(&env, nullptr, path, locale, -1) == 0);
    expectError("Loading Nemotron model", TRANSCRIBE_ERR_GGUF);
    assert(freedModels == 1 && freedSessions == 1);
    loadStatus = TRANSCRIBE_OK;

    sessionStatus = TRANSCRIBE_ERR_BACKEND;
    assert(init(&env, nullptr, path, locale, -1) == 0);
    expectError("Creating Nemotron session", TRANSCRIBE_ERR_BACKEND);
    assert(freedModels == 2 && freedSessions == 1);
    sessionStatus = TRANSCRIBE_OK;

    beginStatus = TRANSCRIBE_ERR_UNSUPPORTED_LANGUAGE;
    assert(init(&env, nullptr, path, locale, -1) == 0);
    expectError("Starting Nemotron stream", TRANSCRIBE_ERR_UNSUPPORTED_LANGUAGE);
    assert(freedModels == 3 && freedSessions == 2);

    std::cout << "JNI contract tests passed: initialized feed/finalize, transcript, errors, cleanup.\n";
}
