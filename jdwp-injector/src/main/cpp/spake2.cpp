#include <jni.h>
#include <openssl/curve25519.h>
#include <openssl/hkdf.h>
#include <openssl/evp.h>

#include "log.h"

#define LOG_TAG "spake2"

static jlong Spake2_Create(JNIEnv *env, jobject obj, jbyteArray jPasswordBytes, jbyteArray jMessageBytes) {
    uint8_t kClientName[] = "adb pair client";
    uint8_t kServerName[] = "adb pair server";
    auto spake2 = SPAKE2_CTX_new(spake2_role_alice, kClientName, sizeof(kClientName), kServerName, sizeof(kServerName));
    if (spake2 == nullptr) {
        LOGE("Unable to create a SPAKE2 context.");
        return 0;
    }
    // Generate the SPAKE2 public key
    size_t key_size = 0;
    uint8_t key[SPAKE2_MAX_MSG_SIZE];
    auto passwordBytes = env->GetByteArrayElements(jPasswordBytes, nullptr);
    auto passwordLength = env->GetArrayLength(jPasswordBytes);
    int status = SPAKE2_generate_msg(spake2, key, &key_size, SPAKE2_MAX_MSG_SIZE, (uint8_t *) passwordBytes, passwordLength);
    if (status != 1 || key_size == 0) {
        LOGE("Unable to generate the SPAKE2 public key.");
        env->ReleaseByteArrayElements(jPasswordBytes, passwordBytes, 0);
        return 0;
    }
    env->ReleaseByteArrayElements(jPasswordBytes, passwordBytes, 0);
    env->SetByteArrayRegion(jMessageBytes, 0, (jsize) key_size, (jbyte *) key);
    return (jlong) spake2;
}

static jbyteArray Spake2_ProcessMessage(JNIEnv *env, jobject obj, jlong spake2_address, jbyteArray jTheirMessage) {
    auto theirMessageBytes = env->GetByteArrayElements(jTheirMessage, nullptr);
    auto theirMessageLength = env->GetArrayLength(jTheirMessage);
    // Don't even try to process a message over the SPAKE2_MAX_MSG_SIZE
    if (theirMessageLength > SPAKE2_MAX_MSG_SIZE) {
        LOGE("theirMessage size greater then max size");
        return nullptr;
    }
    auto spake2 = (SPAKE2_CTX *) spake2_address;
    size_t key_material_len = 0;
    uint8_t key_material[SPAKE2_MAX_KEY_SIZE];
    int status = SPAKE2_process_msg(spake2, key_material, &key_material_len,
                                    sizeof(key_material), (uint8_t *) theirMessageBytes, theirMessageLength);
    env->ReleaseByteArrayElements(jTheirMessage, theirMessageBytes, 0);
    if (status != 1) {
        LOGE("Unable to process their public key");
        return nullptr;
    }

    uint8_t key[16];
    uint8_t info[] = "adb pairing_auth aes-128-gcm key";
    HKDF(key, sizeof(key), EVP_sha256(), key_material, key_material_len, nullptr, 0, info, sizeof(info) - 1);

    jbyteArray hkdfBytes = env->NewByteArray(16);
    env->SetByteArrayRegion(hkdfBytes, 0, 16, (jbyte *) key);
    return hkdfBytes;
}

static void Spake2_Destroy(JNIEnv *env, jobject obj, jlong spake2_address) {
    SPAKE2_CTX_free((SPAKE2_CTX *) spake2_address);
}

JNIEXPORT jint

JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    JNINativeMethod methods[] = {
            {"nativeCreate",         "([B[B)J", (void *) Spake2_Create},
            {"nativeProcessMessage", "(J[B)[B", (void *) Spake2_ProcessMessage},
            {"nativeDestroy",        "(J)V",    (void *) Spake2_Destroy},
    };
    env->RegisterNatives(env->FindClass("com/wuyr/jdwp_injector/adb/Spake2"), methods, sizeof(methods) / sizeof(JNINativeMethod));
    return JNI_VERSION_1_6;
}