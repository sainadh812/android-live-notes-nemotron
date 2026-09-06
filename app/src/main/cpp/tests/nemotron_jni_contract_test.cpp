// Exercise the actual bridge with the pinned public headers and minimal JVM /
// engine doubles. No model weights, Android device, or recognition inference.
#include <cassert>
#include <cstring>
#include <iostream>
#include <vector>

#include "../nemotron_jni.cpp"
#include "host_utf16.h"

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
std::string modelFamily = "parakeet";
bool englishOnly = false;
bool streamFinalized = false;
bool wasTruncated = false;
std::string committedText = "Hello ", tentativeText = "world", finalTextValue = "Hello world.";
std::vector<jchar> lastStringUnits;

std::string & javaString(jstring value) { return *reinterpret_cast<std::string *>(value); }
jstring newString(JNIEnv *, const char *) {
    assert(false && "Engine UTF-8 must not reach NewStringUTF");
    return nullptr;
}
jstring newUtf16String(JNIEnv *, const jchar * units, jsize length) {
    lastStringUnits.assign(units, units + length);
    return reinterpret_cast<jstring>(new std::string(hostUtf16ToUtf8(units, length)));
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
void transcribe_session_params_init(transcribe_session_params * params) {
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
    extension->ext.size = sizeof(*extension);
    extension->ext.kind = TRANSCRIBE_EXT_KIND_PARAKEET_STREAM;
    extension->att_context_right = -1;
}
void transcribe_moonshine_streaming_stream_ext_init(transcribe_moonshine_streaming_stream_ext * extension) {
    std::memset(extension, 0, sizeof(*extension));
    extension->ext.size = sizeof(*extension);
    extension->ext.kind = TRANSCRIBE_EXT_KIND_MOONSHINE_STREAMING_STREAM;
    extension->min_decode_interval_ms = -1;
}
void transcribe_capabilities_init(transcribe_capabilities * caps) {
    std::memset(caps, 0, sizeof(*caps));
    caps->struct_size = sizeof(*caps);
}
transcribe_status transcribe_model_get_capabilities(const transcribe_model *, transcribe_capabilities * caps) {
    assert(caps->struct_size == sizeof(*caps));
    static const char * multilingual[] = {"en-US", "en", "fr-FR"};
    static const char * english[] = {"en"};
    caps->native_sample_rate = 16000;
    caps->supports_streaming = true;
    caps->n_languages = englishOnly ? 1 : 3;
    caps->languages = englishOnly ? english : multilingual;
    return TRANSCRIBE_OK;
}
bool transcribe_model_accepts_ext_kind(const transcribe_model *, transcribe_ext_slot slot, uint32_t kind) {
    assert(slot == TRANSCRIBE_EXT_SLOT_STREAM);
    return (modelFamily == "parakeet" && kind == TRANSCRIBE_EXT_KIND_PARAKEET_STREAM) ||
        (modelFamily == "moonshine_streaming" && kind == TRANSCRIBE_EXT_KIND_MOONSHINE_STREAMING_STREAM);
}
bool transcribe_was_truncated(const transcribe_session *) { return wasTruncated; }
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
transcribe_status transcribe_session_init(transcribe_model *, const transcribe_session_params * params,
                                          transcribe_session ** session) {
    assert(params != nullptr && params->struct_size == sizeof(*params) && params->n_threads == 4);
    *session = sessionStatus == TRANSCRIBE_OK ? new transcribe_session : nullptr;
    return sessionStatus;
}
transcribe_status transcribe_stream_begin(transcribe_session *, const transcribe_run_params * params,
                                          const transcribe_stream_params * stream) {
    assert(params->struct_size == sizeof(*params));
    assert(stream->struct_size == sizeof(*stream));
    if (std::string(params->language) != (englishOnly ? "en" : "en-US")) return TRANSCRIBE_ERR_UNSUPPORTED_LANGUAGE;
    assert(stream->family != nullptr);
    if (modelFamily == "moonshine_streaming") {
        assert(stream->family->kind == TRANSCRIBE_EXT_KIND_MOONSHINE_STREAMING_STREAM);
        auto * extension = reinterpret_cast<const transcribe_moonshine_streaming_stream_ext *>(stream->family);
        assert(extension->ext.size == sizeof(*extension));
        assert(extension->min_decode_interval_ms == 500);
        assert(stream->commit_policy == TRANSCRIBE_STREAM_COMMIT_ON_FINALIZE);
    } else {
        assert(stream->family->kind == TRANSCRIBE_EXT_KIND_PARAKEET_STREAM);
        auto * extension = reinterpret_cast<const transcribe_parakeet_stream_ext *>(stream->family);
        assert(extension->ext.size == sizeof(*extension));
        assert(extension->att_context_right == -1);
        assert(stream->commit_policy == TRANSCRIBE_STREAM_COMMIT_AUTO);
    }
    streamFinalized = false;
    wasTruncated = false;
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
    text->committed_text = streamFinalized ? finalTextValue.c_str() : committedText.c_str();
    text->tentative_text = streamFinalized ? "" : tentativeText.c_str();
    return textStatus;
}
transcribe_status transcribe_stream_finalize(transcribe_session *, transcribe_stream_update * update) {
    if (update && update->struct_size < 40) return TRANSCRIBE_ERR_BAD_STRUCT_SIZE;
    ++finalizeCalls;
    streamFinalized = finalizeStatus == TRANSCRIBE_OK;
    return finalizeStatus;
}
const char * transcribe_full_text(const transcribe_session *) { return "Raw rewrite must never replace committed text."; }
void transcribe_session_free(transcribe_session * session) { ++freedSessions; delete session; }
void transcribe_model_free(transcribe_model * model) { ++freedModels; delete model; }
} // extern "C"

int main() {
    JNINativeInterface_ functions{};
    functions.NewStringUTF = newString;
    functions.NewString = newUtf16String;
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
    expectError("Reading speech transcript", TRANSCRIBE_ERR_BACKEND);
    textStatus = TRANSCRIBE_OK;
    assert(restart(&env, nullptr, handle, locale, -1) == JNI_TRUE);

    finalizeStatus = TRANSCRIBE_ERR_BACKEND;
    assert(finalize(&env, nullptr, handle) == nullptr);
    expectError("Finalizing speech transcript", TRANSCRIBE_ERR_BACKEND);
    finalizeStatus = TRANSCRIBE_OK;
    destroy(&env, nullptr, handle);
    assert(freedModels == 1 && freedSessions == 1);

    loadStatus = TRANSCRIBE_ERR_GGUF;
    assert(init(&env, nullptr, path, locale, -1) == 0);
    expectError("Loading speech model", TRANSCRIBE_ERR_GGUF);
    assert(freedModels == 1 && freedSessions == 1);
    loadStatus = TRANSCRIBE_OK;

    sessionStatus = TRANSCRIBE_ERR_BACKEND;
    assert(init(&env, nullptr, path, locale, -1) == 0);
    expectError("Creating speech session", TRANSCRIBE_ERR_BACKEND);
    assert(freedModels == 2 && freedSessions == 1);
    sessionStatus = TRANSCRIBE_OK;

    beginStatus = TRANSCRIBE_ERR_UNSUPPORTED_LANGUAGE;
    assert(init(&env, nullptr, path, locale, -1) == 0);
    expectError("Starting speech stream", TRANSCRIBE_ERR_UNSUPPORTED_LANGUAGE);
    assert(freedModels == 3 && freedSessions == 2);

    beginStatus = TRANSCRIBE_OK;
    englishOnly = true;
    for (const char * family : {"parakeet", "moonshine_streaming"}) {
        modelFamily = family;
        const jlong alternative = init(&env, nullptr, path, locale, -1);
        assert(alternative != 0 && pendingException.empty());
        assert(Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeWasTruncated(&env, nullptr, alternative) == JNI_FALSE);
        wasTruncated = true;
        assert(Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeWasTruncated(&env, nullptr, alternative) == JNI_TRUE);
        auto stableFinal = finalize(&env, nullptr, alternative);
        assert(javaString(stableFinal) == "Hello world.");
        deleteString(stableFinal);
        assert(restart(&env, nullptr, alternative, locale, -1) == JNI_TRUE);
        assert(Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeWasTruncated(&env, nullptr, alternative) == JNI_FALSE);
        destroy(&env, nullptr, alternative);
    }
    modelFamily = "unsupported";
    assert(init(&env, nullptr, path, locale, -1) == 0);
    expectError("Starting speech stream", TRANSCRIBE_ERR_NOT_IMPLEMENTED);
    modelFamily = "moonshine_streaming";
    language = "fr-FR";
    assert(init(&env, nullptr, path, locale, -1) == 0);
    expectError("Starting speech stream", TRANSCRIBE_ERR_UNSUPPORTED_LANGUAGE);
    assert(freedModels == 7 && freedSessions == 6);

    language = "en-US";
    const jlong unicodeHandle = init(&env, nullptr, path, locale, -1);
    assert(unicodeHandle != 0);
    committedText = u8"नमस्ते 𠮷 ";
    tentativeText = u8"🙂";
    finalTextValue = committedText + tentativeText;
    auto unicodePartial = feed(&env, nullptr, unicodeHandle, samples);
    assert(javaString(unicodePartial) == committedText + '\x01' + tentativeText);
    const std::u16string expectedPartial = u"नमस्ते 𠮷 \x01🙂";
    assert(std::vector<jchar>(expectedPartial.begin(), expectedPartial.end()) == lastStringUnits);
    deleteString(unicodePartial);
    auto unicodeFinal = finalize(&env, nullptr, unicodeHandle);
    assert(javaString(unicodeFinal) == finalTextValue);
    const std::u16string expectedFinal = u"नमस्ते 𠮷 🙂";
    assert(std::vector<jchar>(expectedFinal.begin(), expectedFinal.end()) == lastStringUnits);
    deleteString(unicodeFinal);
    destroy(&env, nullptr, unicodeHandle);

    // Truncated, overlong, surrogate, and out-of-range UTF-8 never reach CheckJNI.
    for (const auto & invalid : {std::string("\xE2\x82"), std::string("\xC0\xAF"),
                                std::string("\xED\xA0\x80"), std::string("\xF4\x90\x80\x80")}) {
        auto replaced = makeJString(&env, invalid.c_str());
        assert(lastStringUnits == std::vector<jchar>(invalid.size(), 0xFFFD));
        deleteString(replaced);
    }
    auto mixed = makeJString(&env, "A\xFF" "B");
    assert((lastStringUnits == std::vector<jchar>{'A', 0xFFFD, 'B'}));
    deleteString(mixed);
    auto empty = makeJString(&env, nullptr);
    assert(lastStringUnits.empty());
    deleteString(empty);

    std::cout << "JNI contract tests passed: feed/finalize, family extensions, locale mapping, stable final text, Unicode, truncation, cleanup.\n";
}
