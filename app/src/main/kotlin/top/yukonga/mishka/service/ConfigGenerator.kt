package top.yukonga.mishka.service

import android.content.Context
import top.yukonga.mishka.custom.runtime.RuntimeConfigValues
import top.yukonga.mishka.domain.model.ConfigurationOverride
import top.yukonga.mishka.domain.model.TunOverride
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import java.io.File

/**
 * Runtime profile-value resolution for mihomo. The active profile file has priority over Mishka
 * overrides; controller credentials are empty when neither source configures a secret.
 */
object ConfigGenerator {

    private const val DEFAULT_EXTERNAL_CONTROLLER = "127.0.0.1:9090"

    /**
     * Secret priority: active profile `secret` > Mishka override > no authentication.
     * An explicitly empty/null profile secret is retained as empty and therefore disables auth.
     */
    fun resolveSecret(
        context: Context,
        userOverride: ConfigurationOverride,
        subscriptionId: String?,
    ): String = resolveSecret(userOverride, readProfileScalar(context, subscriptionId, "secret"))

    /** Resolve a secret using an already decrypted/transformed profile value. */
    fun resolveSecret(userOverride: ConfigurationOverride, profileSecret: String?): String =
        RuntimeConfigValues.selectSecret(profileSecret, userOverride.secret)

    /**
     * Binding priority: active profile `external-controller` > Mishka override > loopback default.
     * The configured bind value is returned unchanged; use [apiClientAddress] for requests made by
     * Mishka itself when that bind is a wildcard.
     */
    fun resolveExternalController(
        context: Context,
        userOverride: ConfigurationOverride,
        subscriptionId: String?,
    ): String = resolveExternalController(
        userOverride,
        readSubscriptionExternalController(context, subscriptionId),
    )

    /** Resolve a binding using an already decrypted/transformed profile value. */
    fun resolveExternalController(userOverride: ConfigurationOverride, profileController: String?): String =
        RuntimeConfigValues.selectExternalController(
            profileController,
            userOverride.externalController,
            DEFAULT_EXTERNAL_CONTROLLER,
        )

    fun apiClientAddress(bindAddress: String): String = RuntimeConfigValues.apiClientAddress(bindAddress)

    /** Read the active profile's top-level `tproxy-port`, if it is plain YAML. */
    fun readSubscriptionTproxyPort(context: Context, subscriptionId: String?): Int? =
        readProfileScalar(context, subscriptionId, "tproxy-port")
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it in 1..65535 }

    /** Active profile `tproxy-port` > app override > the historical ROOT fallback. */
    fun resolveRootTproxyPort(profilePort: Int?, appPort: Int?): Int =
        RuntimeConfigValues.selectRootTproxyPort(
            profilePort = profilePort,
            appPort = appPort,
            defaultPort = RootTproxyApplier.TPROXY_PORT,
        )

    /**
     * ROOT TUN device priority: active profile `tun.device` > Mishka's `tun.device` override >
     * existing ROOT device setting > the historic default.
     */
    fun resolveRootTunDevice(
        context: Context,
        userOverride: ConfigurationOverride,
        subscriptionId: String?,
        storage: PlatformStorage = PlatformStorage(context),
    ): String = resolveRootTunDevice(
        userOverride,
        readSubscriptionTunDevice(context, subscriptionId),
        storage,
    )

    /** Resolve ROOT TUN name using an already decrypted/transformed profile value. */
    fun resolveRootTunDevice(
        userOverride: ConfigurationOverride,
        profileTunDevice: String?,
        storage: PlatformStorage,
    ): String = RuntimeConfigValues.selectTunDevice(
        profileValue = profileTunDevice,
        appValue = userOverride.tun?.device,
        rootSetting = storage.getString(StorageKeys.ROOT_TUN_DEVICE, RuntimeOverrideBuilder.DEFAULT_TUN_DEVICE),
        defaultValue = RuntimeOverrideBuilder.DEFAULT_TUN_DEVICE,
    )

    fun getWorkDir(context: Context): File {
        val dir = File(context.filesDir, "mihomo")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Plain-YAML fallback used only if native age/transform inspection is unavailable. */
    fun readSubscriptionSecret(context: Context, subscriptionId: String): String? =
        readProfileScalar(context, subscriptionId, "secret")

    /** Read the active profile's top-level `external-controller` scalar, if present. */
    fun readSubscriptionExternalController(context: Context, subscriptionId: String?): String? =
        readProfileScalar(context, subscriptionId, "external-controller")

    /** Read the active profile's direct `tun.device` scalar, including flow-style `tun` maps. */
    fun readSubscriptionTunDevice(context: Context, subscriptionId: String?): String? {
        val file = configFile(context, subscriptionId)
        if (!file.isFile) return null
        return try {
            RuntimeConfigValues.nestedScalar(file.readText(Charsets.UTF_8), "tun", "device")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /** Read simple tun values from plain YAML as a fallback when native effective-config inspection is unavailable. */
    fun readSubscriptionTunConfig(context: Context, subscriptionId: String?): TunOverride? {
        val file = configFile(context, subscriptionId)
        if (!file.isFile) return null
        return try {
            val source = file.readText(Charsets.UTF_8)
            if (source.trimStart().startsWith("-----BEGIN AGE ENCRYPTED FILE-----")) return null
            fun scalar(key: String): String? = RuntimeConfigValues.nestedScalar(source, "tun", key)
            val config = TunOverride(
                enable = scalar("enable")?.trim()?.toBooleanStrictOrNull(),
                device = scalar("device")?.trim()?.takeIf { it.isNotEmpty() },
                stack = scalar("stack")?.trim()?.takeIf { it.isNotEmpty() },
                autoRoute = scalar("auto-route")?.trim()?.toBooleanStrictOrNull(),
                strictRoute = scalar("strict-route")?.trim()?.toBooleanStrictOrNull(),
                autoRedirect = scalar("auto-redirect")?.trim()?.toBooleanStrictOrNull(),
                autoDetectInterface = scalar("auto-detect-interface")?.trim()?.toBooleanStrictOrNull(),
                mtu = scalar("mtu")?.trim()?.toIntOrNull(),
                gso = scalar("gso")?.trim()?.toBooleanStrictOrNull(),
                gsoMaxSize = scalar("gso-max-size")?.trim()?.toIntOrNull(),
            )
            config.takeIf {
                it.enable != null || it.device != null || it.stack != null || it.autoRoute != null ||
                    it.strictRoute != null || it.autoRedirect != null || it.autoDetectInterface != null ||
                    it.mtu != null || it.gso != null || it.gsoMaxSize != null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Read a top-level scalar from the active profile's config.yaml. `""` is intentionally
     * different from null: it means the key is present but empty (for example `secret:`).
     */
    private fun readProfileScalar(context: Context, subscriptionId: String?, key: String): String? {
        val file = configFile(context, subscriptionId)
        if (!file.isFile) return null
        return try {
            RuntimeConfigValues.topLevelScalar(file.readText(Charsets.UTF_8), key)
        } catch (_: Exception) {
            null
        }
    }

    private fun configFile(context: Context, subscriptionId: String?): File =
        if (!subscriptionId.isNullOrBlank()) {
            ProfileFileOps.getSubscriptionConfigFile(context, subscriptionId)
        } else {
            File(getWorkDir(context), "config.yaml")
        }

    /**
     * From the active profile's top-level `mixed-port`, used to decide whether mihomo already
     * listens for HTTP proxy traffic. Invalid/empty values are treated as unset.
     */
    fun readSubscriptionMixedPort(context: Context, subscriptionId: String): Int? =
        readProfileScalar(context, subscriptionId, "mixed-port")
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it in 1..65535 }
}
