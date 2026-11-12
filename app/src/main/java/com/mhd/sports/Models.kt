package com.mhd.sports

object Config {
    const val APP_NAME = "MHD Sports"
    const val APP_TAGLINE = "Trực tiếp thể thao"

    /** Nguồn dữ liệu duy nhất của ứng dụng (cố định, không cho nhập link khác); tự tải lại mỗi DATA_REFRESH_MIN phút. */
    const val DEFAULT_SOURCE_URL = "https://raw.githubusercontent.com/xiaomi308hd/hinh-nen/refs/heads/main/thethao.m3u"

    /** Repo GitHub Releases chứa APK. Chưa tự đặt vì repo phát hành APK cần được xác nhận riêng. */
    const val GITHUB_REPO = "guiterhd-bit/MHD-Sports"

    const val DEFAULT_DURATION_MIN = 120
    const val DATA_REFRESH_MIN = 2

    /**
     * Logo nhà đài (tuỳ chọn) khi nguồn M3U không ghi sẵn thuộc tính `group-logo="..."`.
     * Khoá = tên nhà đài (chính là `group-title` trong M3U), không phân biệt hoa thường.
     * Ví dụ: "chuối chiên tv" to "https://.../chuoichien.png"
     * Nhà đài không có logo sẽ hiện chữ cái đầu trong hình tròn.
     */
    val STATION_LOGOS: Map<String, String> = mapOf()

    fun stationLogo(name: String): String? =
        STATION_LOGOS.entries.firstOrNull { it.key.trim().equals(name.trim(), ignoreCase = true) }?.value

    private val URL_IN_TEXT = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    /** Không để lộ link nguồn trong thông báo lỗi trên màn hình. */
    fun scrub(message: String?): String =
        (message ?: "không rõ nguyên nhân").replace(URL_IN_TEXT, "[link]")
}

enum class State { LIVE, UPCOMING, ENDED, ALWAYS }

/** Một địa chỉ phát (dùng làm dự phòng khi luồng chính lỗi). */
data class StreamSource(val url: String, val headers: Map<String, String> = emptyMap(), val label: String = "")

/** Một lựa chọn phát. FLV và HLS cùng bình luận viên/chất lượng được gộp: url là luồng ưu tiên, fallbacks là dự phòng. */
data class StreamOption(
    val label: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val fallbacks: List<StreamSource> = emptyList(),
    /** Nhãn định dạng của luồng chính (HLS / HLS2 / FLV) để hiện trên thanh đổi luồng trong trình phát. */
    val format: String = ""
)

data class Channel(
    val name: String,
    val logo: String?,
    val url: String,
    val group: String,
    val start: Long?,
    val end: Long?,
    val headers: Map<String, String> = emptyMap(),
    val streams: List<StreamOption> = emptyList(),
    val detail: String? = null,
    val providerStatus: String? = null,
    val groupLogo: String? = null,
    /** true: logo là logo giải/đội (vuông) => hiển thị nguyên hình, không cắt. */
    val logoFit: Boolean = false,
    val sport: Sport? = null,
    /** Nhà đài (API nguồn) của mục này, ví dụ "Chuối Chiên TV". Rỗng = dùng tên nhóm làm nhà đài như bản cũ. */
    val station: String = "",
    val stationLogo: String? = null
) {
    /** Khoá nhà đài dùng cho hàng logo và bộ lọc: nhà đài khai báo, nếu không có thì tên nhóm. */
    val stationKey: String get() = station.ifBlank { group }

    private enum class Provider { LIVE, ENDED, UPCOMING, UNKNOWN }

    private fun providerState(): Provider {
        val s = providerStatus?.trim()?.lowercase()?.replace(' ', '_')?.replace('-', '_') ?: return Provider.UNKNOWN
        return when (s) {
            "live", "1h", "2h", "ht", "et", "p", "inplay", "in_play", "playing", "inprogress", "in_progress",
            "started", "ongoing", "running", "live_now", "first_half", "second_half", "half_time", "halftime",
            "extra_time", "truc_tiep", "trực_tiếp", "dang_dien_ra", "đang_diễn_ra", "đang_phát" -> Provider.LIVE
            "ft", "aet", "pen", "finished", "finish", "ended", "end", "full_time", "fulltime", "complete",
            "completed", "closed", "over", "cancelled", "canceled", "abandoned", "ket_thuc", "kết_thúc",
            "da_ket_thuc", "đã_kết_thúc" -> Provider.ENDED
            "ns", "scheduled", "upcoming", "not_started", "notstarted", "timed", "pending", "soon", "tbd",
            "prematch", "pre_match", "sap_dien_ra", "sắp_diễn_ra" -> Provider.UPCOMING
            else -> Provider.UNKNOWN
        }
    }

    fun state(now: Long): State {
        val timeBased: State? = start?.let { s ->
            val e = end ?: (s + Config.DEFAULT_DURATION_MIN * 60_000L)
            when {
                now < s -> State.UPCOMING
                now < e -> State.LIVE
                else -> State.ENDED
            }
        }
        return when (providerState()) {
            Provider.LIVE ->
                // Nguồn ghi "live" nhưng giờ bắt đầu còn xa => vẫn là sắp diễn ra
                if (start != null && now < start - 10 * 60_000L) State.UPCOMING else State.LIVE
            Provider.ENDED -> State.ENDED
            Provider.UPCOMING -> timeBased?.takeIf { it != State.ENDED } ?: State.UPCOMING
            Provider.UNKNOWN -> timeBased ?: State.ALWAYS
        }
    }
}
