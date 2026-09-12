#pragma once
#include <cstdarg>
#include <cstdio>

// The shared JNI bridge uses only this small Android logging surface.
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_ERROR 6
inline int __android_log_print(int, const char * tag, const char * format, ...) {
    std::fprintf(stderr, "[%s] ", tag);
    va_list arguments;
    va_start(arguments, format);
    const int count = std::vfprintf(stderr, format, arguments);
    va_end(arguments);
    std::fputc('\n', stderr);
    return count;
}
