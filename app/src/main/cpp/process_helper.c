#include <jni.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <signal.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <stdint.h>
#include <time.h>
#include <android/log.h>

#define TAG "ProcessHelper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

JNIEXPORT jint JNICALL
Java_top_yukonga_mishka_service_ProcessHelper_nativeCreateHttpListener(
        JNIEnv *env, jclass clazz) {
    // 随机数只在父进程的 JNI 调用中读取，不能移入 fork 到 exec 之间。
    int random_fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (random_fd < 0) return -1;

    unsigned char random_address[3];
    size_t offset = 0;
    while (offset < sizeof(random_address)) {
        ssize_t count = read(random_fd, random_address + offset, sizeof(random_address) - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) {
            close(random_fd);
            return -1;
        }
        offset += (size_t) count;
    }
    close(random_fd);

    // 不设置 CLOEXEC：子进程通过 --vpn-http-fd 接管这个已监听 socket。
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;

    struct sockaddr_in address = {0};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(0x7f000000u
            | ((1u + (unsigned int) random_address[0] % 199u) << 16)
            | ((1u + (unsigned int) random_address[1] % 199u) << 8)
            | (1u + (unsigned int) random_address[2] % 199u));
    address.sin_port = htons(0);
    if (bind(fd, (const struct sockaddr *) &address, sizeof(address)) < 0
            || listen(fd, SOMAXCONN) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int http_listener_address(int fd, struct sockaddr_in *address) {
    socklen_t length = sizeof(*address);
    return getsockname(fd, (struct sockaddr *) address, &length) == 0
            && length == sizeof(*address) && address->sin_family == AF_INET;
}

JNIEXPORT jint JNICALL
Java_top_yukonga_mishka_service_ProcessHelper_nativeHttpListenerPort(
        JNIEnv *env, jclass clazz, jint fd) {
    struct sockaddr_in address = {0};
    if (!http_listener_address(fd, &address)) return -1;
    return (jint) ntohs(address.sin_port);
}

JNIEXPORT jstring JNICALL
Java_top_yukonga_mishka_service_ProcessHelper_nativeHttpListenerAddress(
        JNIEnv *env, jclass clazz, jint fd) {
    struct sockaddr_in address = {0};
    if (!http_listener_address(fd, &address)) return NULL;
    char numeric_address[INET_ADDRSTRLEN];
    if (inet_ntop(AF_INET, &address.sin_addr, numeric_address, sizeof(numeric_address)) == NULL) {
        return NULL;
    }
    return (*env)->NewStringUTF(env, numeric_address);
}

/**
 * fork + exec 启动子进程，不关闭继承的 fd。
 * Android 的 ProcessBuilder 会在 fork 后关闭所有非标准 fd，
 * 这个函数绕过该限制，让子进程能继承 VPN 的 TUN fd。
 *
 * 返回子进程 PID，失败返回 -1。
 */
JNIEXPORT jint JNICALL
Java_top_yukonga_mishka_service_ProcessHelper_nativeForkExec(
        JNIEnv *env, jclass clazz,
        jstring jBinary, jobjectArray jArgs, jstring jWorkDir, jstring jLogFile) {

    const char *binary = (*env)->GetStringUTFChars(env, jBinary, NULL);
    const char *workDir = (*env)->GetStringUTFChars(env, jWorkDir, NULL);
    const char *logFile = jLogFile ? (*env)->GetStringUTFChars(env, jLogFile, NULL) : NULL;
    char **argv = NULL;
    int result = -1;
    int argc = 0;

    if (binary == NULL || workDir == NULL || (jLogFile != NULL && logFile == NULL)) {
        LOGE("GetStringUTFChars returned NULL");
        goto cleanup;
    }

    argc = (*env)->GetArrayLength(env, jArgs);
    // argv: [binary, args..., NULL]
    argv = (char **) calloc(argc + 2, sizeof(char *));
    if (argv == NULL) {
        LOGE("calloc for argv failed");
        goto cleanup;
    }
    argv[0] = strdup(binary);
    if (argv[0] == NULL) {
        LOGE("strdup binary failed");
        goto cleanup;
    }
    for (int i = 0; i < argc; i++) {
        jstring jArg = (jstring) (*env)->GetObjectArrayElement(env, jArgs, i);
        if (jArg == NULL) {
            LOGE("null arg at %d", i);
            goto cleanup;
        }
        const char *arg = (*env)->GetStringUTFChars(env, jArg, NULL);
        if (arg != NULL) {
            argv[i + 1] = strdup(arg);
            (*env)->ReleaseStringUTFChars(env, jArg, arg);
        }
        // 局部引用要显式释放：JNI 只保证 512 个 slot，argv 随新增 CLI flag 增长会突然溢出
        (*env)->DeleteLocalRef(env, jArg);
        if (argv[i + 1] == NULL) {
            LOGE("strdup arg %d failed", i);
            goto cleanup;
        }
    }

    LOGI("fork+exec: %s, workDir=%s, logFile=%s", binary, workDir, logFile ? logFile : "(null)");

    pid_t pid = fork();

    if (pid == 0) {
        // 子进程：脱离会话组，不关闭任何 fd，直接 exec。
        // fork 与 exec 之间只能调 async-signal-safe 函数——__android_log_print 要取锁，
        // 在这里调用可能死锁，**不要**往下面任何分支加日志
        setsid();

        if (chdir(workDir) != 0) {
            _exit(126);
        }

        if (logFile) {
            int logFd = open(logFile, O_WRONLY | O_CREAT | O_TRUNC, 0644);
            if (logFd >= 0) {
                dup2(logFd, STDOUT_FILENO);
                dup2(logFd, STDERR_FILENO);
                close(logFd);
            }
        } else {
            dup2(STDERR_FILENO, STDOUT_FILENO);
        }

        execv(binary, argv);
        _exit(127);
    }

    if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
    } else {
        LOGI("child pid=%d", pid);
        result = pid;
    }

    cleanup:
    if (argv != NULL) {
        for (int i = 0; argv[i] != NULL; i++) {
            free(argv[i]);
        }
        free(argv);
    }
    if (binary != NULL) (*env)->ReleaseStringUTFChars(env, jBinary, binary);
    if (workDir != NULL) (*env)->ReleaseStringUTFChars(env, jWorkDir, workDir);
    if (logFile != NULL) (*env)->ReleaseStringUTFChars(env, jLogFile, logFile);

    return result;
}

enum {
    STOP_NOT_CHILD = -1,
    STOP_TIMEOUT = -2,
    STOP_PENDING = -3,
    STOP_ERROR_BASE = -1000,
};

static int stop_error(int error) {
    return STOP_ERROR_BASE - error;
}

static int observe_child(pid_t pid, siginfo_t *info) {
    int result;
    do {
        memset(info, 0, sizeof(*info));
        result = waitid(P_PID, (id_t) pid, info, WEXITED | WNOHANG | WNOWAIT);
    } while (result < 0 && errno == EINTR);
    return result;
}

// 判活保留僵尸，防止本组件先收割、随后 stop 仍拿旧 PID 发信号。
JNIEXPORT jboolean JNICALL
Java_top_yukonga_mishka_service_ProcessHelper_nativeIsAlive(
        JNIEnv *env, jclass clazz, jint pid) {
    if (pid <= 0) return JNI_FALSE;
    siginfo_t info;
    return observe_child((pid_t) pid, &info) == 0 && info.si_pid == 0
            ? JNI_TRUE : JNI_FALSE;
}

static int reap_or_observe_child(pid_t pid) {
    siginfo_t info;
    if (observe_child(pid, &info) < 0) {
        return errno == ECHILD ? STOP_NOT_CHILD : stop_error(errno);
    }
    if (info.si_pid == 0) return STOP_PENDING;

    int status;
    pid_t result;
    do {
        result = waitpid(pid, &status, WNOHANG);
    } while (result < 0 && errno == EINTR);
    if (result < 0) return errno == ECHILD ? STOP_NOT_CHILD : stop_error(errno);
    if (result == pid) {
        if (WIFEXITED(status)) return WEXITSTATUS(status);
        if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    }
    return stop_error(EAGAIN);
}

static int signal_owned_child(pid_t pid, int signal_number) {
    for (;;) {
        int state = reap_or_observe_child(pid);
        if (state != STOP_PENDING) return state;
        // libcore 等外部 reaper 不受本组件锁约束；没有 pidfd，检查到 kill 之间仍有 TOCTOU。
        if (kill(pid, signal_number) == 0) return STOP_PENDING;
        int error = errno;
        if (error == EINTR) continue;
        if (error == ESRCH) {
            state = reap_or_observe_child(pid);
            if (state != STOP_PENDING) return state;
        }
        return stop_error(error);
    }
}

static int monotonic_time_ns(int64_t *value) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) < 0) return stop_error(errno);
    *value = (int64_t) now.tv_sec * 1000000000 + now.tv_nsec;
    return 0;
}

static int wait_for_child_exit(pid_t pid, int timeout_ms) {
    int64_t now;
    int result = monotonic_time_ns(&now);
    if (result < 0) return result;
    const int64_t deadline = now + (int64_t) timeout_ms * 1000000;
    for (;;) {
        result = reap_or_observe_child(pid);
        if (result != STOP_PENDING) return result;
        result = monotonic_time_ns(&now);
        if (result < 0) return result;
        if (now >= deadline) return STOP_TIMEOUT;
        int64_t remaining = deadline - now;
        struct timespec pause = {
                .tv_sec = 0,
                .tv_nsec = remaining < 10000000 ? (long) remaining : 10000000,
        };
        // EINTR 后重新观察子进程和单调时钟，避免信号延长真实超时。
        if (nanosleep(&pause, NULL) < 0 && errno != EINTR) return stop_error(errno);
    }
}

JNIEXPORT jint JNICALL
Java_top_yukonga_mishka_service_ProcessHelper_nativeStop(
        JNIEnv *env, jclass clazz, jint pid, jint grace_ms, jint force_ms) {
    if (pid <= 0 || grace_ms < 0 || force_ms < 0) return stop_error(EINVAL);
    int result = signal_owned_child((pid_t) pid, SIGTERM);
    if (result != STOP_PENDING) return result;
    result = wait_for_child_exit((pid_t) pid, grace_ms);
    // ECHILD 表示所有权已经结束，只有仍活着且真正超时才允许升级。
    if (result != STOP_TIMEOUT) return result;
    result = signal_owned_child((pid_t) pid, SIGKILL);
    if (result != STOP_PENDING) return result;
    return wait_for_child_exit((pid_t) pid, force_ms);
}
