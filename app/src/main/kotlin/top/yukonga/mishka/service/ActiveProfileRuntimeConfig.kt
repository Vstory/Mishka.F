package top.yukonga.mishka.service

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import top.yukonga.mishka.custom.forms.FormValues
import top.yukonga.mishka.custom.forms.YamlDoc
import top.yukonga.mishka.custom.forms.YamlNode
import top.yukonga.mishka.custom.forms.YamlPatch
import java.io.File
import java.io.IOException

/** Result of updating the active profile's app-mode fields. */
internal data class ProfileModeConfigPreparation(
    val encrypted: Boolean,
    val tunDefaultsInserted: Boolean,
)

/** Result of attempting to update tun fields in the active source profile. */
internal data class ProfileTunConfigUpdate(
    /** Age ciphertext cannot be modified; callers should use an app-managed runtime override. */
    val encrypted: Boolean,
    /** True when the source was plain YAML and safely accepted the update (even if already equal). */
    val sourceWritable: Boolean,
)

/**
 * Keeps ROOT/VPN mode switches aligned with the effective profile. Plain YAML is patched in place
 * with the comment-preserving form YAML engine. Age-encrypted source files are never rewritten:
 * their ciphertext stays intact and a per-profile app overlay records the mode state, while the
 * decrypted effective listener list is patched into override.run.json for this run.
 */
internal object ActiveProfileRuntimeConfig {
    const val DEFAULT_TUN_DEVICE = "mihomo"

    private val overlayJson = Json { prettyPrint = true }
    private fun tunDefaults(enable: Boolean = true): Map<String, Any?> = linkedMapOf(
        "enable" to enable,
        "mtu" to 9000,
        "device" to DEFAULT_TUN_DEVICE,
        "stack" to "system",
        "dns-hijack" to listOf("any:53", "tcp://any:53"),
        "auto-route" to true,
        "strict-route" to true,
        "auto-redirect" to true,
        "auto-detect-interface" to true,
    )

    /** Patch plaintext active YAML, or persist a safe sidecar for age-encrypted profiles. */
    fun prepare(
        context: Context,
        subscriptionId: String?,
        ebpfEnabled: Boolean,
        forceTunEnabled: Boolean = false,
        forceTunDisabled: Boolean = false,
        systemIpv6: Boolean = false,
    ): ProfileModeConfigPreparation {
        val configFile = if (subscriptionId.isNullOrBlank()) {
            File(ConfigGenerator.getWorkDir(context), "config.yaml")
        } else {
            ProfileFileOps.getSubscriptionConfigFile(context, subscriptionId)
        }
        if (!configFile.isFile || configFile.length() == 0L) {
            throw IOException("Active profile config.yaml is missing or empty")
        }

        val source = configFile.readText(Charsets.UTF_8)
        if (isAgeEncrypted(source)) {
            val overlayId = subscriptionId?.takeIf { it.isNotBlank() } ?: "default"
            val overlay = ProfileFileOps.getRootModeOverlayFile(context, overlayId)
            val document = buildJsonObject {
                put("schema", 1)
                put("kind", "mishka-root-mode-overlay")
                put("tunDefaults", defaultTunJson(enable = !forceTunDisabled))
                put("ebpfMasterEnabled", ebpfEnabled)
            }
            ProfileFileOps.writeAtomically(overlay, overlayJson.encodeToString(JsonObject.serializer(), document))
            return ProfileModeConfigPreparation(encrypted = true, tunDefaultsInserted = false)
        }

        var doc = YamlDoc.parse(source)
        if (doc.root.entries.any { it.key == "__seq__" || it.key == "__scalar__" }) {
            throw IllegalArgumentException("Active profile YAML root must be a mapping")
        }

        val currentTun = doc.get(listOf("tun"))
        val tunMissing = currentTun == null || doc.isNullText(currentTun)
        // TPROXY / eBPF 不初始化 sing-tun，源配置里的 tun 也一并写死关闭：导出的
        // config.yaml 若仍挂着 enable: true，与运行时实际状态不一致，排查时极易误导。
        // TUN 子模式反过来要求继续声明开启（forceTunEnabled）；两者互斥，开启优先。
        val tunEnabledInSource = when {
            forceTunEnabled -> true
            forceTunDisabled -> false
            else -> null
        }
        if (tunMissing) {
            doc = YamlPatch.setValue(doc, listOf("tun"), tunDefaults(enable = tunEnabledInSource ?: true))
        } else if (currentTun?.kind != YamlNode.Kind.MAP) {
            throw IllegalArgumentException("Active profile tun field is not a mapping; refusing to replace it")
        }

        // ROOT TUN needs the source profile to keep declaring TUN enabled. The runtime override
        // also enforces this for encrypted/transformed configs, whose source cannot be rewritten.
        if (tunEnabledInSource != null && !tunMissing) {
            doc = YamlPatch.setValue(doc, listOf("tun", "enable"), tunEnabledInSource)
        }

        doc = ensureEbpfListeners(doc, ebpfEnabled, systemIpv6)
        val updated = doc.dump()
        if (updated != source) ProfileFileOps.writeAtomically(configFile, updated)

        subscriptionId?.let { ProfileFileOps.getRootModeOverlayFile(context, it).delete() }
        return ProfileModeConfigPreparation(encrypted = false, tunDefaultsInserted = tunMissing)
    }

    /**
     * Update selected keys in the active source profile while preserving YAML comments and layout.
     * Age-encrypted files are intentionally left byte-for-byte intact; callers can persist those
     * edits as runtime overrides instead. A null value removes a key (used for gso-max-size).
     */
    fun updateTunValues(
        context: Context,
        subscriptionId: String?,
        values: Map<String, Any?>,
    ): ProfileTunConfigUpdate {
        require(values.isNotEmpty()) { "No tun values to update" }
        val configFile = if (subscriptionId.isNullOrBlank()) {
            File(ConfigGenerator.getWorkDir(context), "config.yaml")
        } else {
            ProfileFileOps.getSubscriptionConfigFile(context, subscriptionId)
        }
        if (!configFile.isFile || configFile.length() == 0L) {
            throw IOException("Active profile config.yaml is missing or empty")
        }

        val source = configFile.readText(Charsets.UTF_8)
        if (isAgeEncrypted(source)) return ProfileTunConfigUpdate(encrypted = true, sourceWritable = false)

        var doc = YamlDoc.parse(source)
        if (doc.root.entries.any { it.key == "__seq__" || it.key == "__scalar__" }) {
            throw IllegalArgumentException("Active profile YAML root must be a mapping")
        }
        val currentTun = doc.get(listOf("tun"))
        if (currentTun == null || doc.isNullText(currentTun)) {
            doc = YamlPatch.setValue(doc, listOf("tun"), emptyMap<String, Any?>())
        } else if (currentTun.kind != YamlNode.Kind.MAP) {
            throw IllegalArgumentException("Active profile tun field is not a mapping; refusing to replace it")
        }

        for ((key, value) in values) {
            val path = listOf<Any>("tun", key)
            doc = if (value == null) YamlPatch.removeKey(doc, path) else YamlPatch.setValue(doc, path, value)
            if (value != null && doc.get(path) == null) {
                throw IOException("Unable to update active profile tun.$key")
            }
        }
        val updated = doc.dump()
        if (updated != source) ProfileFileOps.writeAtomically(configFile, updated)
        return ProfileTunConfigUpdate(encrypted = false, sourceWritable = true)
    }

    /**
     * Create/update eBPF role flags in the effective (possibly transformed and decrypted) listener
     * array. The full array is returned so mihomo's JSON override does not discard unrelated entries.
     */
    fun patchRuntimeListeners(existing: JsonElement?, ebpfEnabled: Boolean, systemIpv6: Boolean = false): JsonArray {
        val entries = when (existing) {
            null, JsonNull -> mutableListOf()
            is JsonArray -> existing.toMutableList()
            else -> throw IllegalArgumentException("Active profile listeners field is not a list")
        }

        var ebpfIndices = entries.indices.filter { isEbpfListener(entries[it]) }
        if (ebpfIndices.isEmpty()) {
            val name = uniqueJsonListenerName(entries)
            entries += defaultEbpfListener(name, systemIpv6)
            ebpfIndices = listOf(entries.lastIndex)
        }

        for (index in ebpfIndices) {
            val original = entries[index] as? JsonObject
                ?: throw IllegalArgumentException("listeners[$index] is not a mapping")
            val listener = original.toMutableMap()
            // `mode` cannot be combined with explicit role flags in the eBPF schema.
            listener.remove("mode")
            listener["local"] = patchRole(listener["local"], "listeners[$index].local", ebpfEnabled)
            listener["shared"] = patchRole(listener["shared"], "listeners[$index].shared", ebpfEnabled)
            entries[index] = JsonObject(listener)
        }
        return JsonArray(entries)
    }

    private fun ensureEbpfListeners(input: YamlDoc, ebpfEnabled: Boolean, systemIpv6: Boolean): YamlDoc {
        var doc = input
        var listeners = doc.get(listOf("listeners"))
        if (listeners == null || doc.isNullText(listeners)) {
            val name = uniqueYamlListenerName(doc)
            doc = YamlPatch.setValue(doc, listOf("listeners"), listOf(defaultEbpfListenerMap(name, systemIpv6)))
            listeners = doc.get(listOf("listeners"))
        } else if (listeners.kind != YamlNode.Kind.SEQ) {
            throw IllegalArgumentException("Active profile listeners field is not a list; refusing to overwrite it")
        }

        listeners = doc.get(listOf("listeners"))
            ?: throw IllegalArgumentException("Unable to create active profile listeners list")
        if (listeners.kind != YamlNode.Kind.SEQ) {
            throw IllegalArgumentException("Active profile listeners field is not a list")
        }
        var indices = listeners.items.indices.filter { index ->
            FormValues.readRaw(doc, listOf("listeners", index, "type"))?.equals("ebpf", ignoreCase = true) == true
        }
        if (indices.isEmpty()) {
            val name = uniqueYamlListenerName(doc)
            doc = YamlPatch.insertItem(doc, listOf("listeners"), listeners.items.size, defaultEbpfListenerMap(name, systemIpv6))
            listeners = doc.get(listOf("listeners"))
                ?: throw IllegalArgumentException("Unable to append eBPF listener")
            if (listeners.kind != YamlNode.Kind.SEQ) throw IllegalArgumentException("Active profile listeners field is not a list")
            indices = listOf(listeners.items.lastIndex)
        }

        for (index in indices) {
            val base = listOf("listeners", index)
            val localPath = base + "local"
            val sharedPath = base + "shared"
            doc = ensureYamlRoleMap(doc, localPath)
            doc = ensureYamlRoleMap(doc, sharedPath)
            doc = YamlPatch.removeKey(doc, base + "mode")
            val localLegacyEnable = doc.get(localPath + "enabled") != null
            val sharedLegacyEnable = doc.get(sharedPath + "enabled") != null
            doc = YamlPatch.setValue(doc, localPath + "enable", ebpfEnabled)
            doc = YamlPatch.setValue(doc, sharedPath + "enable", ebpfEnabled)
            // Keep legacy aliases if present, but make them agree with the canonical spelling.
            if (localLegacyEnable) doc = YamlPatch.setValue(doc, localPath + "enabled", ebpfEnabled)
            if (sharedLegacyEnable) doc = YamlPatch.setValue(doc, sharedPath + "enabled", ebpfEnabled)
            if (doc.get(localPath + "enable") == null || doc.get(sharedPath + "enable") == null) {
                throw IllegalArgumentException("Unable to update eBPF master switch in listeners[$index]")
            }
        }
        return doc
    }

    private fun ensureYamlRoleMap(doc: YamlDoc, path: List<Any>): YamlDoc {
        val current = doc.get(path)
        if (current == null || doc.isNullText(current)) {
            return YamlPatch.setValue(doc, path, emptyMap<String, Any?>())
        }
        if (current.kind != YamlNode.Kind.MAP) {
            throw IllegalArgumentException("${path.joinToString(".")} is not a mapping")
        }
        return doc
    }

    private fun patchRole(value: JsonElement?, path: String, enabled: Boolean): JsonObject {
        val role = when (value) {
            null, JsonNull -> linkedMapOf()
            is JsonObject -> value.toMutableMap()
            else -> throw IllegalArgumentException("$path is not a mapping")
        }
        val hadLegacyEnable = "enabled" in role
        role["enable"] = JsonPrimitive(enabled)
        if (hadLegacyEnable) role["enabled"] = JsonPrimitive(enabled)
        return JsonObject(role)
    }

    private fun defaultEbpfListenerMap(name: String, ipv6: Boolean): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "type" to "ebpf",
        "bypass-tun-direct" to false,
        "local" to linkedMapOf<String, Any?>(
            "enable" to false,
            "ipv6" to ipv6,
            "bypass-private-address" to false,
        ),
        "shared" to linkedMapOf<String, Any?>(
            "enable" to false,
            "ipv6" to ipv6,
            "bypass-private-address" to false,
            "interface" to listOf("wlan2", "br-lan", "br0"),
        ),
    )

    private fun defaultEbpfListener(name: String, ipv6: Boolean): JsonObject = buildJsonObject {
        put("name", name)
        put("type", "ebpf")
        put("bypass-tun-direct", false)
        put("local", buildJsonObject {
            put("enable", false)
            put("ipv6", ipv6)
            put("bypass-private-address", false)
        })
        put("shared", buildJsonObject {
            put("enable", false)
            put("ipv6", ipv6)
            put("bypass-private-address", false)
            put("interface", buildJsonArray {
                add(JsonPrimitive("wlan2"))
                add(JsonPrimitive("br-lan"))
                add(JsonPrimitive("br0"))
            })
        })
    }

    private fun defaultTunJson(enable: Boolean = true): JsonObject = buildJsonObject {
        put("enable", enable)
        put("mtu", 9000)
        put("device", DEFAULT_TUN_DEVICE)
        put("stack", "system")
        put("dns-hijack", buildJsonArray {
            add(JsonPrimitive("any:53"))
            add(JsonPrimitive("tcp://any:53"))
        })
        put("auto-route", true)
        put("strict-route", true)
        put("auto-redirect", true)
        put("auto-detect-interface", true)
    }

    private fun uniqueYamlListenerName(doc: YamlDoc): String {
        val list = doc.get(listOf("listeners"))
        val used = if (list?.kind == YamlNode.Kind.SEQ) {
            list.items.indices.mapNotNull { FormValues.readRaw(doc, listOf("listeners", it, "name")) }.toSet()
        } else emptySet()
        if ("ebpf-in" !in used) return "ebpf-in"
        var suffix = 2
        while ("ebpf-in-$suffix" in used) suffix++
        return "ebpf-in-$suffix"
    }

    private fun uniqueJsonListenerName(entries: List<JsonElement>): String {
        val used = entries.mapNotNull { item ->
            (item as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
        }.toSet()
        if ("ebpf-in" !in used) return "ebpf-in"
        var suffix = 2
        while ("ebpf-in-$suffix" in used) suffix++
        return "ebpf-in-$suffix"
    }

    private fun isEbpfListener(item: JsonElement): Boolean =
        (item as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull.equals("ebpf", ignoreCase = true)

    private fun isAgeEncrypted(source: String): Boolean =
        source.trimStart().startsWith("-----BEGIN AGE ENCRYPTED FILE-----")
}
