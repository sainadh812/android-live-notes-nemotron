// Finite-audio smoke test of the actual JNI bridge linked to a host build of
// the pinned engine. This does not exercise Android hardware or arm64 code.
#include <chrono>
#include <algorithm>
#include <cctype>
#include <sstream>
#include <cstring>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <vector>

#include "../nemotron_jni.cpp"
#include "host_utf16.h"

namespace {
std::string pendingException;
std::string & stringValue(jstring value) { return *reinterpret_cast<std::string *>(value); }
jstring newUtf16String(JNIEnv *, const jchar * units, jsize length) {
    return reinterpret_cast<jstring>(new std::string(hostUtf16ToUtf8(units, length)));
}
const char * stringChars(JNIEnv *, jstring value, jboolean *) { return stringValue(value).c_str(); }
void releaseChars(JNIEnv *, jstring, const char *) {}
jboolean hasException(JNIEnv *) { return !pendingException.empty(); }
jclass findClass(JNIEnv *, const char *) { return reinterpret_cast<jclass>(1); }
jint throwException(JNIEnv *, jclass, const char * message) { pendingException = message; return 0; }
void deleteLocalRef(JNIEnv *, jobject) {}
jsize arrayLength(JNIEnv *, jarray array) { return static_cast<jsize>(reinterpret_cast<std::vector<float> *>(array)->size()); }
jfloat * arrayElements(JNIEnv *, jfloatArray array, jboolean *) { return reinterpret_cast<std::vector<float> *>(array)->data(); }
void releaseElements(JNIEnv *, jfloatArray, jfloat *, jint) {}
void checkJava() { if (!pendingException.empty()) throw std::runtime_error(pendingException); }
std::string takeString(jstring value) {
    checkJava();
    if (value == nullptr) throw std::runtime_error("JNI returned null without an exception");
    std::string result = stringValue(value);
    delete reinterpret_cast<std::string *>(value);
    return result;
}
uint32_t little32(const char * bytes) {
    return static_cast<unsigned char>(bytes[0]) | (static_cast<uint32_t>(static_cast<unsigned char>(bytes[1])) << 8) |
        (static_cast<uint32_t>(static_cast<unsigned char>(bytes[2])) << 16) |
        (static_cast<uint32_t>(static_cast<unsigned char>(bytes[3])) << 24);
}
uint16_t little16(const char * bytes) {
    return static_cast<unsigned char>(bytes[0]) | (static_cast<uint16_t>(static_cast<unsigned char>(bytes[1])) << 8);
}
std::vector<float> readWav(const char * path) {
    std::ifstream input(path, std::ios::binary);
    char header[12];
    if (!input.read(header, 12) || std::memcmp(header, "RIFF", 4) || std::memcmp(header + 8, "WAVE", 4)) {
        throw std::runtime_error("Expected a RIFF WAV sample");
    }
    bool formatValidated = false;
    while (input.read(header, 8)) {
        const auto size = little32(header + 4);
        if (size > 16 * 1024 * 1024) throw std::runtime_error("Sample exceeds finite-test input limit");
        std::vector<char> bytes(size);
        if (!input.read(bytes.data(), size)) throw std::runtime_error("Truncated WAV");
        if (!std::memcmp(header, "fmt ", 4)) {
            formatValidated = size >= 16 && little16(bytes.data()) == 1 && little16(bytes.data() + 2) == 1 &&
                little32(bytes.data() + 4) == 16000 && little16(bytes.data() + 14) == 16;
        } else if (!std::memcmp(header, "data", 4)) {
            if (!formatValidated || size % 2 != 0) throw std::runtime_error("Expected 16 kHz mono PCM16 WAV");
            std::vector<float> samples(size / 2);
            for (size_t i = 0; i < samples.size(); ++i) {
                samples[i] = static_cast<int16_t>(little16(bytes.data() + 2 * i)) / 32768.0f;
            }
            return samples;
        }
        if (size % 2) input.seekg(1, std::ios::cur);
    }
    throw std::runtime_error("No WAV audio found");
}
} // namespace

int main(int argc, char ** argv) {
    if (argc != 3) { std::cerr << "Usage: speech_model_smoke_test model.gguf sample.wav\n"; return 2; }
    JNINativeInterface_ functions{};
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
    jlong handle = 0;
    try {
        const auto audio = readWav(argv[2]);
        std::string path = argv[1], language = "en-US";
        const auto start = std::chrono::steady_clock::now();
        handle = Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeInit(
            &env, nullptr, reinterpret_cast<jstring>(&path), reinterpret_cast<jstring>(&language), -1);
        checkJava();
        if (handle == 0) throw std::runtime_error("No native session");
        const auto * native = reinterpret_cast<NativeSession *>(handle);
        std::cout << "Loaded family=" << transcribe_model_arch_string(native->model)
                  << " variant=" << transcribe_model_variant_string(native->model) << std::endl;
        const auto initialized = std::chrono::steady_clock::now();
        std::vector<long long> feedDurations;
        std::string committed;
        for (size_t offset = 0; offset < audio.size(); offset += 8000) {
            std::vector<float> chunk(audio.begin() + offset, audio.begin() + std::min(audio.size(), offset + 8000));
            const auto feedStart = std::chrono::steady_clock::now();
            const auto text = takeString(Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeFeedPcm(
                &env, nullptr, handle, reinterpret_cast<jfloatArray>(&chunk)));
            feedDurations.push_back(std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - feedStart).count());
            const auto next = text.substr(0, text.find('\x01'));
            if (next.compare(0, committed.size(), committed) != 0) throw std::runtime_error("Committed prefix changed");
            committed = next;
            if (Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeWasTruncated(&env, nullptr, handle)) {
                throw std::runtime_error("Sample was truncated during feed");
            }
        }
        const auto text = takeString(Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeFinalizeStream(&env, nullptr, handle));
        if (text.empty() || text.compare(0, committed.size(), committed) != 0) {
            throw std::runtime_error("Final snapshot lost committed text");
        }
        if (Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeWasTruncated(&env, nullptr, handle)) {
            throw std::runtime_error("Sample was truncated at finalize");
        }
        if (std::string(argv[2]).find("jfk.wav") != std::string::npos) {
            std::string normalized;
            for (const unsigned char character : text) {
                if (std::isalpha(character) || std::isspace(character)) normalized += static_cast<char>(std::tolower(character));
            }
            if (normalized.find("ask not what your country can do for you") == std::string::npos ||
                normalized.find("ask what you can do for your country") == std::string::npos) {
                throw std::runtime_error("JFK reference content is incomplete: " + text);
            }
        }
        const auto wordTiming = takeString(Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeWordTimings(&env, nullptr, handle));
        if (std::string(transcribe_model_arch_string(native->model)) == "parakeet" && wordTiming.empty()) {
            throw std::runtime_error("Parakeet word alignment was not returned");
        }
        std::cout << "word_timing_begin\n" << wordTiming << "word_timing_end\n";
        if (!Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeRestartStream(
            &env, nullptr, handle, reinterpret_cast<jstring>(&language), -1)) {
            checkJava();
            throw std::runtime_error("Restart failed");
        }
        const auto loadMs = std::chrono::duration_cast<std::chrono::milliseconds>(initialized - start).count();
        std::sort(feedDurations.begin(), feedDurations.end());
        const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
        std::cout << "PASS actual JNI: " << argv[1] << "\naudio_samples=" << audio.size() << " elapsed_ms=" << elapsed
                  << " load_ms=" << loadMs << " feed_p50_ms=" << feedDurations.at(feedDurations.size() / 2) / 1000.0
                  << " feed_max_ms=" << feedDurations.back() / 1000.0 << " threads=4\ntext=" << text << "\n";
        Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeDestroy(&env, nullptr, handle);
        return 0;
    } catch (const std::exception & error) {
        Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeDestroy(&env, nullptr, handle);
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
