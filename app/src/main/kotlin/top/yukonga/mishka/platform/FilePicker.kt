package top.yukonga.mishka.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

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
     * 初始位置要按 picker 分别喂：
     * - 标准 `EXTRA_INITIAL_URI` 给 DocumentsUI / Google 文件（要求 document URI）。
     * - `CurrentPath` 是 ColorOS 文件管理的私有 extra，那个 picker 只认文件系统绝对路径，
     *   拿到 content URI 会当路径用 → 解析失败 → 静默回到存储根。
     */
    private fun putInitialLocation(intent: Intent) {
        initialLocation()?.let { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        initialDirectoryPath()?.let { intent.putExtra("CurrentPath", it) }
    }

    // DocumentsUI 只在 intent 没带 EXTRA_INITIAL_URI 时按自己的规则挑初始位置，实际表现就是
    // 每次回到默认根目录——要「回到上次导出的目录」只能由应用自己记住并回传。
    private fun rememberLocation(uri: Uri) {
        storage.putString(StorageKeys.SAF_LAST_URI, (documentParent(uri) ?: uri).toString())
        storage.putString(StorageKeys.SAF_LAST_DIR_PATH, documentParentPath(uri).orEmpty())
    }

    private fun initialLocation(): Uri? =
        storage.getString(StorageKeys.SAF_LAST_URI, "")
            .takeIf { it.isNotEmpty() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    private fun initialDirectoryPath(): String? =
        storage.getString(StorageKeys.SAF_LAST_DIR_PATH, "").takeIf { it.isNotEmpty() }

    /**
     * 文档 Uri → 所在目录 Uri。external storage 的 document id 形如 `primary:Download/xx.zip`，
     * 剥掉最后一段即父目录；Downloads 这类 id 不含路径段（`msf:123`）时返回 null，
     * 调用方退回原 Uri 交给 DocumentsUI 自行上溯。
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

    /**
     * 文档 Uri → 所在目录的绝对路径，只覆盖 external storage 与 Downloads 的 `raw:` 形式；
     * `msf:` 这类 MediaStore id 不含路径，返回 null。见 [putInitialLocation] 里为什么需要它。
     */
    private fun documentParentPath(uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val sep = docId.indexOf(':')
        if (sep <= 0) return null
        val volume = docId.substring(0, sep)
        // 先剥掉文件名，剩下的是目录在卷内的相对路径；raw: 之后本身就是绝对路径
        val path = docId.substring(sep + 1).substringBeforeLast('/', missingDelimiterValue = "")
        val full = when (volume) {
            "raw" -> path
            "home" -> "/storage/emulated/0/${path.ifEmpty { "Documents" }}"
            "primary" -> "/storage/emulated/0/$path"
            // 卷 id 形式（SD 卡 `1B0F-2A3C:Download/x.zip`）；`msf:1234567890` 这类 MediaStore id
            // 剥完没有路径段，落到下面的空串判断，不给 picker 一个不存在的目录
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
