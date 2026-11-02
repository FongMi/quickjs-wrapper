#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>

namespace quickjs_wrapper {

// Stable reservations committed in WebAssembly page units.
class VirtualMemory {
public:
    static constexpr size_t WASM_PAGE_SIZE = 65536;
    static std::unique_ptr<VirtualMemory> allocate(size_t capacity, size_t size);
    ~VirtualMemory();
    VirtualMemory(const VirtualMemory &) = delete;
    VirtualMemory &operator=(const VirtualMemory &) = delete;
    uint8_t *data() const { return data_; }
    size_t capacity() const { return capacity_; }
    size_t size() const { return size_; }
    bool grow(size_t size);

private:
    VirtualMemory(uint8_t *data, size_t capacity) : data_(data), capacity_(capacity) {}
    uint8_t *data_;
    size_t capacity_;
    size_t size_ = 0;
};

} // namespace quickjs_wrapper
