package com.mhd.sports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Đọc M3U/M3U8, JSON thông thường, JSON trận đấu Chuối Chiên và index.json nhiều nhà đài. */
object SourceLoader {
    data class Result(val channels: List<Channel>, val stationCount: Int, val stationNames: List<String>)
    private data class Document(val text: String, val finalUrl: String)

    private val URL_KEYS = listOf("url", "link", "stream", "stream_url", "src", "m3u8", "source", "play_url")
    private val ATTR = Regex("""([\w-]+)="([^"]*)"""")
    private val FORMATS = listOf(
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm",
        "dd/MM/yyyy HH:mm", "HH:mm"
    )

    suspend fun load(url: String): List<Channel> = loadCatalog(url).channels

    suspend fun loadCatalog(url: String): Result = withContext(Dispatchers.IO) {
        val root = fetch(url)
        val parsed = runCatching { JSONTokener(root.text).nextValue() }.getOrNull()
        if (parsed is JSONObject && parsed.optJSONArray("stations") != null) {
            val stations = parsed.optJSONArray("stations")!!
            val all = mutableListOf<Channel>()
            val names = mutableListOf<String>()
            for (i in 0 until stations.length()) {
                val station = stations.optJSONObject(i) ?: continue
                if (!station.optBoolean("enabled", true)) continue
                val stationName = station.optString("name").ifBlank { station.optString("id") }.ifBlank { "Nhà đài ${i + 1}" }
                val source = station.optString("url").trim()
                if (source.isBlank()) continue
                val stationUrl = resolveRelative(root.finalUrl, source)
                val doc = fetch(stationUrl)
                val stationLogo = station.optString("logo").takeIf { it.startsWith("http") }
                val items = parseDocument(doc.text, doc.finalUrl, stationName, stationLogo, forceStationGroup = true)
                if (items.isNotEmpty()) {
                    all += items
                    names += stationName
                }
            }
            if (all.isEmpty()) error("index.json hợp lệ nhưng chưa có nhà đài nào trả về trận đấu hoặc link phát")
            Result(all, names.size, names)
        } else {
            val stationName = inferStationName(root.finalUrl)
            val items = parseDocument(root.text, root.finalUrl, stationName, null)
            if (items.isEmpty()) error("JSON đã tải nhưng không tìm thấy trận đấu/kênh có link phát")
            Result(items, items.map { it.group }.distinct().size, items.map { it.group }.distinct())
        }
    }

    private fun fetch(url: String): Document {
        val requestUrl = cacheBusted(url)
        Net.client.newCall(Net.req(requestUrl)).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}: $url")
            val finalUrl = response.request.url.toString().substringBefore("?_mhd_refresh=").substringBefore("&_mhd_refresh=")
            val text = (response.body?.string() ?: "").trim().removePrefix("\uFEFF")
            if (text.isBlank()) error("Nguồn trả về nội dung trống: $url")
            return Document(text, finalUrl)
        }
    }

    private fun cacheBusted(url: String): String {
        if (!url.contains("raw.githubusercontent.com", true)) return url
        val separator = if (url.contains("?")) "&" else "?"
        return url + separator + "_mhd_refresh=" + System.currentTimeMillis()
    }

    private fun resolveRelative(base: String, value: String): String =
        runCatching { URI(base).resolve(value).toString() }.getOrDefault(value)

    private fun inferStationName(url: String): String = when {
        url.contains("Api-JK", true) || url.contains("sport.json", true) -> "CHUỐI CHIẾN TV"
        else -> "Nhà đài"
    }

    private fun parseDocument(
        text: String,
        finalUrl: String,
        stationName: String,
        stationLogo: String?,
        forceStationGroup: Boolean = false
    ): List<Channel> {
        val parsed = when {
            text.startsWith("{") || text.startsWith("[") -> parseJson(text, stationName, stationLogo, forceStationGroup)
            text.startsWith("#EXTM3U") && !text.contains("#EXT-X-") -> parseM3u(text)
            text.startsWith("#EXTM3U") -> listOf(Channel("Kênh của bạn", null, finalUrl, stationName, null, null, groupLogo = stationLogo))
            else -> error("Không nhận ra định dạng (cần JSON, M3U hoặc M3U8)")
        }
        return if (forceStationGroup) parsed.map { it.copy(group = stationName, groupLogo = stationLogo) } else parsed
    }

    private fun parseJson(text: String, stationName: String, stationLogo: String?, forceStationGroup: Boolean): List<Channel> {
        val root = JSONTokener(text).nextValue()
        if (root is JSONObject && root.optJSONArray("matches") != null) {
            return parseMatchApi(root, stationName, stationLogo)
        }
        val out = mutableListOf<Channel>()
        walk(root, stationName.ifBlank { "Khác" }, out)
        return out.map { item ->
            item.copy(
                group = if (forceStationGroup) stationName else item.group,
                groupLogo = stationLogo ?: item.groupLogo
            )
        }
    }

    /** Chuẩn hóa schema API Chuối Chiên: matches[] -> một card/trận; blvs*.streams[] -> lựa chọn phát. */
    private fun parseMatchApi(root: JSONObject, stationName: String, stationLogo: String?): List<Channel> {
        val matches = root.optJSONArray("matches") ?: JSONArray()
        val out = mutableListOf<Channel>()
        for (i in 0 until matches.length()) {
            val match = matches.optJSONObject(i) ?: continue
            val teams = match.optJSONObject("teams")
            val home = teams?.optJSONObject("home")
            val away = teams?.optJSONObject("away")
            val homeName = home?.optString("name", "").orEmpty()
            val awayName = away?.optString("name", "").orEmpty()
            val matchName = listOf(homeName, awayName).filter { it.isNotBlank() }.joinToString(" vs ")
                .ifBlank { match.optString("name", "Trận đấu") }

            val options = mutableListOf<StreamOption>()
            val seen = mutableSetOf<String>()
            val keys = match.keys().asSequence().filter { it == "blvs" || it.startsWith("blvs_") }.toList()
            keys.forEach { key ->
                val commentators = match.optJSONArray(key) ?: return@forEach
                for (j in 0 until commentators.length()) {
                    val commentator = commentators.optJSONObject(j) ?: continue
                    val commentatorName = commentator.optString("name").ifBlank { commentator.optString("username") }
                    val streams = commentator.optJSONArray("streams") ?: continue
                    for (k in 0 until streams.length()) {
                        val stream = streams.optJSONObject(k) ?: continue
                        val streamUrl = stream.optString("url").trim()
                        if (!streamUrl.startsWith("http") || !seen.add(streamUrl)) continue
                        val quality = stream.optString("label").ifBlank { "Phát ${k + 1}" }
                        val label = listOf(commentatorName, quality).filter { it.isNotBlank() }.joinToString(" · ")
                        options += StreamOption(label.ifBlank { quality }, streamUrl, jsonHeaders(stream))
                    }
                }
            }

            // Hỗ trợ cả API JSON có url trực tiếp ở cấp trận.
            if (options.isEmpty()) {
                val directUrl = URL_KEYS.firstNotNullOfOrNull { key -> match.optString(key).takeIf { it.startsWith("http") } }
                if (directUrl != null) options += StreamOption("Phát trực tiếp", directUrl, jsonHeaders(match))
            }
            val league = match.optJSONObject("league")
            val leagueName = league?.optString("name").orEmpty()
            val leagueLogo = league?.optString("logo")?.takeIf { it.startsWith("http") }
            val score = match.optJSONObject("score")
            val scoreText = if (score != null && (score.has("home") || score.has("away"))) {
                "${score.opt("home") ?: "-"} - ${score.opt("away") ?: "-"}"
            } else ""
            val detail = listOf(leagueName, scoreText).filter { it.isNotBlank() }.joinToString("  ·  ")
            val start = parseTime(match.opt("matchTime") ?: match.opt("time"))
            val end = parseTime(match.opt("endTime") ?: match.opt("end_time"))
            val status = match.optString("status").takeIf { it.isNotBlank() }
            out += Channel(
                name = matchName,
                logo = leagueLogo ?: home?.optString("logo")?.takeIf { it.startsWith("http") } ?: away?.optString("logo")?.takeIf { it.startsWith("http") },
                url = options.firstOrNull()?.url.orEmpty(),
                group = stationName,
                start = start,
                end = end,
                headers = options.firstOrNull()?.headers ?: emptyMap(),
                streams = options,
                detail = detail,
                providerStatus = status,
                groupLogo = stationLogo
            )
        }
        return out
    }

    private fun JSONObject.str(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> optString(key, "").takeIf { it.isNotBlank() && it != "null" } }

    private fun jsonHeaders(node: JSONObject): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        (node.opt("headers") as? JSONObject)?.let { obj ->
            for (key in obj.keys()) obj.optString(key).takeIf { it.isNotBlank() }?.let { headers[key] = it }
        }
        node.str("referer", "referrer", "http_referrer")?.let { headers["Referer"] = it }
        node.str("origin")?.let { headers["Origin"] = it }
        node.str("user_agent", "useragent", "ua", "user-agent")?.let { headers["User-Agent"] = it }
        node.str("cookie")?.let { headers["Cookie"] = it }
        return headers
    }

    private fun walk(node: Any?, group: String, out: MutableList<Channel>) {
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), group, out)
            is JSONObject -> {
                val url = URL_KEYS.firstNotNullOfOrNull { key -> node.str(key)?.takeIf { it.startsWith("http") } }
                if (url != null) {
                    out += Channel(
                        name = node.str("name", "title", "channel", "match") ?: "Kênh",
                        logo = node.str("logo", "thumbnail", "thumb", "image", "icon", "poster"),
                        url = url,
                        group = node.str("group", "category", "league", "group_title") ?: group,
                        start = parseTime(node.opt("start") ?: node.opt("start_time") ?: node.opt("time") ?: node.opt("begin") ?: node.opt("kickoff")),
                        end = parseTime(node.opt("end") ?: node.opt("end_time") ?: node.opt("stop")),
                        headers = jsonHeaders(node)
                    )
                } else {
                    val nextGroup = node.str("group_name", "group", "category", "name", "title") ?: group
                    for (key in node.keys()) {
                        val value = node.opt(key)
                        if (value is JSONArray || value is JSONObject) walk(value, nextGroup, out)
                    }
                }
            }
        }
    }

    private fun parseM3u(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var meta: Map<String, String>? = null
        var name = ""
        var headers = linkedMapOf<String, String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) {
                meta = ATTR.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                name = line.substringAfterLast(",").trim()
            } else if (line.startsWith("#EXTVLCOPT:")) {
                val kv = line.removePrefix("#EXTVLCOPT:")
                val value = kv.substringAfter('=', "")
                when (kv.substringBefore('=').lowercase()) {
                    "http-referrer", "http-referer" -> headers["Referer"] = value
                    "http-user-agent" -> headers["User-Agent"] = value
                    "http-origin" -> headers["Origin"] = value
                }
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                val m = meta ?: continue
                out += Channel(
                    name = name.ifBlank { m["tvg-name"] ?: "Kênh" },
                    logo = m["tvg-logo"] ?: m["logo"],
                    url = line,
                    group = m["group-title"] ?: "Khác",
                    start = parseTime(m["start"] ?: m["tvg-start"]),
                    end = parseTime(m["end"]),
                    headers = headers
                )
                meta = null
                headers = linkedMapOf()
            }
        }
        return out
    }

    fun parseTime(value: Any?): Long? {
        when (value) {
            is Number -> return value.toLong().let { if (it < 100_000_000_000L) it * 1000 else it }
            is String -> {
                val s = value.trim()
                s.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000 else it }
                // API Chuối Chiên dùng ISO-8601 UTC, ví dụ 2026-11-06T19:30:00Z.
                val isoPatterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
                for (pattern in isoPatterns) {
                    try {
                        val parser = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
                        parser.parse(s)?.let { return it.time }
                    } catch (_: Exception) { }
                }
                for (pattern in FORMATS) {
                    try {
                        val date = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(s) ?: continue
                        if (pattern == "HH:mm") {
                            val tm = Calendar.getInstance().apply { time = date }
                            return Calendar.getInstance().apply {
                                set(Calendar.HOUR_OF_DAY, tm.get(Calendar.HOUR_OF_DAY))
                                set(Calendar.MINUTE, tm.get(Calendar.MINUTE))
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }.timeInMillis
                        }
                        return date.time
                    } catch (_: Exception) { }
                }
            }
        }
        return null
    }
}
