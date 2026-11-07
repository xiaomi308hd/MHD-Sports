package com.mhd.sports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Nhận link (có thể rút gọn) và đọc ra danh sách kênh/trận.
 * Hỗ trợ: JSON (nhiều dạng), M3U/M3U8 danh sách kênh, hoặc 1 link m3u8 phát trực tiếp.
 */
object SourceLoader {
    private val URL_KEYS = listOf("url", "link", "stream", "stream_url", "src", "m3u8", "source", "play_url")
    private val ATTR = Regex("""([\w-]+)="([^"]*)"""")
    private val FORMATS = listOf(
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm",
        "dd/MM/yyyy HH:mm", "HH:mm"
    )

    suspend fun load(url: String): List<Channel> = withContext(Dispatchers.IO) {
        Net.client.newCall(Net.req(url)).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val finalUrl = r.request.url.toString()
            val text = (r.body?.string() ?: "").trim().removePrefix("\uFEFF")
            parse(text, finalUrl)
        }
    }

    fun parse(t: String, finalUrl: String): List<Channel> =
        parseRaw(t, finalUrl).map { enrich(it) }

    // Nguồn hay nhét giờ vào tên, ví dụ "15:30 06/11 ⚽ A vs B" => tự tách ra làm giờ bắt đầu
    private val CLEAN = Regex("""[\p{So}\p{Cs}\uFE0F\u200D]""")
    private val TIME_FIRST = Regex("""(\d{1,2})[:h](\d{2})\s*[-,]?\s*(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?""")
    private val DATE_FIRST = Regex("""(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?\s*[-,]?\s*(\d{1,2})[:h](\d{2})""")

    private fun enrich(c: Channel): Channel {
        var name = c.name
        var start = c.start
        if (start == null) {
            val a = TIME_FIRST.find(name)
            val b = if (a == null) DATE_FIRST.find(name) else null
            val m = a ?: b
            if (m != null) {
                val g = m.groupValues
                val parts = if (a != null) listOf(g[1], g[2], g[3], g[4], g[5]) else listOf(g[4], g[5], g[1], g[2], g[3])
                start = buildTime(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), parts[4])
                if (start != null) name = name.replace(m.value, "")
            }
        }
        name = name.replace(CLEAN, "").replace(Regex("\\s+"), " ").trim().trimStart('-', '|', ':', ' ')
        var url = c.url
        var hdrs = c.headers
        val bar = url.indexOf('|')
        if (bar > 0) { // kiểu Kodi: https://...m3u8|User-Agent=abc&Referer=https://site
            val m = linkedMapOf<String, String>()
            url.substring(bar + 1).split('&').forEach { kv ->
                val i = kv.indexOf('=')
                if (i > 0) m[kv.substring(0, i).trim()] =
                    runCatching { java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8") }.getOrDefault(kv.substring(i + 1))
            }
            url = url.substring(0, bar)
            hdrs = m + hdrs
        }
        return c.copy(name = name.ifBlank { c.name }, start = start, url = url, headers = hdrs)
    }

    private fun buildTime(h: Int, mi: Int, d: Int, mo: Int, y: String): Long? {
        if (h > 23 || mi > 59 || d !in 1..31 || mo !in 1..12) return null
        val now = System.currentTimeMillis()
        val year = if (y.isNotEmpty()) y.toInt().let { if (it < 100) 2000 + it else it }
                   else Calendar.getInstance().get(Calendar.YEAR)
        fun at(yr: Int) = Calendar.getInstance().apply {
            clear(); set(yr, mo - 1, d, h, mi, 0)
        }.timeInMillis
        val t = at(year)
        // Không ghi năm mà giờ đã lùi quá xa (ví dụ tháng 1 đọc vào tháng 12) => hiểu là năm sau
        return if (y.isEmpty() && t < now - 200L * 86_400_000L) at(year + 1) else t
    }

    private fun parseRaw(t: String, finalUrl: String): List<Channel> = when {
        t.startsWith("{") || t.startsWith("[") -> parseJson(t)
        t.startsWith("#EXTM3U") && !t.contains("#EXT-X-") -> parseM3u(t)
        t.startsWith("#EXTM3U") -> listOf(Channel("Kênh của bạn", null, finalUrl, "Của tôi", null, null))
        else -> error("Không nhận ra định dạng (cần m3u8 hoặc JSON)")
    }

    // ---------- JSON ----------
    private fun parseJson(t: String): List<Channel> {
        val out = mutableListOf<Channel>()
        walk(JSONTokener(t).nextValue(), "Khác", out)
        return out
    }

    private fun JSONObject.any(vararg ks: String): Any? =
        ks.firstNotNullOfOrNull { k -> opt(k).takeIf { it != null && it != JSONObject.NULL } }

    private fun JSONObject.str(vararg ks: String): String? =
        ks.firstNotNullOfOrNull { k -> optString(k, "").takeIf { it.isNotBlank() && it != "null" } }

    private fun jsonHeaders(n: JSONObject): Map<String, String> {
        val h = linkedMapOf<String, String>()
        (n.opt("headers") as? JSONObject)?.let { o ->
            for (k in o.keys()) o.optString(k).takeIf { it.isNotBlank() }?.let { h[k] = it }
        }
        n.str("referer", "referrer", "http_referrer")?.let { h["Referer"] = it }
        n.str("origin")?.let { h["Origin"] = it }
        n.str("user_agent", "useragent", "ua", "user-agent")?.let { h["User-Agent"] = it }
        n.str("cookie")?.let { h["Cookie"] = it }
        return h
    }

    private fun walk(n: Any?, group: String, out: MutableList<Channel>) {
        when (n) {
            is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i), group, out)
            is JSONObject -> {
                val url = URL_KEYS.firstNotNullOfOrNull { k -> n.str(k)?.takeIf { it.startsWith("http") } }
                if (url != null) {
                    out += Channel(
                        name = n.str("name", "title", "channel", "match") ?: "Kênh",
                        logo = n.str("logo", "thumbnail", "thumb", "image", "icon", "poster"),
                        url = url,
                        group = n.str("group", "category", "league", "group_title") ?: group,
                        start = parseTime(n.any("start", "start_time", "time", "begin", "kickoff")),
                        end = parseTime(n.any("end", "end_time", "stop")),
                        headers = jsonHeaders(n)
                    )
                } else {
                    val g = n.str("group_name", "group", "category", "name", "title") ?: group
                    for (k in n.keys()) {
                        val v = n.opt(k)
                        if (v is JSONArray || v is JSONObject) walk(v, g, out)
                    }
                }
            }
        }
    }

    // ---------- M3U ----------
    private fun parseM3u(t: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var meta: Map<String, String>? = null
        var name = ""
        var hdr = linkedMapOf<String, String>()
        for (raw in t.lines()) {
            val l = raw.trim()
            if (l.startsWith("#EXTINF")) {
                meta = ATTR.findAll(l).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                name = l.substringAfterLast(",").trim()
            } else if (l.startsWith("#EXTVLCOPT:")) {
                val kv = l.removePrefix("#EXTVLCOPT:")
                val v = kv.substringAfter('=', "")
                when (kv.substringBefore('=').lowercase()) {
                    "http-referrer", "http-referer" -> hdr["Referer"] = v
                    "http-user-agent" -> hdr["User-Agent"] = v
                    "http-origin" -> hdr["Origin"] = v
                }
            } else if (l.isNotEmpty() && !l.startsWith("#")) {
                val m = meta ?: continue
                out += Channel(
                    name = name.ifBlank { m["tvg-name"] ?: "Kênh" },
                    logo = m["tvg-logo"] ?: m["logo"],
                    url = l,
                    group = m["group-title"] ?: "Khác",
                    start = parseTime(m["start"] ?: m["tvg-start"]),
                    end = parseTime(m["end"]),
                    headers = hdr
                )
                meta = null; hdr = linkedMapOf()
            }
        }
        return out
    }

    // ---------- Thời gian ----------
    fun parseTime(v: Any?): Long? {
        when (v) {
            is Number -> return v.toLong().let { if (it < 100_000_000_000L) it * 1000 else it }
            is String -> {
                val s = v.trim()
                s.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000 else it }
                for (p in FORMATS) {
                    try {
                        val d = SimpleDateFormat(p, Locale.US).apply { isLenient = false }.parse(s) ?: continue
                        if (p == "HH:mm") { // chỉ có giờ => hiểu là hôm nay
                            val tm = Calendar.getInstance().apply { time = d }
                            return Calendar.getInstance().apply {
                                set(Calendar.HOUR_OF_DAY, tm.get(Calendar.HOUR_OF_DAY))
                                set(Calendar.MINUTE, tm.get(Calendar.MINUTE))
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }.timeInMillis
                        }
                        return d.time
                    } catch (_: Exception) { }
                }
            }
        }
        return null
    }
}
