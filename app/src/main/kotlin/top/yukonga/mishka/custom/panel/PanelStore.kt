package top.yukonga.mishka.custom.panel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 面板列表持久化（对齐 box.app：SharedPreferences `panel_cache`，不上 Room）。
 *
 * 就几行展示偏好，进数据库要多一张表加一次迁移；而换设备/清数据时它跟 WebView 缓存一样
 * 属于「丢了重填即可」的东西。三份数据：自定义面板列表、当前选中的面板 id、上次解析到的
 * 本地面板地址（下次冷启动先拿它顶上，避免首屏空白）。
 */
object PanelStore {

    private const val PREFS_NAME = "panel_cache"
    private const val KEY_LIST = "panel_list_v1"
    private const val KEY_SELECTED = "panel_selected_id_v1"
    private const val KEY_LOCAL_URL = "panel_url_v1"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun customPanels(context: Context): List<PanelEntry> {
        val raw = prefs(context).getString(KEY_LIST, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id").trim()
                    val name = obj.optString("name").trim()
                    val url = obj.optString("url").trim().ifBlank { null }
                    if (id.isNotBlank() && name.isNotBlank() && url != null) {
                        add(PanelEntry(id = id, name = name, url = url, isBuiltIn = false))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveCustomPanels(context: Context, panels: List<PanelEntry>) {
        val array = JSONArray()
        panels.forEach { panel ->
            val obj = JSONObject()
            obj.put("id", panel.id)
            obj.put("name", panel.name)
            obj.put("url", panel.url)
            array.put(obj)
        }
        prefs(context).edit().putString(KEY_LIST, array.toString()).apply()
    }

    fun selectedId(context: Context): String =
        prefs(context).getString(KEY_SELECTED, null)
            ?.takeIf { it.isNotBlank() }
            ?: PANEL_ID_LOCAL

    fun saveSelectedId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_SELECTED, id).apply()
    }

    fun cachedLocalUrl(context: Context): String? =
        prefs(context).getString(KEY_LOCAL_URL, null)?.takeIf { it.isNotBlank() }

    fun cacheLocalUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_LOCAL_URL, url).apply()
    }
}