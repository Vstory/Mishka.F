package top.yukonga.mishka.service

/**
 * JNI 辅助：使用 fork+exec 启动子进程，不关闭继承的 fd。
 * 绕过 Android ProcessBuilder 强制关闭非标准 fd 的安全机制。
 */
object ProcessHelper {

    init {
        System.loadLibrary("mishka")
    }

    /**
     * fork+exec 启动进程，子进程继承所有 fd。
     * @param logFile 日志文件路径，子进程的 stdout/stderr 将重定向到此文件；null 则不捕获
     * @return 子进程 PID，失败返回 -1
     */
    external fun nativeForkExec(binary: String, args: Array<String>, workDir: String, logFile: String? = null): Int

    /**
     * 在父进程创建随机 127.x.x.x 地址、随机端口的 HTTP 监听 socket，失败返回 -1。
     * fd 不带 CLOEXEC；调用方传给子进程，并持有至 stop、重启或启动失败时负责关闭。
     */
    external fun nativeCreateHttpListener(): Int

    /** 返回监听 fd 的实际端口，查询失败返回 -1。 */
    external fun nativeHttpListenerPort(fd: Int): Int

    /** 返回监听 fd 的实际数字 IPv4 地址（不含端口），查询失败返回 null。 */
    external fun nativeHttpListenerAddress(fd: Int): String?

    /** 僵尸的 `/proc/<pid>` 仍在；waitid(WNOWAIT) 只观察退出，不提前收割或释放 PID。 */
    external fun nativeIsAlive(pid: Int): Boolean

    const val STOP_NOT_CHILD = -1
    const val STOP_TIMEOUT = -2
    const val STOP_ERROR_BASE = -1000

    /**
     * 统一确认子进程所有权、TERM、限时等待、超时 KILL 与收割。调用方须串行持有该 PID。
     * >=0 为已收割的退出码（信号终止为 128+signo）；[STOP_NOT_CHILD] 为 ECHILD、所有权已结束。
     * [STOP_TIMEOUT] 表示强制停止仍超时；其他错误为 [STOP_ERROR_BASE]-errno。
     * 超时或错误时必须保留 PID 与资源，不能将仍可能存活的子进程当作停止成功。
     */
    external fun nativeStop(pid: Int, graceMs: Int, forceMs: Int): Int
}
