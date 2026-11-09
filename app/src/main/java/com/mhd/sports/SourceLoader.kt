package com.mhd.sports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.util.Calendar
import java.util.TimeZone

/** Đọc M3U/M3U8, JSON thông thường, JSON trận đấu Chuối Chiên và index.json nhiều nhà đài. */
object SourceLoader {
    data class Result(
        val channels: List<Channel>,
        val stationCount: Int,
        val stationNames: List<String>,
        val failedStations: List<String> = emptyList()
    )

    private data class Document(val text: String, val finalUrl: String)
    private data class StationEntry(val name: String, val logo: String?, val url: String)

    private val URL_KEYS = listOf("url", "link", "stream", "stream_url", "src", "m3u8", "source", "play_url")
    private val ATTR = Regex("""([\w-]+)="([^"]*)"""")

    // Khoá giờ bắt đầu / kết thúc thường gặp trong các API trận đấu
    private val START_KEYS = arrayOf(
        "matchTime", "match_time", "startTime", "start_time", "start", "kickoff", "kickOff", "kick_off",
        "begin", "scheduledAt", "scheduled_at", "datetime", "dateTime", "timestamp", "time", "date"
    )
    private val END_KEYS = arrayOf("endTime", "end_time", "end", "stop", "finishTime", "finish_time")
    private val STATUS_KEYS = arrayOf(
        "status", "state", "matchStatus", "match_status", "statusText", "status_text", "isLive", "is_live", "live"
    )

    suspend fun load(url: String): List<Channel> = loadCatalog(url).channels

    suspend fun loadCatalog(rawUrl: String): Result = withContext(Dispatchers.IO) {
        val decoded = UrlTools.decode(rawUrl)
        val url = decoded.url.ifBlank { rawUrl }
        // Link trỏ thẳng vào một luồng (flv/mp4/ts) => tạo luôn một kênh để phát
        if (UrlTools.isDirectStream(url)) {
            val ch = Channel("Link của bạn", null, url, "Link của bạn", null, null, headers = decoded.headers)
            return@withContext Result(listOf(ch), 1, listOf("Link của bạn"))
        }
        val root = fetch(url)
        val parsed = runCatching { JSONTokener(root.text).nextValue() }.getOrNull()
        if (parsed is JSONObject && parsed.optJSONArray("stations") != null) {
            val stations = parsed.optJSONArray("stations")!!
            val entries = mutableListOf<StationEntry>()
            val declared = mutableSetOf<String>()
            for (i in 0 until stations.length()) {
                val station = stations.optJSONObject(i) ?: continue
                val source = station.optString("url").trim()
                if (source.isBlank()) continue
                val stationUrl = resolveRelative(root.finalUrl, source)
                declared += normalizeUrl(stationUrl)
                if (!station.optBoolean("enabled", true)) continue
                val stationName = station.optString("name").ifBlank { station.optString("id") }
                    .ifBlank { "Nhà đài ${i + 1}" }
                val stationLogo = station.optString("logo").takeIf { it.startsWith("http") }
                entries += StationEntry(stationName, stationLogo, stationUrl)
            }
            // Gộp luồng có sẵn trong ứng dụng (bỏ qua nếu index.json đã khai báo, kể cả đang tắt)
            Config.BUILTIN_STATIONS.forEach { b ->
                if (normalizeUrl(b.url) !in declared) entries += StationEntry(b.name, b.logo, b.url)
            }

            // Tải song song; một nhà đài lỗi không làm hỏng cả danh sách
            val outcomes = coroutineScope {
                entries.map { e -> async { runCatching { loadStation(e) } } }.awaitAll()
            }
            val all = mutableListOf<Channel>()
            val names = mutableListOf<String>()
            val failed = mutableListOf<String>()
            entries.forEachIndexed { i, e ->
                val items = outcomes[i].getOrNull()
                if (items == null) failed += e.name
                else if (items.isNotEmpty()) { all += items; names += e.name }
            }
            if (all.isEmpty()) {
                error("Chưa có nhà đài nào trả về trận đấu hoặc link phát" +
                    if (failed.isNotEmpty()) " (${failed.size} nguồn không tải được)" else "")
            }
            Result(all, names.size, names, failed)
        } else {
            val stationName = inferStationName(root.finalUrl)
            val items = parseDocument(root.text, root.finalUrl, stationName, null)
            if (items.isEmpty()) error("Nguồn đã tải nhưng không tìm thấy trận đấu/kênh có link phát")
            Result(items, items.map { it.group }.distinct().size, items.map { it.group }.distinct())
        }
    }

    private fun loadStation(e: StationEntry): List<Channel> {
        val doc = fetch(e.url)
        return parseDocument(doc.text, doc.finalUrl, e.name, e.logo, forceStationGroup = true)
    }

    // Thông báo lỗi không kèm link để không lộ nguồn mặc định
    private fun fetch(url: String): Document {
        val requestUrl = cacheBusted(url)
        Net.client.newCall(Net.req(requestUrl)).execute().use { response ->
            if (!response.isSuccessful) error("máy chủ trả về HTTP ${response.code}")
            val finalUrl = response.request.url.toString().substringBefore("?_mhd_refresh=").substringBefore("&_mhd_refresh=")
            val text = (response.body?.string() ?: "").trim().removePrefix("\uFEFF")
            if (text.isBlank()) error("nguồn trả về nội dung trống")
            return Document(text, finalUrl)
        }
    }

    private fun cacheBusted(url: String): String {
        if (!url.contains("raw.githubusercontent.com", true)) return url
        val separator = if (url.contains("?")) "&" else "?"
        return url + separator + "_mhd_refresh=" + System.currentTimeMillis()
    }

    private fun normalizeUrl(url: String): String = url.trim().substringBefore("#").trimEnd('/').lowercase()

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
        val grouped = if (forceStationGroup) parsed.map { it.copy(group = stationName, groupLogo = stationLogo) } else parsed
        return mergeDuplicates(grouped)
    }

    // ---------- gộp trận trùng tên ----------
    // Nhãn nằm trong ngoặc: định dạng [hls] [flv] [hls2], chất lượng (HD KOBE) [FHD], và luồng [geo].
    // Bỏ các nhãn đó khi so tên => "(KOBE)" và "(HD KOBE)" là cùng một trận / cùng một BLV.
    private val BRACKET = Regex("""[\[(]([^\])\[(]*)[\])]""")
    private val MOD_TOKEN = Regex(
        """(?i)(?<![\p{L}\p{N}])(http[-_ ]?flv|flv\s*\d*|hls\s*\d*|m3u8\s*\d*|geo|fhd|full[ -]?hd|hd|sd|4k|1080p?|720p?|480p?)(?![\p{L}\p{N}])"""
    )
    private val DIACRITICS = Regex("""\p{M}+""")
    private val SPACES = Regex("""\s+""")
    private const val SAME_START_MS = 5 * 60_000L

    private class Tags(val format: String, val mods: List<String>)
    private class Member(val tags: Tags, val ch: Channel)
    private class Variant(val label: String, val url: String, val headers: Map<String, String>, val geoRank: Int, val quality: Int, val fmtRank: Int)

    private fun foldName(t: String): String =
        java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD).replace(DIACRITICS, "")
            .replace('đ', 'd').replace('Đ', 'D').lowercase()
            .replace(Regex("""[^\p{L}\p{N}:/]+"""), " ").trim()

    /** "hls 2" -> "HLS2", "http-flv" -> "FLV". Trả chuỗi rỗng nếu không nhận ra. */
    private fun tagLabel(tag: String?): String {
        if (tag == null) return ""
        val t = tag.lowercase().replace(Regex("""[\s_-]"""), "")
        val n = Regex("""\d+""").find(t)?.value.orEmpty()
        return when {
            "flv" in t -> "FLV$n"
            "hls" in t || "m3u8" in t -> "HLS$n"
            else -> ""
        }
    }

    private fun labelFromUrl(url: String): String {
        val path = url.substringBefore('?').substringBefore('|').lowercase()
        return when {
            path.contains(".m3u8") -> "HLS"
            path.contains(".flv") -> "FLV"
            else -> ""
        }
    }

    private fun labelRank(label: String): Int = when {
        label.startsWith("HLS") -> 0
        label.startsWith("FLV") -> 2
        else -> 1
    }

    /** GEO luôn xếp sau cùng. */
    private fun geoRank(mods: List<String>): Int = if ("GEO" in mods) 1 else 0

    private val Q_4K = Regex("""(?i)(?<![a-z0-9])(4k|2160p?|uhd)(?![a-z0-9])""")
    private val Q_FHD = Regex("""(?i)(?<![a-z0-9])(fhd|full[ -]?hd|1080p?)(?![a-z0-9])""")
    private val Q_HD = Regex("""(?i)(?<![a-z])(hd|720p?)""")
    private val Q_SD = Regex("""(?i)(?<![a-z0-9])(sd|576p?|480p?|360p?|240p?)(?![a-z])""")

    /** Điểm độ phân giải suy từ nhãn: 4K > FHD/1080 > HD/720 > không nhãn > SD. Điểm cao được thử trước. */
    private fun qualityScore(text: String): Int = when {
        Q_4K.containsMatchIn(text) -> 40
        Q_FHD.containsMatchIn(text) -> 30
        Q_HD.containsMatchIn(text) -> 20
        Q_SD.containsMatchIn(text) -> 5
        else -> 10
    }

    /** Tách nhãn trong ngoặc ra khỏi tên. Trả về (tên đã sạch, nhãn). */
    private fun splitTags(raw: String): Pair<String, Tags> {
        var format = ""
        val mods = mutableListOf<String>()
        val replaced = BRACKET.replace(raw) { m ->
            val inner = m.groupValues[1]
            val tokens = MOD_TOKEN.findAll(inner).map { it.value }.toList()
            if (tokens.isEmpty()) return@replace m.value
            tokens.forEach { t ->
                val l = t.lowercase().replace(Regex("""[\s_-]"""), "")
                when {
                    "flv" in l || "hls" in l || "m3u8" in l -> if (format.isEmpty()) format = tagLabel(t)
                    l == "geo" -> mods += "GEO"
                    l.startsWith("full") || l == "fhd" -> mods += "FHD"
                    else -> mods += l.uppercase()
                }
            }
            val rest = inner.replace(MOD_TOKEN, " ").replace(SPACES, " ").trim()
            if (rest.isEmpty()) " " else "${m.value.first()}$rest${m.value.last()}"
        }
        return cleanName(replaced.replace(SPACES, " ")) to Tags(format, mods.distinct())
    }

    /** Trùng nhãn thì đánh số: HLS, HLS -> HLS, HLS2; nhãn rỗng -> "Luồng N". */
    private fun numberLabels(raw: List<String>): List<String> {
        val used = mutableSetOf<String>()
        return raw.mapIndexed { i, l ->
            if (l.isBlank()) return@mapIndexed "Luồng ${i + 1}".also { used += it }
            var cand = l
            if (cand in used) {
                val base = l.trimEnd { it.isDigit() }
                var n = 2
                while ("$base$n" in used) n++
                cand = "$base$n"
            }
            used += cand
            cand
        }
    }

    /**
     * Các mục cùng tên (sau khi bỏ nhãn hls/flv/HD/geo) trong cùng nhà đài, cùng giờ => gộp thành 1 thẻ.
     * Mỗi mục cũ thành một luồng trong danh sách (HLS, HLS2, FLV, HD HLS, GEO...); người xem đổi ngay trong trình phát.
     */
    private fun mergeDuplicates(list: List<Channel>): List<Channel> {
        class Bucket(val members: MutableList<Member>)
        val buckets = mutableListOf<Bucket>()
        val byKey = HashMap<String, MutableList<Bucket>>()
        for (ch in list) {
            val (base, tags) = splitTags(ch.name)
            val key = ch.group.lowercase() + "|" + foldName(base)
            val candidates = byKey.getOrPut(key) { mutableListOf() }
            val target = candidates.firstOrNull { b ->
                val s0 = b.members.first().ch.start
                s0 == null || ch.start == null || kotlin.math.abs(s0 - ch.start) <= SAME_START_MS
            } ?: Bucket(mutableListOf()).also { candidates += it; buckets += it }
            target.members += Member(tags, ch.copy(name = base))
        }
        return buckets.map { b ->
            val first = b.members.first().ch
            val tagged = b.members.any { it.tags.format.isNotEmpty() || it.tags.mods.isNotEmpty() }
            if (b.members.size == 1 && !tagged) return@map first

            val ownStreams = b.members.flatMap { it.ch.streams }
            val options: List<StreamOption> = if (ownStreams.isNotEmpty()) {
                ownStreams.distinctBy { it.label + "#" + it.url }
            } else {
                val raw = b.members.map { m ->
                    val fmt = m.tags.format.ifEmpty { labelFromUrl(m.ch.url) }
                    Variant((m.tags.mods + fmt).filter { it.isNotBlank() }.joinToString(" "),
                        m.ch.url, m.ch.headers, geoRank(m.tags.mods), qualityScore(m.tags.mods.joinToString(" ")), labelRank(fmt))
                }.filter { it.url.isNotBlank() }.distinctBy { it.url }
                // Độ phân giải cao nhất thử trước; cùng độ phân giải thì HLS trước FLV; GEO sau cùng.
                val sorted = raw.sortedWith(compareBy<Variant>({ it.geoRank }, { -it.quality }, { it.fmtRank }, { it.label }))
                val labels = numberLabels(sorted.map { it.label })
                if (sorted.isEmpty()) emptyList()
                else listOf(StreamOption(
                    label = "Phát trực tiếp",
                    url = sorted[0].url,
                    headers = sorted[0].headers,
                    fallbacks = sorted.drop(1).mapIndexed { i, v -> StreamSource(v.url, v.headers, labels[i + 1]) },
                    format = labels[0]
                ))
            }
            first.copy(
                logo = b.members.firstNotNullOfOrNull { it.ch.logo?.takeIf { l -> l.isNotBlank() } },
                url = options.firstOrNull()?.url ?: first.url,
                headers = options.firstOrNull()?.headers ?: first.headers,
                start = b.members.mapNotNull { it.ch.start }.minOrNull(),
                end = b.members.firstNotNullOfOrNull { it.ch.end },
                streams = options,
                detail = b.members.firstNotNullOfOrNull { it.ch.detail?.takeIf { d -> d.isNotBlank() } },
                providerStatus = b.members.firstNotNullOfOrNull { it.ch.providerStatus },
                groupLogo = b.members.firstNotNullOfOrNull { it.ch.groupLogo },
                logoFit = b.members.any { it.ch.logoFit },
                sport = b.members.firstNotNullOfOrNull { it.ch.sport }
            )
        }
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

            val raws = mutableListOf<RawStream>()
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
                        val label = stream.optString("label")
                        val hint = listOf(key, label, stream.str("type", "format", "protocol", "kind").orEmpty()).joinToString(" ")
                        raws += RawStream(commentatorName, label.ifBlank { "Phát ${k + 1}" }, streamUrl, jsonHeaders(stream), formatOf(streamUrl, hint), GEO_WORD.containsMatchIn(hint))
                    }
                }
            }
            val options = mergeStreams(raws).toMutableList()

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

            var start = timeOf(match.any(*START_KEYS))
            if (start == null) {
                // Một số API tách ngày và giờ thành hai trường
                val d = match.str("date", "matchDate", "match_date")
                val t = match.str("time", "matchHour", "hour")
                if (d != null && t != null) start = parseTime("$d $t")
            }
            val end = timeOf(match.any(*END_KEYS))
            val status = statusOf(match)
            val sportField = match.any("sport", "sportType", "sport_type", "sportName", "sport_name", "category", "categoryName")
                ?.let { if (it is JSONObject) it.str("name", "title", "label") else it.toString() }
            // API trận đấu không ghi môn thì mặc định là bóng đá
            val sport = SportDetector.detect(sportField, leagueName, matchName) ?: Sport.FOOTBALL

            out += Channel(
                name = cleanName(matchName),
                logo = leagueLogo ?: home?.optString("logo")?.takeIf { it.startsWith("http") } ?: away?.optString("logo")?.takeIf { it.startsWith("http") },
                url = options.firstOrNull()?.url.orEmpty(),
                group = stationName,
                start = start,
                end = end,
                headers = options.firstOrNull()?.headers ?: emptyMap(),
                streams = options,
                detail = detail,
                providerStatus = status,
                groupLogo = stationLogo,
                logoFit = true,
                sport = sport
            )
        }
        return out
    }

    // ---------- gộp luồng FLV / HLS ----------
    private enum class Fmt { HLS, OTHER, FLV }
    private class RawStream(
        val commentator: String, val quality: String, val url: String,
        val headers: Map<String, String>, val fmt: Fmt, val geo: Boolean = false
    )
    private val GEO_WORD = Regex("""(?i)(?<![\p{L}\p{N}])geo(?![\p{L}\p{N}])""")

    private val FORMAT_WORDS = Regex("""(?i)\b(http[-_ ]?flv|flv|hls|m3u8)\b""")
    private val SEPARATORS = Regex("""[\s\-_/|()\[\]·:]+""")

    private fun fmtName(f: Fmt): String = when (f) { Fmt.HLS -> "HLS"; Fmt.FLV -> "FLV"; Fmt.OTHER -> "" }

    private fun formatOf(url: String, hint: String): Fmt {
        val path = url.substringBefore('?').lowercase()
        val h = hint.lowercase()
        return when {
            path.contains(".m3u8") -> Fmt.HLS
            path.contains(".flv") -> Fmt.FLV
            "hls" in h || "m3u8" in h -> Fmt.HLS
            "flv" in h -> Fmt.FLV
            else -> Fmt.OTHER
        }
    }

    /**
     * Gộp tất cả chất lượng của cùng bình luận viên thành một lựa chọn.
     * Ví dụ HD1.m3u8, HD2.m3u8 và FHD.m3u8 sẽ thành một mục; từng chất lượng
     * xuất hiện thành nút đổi luồng trong PlayerActivity. HLS được ưu tiên trước FLV.
     */
    private fun mergeStreams(raws: List<RawStream>): List<StreamOption> {
        fun cleanQuality(q: String) =
            q.replace(FORMAT_WORDS, " ").replace(GEO_WORD, " ").replace(SEPARATORS, " ").trim()

        // Không đưa chất lượng vào khóa nhóm: HD1/HD2/FHD là các biến thể của cùng một mục.
        val groups = linkedMapOf<String, MutableList<RawStream>>()
        raws.forEach { r ->
            val commentatorKey = r.commentator.trim().lowercase().ifBlank { "default" }
            groups.getOrPut(commentatorKey) { mutableListOf() } += r
        }

        return groups.values.map { list ->
            // Độ phân giải cao nhất phát trước; cùng độ phân giải thì HLS trước FLV; GEO xếp cuối.
            val sorted = list.distinctBy { it.url }
                .sortedWith(compareBy<RawStream>({ it.geo }, { -qualityScore(it.quality) }, { it.fmt.ordinal }))
            val first = sorted.first()
            val label = first.commentator.trim().ifBlank { "Phát trực tiếp" }

            // Giữ nhãn chất lượng trên từng nút đổi luồng, ví dụ "HD1 HLS", "HD2 HLS", "FHD HLS".
            val rawLabels = sorted.map { stream ->
                listOf(
                    cleanQuality(stream.quality),
                    if (stream.geo) "GEO" else "",
                    fmtName(stream.fmt)
                ).filter { it.isNotBlank() }.joinToString(" ")
            }
            val labels = numberLabels(rawLabels)

            StreamOption(
                label = label,
                url = first.url,
                headers = first.headers,
                fallbacks = sorted.drop(1).mapIndexed { i, stream ->
                    StreamSource(stream.url, stream.headers, labels[i + 1])
                },
                format = labels.firstOrNull().orEmpty()
            )
        }
    }

    private fun JSONObject.any(vararg keys: String): Any? =
        keys.firstNotNullOfOrNull { k -> opt(k).takeIf { it != null && it != JSONObject.NULL } }

    private fun JSONObject.str(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> optString(key, "").takeIf { it.isNotBlank() && it != "null" } }

    /** Trạng thái có thể là chuỗi, số, boolean hoặc đối tượng {short,long,...}. */
    private fun statusOf(match: JSONObject): String? {
        val v = match.any(*STATUS_KEYS) ?: return null
        return when (v) {
            is Boolean -> if (v) "live" else null
            is JSONObject -> v.str("short", "code", "name", "long", "text", "value")
            else -> v.toString().takeIf { it.isNotBlank() && it != "null" }
        }
    }

    /** Giờ có thể là chuỗi/số hoặc đối tượng lồng nhau. */
    private fun timeOf(v: Any?): Long? = when (v) {
        is JSONObject -> parseTime(v.any("start", "time", "datetime", "iso", "utc", "value", "timestamp"))
        else -> parseTime(v)
    }

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
                    val rawName = node.str("name", "title", "channel", "match") ?: "Kênh"
                    out += Channel(
                        name = cleanName(rawName),
                        logo = node.str("logo", "thumbnail", "thumb", "image", "icon", "poster"),
                        url = url,
                        group = node.str("group", "category", "league", "group_title") ?: group,
                        start = timeOf(node.any("start", "start_time", "startTime", "matchTime", "time", "begin", "kickoff"))
                            ?: timeFromName(rawName),
                        end = timeOf(node.any("end", "end_time", "endTime", "stop")),
                        headers = jsonHeaders(node),
                        providerStatus = statusOf(node),
                        sport = SportDetector.detect(node.str("sport", "sport_type"), rawName, node.str("group", "category", "league", "group_title"), group)
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
                val rawName = name.ifBlank { m["tvg-name"] ?: "Kênh" }
                out += Channel(
                    name = cleanName(rawName),
                    logo = (m["tvg-logo"] ?: m["logo"])?.takeIf { it.isNotBlank() },
                    url = line,
                    group = m["group-title"] ?: "Khác",
                    groupLogo = (m["group-logo"] ?: m["tvg-group-logo"] ?: m["station-logo"])
                        ?.takeIf { it.startsWith("http", true) } ?: Config.stationLogo(m["group-title"] ?: "Khác"),
                    start = parseTime(m["start"] ?: m["tvg-start"]) ?: timeFromName(rawName),
                    end = parseTime(m["end"]),
                    headers = headers,
                    sport = SportDetector.detect(rawName, m["group-title"])
                )
                meta = null
                headers = linkedMapOf()
            }
        }
        return out
    }

    // ---------- tên trận / giờ ----------
    // Gồm cả ký tự chưa được Android đời cũ biết (hiện thành ô vuông): vùng emoji, ký tự chưa gán, định dạng, dùng riêng
    private val EMOJI = Regex("""[\p{So}\p{Sk}\p{Cs}\p{Cn}\p{Co}\p{Cf}\uFE0F\u200D\u20E3\uFFFC\uFFFD\x{1F000}-\x{1FAFF}]""")
    private val MULTI_SPACE = Regex("""\s{2,}""")
    private val NAME_TIME = Regex("""(?<!\d)(\d{1,2}):(\d{2})(?!\d)""")
    private val NAME_DATE = Regex("""(?<!\d)(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?(?!\d)""")

    /** Bỏ emoji khỏi tên, giữ nguyên chữ có dấu. */
    private fun cleanName(name: String): String =
        name.replace(EMOJI, "").replace(MULTI_SPACE, " ").trim().ifBlank { name.trim() }

    /** Đọc giờ từ tên trận, ví dụ "15:30 06/11 A vs B" (không có ngày => hôm nay). */
    private fun timeFromName(name: String): Long? {
        val t = NAME_TIME.find(name) ?: return null
        val h = t.groupValues[1].toInt()
        val mi = t.groupValues[2].toInt()
        if (h > 23 || mi > 59) return null
        val cal = Calendar.getInstance()
        var y = cal.get(Calendar.YEAR); var mo = cal.get(Calendar.MONTH); var d = cal.get(Calendar.DAY_OF_MONTH)
        val dm = NAME_DATE.find(name)
        if (dm != null) {
            val dd = dm.groupValues[1].toInt(); val mm = dm.groupValues[2].toInt()
            if (dd in 1..31 && mm in 1..12) {
                d = dd; mo = mm - 1
                dm.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull()?.let { yy -> y = if (yy < 100) 2000 + yy else yy }
            }
        }
        cal.set(y, mo, d, h, mi, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // ---------- đọc thời gian ----------
    // ISO-8601 / "yyyy-MM-dd HH:mm[:ss[.SSS]]" kèm múi giờ tuỳ chọn (Z, +07:00, +0700, +07)
    private val ISO_TIME = Regex(
        """^(\d{4})[-/](\d{1,2})[-/](\d{1,2})(?:[T\s]+(\d{1,2}):(\d{2})(?::(\d{2}))?(?:[.,]\d+)?)?\s*(Z|UTC|GMT|[+-]\d{2}(?::?\d{2})?)?$""",
        RegexOption.IGNORE_CASE
    )
    // "dd/MM/yyyy HH:mm[:ss]"
    private val DMY_TIME = Regex(
        """^(\d{1,2})[/-](\d{1,2})[/-](\d{4})(?:[T\s]+(\d{1,2}):(\d{2})(?::(\d{2}))?)?\s*(Z|UTC|GMT|[+-]\d{2}(?::?\d{2})?)?$""",
        RegexOption.IGNORE_CASE
    )
    private val HM_ONLY = Regex("""^(\d{1,2}):(\d{2})(?::(\d{2}))?$""")

    private fun offsetMinutes(tz: String): Int {
        if (tz.equals("Z", true) || tz.equals("UTC", true) || tz.equals("GMT", true)) return 0
        val sign = if (tz.startsWith("-")) -1 else 1
        val digits = tz.drop(1).replace(":", "")
        val hh = digits.take(2).toIntOrNull() ?: return 0
        val mm = digits.drop(2).toIntOrNull() ?: 0
        return sign * (hh * 60 + mm)
    }

    private fun build(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, tz: String?): Long? {
        if (mo !in 1..12 || d !in 1..31 || h !in 0..23 || mi !in 0..59 || s !in 0..59) return null
        return if (tz.isNullOrBlank()) {
            Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi, s) }.timeInMillis
        } else {
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, mo - 1, d, h, mi, s) }.timeInMillis
            utc - offsetMinutes(tz) * 60_000L
        }
    }

    fun parseTime(value: Any?): Long? {
        when (value) {
            null -> return null
            is Number -> return value.toLong().let { if (it < 100_000_000_000L) it * 1000 else it }
            is String -> {
                val s = value.trim()
                if (s.isEmpty() || s == "null") return null
                s.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000 else it }
                ISO_TIME.matchEntire(s)?.let { m ->
                    val g = m.groupValues
                    return build(
                        g[1].toInt(), g[2].toInt(), g[3].toInt(),
                        g[4].ifEmpty { "0" }.toInt(), g[5].ifEmpty { "0" }.toInt(), g[6].ifEmpty { "0" }.toInt(),
                        g[7].ifEmpty { null }
                    )
                }
                DMY_TIME.matchEntire(s)?.let { m ->
                    val g = m.groupValues
                    return build(
                        g[3].toInt(), g[2].toInt(), g[1].toInt(),
                        g[4].ifEmpty { "0" }.toInt(), g[5].ifEmpty { "0" }.toInt(), g[6].ifEmpty { "0" }.toInt(),
                        g[7].ifEmpty { null }
                    )
                }
                HM_ONLY.matchEntire(s)?.let { m ->
                    val g = m.groupValues
                    val h = g[1].toInt(); val mi = g[2].toInt()
                    if (h > 23 || mi > 59) return null
                    return Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, mi)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                }
            }
        }
        return null
    }
}
