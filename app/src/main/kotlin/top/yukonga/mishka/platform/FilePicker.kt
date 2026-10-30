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

    // === 备份落点目录（授权一次，之后不再弹 picker）===
    // Android 12 起 DocumentsUI 不再提供「进入某个目录浏览」的入口，EXTRA_INITIAL_URI 因此
    // 无处落点（ColorOS 的文件管理器同理），「每次回到上次目录」不可实现。只能反过来：
    // 让用户把目录本身授权下来，导出直接往这棵目录树里建文件。
    private var sessionDirectory: Uri? = null
    private var dirCallback: ((Uri?) -> Unit)? = null
    private val dirLauncher = activity.registerForActivityResult(DocumentUriContract()) { uri ->
        val cb = dirCallback
        dirCallback = null
        // 个别 OEM 的 picker 对 OPEN_DOCUMENT_TREE 回普通 document Uri，那种 Uri 建不了子文档
        val tree = uri?.takeIf { runCatching { DocumentsContract.isTreeUri(it) }.getOrDefault(false) }
        tree?.let { rememberBackupDirectory(it) }
        cb?.invoke(tree)
    }

    /** 让用户授权备份落点目录；回调 null 表示取消。 */
    fun pickBackupDirectory(onResult: (Uri?) -> Unit) {
        dirCallback = onResult
        dirLauncher.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                )
                // 尽力而为：picker 认不认这个初始位置随系统
                backupDirectory()?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            }
        )
    }

    /** 已授权的备份目录；授权被系统/用户撤销后返回 null，调用方需要重新授权。 */
    fun backupDirectory(): Uri? {
        sessionDirectory?.let { return it }
        val remembered = storage.getString(StorageKeys.SAF_BACKUP_DIR_URI, "")
            .takeIf { it.isNotEmpty() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: return null
        val writable = activity.contentResolver.persistedUriPermissions.any {
            it.isWritePermission && it.uri == remembered
        }
        return remembered.takeIf { writable }
    }

    /** 备份目录的显示名（tree document id 的末段，如 `primary:Download` → Download）。 */
    fun backupDirectoryLabel(): String? = backupDirectory()
        ?.let { runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull() }
        ?.substringAfterLast(':')
        ?.takeIf { it.isNotEmpty() }

    private fun rememberBackupDirectory(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persisted = runCatching {
            activity.contentResolver.takePersistableUriPermission(uri, flags)
        }.isSuccess
        // provider 不给持久化授权时仍留在内存里：本次运行可用，重启后再授权一次
        sessionDirectory = uri
        if (persisted) storage.putString(StorageKeys.SAF_BACKUP_DIR_URI, uri.toString())
    }

    private var pickCallback: ((Uri?) -> Unit)? = null
    private val pickLauncher = activity.registerForActivityResult(DocumentUriContract()) { uri ->
        val cb = pickCallback
        pickCallback = null
        uri?.let { rememberLocation(it) }
        cb?.invoke(uri)
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

    // 尽力而为：picker 认不认这个初始位置随系统（Android 12 起它本身已没有目录浏览入口）。
    private fun putInitialLocation(intent: Intent) {
        initialLocation()?.let { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
    }

    // 导入侧只求「尽量靠近上次的位置」：picker 本身不保证，存个父目录 Uri 尽力而为。
    private fun rememberLocation(uri: Uri) {
        storage.putString(StorageKeys.SAF_LAST_URI, (documentParent(uri) ?: uri).toString())
    }

    private fun initialLocation(): Uri? =
        storage.getString(StorageKeys.SAF_LAST_URI, "")
            .takeIf { it.isNotEmpty() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

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
 * SAF 透传合约：Intent 由调用方组装，只解析返回的 Uri。
 *
 * 系统自带的合约把 intent 拼死了（只设 type 与 EXTRA_TITLE），没留口子加 extra。
 */
private class DocumentUriContract : ActivityResultContract<Intent, Uri?>() {
    override fun createIntent(context: Context, input: Intent): Intent = input

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        if (resultCode != Activity.RESULT_OK) return null
        return intent?.data
    }
}
