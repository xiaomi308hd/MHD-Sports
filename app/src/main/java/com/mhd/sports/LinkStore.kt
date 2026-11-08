package com.mhd.sports

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Lưu danh sách link do người dùng nhập và chuẩn hoá (giải mã) link trước khi tải/phát.
 * Link được lưu cục bộ trong SharedPreferences "links" của ứng dụng.
 */
object LinkStore {
    data class Saved(val name: String, val url: String)
    data class Decoded(val url: String, val headers: Map<String, String> = emptyMap())

    private const val PREF = "links"
    private const val KEY_LIST = "list"
    private const val KEY_ACTIVE = "active"

    private val BASE64_LIKE = Regex("""^[A-Za-z0-9+/_-]{16,}={0,2}$""")
    private val DIRECT_EXT = Regex("""(?i)\.(flv|mp4|ts|mkv|webm)(\?|$)""")

    // ---------- lưu trữ ----------
    private fun sp(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun list(c: Context): List<Saved> {
        val arr = runCatching { JSONArray(sp(c).getString(KEY_LIST, "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val u = o.optString("url")
            if (u.isBlank()) null else Saved(o.optString("name").ifBlank { shortName(u) }, u)
        }
    }

    private fun write(c: Context, items: List<Saved>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url)) }
        sp(c).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    /** Lưu (hoặc cập nhật) link và đặt làm link đang dùng. Trả về false nếu link không hợp lệ. */
    fun saveAndUse(c: Context, raw: String, name: String? = null): Boolean {
        val d = decode(raw)
        if (!d.url.startsWith("http", ignoreCase = true)) return false
        val kept = raw.trim()
        val items = list(c).filterNot { it.url == kept }.toMutableList()
        items.add(0, Saved(name?.takeIf { it.isNotBlank() } ?: shortName(d.url), kept))
        write(c, items.take(30))
        sp(c).edit().putString(KEY_ACTIVE, kept).apply()
        return true
    }

    fun use(c: Context, url: String?) {
        if (url == null) sp(c).edit().remove(KEY_ACTIVE).apply()
        else sp(c).edit().putString(KEY_ACTIVE, url).apply()
    }

    fun remove(c: Context, url: String) {
        write(c, list(c).filterNot { it.url == url })
        if (active(c) == url) use(c, null)
    }

    /** Link người dùng đang chọn (dạng gốc đã lưu), hoặc null => dùng nguồn mặc định. */
    fun active(c: Context): String? = sp(c).getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() }

    private fun shortName(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: "Link của tôi"

    // ---------- giải mã URL ----------
    /**
     * Chuẩn hoá link người dùng dán vào:
     *  - bỏ khoảng trắng, dấu ngoặc kép, xuống dòng
     *  - "b64:..." / "mhd://..." hoặc chuỗi base64 thuần => giải base64 ra URL
     *  - URL bị mã hoá phần trăm (http%3A%2F%2F...) => giải mã
     *  - github.com/.../blob/... => raw.githubusercontent.com
     *  - hậu tố "|Referer=...&User-Agent=..." (kiểu Kodi/VLC) => tách thành header
     */
    fun decode(raw: String): Decoded {
        var s = raw.trim().trim('"', '\'').replace("\n", "").replace("\r", "").trim()
        if (s.isEmpty()) return Decoded("")

        val lower = s.lowercase()
        when {
            lower.startsWith("b64:") -> s = unBase64(s.substring(4)) ?: s
            lower.startsWith("mhd://") -> s = unBase64(s.substring(6)) ?: s
            !s.contains("://") && BASE64_LIKE.matches(s) ->
                unBase64(s)?.takeIf { it.startsWith("http", true) }?.let { s = it }
        }
        if (!s.contains("://") || s.contains("%3A%2F", ignoreCase = true) || s.contains("%2F%2F", ignoreCase = true)) {
            s = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
        }

        // hậu tố header: url|Key=Value&Key2=Value2
        val headers = linkedMapOf<String, String>()
        if (s.contains("|")) {
            val tail = s.substringAfter("|")
            s = s.substringBefore("|")
            tail.split("&").forEach { kv ->
                val k = kv.substringBefore("=", "").trim()
                val v = runCatching { URLDecoder.decode(kv.substringAfter("=", ""), "UTF-8") }.getOrDefault("")
                if (k.isNotEmpty() && v.isNotEmpty()) headers[normalizeHeader(k)] = v
            }
        }

        // github blob -> raw
        Regex("""^https?://github\.com/([^/]+)/([^/]+)/blob/(.+)$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let {
            s = "https://raw.githubusercontent.com/${it.groupValues[1]}/${it.groupValues[2]}/${it.groupValues[3]}"
        }
        return Decoded(s.trim(), headers)
    }

    private fun normalizeHeader(k: String) = when (k.lowercase().replace("_", "-")) {
        "referer", "referrer", "http-referrer" -> "Referer"
        "user-agent", "useragent", "ua" -> "User-Agent"
        "origin" -> "Origin"
        "cookie" -> "Cookie"
        else -> k
    }

    private fun unBase64(v: String): String? = runCatching {
        String(Base64.decode(v.trim(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
    }.recoverCatching {
        String(Base64.decode(v.trim(), Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()?.trim()

    /** Link trỏ thẳng tới luồng (flv/mp4/ts...) chứ không phải danh sách để đọc. */
    fun isDirectStream(url: String): Boolean = DIRECT_EXT.containsMatchIn(url.substringBefore('#'))
}
