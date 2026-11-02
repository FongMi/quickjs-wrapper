#include "virtual_memory.h"

#include <new>

#ifdef _WIN32
#include <windows.h>
#else
#include <sys/mman.h>
#endif

namespace quickjs_wrapper {
namespace {

void release(void *data, size_t capacity) {
#ifdef _WIN32
    VirtualFree(data, 0, MEM_RELEASE);
#else
    munmap(data, capacity);
#endif
}

} // namespace

std::unique_ptr<VirtualMemory> VirtualMemory::allocate(size_t capacity, size_t size) {
    if (!capacity || size > capacity || size % WASM_PAGE_SIZE) return nullptr;
#ifdef _WIN32
    void *data = VirtualAlloc(nullptr, capacity, MEM_RESERVE, PAGE_NOACCESS);
#else
    void *data = mmap(nullptr, capacity, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (data == MAP_FAILED) data = nullptr;
#endif
    if (!data) return nullptr;
    std::unique_ptr<VirtualMemory> memory(new (std::nothrow) VirtualMemory(static_cast<uint8_t *>(data), capacity));
    if (!memory) { release(data, capacity); return nullptr; }
    if (!memory->grow(size)) return nullptr;
    return memory;
}

VirtualMemory::~VirtualMemory() { release(data_, capacity_); }

bool VirtualMemory::grow(size_t size) {
    if (size < size_ || size > capacity_ || size % WASM_PAGE_SIZE) return false;
    if (size == size_) return true;
#ifdef _WIN32
    if (!VirtualAlloc(data_ + size_, size - size_, MEM_COMMIT, PAGE_READWRITE)) return false;
#else
    if (mprotect(data_ + size_, size - size_, PROT_READ | PROT_WRITE)) return false;
#endif
    size_ = size;
    return true;
}

} // namespace quickjs_wrapper
