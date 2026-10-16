#pragma once

#include <quickjs.h>
#include <cstddef>

namespace quickjs_wrapper {

struct SharedAllocation {
    JSReallocArrayBufferDataFunc *realloc;
    void *opaque;
    uint8_t *data;
    size_t capacity;
    size_t pins = 0;
    bool js_owner = true;
};

inline void free_allocation(JSRuntime *runtime, SharedAllocation *allocation) {
    allocation->realloc(runtime, allocation->opaque, allocation->data, 0);
    delete allocation;
}

inline void *share_allocation(JSRuntime *runtime, void *opaque, void *, size_t size) {
    auto allocation = static_cast<SharedAllocation *>(opaque);
    if (!size) {
        allocation->js_owner = false;
        if (!allocation->pins) free_allocation(runtime, allocation);
        return nullptr;
    }
    if (allocation->pins) return size == allocation->capacity ? allocation->data : nullptr;
    void *data = allocation->realloc(runtime, allocation->opaque, allocation->data, size);
    if (data) {
        allocation->data = static_cast<uint8_t *>(data);
        allocation->capacity = size;
    }
    return data;
}

} // namespace quickjs_wrapper
