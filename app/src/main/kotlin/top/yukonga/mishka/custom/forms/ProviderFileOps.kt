package top.yukonga.mishka.custom.forms

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.mishka.platform.showToast
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * file 类型合集的「源文件」操作（对齐 mihomo_box `fileOpsCtl` 的 Android 版）：上传（SAF，二进制安全，
 * 支持 .mrs）/ 在线编辑。相对路径按本订阅 imported/ 目录解析（mihomo 以工作目录启动，语义一致）；
 * 绝对路径原样用。写盘直接 java.io，不经编辑器草稿——源文件不是配置本身，内核按 path 单独读它。
 * 订阅源文件（kind=sub）保存前经 [ProxyUri.ensureProxiesRoot] 兜底补 `proxies:` 根。
 */

/** `path` 配置值 → 实际文件：绝对路径原样；相对去掉 `./` 挂到 imported/ 目录下；无基目录返回 null。 */
internal fun resolveProviderFile(baseDir: String?, cfgPath: String): File? {
    val p = cfgPath.trim()
    if (p.isEmpty()) return null
    if (p.startsWith("/")) return File(p)
    val b = baseDir?.trim() ?: return null
    if (b.isEmpty()) return null
    return File(b, p.removePrefix("./"))
}

internal const val MAX_EDIT_BYTES = 4L * 1024 * 1024
internal const val MAX_UPLOAD_BYTES = 8L * 1024 * 1024

@Composable
internal fun ProviderFileOpsRow(
    host: FormHost,
    base: YPath,
    kind: String,
    defaultPath: String,
    newFileText: String,
) {
    val doc = host.doc
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localPath = FormValues.readRaw(doc, base + "path")?.ifBlank { null }
    val cfgPath = localPath ?: defaultPath
    val file = remember(host.fileBaseDir, cfgPath) { resolveProviderFile(host.fileBaseDir, cfgPath) }
    val stat = remember(file, doc) { file?.takeIf { it.exists() }?.let { "${it.length()} 字节" } }
    val isMrs = cfgPath.endsWith(".mrs", ignoreCase = true)
    var editOpen by remember { mutableStateOf(false) }

    /** 源文件操作落地前确保配置里有 path（没有就写默认相对路径），返回实际目标文件。 */
    fun ensureTarget(): File? {
        if (localPath == null) host.set(base + "path", defaultPath, "本地路径")
        return resolveProviderFile(host.fileBaseDir, localPath ?: defaultPath)
    }

    /**
     * 打开在线编辑的统一守卫——卡片整行 onClick 与「编辑」按钮必须走同一条路：
     * 之前卡片点击绕过了守卫，订阅目录未知时弹层带着空目标打开，「保存文件」静默无反应。
     */
    fun tryOpenEdit() {
        when {
            isMrs -> showToast("mrs 是二进制格式，不支持在线编辑，请用「上传」替换", long = true)
            file != null && file.exists() && file.length() > MAX_EDIT_BYTES ->
                showToast("文件超过 4MB，请直接用文件管理器编辑", long = true)
            host.fileBaseDir == null && !cfgPath.startsWith("/") ->
                showToast("当前编辑器不在订阅目录里，无法定位源文件", long = true)
            else -> editOpen = true
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val target = ensureTarget()
        if (target == null) {
            showToast("无法确定写入位置：缺少订阅目录", long = true)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val buf = readBytesLimited(input, MAX_UPLOAD_BYTES + 1)
                        require(buf.size <= MAX_UPLOAD_BYTES) { "文件过大（>8MB）" }
                        target.parentFile?.mkdirs()
                        target.writeBytes(buf)
                    } ?: error("读取所选文件失败")
                }
            }
            result.fold(
                onSuccess = {
                    showToast("已上传 → ${target.path}")
                    // 与参考实现一致：.mrs 文件名自动把 format 切到 mrs
                    val name = target.name
                    if (name.endsWith(".mrs", ignoreCase = true) && FormValues.readRaw(doc, base + "format") != "mrs") {
                        host.set(base + "format", "mrs", "文件格式")
                    }
                },
                onFailure = { showToast("上传失败：${it.message ?: it::class.simpleName}", long = true) },
            )
        }
    }

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors()) {
        BasicComponent(
            title = "源文件",
            summary = buildString {
                append(cfgPath)
                if (localPath == null) append("（默认路径，操作时写入配置）")
                append(" · ")
                append(
                    when {
                        stat != null -> stat
                        file == null -> "无法定位文件（订阅目录未知）：可用「上传」，或在配置里填绝对路径"
                        else -> "文件不存在，上传 / 保存即创建"
                    },
                )
                if (isMrs) append(" · mrs 为二进制，只能上传替换")
            },
            endActions = {
                // 两个按钮收进一行并压紧最小宽度：默认 TextButton 的最小宽度会在窄屏上把摘要挤换行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(text = "上传", minWidth = 0.dp, onClick = { uploadLauncher.launch(arrayOf("*/*")) })
                    Spacer(Modifier.width(6.dp))
                    MiniIconButton(MiuixIcons.Edit, "编辑") { tryOpenEdit() }
                }
            },
            onClick = { tryOpenEdit() },
        )
    }

    if (editOpen) {
        FileContentDialog(
            title = if (kind == "ep") "编辑规则集文件" else "编辑订阅文件",
            file = file,
            baseDir = host.fileBaseDir,
            cfgPath = cfgPath,
            newFileText = newFileText,
            // 订阅源文件保存前自动补 proxies: 根（参考实现 ensureProxiesRoot；mrs 二进制不进编辑器）
            wrapProxies = kind == "sub",
            scope = scope,
            onDismiss = { editOpen = false },
            onSaved = {
                editOpen = false
                if (localPath == null) host.set(base + "path", defaultPath, "本地路径")
                showToast("已保存源文件")
            },
        )
    }
}

/** 在线编辑弹层：打开时 IO 读现有内容（不存在给模板），保存直接写文件。 */
@Composable
internal fun FileContentDialog(
    title: String,
    file: File?,
    baseDir: String?,
    cfgPath: String,
    newFileText: String,
    wrapProxies: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var draft by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val target = file ?: remember(baseDir, cfgPath) { resolveProviderFile(baseDir, cfgPath) }
    LaunchedEffect(target) {
        draft = withContext(Dispatchers.IO) {
            runCatching {
                target?.takeIf { it.exists() }?.readText() ?: newFileText
            }.getOrElse { failed = true; null }
        }
    }
    val text = draft
    WindowDialog(
        show = true,
        title = title,
        summary = target?.path ?: cfgPath,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (failed) DialogNote("读取文件失败，请在编辑器外检查权限。")
            TextField(
                value = text.orEmpty(),
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                label = "文件内容",
                singleLine = false,
                minLines = 8,
                maxLines = 16,
            )
            DialogButtons(onDismiss = onDismiss, confirmLabel = "保存文件") {
                val t = target
                when {
                    t == null -> showToast("无法确定写入位置：缺少订阅目录或路径为空，请先用「上传」或在配置里填绝对路径", long = true)
                    failed && draft == null -> showToast("文件内容没读到，不执行保存（避免把原文件清空）", long = true)
                    else -> {
                        // mihomo 要求订阅文件是 proxies 列表：只贴了节点列表 / 单节点时自动补根，其余内容不动
                        val content = if (wrapProxies) ProxyUri.ensureProxiesRoot(draft.orEmpty()).first else draft.orEmpty()
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { t?.parentFile?.mkdirs(); t?.writeText(content) }.isSuccess
                            }
                            if (ok) onSaved() else showToast("写入失败：请检查文件权限或磁盘空间", long = true)
                        }
                    }
                }
            }
        }
    }
}

internal fun readBytesLimited(input: java.io.InputStream, limit: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > limit) throw IllegalStateException("文件过大")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
