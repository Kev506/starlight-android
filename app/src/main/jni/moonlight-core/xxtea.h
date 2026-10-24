#pragma clang diagnostic push
#pragma ide diagnostic ignored "clion-misra-cpp2023-6-2-4"
#pragma ide diagnostic ignored "clion-misra-cpp2008-6-5-2"
#pragma ide diagnostic ignored "clion-misra-cpp2023-11-6-1"
#pragma ide diagnostic ignored "clion-misra-cpp2023-21-6-1"
#pragma ide diagnostic ignored "clion-misra-cpp2008-5-0-6"
#pragma ide diagnostic ignored "clion-misra-cpp2008-6-6-5"
#pragma ide diagnostic ignored "clion-misra-cpp2008-5-0-4"
#pragma once

#include <vector>
#include <string>
#include <cstdint>
#include <climits>
#include <limits>

/**
 * XXTEA encryption algorithm library for C++.
 *
 * Encryption Algorithm Authors:
 *       David J. Wheeler
 *       Roger M. Needham
 *
 * The algorithm description is here: https://en.wikipedia.org/wiki/XXTEA
 */

// acording to this stackexchange https://crypto.stackexchange.com/a/12997 comment
// XXTAE security can be increase by increasing the number of mixes,
// so i've put this to a constant
#define XXTEA_NUMBER_OF_MIXES 6
#define XXTEA_MX (((z>>5U^y<<2U) + (y>>3U^z<<4U)) ^ ((sum^y) + (k[(p&3U)^e] ^ z)))

namespace xxtea {
    using bytes = std::vector<std::uint32_t>;

    namespace internal {
        constexpr std::size_t round_up(std::size_t num, std::size_t multiple) {
            auto remainder = num % multiple;
            if (remainder == 0) {
                return num;
            }

            return num + multiple - remainder;
        }

        template<class BlockType>
        std::vector<BlockType> to_blocks(std::string string) {
            constexpr unsigned short block_size = std::numeric_limits<BlockType>::digits;

            static_assert(block_size >= CHAR_BIT, "Can't be smaller than CHAR_BIT");

            string.resize(round_up(string.size(), block_size), '\x03');

            auto number_of_bits = string.size() * CHAR_BIT;
            std::vector<BlockType> blocks{};
            auto n_blocks = number_of_bits / block_size;
            n_blocks = std::max(n_blocks, decltype(n_blocks){1});
            blocks.resize(n_blocks, 0);

            constexpr auto fit_size = block_size / CHAR_BIT;

            for (std::size_t i = 0; i < string.size(); ++i) {
                auto bucket = (i * CHAR_BIT) / block_size;
                auto shift = CHAR_BIT * (i % fit_size);
                auto c = static_cast<unsigned int>(string[i]);
                blocks[bucket] += c << shift;

            }

            return blocks;
        }

        template<class BlockType>
        std::string to_string(const std::vector<BlockType> &blocks) {
            constexpr unsigned short block_size = std::numeric_limits<BlockType>::digits;
            constexpr auto fit_size = block_size / CHAR_BIT;
            std::string s;
            s.reserve(fit_size * blocks.size());

            for (const auto &i: blocks) {
                for (std::size_t j = 0; j < fit_size; ++j) {
                    auto shift = CHAR_BIT * (j % fit_size);
                    auto c = static_cast<char >(i >> shift);
                    if (c != '\x03') {
                        s.push_back(c);
                    } else {
                        break;
                    }
                }
            }

            return s;
        }

        constexpr unsigned long delta{0x9E3779B9};
    }

/**
 * encodes an array of unsigned 32-bit integers using 128-bit key.
 * @param v vector of uint32
 * @param k 128-bit key, if smaller the vector will be padded
 */
    inline void encode(bytes &v, const bytes &k) {
        if (v.empty()) {
            return;
        }

        auto n = v.size();

        unsigned long z{v[n - 1]};
        unsigned long y{v[0]};
        unsigned long sum = 0;
        long rounds = XXTEA_NUMBER_OF_MIXES + 52 / n;
        std::size_t p;

        while (rounds-- > 0) {
            sum += internal::delta;
            unsigned long e = (sum >> 2U) & 3U;
            for (p = 0; p < n - 1; ++p) {
                y = v[p + 1];
                v[p] += XXTEA_MX;
                z = v[p];

            }
            y = v[0];
            v[n - 1] += XXTEA_MX;
            z = v[n - 1];
        }
    }

/**
 * decodes an array of unsigned 32-bit integers using 128-bit key.
 * @param v array to be decoded
 * @param k 128-bit key
 */
    inline void decode(bytes &v, const bytes &k) {
        if (v.empty()) {
            return;
        }

        auto n = v.size();

        unsigned long z{v[n - 1]};
        unsigned long y{v[0]};
        long rounds = XXTEA_NUMBER_OF_MIXES + 52 / n;
        unsigned long sum = rounds * internal::delta;
        std::size_t p;

        for (; sum != 0; sum -= internal::delta) {
            unsigned long e = (sum >> 2U) & 3U;
            for (p = n - 1; p > 0; --p) {
                z = v[p - 1];
                v[p] -= XXTEA_MX;
                y = v[p];

            }
            z = v[n - 1];
            v[0] -= XXTEA_MX;
            y = v[0];
        }
    }

/**
 *  Encrypts text using Corrected Block TEA (aka xxtea) algorithm
 * @param plaintext String to be encrypted. Handles utf-8.
 * @param password Password to be used for encryption (only 128 bits are used).
 * @return Encrypted text as uint32_t vector
 */
    inline bytes encrypt(const std::string &plaintext, const std::string &password) {
        auto text = internal::to_blocks<std::uint32_t>(plaintext);
        auto key = internal::to_blocks<std::uint32_t>(password);

        encode(text, key);

        return text;
    }

/**
 * Decrypts text using Corrected Block TEA (xxtea) algorithm
 * @param encrypted_string @see encrypt
 * @param password password used to encrypt the string
 * @return utf8 encoded string
 */
    inline std::string decrypt(bytes &encrypted_string, const std::string &password) {
        auto key = internal::to_blocks<std::uint32_t>(password);

        decode(encrypted_string, key);

        return internal::to_string<std::uint32_t>(encrypted_string);
    }
}

#pragma clang diagnostic pop
