#pragma once

#include <cstdint>
#include <memory>
#include <span>
#include <string>
#include <vector>

#if defined(ANDROID_NO_CRYPTO)

class ESP_AALG {
public:
  ESP_AALG(int /*algorithmCode*/, std::span<uint8_t> /*_key*/) : digestSize(0) {}

  std::vector<uint8_t> hash(std::span<uint8_t> /*message*/) { return {}; }
  bool verify(std::span<const uint8_t> /*message*/) { return false; }
  bool empty() const { return true; }
  std::string name() { return "STUB-ESP_AALG"; }
  std::string provider() { return "STUB"; }
  uint32_t hashLength() { return digestSize; }

private:
  uint32_t digestSize;
};

class ESP_EALG {
public:
  ESP_EALG(int /*algorithmCode*/, std::span<uint8_t> /*_key*/)
      : _blockSize(0), _ivLength(0) {}

  template <typename ENC, typename DEC, int blockSize>
  void makeCipher(void* /*key*/) {
    // stub：不做任何事；僅為了配合既有模板呼叫點
    _blockSize = static_cast<uint32_t>(blockSize);
  }

  std::vector<uint8_t> encrypt(std::span<uint8_t> message) {
    // stub：直接回傳空陣列或原文，你可改成回原文以利除錯
    return std::vector<uint8_t>(message.begin(), message.end());
  }

  std::vector<uint8_t> decrypt(std::span<const uint8_t> message) {
    // stub：直接回傳輸入（視需求可改成空陣列）
    return std::vector<uint8_t>(message.begin(), message.end());
  }

  bool empty() const { return true; }
  std::string name() { return "STUB-ESP_EALG"; }
  std::string provider() { return "STUB"; }
  uint32_t ivLength() { return _ivLength; }
  uint32_t blockSize() { return _blockSize; }

private:
  uint32_t _blockSize;
  uint32_t _ivLength;
};

#else

// =========================
// 原始版本（使用 Crypto++）
// =========================

#include <cryptopp/aes.h>
#include <cryptopp/hmac.h>
#include <cryptopp/modes.h>
#include <cryptopp/osrng.h>
#include <cryptopp/secblock.h>

class ESP_AALG {
public:
  ESP_AALG(int algorithmCode, std::span<uint8_t> _key);

  std::vector<uint8_t> hash(std::span<uint8_t> message);
  bool verify(std::span<const uint8_t> message);
  bool empty() const { return algorithm == nullptr; }
  std::string name() { return algorithm->AlgorithmName(); }
  std::string provider() { return algorithm->AlgorithmProvider(); }
  uint32_t hashLength() { return digestSize; }

private:
  uint32_t digestSize;
  std::unique_ptr<CryptoPP::HMAC_Base> algorithm;
};

class ESP_EALG {
public:
  ESP_EALG(int algorithmCode, std::span<uint8_t> _key);

  template <typename ENC, typename DEC, int blockSize>
  void makeCipher(CryptoPP::SecByteBlock* key) {
    CryptoPP::SecByteBlock iv(_ivLength);
    _blockSize = blockSize;
    enc = std::make_unique<ENC>();
    if (enc->IVRequirement() == CryptoPP::SimpleKeyingInterface::NOT_RESYNCHRONIZABLE) {
      enc->SetKey(*key, key->size());
    } else {
      enc->SetKeyWithIV(*key, key->size(), iv, iv.size());
    }
    dec = std::make_unique<DEC>();
    if (dec->IVRequirement() == CryptoPP::SimpleKeyingInterface::NOT_RESYNCHRONIZABLE) {
      dec->SetKey(*key, key->size());
    } else {
      dec->SetKeyWithIV(*key, key->size(), iv, iv.size());
    }
  }

  std::vector<uint8_t> encrypt(std::span<uint8_t> message);
  std::vector<uint8_t> decrypt(std::span<const uint8_t> message);
  bool empty() const { return enc == nullptr; }
  std::string name() { return enc->AlgorithmName(); }
  std::string provider() { return enc->AlgorithmProvider(); }
  uint32_t ivLength() { return _ivLength; }
  uint32_t blockSize() { return _blockSize; }

private:
  uint32_t _blockSize;
  uint32_t _ivLength;
  CryptoPP::AutoSeededRandomPool prng;
  std::unique_ptr<CryptoPP::CipherModeBase> enc;
  std::unique_ptr<CryptoPP::CipherModeBase> dec;
};

#endif // ANDROID_NO_CRYPTO
