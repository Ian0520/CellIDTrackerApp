#include "encoder.h"

#include <cryptopp/aes.h>
#include <cryptopp/blowfish.h>
#include <cryptopp/cast.h>
#include <cryptopp/des.h>
#include <cryptopp/filters.h>
#include <cryptopp/ripemd.h>
#include <cryptopp/sha.h>
#include <linux/pfkeyv2.h>

#define CRYPTOPP_ENABLE_NAMESPACE_WEAK 1
#include <cryptopp/md5.h>
#undef CRYPTOPP_ENABLE_NAMESPACE_WEAK

#define MAKE_EALG_ARG(x, y) \
  CryptoPP::x<CryptoPP::y>::Encryption, CryptoPP::x<CryptoPP::y>::Decryption, CryptoPP::y::BLOCKSIZE

namespace {
  template <typename Algorithm>
  std::unique_ptr<CryptoPP::HMAC_Base> makeHMAC(CryptoPP::SecByteBlock* key) {
    return std::make_unique<CryptoPP::HMAC<Algorithm>>(*key, key->size());
  }
}  // namespace

ESP_AALG::ESP_AALG(int algorithmCode, std::span<uint8_t> _key) : algorithm(nullptr) {
  CryptoPP::SecByteBlock key(_key.data(), _key.size());
  switch (algorithmCode) {
    case SADB_AALG_MD5HMAC:
      algorithm = makeHMAC<CryptoPP::Weak::MD5>(&key);
      break;
    case SADB_AALG_SHA1HMAC:
      algorithm = makeHMAC<CryptoPP::SHA1>(&key);
      break;
    case SADB_X_AALG_SHA2_256HMAC:
      algorithm = makeHMAC<CryptoPP::SHA256>(&key);
      break;
    case SADB_X_AALG_SHA2_384HMAC:
      algorithm = makeHMAC<CryptoPP::SHA384>(&key);
      break;
    case SADB_X_AALG_SHA2_512HMAC:
      algorithm = makeHMAC<CryptoPP::SHA512>(&key);
      break;
    case SADB_X_AALG_RIPEMD160HMAC:
      algorithm = makeHMAC<CryptoPP::RIPEMD160>(&key);
      break;
    case SADB_AALG_NONE:
      [[fallthrough]];
    default:
      break;
  }

  switch (algorithmCode) {
    case SADB_AALG_MD5HMAC:
      [[fallthrough]];
    case SADB_AALG_SHA1HMAC:
      [[fallthrough]];
    case SADB_X_AALG_RIPEMD160HMAC:
      digestSize = 12;
      break;
    case SADB_X_AALG_SHA2_256HMAC:
      digestSize = 16;
      break;
    case SADB_X_AALG_SHA2_384HMAC:
      digestSize = 24;
      break;
    case SADB_X_AALG_SHA2_512HMAC:
      digestSize = 32;
      break;
    case SADB_AALG_NONE:
      [[fallthrough]];
    default:
      digestSize = 0;
      break;
  }
}

std::vector<uint8_t> ESP_AALG::hash(std::span<uint8_t> message) {
  // CryptoPP will handle the ownership, so we need to use `new`
  std::vector<uint8_t> mac;
  auto sink = new CryptoPP::VectorSink(mac);
  auto filter = new CryptoPP::HashFilter(*algorithm, sink, false, digestSize);
  CryptoPP::ArraySource(message.data(), message.size(), true, filter);
  return mac;
}

bool ESP_AALG::verify(std::span<const uint8_t> message) {
  // CryptoPP will handle the ownership, so we need to use `new`
  CryptoPP::byte result = 0;
  auto sink = new CryptoPP::ArraySink(&result, sizeof(result));
  auto flag = CryptoPP::HashVerificationFilter::PUT_RESULT
              | CryptoPP::HashVerificationFilter::HASH_AT_END;
  auto filter = new CryptoPP::HashVerificationFilter(*algorithm, sink, flag, digestSize);
  CryptoPP::ArraySource(message.data(), message.size(), true, filter);
  return result;
}

ESP_EALG::ESP_EALG(int algorithmCode, std::span<uint8_t> _key) : enc(nullptr), dec(nullptr) {
  CryptoPP::SecByteBlock key(_key.data(), _key.size());

  switch (algorithmCode) {
    case SADB_X_EALG_AESCBC:
      _ivLength = 16;
      break;
    // case SADB_EALG_DESCBC: [[fallthrough]];           // TODO
    // case SADB_X_EALG_CASTCBC: [[fallthrough]];        // TODO
    // case SADB_X_EALG_BLOWFISHCBC: [[fallthrough]];    // TODO
    // case SADB_X_EALG_AESCTR: [[fallthrough]];         // TODO
    // case SADB_X_EALG_AES_GCM_ICV8: [[fallthrough]];   // TODO
    // case SADB_X_EALG_AES_GCM_ICV12: [[fallthrough]];  // TODO
    // case SADB_X_EALG_AES_GCM_ICV16: [[fallthrough]];  // TODO
    // case SADB_EALG_3DESCBC: [[fallthrough]];          // TODO
    case SADB_EALG_NONE:
      [[fallthrough]];
    case SADB_EALG_NULL:
      [[fallthrough]];
    default:
      _ivLength = 0;
      break;
  }
  switch (algorithmCode) {
    case SADB_X_EALG_AESCBC:
      makeCipher<MAKE_EALG_ARG(CBC_Mode, AES)>(&key);
      break;
    // case SADB_EALG_DESCBC: [[fallthrough]];           // TODO
    // case SADB_X_EALG_CASTCBC: [[fallthrough]];        // TODO
    // case SADB_X_EALG_BLOWFISHCBC: [[fallthrough]];    // TODO
    // case SADB_X_EALG_AESCTR: [[fallthrough]];         // TODO
    // case SADB_X_EALG_AES_GCM_ICV8: [[fallthrough]];   // TODO
    // case SADB_X_EALG_AES_GCM_ICV12: [[fallthrough]];  // TODO
    // case SADB_X_EALG_AES_GCM_ICV16: [[fallthrough]];  // TODO
    // case SADB_EALG_3DESCBC: [[fallthrough]];          // TODO
    case SADB_EALG_NONE:
      [[fallthrough]];
    case SADB_EALG_NULL:
      [[fallthrough]];
    default:
      break;
  }
}

std::vector<uint8_t> ESP_EALG::encrypt(std::span<uint8_t> message) {
  std::vector<uint8_t> cipher;
  if (enc->IsResynchronizable()) {
    cipher.resize(_ivLength);
    prng.GenerateBlock(cipher.data(), cipher.size());
    enc->Resynchronize(cipher.data(), _ivLength);
  }
  auto sink = new CryptoPP::VectorSink(cipher);
  auto filter = new CryptoPP::StreamTransformationFilter(
      *enc, sink, CryptoPP::BlockPaddingSchemeDef::NO_PADDING);
  CryptoPP::ArraySource(message.data(), message.size(), true, filter);
  return cipher;
}

std::vector<uint8_t> ESP_EALG::decrypt(std::span<const uint8_t> message) {
  std::vector<uint8_t> plain;
  if (dec->IsResynchronizable()) {
    dec->Resynchronize(message.data(), _ivLength);
    message = message.last(message.size() - _ivLength);
  }
  auto sink = new CryptoPP::VectorSink(plain);
  auto filter = new CryptoPP::StreamTransformationFilter(
      *dec, sink, CryptoPP::BlockPaddingSchemeDef::NO_PADDING);
  CryptoPP::ArraySource(message.data(), message.size(), true, filter);
  return plain;
}
