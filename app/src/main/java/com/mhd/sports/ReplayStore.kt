package com.mhd.sports

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Lưu trận đã thấy trong máy để vẫn xem lại được khi nguồn đã xoá trận.
 * Mỗi trận giữ tới RETAIN_HOURS giờ sau khi kết thúc; bản lưu được làm mới theo dữ liệu nguồn mỗi lần tải.
 */
object ReplayStore {
    const val RETAIN_HOURS = 24
    private const val FILE = "replay_cache.json"
    private val lock = Any()

    private fun key(c: Channel) = c.name + "|" + (c.start ?: 0L)
    private fun endOf(c: Channel): Long? =
        c.start?.let { s -> c.end ?: (s + Config.DEFAULT_DURATION_MIN * 60_000L) }
    private fun expiry(c: Channel): Long? = endOf(c)?.let { it + RETAIN_HOURS * 3_600_000L }

    /** Gộp dữ liệu nguồn với bản lưu: lưu lại trận có giờ, trả về danh sách + các trận đã xong mà nguồn không còn. */
    fun merge(ctx: Context, fresh: List<Channel>, now: Long): List<Channel> = synchronized(lock) {
        val saved = readAll(ctx).associateBy { key(it) }.toMutableMap()
        // làm mới bản lưu bằng dữ liệu nguồn mới nhất (chỉ trận có giờ bắt đầu)
        fresh.filter { it.start != null }.forEach { saved[key(it)] = it }
        // bỏ trận quá hạn
        saved.values.removeAll { (expiry(it) ?: 0L) < now }
        writeAll(ctx, saved.values.toList())

        val present = fresh.map { key(it) }.toSet()
        val extra = saved.values.filter { c ->
            key(c) !in present && (endOf(c) ?: Long.MAX_VALUE) <= now &&
                c.providerStatus?.trim()?.lowercase() !in setOf("cancelled", "canceled", "abandoned")
        }.map { it.copy(providerStatus = "finished") }   // nguồn đã xoá và quá giờ => chắc chắn đã xong
        fresh + extra
    }

    private fun readAll(ctx: Context): List<Channel> = runCatching {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).mapNotNull { runCatching { fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun writeAll(ctx: Context, list: List<Channel>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(toJson(it)) }
            File(ctx.filesDir, FILE).writeText(arr.toString())
        }
    }

    private fun mapJson(m: Map<String, String>) = JSONObject().also { o -> m.forEach { (k, v) -> o.put(k, v) } }
    private fun jsonMap(o: JSONObject?): Map<String, String> =
        if (o == null) emptyMap() else o.keys().asSequence().associateWith { o.getString(it) }

    private fun toJson(c: Channel) = JSONObject().apply {
        put("name", c.name); put("logo", c.logo ?: JSONObject.NULL); put("url", c.url); put("group", c.group)
        put("start", c.start ?: JSONObject.NULL); put("end", c.end ?: JSONObject.NULL)
        put("headers", mapJson(c.headers))
        put("streams", JSONArray().also { a ->
            c.streams.forEach { s ->
                a.put(JSONObject().apply {
                    put("label", s.label); put("url", s.url); put("headers", mapJson(s.headers)); put("format", s.format)
                    put("fallbacks", JSONArray().also { fa ->
                        s.fallbacks.forEach { f ->
                            fa.put(JSONObject().apply { put("url", f.url); put("headers", mapJson(f.headers)); put("label", f.label) })
                        }
                    })
                })
            }
        })
        put("detail", c.detail ?: JSONObject.NULL); put("providerStatus", c.providerStatus ?: JSONObject.NULL)
        put("groupLogo", c.groupLogo ?: JSONObject.NULL); put("logoFit", c.logoFit)
        put("sport", c.sport?.name ?: JSONObject.NULL)
        put("station", c.station); put("stationLogo", c.stationLogo ?: JSONObject.NULL)
    }

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else getString(k)
    private fun JSONObject.lng(k: String): Long? = if (isNull(k)) null else getLong(k)

    private fun fromJson(o: JSONObject) = Channel(
        name = o.getString("name"), logo = o.str("logo"), url = o.getString("url"), group = o.getString("group"),
        start = o.lng("start"), end = o.lng("end"), headers = jsonMap(o.optJSONObject("headers")),
        streams = o.optJSONArray("streams")?.let { a ->
            (0 until a.length()).map { i ->
                val s = a.getJSONObject(i)
                StreamOption(
                    label = s.getString("label"), url = s.getString("url"),
                    headers = jsonMap(s.optJSONObject("headers")),
                    fallbacks = s.optJSONArray("fallbacks")?.let { fa ->
                        (0 until fa.length()).map { j ->
                            val f = fa.getJSONObject(j)
                            StreamSource(f.getString("url"), jsonMap(f.optJSONObject("headers")), f.optString("label", ""))
                        }
                    } ?: emptyList(),
                    format = s.optString("format", "")
                )
            }
        } ?: emptyList(),
        detail = o.str("detail"), providerStatus = o.str("providerStatus"),
        groupLogo = o.str("groupLogo"), logoFit = o.optBoolean("logoFit", false),
        sport = o.str("sport")?.let { n -> Sport.values().firstOrNull { it.name == n } },
        station = o.optString("station", ""), stationLogo = o.str("stationLogo")
    )
}
