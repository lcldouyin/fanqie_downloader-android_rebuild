#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <cstdint>

#define LOG_TAG "FqSigner"
#define SIG_FUNC_OFFSET 0x168c80
#define JNI_ONLOAD_OFFSET 0x58b80

typedef const char* (*sig_func_t)(const char* url, const char* headers);
typedef jint (*jni_onload_t)(JavaVM*, void*);

static JavaVM* g_vm = nullptr;
static void* g_soHandle = nullptr;
static sig_func_t g_sigFunc = nullptr;

static uintptr_t findImageBase(const char* libraryName) {
    FILE* maps = fopen("/proc/self/maps", "r");
    if (!maps) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "could not open /proc/self/maps");
        return 0;
    }

    char line[1024];
    uintptr_t base = 0;
    while (fgets(line, sizeof(line), maps)) {
        if (!strstr(line, libraryName)) continue;

        unsigned long long start = 0;
        unsigned long long fileOffset = 0;
        if (sscanf(line, "%llx-%*llx %*s %llx", &start, &fileOffset) != 2) {
            continue;
        }
        if (fileOffset == 0) {
            // This ELF's first PT_LOAD has p_offset == p_vaddr == 0.
            base = static_cast<uintptr_t>(start);
            break;
        }
    }
    fclose(maps);

    if (!base) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "could not find offset-zero mapping for %s", libraryName);
    }
    return base;
}

extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "FqSigner JNI_OnLoad");
    return JNI_VERSION_1_6;
}

static void ensureLoaded(JNIEnv* env, const char* libPath) {
    if (g_soHandle) return;

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "dlopen: %s", libPath);
    g_soHandle = dlopen(libPath, RTLD_NOW | RTLD_GLOBAL);
    if (!g_soHandle) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "dlopen failed: %s", dlerror());
        return;
    }

    uintptr_t base = findImageBase("libmetasec_ml.so");
    if (!base) return;

    // Call the SO's JNI_OnLoad to initialize internal state
    jni_onload_t soJniOnLoad = (jni_onload_t)dlsym(g_soHandle, "JNI_OnLoad");
    if (soJniOnLoad) {
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Calling SO JNI_OnLoad at %p", soJniOnLoad);
        jint ver = soJniOnLoad(g_vm, nullptr);
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "SO JNI_OnLoad returned %d", ver);
    } else {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "JNI_OnLoad not exported, trying offset 0x%x", JNI_ONLOAD_OFFSET);
        soJniOnLoad = (jni_onload_t)(base + JNI_ONLOAD_OFFSET);
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                            "Calling SO JNI_OnLoad at %p (base+offset)", soJniOnLoad);
        jint ver = soJniOnLoad(g_vm, nullptr);
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "SO JNI_OnLoad returned %d", ver);
    }

    // Resolve the signature function
    g_sigFunc = (sig_func_t)(base + SIG_FUNC_OFFSET);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "sig func at %p (base=%p, offset=0x%x)",
                        g_sigFunc, (void*)base, SIG_FUNC_OFFSET);

    if (!g_sigFunc) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "could not resolve sig func");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_fqdownloader_jni_FqSigner_nativeGenerateSignature(
    JNIEnv* env, jclass clazz, jstring jurl, jstring jheaders, jstring jLibPath) {

    if (!jurl || !jheaders || !jLibPath) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "null argument");
        return nullptr;
    }

    const char* url = env->GetStringUTFChars(jurl, nullptr);
    const char* headers = env->GetStringUTFChars(jheaders, nullptr);
    const char* libPath = env->GetStringUTFChars(jLibPath, nullptr);

    ensureLoaded(env, libPath);

    if (!g_sigFunc) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "sig func not available");
        env->ReleaseStringUTFChars(jurl, url);
        env->ReleaseStringUTFChars(jheaders, headers);
        env->ReleaseStringUTFChars(jLibPath, libPath);
        return nullptr;
    }

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Calling sig func");
    const char* result = g_sigFunc(url, headers);

    env->ReleaseStringUTFChars(jurl, url);
    env->ReleaseStringUTFChars(jheaders, headers);
    env->ReleaseStringUTFChars(jLibPath, libPath);

    if (!result) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "sig func returned null");
        return nullptr;
    }

    jstring jresult = env->NewStringUTF(result);
    return jresult;
}
