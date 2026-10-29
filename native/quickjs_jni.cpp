#include <jni.h>
#include <quickjs.h>
#include "buffer_lease.h"

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <limits>
#include <mutex>
#include <new>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

namespace {

// Counts only global references owned by this wrapper, across its runtimes.
std::atomic<int64_t> global_references{0};

jobject new_global_ref(JNIEnv *env, jobject value) {
    jobject result = env->NewGlobalRef(value);
    if (result) global_references.fetch_add(1, std::memory_order_relaxed);
    return result;
}

void delete_global_ref(JNIEnv *env, jobject value) {
    if (!value) return;
    env->DeleteGlobalRef(value);
    global_references.fetch_sub(1, std::memory_order_relaxed);
}

using quickjs_wrapper::SharedAllocation;
using quickjs_wrapper::free_allocation;
using quickjs_wrapper::share_allocation;

struct Callback {
    JSValue function;
    jlong origin;
};

struct Reference {
    JSValue value;
    size_t count;
};

struct Conversion;

struct BufferRange {
    SharedAllocation *allocation;
    uint8_t *data;
    size_t length;
};

struct Runtime {
    JNIEnv *env;
    jobject owner;
    JSRuntime *runtime = nullptr;
    JSContext *context = nullptr;
    JSValue promise_then = JS_UNDEFINED;
    JSValue error_stack_getter = JS_UNDEFINED;
    JSValue host_receiver = JS_UNDEFINED;
    std::unordered_map<jlong, Callback> callbacks;
    std::unordered_map<jlong, size_t> callback_origins;
    std::unordered_map<jlong, size_t> job_origins;
    std::unordered_map<jlong, Reference> references;
    std::unordered_map<void *, jlong> identities;
    std::unordered_map<void *, JSValue> rejections;
    std::unordered_map<jlong, BufferRange> buffer_leases;
    jlong next_buffer_lease = 1;
    Conversion *conversion = nullptr;
    jlong next_callback = 1;
    jlong next_reference = 1;
    jlong active = 0;
    int depth = 0;
    std::mutex cancel_mutex;
    std::unordered_set<jlong> canceled;
    std::vector<jlong> executing;
    jlong last_call = 0;
    std::atomic<bool> stopping{false};
    std::vector<jclass> classes;
    jclass boolean_class, integer_class, short_class, byte_class, long_class, double_class, number_class;
    jclass string_class, bytes_class, array_class, object_class, bigint_class;
    jclass json_class, binary_class, text_class, byte_array_class, direct_buffer_class, callback_class, reference_class, transfer_class;
    jfieldID direct_buffer_data;
    jmethodID dispatch_method, dispatch_async_method, complete_method, rejection_method, normalize_method, load_method, load_bytecode_method;
    jmethodID uncatchable_method;
    JSClassID job_scope_class = 0;
    bool job_scopes_enabled = false;
    jmethodID capture_job_scope, enter_job_scope, leave_job_scope;
    jmethodID load_source_bytecode_method, store_bytecode_method, module_failed_method, module_url_method;
    bool module_cache = false;
    jmethodID boolean_value, integer_value, double_value, bigint_constructor;
    jmethodID boolean_get, number_get, number_double, bigint_string;
    jmethodID json_constructor, json_text, binary_json, binary_property, binary_bytes;
    jmethodID text_json, text_property, text_value;
    jmethodID byte_array_json, byte_array_property, byte_array_bytes;
    jmethodID callback_constructor;
    jfieldID callback_id, callback_runtime;
    jmethodID reference_constructor, transfer_value;
    jfieldID reference_id, reference_runtime;
};

Runtime *get(jlong handle) {
    return reinterpret_cast<Runtime *>(handle);
}

struct DirectBufferLease {
    Runtime *runtime;
    jobject buffer;
};

void *release_direct_buffer(JSRuntime *, void *opaque, void *, size_t size) {
    if (size) return nullptr;
    auto lease = static_cast<DirectBufferLease *>(opaque);
    delete_global_ref(lease->runtime->env, lease->buffer);
    delete lease;
    return nullptr;
}

std::string utf8(JNIEnv *env, jstring string) {
    if (!string) return {};
    const jchar *chars = env->GetStringChars(string, nullptr);
    if (!chars) return {};
    jsize length = env->GetStringLength(string);
    std::string result;
    for (jsize i = 0; i < length; ++i) {
        uint32_t c = chars[i];
        if (c >= 0xd800 && c <= 0xdbff && i + 1 < length &&
            chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff) {
            c = 0x10000 + ((c - 0xd800) << 10) + chars[++i] - 0xdc00;
        }
        if (c < 0x80) result += static_cast<char>(c);
        else if (c < 0x800) {
            result += static_cast<char>(0xc0 | (c >> 6));
            result += static_cast<char>(0x80 | (c & 63));
        } else if (c < 0x10000) {
            result += static_cast<char>(0xe0 | (c >> 12));
            result += static_cast<char>(0x80 | ((c >> 6) & 63));
            result += static_cast<char>(0x80 | (c & 63));
        } else {
            result += static_cast<char>(0xf0 | (c >> 18));
            result += static_cast<char>(0x80 | ((c >> 12) & 63));
            result += static_cast<char>(0x80 | ((c >> 6) & 63));
            result += static_cast<char>(0x80 | (c & 63));
        }
    }
    env->ReleaseStringChars(string, chars);
    return result;
}

jstring new_string(JNIEnv *env, const char *text, size_t length) {
    std::vector<jchar> chars;
    for (size_t i = 0; i < length;) {
        uint32_t c = static_cast<unsigned char>(text[i++]);
        unsigned continuation = c < 0x80 ? 0 : c < 0xe0 ? 1 : c < 0xf0 ? 2 : 3;
        if (continuation) c &= (1U << (6 - continuation)) - 1;
        while (continuation-- && i < length) c = (c << 6) | (text[i++] & 63);
        if (c > 0xffff) {
            chars.push_back(static_cast<jchar>(0xd800 + ((c - 0x10000) >> 10)));
            chars.push_back(static_cast<jchar>(0xdc00 + ((c - 0x10000) & 1023)));
        } else chars.push_back(static_cast<jchar>(c));
    }
    return env->NewString(chars.data(), static_cast<jsize>(chars.size()));
}

void clear_exception(Runtime *rt) {
    JS_FreeValue(rt->context, JS_GetException(rt->context));
}

JSValue from_string(Runtime *rt, jstring value) {
    const jchar *chars = rt->env->GetStringChars(value, nullptr);
    if (!chars) return JS_EXCEPTION;
    JSValue result = JS_NewStringUTF16(rt->context, reinterpret_cast<const uint16_t *>(chars),
                                     rt->env->GetStringLength(value));
    rt->env->ReleaseStringChars(value, chars);
    return result;
}

JSAtom property_atom(Runtime *rt, jstring value) {
    JSValue string = from_string(rt, value);
    if (JS_IsException(string)) return JS_ATOM_NULL;
    JSAtom atom = JS_ValueToAtom(rt->context, string);
    JS_FreeValue(rt->context, string);
    return atom;
}

jstring to_string(Runtime *rt, JSValueConst value) {
    size_t length;
    const uint16_t *chars = JS_ToCStringLenUTF16(rt->context, &length, value);
    if (!chars) return nullptr;
    jstring result = rt->env->NewString(reinterpret_cast<const jchar *>(chars),
                                      static_cast<jsize>(length));
    JS_FreeCStringUTF16(rt->context, chars);
    return result;
}

std::string js_error(Runtime *rt, JSValueConst error) {
    size_t length = 0;
    const char *message = JS_ToCStringLen(rt->context, &length, error);
    std::string result = message ? std::string(message, length) : "JavaScript exception";
    if (message) JS_FreeCString(rt->context, message);
    else clear_exception(rt);
    if (JS_IsObject(error)) {
        JSValue stack = JS_GetPropertyStr(rt->context, error, "stack");
        if (!JS_IsUndefined(stack) && !JS_IsException(stack)) {
            const char *text = JS_ToCStringLen(rt->context, &length, stack);
            if (text) {
                result += "\n";
                result.append(text, length);
                JS_FreeCString(rt->context, text);
            } else clear_exception(rt);
        }
        if (JS_IsException(stack)) clear_exception(rt);
        JS_FreeValue(rt->context, stack);
    }
    return result;
}

struct ScriptError {
    std::string message;
    bool uncatchable = false;
};

ScriptError take_error(Runtime *rt) {
    JSValue error = JS_GetException(rt->context);
    bool uncatchable = JS_IsUncatchableError(error);
    ScriptError result{js_error(rt, error), uncatchable};
    JS_FreeValue(rt->context, error);
    return result;
}

void clear_rejections(Runtime *rt) {
    for (const auto &entry : rt->rejections) JS_FreeValue(rt->context, entry.second);
    rt->rejections.clear();
}

void track_rejection(JSContext *ctx, JSValueConst promise, JSValueConst, bool handled, void *opaque) {
    auto rt = static_cast<Runtime *>(opaque);
    void *identity = JS_VALUE_GET_PTR(promise);
    auto found = rt->rejections.find(identity);
    if (handled) {
        if (found != rt->rejections.end()) {
            JS_FreeValue(ctx, found->second);
            rt->rejections.erase(found);
        }
    } else if (found == rt->rejections.end() && !rt->stopping.load(std::memory_order_relaxed)) {
        rt->rejections.emplace(identity, JS_DupValue(ctx, promise));
    }
}

std::string rejection_text(Runtime *rt, JSValueConst reason) {
    if (!JS_IsObject(reason)) return js_error(rt, reason);
    if (!JS_IsError(reason)) return "Unhandled Promise rejection (object)";
    // Native Error own data properties avoid invoking user accessors or toString.
    std::string name = "Error", message, stack;
    const char *properties[] = {"name", "message", "stack"};
    std::string *values[] = {&name, &message, &stack};
    for (size_t i = 0; i < 3; ++i) {
        JSAtom atom = JS_NewAtom(rt->context, properties[i]);
        JSPropertyDescriptor descriptor{};
        int found = atom ? JS_GetOwnProperty(rt->context, &descriptor, reason, atom) : -1;
        JS_FreeAtom(rt->context, atom);
        if (found > 0) {
            if (!(descriptor.flags & JS_PROP_GETSET) && JS_IsString(descriptor.value)) {
                size_t length;
                const char *text = JS_ToCStringLen(rt->context, &length, descriptor.value);
                if (text) {
                    values[i]->assign(text, length);
                    JS_FreeCString(rt->context, text);
                }
            }
            JS_FreeValue(rt->context, descriptor.value);
            JS_FreeValue(rt->context, descriptor.getter);
            JS_FreeValue(rt->context, descriptor.setter);
        }
        if (JS_HasException(rt->context)) clear_exception(rt);
    }
    if (!message.empty()) name += ": " + message;
    if (stack.empty()) {
        // The cached native getter only duplicates Error's internal stack value.
        JSValue trace = JS_Call(rt->context, rt->error_stack_getter, reason, 0, nullptr);
        if (JS_IsString(trace)) stack = js_error(rt, trace);
        JS_FreeValue(rt->context, trace);
        if (JS_HasException(rt->context)) clear_exception(rt);
    }
    if (!stack.empty()) name += "\n" + stack;
    return name;
}

void notify_rejections(Runtime *rt) {
    auto rejected = std::move(rt->rejections);
    rt->rejections.clear();
    for (const auto &entry : rejected) {
        if (!rt->stopping.load(std::memory_order_relaxed) && !rt->env->ExceptionCheck()) {
            JSValue reason = JS_PromiseResult(rt->context, entry.second);
            std::string text = rejection_text(rt, reason);
            JS_FreeValue(rt->context, reason);
            jstring message = new_string(rt->env, text.data(), text.size());
            if (message) {
                rt->env->CallVoidMethod(rt->owner, rt->rejection_method, message);
                rt->env->DeleteLocalRef(message);
            }
            if (rt->env->ExceptionCheck()) {
                rt->env->ExceptionDescribe();
                rt->env->ExceptionClear();
            }
        }
        JS_FreeValue(rt->context, entry.second);
    }
}

JSValue java_error(Runtime *rt) {
    JNIEnv *env = rt->env;
    jthrowable error = env->ExceptionOccurred();
    env->ExceptionClear();
    bool uncatchable = env->CallBooleanMethod(rt->owner, rt->uncatchable_method, error);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(error);
        return JS_ThrowInternalError(rt->context, "Cannot inspect Java exception");
    }
    jclass type = env->GetObjectClass(error);
    jmethodID method = env->GetMethodID(type, "toString", "()Ljava/lang/String;");
    auto text = static_cast<jstring>(env->CallObjectMethod(error, method));
    std::string message = env->ExceptionCheck() ? "Java host exception" : utf8(env, text);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (text) env->DeleteLocalRef(text);
    env->DeleteLocalRef(type);
    env->DeleteLocalRef(error);
    JSValue result = JS_NewError(rt->context);
    if (JS_IsException(result)) return result;
    JSValue string = JS_NewStringLen(rt->context, message.data(), message.size());
    if (JS_IsException(string) || JS_SetPropertyStr(rt->context, result, "message", string) < 0) {
        JS_FreeValue(rt->context, result);
        return JS_EXCEPTION;
    }
    if (uncatchable) JS_SetUncatchableError(rt->context, result);
    return JS_Throw(rt->context, result);
}

void release_reference(Runtime *rt, jlong id);

void release_callback(Runtime *rt, jlong id) {
    auto found = rt->callbacks.find(id);
    if (found == rt->callbacks.end()) return;
    jlong origin = found->second.origin;
    if (origin && --rt->callback_origins.at(origin) == 0) rt->callback_origins.erase(origin);
    JS_FreeValue(rt->context, found->second.function);
    rt->callbacks.erase(found);
}

struct Conversion {
    Runtime *rt;
    Conversion *previous;
    std::vector<jlong> callbacks, references;
    bool committed = false;

    explicit Conversion(Runtime *runtime) : rt(runtime), previous(runtime->conversion) {
        rt->conversion = this;
    }

    ~Conversion() {
        rt->conversion = previous;
        if (committed) return;
        for (jlong id : callbacks) release_callback(rt, id);
        for (jlong id : references) if (rt->references.count(id)) release_reference(rt, id);
    }
};

jobject to_java(Runtime *rt, JSValueConst value, int depth = 0);
JSValue from_java(Runtime *rt, jobject value, int depth = 0);

jobject to_reference(Runtime *rt, JSValueConst value, bool buffers = false) {
    JSContext *ctx = rt->context;
    if (!JS_IsObject(value) || (!buffers && JS_IsArrayBuffer(value)))
        return to_java(rt, value);
    void *identity = JS_VALUE_GET_PTR(value);
    auto found = rt->identities.find(identity);
    jlong id = found == rt->identities.end() ? rt->next_reference++ : found->second;
    if (found == rt->identities.end()) {
        rt->identities.emplace(identity, id);
        rt->references.emplace(id, Reference{JS_DupValue(ctx, value), 0});
    }
    auto &reference = rt->references.at(id);
    ++reference.count;
    const char *kind = JS_IsFunction(ctx, value) ? "function" : JS_IsArray(value) ? "array" : "object";
    jstring type = rt->env->NewStringUTF(kind);
    jobject object = type ? rt->env->NewObject(rt->reference_class, rt->reference_constructor, rt->owner, id, type) : nullptr;
    if (type) rt->env->DeleteLocalRef(type);
    if (!object && --reference.count == 0) {
        JS_FreeValue(ctx, reference.value);
        rt->references.erase(id);
        rt->identities.erase(identity);
    }
    if (object && rt->conversion) rt->conversion->references.push_back(id);
    return object;
}

jlong reference_id(Runtime *rt, jobject object) {
    if (!object || !rt->env->IsInstanceOf(object, rt->reference_class)) {
        JS_ThrowTypeError(rt->context, "Expected a JavaScript Reference");
        return 0;
    }
    jobject owner = rt->env->GetObjectField(object, rt->reference_runtime);
    bool same = rt->env->IsSameObject(owner, rt->owner);
    rt->env->DeleteLocalRef(owner);
    if (!same) {
        JS_ThrowTypeError(rt->context, "Reference belongs to another runtime");
        return 0;
    }
    jlong id = rt->env->GetLongField(object, rt->reference_id);
    if (!rt->references.count(id)) {
        JS_ThrowTypeError(rt->context, "Reference is closed");
        return 0;
    }
    return id;
}

void release_reference(Runtime *rt, jlong id) {
    auto &reference = rt->references.at(id);
    if (--reference.count == 0) {
        rt->identities.erase(JS_VALUE_GET_PTR(reference.value));
        JS_FreeValue(rt->context, reference.value);
        rt->references.erase(id);
    }
}

jobject to_java(Runtime *rt, JSValueConst value, int depth) {
    JNIEnv *env = rt->env;
    JSContext *ctx = rt->context;
    if (depth > 64) {
        JS_ThrowRangeError(ctx, "Java conversion exceeds 64 levels");
        return nullptr;
    }
    if (JS_IsNull(value) || JS_IsUndefined(value)) return nullptr;
    if (JS_IsString(value)) return to_string(rt, value);
    if (JS_IsBool(value)) return env->CallStaticObjectMethod(rt->boolean_class, rt->boolean_value,
                                                            static_cast<jboolean>(JS_ToBool(ctx, value)));
    if (JS_IsBigInt(value)) {
        jstring string = to_string(rt, value);
        if (!string) return nullptr;
        jobject result = env->NewObject(rt->bigint_class, rt->bigint_constructor, string);
        env->DeleteLocalRef(string);
        return result;
    }
    if (JS_IsNumber(value)) {
        if (JS_VALUE_GET_NORM_TAG(value) == JS_TAG_INT)
            return env->CallStaticObjectMethod(rt->integer_class, rt->integer_value, JS_VALUE_GET_INT(value));
        double number;
        if (JS_ToFloat64(ctx, &number, value)) return nullptr;
        return env->CallStaticObjectMethod(rt->double_class, rt->double_value, number);
    }
    if (JS_IsFunction(ctx, value)) {
        jlong id = rt->next_callback++;
        rt->callbacks.emplace(id, Callback{JS_DupValue(ctx, value), rt->active});
        if (rt->active) ++rt->callback_origins[rt->active];
        jobject result = env->NewObject(rt->callback_class, rt->callback_constructor, rt->owner, id);
        if (!result) release_callback(rt, id);
        if (result && rt->conversion) rt->conversion->callbacks.push_back(id);
        return result;
    }
    if (JS_IsArrayBuffer(value) || JS_GetTypedArrayType(value) >= 0) {
        size_t size = 0, offset = 0, bytes = 0;
        JSValue buffer = JS_UNDEFINED;
        if (JS_GetTypedArrayType(value) >= 0) {
            buffer = JS_GetTypedArrayBuffer(ctx, value, &offset, &size, &bytes);
            if (JS_IsException(buffer)) return nullptr;
        }
        size_t buffer_size;
        uint8_t *data = JS_GetArrayBuffer(ctx, &buffer_size,
                                         JS_IsUndefined(buffer) ? value : buffer);
        if (JS_IsUndefined(buffer)) size = buffer_size;
        if (JS_HasException(ctx) || (!data && size > 0) || size > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
            JS_FreeValue(ctx, buffer);
            JS_ThrowRangeError(ctx, "ArrayBuffer is detached or exceeds Java array size");
            return nullptr;
        }
        jbyteArray result = env->NewByteArray(static_cast<jsize>(size));
        if (result && size) env->SetByteArrayRegion(result, 0, static_cast<jsize>(size),
                                                   reinterpret_cast<const jbyte *>(data + offset));
        JS_FreeValue(ctx, buffer);
        return result;
    }
    if (JS_IsArray(value)) {
        JSValue size = JS_GetPropertyStr(ctx, value, "length");
        uint32_t length;
        int error = JS_ToUint32(ctx, &length, size);
        JS_FreeValue(ctx, size);
        if (error) return nullptr;
        if (length > static_cast<uint32_t>(std::numeric_limits<jsize>::max())) {
            JS_ThrowRangeError(ctx, "Array exceeds Java array size");
            return nullptr;
        }
        jobjectArray result = env->NewObjectArray(static_cast<jsize>(length), rt->object_class, nullptr);
        if (!result) return nullptr;
        for (uint32_t i = 0; i < length; ++i) {
            JSValue item = JS_GetPropertyUint32(ctx, value, i);
            if (JS_IsException(item)) { env->DeleteLocalRef(result); return nullptr; }
            jobject object = to_java(rt, item, depth + 1);
            JS_FreeValue(ctx, item);
            if (env->ExceptionCheck() || JS_HasException(ctx)) {
                if (object) env->DeleteLocalRef(object);
                env->DeleteLocalRef(result);
                return nullptr;
            }
            env->SetObjectArrayElement(result, i, object);
            if (object) env->DeleteLocalRef(object);
        }
        return result;
    }
    if (JS_IsObject(value)) {
        JSValue json = JS_JSONStringify(ctx, value, JS_UNDEFINED, JS_UNDEFINED);
        if (JS_IsException(json) || JS_IsUndefined(json)) return nullptr;
        jstring text = to_string(rt, json);
        JS_FreeValue(ctx, json);
        if (!text) return nullptr;
        jobject result = env->NewObject(rt->json_class, rt->json_constructor, text);
        env->DeleteLocalRef(text);
        return result;
    }
    JS_ThrowTypeError(ctx, "Unsupported JavaScript result type");
    return nullptr;
}

JSValue parse_json(Runtime *rt, jstring string) {
    std::string text = utf8(rt->env, string);
    if (rt->env->ExceptionCheck()) return java_error(rt);
    return JS_ParseJSON(rt->context, text.data(), text.size(), "<java-json>");
}

JSValue signed_byte_array(Runtime *rt, jbyteArray bytes) {
    JNIEnv *env = rt->env;
    JSContext *ctx = rt->context;
    jsize size = env->GetArrayLength(bytes);
    jbyte *data = size ? env->GetByteArrayElements(bytes, nullptr) : nullptr;
    if (size && !data) return env->ExceptionCheck() ? java_error(rt) : JS_ThrowOutOfMemory(ctx);
    JSValue result = JS_NewArray(ctx);
    if (!JS_IsException(result)) {
        for (jsize i = 0; i < size; ++i) {
            if (JS_DefinePropertyValueUint32(ctx, result, i, JS_NewInt32(ctx, data[i]), JS_PROP_C_W_E) < 0) {
                JS_FreeValue(ctx, result);
                result = JS_EXCEPTION;
                break;
            }
        }
    }
    if (data) env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
    return result;
}

JSValue from_java(Runtime *rt, jobject value, int depth) {
    JNIEnv *env = rt->env;
    JSContext *ctx = rt->context;
    if (depth > 64) return JS_ThrowRangeError(ctx, "Java conversion exceeds 64 levels");
    if (!value) return JS_NULL;
    if (env->IsInstanceOf(value, rt->string_class)) return from_string(rt, static_cast<jstring>(value));
    if (env->IsInstanceOf(value, rt->boolean_class)) {
        jboolean boolean = env->CallBooleanMethod(value, rt->boolean_get);
        return env->ExceptionCheck() ? java_error(rt) : JS_NewBool(ctx, boolean);
    }
    if (env->IsInstanceOf(value, rt->bigint_class)) {
        auto text = static_cast<jstring>(env->CallObjectMethod(value, rt->bigint_string));
        if (env->ExceptionCheck()) return java_error(rt);
        std::string literal = utf8(env, text) + "n";
        env->DeleteLocalRef(text);
        return JS_Eval(ctx, literal.data(), literal.size(), "<java-bigint>", JS_EVAL_TYPE_GLOBAL);
    }
    if (env->IsInstanceOf(value, rt->number_class)) {
        if (env->IsInstanceOf(value, rt->integer_class) || env->IsInstanceOf(value, rt->short_class)
            || env->IsInstanceOf(value, rt->byte_class)) {
            jlong number = env->CallLongMethod(value, rt->number_get);
            return env->ExceptionCheck() ? java_error(rt) : JS_NewInt32(ctx, static_cast<int32_t>(number));
        }
        if (env->IsInstanceOf(value, rt->long_class)) {
            jlong number = env->CallLongMethod(value, rt->number_get);
            if (env->ExceptionCheck()) return java_error(rt);
            constexpr jlong safe = 9007199254740991LL;
            return number < -safe || number > safe ? JS_NewBigInt64(ctx, number) : JS_NewInt64(ctx, number);
        }
        jdouble number = env->CallDoubleMethod(value, rt->number_double);
        return env->ExceptionCheck() ? java_error(rt) : JS_NewFloat64(ctx, number);
    }
    if (env->IsInstanceOf(value, rt->bytes_class)) {
        auto array = static_cast<jbyteArray>(value);
        jsize size = env->GetArrayLength(array);
        JSValue result = JS_NewArrayBufferCopy(ctx, nullptr, size);
        if (JS_IsException(result)) return result;
        size_t length;
        uint8_t *data = JS_GetArrayBuffer(ctx, &length, result);
        if (size) env->GetByteArrayRegion(array, 0, size, reinterpret_cast<jbyte *>(data));
        if (env->ExceptionCheck()) {
            JS_FreeValue(ctx, result);
            return java_error(rt);
        }
        return result;
    }
    if (env->IsInstanceOf(value, rt->direct_buffer_class)) {
        jobject buffer = env->GetObjectField(value, rt->direct_buffer_data);
        if (env->ExceptionCheck()) return JS_EXCEPTION;
        jlong size = env->GetDirectBufferCapacity(buffer);
        auto data = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
        if (size < 0 || (!data && size)) {
            env->DeleteLocalRef(buffer);
            return JS_ThrowTypeError(ctx, "Direct buffer is unavailable");
        }
        jobject retained = new_global_ref(env, buffer);
        env->DeleteLocalRef(buffer);
        if (!retained) return JS_EXCEPTION;
        auto lease = new (std::nothrow) DirectBufferLease{rt, retained};
        if (!lease) {
            delete_global_ref(env, retained);
            return JS_ThrowOutOfMemory(ctx);
        }
        static uint8_t empty;
        JSValue result = JS_NewArrayBuffer(ctx, size ? data : &empty, static_cast<size_t>(size),
                                          0, release_direct_buffer, lease, false);
        if (JS_IsException(result)) release_direct_buffer(rt->runtime, lease, data, 0);
        return result;
    }
    if (env->IsInstanceOf(value, rt->array_class)) {
        auto array = static_cast<jobjectArray>(value);
        JSValue result = JS_NewArray(ctx);
        if (JS_IsException(result)) return result;
        for (jsize i = 0; i < env->GetArrayLength(array); ++i) {
            jobject object = env->GetObjectArrayElement(array, i);
            JSValue item = from_java(rt, object, depth + 1);
            if (object) env->DeleteLocalRef(object);
            if (JS_IsException(item) || JS_DefinePropertyValueUint32(ctx, result, i, item, JS_PROP_C_W_E) < 0) {
                JS_FreeValue(ctx, result);
                return JS_EXCEPTION;
            }
        }
        return result;
    }
    if (env->IsInstanceOf(value, rt->json_class)) {
        auto text = static_cast<jstring>(env->CallObjectMethod(value, rt->json_text));
        if (env->ExceptionCheck()) return java_error(rt);
        JSValue result = parse_json(rt, text);
        env->DeleteLocalRef(text);
        return result;
    }
    bool binary_reply = env->IsInstanceOf(value, rt->binary_class);
    bool text_reply = env->IsInstanceOf(value, rt->text_class);
    if (binary_reply || text_reply || env->IsInstanceOf(value, rt->byte_array_class)) {
        auto json = static_cast<jstring>(env->CallObjectMethod(value, binary_reply ? rt->binary_json : text_reply ? rt->text_json : rt->byte_array_json));
        if (env->ExceptionCheck()) return java_error(rt);
        auto property = static_cast<jstring>(env->CallObjectMethod(value, binary_reply ? rt->binary_property : text_reply ? rt->text_property : rt->byte_array_property));
        if (env->ExceptionCheck()) { env->DeleteLocalRef(json); return java_error(rt); }
        jobject payload = env->CallObjectMethod(value, binary_reply ? rt->binary_bytes : text_reply ? rt->text_value : rt->byte_array_bytes);
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(json);
            env->DeleteLocalRef(property);
            return java_error(rt);
        }
        JSValue result = parse_json(rt, json);
        if (!JS_IsException(result) && (!JS_IsObject(result) || JS_IsArray(result))) {
            JS_FreeValue(ctx, result);
            result = JS_ThrowTypeError(ctx, "%s metadata must be a JSON object", binary_reply ? "Binary" : text_reply ? "Text" : "ByteArray");
        }
        if (!JS_IsException(result)) {
            JSAtom atom = property_atom(rt, property);
            JSValue data = JS_EXCEPTION;
            if (atom != JS_ATOM_NULL) {
                data = binary_reply ? from_java(rt, payload, depth + 1)
                                  : text_reply ? from_string(rt, static_cast<jstring>(payload))
                                  : signed_byte_array(rt, static_cast<jbyteArray>(payload));
            }
            if (JS_IsException(data) || JS_DefinePropertyValue(ctx, result, atom, data, JS_PROP_C_W_E) < 0) {
                JS_FreeValue(ctx, result);
                result = JS_EXCEPTION;
            }
            JS_FreeAtom(ctx, atom);
        }
        env->DeleteLocalRef(json);
        env->DeleteLocalRef(property);
        env->DeleteLocalRef(payload);
        return result;
    }
    if (env->IsInstanceOf(value, rt->callback_class)) {
        jobject owner = env->GetObjectField(value, rt->callback_runtime);
        bool same_runtime = env->IsSameObject(owner, rt->owner);
        env->DeleteLocalRef(owner);
        if (!same_runtime) return JS_ThrowTypeError(ctx, "Callback belongs to another runtime");
        jlong id = env->GetLongField(value, rt->callback_id);
        auto entry = rt->callbacks.find(id);
        return entry == rt->callbacks.end() ? JS_ThrowTypeError(ctx, "Callback is closed")
                                           : JS_DupValue(ctx, entry->second.function);
    }
    if (env->IsInstanceOf(value, rt->reference_class)) {
        jlong id = reference_id(rt, value);
        return id ? JS_DupValue(ctx, rt->references.at(id).value) : JS_EXCEPTION;
    }
    if (env->IsInstanceOf(value, rt->transfer_class)) {
        jobject object = env->CallObjectMethod(value, rt->transfer_value);
        if (env->ExceptionCheck()) return java_error(rt);
        jlong id = reference_id(rt, object);
        env->DeleteLocalRef(object);
        if (!id) return JS_EXCEPTION;
        JSValue transferred = JS_DupValue(ctx, rt->references.at(id).value);
        release_reference(rt, id);
        return transferred;
    }
    return JS_ThrowTypeError(ctx, "Unsupported Java argument type");
}

void complete(Runtime *rt, jlong id, JSValueConst value, const ScriptError *error = nullptr, bool references = false, bool buffers = false) {
    JNIEnv *env = rt->env;
    Conversion conversion(rt);
    jobject result = nullptr;
    ScriptError conversion_error;
    if (!error) {
        result = references ? to_reference(rt, value, buffers) : to_java(rt, value);
        if (env->ExceptionCheck()) {
            java_error(rt);
            conversion_error = take_error(rt);
            error = &conversion_error;
        } else if (JS_HasException(rt->context)) {
            conversion_error = take_error(rt);
            error = &conversion_error;
        }
    }
    jstring message = nullptr;
    if (error) {
        message = new_string(env, error->message.data(), error->message.size());
    }
    if (!env->ExceptionCheck()) {
        jboolean accepted = env->CallBooleanMethod(rt->owner, rt->complete_method, id, result, message,
                                                  static_cast<jboolean>(error && error->uncatchable));
        if (!env->ExceptionCheck()) conversion.committed = accepted && !error;
    }
    if (result) env->DeleteLocalRef(result);
    if (message) env->DeleteLocalRef(message);
}

void throw_script_error(Runtime *rt) {
    ScriptError error = take_error(rt);
    jstring message = new_string(rt->env, error.message.data(), error.message.size());
    if (!message) return;
    jclass type = rt->env->FindClass("com/fongmi/quickjs/QuickJS$ScriptException");
    if (type) {
        jmethodID constructor = rt->env->GetMethodID(type, "<init>", "(Ljava/lang/String;Z)V");
        if (constructor) {
            auto exception = static_cast<jthrowable>(rt->env->NewObject(type, constructor, message,
                                                                       static_cast<jboolean>(error.uncatchable)));
            if (exception) {
                rt->env->Throw(exception);
                rt->env->DeleteLocalRef(exception);
            }
        }
        rt->env->DeleteLocalRef(type);
    }
    rt->env->DeleteLocalRef(message);
}

JSValue settled(JSContext *ctx, JSValueConst, int argc, JSValueConst *argv,
                int rejected, JSValue *data) {
    auto rt = static_cast<Runtime *>(JS_GetContextOpaque(ctx));
    int64_t id;
    if (JS_ToInt64(ctx, &id, data[0])) return JS_EXCEPTION;
    JSValueConst value = argc ? argv[0] : JS_UNDEFINED;
    if (rejected) {
        ScriptError error{js_error(rt, value), JS_IsUncatchableError(value)};
        complete(rt, id, JS_UNDEFINED, &error);
    } else complete(rt, id, value);
    return JS_UNDEFINED;
}

void result(Runtime *rt, jlong id, JSValue value) {
    JSContext *ctx = rt->context;
    if (JS_IsException(value)) {
        ScriptError error = take_error(rt);
        complete(rt, id, JS_UNDEFINED, &error);
        return;
    }
    JSPromiseStateEnum state = JS_PromiseState(ctx, value);
    if (state == JS_PROMISE_PENDING || state == JS_PROMISE_FULFILLED || state == JS_PROMISE_REJECTED) {
        JSValue data = JS_NewInt64(ctx, id);
        JSValue functions[2] = {
            JS_NewCFunctionData(ctx, settled, 1, 0, 1, &data),
            JS_NewCFunctionData(ctx, settled, 1, 1, 1, &data)
        };
        JS_FreeValue(ctx, data);
        JSValue chained = JS_IsException(functions[0]) || JS_IsException(functions[1])
                          ? JS_EXCEPTION : JS_Call(ctx, rt->promise_then, value, 2, functions);
        JS_FreeValue(ctx, functions[0]);
        JS_FreeValue(ctx, functions[1]);
        if (JS_IsException(chained)) {
            ScriptError error = take_error(rt);
            complete(rt, id, JS_UNDEFINED, &error);
        }
        JS_FreeValue(ctx, chained);
    } else complete(rt, id, value);
    JS_FreeValue(ctx, value);
}

void reference_result(Runtime *rt, jlong id, JSValue value, bool references = true, bool buffers = false) {
    if (JS_IsException(value)) {
        ScriptError error = take_error(rt);
        complete(rt, id, JS_UNDEFINED, &error);
    } else complete(rt, id, value, nullptr, references, buffers);
    JS_FreeValue(rt->context, value);
}

int interrupted(JSContext *, void *opaque) {
    auto rt = static_cast<Runtime *>(opaque);
    if (rt->stopping.load(std::memory_order_relaxed)) return 1;
    std::lock_guard<std::mutex> lock(rt->cancel_mutex);
    for (jlong call : rt->executing) if (rt->canceled.count(call)) return 1;
    return 0;
}

void retire_cancellations(Runtime *rt) {
    std::lock_guard<std::mutex> lock(rt->cancel_mutex);
    for (auto iterator = rt->canceled.begin(); iterator != rt->canceled.end();) {
        bool retained = *iterator > rt->last_call || rt->callback_origins.count(*iterator)
                        || rt->job_origins.count(*iterator);
        for (jlong call : rt->executing) retained |= call == *iterator;
        if (retained) ++iterator;
        else iterator = rt->canceled.erase(iterator);
    }
}

struct JobScope {
    jobject value;
    jlong origin;
    std::vector<jlong> owners;
};

void free_job_scope(JSRuntime *runtime, JSValueConst value) {
    auto rt = static_cast<Runtime *>(JS_GetRuntimeOpaque(runtime));
    auto scope = static_cast<JobScope *>(JS_GetOpaque(value, rt->job_scope_class));
    if (!scope) return;
    delete_global_ref(rt->env, scope->value);
    for (jlong owner : scope->owners) {
        auto found = rt->job_origins.find(owner);
        if (found != rt->job_origins.end() && --found->second == 0) rt->job_origins.erase(found);
    }
    delete scope;
}

JSValue capture_job_scope(JSContext *ctx, void *opaque) {
    auto rt = static_cast<Runtime *>(opaque);
    jobject captured = rt->env->CallObjectMethod(rt->owner, rt->capture_job_scope);
    if (rt->env->ExceptionCheck()) return java_error(rt);
    JSValue value = JS_NewObjectProtoClass(ctx, JS_NULL, rt->job_scope_class);
    if (JS_IsException(value)) {
        rt->env->DeleteLocalRef(captured);
        return value;
    }
    auto scope = new (std::nothrow) JobScope{nullptr, rt->active, {}};
    if (!scope) {
        rt->env->DeleteLocalRef(captured);
        JS_FreeValue(ctx, value);
        return JS_ThrowOutOfMemory(ctx);
    }
    scope->value = new_global_ref(rt->env, captured);
    rt->env->DeleteLocalRef(captured);
    if (!scope->value) {
        delete scope;
        JS_FreeValue(ctx, value);
        return rt->env->ExceptionCheck() ? java_error(rt) : JS_ThrowOutOfMemory(ctx);
    }
    size_t retained = 0;
    try {
        scope->owners = rt->executing;
        if (scope->origin && std::find(scope->owners.begin(), scope->owners.end(), scope->origin) == scope->owners.end())
            scope->owners.push_back(scope->origin);
        std::sort(scope->owners.begin(), scope->owners.end());
        scope->owners.erase(std::unique(scope->owners.begin(), scope->owners.end()), scope->owners.end());
        for (jlong owner : scope->owners) {
            ++rt->job_origins[owner];
            ++retained;
        }
    } catch (const std::bad_alloc &) {
        for (size_t i = 0; i < retained; ++i) {
            auto found = rt->job_origins.find(scope->owners[i]);
            if (--found->second == 0) rt->job_origins.erase(found);
        }
        delete_global_ref(rt->env, scope->value);
        delete scope;
        JS_FreeValue(ctx, value);
        return JS_ThrowOutOfMemory(ctx);
    }
    JS_SetOpaque(value, scope);
    return value;
}

void drain(Runtime *rt, jlong id) {
    JSContext *ctx;
    while (!rt->stopping.load(std::memory_order_relaxed) && JS_IsJobPending(rt->runtime)) {
        if (rt->env->ExceptionCheck()) break;
        JSValue context = JS_GetPendingJobContextValue(rt->runtime);
        auto scope = static_cast<JobScope *>(JS_GetOpaque(context, rt->job_scope_class));
        bool scoped = scope || rt->job_scopes_enabled;
        jlong previous = rt->active;
        std::vector<jlong> previous_owners;
        jobject restore = nullptr;
        if (scoped) {
            previous_owners.swap(rt->executing);
            if (scope) rt->executing.swap(scope->owners);
            rt->active = scope ? scope->origin : 0;
            restore = rt->env->CallObjectMethod(rt->owner, rt->enter_job_scope, scope ? scope->value : nullptr);
        }
        int status = rt->env->ExceptionCheck() ? -1 : JS_ExecutePendingJob(rt->runtime, &ctx);
        jlong origin = scope ? scope->origin : id;
        if (restore) {
            jthrowable failure = rt->env->ExceptionOccurred();
            if (failure) rt->env->ExceptionClear();
            rt->env->CallVoidMethod(rt->owner, rt->leave_job_scope, restore, failure);
            if (failure) rt->env->DeleteLocalRef(failure);
            rt->env->DeleteLocalRef(restore);
        }
        if (scoped) {
            if (scope) rt->executing.swap(scope->owners);
            rt->executing.swap(previous_owners);
            rt->active = previous;
        }
        if (rt->env->ExceptionCheck()) {
            JS_FreeValue(rt->context, context);
            break;
        }
        if (status < 0) {
            ScriptError error = take_error(rt);
            // Completing a Java future may synchronously reenter this runtime.
            const auto &owners = scope ? scope->owners : rt->executing;
            size_t count = owners.size();
            complete(rt, origin, JS_UNDEFINED, &error);
            if (error.uncatchable) {
                for (size_t i = 0; i < count; ++i) {
                    jlong owner = owners[i];
                    if (owner && owner != origin) complete(rt, owner, JS_UNDEFINED, &error);
                }
            }
            JS_FreeValue(rt->context, context);
            break;
        }
        JS_FreeValue(rt->context, context);
    }
}

struct Entry {
    Runtime *rt;
    jlong id;
    jlong previous;
    size_t execution_count;
    Entry(Runtime *value, JNIEnv *env, jlong call, jlong origin = 0) : rt(value), id(call), previous(value->active) {
        rt->env = env;
        rt->active = origin ? origin : call;
        execution_count = rt->executing.size();
        rt->executing.push_back(call);
        if (origin && origin != call) rt->executing.push_back(origin);
        if (call > rt->last_call) rt->last_call = call;
        ++rt->depth;
    }
    ~Entry() {
        if (rt->depth == 1) {
            if (!rt->stopping.load(std::memory_order_relaxed)) {
                do {
                    drain(rt, id);
                    if (rt->env->ExceptionCheck()) break;
                    JS_ClearKeptObjects(rt->runtime);
                } while (!rt->stopping.load(std::memory_order_relaxed)
                         && !rt->env->ExceptionCheck() && JS_IsJobPending(rt->runtime));
            }
            if (rt->stopping.load(std::memory_order_relaxed)) {
                JS_SetJobContextCapture(rt->runtime, nullptr, nullptr);
                clear_rejections(rt);
                do {
                    JS_DiscardPendingJobs(rt->runtime);
                    JS_ClearKeptObjects(rt->runtime);
                    JS_RunGC(rt->runtime);
                } while (JS_IsJobPending(rt->runtime));
            } else if (!rt->rejections.empty() && !JS_IsJobPending(rt->runtime) && !rt->env->ExceptionCheck()) notify_rejections(rt);
        }
        --rt->depth;
        rt->executing.resize(execution_count);
        rt->active = previous;
        retire_cancellations(rt);
    }
};

struct Receiver {
    Runtime *rt;
    JSValue previous;
    ~Receiver() {
        JS_FreeValue(rt->context, rt->host_receiver);
        rt->host_receiver = previous;
    }
};

JSValue host_call(JSContext *ctx, JSValueConst receiver, int argc, JSValueConst *argv, int references,
                  JSValue *data) {
    auto rt = static_cast<Runtime *>(JS_GetContextOpaque(ctx));
    Receiver frame{rt, rt->host_receiver};
    rt->host_receiver = JS_DupValue(ctx, receiver);
    int32_t id;
    if (JS_ToInt32(ctx, &id, data[0])) return JS_EXCEPTION;
    JNIEnv *env = rt->env;
    Conversion conversion(rt);
    if (env->PushLocalFrame(argc + 16) < 0)
        return env->ExceptionCheck() ? java_error(rt) : JS_ThrowOutOfMemory(ctx);
    jobjectArray args = env->NewObjectArray(argc, rt->object_class, nullptr);
    bool failed = !args;
    for (int i = 0; !failed && i < argc; ++i) {
        jobject value = references ? to_reference(rt, argv[i]) : to_java(rt, argv[i]);
        failed = env->ExceptionCheck() || JS_HasException(ctx);
        if (!failed) env->SetObjectArrayElement(args, i, value);
        if (value) env->DeleteLocalRef(value);
    }
    JSValue result = JS_EXCEPTION;
    if (!failed) {
        conversion.committed = true;
        jobject response = env->CallObjectMethod(rt->owner, rt->dispatch_method, id, args);
        if (env->ExceptionCheck()) result = java_error(rt);
        else result = from_java(rt, response);
    } else if (env->ExceptionCheck()) result = java_error(rt);
    env->PopLocalFrame(nullptr);
    return result;
}

JSValue async_host_call(JSContext *ctx, JSValueConst receiver, int argc, JSValueConst *argv, int, JSValue *data) {
    auto rt = static_cast<Runtime *>(JS_GetContextOpaque(ctx));
    Receiver frame{rt, rt->host_receiver};
    rt->host_receiver = JS_DupValue(ctx, receiver);
    int32_t id;
    if (JS_ToInt32(ctx, &id, data[0])) return JS_EXCEPTION;
    JSValue capabilities[2];
    JSValue promise = JS_NewPromiseCapability(ctx, capabilities);
    if (JS_IsException(promise)) return promise;
    JNIEnv *env = rt->env;
    Conversion conversion(rt);
    bool local_frame = env->PushLocalFrame(argc + 16) >= 0;
    bool failed = !local_frame;
    if (local_frame) {
        jobjectArray args = env->NewObjectArray(argc, rt->object_class, nullptr);
        failed = !args;
        for (int i = 0; !failed && i < argc; ++i) {
            jobject value = to_java(rt, argv[i]);
            failed = env->ExceptionCheck() || JS_HasException(ctx);
            if (!failed) env->SetObjectArrayElement(args, i, value);
            if (value) env->DeleteLocalRef(value);
        }
        if (!failed) {
            jobject resolve = to_java(rt, capabilities[0]);
            jobject reject = env->ExceptionCheck() || JS_HasException(ctx) ? nullptr : to_java(rt, capabilities[1]);
            failed = !resolve || !reject || env->ExceptionCheck() || JS_HasException(ctx);
            if (!failed) {
                env->CallVoidMethod(rt->owner, rt->dispatch_async_method, id, args, resolve, reject);
                failed = env->ExceptionCheck();
                conversion.committed = !failed;
            }
        }
    }
    if (failed) {
        if (env->ExceptionCheck()) java_error(rt);
        else if (!local_frame) JS_ThrowOutOfMemory(ctx);
        JSValue error = JS_GetException(ctx);
        if (JS_IsUncatchableError(error)) {
            JS_FreeValue(ctx, promise);
            promise = JS_Throw(ctx, error);
        } else {
            JSValue rejected = JS_Call(ctx, capabilities[1], JS_UNDEFINED, 1, &error);
            JS_FreeValue(ctx, error);
            if (JS_IsException(rejected)) {
                JS_FreeValue(ctx, promise);
                promise = JS_EXCEPTION;
            }
            JS_FreeValue(ctx, rejected);
        }
    }
    if (local_frame) env->PopLocalFrame(nullptr);
    JS_FreeValue(ctx, capabilities[0]);
    JS_FreeValue(ctx, capabilities[1]);
    return promise;
}

char *normalize(JSContext *ctx, const char *base, const char *name, void *opaque) {
    auto rt = static_cast<Runtime *>(opaque);
    JNIEnv *env = rt->env;
    jstring parent = new_string(env, base, strlen(base));
    jstring module = env->ExceptionCheck() ? nullptr : new_string(env, name, strlen(name));
    auto string = env->ExceptionCheck() ? nullptr
        : static_cast<jstring>(env->CallObjectMethod(rt->owner, rt->normalize_method, parent, module));
    char *result = nullptr;
    if (env->ExceptionCheck()) java_error(rt);
    else if (string) {
        std::string text = utf8(env, string);
        if (env->ExceptionCheck()) java_error(rt);
        else if (text.find('\0') != std::string::npos) JS_ThrowTypeError(ctx, "Module names cannot contain NUL");
        else {
            result = static_cast<char *>(js_malloc(ctx, text.size() + 1));
            if (result) memcpy(result, text.c_str(), text.size() + 1);
        }
    } else JS_ThrowReferenceError(ctx, "Module name is null");
    env->DeleteLocalRef(parent);
    env->DeleteLocalRef(module);
    if (string) env->DeleteLocalRef(string);
    return result;
}

constexpr char bytecode_header[] = "FongMi QuickJS-NG 0.17.0 v4\n";

void fail_module(Runtime *rt);

bool populate_module_meta(Runtime *rt, JSValueConst compiled) {
    if (JS_VALUE_GET_TAG(compiled) != JS_TAG_MODULE) return true;
    JSContext *ctx = rt->context;
    auto module = static_cast<JSModuleDef *>(JS_VALUE_GET_PTR(compiled));
    JSAtom atom = JS_GetModuleName(ctx, module);
    const char *name = JS_AtomToCString(ctx, atom);
    JS_FreeAtom(ctx, atom);
    if (!name) return false;
    jstring input = new_string(rt->env, name, strlen(name));
    JS_FreeCString(ctx, name);
    auto url = rt->env->ExceptionCheck() ? nullptr
        : static_cast<jstring>(rt->env->CallObjectMethod(rt->owner, rt->module_url_method, input));
    if (input) rt->env->DeleteLocalRef(input);
    if (rt->env->ExceptionCheck()) {
        if (url) rt->env->DeleteLocalRef(url);
        java_error(rt);
        return false;
    }
    if (!url) return true;
    JSValue meta = JS_GetImportMeta(ctx, module);
    if (JS_IsException(meta)) {
        rt->env->DeleteLocalRef(url);
        return false;
    }
    std::string text = utf8(rt->env, url);
    rt->env->DeleteLocalRef(url);
    int status = -1;
    if (rt->env->ExceptionCheck()) java_error(rt);
    else {
        JSValue value = JS_NewStringLen(ctx, text.data(), text.size());
        if (!JS_IsException(value)) status = JS_DefinePropertyValueStr(ctx, meta, "url", value, JS_PROP_C_W_E);
    }
    JS_FreeValue(ctx, meta);
    return status >= 0;
}

bool module_meta(Runtime *rt, JSValueConst compiled) {
    if (populate_module_meta(rt, compiled)) return true;
    fail_module(rt);
    return false;
}

JSValue read_bytecode(Runtime *rt, const uint8_t *data, size_t size) {
    constexpr size_t header_size = sizeof(bytecode_header) - 1;
    if (size <= header_size || memcmp(data, bytecode_header, header_size))
        return JS_ThrowTypeError(rt->context, "Incompatible QuickJS bytecode version");
    return JS_ReadObject(rt->context, data + header_size, size - header_size, JS_READ_OBJ_BYTECODE);
}

jbyteArray write_bytecode(Runtime *rt, JSValueConst compiled) {
    size_t size;
    uint8_t *data = JS_WriteObject(rt->context, &size, compiled, JS_WRITE_OBJ_BYTECODE);
    if (!data) return nullptr;
    constexpr size_t header_size = sizeof(bytecode_header) - 1;
    jbyteArray bytes = nullptr;
    if (size > static_cast<size_t>(std::numeric_limits<jsize>::max()) - header_size) {
        JS_ThrowRangeError(rt->context, "Module bytecode exceeds Java array size");
    } else {
        bytes = rt->env->NewByteArray(static_cast<jsize>(header_size + size));
        if (bytes) rt->env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(header_size), reinterpret_cast<const jbyte *>(bytecode_header));
        if (bytes && !rt->env->ExceptionCheck()) rt->env->SetByteArrayRegion(bytes, static_cast<jsize>(header_size), static_cast<jsize>(size), reinterpret_cast<const jbyte *>(data));
    }
    js_free(rt->context, data);
    return bytes;
}

void fail_module(Runtime *rt) {
    // Module registration cannot be rolled back through the engine API.
    // Stop further JS work, including Promise continuations in this entry.
    rt->stopping.store(true, std::memory_order_relaxed);
    JSValue error = JS_GetException(rt->context);
    JS_SetUncatchableError(rt->context, error);
    JS_Throw(rt->context, JS_DupValue(rt->context, error));
    ScriptError message = take_error(rt);
    JS_Throw(rt->context, error);
    rt->env->CallVoidMethod(rt->owner, rt->module_failed_method);
    if (rt->env->ExceptionCheck()) java_error(rt);
    else if (rt->active) complete(rt, rt->active, JS_UNDEFINED, &message);
}

JSValue compile_module(Runtime *rt, jstring name, jstring source, const std::string &file, const std::string &text) {
    JNIEnv *env = rt->env;
    auto bytes = rt->module_cache
        ? static_cast<jbyteArray>(env->CallObjectMethod(rt->owner, rt->load_source_bytecode_method, name, source)) : nullptr;
    if (env->ExceptionCheck()) {
        java_error(rt);
        fail_module(rt);
        return JS_EXCEPTION;
    }
    if (bytes) {
        jsize size = env->GetArrayLength(bytes);
        jbyte *data = env->GetByteArrayElements(bytes, nullptr);
        JSValue compiled = data ? read_bytecode(rt, reinterpret_cast<uint8_t *>(data), size) : JS_EXCEPTION;
        if (data) env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
        env->DeleteLocalRef(bytes);
        if (env->ExceptionCheck()) compiled = java_error(rt);
        if (!JS_IsException(compiled) && JS_VALUE_GET_TAG(compiled) != JS_TAG_MODULE) {
            JS_FreeValue(rt->context, compiled);
            compiled = JS_ThrowTypeError(rt->context, "Module cache returned script bytecode");
        }
        if (!JS_IsException(compiled) && JS_ResolveModule(rt->context, compiled) < 0) {
            JS_FreeValue(rt->context, compiled);
            compiled = JS_EXCEPTION;
        }
        if (JS_IsException(compiled)) fail_module(rt);
        return compiled;
    }
    JSValue compiled = JS_Eval(rt->context, text.data(), text.size(), file.c_str(), JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY);
    if (JS_IsException(compiled) || !rt->module_cache) return compiled;
    bytes = write_bytecode(rt, compiled);
    if (bytes && !env->ExceptionCheck()) env->CallVoidMethod(rt->owner, rt->store_bytecode_method, name, source, bytes);
    if (bytes) env->DeleteLocalRef(bytes);
    if (!bytes || env->ExceptionCheck()) {
        JS_FreeValue(rt->context, compiled);
        if (env->ExceptionCheck()) java_error(rt);
        fail_module(rt);
        return JS_EXCEPTION;
    }
    return compiled;
}

int check_import_attributes(JSContext *ctx, void *, JSValueConst attributes) {
    if (JS_IsUndefined(attributes)) return 0;
    JSPropertyEnum *properties;
    uint32_t length;
    if (JS_GetOwnPropertyNames(ctx, &properties, &length, attributes, JS_GPN_ENUM_ONLY | JS_GPN_STRING_MASK) < 0)
        return -1;
    int result = 0;
    for (uint32_t i = 0; i < length; ++i) {
        size_t size;
        const char *key = JS_AtomToCStringLen(ctx, &size, properties[i].atom);
        if (!key) {
            result = -1;
            break;
        }
        if (size != 4 || memcmp(key, "type", 4) != 0) {
            JS_ThrowTypeError(ctx, "Unsupported import attribute: %s", key);
            result = -1;
        }
        JS_FreeCString(ctx, key);
        if (result < 0) break;
    }
    JS_FreePropertyEnum(ctx, properties, length);
    return result;
}

int import_type(JSContext *ctx, JSValueConst attributes) {
    if (JS_IsUndefined(attributes)) return 0;
    JSValue value = JS_GetPropertyStr(ctx, attributes, "type");
    if (JS_IsException(value)) return -1;
    if (JS_IsUndefined(value)) return 0;
    size_t length;
    const char *type = JS_ToCStringLen(ctx, &length, value);
    JS_FreeValue(ctx, value);
    if (!type) return -1;
    int result = length == 4 && memcmp(type, "json", 4) == 0 ? 1 : -1;
    if (result < 0) JS_ThrowTypeError(ctx, "Unsupported module type: %s", type);
    JS_FreeCString(ctx, type);
    return result;
}

int initialize_default_module(JSContext *ctx, JSModuleDef *module) {
    return JS_SetModuleExport(ctx, module, "default", JS_GetModulePrivateValue(ctx, module));
}

JSModuleDef *load(JSContext *ctx, const char *name, void *opaque, JSValueConst attributes) {
    auto rt = static_cast<Runtime *>(opaque);
    JNIEnv *env = rt->env;
    int type = import_type(ctx, attributes);
    if (type < 0) return nullptr;
    jstring module = new_string(env, name, strlen(name));
    auto bytecode = env->ExceptionCheck() || type != 0 ? nullptr
        : static_cast<jbyteArray>(env->CallObjectMethod(rt->owner, rt->load_bytecode_method, module));
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(module);
        java_error(rt);
        return nullptr;
    }
    if (bytecode) {
        jsize size = env->GetArrayLength(bytecode);
        jbyte *data = env->GetByteArrayElements(bytecode, nullptr);
        JSValue compiled = data ? read_bytecode(rt, reinterpret_cast<uint8_t *>(data), size) : JS_EXCEPTION;
        if (data) env->ReleaseByteArrayElements(bytecode, data, JNI_ABORT);
        env->DeleteLocalRef(bytecode);
        env->DeleteLocalRef(module);
        if (env->ExceptionCheck()) { java_error(rt); return nullptr; }
        if (JS_IsException(compiled)) return nullptr;
        if (JS_VALUE_GET_TAG(compiled) != JS_TAG_MODULE) {
            JS_FreeValue(ctx, compiled);
            JS_ThrowTypeError(ctx, "Module loader returned script bytecode");
            return nullptr;
        }
        auto result = static_cast<JSModuleDef *>(JS_VALUE_GET_PTR(compiled));
        bool initialized = module_meta(rt, compiled);
        JS_FreeValue(ctx, compiled);
        return initialized ? result : nullptr;
    }
    auto string = env->ExceptionCheck() ? nullptr
        : static_cast<jstring>(env->CallObjectMethod(rt->owner, rt->load_method, module));
    if (env->ExceptionCheck() || !string) {
        env->DeleteLocalRef(module);
        if (env->ExceptionCheck()) java_error(rt);
        else JS_ThrowReferenceError(ctx, "Module source is null: %s", name);
        return nullptr;
    }
    std::string source = utf8(env, string);
    if (type == 1) {
        env->DeleteLocalRef(string);
        env->DeleteLocalRef(module);
        if (env->ExceptionCheck()) {
            java_error(rt);
            return nullptr;
        }
        JSValue value = JS_ParseJSON(ctx, source.c_str(), source.size(), name);
        if (JS_IsException(value)) return nullptr;
        JSModuleDef *result = JS_NewCModule(ctx, name, initialize_default_module);
        if (!result) {
            JS_FreeValue(ctx, value);
            return nullptr;
        }
        if (JS_AddModuleExport(ctx, result, "default") < 0) {
            JS_FreeValue(ctx, value);
            fail_module(rt);
            return nullptr;
        }
        JS_SetModulePrivateValue(ctx, result, value);
        return result;
    }
    JSValue compiled = env->ExceptionCheck() ? java_error(rt) : compile_module(rt, module, string, name, source);
    env->DeleteLocalRef(string);
    env->DeleteLocalRef(module);
    if (JS_IsException(compiled)) return nullptr;
    auto result = static_cast<JSModuleDef *>(JS_VALUE_GET_PTR(compiled));
    bool initialized = module_meta(rt, compiled);
    JS_FreeValue(ctx, compiled);
    return initialized ? result : nullptr;
}

jclass cache_class(Runtime *rt, const char *name) {
    if (rt->env->ExceptionCheck()) return nullptr;
    jclass local = rt->env->FindClass(name);
    if (!local) return nullptr;
    auto global = static_cast<jclass>(new_global_ref(rt->env, local));
    rt->env->DeleteLocalRef(local);
    if (global) rt->classes.push_back(global);
    return global;
}

jmethodID method(Runtime *rt, jclass type, const char *name, const char *signature, bool is_static = false) {
    if (!type || rt->env->ExceptionCheck()) return nullptr;
    return is_static ? rt->env->GetStaticMethodID(type, name, signature)
                     : rt->env->GetMethodID(type, name, signature);
}

void destroy(Runtime *rt) {
    if (rt->runtime) JS_SetJobContextCapture(rt->runtime, nullptr, nullptr);
    if (rt->context) {
        clear_rejections(rt);
        for (auto &entry : rt->callbacks) JS_FreeValue(rt->context, entry.second.function);
        for (auto &entry : rt->references) JS_FreeValue(rt->context, entry.second.value);
        JS_FreeValue(rt->context, rt->promise_then);
        JS_FreeValue(rt->context, rt->error_stack_getter);
        JS_FreeContext(rt->context);
    }
    if (rt->runtime) JS_FreeRuntime(rt->runtime);
    for (jclass type : rt->classes) delete_global_ref(rt->env, type);
    delete_global_ref(rt->env, rt->owner);
    delete rt;
}

bool arguments(Runtime *rt, jobjectArray objects, std::vector<JSValue> &values) {
    if (!objects) return true;
    jsize length = rt->env->GetArrayLength(objects);
    values.reserve(static_cast<size_t>(length));
    for (jsize i = 0; i < length; ++i) {
        jobject object = rt->env->GetObjectArrayElement(objects, i);
        JSValue value = from_java(rt, object);
        if (object) rt->env->DeleteLocalRef(object);
        if (rt->env->ExceptionCheck()) {
            JS_FreeValue(rt->context, value);
            value = java_error(rt);
        }
        if (JS_IsException(value)) return false;
        values.push_back(value);
    }
    return true;
}

JSValue call_function(Runtime *rt, JSValueConst function, JSValueConst receiver, jobjectArray objects) {
    if (!JS_IsFunction(rt->context, function)) return JS_ThrowTypeError(rt->context, "Reference is not callable");
    std::vector<JSValue> values;
    JSValue result = arguments(rt, objects, values)
        ? JS_Call(rt->context, function, receiver, static_cast<int>(values.size()), values.data()) : JS_EXCEPTION;
    for (JSValue value : values) JS_FreeValue(rt->context, value);
    return result;
}

std::string value_string(Runtime *rt, JSValueConst value) {
    size_t size;
    const char *text = JS_ToCStringLen(rt->context, &size, value);
    if (!text) return {};
    std::string result(text, size);
    JS_FreeCString(rt->context, text);
    return result;
}

JSValue reference_operation(Runtime *rt, const std::string &operation,
                            const std::vector<JSValue> &args, jobjectArray objects) {
    JSContext *ctx = rt->context;
    auto arity = [&](size_t count) { return args.size() == count; };
    if (operation == "global" && arity(0)) return JS_GetGlobalObject(ctx);
    if (operation == "receiver" && arity(0)) return JS_DupValue(ctx, rt->host_receiver);
    if (operation == "newObject" && arity(0)) return JS_NewObject(ctx);
    if (operation == "newArray" && arity(0)) return JS_NewArray(ctx);
    if (operation == "newArray" && arity(1)) {
        if (!JS_IsArray(args[0])) return JS_ThrowTypeError(ctx, "Array values must be an array");
        return JS_DupValue(ctx, args[0]);
    }
    if ((operation == "evaluate" || operation == "evaluateReference" || operation == "compile") && arity(3)) {
        std::string source = value_string(rt, args[0]);
        if (JS_HasException(ctx)) return JS_EXCEPTION;
        std::string name = value_string(rt, args[1]);
        if (JS_HasException(ctx)) return JS_EXCEPTION;
        if (name.find('\0') != std::string::npos) return JS_ThrowTypeError(ctx, "Source names cannot contain NUL");
        int flags = JS_ToBool(ctx, args[2]) ? JS_EVAL_TYPE_MODULE : JS_EVAL_TYPE_GLOBAL;
        if (operation != "compile" && flags != JS_EVAL_TYPE_MODULE)
            return JS_Eval(ctx, source.data(), source.size(), name.c_str(), flags);
        JSValue compiled = JS_Eval(ctx, source.data(), source.size(), name.c_str(), flags | JS_EVAL_FLAG_COMPILE_ONLY);
        if (JS_IsException(compiled)) return compiled;
        if (!module_meta(rt, compiled)) {
            JS_FreeValue(ctx, compiled);
            return JS_EXCEPTION;
        }
        if (operation != "compile") return JS_EvalFunction(ctx, compiled);
        size_t size;
        uint8_t *data = JS_WriteObject(ctx, &size, compiled, JS_WRITE_OBJ_BYTECODE);
        JS_FreeValue(ctx, compiled);
        if (!data) return JS_EXCEPTION;
        constexpr size_t header_size = sizeof(bytecode_header) - 1;
        std::vector<uint8_t> bytes(header_size + size);
        memcpy(bytes.data(), bytecode_header, header_size);
        memcpy(bytes.data() + header_size, data, size);
        js_free(ctx, data);
        return JS_NewArrayBufferCopy(ctx, bytes.data(), bytes.size());
    }
    if (operation == "execute" && arity(1)) {
        size_t size;
        const uint8_t *data = JS_GetArrayBuffer(ctx, &size, args[0]);
        if (JS_HasException(ctx)) return JS_EXCEPTION;
        JSValue compiled = read_bytecode(rt, data, size);
        if (JS_IsException(compiled)) return compiled;
        int tag = JS_VALUE_GET_TAG(compiled);
        if (tag != JS_TAG_MODULE && tag != JS_TAG_FUNCTION_BYTECODE) {
            JS_FreeValue(ctx, compiled);
            return JS_ThrowTypeError(ctx, "Bytecode does not contain executable code");
        }
        if (tag == JS_TAG_MODULE && !module_meta(rt, compiled)) {
            JS_FreeValue(ctx, compiled);
            return JS_EXCEPTION;
        }
        if (tag == JS_TAG_MODULE && JS_ResolveModule(ctx, compiled) < 0) {
            JS_FreeValue(ctx, compiled);
            return JS_EXCEPTION;
        }
        return JS_EvalFunction(ctx, compiled);
    }
    if ((operation == "get" && arity(2)) || (operation == "set" && arity(3))) {
        JSAtom atom = JS_ValueToAtom(ctx, args[1]);
        if (atom == JS_ATOM_NULL) return JS_EXCEPTION;
        JSValue value = operation == "get" ? JS_GetProperty(ctx, args[0], atom)
            : JS_SetProperty(ctx, args[0], atom, JS_DupValue(ctx, args[2])) < 0 ? JS_EXCEPTION : JS_UNDEFINED;
        JS_FreeAtom(ctx, atom);
        return value;
    }
    if ((operation == "has" || operation == "hasOwn") && arity(2)) {
        if (!JS_IsObject(args[0])) return JS_ThrowTypeError(ctx, "Property target must be an object");
        JSAtom atom = JS_ValueToAtom(ctx, args[1]);
        if (atom == JS_ATOM_NULL) return JS_EXCEPTION;
        int present;
        if (operation == "has") present = JS_HasProperty(ctx, args[0], atom);
        else {
            JSPropertyDescriptor property{};
            present = JS_GetOwnProperty(ctx, &property, args[0], atom);
            if (present > 0) {
                JS_FreeValue(ctx, property.value);
                JS_FreeValue(ctx, property.getter);
                JS_FreeValue(ctx, property.setter);
            }
        }
        JS_FreeAtom(ctx, atom);
        return present < 0 ? JS_EXCEPTION : JS_NewBool(ctx, present != 0);
    }
    if (operation == "stringify" && arity(1)) return JS_JSONStringify(ctx, args[0], JS_UNDEFINED, JS_UNDEFINED);
    if (operation == "parse" && arity(1)) {
        std::string json = value_string(rt, args[0]);
        if (JS_HasException(ctx)) return JS_EXCEPTION;
        return JS_ParseJSON(ctx, json.data(), json.size(), "<java-json>");
    }
    if (operation == "keys" && arity(1)) {
        JSPropertyEnum *properties;
        uint32_t count;
        if (JS_GetOwnPropertyNames(ctx, &properties, &count, args[0], JS_GPN_STRING_MASK | JS_GPN_ENUM_ONLY) < 0)
            return JS_EXCEPTION;
        JSValue array = JS_NewArray(ctx);
        for (uint32_t i = 0; !JS_IsException(array) && i < count; ++i) {
            JSValue name = JS_AtomToString(ctx, properties[i].atom);
            if (JS_IsException(name) || JS_DefinePropertyValueUint32(ctx, array, i, name, JS_PROP_C_W_E) < 0) {
                JS_FreeValue(ctx, array);
                array = JS_EXCEPTION;
            }
        }
        JS_FreePropertyEnum(ctx, properties, count);
        return array;
    }
    if (operation == "dup" && arity(1)) return JS_DupValue(ctx, args[0]);
    if (operation == "free" && arity(1)) {
        jobject object = rt->env->GetObjectArrayElement(objects, 0);
        jlong id = reference_id(rt, object);
        rt->env->DeleteLocalRef(object);
        if (!id) return JS_EXCEPTION;
        release_reference(rt, id);
        return JS_UNDEFINED;
    }
    if (operation == "gc" && arity(0)) { JS_RunGC(rt->runtime); return JS_UNDEFINED; }
    if ((operation == "memoryLimit" || operation == "stackLimit") && arity(1)) {
        int64_t limit;
        if (JS_ToInt64(ctx, &limit, args[0])) return JS_EXCEPTION;
        if (limit < 0 || static_cast<uint64_t>(limit) > std::numeric_limits<size_t>::max())
            return JS_ThrowRangeError(ctx, "Limit is out of range");
        if (operation == "memoryLimit") JS_SetMemoryLimit(rt->runtime, static_cast<size_t>(limit));
        else JS_SetMaxStackSize(rt->runtime, static_cast<size_t>(limit));
        return JS_UNDEFINED;
    }
    if (operation == "gcThreshold" && arity(1)) {
        int64_t threshold;
        if (JS_ToInt64(ctx, &threshold, args[0])) return JS_EXCEPTION;
        if (threshold < 0 || static_cast<uint64_t>(threshold) > std::numeric_limits<size_t>::max())
            return JS_ThrowRangeError(ctx, "GC threshold is out of range");
        JS_SetGCThreshold(rt->runtime, static_cast<size_t>(threshold));
        return JS_UNDEFINED;
    }
    if (operation == "resources" && arity(0)) {
        JSValue result = JS_NewObjectProto(ctx, JS_NULL);
        if (JS_IsException(result)) return result;
#define RESOURCE_FIELD(name, value) \
        if (JS_SetPropertyStr(ctx, result, name, JS_NewInt64(ctx, static_cast<int64_t>(value))) < 0) { \
            JS_FreeValue(ctx, result); return JS_EXCEPTION; \
        }
        RESOURCE_FIELD("jniGlobalRefs", global_references.load(std::memory_order_relaxed))
        RESOURCE_FIELD("callbacks", rt->callbacks.size())
        RESOURCE_FIELD("callbackOrigins", rt->callback_origins.size())
        RESOURCE_FIELD("references", rt->references.size())
        RESOURCE_FIELD("bufferLeases", rt->buffer_leases.size())
        RESOURCE_FIELD("rejections", rt->rejections.size())
#undef RESOURCE_FIELD
        return result;
    }
    if ((operation == "memory" || operation == "memoryUsed") && arity(0)) {
        JSMemoryUsage memory;
        JS_ComputeMemoryUsage(rt->runtime, &memory);
        if (operation == "memoryUsed") return JS_NewInt64(ctx, memory.memory_used_size);
        JSValue result = JS_NewObject(ctx);
        if (JS_IsException(result)) return result;
#define MEMORY_FIELD(name) \
        if (JS_SetPropertyStr(ctx, result, #name, JS_NewInt64(ctx, memory.name)) < 0) { \
            JS_FreeValue(ctx, result); return JS_EXCEPTION; \
        }
        MEMORY_FIELD(malloc_size) MEMORY_FIELD(malloc_limit) MEMORY_FIELD(memory_used_size)
        MEMORY_FIELD(malloc_count) MEMORY_FIELD(memory_used_count) MEMORY_FIELD(atom_count)
        MEMORY_FIELD(atom_size) MEMORY_FIELD(str_count) MEMORY_FIELD(str_size)
        MEMORY_FIELD(obj_count) MEMORY_FIELD(obj_size) MEMORY_FIELD(prop_count)
        MEMORY_FIELD(prop_size) MEMORY_FIELD(shape_count) MEMORY_FIELD(shape_size)
        MEMORY_FIELD(js_func_count) MEMORY_FIELD(js_func_size) MEMORY_FIELD(js_func_code_size)
        MEMORY_FIELD(js_func_pc2line_count) MEMORY_FIELD(js_func_pc2line_size)
        MEMORY_FIELD(c_func_count) MEMORY_FIELD(array_count) MEMORY_FIELD(fast_array_count)
        MEMORY_FIELD(fast_array_elements) MEMORY_FIELD(binary_object_count) MEMORY_FIELD(binary_object_size)
#undef MEMORY_FIELD
        return result;
    }
    return JS_ThrowTypeError(ctx, "Unknown reference operation or invalid argument count: %s", operation.c_str());
}

JSValue invoke_method(Runtime *rt, JSValueConst object, jstring method, jobjectArray args) {
    JSContext *ctx = rt->context;
    JSAtom atom = property_atom(rt, method);
    JSValue function = atom == JS_ATOM_NULL || JS_IsException(object) ? JS_EXCEPTION : JS_GetProperty(ctx, object, atom);
    JS_FreeAtom(ctx, atom);
    JSValue value = JS_IsException(function) ? JS_EXCEPTION
        : JS_IsUndefined(function) ? JS_UNDEFINED : call_function(rt, function, object, args);
    JS_FreeValue(ctx, function);
    return value;
}

void bind_function(Runtime *rt, JSValueConst object, jstring name, jint id, JSCFunctionData *callback, int magic) {
    JSAtom atom = property_atom(rt, name);
    JSValue data = JS_NewInt32(rt->context, id);
    JSValue function = atom == JS_ATOM_NULL ? JS_EXCEPTION
        : JS_NewCFunctionData(rt->context, callback, 0, magic, 1, &data);
    if (JS_IsException(function)) JS_FreeValue(rt->context, function);
    else JS_SetProperty(rt->context, object, atom, function);
    JS_FreeAtom(rt->context, atom);
    JS_FreeValue(rt->context, data);
    if (!rt->env->ExceptionCheck() && JS_HasException(rt->context)) throw_script_error(rt);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL Java_com_fongmi_quickjs_QuickJS_create(JNIEnv *env, jobject owner) {
    auto rt = new Runtime{};
    rt->env = env;
    rt->owner = new_global_ref(env, owner);
    jclass type = env->GetObjectClass(owner);
    rt->dispatch_method = method(rt, type, "dispatch", "(I[Ljava/lang/Object;)Ljava/lang/Object;");
    rt->dispatch_async_method = method(rt, type, "dispatchAsync", "(I[Ljava/lang/Object;Lcom/fongmi/quickjs/QuickJS$Callback;Lcom/fongmi/quickjs/QuickJS$Callback;)V");
    rt->complete_method = method(rt, type, "complete", "(JLjava/lang/Object;Ljava/lang/String;Z)Z");
    rt->uncatchable_method = method(rt, type, "isUncatchable", "(Ljava/lang/Throwable;)Z");
    rt->capture_job_scope = method(rt, type, "captureJobScope", "()Lcom/fongmi/quickjs/QuickJS$AsyncScope;");
    rt->enter_job_scope = method(rt, type, "enterJobScope", "(Lcom/fongmi/quickjs/QuickJS$AsyncScope;)Ljava/lang/Runnable;");
    rt->leave_job_scope = method(rt, type, "leaveJobScope", "(Ljava/lang/Runnable;Ljava/lang/Throwable;)V");
    rt->rejection_method = method(rt, type, "unhandledRejection", "(Ljava/lang/String;)V");
    rt->normalize_method = method(rt, type, "normalize", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    rt->load_method = method(rt, type, "load", "(Ljava/lang/String;)Ljava/lang/String;");
    rt->load_bytecode_method = method(rt, type, "loadBytecode", "(Ljava/lang/String;)[B");
    rt->load_source_bytecode_method = method(rt, type, "loadBytecode", "(Ljava/lang/String;Ljava/lang/String;)[B");
    rt->store_bytecode_method = method(rt, type, "storeBytecode", "(Ljava/lang/String;Ljava/lang/String;[B)V");
    rt->module_failed_method = method(rt, type, "failModule", "()V");
    rt->module_url_method = method(rt, type, "moduleUrl", "(Ljava/lang/String;)Ljava/lang/String;");
    env->DeleteLocalRef(type);
    rt->boolean_class = cache_class(rt, "java/lang/Boolean");
    rt->integer_class = cache_class(rt, "java/lang/Integer");
    rt->short_class = cache_class(rt, "java/lang/Short");
    rt->byte_class = cache_class(rt, "java/lang/Byte");
    rt->long_class = cache_class(rt, "java/lang/Long");
    rt->double_class = cache_class(rt, "java/lang/Double");
    rt->number_class = cache_class(rt, "java/lang/Number");
    rt->string_class = cache_class(rt, "java/lang/String");
    rt->bytes_class = cache_class(rt, "[B");
    rt->array_class = cache_class(rt, "[Ljava/lang/Object;");
    rt->object_class = cache_class(rt, "java/lang/Object");
    rt->bigint_class = cache_class(rt, "java/math/BigInteger");
    rt->json_class = cache_class(rt, "com/fongmi/quickjs/QuickJS$Json");
    rt->binary_class = cache_class(rt, "com/fongmi/quickjs/QuickJS$Binary");
    rt->text_class = cache_class(rt, "com/fongmi/quickjs/QuickJS$Text");
    rt->byte_array_class = cache_class(rt, "com/fongmi/quickjs/QuickJS$ByteArray");
    rt->direct_buffer_class = cache_class(rt, "com/fongmi/quickjs/QuickJS$DirectBuffer");
    rt->callback_class = cache_class(rt, "com/fongmi/quickjs/QuickJS$Callback");
    rt->reference_class = cache_class(rt, "com/fongmi/quickjs/QuickJS$Reference");
    rt->transfer_class = cache_class(rt, "com/fongmi/quickjs/QuickJS$Transfer");
    rt->boolean_value = method(rt, rt->boolean_class, "valueOf", "(Z)Ljava/lang/Boolean;", true);
    rt->integer_value = method(rt, rt->integer_class, "valueOf", "(I)Ljava/lang/Integer;", true);
    rt->double_value = method(rt, rt->double_class, "valueOf", "(D)Ljava/lang/Double;", true);
    rt->bigint_constructor = method(rt, rt->bigint_class, "<init>", "(Ljava/lang/String;)V");
    rt->boolean_get = method(rt, rt->boolean_class, "booleanValue", "()Z");
    rt->number_get = method(rt, rt->number_class, "longValue", "()J");
    rt->number_double = method(rt, rt->number_class, "doubleValue", "()D");
    rt->bigint_string = method(rt, rt->bigint_class, "toString", "()Ljava/lang/String;");
    rt->json_constructor = method(rt, rt->json_class, "<init>", "(Ljava/lang/String;)V");
    rt->json_text = method(rt, rt->json_class, "text", "()Ljava/lang/String;");
    rt->binary_json = method(rt, rt->binary_class, "json", "()Ljava/lang/String;");
    rt->binary_property = method(rt, rt->binary_class, "property", "()Ljava/lang/String;");
    rt->binary_bytes = method(rt, rt->binary_class, "bytes", "()[B");
    rt->text_json = method(rt, rt->text_class, "json", "()Ljava/lang/String;");
    rt->text_property = method(rt, rt->text_class, "property", "()Ljava/lang/String;");
    rt->text_value = method(rt, rt->text_class, "text", "()Ljava/lang/String;");
    rt->byte_array_json = method(rt, rt->byte_array_class, "json", "()Ljava/lang/String;");
    rt->byte_array_property = method(rt, rt->byte_array_class, "property", "()Ljava/lang/String;");
    rt->byte_array_bytes = method(rt, rt->byte_array_class, "bytes", "()[B");
    rt->callback_constructor = method(rt, rt->callback_class, "<init>", "(Lcom/fongmi/quickjs/QuickJS;J)V");
    rt->reference_constructor = method(rt, rt->reference_class, "<init>", "(Lcom/fongmi/quickjs/QuickJS;JLjava/lang/String;)V");
    rt->transfer_value = method(rt, rt->transfer_class, "value", "()Lcom/fongmi/quickjs/QuickJS$Reference;");
    if (env->ExceptionCheck()) { destroy(rt); return 0; }
    rt->callback_id = env->GetFieldID(rt->callback_class, "id", "J");
    if (!env->ExceptionCheck()) rt->callback_runtime = env->GetFieldID(rt->callback_class, "runtime", "Lcom/fongmi/quickjs/QuickJS;");
    if (!env->ExceptionCheck()) rt->reference_id = env->GetFieldID(rt->reference_class, "id", "J");
    if (!env->ExceptionCheck()) rt->reference_runtime = env->GetFieldID(rt->reference_class, "runtime", "Lcom/fongmi/quickjs/QuickJS;");
    if (!env->ExceptionCheck()) rt->direct_buffer_data = env->GetFieldID(rt->direct_buffer_class, "buffer", "Ljava/nio/ByteBuffer;");
    if (env->ExceptionCheck()) { destroy(rt); return 0; }
    rt->runtime = JS_NewRuntime();
    rt->context = rt->runtime ? JS_NewContext(rt->runtime) : nullptr;
    if (!rt->context) {
        jclass error = env->FindClass("java/lang/OutOfMemoryError");
        env->ThrowNew(error, "Cannot create QuickJS runtime");
        env->DeleteLocalRef(error);
        destroy(rt);
        return 0;
    }
    JS_SetContextOpaque(rt->context, rt);
    JS_SetRuntimeOpaque(rt->runtime, rt);
    JS_NewClassID(rt->runtime, &rt->job_scope_class);
    JSClassDef job_scope_definition{};
    job_scope_definition.class_name = "HostJobScope";
    job_scope_definition.finalizer = free_job_scope;
    if (JS_NewClass(rt->runtime, rt->job_scope_class, &job_scope_definition) < 0) {
        JS_ThrowOutOfMemory(rt->context);
        throw_script_error(rt);
        destroy(rt);
        return 0;
    }
    JS_SetInterruptHandler(rt->runtime, interrupted, rt);
    JS_SetModuleLoaderFunc2(rt->runtime, normalize, load, check_import_attributes, rt);
    JSValue global = JS_GetGlobalObject(rt->context);
    JSValue promise = JS_GetPropertyStr(rt->context, global, "Promise");
    JSValue prototype = JS_GetPropertyStr(rt->context, promise, "prototype");
    rt->promise_then = JS_GetPropertyStr(rt->context, prototype, "then");
    JS_FreeValue(rt->context, prototype);
    JS_FreeValue(rt->context, promise);
    JSValue error = JS_GetPropertyStr(rt->context, global, "Error");
    JSValue error_prototype = JS_GetPropertyStr(rt->context, error, "prototype");
    JSAtom stack = JS_NewAtom(rt->context, "stack");
    JSPropertyDescriptor descriptor{};
    if (JS_GetOwnProperty(rt->context, &descriptor, error_prototype, stack) > 0) {
        rt->error_stack_getter = descriptor.getter;
        JS_FreeValue(rt->context, descriptor.value);
        JS_FreeValue(rt->context, descriptor.setter);
    }
    JS_FreeAtom(rt->context, stack);
    JS_FreeValue(rt->context, error_prototype);
    JS_FreeValue(rt->context, error);
    JS_FreeValue(rt->context, global);
    if (JS_HasException(rt->context)) { throw_script_error(rt); destroy(rt); return 0; }
    return reinterpret_cast<jlong>(rt);
}

extern "C" JNIEXPORT void JNICALL Java_com_fongmi_quickjs_QuickJS_rejections(
    JNIEnv *env, jobject, jlong handle, jboolean enabled) {
    Runtime *rt = get(handle);
    rt->env = env;
    JS_SetHostPromiseRejectionTracker(rt->runtime, enabled ? track_rejection : nullptr, rt);
    clear_rejections(rt);
}

extern "C" JNIEXPORT void JNICALL Java_com_fongmi_quickjs_QuickJS_jobScopes(
    JNIEnv *env, jobject, jlong handle, jboolean enabled) {
    Runtime *rt = get(handle);
    rt->env = env;
    rt->job_scopes_enabled = enabled;
    JS_SetJobContextCapture(rt->runtime, enabled ? capture_job_scope : nullptr, rt);
}

extern "C" JNIEXPORT void JNICALL Java_com_fongmi_quickjs_QuickJS_reference(
    JNIEnv *env, jobject, jlong handle, jlong call, jstring operation, jobjectArray objects) {
    Runtime *rt = get(handle);
    Entry entry(rt, env, call);
    std::string name = utf8(env, operation);
    std::vector<JSValue> values;
    JSValue value = env->ExceptionCheck() ? java_error(rt)
        : arguments(rt, objects, values) ? reference_operation(rt, name, values, objects) : JS_EXCEPTION;
    for (JSValue argument : values) JS_FreeValue(rt->context, argument);
    reference_result(rt, call, value, name != "keys" && name != "memory" && name != "resources", name == "evaluateReference");
}

extern "C" JNIEXPORT void JNICALL Java_com_fongmi_quickjs_QuickJS_callFunction(
    JNIEnv *env, jobject, jlong handle, jlong call, jobject reference, jobject receiver, jobjectArray objects) {
    Runtime *rt = get(handle);
    Entry entry(rt, env, call);
    JSValue function = from_java(rt, reference);
    JSValue target = JS_IsException(function) ? JS_EXCEPTION : receiver ? from_java(rt, receiver) : JS_UNDEFINED;
    JSValue value = env->ExceptionCheck() ? java_error(rt)
        : JS_IsException(function) || JS_IsException(target) ? JS_EXCEPTION : call_function(rt, function, target, objects);
    JS_FreeValue(rt->context, function);
    JS_FreeValue(rt->context, target);
    reference_result(rt, call, value);
}

extern "C" JNIEXPORT jlong JNICALL Java_com_fongmi_quickjs_QuickJS_acquireBuffer(
    JNIEnv *env, jobject, jlong handle, jobject reference) {
    Runtime *rt = get(handle);
    rt->env = env;
    jlong reference_id_value = reference_id(rt, reference);
    if (!reference_id_value) { throw_script_error(rt); return 0; }
    size_t offset = 0, length = 0, capacity = 0;
    JSValue backing = JS_GetBufferRange(rt->context, rt->references.at(reference_id_value).value, &offset, &length);
    if (JS_IsException(backing)) { throw_script_error(rt); return 0; }
    uint8_t *data = JS_GetArrayBuffer(rt->context, &capacity, backing);
    if (JS_HasException(rt->context) || JS_IsImmutableArrayBuffer(backing) < 0 ||
        length > static_cast<size_t>(std::numeric_limits<jint>::max()) || offset > capacity || length > capacity - offset) {
        if (!JS_HasException(rt->context)) JS_ThrowTypeError(rt->context, "Unsupported buffer range");
        JS_FreeValue(rt->context, backing);
        throw_script_error(rt);
        return 0;
    }
    jlong id = rt->next_buffer_lease++;
    try {
        rt->buffer_leases.emplace(id, BufferRange{nullptr, nullptr, length});
    } catch (const std::bad_alloc &) {
        JS_FreeValue(rt->context, backing);
        JS_ThrowOutOfMemory(rt->context);
        throw_script_error(rt);
        return 0;
    }
    SharedAllocation *allocation = nullptr;
    if (length) {
        allocation = new (std::nothrow) SharedAllocation{nullptr, nullptr, data, capacity};
        if (!allocation) JS_ThrowOutOfMemory(rt->context);
        JSReallocArrayBufferDataFunc *hook = share_allocation;
        void *opaque = allocation;
        if (!allocation || JS_ExchangeArrayBufferAllocator(rt->context, backing, &hook, &opaque) < 0) {
            delete allocation;
            rt->buffer_leases.erase(id);
            JS_FreeValue(rt->context, backing);
            throw_script_error(rt);
            return 0;
        }
        if (hook == share_allocation) {
            JSReallocArrayBufferDataFunc *restore = hook;
            void *restore_opaque = opaque;
            JS_ExchangeArrayBufferAllocator(rt->context, backing, &restore, &restore_opaque);
            delete allocation;
            allocation = static_cast<SharedAllocation *>(opaque);
        } else {
            allocation->realloc = hook;
            allocation->opaque = opaque;
        }
        ++allocation->pins;
    }
    static uint8_t empty;
    rt->buffer_leases.at(id) = BufferRange{allocation, length ? data + offset : &empty, length};
    JS_FreeValue(rt->context, backing);
    return id;
}

extern "C" JNIEXPORT jobject JNICALL Java_com_fongmi_quickjs_QuickJS_bufferView(
    JNIEnv *env, jobject, jlong handle, jlong id) {
    Runtime *rt = get(handle);
    rt->env = env;
    const auto &range = rt->buffer_leases.at(id);
    return env->NewDirectByteBuffer(range.data, static_cast<jlong>(range.length));
}

extern "C" JNIEXPORT void JNICALL Java_com_fongmi_quickjs_QuickJS_releaseBuffer(
    JNIEnv *env, jobject, jlong handle, jlong id) {
    Runtime *rt = get(handle);
    rt->env = env;
    auto found = rt->buffer_leases.find(id);
    if (found == rt->buffer_leases.end()) return;
    SharedAllocation *allocation = found->second.allocation;
    rt->buffer_leases.erase(found);
    if (allocation && --allocation->pins == 0 && !allocation->js_owner)
        free_allocation(rt->runtime, allocation);
}

extern "C" JNIEXPORT void JNICALL Java_com_fongmi_quickjs_QuickJS_moduleCache(
    JNIEnv *env, jobject, jlong handle, jboolean enabled) {
    Runtime *rt = get(handle);
    rt->env = env;
    rt->module_cache = enabled;
}

extern "C" JNIEXPORT void JNICALL Java_com_fongmi_quickjs_QuickJS_codeGeneration(
    JNIEnv *env, jobject, jlong handle, jboolean allowed) {
    Runtime *rt = get(handle);
    rt->env = env;
    JS_SetCanCompileStrings(rt->context, allowed);
}

extern "C" JNIEXPORT void JNICALL Java_com_fongmi_quickjs_QuickJS_evaluate(
    JNIEnv *env, jobject, jlong handle, jlong call, jstring source, jstring name, jboolean module) {
    Runtime *rt = get(handle);
    Entry entry(rt, env, call);
    std::string text = utf8(env, source), file = utf8(env, name);
    JSValue value;
    if (env->ExceptionCheck()) value = java_error(rt);
    else if (file.find('\0') != std::string::npos) value = JS_ThrowTypeError(rt->context, "Source names cannot contain NUL");
    else if (module) {
        value = compile_module(rt, name, source, file, text);
        if (!JS_IsException(value)) {
            if (module_meta(rt, value)) value = JS_EvalFunction(rt->context, value);
            else { JS_FreeValue(rt->context, value); value = JS_EXCEPTION; }
        }
    } else value = JS_Eval(rt->context, text.data(), text.size(), file.c_str(),
                            module ? JS_EVAL_TYPE_MODULE : JS_EVAL_TYPE_GLOBAL);
    result(rt, call, value);
}

extern "C" JNIEXPORT void JNICALL Java_com_fongmi_quickjs_QuickJS_invoke(
    JNIEnv *env, jobject, jlong handle, jlong call, jstring object_name, jstring method, jobjectArray objects) {
    Runtime *rt = get(handle);
    Entry entry(rt, env, call);
    JSContext *ctx = rt->context;
    JSValue global = JS_GetGlobalObject(ctx);
    JSAtom atom = property_atom(rt, object_name);
    JSValue object = atom == JS_ATOM_NULL ? JS_EXCEPTION
        : env->GetStringLength(object_name) == 0 ? JS_DupValue(ctx, global) : JS_GetProperty(ctx, global, atom);
    JS_FreeAtom(ctx, atom);
    JS_FreeValue(ctx, global);
    JSValue value = invoke_method(rt, object, method, objects);
    JS_FreeValue(ctx, object);
    result(rt, call, value);
}

extern "C" JNIEXPORT void JNICALL Java_com_fongmi_quickjs_QuickJS_invokeObject(
    JNIEnv *env, jobject, jlong handle, jlong call, jobject target, jstring method, jobjectArray objects) {
    Runtime *rt = get(handle);
    Entry entry(rt, env, call);
    JSValue object = from_java(rt, target);
    JSValue value = invoke_method(rt, object, method, objects);
    JS_FreeValue(rt->context, object);
    result(rt, call, value);
}

extern "C" JNIEXPORT void JNICALL Java_com_fongmi_quickjs_QuickJS_callback(
    JNIEnv *env, jobject, jlong handle, jlong call, jlong id, jobjectArray objects, jboolean detached) {
    Runtime *rt = get(handle);
    auto found = rt->callbacks.find(id);
    Entry entry(rt, env, call, detached || found == rt->callbacks.end() ? 0 : found->second.origin);
    if (found == rt->callbacks.end()) {
        result(rt, call, JS_ThrowTypeError(rt->context, "Callback is closed"));
        return;
    }
    JSValue function = JS_DupValue(rt->context, found->second.function);
    JSValue value = call_function(rt, function, JS_UNDEFINED, objects);
    JS_FreeValue(rt->context, function);
    result(rt, call, value);
}

extern "C" JNIEXPORT void JNICALL Java_com_fongmi_quickjs_QuickJS_bind(
    JNIEnv *env, jobject, jlong handle, jstring name, jint id, jboolean references) {
    Runtime *rt = get(handle);
    Entry entry(rt, env, 0);
    JSValue object = JS_GetGlobalObject(rt->context);
    bind_function(rt, object, name, id, host_call, references);
    JS_FreeValue(rt->context, object);
}

extern "C" JNIEXPORT void JNICALL Java_com_fongmi_quickjs_QuickJS_bindAsync(
    JNIEnv *env, jobject, jlong handle, jstring name, jint id) {
    Runtime *rt = get(handle);
    Entry entry(rt, env, 0);
    JSValue object = JS_GetGlobalObject(rt->context);
    bind_function(rt, object, name, id, async_host_call, 0);
    JS_FreeValue(rt->context, object);
}

extern "C" JNIEXPORT void JNICALL Java_com_fongmi_quickjs_QuickJS_bindObject(
    JNIEnv *env, jobject, jlong handle, jobject target, jstring name, jint id, jboolean async, jboolean references) {
    Runtime *rt = get(handle);
    Entry entry(rt, env, 0);
    JSValue object = from_java(rt, target);
    if (!JS_IsException(object) && !JS_IsObject(object)) JS_ThrowTypeError(rt->context, "Binding target must be an object");
    if (!env->ExceptionCheck() && !JS_HasException(rt->context)) bind_function(rt, object, name, id, async ? async_host_call : host_call, async ? 0 : references);
    JS_FreeValue(rt->context, object);
    if (!env->ExceptionCheck() && JS_HasException(rt->context)) throw_script_error(rt);
}

extern "C" JNIEXPORT void JNICALL Java_com_fongmi_quickjs_QuickJS_registerModule(
    JNIEnv *env, jobject, jlong handle, jstring name, jobject exports) {
    Runtime *rt = get(handle);
    Entry entry(rt, env, 0);
    JSContext *ctx = rt->context;
    std::string module_name = utf8(env, name);
    if (env->ExceptionCheck()) return;
    if (module_name.empty() || module_name.find('\0') != std::string::npos) {
        JS_ThrowTypeError(ctx, "Module name must be nonempty and cannot contain NUL");
        throw_script_error(rt);
        return;
    }
    JSValue object = from_java(rt, exports);
    if (!JS_IsException(object) && !JS_IsObject(object)) JS_ThrowTypeError(ctx, "Module exports must be an object");
    JSModuleDef *module = env->ExceptionCheck() || JS_HasException(ctx) ? nullptr : JS_NewCModule(ctx, module_name.c_str(), initialize_default_module);
    if (module && JS_AddModuleExport(ctx, module, "default") == 0) JS_SetModulePrivateValue(ctx, module, JS_DupValue(ctx, object));
    JS_FreeValue(ctx, object);
    if (!env->ExceptionCheck() && JS_HasException(ctx)) throw_script_error(rt);
}

extern "C" JNIEXPORT jlongArray JNICALL Java_com_fongmi_quickjs_QuickJS_scope(
    JNIEnv *env, jobject, jlong handle) {
    Runtime *rt = get(handle);
    bool canceled = rt->stopping.load(std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(rt->cancel_mutex);
        for (jlong call : rt->executing) canceled |= rt->canceled.count(call) != 0;
    }
    if (canceled) {
        jclass error = env->FindClass("java/util/concurrent/CancellationException");
        if (error) { env->ThrowNew(error, "JavaScript call canceled"); env->DeleteLocalRef(error); }
        return nullptr;
    }
    std::vector<jlong> calls{rt->active};
    calls.insert(calls.end(), rt->executing.begin(), rt->executing.end());
    jlongArray result = env->NewLongArray(static_cast<jsize>(calls.size()));
    if (result) env->SetLongArrayRegion(result, 0, static_cast<jsize>(calls.size()), calls.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL Java_com_fongmi_quickjs_QuickJS_settle(
    JNIEnv *env, jobject, jlong handle, jlong call, jlong id, jobject object, jlongArray owners, jboolean rejected) {
    Runtime *rt = get(handle);
    auto found = rt->callbacks.find(id);
    Entry entry(rt, env, call, found == rt->callbacks.end() ? 0 : found->second.origin);
    if (found == rt->callbacks.end()) {
        result(rt, call, JS_ThrowTypeError(rt->context, "Async capability is closed"));
        return;
    }
    jsize size = env->GetArrayLength(owners);
    size_t start = rt->executing.size();
    rt->executing.resize(start + size);
    env->GetLongArrayRegion(owners, 0, size, rt->executing.data() + start);
    JSValue function = JS_DupValue(rt->context, found->second.function);
    JSValue value = env->ExceptionCheck() ? java_error(rt) : from_java(rt, object);
    if (rejected && !JS_IsException(value)) {
        JSValue error = JS_NewError(rt->context);
        if (JS_IsException(error)) {
            JS_FreeValue(rt->context, value);
            value = error;
        } else if (JS_SetPropertyStr(rt->context, error, "message", value) < 0) {
            JS_FreeValue(rt->context, error);
            value = JS_EXCEPTION;
        } else value = error;
    }
    JSValue response = JS_IsException(value) ? JS_EXCEPTION : JS_Call(rt->context, function, JS_UNDEFINED, 1, &value);
    JS_FreeValue(rt->context, value);
    JS_FreeValue(rt->context, function);
    result(rt, call, response);
}

extern "C" JNIEXPORT void JNICALL Java_com_fongmi_quickjs_QuickJS_release(
    JNIEnv *env, jobject, jlong handle, jlong id) {
    Runtime *rt = get(handle);
    rt->env = env;
    release_callback(rt, id);
    retire_cancellations(rt);
}

extern "C" JNIEXPORT void JNICALL Java_com_fongmi_quickjs_QuickJS_limits(
    JNIEnv *env, jobject, jlong handle, jlong memory, jlong stack) {
    Runtime *rt = get(handle);
    if (memory < 0 || stack < 0 || static_cast<uint64_t>(memory) > std::numeric_limits<size_t>::max()
        || static_cast<uint64_t>(stack) > std::numeric_limits<size_t>::max()) {
        jclass error = env->FindClass("java/lang/IllegalArgumentException");
        if (error) { env->ThrowNew(error, "Limits exceed native address space"); env->DeleteLocalRef(error); }
        return;
    }
    if (memory > 0) JS_SetMemoryLimit(rt->runtime, static_cast<size_t>(memory));
    if (stack > 0) JS_SetMaxStackSize(rt->runtime, static_cast<size_t>(stack));
}

extern "C" JNIEXPORT void JNICALL Java_com_fongmi_quickjs_QuickJS_interrupt(
    JNIEnv *, jobject, jlong handle, jlong call) {
    Runtime *rt = get(handle);
    if (call == 0) rt->stopping.store(true, std::memory_order_relaxed);
    else {
        std::lock_guard<std::mutex> lock(rt->cancel_mutex);
        rt->canceled.insert(call);
    }
}

extern "C" JNIEXPORT void JNICALL Java_com_fongmi_quickjs_QuickJS_destroy(
    JNIEnv *env, jobject, jlong handle) {
    Runtime *rt = get(handle);
    rt->env = env;
    destroy(rt);
}
