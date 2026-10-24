#pragma clang diagnostic push
#pragma clang diagnostic push
#pragma ide diagnostic ignored "clion-misra-cpp2008-5-0-11"
#pragma ide diagnostic ignored "clion-misra-cpp2023-5-13-4"
#pragma clang diagnostic push
#pragma ide diagnostic ignored "clion-misra-cpp2008-2-13-3"
#pragma ide diagnostic ignored "clion-misra-cpp2023-8-2-2"
#pragma ide diagnostic ignored "clion-misra-cpp2008-5-2-4"
#pragma ide diagnostic ignored "clion-misra-cpp2008-5-0-6"
#pragma ide diagnostic ignored "clion-misra-cpp2008-5-0-4"
#pragma ide diagnostic ignored "clion-misra-cpp2023-6-0-3"
#pragma ide diagnostic ignored "clion-misra-cpp2023-21-6-1"
#pragma ide diagnostic ignored "clion-misra-cpp2023-0-1-2"
#pragma ide diagnostic ignored "clion-misra-cpp2008-0-1-7"
#pragma ide diagnostic ignored "clion-misra-cpp2008-6-4-5"
#pragma ide diagnostic ignored "clion-misra-cpp2008-6-6-5"

#include <jni.h>
#include <string>
#include <vector>
#include <cstdint>
#include <sstream>
#include <iterator>
#include "xxtea.h"

#pragma clang diagnostic push
#pragma ide diagnostic ignored "clion-misra-cpp2008-7-3-1"

std::string getData(int operationId, const std::string &key, const std::string &data) {
    switch (operationId) {

        case 1: {
            auto encryptedBytes = xxtea::encrypt(data, key);

            std::stringstream result;

            std::copy(
                    encryptedBytes.begin(),
                    encryptedBytes.end(),
                    std::ostream_iterator<std::uint32_t>(result, " ")
            );

            return result.str();
        }

        case 2: {
            std::istringstream inputStream(data);

            std::vector<std::uint32_t> bytesToDecrypt{
                    std::istream_iterator<std::uint32_t>(inputStream),
                    std::istream_iterator<std::uint32_t>()
            };

            return xxtea::decrypt(bytesToDecrypt, key);
        }

        default:
            return "";
    }
}

extern "C" JNIEXPORT jcharArray JNICALL
Java_com_starlight_utils_NativeKeyLoader_getChars(
        JNIEnv *env,
        jobject,
        jint operationId,
        jstring source
) {
    const uint8_t encryptedKey[] = {
            0x39, 0x27, 0x64, 0x66, 0x1F, 0x0B, 0x24, 0x6D, 0x68, 0x08, 0x66, 0x30, 0x37, 0x20,
            0x62, 0x0A, 0x1D, 0x1D, 0x22, 0x6F, 0x1A, 0x2B, 0x09, 0x01, 0x3C, 0x04, 0x6A, 0x67,
            0x3B, 0x18, 0x24, 0x14, 0x62, 0x73, 0x1C, 0x6D, 0x0F, 0x71, 0x27, 0x24, 0x0B, 0x1E,
            0x60, 0x6F, 0x37, 0x3E, 0x00, 0x21, 0x71, 0x1B, 0x21, 0x35, 0x2C, 0x1A, 0x1C, 0x3F,
            0x7E, 0x75, 0x18, 0x22, 0x67, 0x0A, 0x36, 0x67
    };

    const size_t keyLength = sizeof(encryptedKey);
    const uint8_t xorMask = 0x5A;

    std::string key;
    key.reserve(keyLength);
    for (unsigned char i : encryptedKey) {
        key.push_back(static_cast<char>(i ^ xorMask));
    }

    const char *nativeSource = env->GetStringUTFChars(source, nullptr);
    std::string data(nativeSource);
    env->ReleaseStringUTFChars(source, nativeSource);

    std::string result = getData(operationId, key, data);
    jsize len = result.length();

    jcharArray resultChars = env->NewCharArray(len);

    std::vector<jchar> jchars(len);
    for (jsize i = 0; i < len; ++i) {
        jchars[i] = static_cast<jchar>(static_cast<unsigned char>(result[i]));
    }

    env->SetCharArrayRegion(resultChars, 0, len, jchars.data());

    std::fill(result.begin(), result.end(), 0);
    std::fill(jchars.begin(), jchars.end(), 0);
    std::fill(key.begin(), key.end(), 0);

    return resultChars;
}
#pragma clang diagnostic pop