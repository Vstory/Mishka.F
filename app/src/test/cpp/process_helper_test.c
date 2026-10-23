#include <assert.h>
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/*
 * Android/NDK 独立回归验证（无需 JVM 或 Gradle）：
 * $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang \
 *   app/src/test/cpp/process_helper_test.c -llog -o /tmp/process_helper_test
 * adb push /tmp/process_helper_test /data/local/tmp/
 * adb shell /data/local/tmp/process_helper_test
 */

static int signal_calls;
static int term_calls;
static int force_calls;
static int waitid_interrupts;
static int waitpid_interrupts;
static int kill_interrupts;
static int sleep_interrupts;
static int signal_error;
static int suppress_force;
static int reap_after_term;

static int test_waitid(idtype_t type, id_t id, siginfo_t *info, int options) {
    if (waitid_interrupts > 0) {
        --waitid_interrupts;
        errno = EINTR;
        return -1;
    }
    return waitid(type, id, info, options);
}

static pid_t test_waitpid(pid_t pid, int *status, int options) {
    if (waitpid_interrupts > 0) {
        --waitpid_interrupts;
        errno = EINTR;
        return -1;
    }
    return waitpid(pid, status, options);
}

static int test_kill(pid_t pid, int signal_number) {
    ++signal_calls;
    if (signal_number == SIGTERM) ++term_calls;
    if (signal_number == SIGKILL) ++force_calls;
    if (kill_interrupts > 0) {
        --kill_interrupts;
        errno = EINTR;
        return -1;
    }
    if (signal_error != 0) {
        errno = signal_error;
        return -1;
    }
    // 即使回归把非子进程误当成目标，也不能让测试真的杀死自身。
    if (pid == getpid() || (signal_number == SIGKILL && suppress_force)) return 0;
    int result = kill(pid, signal_number);
    if (result == 0 && signal_number == SIGTERM && reap_after_term) {
        int status;
        pid_t reaped;
        do {
            reaped = waitpid(pid, &status, 0);
        } while (reaped < 0 && errno == EINTR);
        assert(reaped == pid);
    }
    return result;
}

static int test_nanosleep(const struct timespec *duration, struct timespec *remaining) {
    if (sleep_interrupts > 0) {
        --sleep_interrupts;
        errno = EINTR;
        return -1;
    }
    return nanosleep(duration, remaining);
}

#define waitid test_waitid
#define waitpid test_waitpid
#define kill test_kill
#define nanosleep test_nanosleep

#include "../../main/cpp/process_helper.c"

#undef waitid
#undef waitpid
#undef kill
#undef nanosleep

static int stop_child(pid_t pid, int grace_ms, int force_ms) {
    return Java_top_yukonga_mishka_service_ProcessHelper_nativeStop(
            NULL, NULL, pid, grace_ms, force_ms);
}

static int child_is_alive(pid_t pid) {
    return Java_top_yukonga_mishka_service_ProcessHelper_nativeIsAlive(NULL, NULL, pid);
}

static void reset_probes(void) {
    signal_calls = term_calls = force_calls = 0;
    waitid_interrupts = waitpid_interrupts = kill_interrupts = sleep_interrupts = 0;
    signal_error = suppress_force = reap_after_term = 0;
}

static void graceful_exit(int signal_number) {
    (void) signal_number;
    _exit(23);
}

static pid_t spawn_child(int ignore_term) {
    int ready[2];
    assert(pipe(ready) == 0);
    pid_t pid = fork();
    assert(pid >= 0);
    if (pid == 0) {
        close(ready[0]);
        struct sigaction action = {0};
        action.sa_handler = ignore_term ? SIG_IGN : graceful_exit;
        sigemptyset(&action.sa_mask);
        if (sigaction(SIGTERM, &action, NULL) < 0 || write(ready[1], "r", 1) != 1) _exit(100);
        close(ready[1]);
        for (;;) pause();
    }
    close(ready[1]);
    char byte;
    ssize_t result;
    do {
        result = read(ready[0], &byte, 1);
    } while (result < 0 && errno == EINTR);
    assert(result == 1);
    close(ready[0]);
    return pid;
}

static void assert_reaped(pid_t pid) {
    int status;
    errno = 0;
    assert(waitpid(pid, &status, WNOHANG) == -1 && errno == ECHILD);
}

static void test_exited_child(void) {
    reset_probes();
    pid_t pid = fork();
    assert(pid >= 0);
    if (pid == 0) _exit(37);
    siginfo_t info;
    // WNOWAIT 让 stop 真正面对一个仍由本组件持有的僵尸。
    assert(waitid(P_PID, (id_t) pid, &info, WEXITED | WNOWAIT) == 0);
    waitid_interrupts = 1;
    assert(!child_is_alive(pid));
    assert(waitid(P_PID, (id_t) pid, &info, WEXITED | WNOHANG | WNOWAIT) == 0);
    assert(info.si_pid == pid);
    waitpid_interrupts = 1;
    assert(stop_child(pid, 100, 1000) == 37);
    assert(signal_calls == 0);
    assert_reaped(pid);
}

static void test_already_reaped_child(void) {
    reset_probes();
    pid_t pid = fork();
    assert(pid >= 0);
    if (pid == 0) _exit(0);
    int status;
    assert(waitpid(pid, &status, 0) == pid);
    assert(!child_is_alive(pid));
    assert(stop_child(pid, 0, 0) == STOP_NOT_CHILD);
    assert(signal_calls == 0);
}

static void test_graceful_child(void) {
    reset_probes();
    pid_t pid = spawn_child(0);
    assert(child_is_alive(pid));
    assert(stop_child(pid, 1000, 1000) == 23);
    assert(term_calls == 1 && force_calls == 0);
    assert_reaped(pid);
}

static void test_forced_child(void) {
    reset_probes();
    pid_t pid = spawn_child(1);
    assert(stop_child(pid, 30, 1000) == 128 + SIGKILL);
    assert(term_calls == 1 && force_calls == 1);
    assert_reaped(pid);
}

static void test_external_reaper(void) {
    reset_probes();
    pid_t pid = spawn_child(0);
    reap_after_term = 1;
    assert(stop_child(pid, 0, 1000) == STOP_NOT_CHILD);
    assert(term_calls == 1 && force_calls == 0);
    assert_reaped(pid);
}

static void test_non_child(void) {
    reset_probes();
    assert(!child_is_alive(getpid()));
    assert(stop_child(getpid(), 0, 0) == STOP_NOT_CHILD);
    assert(signal_calls == 0);
}

static void test_interrupts(void) {
    reset_probes();
    pid_t pid = spawn_child(1);
    waitid_interrupts = waitpid_interrupts = kill_interrupts = sleep_interrupts = 1;
    assert(stop_child(pid, 30, 1000) == 128 + SIGKILL);
    assert(term_calls == 2 && force_calls == 1);
    assert(waitid_interrupts == 0 && waitpid_interrupts == 0);
    assert(kill_interrupts == 0 && sleep_interrupts == 0);
    assert_reaped(pid);
}

static void test_timeout_retains_child(void) {
    reset_probes();
    pid_t pid = spawn_child(1);
    // 模拟 SIGKILL 后仍未退出（例如不可中断内核等待），保留真实活子进程。
    suppress_force = 1;
    assert(stop_child(pid, 10, 10) == STOP_TIMEOUT);
    assert(child_is_alive(pid));
    assert(term_calls == 1 && force_calls == 1);
    suppress_force = 0;
    assert(stop_child(pid, 0, 1000) == 128 + SIGKILL);
    assert_reaped(pid);
}

static void test_signal_error(void) {
    reset_probes();
    pid_t pid = spawn_child(1);
    signal_error = EPERM;
    assert(stop_child(pid, 0, 0) == STOP_ERROR_BASE - EPERM);
    assert(term_calls == 1 && force_calls == 0);
    assert(child_is_alive(pid));
    signal_error = 0;
    assert(stop_child(pid, 0, 1000) == 128 + SIGKILL);
    assert_reaped(pid);
}

int main(void) {
    test_exited_child();
    test_already_reaped_child();
    test_graceful_child();
    test_forced_child();
    test_external_reaper();
    test_non_child();
    test_interrupts();
    test_timeout_retains_child();
    test_signal_error();
    puts("process_helper ownership regressions passed");
    return 0;
}
