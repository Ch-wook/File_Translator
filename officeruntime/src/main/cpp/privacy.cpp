#include <jni.h>
#include <dlfcn.h>
#include <cstdlib>
#include <android/log.h>
#include <mutex>

static void discardOfficeLog(const __android_log_message*) { }

extern "C" JNIEXPORT jboolean JNICALL
Java_org_libreoffice_androidlib_OfficePrivacy_quiet(JNIEnv*, jclass) {
    static std::once_flag environment;
    std::call_once(environment, [] { setenv("SAL_LOG", "-WARN-INFO", 1); });
    // Android 11+ liblog exposes a process-local minimum priority. Filtering at
    // this boundary also covers upstream worker threads that reset their own
    // logger levels. No device-global logging property is changed.
    void* handle = dlopen("liblog.so", RTLD_NOW);
    if (!handle) return JNI_FALSE;
    using Setter = int (*)(int);
    using Getter = int (*)();
    using SetLogger = void (*)(__android_logger_function);
    auto set = reinterpret_cast<Setter>(dlsym(handle, "__android_log_set_minimum_priority"));
    auto get = reinterpret_cast<Getter>(dlsym(handle, "__android_log_get_minimum_priority"));
    auto setLogger = reinterpret_cast<SetLogger>(dlsym(handle, "__android_log_set_logger"));
    bool ok = false;
    if (set && get && setLogger) {
        constexpr int silent = 8; // ANDROID_LOG_SILENT
        setLogger(discardOfficeLog);
        set(silent); ok = get() == silent;
    }
    dlclose(handle);
    return ok ? JNI_TRUE : JNI_FALSE;
}
