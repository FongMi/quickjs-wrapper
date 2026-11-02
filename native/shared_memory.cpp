#include "shared_memory.h"
#include "virtual_memory.h"
#include "com_fongmi_quickjs_SharedMemory.h"

#include <algorithm>
#include <atomic>
#include <cassert>
#include <cstdlib>
#include <memory>
#include <mutex>
#include <new>
#include <unordered_map>

namespace quickjs_wrapper {
namespace {

#define CHECK_ATOMIC_OPERATION(name) \
    static_assert(com_fongmi_quickjs_SharedMemory_ATOMIC_##name == JS_ATOMIC_##name, "Shared atomic ABI mismatch")
CHECK_ATOMIC_OPERATION(ADD);
CHECK_ATOMIC_OPERATION(AND);
CHECK_ATOMIC_OPERATION(OR);
CHECK_ATOMIC_OPERATION(SUB);
CHECK_ATOMIC_OPERATION(XOR);
CHECK_ATOMIC_OPERATION(EXCHANGE);
CHECK_ATOMIC_OPERATION(COMPARE_EXCHANGE);
CHECK_ATOMIC_OPERATION(LOAD);
CHECK_ATOMIC_OPERATION(STORE);
#undef CHECK_ATOMIC_OPERATION

constexpr size_t MAX_BUFFER = 64 * 1024 * 1024;
constexpr size_t MAX_BUFFERS = 128 * 1024 * 1024;
struct Budget { std::atomic<size_t> bytes{0}; };
struct Allocation {
    size_t references;
    size_t size;
    std::shared_ptr<Budget> budget;
    JavaVM *vm;
    jobject buffer;
    std::unique_ptr<VirtualMemory> memory;
};
std::atomic<int64_t> buffer_roots{0};
std::mutex allocations_mutex;
std::unordered_map<void *, Allocation> allocations;

void delete_buffer_root(JavaVM *vm, jobject buffer) {
    JNIEnv *env = nullptr;
    bool attached = false;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
#ifdef __ANDROID__
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
#else
        if (vm->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) != JNI_OK) return;
#endif
        attached = true;
    }
    if (env) { env->DeleteGlobalRef(buffer); buffer_roots.fetch_sub(1); }
    if (attached) vm->DetachCurrentThread();
}

void duplicate(void *, void *data) {
    bool retained = retain_shared_buffer(data);
    assert(retained);
    if (!retained) std::abort();
}

void release(void *, void *data) { release_shared_buffer(data); }

void throw_argument(JNIEnv *env, const char *message) {
    jclass type = env->FindClass("java/lang/IllegalArgumentException");
    if (type) { env->ThrowNew(type, message); env->DeleteLocalRef(type); }
}

void *range(JNIEnv *env, jobject storage, jint address, jint width) {
    if (!storage) { throw_argument(env, "Shared storage is required"); return nullptr; }
    jlong capacity = env->GetDirectBufferCapacity(storage);
    auto data = static_cast<uint8_t *>(env->GetDirectBufferAddress(storage));
    if (!data || capacity < 0 || address < 0 || width <= 0 ||
        address > capacity || width > capacity - address ||
        (reinterpret_cast<uintptr_t>(data + address) & (width - 1))) {
        throw_argument(env, "Invalid shared atomic memory range");
        return nullptr;
    }
    return data + address;
}

struct WaitCheck { JNIEnv *env; jobject runnable; jmethodID run; };
int check_wait(void *opaque) {
    auto check = static_cast<WaitCheck *>(opaque);
    check->env->CallVoidMethod(check->runnable, check->run);
    return check->env->ExceptionCheck();
}

} // namespace

struct SharedMemoryState { std::shared_ptr<Budget> budget = std::make_shared<Budget>(); };

static void *allocate(void *opaque, size_t size) {
    auto state = static_cast<SharedMemoryState *>(opaque);
    size = std::max(size, size_t{1});
    if (size > MAX_BUFFER || size > MAX_BUFFERS - state->budget->bytes.load()) return nullptr;
    void *data = std::calloc(1, size);
    if (!data) return nullptr;
    try {
        std::lock_guard<std::mutex> lock(allocations_mutex);
        allocations.emplace(data, Allocation{1, size, state->budget, nullptr, nullptr});
    } catch (const std::bad_alloc &) { std::free(data); return nullptr; }
    state->budget->bytes.fetch_add(size);
    return data;
}

SharedMemoryState *install_shared_memory(JSRuntime *runtime) {
    auto state = new SharedMemoryState();
    JSSharedArrayBufferFunctions hooks{allocate, release, duplicate, state};
    JS_SetSharedArrayBufferFunctions(runtime, &hooks);
    JS_SetCanBlock(runtime, true);
    return state;
}

void free_shared_memory_state(SharedMemoryState *state) { delete state; }

int64_t shared_buffer_roots() { return buffer_roots.load(); }

size_t shared_buffer_allocations() {
    std::lock_guard<std::mutex> lock(allocations_mutex);
    return allocations.size();
}

bool retain_shared_buffer(void *data) {
    std::lock_guard<std::mutex> lock(allocations_mutex);
    auto found = allocations.find(data);
    if (found == allocations.end()) return false;
    ++found->second.references;
    return true;
}

void release_shared_buffer(void *data) {
    Allocation allocation{};
    {
        std::lock_guard<std::mutex> lock(allocations_mutex);
        auto found = allocations.find(data);
        assert(found != allocations.end() && found->second.references);
        if (found == allocations.end() || --found->second.references) return;
        allocation = std::move(found->second);
        allocations.erase(found);
    }
    if (allocation.memory) {
        allocation.memory.reset();
        delete_buffer_root(allocation.vm, allocation.buffer);
    } else if (allocation.buffer) delete_buffer_root(allocation.vm, allocation.buffer);
    else {
        allocation.budget->bytes.fetch_sub(allocation.size);
        std::free(data);
    }
}

jobject allocate_shared_memory(JNIEnv *env, jint capacity, jint size, jobject charge) {
    if (capacity <= 0 || capacity > MAX_BUFFER || size < 0 || size > capacity || (size % VirtualMemory::WASM_PAGE_SIZE) || !charge) {
        throw_argument(env, "Invalid shared memory reservation");
        return nullptr;
    }
    auto memory = VirtualMemory::allocate(static_cast<size_t>(capacity), static_cast<size_t>(size));
    if (!memory) {
        jclass type = env->FindClass("com/fongmi/quickjs/WasmLimitException");
        if (type) { env->ThrowNew(type, "Cannot reserve or commit shared WASM memory"); env->DeleteLocalRef(type); }
        return nullptr;
    }
    jobject buffer = env->NewDirectByteBuffer(memory->data(), capacity);
    if (!buffer) return nullptr;
    jobject root = env->NewGlobalRef(charge);
    if (!root) { env->DeleteLocalRef(buffer); return nullptr; }
    JavaVM *vm = nullptr;
    env->GetJavaVM(&vm);
    try {
        std::lock_guard<std::mutex> lock(allocations_mutex);
        void *data = memory->data();
        auto entry = allocations.emplace(data, Allocation{1, static_cast<size_t>(capacity), nullptr, vm, root, std::move(memory)});
        assert(entry.second);
        if (!entry.second) std::abort();
    } catch (const std::bad_alloc &) {
        env->DeleteGlobalRef(root);
        env->DeleteLocalRef(buffer);
        jclass type = env->FindClass("java/lang/OutOfMemoryError");
        if (type) { env->ThrowNew(type, "Cannot track shared memory reservation"); env->DeleteLocalRef(type); }
        return nullptr;
    }
    buffer_roots.fetch_add(1);
    return buffer;
}

bool grow_shared_memory(JNIEnv *env, jobject storage, jint size) {
    if (!storage) { throw_argument(env, "Shared storage is required"); return false; }
    void *data = env->GetDirectBufferAddress(storage);
    std::lock_guard<std::mutex> lock(allocations_mutex);
    auto found = allocations.find(data);
    if (found == allocations.end() || !found->second.memory || size < 0 || (size % VirtualMemory::WASM_PAGE_SIZE)) {
        throw_argument(env, "Invalid shared memory growth");
        return false;
    }
    return found->second.memory->grow(static_cast<size_t>(size));
}

size_t shared_reserved_bytes() {
    std::lock_guard<std::mutex> lock(allocations_mutex);
    size_t bytes = 0;
    for (const auto &entry : allocations)
        if (entry.second.memory) bytes += entry.second.memory->capacity();
    return bytes;
}

size_t shared_committed_bytes() {
    std::lock_guard<std::mutex> lock(allocations_mutex);
    size_t bytes = 0;
    for (const auto &entry : allocations)
        if (entry.second.memory) bytes += entry.second.memory->size();
    return bytes;
}

JSValue shared_buffer(JNIEnv *env, JSContext *context, jobject buffer) {
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    void *data = env->GetDirectBufferAddress(buffer);
    if (!data || capacity < 0 || capacity > MAX_BUFFER || (reinterpret_cast<uintptr_t>(data) & 7))
        return JS_ThrowTypeError(context, "Shared buffer must be bounded, direct and aligned to eight bytes");
    jobject root = env->NewGlobalRef(buffer);
    if (!root) return JS_EXCEPTION;
    buffer_roots.fetch_add(1);
    JavaVM *vm = nullptr;
    env->GetJavaVM(&vm);
    bool inserted = false;
    try {
        std::lock_guard<std::mutex> lock(allocations_mutex);
        auto entry = allocations.emplace(data, Allocation{1, static_cast<size_t>(capacity), nullptr, vm, root});
        inserted = entry.second;
        if (!inserted) ++entry.first->second.references;
    } catch (const std::bad_alloc &) {
        env->DeleteGlobalRef(root);
        buffer_roots.fetch_sub(1);
        return JS_ThrowOutOfMemory(context);
    }
    if (!inserted) { env->DeleteGlobalRef(root); buffer_roots.fetch_sub(1); }
    JSValue result = JS_NewArrayBuffer(context, static_cast<uint8_t *>(data), static_cast<size_t>(capacity), 0, nullptr, nullptr, true);
    release_shared_buffer(data);
    return result;
}

} // namespace quickjs_wrapper

extern "C" JNIEXPORT jint JNICALL Java_com_fongmi_quickjs_SharedMemory_abi0(JNIEnv *, jclass) { return 1; }

extern "C" JNIEXPORT jlong JNICALL Java_com_fongmi_quickjs_SharedMemory_atomic0(
    JNIEnv *env, jclass, jobject storage, jint address, jint width, jint operation, jlong value, jlong replacement) {
    if ((width != 1 && width != 2 && width != 4 && width != 8) || operation < JS_ATOMIC_ADD || operation > JS_ATOMIC_STORE) {
        quickjs_wrapper::throw_argument(env, "Invalid shared atomic operation"); return 0;
    }
    void *data = quickjs_wrapper::range(env, storage, address, width);
    return data ? static_cast<jlong>(JS_AtomicMemoryOp(data, width, static_cast<JSAtomicMemoryOperation>(operation), value, replacement)) : 0;
}

extern "C" JNIEXPORT jint JNICALL Java_com_fongmi_quickjs_SharedMemory_wait0(
    JNIEnv *env, jclass, jobject storage, jint address, jint width, jlong expected, jlong timeout, jobject check) {
    if (width != 4 && width != 8) { quickjs_wrapper::throw_argument(env, "Invalid shared wait width"); return 0; }
    void *data = quickjs_wrapper::range(env, storage, address, width);
    if (!data) return 0;
    if (!check) { quickjs_wrapper::throw_argument(env, "Wait cancellation check is required"); return 0; }
    jclass type = env->GetObjectClass(check);
    if (!type) return 0;
    jmethodID run = env->GetMethodID(type, "run", "()V");
    env->DeleteLocalRef(type);
    if (!run) return 0;
    quickjs_wrapper::WaitCheck state{env, check, run};
    return JS_AtomicMemoryWait(data, width, expected, timeout, quickjs_wrapper::check_wait, &state);
}

extern "C" JNIEXPORT jint JNICALL Java_com_fongmi_quickjs_SharedMemory_notify0(
    JNIEnv *env, jclass, jobject storage, jint address, jint count) {
    void *data = quickjs_wrapper::range(env, storage, address, 4);
    return data ? static_cast<jint>(JS_AtomicMemoryNotify(data, static_cast<uint32_t>(count))) : 0;
}

extern "C" JNIEXPORT void JNICALL Java_com_fongmi_quickjs_SharedMemory_fence0(JNIEnv *, jclass) {
    JS_AtomicMemoryFence();
}
