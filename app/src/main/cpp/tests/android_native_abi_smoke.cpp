// Android loader/ABI/CPU baseline only: this deliberately does not load a model.
#include "transcribe.h"
#include "ggml.h"
#include "ggml-alloc.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"

#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <stdexcept>
#include <string>

namespace {
constexpr const char* kCommit = "63a44d9239d610b3908e8a66b384924cd4a77217";

void require(bool condition, const std::string& message) {
    if (!condition) throw std::runtime_error(message);
}

template <typename Function>
Function symbol(void* library, const char* name) {
    dlerror();
    void* address = dlsym(library, name);
    const char* error = dlerror();
    require(address != nullptr && error == nullptr,
            std::string("Missing symbol ") + name + ": " + (error ? error : "null address"));
    return reinterpret_cast<Function>(address);
}

#define LOAD_FUNCTION(library, name) const auto name = symbol<decltype(&::name)>(library, #name)

template <typename T>
void check_initializer(void (*initialize)(T*), const char* name) {
    // The public structs use a uint64_t size field even in a 32-bit process.
    constexpr uint64_t marker = UINT64_C(0x0123456789abcdef);
    struct Guarded { uint64_t before; T value; uint64_t after; } guarded{};
    guarded.before = guarded.after = marker;
    std::memset(&guarded.value, 0xa5, sizeof(T));
    initialize(&guarded.value);
    require(guarded.before == marker && guarded.after == marker,
            std::string(name) + " wrote outside its public struct");
    require(guarded.value.struct_size == sizeof(T), std::string(name) + " struct-size mismatch");
    std::printf("PASS struct %s size=%zu\n", name, sizeof(T));
}

void check_transcribe(void* library, const char* directory) {
    LOAD_FUNCTION(library, transcribe_version);
    LOAD_FUNCTION(library, transcribe_version_commit);
    LOAD_FUNCTION(library, transcribe_init_backends);
    LOAD_FUNCTION(library, transcribe_init_backends_default);
    LOAD_FUNCTION(library, transcribe_device_count);
    LOAD_FUNCTION(library, transcribe_device_get);
    LOAD_FUNCTION(library, transcribe_device_info_init);
    LOAD_FUNCTION(library, transcribe_device_get_info);
    LOAD_FUNCTION(library, transcribe_backend_available);
    const char* version = transcribe_version();
    const char* commit = transcribe_version_commit();
    require(version && std::strcmp(version, TRANSCRIBE_VERSION) == 0, "transcribe version mismatch");
    require(commit && std::strlen(commit) >= 7 && std::strlen(commit) <= std::strlen(kCommit) &&
            std::strncmp(commit, kCommit, std::strlen(commit)) == 0, "transcribe source commit mismatch");
    std::printf("PASS transcribe version=%s commit=%s\n", version, commit);

#define CHECK_INITIALIZER(name) \
    check_initializer(symbol<decltype(&::name)>(library, #name), #name)
    CHECK_INITIALIZER(transcribe_model_load_params_init);
    CHECK_INITIALIZER(transcribe_session_params_init);
    CHECK_INITIALIZER(transcribe_run_params_init);
    CHECK_INITIALIZER(transcribe_capabilities_init);
    CHECK_INITIALIZER(transcribe_stream_params_init);
    CHECK_INITIALIZER(transcribe_stream_update_init);
    CHECK_INITIALIZER(transcribe_stream_text_init);
    CHECK_INITIALIZER(transcribe_word_init);
    CHECK_INITIALIZER(transcribe_token_init);
    CHECK_INITIALIZER(transcribe_device_info_init);
#undef CHECK_INITIALIZER

    require(transcribe_init_backends(directory) == TRANSCRIBE_OK, "backend initialization failed");
    require(transcribe_init_backends_default() == TRANSCRIBE_OK, "package-local backend initialization failed");
    const int count = transcribe_device_count();
    require(count > 0 && count < 128, "no usable devices or invalid device count");
    require(transcribe_backend_available(TRANSCRIBE_BACKEND_CPU), "CPU backend unavailable");
    bool found_cpu = false;
    for (int i = 0; i < count; ++i) {
        const auto device = transcribe_device_get(i);
        require(device != nullptr, "registered device is null");
        transcribe_device_info info{};
        transcribe_device_info_init(&info);
        require(transcribe_device_get_info(device, &info) == TRANSCRIBE_OK, "device information failed");
        found_cpu |= info.device_type == TRANSCRIBE_DEVICE_TYPE_CPU;
        std::printf("PASS device name=%s kind=%s\n", info.name ? info.name : "unknown",
                    info.kind ? info.kind : "unknown");
    }
    require(found_cpu, "registry has no CPU device");
    require(transcribe_device_get(-1) == nullptr && transcribe_device_get(count) == nullptr,
            "device enumeration did not reject invalid indices");
}

void check_cpu_math(void* base, void* cpu) {
    LOAD_FUNCTION(base, ggml_init);
    LOAD_FUNCTION(base, ggml_free);
    LOAD_FUNCTION(base, ggml_new_tensor_2d);
    LOAD_FUNCTION(base, ggml_mul_mat);
    LOAD_FUNCTION(base, ggml_add);
    LOAD_FUNCTION(base, ggml_new_graph_custom);
    LOAD_FUNCTION(base, ggml_build_forward_expand);
    LOAD_FUNCTION(base, ggml_backend_alloc_ctx_tensors);
    LOAD_FUNCTION(base, ggml_backend_buffer_free);
    LOAD_FUNCTION(base, ggml_backend_free);
    LOAD_FUNCTION(base, ggml_backend_tensor_set);
    LOAD_FUNCTION(base, ggml_backend_tensor_get);
    LOAD_FUNCTION(base, ggml_backend_graph_compute);
    LOAD_FUNCTION(cpu, ggml_backend_cpu_init);
    LOAD_FUNCTION(cpu, ggml_backend_cpu_set_n_threads);
    LOAD_FUNCTION(cpu, ggml_backend_is_cpu);

    ggml_context* context = nullptr;
    ggml_backend_t backend = nullptr;
    ggml_backend_buffer_t buffer = nullptr;
    const auto cleanup = [&] {
        if (buffer) ggml_backend_buffer_free(buffer);
        if (backend) ggml_backend_free(backend);
        if (context) ggml_free(context);
    };
    try {
        context = ggml_init({1024 * 1024, nullptr, true});
        require(context != nullptr, "GGML context allocation failed");
        backend = ggml_backend_cpu_init();
        require(backend && ggml_backend_is_cpu(backend), "GGML CPU initialization failed");
        ggml_backend_cpu_set_n_threads(backend, 2);

        auto* a = ggml_new_tensor_2d(context, GGML_TYPE_F32, 3, 2);
        auto* b = ggml_new_tensor_2d(context, GGML_TYPE_F32, 3, 2);
        auto* bias = ggml_new_tensor_2d(context, GGML_TYPE_F32, 2, 2);
        auto* product = ggml_mul_mat(context, a, b);
        auto* result = ggml_add(context, product, bias);
        auto* graph = ggml_new_graph_custom(context, 32, false);
        ggml_build_forward_expand(graph, result);
        buffer = ggml_backend_alloc_ctx_tensors(context, backend);
        require(buffer != nullptr, "GGML tensor allocation failed");
        const std::array<float, 6> a_values{1, 2, 3, 4, 5, 6};
        const std::array<float, 6> b_values{7, 8, 9, 10, 11, 12};
        const std::array<float, 4> bias_values{1, -2, 3, -4};
        const std::array<float, 4> expected{51, 120, 71, 163};
        ggml_backend_tensor_set(a, a_values.data(), 0, sizeof(a_values));
        ggml_backend_tensor_set(b, b_values.data(), 0, sizeof(b_values));
        ggml_backend_tensor_set(bias, bias_values.data(), 0, sizeof(bias_values));
        require(ggml_backend_graph_compute(backend, graph) == GGML_STATUS_SUCCESS, "GGML graph failed");
        std::array<float, 4> actual{};
        ggml_backend_tensor_get(result, actual.data(), 0, sizeof(actual));
        for (size_t i = 0; i < actual.size(); ++i) {
            require(std::isfinite(actual[i]) && std::fabs(actual[i] - expected[i]) <= 0.0001f,
                    "GGML matrix multiplication/addition returned the wrong result at " + std::to_string(i));
        }
        std::printf("PASS GGML CPU matmul+add [%.0f, %.0f, %.0f, %.0f], threads=2\n",
                    actual[0], actual[1], actual[2], actual[3]);
    } catch (...) {
        cleanup();
        throw;
    }
    cleanup();
}
} // namespace

int main(int argc, char** argv) {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    if (argc != 2) {
        std::fprintf(stderr, "Usage: %s /absolute/library/directory\n", argv[0]);
        return 2;
    }
    try {
        require(argv[1][0] == '/', "library directory must be absolute");
        std::printf("Android native smoke: pointer_bits=%zu; no model inference\n", sizeof(void*) * 8);
        constexpr std::array<const char*, 5> names{
            "libggml-base.so", "libggml-cpu.so", "libggml.so", "libtranscribe.so", "libnemotron_jni.so"};
        std::array<void*, 5> libraries{};
        for (size_t i = 0; i < names.size(); ++i) {
            const std::string path = std::string(argv[1]) + "/" + names[i];
            libraries[i] = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
            const char* error = libraries[i] ? nullptr : dlerror();
            require(libraries[i] != nullptr, path + ": " + (error ? error : "dlopen failed"));
            std::printf("PASS dlopen RTLD_NOW %s\n", names[i]);
        }
        // Keep handles alive until process exit: registered backend pointers belong to these libraries.
        constexpr std::array<const char*, 7> entrypoints{
            "nativeInit", "nativeFeedPcm", "nativeFinalizeStream", "nativeWordTimings",
            "nativeRestartStream", "nativeWasTruncated", "nativeDestroy"};
        for (const char* entry : entrypoints) {
            const std::string name = std::string("Java_com_sainadh_livenotes_stt_NemotronTranscriber_") + entry;
            (void)symbol<void*>(libraries[4], name.c_str());
            std::printf("PASS JNI export %s\n", entry);
        }
        check_transcribe(libraries[3], argv[1]);
        check_cpu_math(libraries[0], libraries[1]);
        std::puts("PASS native ABI smoke (loader, JNI exports, transcribe API, GGML CPU; no model inference)");
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "FAIL native ABI smoke: %s\n", error.what());
        return 1;
    }
}
