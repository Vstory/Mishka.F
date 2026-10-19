import org.gradle.api.Project

object ProjectConfig {
    const val APP_NAME = "Mishka"
    const val PACKAGE_NAME = "top.yukonga.mishka"
    const val VERSION_NAME = "1.0.0"

    object Android {
        const val TARGET_SDK = 37
        const val MIN_SDK = 31
        const val COMPILE_SDK = 37
        const val COMPILE_SDK_MINOR = 2
    }
}

private const val FALLBACK_VERSION_CODE = 1

/** 提交数作为 versionCode。调用方应只求值一次。 */
fun Project.getGitVersionCode(): Int =
    runCatching {
        providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim().toInt()
    }.getOrElse {
        logger.warn("git rev-list failed (${it.message}); versionCode falls back to $FALLBACK_VERSION_CODE")
        FALLBACK_VERSION_CODE
    }
