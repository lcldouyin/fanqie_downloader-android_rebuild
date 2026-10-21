#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstring>
#include <cstdio>
#include <cstdint>
#include <mutex>
#include <string>

#define LOG_TAG "FqSigner"
#define SIG_FUNC_OFFSET 0x168c80
#define JNI_ONLOAD_OFFSET 0x58b80

typedef const char* (*sig_func_t)(const char* url, const char* headers);
typedef jint (*jni_onload_t)(JavaVM*, void*);

static JavaVM* g_vm = nullptr;
static void* g_soHandle = nullptr;
static sig_func_t g_sigFunc = nullptr;
static bool g_loadFailed = false;
static std::string g_loadError;
static std::mutex g_signerMutex;

static uintptr_t findImageBase(const char* libraryName) {
    FILE* maps = fopen("/proc/self/maps", "r");
    if (!maps) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "could not open /proc/self/maps");
        return 0;
    }

    const char* fileName = strrchr(libraryName, '/');
    fileName = fileName ? fileName + 1 : libraryName;
    char line[1024];
    uintptr_t base = 0;
    while (fgets(line, sizeof(line), maps)) {
        const char* path = strstr(line, fileName);
        if (!path) continue;
        size_t fileNameLength = strlen(fileName);
        if (path[fileNameLength] != '\0'
                && path[fileNameLength] != ' '
                && path[fileNameLength] != '\n') {
            continue;
        }

        unsigned long long start = 0;
        unsigned long long fileOffset = 0;
        if (sscanf(line, "%llx-%*llx %*s %llx", &start, &fileOffset) != 2) {
            continue;
        }
        if (fileOffset == 0) {
            base = static_cast<uintptr_t>(start);
            break;
        }
    }
    fclose(maps);

    if (!base) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "could not find offset-zero mapping for %s (matched filename %s)",
                            libraryName, fileName);
    }
    return base;
}

extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "FqSigner JNI_OnLoad");
    return JNI_VERSION_1_6;
}

static bool ensureLoaded(const char* libPath) {
    if (g_sigFunc) return true;
    if (g_loadFailed) return false;

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "dlopen: %s", libPath);
    g_soHandle = dlopen(libPath, RTLD_NOW | RTLD_GLOBAL);
    if (!g_soHandle) {
        const char* error = dlerror();
        g_loadError = std::string("dlopen failed: ")
                + (error ? error : "unknown linker error");
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", g_loadError.c_str());
        return false;
    }

    uintptr_t base = findImageBase(libPath);
    if (!base) {
        g_loadError = "Library loaded but its offset-zero mapping was not found";
        dlclose(g_soHandle);
        g_soHandle = nullptr;
        return false;
    }

    jni_onload_t soJniOnLoad =
            reinterpret_cast<jni_onload_t>(dlsym(g_soHandle, "JNI_OnLoad"));
    if (!soJniOnLoad) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                            "JNI_OnLoad not exported, using offset 0x%x", JNI_ONLOAD_OFFSET);
        soJniOnLoad = reinterpret_cast<jni_onload_t>(base + JNI_ONLOAD_OFFSET);
    }

    if (!g_vm) {
        g_loadError = "JavaVM is not available";
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", g_loadError.c_str());
        g_loadFailed = true;
        return false;
    }

    jint jniVersion = soJniOnLoad(g_vm, nullptr);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "libmetasec_ml.so JNI_OnLoad returned 0x%x", jniVersion);
    if (jniVersion == JNI_ERR) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                            "JNI_OnLoad returned JNI_ERR; continuing as in the reference implementation");
    }

    g_sigFunc = reinterpret_cast<sig_func_t>(base + SIG_FUNC_OFFSET);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "sig func at %p (base=%p, offset=0x%x)",
                        reinterpret_cast<void*>(g_sigFunc),
                        reinterpret_cast<void*>(base), SIG_FUNC_OFFSET);
    return true;
}

static void throwIllegalState(JNIEnv* env, const char* message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    if (exceptionClass) env->ThrowNew(exceptionClass, message);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_fqdownloader_jni_FqSigner_nativeGenerateSignature(
        JNIEnv* env, jclass clazz, jstring jurl, jstring jheaders, jstring jLibPath) {
    std::lock_guard<std::mutex> lock(g_signerMutex);
    if (!jurl || !jheaders || !jLibPath) {
        throwIllegalState(env, "Signature arguments must not be null");
        return nullptr;
    }

    const char* url = env->GetStringUTFChars(jurl, nullptr);
    if (!url) return nullptr;
    const char* headers = env->GetStringUTFChars(jheaders, nullptr);
    if (!headers) {
        env->ReleaseStringUTFChars(jurl, url);
        return nullptr;
    }
    const char* libPath = env->GetStringUTFChars(jLibPath, nullptr);
    if (!libPath) {
        env->ReleaseStringUTFChars(jurl, url);
        env->ReleaseStringUTFChars(jheaders, headers);
        return nullptr;
    }

    if (!ensureLoaded(libPath)) {
        const char* message = g_loadError.empty()
                ? "Failed to initialize libmetasec_ml.so" : g_loadError.c_str();
        env->ReleaseStringUTFChars(jurl, url);
        env->ReleaseStringUTFChars(jheaders, headers);
        env->ReleaseStringUTFChars(jLibPath, libPath);
        throwIllegalState(env, message);
        return nullptr;
    }

    const char* result = g_sigFunc(url, headers);
    env->ReleaseStringUTFChars(jurl, url);
    env->ReleaseStringUTFChars(jheaders, headers);
    env->ReleaseStringUTFChars(jLibPath, libPath);

    if (!result) {
        throwIllegalState(env, "Signature function returned null");
        return nullptr;
    }
    return env->NewStringUTF(result);
}
