#include <jni.h>
#include <sys/stat.h>
#include <climits>
#include <unistd.h>
#include <string>

#include <android/log.h>
#include "breakpad/src/client/linux/handler/exception_handler.h"
#include "breakpad/src/client/linux/handler/minidump_descriptor.h"


thread_local static char buf[PATH_MAX + 1];

static bool mgla_check_utf8(const char *data, size_t len) {
    const char *data_end = data + len;
    do {
        unsigned int a = (unsigned char) (*data++);
        if ((a & 0x80) == 0) {
            if (data == data_end + 1) {
                return true;
            }
            continue;
        }
        if ((a & 0x40) == 0) {
            return false;
        }
        unsigned int b = (unsigned char) (*data++);
        if ((b & 0xc0) != 0x80) {
            return false;
        }
        if ((a & 0x20) == 0) {
            if ((a & 0x1e) == 0) {
                return false;
            }
            continue;
        }
        unsigned int c = (unsigned char) (*data++);
        if ((c & 0xc0) != 0x80) {
            return false;
        }
        if ((a & 0x10) == 0) {
            continue;
        }
        unsigned int d = (unsigned char) (*data++);
        if ((d & 0xc0) != 0x80) {
            return false;
        }
    } while (true);
}

extern "C" JNIEXPORT jstring Java_org_telegram_messenger_Utilities_readlink(JNIEnv *env, jclass clazz, jstring path) {
    const char *fileName = env->GetStringUTFChars(path, NULL);
    ssize_t result = readlink(fileName, buf, PATH_MAX);
    jstring value = 0;
    if (result != -1) {
        buf[result] = '\0';
        value = env->NewStringUTF(mgla_check_utf8(buf, (size_t) result) ? buf : "");
    }
    env->ReleaseStringUTFChars(path, fileName);
    return value;
}

extern "C" JNIEXPORT jstring Java_org_telegram_messenger_Utilities_readlinkFd(JNIEnv *env, jclass clazz, int fd) {
    std::string path = "/proc/self/fd/";
    path += fd;
    ssize_t result = readlink(path.c_str(), buf, PATH_MAX);
    jstring value = 0;
    if (result != -1) {
        buf[result] = '\0';
        value = env->NewStringUTF(mgla_check_utf8(buf, (size_t) result) ? buf : "");
    }
    return value;
}

bool dumpCallback(const google_breakpad::MinidumpDescriptor &descriptor,
                  void *context,
                  bool succeeded) {

    __android_log_print(ANDROID_LOG_DEBUG, "tmessages",
                        "Wrote breakpad minidump at %s succeeded=%d\n", descriptor.path(),
                        succeeded);
    return false;
}

extern "C"
JNIEXPORT void JNICALL
Java_org_telegram_messenger_Utilities_setupNativeCrashesListener(JNIEnv *env, jclass clazz,
                                                                 jstring path) {
    const char *dumpPath = (char *) env->GetStringUTFChars(path, NULL);
    google_breakpad::MinidumpDescriptor descriptor(dumpPath);
    new google_breakpad::ExceptionHandler(descriptor, NULL, dumpCallback, NULL, true, -1);
    env->ReleaseStringUTFChars(path, dumpPath);
}
