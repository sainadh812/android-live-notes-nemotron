#pragma once

#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_ERROR 6

// The host contract test does not need Android's logging implementation.
inline int __android_log_print(int, const char *, const char *, ...) { return 0; }
