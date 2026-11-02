#pragma once

#include <jni.h>
#include <quickjs.h>
#include <cstdint>

namespace quickjs_wrapper {

struct SharedMemoryState;
SharedMemoryState *install_shared_memory(JSRuntime *runtime);
void free_shared_memory_state(SharedMemoryState *state);
JSValue shared_buffer(JNIEnv *env, JSContext *context, jobject buffer);
int64_t shared_buffer_roots();
size_t shared_buffer_allocations();
size_t shared_reserved_bytes();
size_t shared_committed_bytes();
bool retain_shared_buffer(void *data);
void release_shared_buffer(void *data);

} // namespace quickjs_wrapper
