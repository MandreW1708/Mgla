// JNI-обёртка над ByeDPI (jni/byedpi): локальный SOCKS5-прокси с дезинхронизацией DPI
// для трафика Telegram. Стратегии — обычные аргументы ciadpi, совместимые с ByeByeDPI.
//
// ByeDPI хранит состояние в глобальной `params` и разбирает аргументы через getopt,
// поэтому одновременно работает один экземпляр, а перед каждым запуском `params`
// восстанавливается из снимка начального состояния, а getopt сбрасывается (optind = 0).

#include <jni.h>

#include <arpa/inet.h>
#include <errno.h>
#include <getopt.h>
#include <netinet/in.h>
#include <pthread.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "byedpi/params.h"
#include "byedpi/proxy.h"

int parse_args(int argc, char **argv);
int init(void);
void clear_params(char *line, char **argv);

#define ERR_ARGS (-1)
#define ERR_INIT (-2)
#define ERR_BIND (-3)
#define ERR_THREAD (-4)

// Аргумент -D/--daemon сделал бы fork() процесса приложения.
int __wrap_daemon(int nochdir, int noclose) {
    (void) nochdir;
    (void) noclose;
    errno = EPERM;
    return -1;
}

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static struct params g_initial_params;
static bool g_snapshot_taken;

static pthread_t g_thread;
static bool g_thread_valid;
static volatile int g_loop_alive;
// dup() слушающего сокета: event loop сам закрывает свой fd при выходе, а shutdown()
// по чужому (переиспользованному) номеру дескриптора недопустим.
static int g_ctl_fd = -1;

// getopt сохраняет указатели на строки argv (например, --fake-sni), поэтому они живут до остановки.
static int g_argc;
static char **g_argv;

static void free_args(void) {
    if (g_argv) {
        for (int i = 0; i < g_argc; i++) {
            free(g_argv[i]);
        }
        free(g_argv);
    }
    g_argv = NULL;
    g_argc = 0;
}

static void *loop_thread(void *arg) {
    start_event_loop((int) (intptr_t) arg);
    __atomic_store_n(&g_loop_alive, 0, __ATOMIC_SEQ_CST);
    return NULL;
}

static void stop_locked(void) {
    if (!g_thread_valid) {
        return;
    }
    if (g_ctl_fd >= 0) {
        shutdown(g_ctl_fd, SHUT_RDWR);
    }
    pthread_join(g_thread, NULL);
    g_thread_valid = false;
    if (g_ctl_fd >= 0) {
        close(g_ctl_fd);
        g_ctl_fd = -1;
    }
    clear_params(NULL, NULL);
    free_args();
}

static bool copy_args(JNIEnv *env, jobjectArray args) {
    int count = args ? (*env)->GetArrayLength(env, args) : 0;
    g_argv = calloc(count + 2, sizeof(char *));
    if (!g_argv) {
        return false;
    }
    g_argv[g_argc++] = strdup("ciadpi");
    for (int i = 0; i < count; i++) {
        jstring arg = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        if (!arg) {
            return false;
        }
        const char *str = (*env)->GetStringUTFChars(env, arg, NULL);
        if (!str) {
            (*env)->DeleteLocalRef(env, arg);
            return false;
        }
        g_argv[g_argc++] = strdup(str);
        (*env)->ReleaseStringUTFChars(env, arg, str);
        (*env)->DeleteLocalRef(env, arg);
        if (!g_argv[g_argc - 1]) {
            return false;
        }
    }
    return g_argv[0] != NULL;
}

static int bind_loopback(int port) {
    union sockaddr_u addr;
    memset(&addr, 0, sizeof(addr));
    addr.in.sin_family = AF_INET;
    addr.in.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.in.sin_port = htons((uint16_t) port);
    return listen_socket(&addr);
}

JNIEXPORT jint JNICALL
Java_org_telegram_utils_dpi_MglaDpiNative_nativeStart(JNIEnv *env, jclass clazz, jobjectArray args, jint port) {
    (void) clazz;
    pthread_mutex_lock(&g_lock);
    stop_locked();

    if (!g_snapshot_taken) {
        g_initial_params = params;
        g_snapshot_taken = true;
    } else {
        params = g_initial_params;
    }
    optind = 0;
    signal(SIGPIPE, SIG_IGN);

    int result;
    if (!copy_args(env, args)) {
        free_args();
        result = ERR_ARGS;
        goto done;
    }
    if (parse_args(g_argc, g_argv) != 0) {
        clear_params(NULL, NULL);
        free_args();
        result = ERR_ARGS;
        goto done;
    }
    if (init() < 0) {
        clear_params(NULL, NULL);
        free_args();
        result = ERR_INIT;
        goto done;
    }

    int fd = port > 0 ? bind_loopback(port) : -1;
    if (fd < 0) {
        fd = bind_loopback(0);
    }
    if (fd < 0) {
        clear_params(NULL, NULL);
        free_args();
        result = ERR_BIND;
        goto done;
    }
    struct sockaddr_in bound;
    socklen_t boundLen = sizeof(bound);
    getsockname(fd, (struct sockaddr *) &bound, &boundLen);
    int boundPort = ntohs(bound.sin_port);

    g_ctl_fd = dup(fd);
    __atomic_store_n(&g_loop_alive, 1, __ATOMIC_SEQ_CST);
    if (g_ctl_fd < 0 || pthread_create(&g_thread, NULL, loop_thread, (void *) (intptr_t) fd) != 0) {
        __atomic_store_n(&g_loop_alive, 0, __ATOMIC_SEQ_CST);
        close(fd);
        if (g_ctl_fd >= 0) {
            close(g_ctl_fd);
            g_ctl_fd = -1;
        }
        clear_params(NULL, NULL);
        free_args();
        result = ERR_THREAD;
        goto done;
    }
    g_thread_valid = true;
    result = boundPort;

done:
    pthread_mutex_unlock(&g_lock);
    return result;
}

JNIEXPORT void JNICALL
Java_org_telegram_utils_dpi_MglaDpiNative_nativeStop(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    pthread_mutex_lock(&g_lock);
    stop_locked();
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT jboolean JNICALL
Java_org_telegram_utils_dpi_MglaDpiNative_nativeIsRunning(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    pthread_mutex_lock(&g_lock);
    bool running = g_thread_valid && __atomic_load_n(&g_loop_alive, __ATOMIC_SEQ_CST);
    pthread_mutex_unlock(&g_lock);
    return running ? JNI_TRUE : JNI_FALSE;
}
