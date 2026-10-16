#include <jni.h>

#include <cstdio>
#include <cstring>

#ifdef __ANDROID__
#include <android/log.h>
#endif

extern "C" JNIEXPORT void JNICALL Java_com_fongmi_quickjs_QuickJS_writeConsole(
    JNIEnv *env, jclass, jint priority, jbyteArray message) {
    jsize length = env->GetArrayLength(message);
    jbyte *bytes = env->GetByteArrayElements(message, nullptr);
    if (!bytes) return;
    const char *text = reinterpret_cast<const char *>(bytes);
#ifdef __ANDROID__
    int level = priority == 1 ? ANDROID_LOG_INFO : priority == 2 ? ANDROID_LOG_WARN
        : priority == 3 ? ANDROID_LOG_ERROR : ANDROID_LOG_DEBUG;
    jsize start = 0;
    do {
        char chunk[4001];
        size_t size = 0;
        while (start < length) {
            unsigned char first = static_cast<unsigned char>(text[start]);
            size_t width = first < 0x80 ? 1 : first < 0xe0 ? 2 : first < 0xf0 ? 3 : 4;
            size_t count = first == 0 ? 2 : width;
            if (size + count > sizeof(chunk) - 1) break;
            if (first == 0) {
                chunk[size++] = '\\';
                chunk[size++] = '0';
            } else {
                std::memcpy(chunk + size, text + start, width);
                size += width;
            }
            start += static_cast<jsize>(width);
        }
        chunk[size] = '\0';
        __android_log_write(level, "quickjs", chunk);
    } while (start < length);
#else
    FILE *stream = priority >= 2 ? stderr : stdout;
    std::fwrite(text, 1, static_cast<size_t>(length), stream);
    std::fputc('\n', stream);
    std::fflush(stream);
#endif
    env->ReleaseByteArrayElements(message, bytes, JNI_ABORT);
}
