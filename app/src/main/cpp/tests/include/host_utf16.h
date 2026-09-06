#pragma once
// Test JVM stand-in: encode Java UTF-16 output for readable host assertions.
#include <jni.h>
#include <string>
#include <stdexcept>
inline std::string hostUtf16ToUtf8(const jchar * units, jsize length) {
    std::string output;
    for (jsize i = 0; i < length; ++i) {
        uint32_t codepoint = units[i];
        if (codepoint >= 0xD800 && codepoint <= 0xDBFF) {
            if (i + 1 == length || units[i + 1] < 0xDC00 || units[i + 1] > 0xDFFF) {
                throw std::runtime_error("Unpaired high surrogate returned to Java");
            }
            codepoint = 0x10000 + ((codepoint - 0xD800) << 10) + (units[++i] - 0xDC00);
        } else if (codepoint >= 0xDC00 && codepoint <= 0xDFFF) {
            throw std::runtime_error("Unpaired low surrogate returned to Java");
        }
        if (codepoint < 0x80) output += static_cast<char>(codepoint);
        else if (codepoint < 0x800) {
            output += static_cast<char>(0xC0 | (codepoint >> 6));
            output += static_cast<char>(0x80 | (codepoint & 0x3F));
        } else if (codepoint < 0x10000) {
            output += static_cast<char>(0xE0 | (codepoint >> 12));
            output += static_cast<char>(0x80 | ((codepoint >> 6) & 0x3F));
            output += static_cast<char>(0x80 | (codepoint & 0x3F));
        } else {
            output += static_cast<char>(0xF0 | (codepoint >> 18));
            output += static_cast<char>(0x80 | ((codepoint >> 12) & 0x3F));
            output += static_cast<char>(0x80 | ((codepoint >> 6) & 0x3F));
            output += static_cast<char>(0x80 | (codepoint & 0x3F));
        }
    }
    return output;
}
