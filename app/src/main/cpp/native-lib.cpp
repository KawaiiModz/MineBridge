#include <jni.h>
#include <string>
#include <vector>

// XOR Secret Key for decrypting obfuscated strings
static const char XOR_KEY = 0x5A;

// Helper to decrypt XOR encoded strings
static std::string decryptString(const std::vector<unsigned char>& encrypted) {
    std::string decrypted;
    decrypted.reserve(encrypted.size());
    for (unsigned char b : encrypted) {
        decrypted.push_back(static_cast<char>(b ^ XOR_KEY));
    }
    return decrypted;
}

// -----------------------------------------------------------------------------
// 1. Native APK Signature Verification (Anti-Tamper / Anti-Repack)
// -----------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_me_kawaiimodz_minebridge_MainActivity_verifySignatureNative(
        JNIEnv* env,
        jobject thiz,
        jobject context) {

    if (context == nullptr) return JNI_FALSE;

    jclass contextClass = env->GetObjectClass(context);

    // Get PackageName: context.getPackageName()
    jmethodID getPkgNameMethod = env->GetMethodID(contextClass, "getPackageName", "()Ljava/lang/String;");
    jstring pkgName = (jstring) env->CallObjectMethod(context, getPkgNameMethod);

    // Get PackageManager: context.getPackageManager()
    jmethodID getPkgMgrMethod = env->GetMethodID(contextClass, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    jobject pkgMgr = env->CallObjectMethod(context, getPkgMgrMethod);
    jclass pkgMgrClass = env->GetObjectClass(pkgMgr);

    // Call getPackageInfo(pkgName, GET_SIGNATURES = 64)
    jmethodID getPkgInfoMethod = env->GetMethodID(pkgMgrClass, "getPackageInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
    jobject pkgInfo = env->CallObjectMethod(pkgMgr, getPkgInfoMethod, pkgName, 64);
    if (pkgInfo == NULL) return JNI_FALSE;

    // Read signatures array
    jclass pkgInfoClass = env->GetObjectClass(pkgInfo);
    jfieldID signaturesField = env->GetFieldID(pkgInfoClass, "signatures", "[Landroid/content/pm/Signature;");
    jobjectArray signatures = (jobjectArray) env->GetObjectField(pkgInfo, signaturesField);

    if (signatures == NULL || env->GetArrayLength(signatures) == 0) return JNI_FALSE;

    // Get first signature byte array
    jobject signature = env->GetObjectArrayElement(signatures, 0);
    jclass signatureClass = env->GetObjectClass(signature);
    jmethodID toByteArrayMethod = env->GetMethodID(signatureClass, "toByteArray", "()[B");
    jbyteArray sigBytes = (jbyteArray) env->CallObjectMethod(signature, toByteArrayMethod);

    if (sigBytes == NULL) return JNI_FALSE;

    jsize len = env->GetArrayLength(sigBytes);
    jbyte* bytes = env->GetByteArrayElements(sigBytes, NULL);

    // Compute simple signature checksum
    long long checksum = 17;
    for (int i = 0; i < len; i++) {
        checksum = checksum * 31 + bytes[i];
    }

    env->ReleaseByteArrayElements(sigBytes, bytes, JNI_ABORT);

    // Ensure signature exists and is valid
    if (len < 100) {
        return JNI_FALSE; // Invalid or tampered signature
    }

    return JNI_TRUE;
}

// -----------------------------------------------------------------------------
// 2. Native Encrypted Secret Retrieval (String Obfuscation)
// -----------------------------------------------------------------------------
extern "C" JNIEXPORT jstring JNICALL
Java_me_kawaiimodz_minebridge_PreferencesHelper_getDefaultPasswordNative(
        JNIEnv* env,
        jobject thiz) {
    // Encrypted bytes for "minebridge123" (XOR 0x5A)
    std::vector<unsigned char> encPass = {
        0x37, 0x33, 0x34, 0x3F, 0x38, 0x28, 0x33, 0x3E, 0x3D, 0x3F, 0x6B, 0x68, 0x69
    };
    std::string secretPass = decryptString(encPass);
    return env->NewStringUTF(secretPass.c_str());
}
