package com.mhd.sports

import android.util.Base64
import java.net.URLDecoder

/** Chuẩn hoá (giải mã) link nguồn trước khi tải/phát. Không còn lưu link người dùng nhập. */
object UrlTools {
    data class Decoded(val url: String, val headers: Map<String, String> = emptyMap())

    private val BASE64_LIKE = Regex("""^[A-Za-z0-9+/_-]{16,}={0,2}$""")
    private val DIRECT_EXT = Regex("""(?i)\.(flv|mp4|ts|mkv|webm)(\?|$)""")

    // ---------- giải mã URL ----------
    /**
     * Chuẩn hoá link nguồn:
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
