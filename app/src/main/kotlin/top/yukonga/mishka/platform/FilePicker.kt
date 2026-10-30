package top.yukonga.mishka.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/**
 * 文件选择结果
 */
data class FilePickResult(
    val fileName: String,
    val content: String,
)

class FilePicker(
    private val activity: ComponentActivity,
    private val storage: PlatformStorage,
) {

    private var callback: ((FilePickResult?) -> Unit)? = null

    // Activity Result API：在 Activity onCreate 期间构造 FilePicker 时注册，替代已弃用的
    // startActivityForResult / onActivityResult
    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> handleResult(result) }

    fun pickYamlFile(onResult: (FilePickResult?) -> Unit) {
        callback = onResult
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        launcher.launch(intent)
    }

    // === 二进制备份文档（WebDAV 本地备份同款 zip）===
    // 只负责拿 Uri：备份可达数十 MB，读写留给数据层在 IO 上流式完成，不在这条主线程回调里搬内容

    private var saveCallback: ((Uri?) -> Unit)? = null
    private val saveLauncher = activity.registerForActivityResult(DocumentUriContract()) { uri ->
        val cb = saveCallback
        saveCallback = null
        uri?.let { rememberLocation(it) }
        cb?.invoke(uri)
    }

    private var pickCallback: ((Uri?) -> Unit)? = null
    private val pickLauncher = activity.registerForActivityResult(DocumentUriContract()) { uri ->
        val cb = pickCallback
        pickCallback = null
        uri?.let { rememberLocation(it) }
        cb?.invoke(uri)
    }

    /** 经 SAF 新建文档作为写入目标；回调 null 表示用户取消。 */
    fun createZipDocument(suggestedName: String, onResult: (Uri?) -> Unit) {
        saveCallback = onResult
        saveLauncher.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, suggestedName)
                putInitialLocation(this)
            }
        )
    }

    /** 经 SAF 选择已有文档；回调 null 表示用户取消。 */
    fun pickZipDocument(onResult: (Uri?) -> Unit) {
        pickCallback = onResult
        pickLauncher.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                // 不按 MIME 过滤：备份文件经网盘/传输工具流转后 provider 常报 octet-stream 等杂项类型
                type = "*/*"
                putInitialLocation(this)
            }
        )
    }

    /**
     * 只传标准 `EXTRA_INITIAL_URI`。ColorOS 的 picker（com.coloros.filemanager）拿到它后
     * `Uri.parse` → `isDocumentUri` / `isRootUri` 判定 → 取 docId 的 `split(":")[1]` 拼成
     * `/storage/emulated/0/<tail>`；判定落空就静默回默认位置。所以这里必须是 external storage
     * 的 document URI —— 它另有个私有 `CurrentPath` extra 读纯路径，且优先级更高，
     * 塞个 `/storage/...` 进去会让上面这条判定整个落空。
     */
    private fun putInitialLocation(intent: Intent) {
        initialLocation()?.let { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
    }

    // DocumentsUI 与 ColorOS 文件管理都不会自己记住上次落点，要「回到上次导出的目录」
    // 只能由应用记住并回传。
    private fun rememberLocation(uri: Uri) {
        storage.putString(StorageKeys.SAF_LAST_URI, canonicalDirectoryUri(uri).toString())
    }

    private fun initialLocation(): Uri? =
        storage.getString(StorageKeys.SAF_LAST_URI, "")
            .takeIf { it.isNotEmpty() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /**
     * 归一化成 external storage 的目录 document URI：picker 侧只能解析这种形态。
     * 网盘等 provider 的 Uri 推不出目录时，退回「父目录 Uri → 原 Uri」。
     */
    private fun canonicalDirectoryUri(uri: Uri): Uri {
        val path = documentParentPath(uri)
        return path?.let { directoryDocumentUri(it) } ?: documentParent(uri) ?: uri
    }

    /**
     * 文档 Uri → 所在目录 Uri。external storage 的 document id 形如 `primary:Download/xx.zip`，
     * 剥掉最后一段即父目录；不含路径段的 id 返回 null，调用方退回原 Uri。
     */
    private fun documentParent(uri: Uri): Uri? {
        val authority = uri.authority ?: return null
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val slash = docId.lastIndexOf('/')
        if (slash <= 0) return null
        return runCatching {
            DocumentsContract.buildDocumentUri(authority, docId.substring(0, slash))
        }.getOrNull()
    }

    /** 目录绝对路径 → external storage 的 document URI（picker 唯一认得的形态）。 */
    private fun directoryDocumentUri(path: String): Uri? {
        val primary = Environment.getExternalStorageDirectory().absolutePath
        val (volume, relative) = when {
            path == primary -> "primary" to ""
            path.startsWith("$primary/") -> "primary" to path.removePrefix("$primary/")
            else -> {
                // SD 卡之类 `/storage/<卷 id>/<相对路径>`；emulated/<userId> 不在猜测范围
                val match = Regex("^/storage/([^/]+)/(.+)$").find(path) ?: return null
                val name = match.groupValues[1]
                if (name == "emulated") return null
                name to match.groupValues[2]
            }
        }
        val docId = if (relative.isEmpty()) "$volume:" else "$volume:$relative"
        return runCatching {
            DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)
        }.getOrNull()
    }

    /**
     * 文档 Uri → 所在目录的绝对路径，只覆盖 external storage（`primary:` / `home:` / 卷 id / `raw:`）。
     * 其余 provider（网盘等）返回 null。
     */
    private fun documentParentPath(uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val sep = docId.indexOf(':')
        if (sep <= 0) return null
        val volume = docId.substring(0, sep)
        val rest = docId.substring(sep + 1)
        // 先剥掉文件名，剩下的是目录在卷内的相对路径；raw: 之后本身就是绝对路径
        val path = rest.substringBeforeLast('/', missingDelimiterValue = "")
        val full = when (volume) {
            "raw" -> path
            "home" -> "/storage/emulated/0/${path.ifEmpty { "Documents" }}"
            "primary" -> "/storage/emulated/0/$path"
            // 卷 id 形式（SD 卡 `1B0F-2A3C:Download/x.zip`）；扫不出路径段就当未知
            else -> if (path.isEmpty()) "" else "/storage/$volume/$path"
        }
        return full.trimEnd('/').ifEmpty { null }
    }

    private fun handleResult(result: ActivityResult) {
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data?.data == null) {
            callback?.invoke(null)
            callback = null
            return
        }
        val uri: Uri = data.data!!
        try {
            val fileName = getFileName(uri)
            val content = activity.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: ""
            callback?.invoke(FilePickResult(fileName, content))
        } catch (_: Exception) {
            callback?.invoke(null)
        }
        callback = null
    }

    private fun getFileName(uri: Uri): String {
        var name = "imported.yaml"
        activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }
}

/**
 * SAF 透传合约：Intent 由调用方组装，只解析返回文档的 Uri。
 *
 * 系统自带的 CreateDocument / OpenDocument 合约把 intent 拼死了（只设 type 与 EXTRA_TITLE），
 * 没留口子附加 EXTRA_INITIAL_URI —— 而「回到上次的目录」全押在这个 extra 上。
 */
private class DocumentUriContract : ActivityResultContract<Intent, Uri?>() {
    override fun createIntent(context: Context, input: Intent): Intent = input

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        if (resultCode != Activity.RESULT_OK) return null
        return intent?.data
    }
}
