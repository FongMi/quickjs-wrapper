#include <jni.h>
#include <string>
#include <android/log.h>
#include "quickjs_wrapper.h"

static void j_println(QuickJSWrapper *wrapper, JSValue &value) {
    const char *result = wrapper->stringify(value);
    __android_log_print(ANDROID_LOG_DEBUG, "quickjs-android-native", "println=%s", result);
}

static void throwJavaException(JNIEnv *env, const char *exceptionClass, const char *fmt, ...) {
    char msg[512];
    va_list args;
    va_start (args, fmt);
    vsnprintf(msg, sizeof(msg), fmt, args);
    va_end (args);
    env->ThrowNew(env->FindClass(exceptionClass), msg);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_whl_quickjs_wrapper_QuickJSContext_destroyContext(JNIEnv *env, jobject thiz,
                                                           jlong context) {
    delete reinterpret_cast<QuickJSWrapper*>(context);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_whl_quickjs_wrapper_QuickJSContext_evaluate(JNIEnv *env, jobject thiz, jlong context, jstring script,
                                                     jstring file_name) {
    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);
    if (!wrapper) {
        throwJavaException(env, "java/lang/NullPointerException",
                           "Null QuickJS wrapper - did you destroy your QuickJS?");
        return -1;
    }

    const char *c_script = env->GetStringUTFChars(script, JNI_FALSE);
    const char *c_file_name = env->GetStringUTFChars(file_name, JNI_FALSE);

    JSValue result = wrapper->evaluate(c_script, c_file_name);

    j_println(wrapper, result);

    env->ReleaseStringUTFChars(script, c_script);
    env->ReleaseStringUTFChars(file_name, c_file_name);

    return reinterpret_cast<jlong>(JS_VALUE_GET_PTR(result));
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_whl_quickjs_wrapper_QuickJSContext_getGlobalObject(JNIEnv *env, jobject thiz,
                                                            jlong context) {
    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);
    JSValue value = wrapper->getGlobalObject();
    return reinterpret_cast<jlong>(JS_VALUE_GET_PTR(value));
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_whl_quickjs_wrapper_JSValue_getProperty(JNIEnv *env, jobject thiz, jlong context, jlong value,
                                                 jstring name) {
    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);

    const char *propsName = env->GetStringUTFChars(name, JNI_FALSE);

    JSValue jsObject = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(value));
    const char *result = wrapper->stringify(jsObject);
    __android_log_print(ANDROID_LOG_DEBUG, "quickjs-android-wrapper", "get props result=%s", result);

    JSValue propsValue = wrapper->getProperty(jsObject, propsName);
    const char *r_result = wrapper->stringify(propsValue);
    __android_log_print(ANDROID_LOG_DEBUG, "quickjs-android-wrapper", "get props r_value=%s", r_result);

    return reinterpret_cast<jlong>(JS_VALUE_GET_PTR(propsValue));
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_whl_quickjs_wrapper_QuickJSContext_call(JNIEnv *env, jobject thiz, jlong context,
                                                 jlong func, jlong this_obj, jint arg_count,
                                                 jlong arg_value) {
    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);

    // todo delete
    JSValue argv = JS_NewString(wrapper->context, "args");

    JSValue jsObj = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(this_obj));
    JSValue jsFunc = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(func));
    JSValue funcRet = wrapper->call(jsFunc, jsObj, 1, &argv);
    const char *r_result = wrapper->stringify(funcRet);
    __android_log_print(ANDROID_LOG_DEBUG, "quickjs-android-wrapper", "get props func_value=%s", r_result);

    return reinterpret_cast<jlong>(JS_VALUE_GET_PTR(funcRet));
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_whl_quickjs_wrapper_JSValue_stringify(JNIEnv *env, jobject thiz, jlong context,
                                               jlong value) {
    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);
    JSValue jsObj = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(value));
    const char *stringify = wrapper->stringify(jsObj);
    return env->NewStringUTF(stringify);
}extern "C"
JNIEXPORT jboolean JNICALL
Java_com_whl_quickjs_wrapper_JSValue_isArray(JNIEnv *env, jobject thiz, jlong context, jlong value) {
    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);
    JSValue jsObj = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(value));
    return JS_IsArray(wrapper->context, jsObj) != 0;
}extern "C"
JNIEXPORT jint JNICALL
Java_com_whl_quickjs_wrapper_JSValue_getLength(JNIEnv *env, jobject thiz, jlong context,
                                               jlong value) {
    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);
    JSValue jsObj = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(value));
    JSValue length = wrapper->getProperty(jsObj, "length");
    return JS_VALUE_GET_INT(length);
}extern "C"
JNIEXPORT jlong JNICALL
Java_com_whl_quickjs_wrapper_JSValue_get(JNIEnv *env, jobject thiz, jlong context, jlong value,
                                         jint index) {
    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);
    JSValue jsObj = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(value));
    JSValue child = JS_GetPropertyUint32(wrapper->context, jsObj, index);
    const char *childStr = wrapper->stringify(child);
    __android_log_print(ANDROID_LOG_DEBUG, "quickjs-android-wrapper", "get index=%s", childStr);

    return reinterpret_cast<jlong>(JS_VALUE_GET_PTR(child));
}extern "C"
JNIEXPORT jlong JNICALL
Java_com_whl_quickjs_wrapper_QuickJSContext_createContext(JNIEnv *env, jobject thiz) {
    auto *wrapper = new(std::nothrow) QuickJSWrapper();
    if (!wrapper || !wrapper->context || !wrapper->runtime) {
        delete wrapper;
        wrapper = nullptr;
    }

    return reinterpret_cast<jlong>(wrapper);
}