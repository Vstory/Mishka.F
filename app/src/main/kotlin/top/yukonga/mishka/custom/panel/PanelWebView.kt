package top.yukonga.mishka.custom.panel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.mishka.R
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 两阶段 WebView 预热器（box.app 同款）：
 *
 * 阶段 1（后台线程，立即）：`CookieManager.getInstance()` 触发 Chromium native library 加载；
 * 阶段 2（主线程，空闲时）：预创建 WebView 实例并暂存，不加载任何 URL、零历史。
 *
 * 面板每次**进入**都是新 WebView + `loadUrl(入口)`（box.app 的返回进入逻辑），
 * `new WebView()` 的 ~500ms 冷启是这套逻辑唯一的构造开销，预热后取用近乎 0ms。
 * 用 WeakReference 暂存，避免静态持有 WebView 造成泄漏。
 */
internal object WebViewPreloader {
    @Volatile
    private var prewarmedRef: java.lang.ref.WeakReference<WebView>? = null

    @Volatile
    private var providerLoaded = false

    /** 在 Activity.onCreate 调：先后台加载 Chromium provider，再在主线程空闲时预创建实例。 */
    fun preload(context: Context) {
        if (providerLoaded) return
        providerLoaded = true
        Thread {
            runCatching { CookieManager.getInstance() }
            Handler(Looper.getMainLooper()).post {
                Looper.myQueue().addIdleHandler {
                    if (prewarmedRef?.get() == null) {
                        runCatching {
                            val wv = WebView(context.applicationContext).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                            }
                            prewarmedRef = java.lang.ref.WeakReference(wv)
                        }
                    }
                    false
                }
            }
        }.start()
    }

    /** 取出预热的 WebView（一次性）；未就绪或已被 GC 回收则返回 null，调用方 fallback 到新建。 */
    fun take(): WebView? {
        val wv = prewarmedRef?.get()
        prewarmedRef = null
        return wv
    }
}

/**
 * 清除面板 WebView 的全部站点数据：缓存 / Cookie / Storage / 表单 / SSL 偏好。
 *
 * 必须在主线程跑：`WebView(context)` 构造会触碰 Chromium 的 UI 侧初始化，
 * 换到 IO 线程去调只会拿到一屏 `WebView` 未附加的 logcat 噪声、清不掉东西。
 */
suspend fun clearWebViewAppData(context: Context): Boolean = withContext(Dispatchers.Main.immediate) {
    runCatching {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        runCatching { WebStorage.getInstance().deleteAllData() }
        WebView(context).apply {
            clearCache(true)
            clearHistory()
            clearFormData()
            clearSslPreferences()
            destroy()
        }
    }.isSuccess
}

/**
 * 劫持 `<a download>` 的点击：面板里的「导出配置」是先 `URL.createObjectURL` 造 blob
 * 再点一下隐藏链接，而 WebView 的 DownloadListener **收不到 blob:/data: 开头的地址**
 * （没有 http 请求可拦）。所以这两类地址改成 fetch → base64 → JS bridge 走 SAF 存盘。
 */
private const val DOWNLOAD_HOOK_JS = """
(function () {
  if (window.__mishkaDownloadHookInstalled) return;
  window.__mishkaDownloadHookInstalled = true;
  function toBase64(blob) {
    return new Promise(function (resolve, reject) {
      var reader = new FileReader();
      reader.onloadend = function () {
        var result = reader.result || '';
        var idx = result.indexOf(',');
        resolve(idx >= 0 ? result.substring(idx + 1) : '');
      };
      reader.onerror = reject;
      reader.readAsDataURL(blob);
    });
  }
  var originalClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    var anchor = this;
    if (anchor.download) {
      var href = anchor.getAttribute('href') || anchor.href || '';
      if (href.indexOf('blob:') === 0 || href.indexOf('data:') === 0) {
        fetch(href)
          .then(function (r) { return r.blob(); })
          .then(function (blob) {
            return toBase64(blob).then(function (b64) {
              window.MishkaAndroid.saveBase64File(
                anchor.download || 'export',
                blob.type || 'application/octet-stream',
                b64
              );
            });
          })
          .catch(function () {});
        return;
      }
    }
    return originalClick.apply(this, arguments);
  };
})();
"""

private data class TagState(
    val reloadKey: Int,
    val url: String,
    val backRequestKey: Int,
)

/**
 * 外部面板用的 WebView——box.app `ThemedWebView` 的忠实移植（返回进入逻辑整套照搬）：
 *
 *  - **每次进入都是新实例 + `loadUrl(入口 URL)`**（box.app 的进入逻辑：重进总是面板首页）；
 *  - **返回先退页内历史**（[backRequestKey] → `goBack`），退无可退由宿主退出页面；
 *  - **防白闪 = [hideUntilCommitVisible]**：内容提交（`onPageCommitVisible`）前 WebView
 *    保持隐藏，只露底色——加载期永远不露出白帧，这是 box.app 组件自带的防闪机制；
 *  - **[resetHistoryOnUrlChange]**：换 [url]（切面板）加载完 `clearHistory()`，返回不会
 *    走进上一个面板的历史；
 *  - `key(isDark, sessionKey)` 重建（box.app 同款）：换面板 / 换深浅色都换实例。
 *
 * 与 box.app 的差异（都为修 bug，行为只强不弱）：
 *  1. `doUpdateVisitedHistory` 转发 canGoBack——SPA 的 pushState/回退不触发 `onPageFinished`，
 *     没有它「返回先退页内历史」在面板子页里永远不生效（box.app 漏了这个回调）；
 *  2. http(s) 导航交回 WebView（`return false`，保留 POST 语义），其余 scheme 交系统
 *     （box.app 是一律 `loadUrl` 重放，POST 会丢）；
 *  3. `onRelease` 里 `destroy()`（box.app 丢给 GC，顺手防泄漏，对外行为一致）。
 *
 * @param reloadKey 自增即触发一次 `reload()`
 * @param sessionKey 自增即销毁重建 WebView（切面板时用）
 * @param backRequestKey 自增即 `goBack()`（供返回手势/系统返回回调）
 * @param hideUntilCommitVisible true 时内容提交前隐藏 WebView（去白闪），默认开启
 * @param resetHistoryOnUrlChange true 时换 URL 加载完清 history（返回不进上一个面板）
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PanelWebView(
    url: String,
    isDark: Boolean,
    reloadKey: Int,
    sessionKey: Int = 0,
    backRequestKey: Int = 0,
    modifier: Modifier = Modifier,
    hideUntilCommitVisible: Boolean = true,
    resetHistoryOnUrlChange: Boolean = true,
    onPageFinished: ((String?) -> Unit)? = null,
    onTitleChange: ((String?) -> Unit)? = null,
    onWebError: ((String?) -> Unit)? = null,
    onCanGoBackChange: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val surfaceColor = if (isDark) Color.BLACK else Color.WHITE

    // 内容是否已提交可见（hideUntilCommitVisible 的开关）。放稳定 holder、key 化重置：
    // 记忆化 WebViewClient 闭包抓 State 实例，remember(url…) 换实例会让回调写到旧对象上
    val contentVisible = remember { mutableStateOf(!hideUntilCommitVisible) }
    LaunchedEffect(url, isDark, hideUntilCommitVisible) {
        contentVisible.value = !hideUntilCommitVisible
    }
    // 换 URL 的加载完成后清一次 history（box.app resetHistoryOnUrlChange）
    val clearHistoryAfterNextPageFinished = remember { AtomicBoolean(false) }

    // Cookie 持久化（登录态跨会话）
    val cookieManager = remember { CookieManager.getInstance() }
    DisposableEffect(cookieManager) {
        onDispose { runCatching { cookieManager.flush() } }
    }

    data class PendingSave(
        val displayName: String,
        val mimeType: String,
        val bytes: ByteArray,
    )

    data class PendingHttpDownload(
        val url: String,
        val fileName: String,
        val mimeType: String,
        val userAgent: String,
        val cookie: String?,
        val referer: String,
    )

    var pendingSave by remember { mutableStateOf<PendingSave?>(null) }
    var pendingHttpDownload by remember { mutableStateOf<PendingHttpDownload?>(null) }
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    // 回调包一层 rememberUpdatedState：clients 只在 remember 里建一次，直接闭包捕获的话
    // 屏幕重组后就一直调的是首帧那批 lambda（拿到的都是旧快照）。
    // 这两条是 app 自己的提示，跟 WebView 报的英文错误混在一起显示，必须跟着系统语言
    val currentSaveFailed = rememberUpdatedState(stringResource(R.string.panel_save_failed))
    val currentNoFilePicker = rememberUpdatedState(stringResource(R.string.panel_no_file_picker))
    val currentOnPageFinished = rememberUpdatedState(onPageFinished)
    val currentOnTitleChange = rememberUpdatedState(onTitleChange)
    val currentOnWebError = rememberUpdatedState(onWebError)
    val currentOnCanGoBack = rememberUpdatedState(onCanGoBackChange)

    val saveFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val outUri = result.data?.data
        val payload = pendingSave
        val httpPayload = pendingHttpDownload
        pendingSave = null
        pendingHttpDownload = null
        if (outUri != null && httpPayload != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        // 与面板 WebView 同一出口（mixed-port），否则下载会直连拿到国内出口
                        val conn = (URL(httpPayload.url).openConnection(PanelProxyOverride.javaProxy()) as HttpURLConnection).apply {
                            instanceFollowRedirects = true
                            setRequestProperty("User-Agent", httpPayload.userAgent)
                            setRequestProperty("Referer", httpPayload.referer)
                            httpPayload.cookie?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
                        }
                        conn.inputStream.use { input ->
                            context.contentResolver.openOutputStream(outUri)?.use { output ->
                                input.copyTo(output)
                            } ?: error("openOutputStream failed")
                        }
                        runCatching { conn.disconnect() }
                    }
                }.onFailure {
                    currentOnWebError.value?.invoke(currentSaveFailed.value)
                }
            }
            return@rememberLauncherForActivityResult
        }
        if (outUri == null || payload == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(outUri)?.use { it.write(payload.bytes) }
        }.onFailure {
            currentOnWebError.value?.invoke(currentSaveFailed.value)
        }
    }

    fun normalizeAcceptType(raw: String?): String {
        val v = raw?.trim().orEmpty()
        if (v.isBlank() || v == "*/*") return "*/*"
        if (v.startsWith(".")) {
            val ext = v.removePrefix(".").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            return mime ?: "*/*"
        }
        return v
    }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cb = pendingFileCallback
        pendingFileCallback = null
        if (cb == null) return@rememberLauncherForActivityResult
        val uris = ArrayList<Uri>()
        val clip = result.data?.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let { uris.add(it) }
        } else {
            result.data?.data?.let { uris.add(it) }
        }
        cb.onReceiveValue(uris.toTypedArray().takeIf { it.isNotEmpty() })
    }

    // 回调的 rememberUpdatedState 见上（在 launcher 之前声明，供其错误提示用）

    val jsBridge = remember {
        object {
            /** JS 线程回调，不在主线程；SAF 的 launch 必须切回主线程再调。 */
            @JavascriptInterface
            fun saveBase64File(filename: String?, mimeType: String?, base64: String?) {
                val rawName = filename?.takeIf { it.isNotBlank() } ?: "export"
                val mime = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                    ?: when (mime) {
                        "application/json" -> "json"
                        "application/yaml" -> "yaml"
                        "text/plain" -> "txt"
                        else -> "bin"
                    }
                val name = if (rawName.contains('.')) rawName.trimEnd('.') else "$rawName.$ext"
                val data = runCatching { Base64.decode(base64 ?: return, Base64.DEFAULT) }.getOrNull() ?: return
                pendingSave = PendingSave(displayName = name, mimeType = mime, bytes = data)
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mime
                    putExtra(Intent.EXTRA_TITLE, name)
                }
                Handler(Looper.getMainLooper()).post {
                    runCatching { saveFileLauncher.launch(intent) }
                }
            }
        }
    }

    val viewClient = remember {
        object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request?.isForMainFrame != true) return false
                val uri = request.url ?: return false
                val scheme = uri.scheme.orEmpty()
                // http(s) 交回 WebView 自己加载（保留重定向与 POST 语义）；
                // 其余 scheme（intent://、market://、tg:// ...）交给系统，别在面板里空转
                if (scheme == "http" || scheme == "https") return false
                return runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
                    true
                }.getOrDefault(false)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                // 防白闪的关键帧：真实内容已提交，可以露出来了
                contentVisible.value = true
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                // SPA 的 pushState / popstate 不触发 onPageFinished，canGoBack 只能从这里跟上
                currentOnCanGoBack.value?.invoke(view?.canGoBack() == true)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                contentVisible.value = true
                // 换 URL 的那次加载完成后清 history：canGoBack 回报的是清完后的值
                if (clearHistoryAfterNextPageFinished.getAndSet(false)) {
                    runCatching { view?.clearHistory() }
                }
                // 加载成功即清掉上一次的错误横幅：否则一个 404 会一直挂在页面上
                currentOnWebError.value?.invoke(null)
                currentOnPageFinished.value?.invoke(url)
                currentOnCanGoBack.value?.invoke(view?.canGoBack() == true)
                runCatching { cookieManager.flush() }
                view?.evaluateJavascript(DOWNLOAD_HOOK_JS, null)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    currentOnWebError.value?.invoke(error?.description?.toString()?.ifBlank { null })
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                if (request?.isForMainFrame == true) {
                    val code = errorResponse?.statusCode
                    if (code != null) currentOnWebError.value?.invoke("HTTP $code")
                }
            }
        }
    }

    val chromeClient = remember {
        object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                currentOnTitleChange.value?.invoke(title?.ifBlank { null })
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback

                val intent = try {
                    fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                } catch (_: Throwable) {
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                }

                // 有些站点把 ".json" 这类后缀当 type 传，归一成 MIME
                val accept = normalizeAcceptType(fileChooserParams?.acceptTypes?.firstOrNull())
                if (intent.type.isNullOrBlank() || intent.type?.startsWith(".") == true) {
                    intent.type = accept
                }

                val canHandle = intent.resolveActivity(context.packageManager) != null
                if (!canHandle) {
                    pendingFileCallback?.onReceiveValue(null)
                    pendingFileCallback = null
                    currentOnWebError.value?.invoke(currentNoFilePicker.value)
                    return true
                }
                try {
                    filePickerLauncher.launch(intent)
                } catch (_: Throwable) {
                    pendingFileCallback?.onReceiveValue(null)
                    pendingFileCallback = null
                }
                return true
            }
        }
    }

    val downloadListener = remember {
        DownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
            // DownloadManager 收不到 blob:/data:，那两类走 JS bridge
            if (downloadUrl.startsWith("blob:") || downloadUrl.startsWith("data:")) return@DownloadListener

            val fileName = URLUtil.guessFileName(downloadUrl, contentDisposition, mimeType)
            val mime = mimeType?.ifBlank { "application/octet-stream" } ?: "application/octet-stream"
            pendingHttpDownload = PendingHttpDownload(
                url = downloadUrl,
                fileName = fileName,
                mimeType = mime,
                userAgent = userAgent ?: "",
                cookie = CookieManager.getInstance().getCookie(downloadUrl),
                referer = url,
            )
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
                putExtra(Intent.EXTRA_TITLE, fileName)
            }
            Handler(Looper.getMainLooper()).post {
                runCatching { saveFileLauncher.launch(intent) }
            }
        }
    }

    // key 照 box.app：isDark / sessionKey 变化都重建实例（换面板、换深浅色）
    key(isDark, sessionKey) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(if (isDark) ComposeColor.Black else ComposeColor.White),
        ) {
            AndroidView(
                factory = { ctx ->
                    // 先取到面板入口 URL：apply 的 receiver 是 WebView，它自己就有个
                    // getUrl() 属性，里面裸写 `url` 会被它遮蔽成「当前页地址」
                    val entryUrl = url
                    // 每次进入都是新实例 + 加载入口（box.app 进入逻辑：重进总是面板首页）；
                    // 预热器负责把 new WebView() 的 ~500ms 冷启压掉
                    (WebViewPreloader.take() ?: WebView(ctx)).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )

                        // 别让外层容器（弹层 / 滑动容器）抢走面板里的纵向滑动手势
                        setOnTouchListener { v, event ->
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN,
                                MotionEvent.ACTION_MOVE -> v.parent?.requestDisallowInterceptTouchEvent(true)
                                MotionEvent.ACTION_UP,
                                MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                            false
                        }

                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        addJavascriptInterface(jsBridge, "MishkaAndroid")
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            @Suppress("DEPRECATION")
                            databaseEnabled = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            allowFileAccess = true
                            allowContentAccess = true
                            javaScriptCanOpenWindowsAutomatically = true
                            blockNetworkLoads = false
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            // 防止系统字体缩放把桌面端面板布局撑爆
                            textZoom = 100
                            // API 33+ 的算法深色会自动跳过已支持 prefers-color-scheme 的站点；
                            // 29-32 只有已废弃的 forceDark 可用，再往下系统 WebView 没有深色能力
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                isAlgorithmicDarkeningAllowed = isDark
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                @Suppress("DEPRECATION")
                                forceDark = if (isDark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
                            }
                        }

                        webViewClient = viewClient
                        webChromeClient = chromeClient
                        setDownloadListener(downloadListener)

                        // 不透明底色，避免导航转场合成路径下的渲染冲突（box.app 同款）
                        setBackgroundColor(if (isDark) Color.BLACK else Color.WHITE)

                        loadUrl(entryUrl)
                        tag = TagState(reloadKey = reloadKey, url = entryUrl, backRequestKey = backRequestKey)
                    }
                },
                update = { webView ->
                    // 深色模式同步（每次 isDark 变化重新应用；实例本身在 key(isDark,…) 已重建）
                    webView.settings.apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (isAlgorithmicDarkeningAllowed != isDark) isAlgorithmicDarkeningAllowed = isDark
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            @Suppress("DEPRECATION")
                            val target = if (isDark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
                            @Suppress("DEPRECATION")
                            if (forceDark != target) forceDark = target
                        }
                    }
                    webView.setBackgroundColor(if (isDark) Color.BLACK else Color.WHITE)

                    // 防白闪：内容提交前隐藏 WebView（露底色），提交后才露真身
                    if (hideUntilCommitVisible) {
                        webView.alpha = if (contentVisible.value) 1f else 0f
                        webView.visibility = if (contentVisible.value) WebView.VISIBLE else WebView.INVISIBLE
                    } else {
                        webView.alpha = 1f
                        webView.visibility = WebView.VISIBLE
                    }

                    val last = webView.tag as? TagState
                    val urlChanged = last?.url != url
                    val needsReload = last?.reloadKey != reloadKey
                    val backRequested = last?.backRequestKey != backRequestKey
                    if (urlChanged) {
                        contentVisible.value = !hideUntilCommitVisible
                        if (resetHistoryOnUrlChange) {
                            clearHistoryAfterNextPageFinished.set(true)
                            currentOnCanGoBack.value?.invoke(false)
                        }
                        webView.loadUrl(url)
                        webView.tag = TagState(reloadKey, url, backRequestKey)
                    } else if (needsReload) {
                        contentVisible.value = !hideUntilCommitVisible
                        webView.reload()
                        webView.tag = TagState(reloadKey, url, backRequestKey)
                    } else if (backRequested) {
                        if (webView.canGoBack()) webView.goBack()
                        currentOnCanGoBack.value?.invoke(webView.canGoBack())
                        webView.tag = TagState(reloadKey, url, backRequestKey)
                    }
                },
                onRelease = { released ->
                    // box.app 是丢给 GC；这里顺手 destroy 防泄漏，对外行为一致（退出即弃）
                    runCatching {
                        released.stopLoading()
                        released.destroy()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}