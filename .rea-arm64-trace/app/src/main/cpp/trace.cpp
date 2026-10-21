#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <dlfcn.h>
#include <link.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <unistd.h>
#include <ucontext.h>

namespace {

constexpr char kTag[] = "Arm64TraceProbe";
constexpr unsigned int kSyntheticRandSeed = 0x13579bdf;
constexpr uintptr_t kSignerOffset = 0x168c80;
constexpr uintptr_t kSingleCallbackState = 0x169828;
constexpr uintptr_t kPairCallbackState = 0x16a45c;
constexpr uintptr_t kSingleAdapter = 0x16da80;
constexpr uintptr_t kSingleHelper = 0x16db38;
constexpr uintptr_t kPairAdapter = 0x16dad8;
constexpr uintptr_t kPairHelper = 0x16ee20;
constexpr uintptr_t kPairHelperReturn = 0x16db04;
constexpr uintptr_t kPairAdapterReturn = 0x16db34;
constexpr uintptr_t kPairResultLoad = 0x169ba8;
constexpr uintptr_t kSignerResultLoad = 0x16a5ac;
constexpr uintptr_t kRandPltBranch = 0x3303c;
constexpr uintptr_t kSrandPltBranch = 0x3328c;
constexpr uintptr_t kGettimeofdayPltBranch = 0x32cbc;
constexpr uintptr_t kClockGettimePltBranch = 0x32ebc;
constexpr uintptr_t kLrand48PltBranch = 0x3371c;
constexpr uintptr_t kTimePltBranch = 0x338dc;
constexpr uintptr_t kSrand48PltBranch = 0x33a0c;
constexpr uintptr_t kSyscallPltBranch = 0x334fc;
constexpr uintptr_t kMallocPltBranch = 0x32fac;
constexpr uintptr_t kCallocPltBranch = 0x32f5c;
constexpr uintptr_t kPosixMemalignPltBranch = 0x3306c;
constexpr uintptr_t kReallocPltBranch = 0x331dc;
constexpr uintptr_t kMmapPltBranch = 0x3344c;
constexpr uintptr_t kFopenPltBranch = 0x32cac;
constexpr uintptr_t kFreadPltBranch = 0x32efc;
constexpr uintptr_t kReadPltBranch = 0x3307c;
constexpr uintptr_t kPthreadSelfPltBranch = 0x3313c;
constexpr uintptr_t kGettidPltBranch = 0x3314c;
constexpr uintptr_t kOpenat2PltBranch = 0x334cc;
constexpr uintptr_t kGetauxvalPltBranch = 0x3378c;
constexpr uintptr_t kOpenPltBranch = 0x33a1c;
constexpr uintptr_t kLrand48ReturnSite = 0x2215c0;
constexpr uintptr_t kGettimeofdayDeadlineReturn = 0x1c10ac;
constexpr uintptr_t kGettimeofdaySampleReturn = 0x1c1c54;
constexpr uintptr_t kClockRealtimeReturn = 0x22fa74;
constexpr uintptr_t kClockMonotonicReturn = 0x22fb14;
constexpr uint64_t kSyntheticSeconds = 1700000000;
constexpr uint64_t kSyntheticNanoseconds = 123456789;
constexpr uintptr_t kRandValueReturn = 0x1c2134;
constexpr size_t kTimeApiEventCapacity = 24;
constexpr size_t kTimeResultCapacity = 24;
constexpr uintptr_t kRandObjectStoreSites[] = {
        0x17832c, 0x17857c, 0x178884, 0x178cd4,
};
constexpr size_t kRandObjectStoreSiteCount =
        sizeof(kRandObjectStoreSites) / sizeof(kRandObjectStoreSites[0]);
constexpr uintptr_t kStringBuilderHelper = 0x170324;
constexpr uintptr_t kSplitStringHelper = 0x6c4dc;
constexpr uintptr_t kPairContainerUpdate = 0x1aca7c;
constexpr uintptr_t kPairCompareResultBranch = 0x1acadc;
constexpr uintptr_t kParserEmptyGuard = 0x170394;
constexpr uintptr_t kParserOddCountGuard = 0x1703a0;
constexpr uintptr_t kParserStatusGuard = 0x1703d0;
constexpr uintptr_t kCoreVirtualCall = 0x16fc38;
constexpr uintptr_t kPairMethodCallSite = 0x16f408;
constexpr uintptr_t kPairMethodReturnOne = 0x16f40c;
constexpr uintptr_t kPairMethodReturnTwo = 0x16f770;
constexpr uintptr_t kX5RestoreSites[] = {
        0x16dbd0, 0x16ed90, 0x16eec0,
};
constexpr uintptr_t kTransformCallSite = 0x16c888;
constexpr uintptr_t kTransformReturnSite = 0x16c88c;
constexpr uintptr_t kSyscallReturnSite = 0x15af88;
constexpr size_t kTransformX5WordCount = 32;
constexpr size_t kTransformX28WordCount = 64;
constexpr size_t kTransformX5IndirectWordCount = 32;
constexpr size_t kCapturedLrand48Count = 8;
constexpr size_t kCapturedSyscallCount = 8;
constexpr size_t kCapturedSrandCount = 8;
constexpr size_t kCallStateEventCapacity = 128;
constexpr size_t kCallStateEventWordCount = 40;
constexpr size_t kFileApiEventCapacity = 32;
constexpr size_t kFileApiEventWordCount = 26;
constexpr size_t kAllocationEventCapacity = 2048;
constexpr size_t kAllocationEventWordCount = 6;
constexpr size_t kTreeNodeEventCapacity = 64;
constexpr size_t kTreeNodeEventWordCount = 56;
constexpr size_t kMetricsLength = 925
        + kCallStateEventCapacity * kCallStateEventWordCount
        + 1 + kFileApiEventCapacity * kFileApiEventWordCount
        + 1 + kAllocationEventCapacity * kAllocationEventWordCount
        + 1 + kTreeNodeEventCapacity * kTreeNodeEventWordCount;
constexpr uint32_t kBrkInstruction = 0xd4200000;

struct CoreCallSite {
    uintptr_t offset;
    uint8_t targetRegister;
};

constexpr CoreCallSite kCoreNestedCalls[] = {
        {0x16b91c, 8},
        {0x16b998, 8},
        {0x16ba2c, 8},
        {0x16bc2c, 8},
        {0x16bc9c, 8},
        {0x16c188, 8},
        {0x16c888, 9},
        {0x16cb40, 8},
        {0x16d034, 8},
        {0x16d248, 9},
        {0x16d554, 8},
};
constexpr size_t kCoreNestedCallCount =
        sizeof(kCoreNestedCalls) / sizeof(kCoreNestedCalls[0]);

constexpr uintptr_t kNestedMethodCallSites[] = {
        0x15b31c, 0x15beb8, 0x15c328, 0x15c434, 0x15c480, 0x15c81c,
        0x15c898, 0x15cd38, 0x15cdb0, 0x15cf64, 0x15cfa4, 0x15d02c,
        0x15d074, 0x15d238, 0x15d6fc, 0x15db2c, 0x15de9c,
        0x15dfe4, 0x15e4dc, 0x15e524, 0x15eb14, 0x15eb60, 0x15ecc4,
        0x15f010, 0x15f18c, 0x15f7d4, 0x15fc78, 0x15fec0,
};
constexpr size_t kNestedMethodCallCount =
        sizeof(kNestedMethodCallSites) / sizeof(kNestedMethodCallSites[0]);
constexpr size_t kX5RestoreSiteCount =
        sizeof(kX5RestoreSites) / sizeof(kX5RestoreSites[0]);
constexpr uintptr_t kRandCallReturnSites[] = {
        0x8d840, 0x13f8d4, 0x1c2134, 0x1edb64,
};
constexpr size_t kRandCallReturnSiteCount =
        sizeof(kRandCallReturnSites) / sizeof(kRandCallReturnSites[0]);
constexpr size_t kRandValueCapacity = 16;
constexpr size_t kRandObjectVtableCapacity = 8;
constexpr size_t kRandObjectEventCapacity = 32;

using Signer = const char* (*)(const char*, const char*);
using TargetJniOnLoad = jint (*)(JavaVM*, void*);

void* g_library = nullptr;
Signer g_signer = nullptr;
TargetJniOnLoad g_targetOnLoad = nullptr;
JavaVM* g_vm = nullptr;
bool g_initialized = false;
uintptr_t g_imageBase = 0;
volatile sig_atomic_t g_hits[23]{};
volatile sig_atomic_t g_randPltCalls = 0;
volatile sig_atomic_t g_timeApiEventCount = 0;
volatile uint32_t g_timeApiEvents[kTimeApiEventCapacity * 4]{};
volatile sig_atomic_t g_timeResultCount = 0;
volatile uint32_t g_timeResults[kTimeResultCapacity * 6]{};
volatile sig_atomic_t g_randCallSiteHits[kRandCallReturnSiteCount]{};
volatile sig_atomic_t g_randValueCount = 0;
volatile uint32_t g_randValues[kRandValueCapacity]{};
volatile uint32_t g_randCallerOffsets[kRandValueCapacity]{};
volatile uint32_t g_randObjectCallbacks[kRandObjectVtableCapacity * 2]{};
volatile sig_atomic_t g_randObjectEventCount = 0;
volatile uint32_t g_randObjectEvents[kRandObjectEventCapacity * 4]{};
volatile uint32_t g_pairArguments[4]{};
volatile uint32_t g_pairReturn[2]{};
volatile uint32_t g_adapterReturn[4]{};
volatile uint32_t g_resultSlot[2]{};
volatile uint32_t g_signerResult[2]{};
volatile uint32_t g_stringBuilderArguments[6]{};
volatile uint32_t g_splitArguments[6]{};
volatile int32_t g_splitLengths[2]{-1, -1};
volatile uint32_t g_coreVirtualCall[4]{};
volatile sig_atomic_t g_coreNestedHits[kCoreNestedCallCount]{};
volatile uint32_t g_coreNestedTargets[kCoreNestedCallCount * 2]{};
volatile sig_atomic_t g_nestedMethodHits[kNestedMethodCallCount]{};
volatile uint32_t g_nestedMethodTargets[kNestedMethodCallCount * 2]{};
volatile uint32_t g_transformObservation[126]{};
volatile sig_atomic_t g_transformCallCount = 0;
volatile uint32_t g_transformCallRegisters[16]{};
volatile uint32_t g_transformCallX5[kTransformX5WordCount]{};
volatile uint32_t g_transformCallX28[kTransformX28WordCount]{};
volatile uint32_t g_transformCallX5Indirect[kTransformX5IndirectWordCount]{};
volatile sig_atomic_t g_transformX5ReadBytes = 0;
volatile sig_atomic_t g_transformX28ReadBytes = 0;
volatile sig_atomic_t g_transformX5ReadError = 0;
volatile sig_atomic_t g_transformX28ReadError = 0;
volatile sig_atomic_t g_transformX5IndirectReadBytes = 0;
volatile sig_atomic_t g_transformX5IndirectReadError = 0;
volatile sig_atomic_t g_signerThreadId = 0;
volatile uint32_t g_srand48Count = 0;
volatile uint32_t g_srand48Seeds[kCapturedLrand48Count]{};
volatile uint32_t g_srand48Callers[kCapturedLrand48Count]{};
volatile uint32_t g_srand48ThreadIds[kCapturedLrand48Count]{};
volatile uint32_t g_lrand48CallCount = 0;
volatile uint32_t g_lrand48ReturnCount = 0;
volatile uint32_t g_lrand48Values[kCapturedLrand48Count]{};
volatile uint32_t g_lrand48Callers[kCapturedLrand48Count]{};
volatile uint32_t g_lrand48ThreadIds[kCapturedLrand48Count]{};
volatile uint32_t g_syscallCount = 0;
volatile uint32_t g_syscallNumbers[kCapturedSyscallCount]{};
volatile uint32_t g_syscallCallers[kCapturedSyscallCount]{};
volatile uint32_t g_syscallThreadIds[kCapturedSyscallCount]{};
volatile uint32_t g_srandCount = 0;
volatile uint32_t g_srandSeeds[kCapturedSrandCount]{};
volatile uint32_t g_srandCallers[kCapturedSrandCount]{};
volatile uint32_t g_srandThreadIds[kCapturedSrandCount]{};
volatile sig_atomic_t g_traceCallState = 0;
volatile sig_atomic_t g_callStateEventCount = 0;
volatile uint32_t g_callStateEvents[
        kCallStateEventCapacity * kCallStateEventWordCount]{};
volatile uint32_t g_fileApiEventCount = 0;
volatile uint32_t g_fileApiEvents[
        kFileApiEventCapacity * kFileApiEventWordCount]{};
volatile uint32_t g_allocationEventCount = 0;
volatile uint32_t g_allocationEvents[
        kAllocationEventCapacity * kAllocationEventWordCount]{};
volatile sig_atomic_t g_treeNodeEventCount = 0;
volatile uint32_t g_treeNodeEvents[
        kTreeNodeEventCapacity * kTreeNodeEventWordCount]{};
volatile uint32_t g_transformThreadId = 0;
uintptr_t g_transformOutput = 0;
uintptr_t g_mallocReturnTrap = 0;
uintptr_t g_mallocReturnAddress = 0;
uintptr_t g_mallocCaller = 0;
size_t g_mallocRequestedSize = 0;
uintptr_t g_allocationOutputAddress = 0;
uint32_t g_allocationKind = 0;

struct ImageSearch {
    uintptr_t base;
};

int coreNestedCallIndex(uintptr_t offset) {
    for (size_t index = 0; index < kCoreNestedCallCount; ++index) {
        if (offset == kCoreNestedCalls[index].offset) {
            return static_cast<int>(index);
        }
    }
    return -1;
}

int nestedMethodCallIndex(uintptr_t offset) {
    for (size_t index = 0; index < kNestedMethodCallCount; ++index) {
        if (offset == kNestedMethodCallSites[index]) {
            return static_cast<int>(index);
        }
    }
    return -1;
}

int randObjectStoreSiteIndex(uintptr_t offset) {
    for (size_t index = 0; index < kRandObjectStoreSiteCount; ++index) {
        if (offset == kRandObjectStoreSites[index]) {
            return static_cast<int>(index);
        }
    }
    return -1;
}

int x5RestoreSiteIndex(uintptr_t offset) {
    for (size_t index = 0; index < kX5RestoreSiteCount; ++index) {
        if (offset == kX5RestoreSites[index]) return static_cast<int>(index);
    }
    return -1;
}

int fileApiIndex(uintptr_t offset) {
    if (offset == kFopenPltBranch) return 0;
    if (offset == kFreadPltBranch) return 1;
    if (offset == kReadPltBranch) return 2;
    if (offset == kOpenPltBranch) return 3;
    if (offset == kOpenat2PltBranch) return 4;
    if (offset == kGettidPltBranch) return 5;
    if (offset == kPthreadSelfPltBranch) return 6;
    if (offset == kGetauxvalPltBranch) return 7;
    return -1;
}

bool isTrapOffset(uintptr_t offset) {
    return offset == kSingleCallbackState || offset == kPairCallbackState
            || offset == kSingleAdapter || offset == kSingleHelper
            || offset == kPairAdapter || offset == kPairHelper
            || offset == kPairHelperReturn || offset == kPairAdapterReturn
            || offset == kPairResultLoad || offset == kSignerResultLoad
            || offset == kRandPltBranch
            || offset == kMallocPltBranch
            || offset == kCallocPltBranch
            || offset == kPosixMemalignPltBranch
            || offset == kReallocPltBranch
            || offset == kMmapPltBranch
            || offset == kSrandPltBranch
            || offset == kGettimeofdayPltBranch
            || offset == kClockGettimePltBranch
            || offset == kLrand48PltBranch
            || offset == kSrand48PltBranch
            || offset == kSyscallPltBranch
            || fileApiIndex(offset) >= 0
            || offset == kLrand48ReturnSite
            || offset == kTimePltBranch
            || offset == kGettimeofdayDeadlineReturn
            || offset == kGettimeofdaySampleReturn
            || offset == kClockMonotonicReturn
            || offset == kClockRealtimeReturn
            || offset == kRandValueReturn
            || randObjectStoreSiteIndex(offset) >= 0
            || offset == kStringBuilderHelper || offset == kSplitStringHelper
            || offset == kPairContainerUpdate
            || offset == kPairCompareResultBranch
            || offset == kParserEmptyGuard || offset == kParserOddCountGuard
            || offset == kParserStatusGuard || offset == kCoreVirtualCall
            || offset == kPairMethodCallSite
            || offset == kPairMethodReturnOne || offset == kPairMethodReturnTwo
            || offset == kTransformReturnSite
            || offset == kSyscallReturnSite
            || x5RestoreSiteIndex(offset) >= 0
            || coreNestedCallIndex(offset) >= 0
            || nestedMethodCallIndex(offset) >= 0;
}

void storeObservedValue(volatile uint32_t* destination, uint64_t value) {
    destination[0] = static_cast<uint32_t>(value);
    destination[1] = static_cast<uint32_t>(value >> 32);
}

void copyObservedWords(volatile uint32_t* destination, uintptr_t source,
                       size_t wordCount) {
    // Small inline/sentinel values are not readable buffer addresses.
    if (source < 0x10000) return;
    const auto* input = reinterpret_cast<const volatile uint32_t*>(source);
    for (size_t index = 0; index < wordCount; ++index) {
        destination[index] = input[index];
    }
}

ssize_t copySafeObservedWords(volatile uint32_t* destination, uintptr_t source,
                              size_t wordCount) {
    if (source < 0x10000 || wordCount > kTransformX28WordCount) return 0;

    uint32_t snapshot[kTransformX28WordCount]{};
    iovec local{snapshot, wordCount * sizeof(uint32_t)};
    iovec remote{reinterpret_cast<void*>(source), wordCount * sizeof(uint32_t)};
    const ssize_t copied = process_vm_readv(getpid(), &local, 1, &remote, 1, 0);
    if (copied <= 0) return copied;

    const size_t copiedWords = static_cast<size_t>(copied) / sizeof(uint32_t);
    for (size_t index = 0; index < copiedWords; ++index) {
        destination[index] = snapshot[index];
    }
    return copied;
}

void captureCallState(uintptr_t offset, ucontext_t* state) {
    const sig_atomic_t eventIndex = g_callStateEventCount++;
    if (eventIndex < 0
            || static_cast<size_t>(eventIndex) >= kCallStateEventCapacity) {
        return;
    }

    volatile uint32_t* event = g_callStateEvents
            + static_cast<size_t>(eventIndex) * kCallStateEventWordCount;
    event[0] = static_cast<uint32_t>(offset);
    storeObservedValue(event + 1, state->uc_mcontext.regs[5]);
    storeObservedValue(event + 3, state->uc_mcontext.regs[28]);

    uint32_t x5Header[6]{};
    errno = 0;
    const ssize_t headerBytes = copySafeObservedWords(
            reinterpret_cast<volatile uint32_t*>(x5Header),
            state->uc_mcontext.regs[5], 6);
    event[5] = static_cast<uint32_t>(headerBytes);
    event[6] = errno;
    for (size_t index = 0; index < 6; ++index) {
        event[7 + index] = x5Header[index];
    }

    const uintptr_t payload = (static_cast<uintptr_t>(x5Header[5]) << 32)
            | x5Header[4];
    storeObservedValue(event + 13, payload);
    uint32_t payloadWords[16]{};
    errno = 0;
    const ssize_t payloadBytes = copySafeObservedWords(
            reinterpret_cast<volatile uint32_t*>(payloadWords), payload, 16);
    event[15] = static_cast<uint32_t>(payloadBytes);
    event[16] = errno;
    for (size_t index = 0; index < 16; ++index) {
        event[17 + index] = payloadWords[index];
    }
    const uintptr_t returnAddress = state->uc_mcontext.regs[30];
    storeObservedValue(event + 33, returnAddress >= g_imageBase
            ? returnAddress - g_imageBase : returnAddress);
    const uintptr_t framePointer = state->uc_mcontext.regs[29];
    storeObservedValue(event + 35, framePointer);
    if (framePointer >= 0x10000) {
        errno = 0;
        const ssize_t frameBytes = copySafeObservedWords(
                event + 37, framePointer + sizeof(uintptr_t), 2);
        event[39] = static_cast<uint32_t>(frameBytes);
    }
}

void captureFileApiEvent(uintptr_t offset, ucontext_t* state) {
    const uint32_t eventIndex = __atomic_fetch_add(
            &g_fileApiEventCount, 1U, __ATOMIC_RELAXED);
    if (eventIndex >= kFileApiEventCapacity) return;

    volatile uint32_t* event =
            g_fileApiEvents + eventIndex * kFileApiEventWordCount;
    event[0] = static_cast<uint32_t>(fileApiIndex(offset));
    const uintptr_t returnAddress = state->uc_mcontext.regs[30];
    event[1] = static_cast<uint32_t>(returnAddress >= g_imageBase
            ? returnAddress - g_imageBase : returnAddress);
    storeObservedValue(event + 2, state->uc_mcontext.regs[0]);
    storeObservedValue(event + 4, state->uc_mcontext.regs[1]);
    storeObservedValue(event + 6, state->uc_mcontext.regs[2]);
    event[8] = static_cast<uint32_t>(syscall(SYS_gettid));

    uintptr_t path = 0;
    if (offset == kFopenPltBranch || offset == kOpenPltBranch) {
        path = state->uc_mcontext.regs[0];
    } else if (offset == kOpenat2PltBranch) {
        path = state->uc_mcontext.regs[1];
    }
    if (path) {
        errno = 0;
        const ssize_t bytes = copySafeObservedWords(
                event + 10, path, 16);
        event[9] = static_cast<uint32_t>(bytes);
    }
}

int allocationPltKind(uintptr_t offset) {
    if (offset == kMallocPltBranch) return 0;
    if (offset == kCallocPltBranch) return 1;
    if (offset == kReallocPltBranch) return 2;
    if (offset == kMmapPltBranch) return 3;
    if (offset == kPosixMemalignPltBranch) return 4;
    return -1;
}

uint64_t loadObservedValue(const volatile uint32_t* source) {
    return (static_cast<uint64_t>(source[1]) << 32) | source[0];
}

int findImage(struct dl_phdr_info* info, size_t, void* opaque) {
    if (info->dlpi_name && strstr(info->dlpi_name, "libmetasec_ml.so")) {
        static_cast<ImageSearch*>(opaque)->base =
                static_cast<uintptr_t>(info->dlpi_addr);
        return 1;
    }
    return 0;
}

void throwJava(JNIEnv* env, const char* message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception) env->ThrowNew(exception, message);
}

bool isSignerThread() {
    return g_signerThreadId != 0
            && static_cast<sig_atomic_t>(syscall(SYS_gettid)) == g_signerThreadId;
}

void handleTrap(int signal, siginfo_t* info, void* context) {
    auto* state = static_cast<ucontext_t*>(context);
    const uintptr_t pc = static_cast<uintptr_t>(state->uc_mcontext.pc);
    if (g_mallocReturnTrap
            && (pc == g_mallocReturnTrap || pc == g_mallocReturnTrap + 4)) {
        if (g_traceCallState && isSignerThread()) {
            const uint32_t eventIndex = __atomic_fetch_add(
                    &g_allocationEventCount, 1U, __ATOMIC_RELAXED);
            if (eventIndex < kAllocationEventCapacity) {
                volatile uint32_t* event = g_allocationEvents
                        + eventIndex * kAllocationEventWordCount;
                uintptr_t pointer = state->uc_mcontext.regs[0];
                if (g_allocationKind == 4 && g_allocationOutputAddress) {
                    uint32_t outputPointer[2]{};
                    const ssize_t copied = copySafeObservedWords(
                            reinterpret_cast<volatile uint32_t*>(outputPointer),
                            g_allocationOutputAddress, 2);
                    if (copied >= static_cast<ssize_t>(sizeof(uintptr_t))) {
                        pointer = (static_cast<uintptr_t>(outputPointer[1]) << 32)
                                | outputPointer[0];
                    } else {
                        pointer = 0;
                    }
                }
                event[0] = g_allocationKind;
                event[1] = static_cast<uint32_t>(g_mallocRequestedSize);
                storeObservedValue(event + 2, pointer);
                event[4] = static_cast<uint32_t>(g_mallocCaller >= g_imageBase
                        ? g_mallocCaller - g_imageBase : g_mallocCaller);
                event[5] = static_cast<uint32_t>(syscall(SYS_gettid));
            }
        }
        g_allocationKind = 0;
        g_allocationOutputAddress = 0;
        state->uc_mcontext.regs[30] = g_mallocReturnAddress;
        state->uc_mcontext.pc = g_mallocReturnAddress;
        return;
    }

    uintptr_t offset = pc - g_imageBase;
    if (!isTrapOffset(offset) && pc >= 4) {
        offset = pc - 4 - g_imageBase;
    }
    if (!isTrapOffset(offset)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                            "Unhandled signal %d at pc=%p base=%p offset=0x%lx code=%d",
                            signal, reinterpret_cast<void*>(pc),
                            reinterpret_cast<void*>(g_imageBase),
                            static_cast<unsigned long>(offset),
                            info ? info->si_code : 0);
        _exit(128 + signal + (info ? info->si_code : 0));
    }

    if (offset == kSingleCallbackState) {
        state->uc_mcontext.regs[1] =
                *reinterpret_cast<uint64_t*>(state->uc_mcontext.sp + 0x158);
        ++g_hits[0];
    } else if (offset == kSingleAdapter || offset == kPairAdapter) {
        if (offset == kPairAdapter && isSignerThread() && g_traceCallState) {
            captureCallState(offset, state);
        }
        const uintptr_t newSp = state->uc_mcontext.sp - 0x30;
        *reinterpret_cast<uint64_t*>(newSp) = state->uc_mcontext.regs[21];
        state->uc_mcontext.sp = newSp;
        ++g_hits[offset == kSingleAdapter ? 1 : 4];
    } else if (offset == kSingleHelper || offset == kPairHelper) {
        if (offset == kPairHelper) {
            storeObservedValue(g_pairArguments, state->uc_mcontext.regs[0]);
            storeObservedValue(g_pairArguments + 2, state->uc_mcontext.regs[1]);
            if (isSignerThread() && g_traceCallState) {
                captureCallState(offset, state);
            }
        }
        const uintptr_t newSp = state->uc_mcontext.sp - 0x60;
        auto* saved = reinterpret_cast<uint64_t*>(newSp);
        saved[0] = state->uc_mcontext.regs[28];
        saved[1] = state->uc_mcontext.regs[27];
        state->uc_mcontext.sp = newSp;
        ++g_hits[offset == kSingleHelper ? 2 : 5];
    } else if (offset == kPairHelperReturn) {
        storeObservedValue(g_pairReturn, state->uc_mcontext.regs[0]);
        state->uc_mcontext.regs[21] = state->uc_mcontext.regs[0];
        ++g_hits[6];
    } else if (offset == kPairAdapterReturn) {
        storeObservedValue(g_adapterReturn, state->uc_mcontext.regs[0]);
        storeObservedValue(g_adapterReturn + 2, state->uc_mcontext.regs[30]);
        ++g_hits[7];
        state->uc_mcontext.pc = state->uc_mcontext.regs[30];
        return;
    } else if (offset == kPairResultLoad) {
        const uint64_t value =
                *reinterpret_cast<uint64_t*>(state->uc_mcontext.sp + 0x10);
        state->uc_mcontext.regs[8] = value;
        storeObservedValue(g_resultSlot, value);
        ++g_hits[8];
    } else if (offset == kSignerResultLoad) {
        const uint64_t value = *reinterpret_cast<uint64_t*>(
                state->uc_mcontext.regs[29] - 0x68);
        state->uc_mcontext.regs[0] = value;
        storeObservedValue(g_signerResult, value);
        ++g_hits[9];
    } else if (offset == kGettimeofdayDeadlineReturn
            || offset == kGettimeofdaySampleReturn
            || offset == kClockMonotonicReturn
            || offset == kClockRealtimeReturn) {
        if (!isSignerThread()) {
            state->uc_mcontext.pc = g_imageBase + offset + 4;
            return;
        }
        uint64_t seconds = 0;
        uint64_t fraction = 0;
        if (offset == kGettimeofdayDeadlineReturn) {
            seconds = kSyntheticSeconds;
            fraction = 123456;
            state->uc_mcontext.regs[9] = seconds;
            state->uc_mcontext.regs[10] = fraction;
        } else if (offset == kGettimeofdaySampleReturn) {
            seconds = kSyntheticSeconds;
            fraction = 123456;
            *reinterpret_cast<uint64_t*>(
                    state->uc_mcontext.sp) = seconds;
            *reinterpret_cast<uint64_t*>(
                    state->uc_mcontext.sp + 8) = fraction;
            state->uc_mcontext.regs[10] = seconds;
            state->uc_mcontext.regs[9] = fraction;
        } else {
            seconds = kSyntheticSeconds;
            fraction = *reinterpret_cast<const uint64_t*>(
                    state->uc_mcontext.sp + 0x10);
            const bool failed =
                    static_cast<uint32_t>(state->uc_mcontext.regs[0]) != 0;
            if (failed) {
                state->uc_mcontext.pc = g_imageBase
                        + (offset == kClockRealtimeReturn ? 0x22fac4 : 0x22fb48);
            } else if (offset == kClockRealtimeReturn
                    || offset == kClockMonotonicReturn) {
                fraction = kSyntheticNanoseconds;
                *reinterpret_cast<uint64_t*>(
                        state->uc_mcontext.sp + 8) = seconds;
                *reinterpret_cast<uint64_t*>(
                        state->uc_mcontext.sp + 0x10) = fraction;
            }
        }
        const sig_atomic_t eventIndex = g_timeResultCount++;
        if (eventIndex >= 0
                && static_cast<size_t>(eventIndex) < kTimeResultCapacity) {
            volatile uint32_t* event =
                    g_timeResults + static_cast<size_t>(eventIndex) * 6;
            event[0] = static_cast<uint32_t>(offset);
            event[1] = static_cast<uint32_t>(seconds);
            event[2] = static_cast<uint32_t>(seconds >> 32);
            event[3] = static_cast<uint32_t>(fraction);
            event[4] = static_cast<uint32_t>(fraction >> 32);
            event[5] = static_cast<uint32_t>(state->uc_mcontext.regs[0]);
        }
    } else if (offset == kRandValueReturn) {
        if (isSignerThread()) {
            const sig_atomic_t index = g_randValueCount++;
            if (index >= 0 && static_cast<size_t>(index) < kRandValueCapacity) {
                g_randValues[index] =
                        static_cast<uint32_t>(state->uc_mcontext.regs[0]);
                const uintptr_t returnAddress = *reinterpret_cast<const uintptr_t*>(
                        state->uc_mcontext.sp + 0x28);
                g_randCallerOffsets[index] = static_cast<uint32_t>(
                        returnAddress >= g_imageBase
                                ? returnAddress - g_imageBase
                                : returnAddress);
                if (index < static_cast<sig_atomic_t>(kRandObjectVtableCapacity)) {
                    const uintptr_t returnOffset = returnAddress >= g_imageBase
                            ? returnAddress - g_imageBase
                            : returnAddress;
                    if (returnOffset >= 0x17831c && returnOffset <= 0x178cd4) {
                        const uintptr_t object = *reinterpret_cast<const uintptr_t*>(
                                state->uc_mcontext.sp + 0x10);
                        if (object) {
                            const uintptr_t callback =
                                    *reinterpret_cast<const uintptr_t*>(object);
                            storeObservedValue(
                                    g_randObjectCallbacks + index * 2,
                                    callback >= g_imageBase
                                            ? callback - g_imageBase
                                            : callback);
                        }
                    }
                }
            }
        }
        state->uc_mcontext.regs[8] =
                *reinterpret_cast<const uint64_t*>(
                        state->uc_mcontext.regs[19] + 0x28);
    } else if (randObjectStoreSiteIndex(offset) >= 0) {
        const size_t index = static_cast<size_t>(randObjectStoreSiteIndex(offset));
        const uintptr_t object = state->uc_mcontext.regs[19];
        const uintptr_t callback = object
                ? *reinterpret_cast<const uintptr_t*>(object)
                : 0;
        const uintptr_t caller =
                *reinterpret_cast<const uintptr_t*>(state->uc_mcontext.sp + 8);
        const uintptr_t callbackOffset = callback >= g_imageBase
                ? callback - g_imageBase
                : callback;
        const uintptr_t callerOffset = caller >= g_imageBase
                ? caller - g_imageBase
                : caller;
        if (isSignerThread()) {
            const sig_atomic_t eventIndex = g_randObjectEventCount++;
            if (eventIndex >= 0
                    && static_cast<size_t>(eventIndex) < kRandObjectEventCapacity) {
                volatile uint32_t* event =
                        g_randObjectEvents + static_cast<size_t>(eventIndex) * 4;
                event[0] = static_cast<uint32_t>(kRandObjectStoreSites[index]);
                event[1] = static_cast<uint32_t>(callbackOffset);
                event[2] = static_cast<uint32_t>(callerOffset);
                event[3] = static_cast<uint32_t>(state->uc_mcontext.regs[0]);
            }
        }
        *reinterpret_cast<uint32_t*>(object + 8) =
                static_cast<uint32_t>(state->uc_mcontext.regs[0]);
    } else if (offset == kRandPltBranch) {
        if (isSignerThread()) ++g_randPltCalls;
        const uintptr_t returnAddress = state->uc_mcontext.regs[30];
        const uintptr_t returnOffset = returnAddress >= g_imageBase
                ? returnAddress - g_imageBase
                : returnAddress;
        for (size_t index = 0; index < kRandCallReturnSiteCount; ++index) {
            if (returnOffset == kRandCallReturnSites[index]) {
                if (isSignerThread()) ++g_randCallSiteHits[index];
                break;
            }
        }
        state->uc_mcontext.pc = state->uc_mcontext.regs[17];
        return;
    } else if (offset == kSrand48PltBranch) {
        const uint32_t eventIndex = __atomic_fetch_add(
                &g_srand48Count, 1U, __ATOMIC_RELAXED);
        if (eventIndex < kCapturedLrand48Count) {
            g_srand48Seeds[eventIndex] =
                    static_cast<uint32_t>(state->uc_mcontext.regs[0]);
            const uintptr_t returnAddress = state->uc_mcontext.regs[30];
            g_srand48Callers[eventIndex] =
                    static_cast<uint32_t>(returnAddress >= g_imageBase
                    ? returnAddress - g_imageBase
                    : returnAddress);
            g_srand48ThreadIds[eventIndex] =
                    static_cast<uint32_t>(syscall(SYS_gettid));
        }
        state->uc_mcontext.pc = state->uc_mcontext.regs[17];
        return;
    } else if (offset == kLrand48ReturnSite) {
        const uint32_t eventIndex = __atomic_fetch_add(
                &g_lrand48ReturnCount, 1U, __ATOMIC_RELAXED);
        if (eventIndex < kCapturedLrand48Count) {
            g_lrand48Values[eventIndex] =
                    static_cast<uint32_t>(state->uc_mcontext.regs[0]);
            g_lrand48Callers[eventIndex] = kLrand48ReturnSite;
            g_lrand48ThreadIds[eventIndex] =
                    static_cast<uint32_t>(syscall(SYS_gettid));
        }
    } else if (allocationPltKind(offset) >= 0) {
        if (g_traceCallState && isSignerThread() && g_mallocReturnTrap) {
            g_allocationKind = static_cast<uint32_t>(allocationPltKind(offset));
            if (offset == kMallocPltBranch) {
                g_mallocRequestedSize =
                        static_cast<size_t>(state->uc_mcontext.regs[0]);
            } else if (offset == kCallocPltBranch) {
                g_mallocRequestedSize = static_cast<size_t>(
                        state->uc_mcontext.regs[0] * state->uc_mcontext.regs[1]);
            } else if (offset == kReallocPltBranch) {
                g_mallocRequestedSize =
                        static_cast<size_t>(state->uc_mcontext.regs[1]);
            } else if (offset == kMmapPltBranch) {
                g_mallocRequestedSize =
                        static_cast<size_t>(state->uc_mcontext.regs[1]);
            } else {
                g_mallocRequestedSize =
                        static_cast<size_t>(state->uc_mcontext.regs[1]);
                g_allocationOutputAddress = state->uc_mcontext.regs[2];
            }
            g_mallocCaller = state->uc_mcontext.regs[30];
            g_mallocReturnAddress = state->uc_mcontext.regs[30];
            state->uc_mcontext.regs[30] = g_mallocReturnTrap;
        }
        state->uc_mcontext.pc = state->uc_mcontext.regs[17];
        return;
    } else if (fileApiIndex(offset) >= 0) {
        if (isSignerThread()) captureFileApiEvent(offset, state);
        state->uc_mcontext.pc = state->uc_mcontext.regs[17];
        return;
    } else if (offset == kSyscallPltBranch) {
        const uint32_t eventIndex = __atomic_fetch_add(
                &g_syscallCount, 1U, __ATOMIC_RELAXED);
        if (eventIndex < kCapturedSyscallCount) {
            g_syscallNumbers[eventIndex] =
                    static_cast<uint32_t>(state->uc_mcontext.regs[0]);
            const uintptr_t returnAddress = state->uc_mcontext.regs[30];
            g_syscallCallers[eventIndex] = static_cast<uint32_t>(
                    returnAddress >= g_imageBase
                            ? returnAddress - g_imageBase
                            : returnAddress);
            g_syscallThreadIds[eventIndex] =
                    static_cast<uint32_t>(syscall(SYS_gettid));
        }
        state->uc_mcontext.pc = state->uc_mcontext.regs[17];
        return;
    } else if (offset == kSrandPltBranch) {
        const uint32_t eventIndex = __atomic_fetch_add(
                &g_srandCount, 1U, __ATOMIC_RELAXED);
        if (eventIndex < kCapturedSrandCount) {
            g_srandSeeds[eventIndex] =
                    static_cast<uint32_t>(state->uc_mcontext.regs[0]);
            const uintptr_t returnAddress = state->uc_mcontext.regs[30];
            g_srandCallers[eventIndex] = static_cast<uint32_t>(
                    returnAddress >= g_imageBase
                            ? returnAddress - g_imageBase
                            : returnAddress);
            g_srandThreadIds[eventIndex] =
                    static_cast<uint32_t>(syscall(SYS_gettid));
        }
        state->uc_mcontext.pc = state->uc_mcontext.regs[17];
        return;
    } else if (offset == kGettimeofdayPltBranch
            || offset == kClockGettimePltBranch
            || offset == kLrand48PltBranch
            || offset == kTimePltBranch) {
        if (offset == kLrand48PltBranch) {
            __atomic_fetch_add(&g_lrand48CallCount, 1U, __ATOMIC_RELAXED);
        }
        const uintptr_t returnAddress = state->uc_mcontext.regs[30];
        const uint32_t returnOffset = static_cast<uint32_t>(
                returnAddress >= g_imageBase
                        ? returnAddress - g_imageBase
                        : returnAddress);
        if (isSignerThread()) {
            const sig_atomic_t eventIndex = g_timeApiEventCount++;
            if (eventIndex >= 0
                    && static_cast<size_t>(eventIndex) < kTimeApiEventCapacity) {
                volatile uint32_t* event =
                        g_timeApiEvents + static_cast<size_t>(eventIndex) * 4;
                event[0] = offset == kGettimeofdayPltBranch ? 0
                        : offset == kClockGettimePltBranch ? 1
                        : offset == kTimePltBranch ? 2 : 3;
                event[1] = returnOffset;
                uintptr_t argumentOrParent = state->uc_mcontext.regs[0];
                if (offset == kClockGettimePltBranch
                        && (returnOffset == 0x22fa74 || returnOffset == 0x22fb14)) {
                    argumentOrParent = *reinterpret_cast<const uintptr_t*>(
                            state->uc_mcontext.sp + 0x48);
                    if (argumentOrParent >= g_imageBase) {
                        argumentOrParent -= g_imageBase;
                    }
                }
                event[2] = static_cast<uint32_t>(argumentOrParent);
                event[3] = static_cast<uint32_t>(state->uc_mcontext.regs[1]);
            }
        }
        state->uc_mcontext.pc = state->uc_mcontext.regs[17];
        return;
    } else if (offset == kStringBuilderHelper) {
        storeObservedValue(g_stringBuilderArguments,
                           state->uc_mcontext.regs[0]);
        storeObservedValue(g_stringBuilderArguments + 2,
                           state->uc_mcontext.regs[1]);
        storeObservedValue(g_stringBuilderArguments + 4,
                           state->uc_mcontext.regs[8]);
        state->uc_mcontext.sp -= 0x90;
        ++g_hits[10];
    } else if (offset == kSplitStringHelper) {
        const uint64_t input = state->uc_mcontext.regs[0];
        const uint64_t delimiter = state->uc_mcontext.regs[1];
        storeObservedValue(g_splitArguments, input);
        storeObservedValue(g_splitArguments + 2, delimiter);
        storeObservedValue(g_splitArguments + 4, state->uc_mcontext.regs[8]);
        if (input) {
            g_splitLengths[0] =
                    *reinterpret_cast<const int32_t*>(input + 0x0c);
        }
        if (delimiter) {
            g_splitLengths[1] =
                    *reinterpret_cast<const int32_t*>(delimiter + 0x0c);
        }
        const uintptr_t newSp = state->uc_mcontext.sp - 0x40;
        auto* saved = reinterpret_cast<uint64_t*>(newSp);
        saved[0] = state->uc_mcontext.regs[24];
        saved[1] = state->uc_mcontext.regs[23];
        state->uc_mcontext.sp = newSp;
        ++g_hits[11];
    } else if (offset == kPairContainerUpdate) {
        state->uc_mcontext.sp -= 0x80;
        ++g_hits[12];
        if (state->uc_mcontext.regs[30] == g_imageBase + 0x170450) {
            ++g_hits[13];
        }
    } else if (offset == kPairCompareResultBranch) {
        const uintptr_t caller = *reinterpret_cast<const uintptr_t*>(
                state->uc_mcontext.sp + 0x78);
        if (caller == g_imageBase + 0x170450) {
            ++g_hits[state->uc_mcontext.regs[0] == 0 ? 14 : 15];
        }
        state->uc_mcontext.pc = g_imageBase
                + (state->uc_mcontext.regs[0] == 0 ? 0x1acb00 : 0x1acae0);
        return;
    } else if (offset == kParserEmptyGuard) {
        const bool taken = state->uc_mcontext.regs[0] == 0;
        ++g_hits[16];
        if (taken) ++g_hits[17];
        state->uc_mcontext.pc =
                g_imageBase + (taken ? 0x17045c : 0x170398);
        return;
    } else if (offset == kParserOddCountGuard) {
        const bool taken = (state->uc_mcontext.regs[0] & 1) != 0;
        ++g_hits[18];
        if (taken) ++g_hits[19];
        state->uc_mcontext.pc =
                g_imageBase + (taken ? 0x17045c : 0x1703a4);
        return;
    } else if (offset == kParserStatusGuard) {
        const bool taken = state->uc_mcontext.regs[0] != 0;
        ++g_hits[20];
        if (taken) ++g_hits[21];
        state->uc_mcontext.pc =
                g_imageBase + (taken ? 0x17045c : 0x1703d4);
        return;
    } else if (offset == kCoreVirtualCall) {
        const uint64_t target = state->uc_mcontext.regs[9];
        if (isSignerThread()) {
            const uint64_t receiver = state->uc_mcontext.regs[0];
            const uint64_t vtable = receiver
                    ? *reinterpret_cast<const uint64_t*>(receiver)
                    : 0;
            storeObservedValue(g_coreVirtualCall,
                               target >= g_imageBase ? target - g_imageBase : target);
            storeObservedValue(g_coreVirtualCall + 2,
                               vtable >= g_imageBase ? vtable - g_imageBase : vtable);
            ++g_hits[22];
        }
        state->uc_mcontext.regs[30] = g_imageBase + offset + 4;
        state->uc_mcontext.pc = target;
        return;
    } else if (x5RestoreSiteIndex(offset) >= 0) {
        const auto* saved = reinterpret_cast<const volatile uint64_t*>(
                state->uc_mcontext.sp + 0x20);
        state->uc_mcontext.regs[4] = saved[0];
        state->uc_mcontext.regs[5] = saved[1];
        if (isSignerThread() && g_traceCallState) {
            captureCallState(offset, state);
        }
        state->uc_mcontext.pc = g_imageBase + offset + 4;
        return;
    } else if (offset == kTransformReturnSite) {
        if (isSignerThread()) {
            storeObservedValue(g_transformObservation + 40,
                               state->uc_mcontext.regs[0]);
            if (g_transformOutput) {
                const auto* output =
                        reinterpret_cast<const volatile uintptr_t*>(g_transformOutput);
                storeObservedValue(g_transformObservation + 42, output[0]);
                storeObservedValue(g_transformObservation + 44, output[1]);
                copyObservedWords(g_transformObservation + 46, output[0], 8);
                copyObservedWords(g_transformObservation + 54, output[1], 8);
            }
        }
        state->uc_mcontext.regs[0] =
                *reinterpret_cast<const uint64_t*>(state->uc_mcontext.sp + 0x68);
    } else if (offset == kSyscallReturnSite) {
        if (isSignerThread()) {
            ++g_transformObservation[122];
            g_transformObservation[123] =
                    static_cast<uint32_t>(state->uc_mcontext.regs[8]);
            storeObservedValue(g_transformObservation + 124,
                               state->uc_mcontext.regs[0]);
        }
        state->uc_mcontext.sp += 0x50;
    } else if (coreNestedCallIndex(offset) >= 0) {
        const int index = coreNestedCallIndex(offset);
        const uint64_t target =
                state->uc_mcontext.regs[kCoreNestedCalls[index].targetRegister];
        if (isSignerThread()) {
            if (g_traceCallState) captureCallState(offset, state);
            storeObservedValue(g_coreNestedTargets + index * 2,
                               target >= g_imageBase ? target - g_imageBase : target);
        }
        if (offset == kTransformCallSite && isSignerThread()) {
            storeObservedValue(g_transformObservation,
                               state->uc_mcontext.regs[0]);
            storeObservedValue(g_transformObservation + 2,
                               state->uc_mcontext.regs[1]);
            g_transformObservation[4] =
                    static_cast<uint32_t>(state->uc_mcontext.regs[2]);
            storeObservedValue(g_transformObservation + 5,
                               state->uc_mcontext.regs[3]);
            storeObservedValue(g_transformObservation + 7,
                               state->uc_mcontext.regs[8]);
            storeObservedValue(g_transformObservation + 9, target);
            for (size_t registerIndex = 4; registerIndex <= 7; ++registerIndex) {
                storeObservedValue(
                        g_transformObservation + 94 + (registerIndex - 4) * 2,
                        state->uc_mcontext.regs[registerIndex]);
            }
            storeObservedValue(g_transformObservation + 102,
                               state->uc_mcontext.regs[28]);
            storeObservedValue(g_transformObservation + 104,
                               state->uc_mcontext.sp);
            storeObservedValue(g_transformObservation + 106,
                               state->uc_mcontext.regs[30]);
            const sig_atomic_t callIndex = g_transformCallCount++;
            if (callIndex == 0) {
                for (size_t registerIndex = 0; registerIndex < 8; ++registerIndex) {
                    storeObservedValue(g_transformCallRegisters + registerIndex * 2,
                                       state->uc_mcontext.regs[registerIndex]);
                }
                errno = 0;
                g_transformX5ReadBytes = static_cast<sig_atomic_t>(
                        copySafeObservedWords(g_transformCallX5,
                                              state->uc_mcontext.regs[5],
                                              kTransformX5WordCount));
                g_transformX5ReadError = errno;
                errno = 0;
                g_transformX28ReadBytes = static_cast<sig_atomic_t>(
                        copySafeObservedWords(g_transformCallX28,
                                              state->uc_mcontext.regs[28],
                                              kTransformX28WordCount));
                g_transformX28ReadError = errno;
                const uintptr_t x5Indirect =
                        (static_cast<uintptr_t>(g_transformCallX5[5]) << 32)
                        | g_transformCallX5[4];
                errno = 0;
                g_transformX5IndirectReadBytes = static_cast<sig_atomic_t>(
                        copySafeObservedWords(g_transformCallX5Indirect,
                                              x5Indirect,
                                              kTransformX5IndirectWordCount));
                g_transformX5IndirectReadError = errno;
            }
            g_transformOutput = state->uc_mcontext.regs[8];
            copyObservedWords(g_transformObservation + 70,
                              state->uc_mcontext.regs[0], 8);
            copyObservedWords(g_transformObservation + 78,
                              state->uc_mcontext.regs[1], 8);
            copyObservedWords(g_transformObservation + 86,
                              state->uc_mcontext.regs[8], 8);
            if (state->uc_mcontext.regs[0]) {
                const auto* first = reinterpret_cast<const volatile uintptr_t*>(
                        state->uc_mcontext.regs[0]);
                copyObservedWords(g_transformObservation + 12, first[0], 4);
                copyObservedWords(g_transformObservation + 16, first[1], 4);
            }
            if (state->uc_mcontext.regs[1]) {
                const auto* second = reinterpret_cast<const volatile uintptr_t*>(
                        state->uc_mcontext.regs[1]);
                copyObservedWords(g_transformObservation + 20, second[0], 4);
                copyObservedWords(g_transformObservation + 24, second[1], 4);
                if (second[0] >= 0x10000) {
                    copyObservedWords(g_transformObservation + 110,
                                      second[0] + 16, 4);
                }
                if (second[1] >= 0x10000) {
                    copyObservedWords(g_transformObservation + 118,
                                      second[1] + 16, 4);
                    const uintptr_t payload =
                            *reinterpret_cast<const volatile uintptr_t*>(
                                    second[1] + 8);
                    storeObservedValue(g_transformObservation + 114, payload);
                    if (payload >= 0x10000) {
                        const auto* bytes =
                                reinterpret_cast<const volatile uint8_t*>(payload);
                        g_transformObservation[116] = bytes[0]
                                | (static_cast<uint32_t>(bytes[1]) << 8)
                                | (static_cast<uint32_t>(bytes[2]) << 16)
                                | (static_cast<uint32_t>(bytes[3]) << 24);
                    }
                }
            }
            copyObservedWords(g_transformObservation + 28,
                              state->uc_mcontext.regs[3], 4);
            if (state->uc_mcontext.regs[8]) {
                const auto* output = reinterpret_cast<const volatile uintptr_t*>(
                        state->uc_mcontext.regs[8]);
                copyObservedWords(g_transformObservation + 32, output[0], 4);
                copyObservedWords(g_transformObservation + 36, output[1], 4);
            }
        }
        if (isSignerThread()) ++g_coreNestedHits[index];
        state->uc_mcontext.regs[30] = g_imageBase + offset + 4;
        state->uc_mcontext.pc = target;
        return;
    } else if (nestedMethodCallIndex(offset) >= 0) {
        const int index = nestedMethodCallIndex(offset);
        const uint64_t target = state->uc_mcontext.regs[8];
        if (isSignerThread()) {
            if (g_traceCallState) captureCallState(offset, state);
            storeObservedValue(g_nestedMethodTargets + index * 2,
                               target >= g_imageBase ? target - g_imageBase : target);
            ++g_nestedMethodHits[index];
        }
        state->uc_mcontext.regs[30] = g_imageBase + offset + 4;
        state->uc_mcontext.pc = target;
        return;
    } else if (offset == kPairMethodCallSite) {
        const uintptr_t target = state->uc_mcontext.regs[8];
        if (isSignerThread() && g_traceCallState) {
            const sig_atomic_t eventIndex = g_callStateEventCount;
            captureCallState(offset, state);
            if (eventIndex >= 0
                    && static_cast<size_t>(eventIndex) < kCallStateEventCapacity) {
                volatile uint32_t* event = g_callStateEvents
                        + static_cast<size_t>(eventIndex) * kCallStateEventWordCount;
                storeObservedValue(event + 33, target >= g_imageBase
                        ? target - g_imageBase : target);
            }
        }
        state->uc_mcontext.regs[30] = g_imageBase + offset + 4;
        state->uc_mcontext.pc = target;
        return;
    } else if (offset == kPairMethodReturnOne
            || offset == kPairMethodReturnTwo) {
        if (isSignerThread() && g_traceCallState) {
            captureCallState(offset, state);
        }
        const uintptr_t frameSlot = state->uc_mcontext.regs[29]
                - (offset == kPairMethodReturnOne ? 0xb8 : 0xe8);
        state->uc_mcontext.regs[8] =
                *reinterpret_cast<const volatile uint64_t*>(frameSlot);
    } else if (offset == kPairCallbackState) {
        state->uc_mcontext.regs[1] =
                *reinterpret_cast<uint64_t*>(state->uc_mcontext.regs[29] - 0xe8);
        ++g_hits[3];
    }

    state->uc_mcontext.pc = g_imageBase + offset + 4;
}

bool installInstructionTrap(uintptr_t offset) {
    const long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) return false;

    const uintptr_t address = g_imageBase + offset;
    const uintptr_t page = address & ~static_cast<uintptr_t>(pageSize - 1);
    if (mprotect(reinterpret_cast<void*>(page), static_cast<size_t>(pageSize),
                 PROT_READ | PROT_WRITE) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                            "mprotect failed at offset 0x%lx: %s",
                            static_cast<unsigned long>(offset), strerror(errno));
        return false;
    }

    *reinterpret_cast<volatile uint32_t*>(address) = kBrkInstruction;
    __builtin___clear_cache(reinterpret_cast<char*>(address),
                            reinterpret_cast<char*>(address + sizeof(kBrkInstruction)));

    if (mprotect(reinterpret_cast<void*>(page), static_cast<size_t>(pageSize),
                 PROT_READ | PROT_EXEC) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                            "Could not restore executable page at offset 0x%lx: %s",
                            static_cast<unsigned long>(offset), strerror(errno));
        return false;
    }
    return true;
}

bool installMallocReturnTrampoline() {
    const long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) return false;
    void* page = mmap(nullptr, static_cast<size_t>(pageSize),
                      PROT_READ | PROT_WRITE,
                      MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (page == MAP_FAILED) return false;

    g_mallocReturnTrap = reinterpret_cast<uintptr_t>(page);
    *reinterpret_cast<volatile uint32_t*>(g_mallocReturnTrap) = kBrkInstruction;
    __builtin___clear_cache(reinterpret_cast<char*>(g_mallocReturnTrap),
                            reinterpret_cast<char*>(g_mallocReturnTrap + 4));
    if (mprotect(page, static_cast<size_t>(pageSize), PROT_READ | PROT_EXEC) != 0) {
        g_mallocReturnTrap = 0;
        return false;
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_arm64trace_ProbeActivity_nativePrepare(
        JNIEnv* env, jclass, jstring libraryPath) {
    const char* path = env->GetStringUTFChars(libraryPath, nullptr);
    if (!path) return JNI_FALSE;

    g_library = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    env->ReleaseStringUTFChars(libraryPath, path);
    if (!g_library) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "dlopen failed: %s", dlerror());
        throwJava(env, "Could not load the ARM64 target library");
        return JNI_FALSE;
    }

    g_targetOnLoad = reinterpret_cast<TargetJniOnLoad>(dlsym(g_library, "JNI_OnLoad"));
    if (!g_targetOnLoad) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Target JNI_OnLoad is unavailable");
        throwJava(env, "Target JNI_OnLoad is unavailable");
        return JNI_FALSE;
    }

    if (env->GetJavaVM(&g_vm) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Could not get JavaVM");
        throwJava(env, "Could not get JavaVM");
        return JNI_FALSE;
    }

    ImageSearch search{};
    dl_iterate_phdr(findImage, &search);
    if (!search.base) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Target image base was not found");
        throwJava(env, "Target image base was not found");
        return JNI_FALSE;
    }

    g_imageBase = search.base;
    g_signer = reinterpret_cast<Signer>(g_imageBase + kSignerOffset);

    struct sigaction action{};
    action.sa_sigaction = handleTrap;
    action.sa_flags = SA_SIGINFO;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGTRAP, &action, nullptr) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Could not install SIGTRAP handler: %s",
                            strerror(errno));
        throwJava(env, "Could not install the ARM64 breakpoint handler");
        return JNI_FALSE;
    }
    if (!installMallocReturnTrampoline()) {
        throwJava(env, "Could not install the malloc return tracer");
        return JNI_FALSE;
    }

    constexpr uintptr_t trapOffsets[] = {
            kSingleCallbackState,
            kPairCallbackState,
            kSingleAdapter,
            kSingleHelper,
            kPairAdapter,
            kPairHelper,
            kPairHelperReturn,
            kPairAdapterReturn,
            kPairResultLoad,
            kSignerResultLoad,
            kRandPltBranch,
            kMallocPltBranch,
            kCallocPltBranch,
            kPosixMemalignPltBranch,
            kReallocPltBranch,
            kMmapPltBranch,
            kSrandPltBranch,
            kGettimeofdayPltBranch,
            kClockGettimePltBranch,
            kLrand48PltBranch,
            kSrand48PltBranch,
            kSyscallPltBranch,
            kFopenPltBranch,
            kFreadPltBranch,
            kReadPltBranch,
            kPthreadSelfPltBranch,
            kGettidPltBranch,
            kOpenat2PltBranch,
            kGetauxvalPltBranch,
            kOpenPltBranch,
            kTimePltBranch,
            kGettimeofdayDeadlineReturn,
            kGettimeofdaySampleReturn,
            kClockMonotonicReturn,
            kClockRealtimeReturn,
            kRandValueReturn,
            kLrand48ReturnSite,
            kRandObjectStoreSites[0],
            kRandObjectStoreSites[1],
            kRandObjectStoreSites[2],
            kRandObjectStoreSites[3],
            kStringBuilderHelper,
            kSplitStringHelper,
            kPairContainerUpdate,
            kPairCompareResultBranch,
            kParserEmptyGuard,
            kParserOddCountGuard,
            kParserStatusGuard,
            kCoreVirtualCall,
            kPairMethodCallSite,
            kPairMethodReturnOne,
            kPairMethodReturnTwo,
            kTransformReturnSite,
            kSyscallReturnSite,
    };
    for (uintptr_t offset : trapOffsets) {
        if (!installInstructionTrap(offset)) {
            throwJava(env, "Could not install all ARM64 instruction breakpoints");
            return JNI_FALSE;
        }
    }
    for (const CoreCallSite& callSite : kCoreNestedCalls) {
        if (!installInstructionTrap(callSite.offset)) {
            throwJava(env, "Could not install all core method call breakpoints");
            return JNI_FALSE;
        }
    }
    for (uintptr_t offset : kNestedMethodCallSites) {
        if (!installInstructionTrap(offset)) {
            throwJava(env, "Could not install all nested method call breakpoints");
            return JNI_FALSE;
        }
    }
    for (uintptr_t offset : kX5RestoreSites) {
        if (!installInstructionTrap(offset)) {
            throwJava(env, "Could not install all X5 restore breakpoints");
            return JNI_FALSE;
        }
    }

    __android_log_print(ANDROID_LOG_INFO, kTag, "ARM64 traps installed; image base=%p signer=%p",
                        reinterpret_cast<void*>(g_imageBase),
                        reinterpret_cast<void*>(g_signer));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_arm64trace_ProbeActivity_nativeSign(
        JNIEnv* env, jclass, jstring urlValue, jstring headersValue,
        jboolean traceCallState) {
    if (!g_signer || !g_targetOnLoad || !g_vm || !urlValue || !headersValue) {
        throwJava(env, "Signer is not ready or the synthetic input is missing");
        return nullptr;
    }

    const char* url = env->GetStringUTFChars(urlValue, nullptr);
    const char* headers = env->GetStringUTFChars(headersValue, nullptr);
    if (!url || !headers) {
        if (url) env->ReleaseStringUTFChars(urlValue, url);
        if (headers) env->ReleaseStringUTFChars(headersValue, headers);
        return nullptr;
    }

    if (!g_initialized) {
        const jint version = g_targetOnLoad(g_vm, nullptr);
        if (version != JNI_VERSION_1_6) {
            env->ReleaseStringUTFChars(urlValue, url);
            env->ReleaseStringUTFChars(headersValue, headers);
            throwJava(env, "Target JNI_OnLoad initialization failed");
            return nullptr;
        }
        g_initialized = true;
        __android_log_print(ANDROID_LOG_INFO, kTag,
                            "Target JNI_OnLoad completed; signer call is starting");
    }

    for (volatile sig_atomic_t& hit : g_hits) hit = 0;
    g_randPltCalls = 0;
    g_timeApiEventCount = 0;
    for (volatile uint32_t& value : g_timeApiEvents) value = 0;
    g_timeResultCount = 0;
    for (volatile uint32_t& value : g_timeResults) value = 0;
    for (volatile sig_atomic_t& hit : g_randCallSiteHits) hit = 0;
    g_randValueCount = 0;
    for (size_t index = 0; index < kRandValueCapacity; ++index) {
        g_randValues[index] = 0;
        g_randCallerOffsets[index] = 0;
    }
    for (volatile uint32_t& value : g_randObjectCallbacks) value = 0;
    g_randObjectEventCount = 0;
    for (volatile uint32_t& value : g_randObjectEvents) value = 0;
    for (volatile uint32_t& part : g_pairArguments) part = 0;
    for (volatile uint32_t& part : g_pairReturn) part = 0;
    for (volatile uint32_t& part : g_adapterReturn) part = 0;
    for (volatile uint32_t& part : g_resultSlot) part = 0;
    for (volatile uint32_t& part : g_signerResult) part = 0;
    for (volatile uint32_t& part : g_stringBuilderArguments) part = 0;
    for (volatile uint32_t& part : g_splitArguments) part = 0;
    for (volatile uint32_t& part : g_coreVirtualCall) part = 0;
    for (volatile sig_atomic_t& hit : g_coreNestedHits) hit = 0;
    for (volatile uint32_t& part : g_coreNestedTargets) part = 0;
    for (volatile sig_atomic_t& hit : g_nestedMethodHits) hit = 0;
    for (volatile uint32_t& part : g_nestedMethodTargets) part = 0;
    for (volatile uint32_t& part : g_transformObservation) part = 0;
    g_transformCallCount = 0;
    for (volatile uint32_t& part : g_transformCallRegisters) part = 0;
    for (volatile uint32_t& part : g_transformCallX5) part = 0;
    for (volatile uint32_t& part : g_transformCallX28) part = 0;
    g_transformX5ReadBytes = 0;
    g_transformX28ReadBytes = 0;
    g_transformX5ReadError = 0;
    g_transformX28ReadError = 0;
    g_transformX5IndirectReadBytes = 0;
    g_transformX5IndirectReadError = 0;
    g_transformThreadId = 0;
    __atomic_store_n(&g_srand48Count, 0U, __ATOMIC_RELAXED);
    __atomic_store_n(&g_lrand48CallCount, 0U, __ATOMIC_RELAXED);
    __atomic_store_n(&g_lrand48ReturnCount, 0U, __ATOMIC_RELAXED);
    __atomic_store_n(&g_syscallCount, 0U, __ATOMIC_RELAXED);
    __atomic_store_n(&g_srandCount, 0U, __ATOMIC_RELAXED);
    for (size_t index = 0; index < kCapturedLrand48Count; ++index) {
        g_srand48Seeds[index] = 0;
        g_srand48Callers[index] = 0;
        g_srand48ThreadIds[index] = 0;
        g_lrand48Values[index] = 0;
        g_lrand48Callers[index] = 0;
        g_lrand48ThreadIds[index] = 0;
    }
    for (size_t index = 0; index < kCapturedSyscallCount; ++index) {
        g_syscallNumbers[index] = 0;
        g_syscallCallers[index] = 0;
        g_syscallThreadIds[index] = 0;
    }
    for (size_t index = 0; index < kCapturedSrandCount; ++index) {
        g_srandSeeds[index] = 0;
        g_srandCallers[index] = 0;
        g_srandThreadIds[index] = 0;
    }
    g_traceCallState = traceCallState == JNI_TRUE;
    g_callStateEventCount = 0;
    for (volatile uint32_t& value : g_callStateEvents) value = 0;
    __atomic_store_n(&g_fileApiEventCount, 0U, __ATOMIC_RELAXED);
    for (volatile uint32_t& value : g_fileApiEvents) value = 0;
    __atomic_store_n(&g_allocationEventCount, 0U, __ATOMIC_RELAXED);
    for (volatile uint32_t& value : g_allocationEvents) value = 0;
    g_treeNodeEventCount = 0;
    for (volatile uint32_t& value : g_treeNodeEvents) value = 0;
    g_mallocReturnAddress = 0;
    g_mallocCaller = 0;
    g_mallocRequestedSize = 0;
    g_allocationOutputAddress = 0;
    g_allocationKind = 0;
    g_transformOutput = 0;
    g_splitLengths[0] = -1;
    g_splitLengths[1] = -1;
    srand(kSyntheticRandSeed);
    g_signerThreadId = static_cast<sig_atomic_t>(syscall(SYS_gettid));
    const char* result = g_signer(url, headers);
    g_transformThreadId = static_cast<uint32_t>(g_signerThreadId);
    g_signerThreadId = 0;
    g_traceCallState = 0;
    const int length = result ? static_cast<int>(strlen(result)) : -1;
    jstring signedOutput = result ? env->NewStringUTF(result) : nullptr;
    env->ReleaseStringUTFChars(urlValue, url);
    env->ReleaseStringUTFChars(headersValue, headers);
    if (result && !signedOutput) return nullptr;

    jlong values[kMetricsLength] = {
            length,
            g_hits[0],
            g_hits[1],
            g_hits[2],
            g_hits[3],
            g_hits[4],
            g_hits[5],
            g_hits[6],
            static_cast<jlong>(loadObservedValue(g_pairArguments)),
            static_cast<jlong>(loadObservedValue(g_pairArguments + 2)),
            static_cast<jlong>(loadObservedValue(g_pairReturn)),
            g_hits[7],
            static_cast<jlong>(loadObservedValue(g_adapterReturn)),
            static_cast<jlong>(loadObservedValue(g_adapterReturn + 2)),
            g_hits[8],
            static_cast<jlong>(loadObservedValue(g_resultSlot)),
            g_hits[9],
            static_cast<jlong>(loadObservedValue(g_signerResult)),
            g_hits[10],
            static_cast<jlong>(loadObservedValue(g_stringBuilderArguments)),
            static_cast<jlong>(loadObservedValue(g_stringBuilderArguments + 2)),
            static_cast<jlong>(loadObservedValue(g_stringBuilderArguments + 4)),
            g_hits[11],
            static_cast<jlong>(loadObservedValue(g_splitArguments)),
            static_cast<jlong>(loadObservedValue(g_splitArguments + 2)),
            static_cast<jlong>(loadObservedValue(g_splitArguments + 4)),
            g_splitLengths[0],
            g_splitLengths[1],
            g_hits[12],
            g_hits[13],
            g_hits[14],
            g_hits[15],
            g_hits[16],
            g_hits[17],
            g_hits[18],
            g_hits[19],
            g_hits[20],
            g_hits[21],
            g_hits[22],
            static_cast<jlong>(loadObservedValue(g_coreVirtualCall)),
            static_cast<jlong>(loadObservedValue(g_coreVirtualCall + 2)),
    };
    for (size_t index = 0; index < kCoreNestedCallCount; ++index) {
        values[41 + index] = g_coreNestedHits[index];
        values[52 + index] = static_cast<jlong>(
                loadObservedValue(g_coreNestedTargets + index * 2));
    }
    for (size_t index = 0; index < kNestedMethodCallCount; ++index) {
        values[63 + index] = g_nestedMethodHits[index];
        values[92 + index] = static_cast<jlong>(
                loadObservedValue(g_nestedMethodTargets + index * 2));
    }
    values[121] = g_randPltCalls;
    for (size_t index = 0; index < kRandCallReturnSiteCount; ++index) {
        values[122 + index] = g_randCallSiteHits[index];
    }
    values[126] = g_randValueCount;
    for (size_t index = 0; index < kRandValueCapacity; ++index) {
        values[127 + index] = g_randValues[index];
        values[143 + index] = g_randCallerOffsets[index];
    }
    for (size_t index = 0; index < kRandObjectVtableCapacity; ++index) {
        values[159 + index] = static_cast<jlong>(
                loadObservedValue(g_randObjectCallbacks + index * 2));
    }
    values[167] = g_randObjectEventCount;
    for (size_t index = 0; index < kRandObjectEventCapacity; ++index) {
        values[168 + index * 4] = g_randObjectEvents[index * 4];
        values[169 + index * 4] = g_randObjectEvents[index * 4 + 1];
        values[170 + index * 4] = g_randObjectEvents[index * 4 + 2];
        values[171 + index * 4] = g_randObjectEvents[index * 4 + 3];
    }
    values[296] = g_timeApiEventCount;
    for (size_t index = 0; index < kTimeApiEventCapacity; ++index) {
        for (size_t field = 0; field < 4; ++field) {
            values[297 + index * 4 + field] =
                    g_timeApiEvents[index * 4 + field];
        }
    }
    values[393] = g_timeResultCount;
    for (size_t index = 0; index < kTimeResultCapacity; ++index) {
        for (size_t field = 0; field < 6; ++field) {
            values[394 + index * 6 + field] =
                    g_timeResults[index * 6 + field];
        }
    }
    for (size_t index = 0; index < 126; ++index) {
        values[544 + index] = g_transformObservation[index];
    }
    values[670] = g_transformCallCount;
    for (size_t index = 0; index < 16; ++index) {
        values[671 + index] = g_transformCallRegisters[index];
    }
    for (size_t index = 0; index < kTransformX5WordCount; ++index) {
        values[687 + index] = g_transformCallX5[index];
    }
    for (size_t index = 0; index < kTransformX28WordCount; ++index) {
        values[719 + index] = g_transformCallX28[index];
    }
    values[783] = g_transformX5ReadBytes;
    values[784] = g_transformX28ReadBytes;
    values[785] = g_transformX5ReadError;
    values[786] = g_transformX28ReadError;
    values[787] = g_transformX5IndirectReadBytes;
    values[788] = g_transformX5IndirectReadError;
    for (size_t index = 0; index < kTransformX5IndirectWordCount; ++index) {
        values[789 + index] = g_transformCallX5Indirect[index];
    }
    values[821] = g_lrand48CallCount;
    values[822] = g_lrand48ReturnCount;
    values[823] = g_srand48Count;
    values[824] = g_syscallCount;
    values[826] = g_transformThreadId;
    for (size_t index = 0; index < kCapturedLrand48Count; ++index) {
        values[827 + index] = g_lrand48Values[index];
        values[835 + index] = g_lrand48Callers[index];
        values[843 + index] = g_lrand48ThreadIds[index];
        values[851 + index] = g_srand48Seeds[index];
        values[859 + index] = g_srand48Callers[index];
        values[891 + index] = g_srand48ThreadIds[index];
    }
    for (size_t index = 0; index < kCapturedSyscallCount; ++index) {
        values[867 + index] = g_syscallNumbers[index];
        values[875 + index] = g_syscallCallers[index];
        values[883 + index] = g_syscallThreadIds[index];
    }
    values[899] = g_srandCount;
    for (size_t index = 0; index < kCapturedSrandCount; ++index) {
        values[900 + index] = g_srandSeeds[index];
        values[908 + index] = g_srandCallers[index];
        values[916 + index] = g_srandThreadIds[index];
    }
    values[924] = g_callStateEventCount;
    for (size_t index = 0; index < kCallStateEventCapacity
            * kCallStateEventWordCount; ++index) {
        values[925 + index] = g_callStateEvents[index];
    }
    const size_t fileApiCountOffset =
            925 + kCallStateEventCapacity * kCallStateEventWordCount;
    values[fileApiCountOffset] = g_fileApiEventCount;
    for (size_t index = 0; index < kFileApiEventCapacity
            * kFileApiEventWordCount; ++index) {
        values[fileApiCountOffset + 1 + index] = g_fileApiEvents[index];
    }
    const size_t allocationCountOffset = fileApiCountOffset + 1
            + kFileApiEventCapacity * kFileApiEventWordCount;
    values[allocationCountOffset] = g_allocationEventCount;
    for (size_t index = 0; index < kAllocationEventCapacity
            * kAllocationEventWordCount; ++index) {
        values[allocationCountOffset + 1 + index] = g_allocationEvents[index];
    }
    const size_t treeNodeCountOffset = allocationCountOffset + 1
            + kAllocationEventCapacity * kAllocationEventWordCount;
    values[treeNodeCountOffset] = g_treeNodeEventCount;
    for (size_t index = 0; index < kTreeNodeEventCapacity
            * kTreeNodeEventWordCount; ++index) {
        values[treeNodeCountOffset + 1 + index] = g_treeNodeEvents[index];
    }
    jlongArray metrics = env->NewLongArray(kMetricsLength);
    if (!metrics) return nullptr;
    env->SetLongArrayRegion(metrics, 0, kMetricsLength, values);

    jclass objectClass = env->FindClass("java/lang/Object");
    if (!objectClass) return nullptr;
    jobjectArray observation = env->NewObjectArray(2, objectClass, nullptr);
    env->DeleteLocalRef(objectClass);
    if (!observation) return nullptr;
    env->SetObjectArrayElement(observation, 0, metrics);
    env->SetObjectArrayElement(observation, 1, signedOutput);
    return observation;
}