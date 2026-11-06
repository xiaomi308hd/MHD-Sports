package com.sportsplayer.app

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

    fun parse(t: String, finalUrl: String): List<Channel> = when {
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
                        end = parseTime(n.any("end", "end_time", "stop"))
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
        for (raw in t.lines()) {
            val l = raw.trim()
            if (l.startsWith("#EXTINF")) {
                meta = ATTR.findAll(l).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                name = l.substringAfterLast(",").trim()
            } else if (l.isNotEmpty() && !l.startsWith("#")) {
                val m = meta ?: continue
                out += Channel(
                    name = name.ifBlank { m["tvg-name"] ?: "Kênh" },
                    logo = m["tvg-logo"] ?: m["logo"],
                    url = l,
                    group = m["group-title"] ?: "Khác",
                    start = parseTime(m["start"] ?: m["tvg-start"]),
                    end = parseTime(m["end"])
                )
                meta = null
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
