package top.yukonga.mishka.data.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.yukonga.mishka.domain.model.Subscription
import top.yukonga.mishka.domain.model.orderedOverrideIds
import top.yukonga.mishka.platform.ProfileFileManager

@Serializable
private data class TransformSpec(
    val overrides: List<OverrideSpec>,
)

/** 将订阅选择的覆写清单写成内核 --transform 使用的快照。 */
class ProfileTransformWriter(
    private val fileManager: ProfileFileManager,
    private val overrides: OverrideProfileStore,
) {
    private val json = Json { encodeDefaults = true }

    fun write(
        subscription: Subscription?,
        relativePath: String,
        replacement: OverrideReplacement? = null,
    ): String? {
        val target = java.io.File(fileManager.getMihomoWorkDir(), relativePath)
        val specs = subscription?.orderedOverrideIds?.let { overrides.specs(it, replacement) }.orEmpty()
        if (specs.isEmpty()) {
            target.delete()
            return null
        }
        fileManager.writeMihomoFile(relativePath, json.encodeToString(TransformSpec.serializer(), TransformSpec(specs)))
        return target.path
    }

    companion object {
        const val RUNTIME_PATH = "profile.transform.json"
    }
}
