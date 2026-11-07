package com.mhd.sports

object Config {
    /** GitHub index.json quản lý nhiều nhà đài. Có thể đổi bằng nút Nhập link. */
    const val DEFAULT_SOURCE_URL = "https://raw.githubusercontent.com/guiterhd-bit/Api-JK/main/index.json"

    /** Repo GitHub Releases chứa APK. Chưa tự đặt vì repo phát hành APK cần được xác nhận riêng. */
    const val GITHUB_REPO = "your-name/MHDSports"

    const val LINK_HINT = "Nhập link index.json, JSON, M3U hoặc M3U8"
    const val DEFAULT_DURATION_MIN = 120
    const val DATA_REFRESH_MIN = 5
}

enum class State { LIVE, UPCOMING, ENDED, ALWAYS }

data class StreamOption(
    val label: String,
    val url: String,
    val headers: Map<String, String> = emptyMap()
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
    val groupLogo: String? = null
) {
    fun state(now: Long): State {
        when (providerStatus?.trim()?.lowercase()) {
            "live", "1h", "2h", "ht", "et", "p", "inplay", "in_play", "playing" -> return State.LIVE
            "ft", "aet", "pen", "finished", "ended", "full_time", "complete" -> return State.ENDED
            "ns", "scheduled", "upcoming", "not_started", "timed" -> return State.UPCOMING
        }
        val s = start ?: return State.ALWAYS
        val e = end ?: (s + Config.DEFAULT_DURATION_MIN * 60_000L)
        return when {
            now < s -> State.UPCOMING
            now < e -> State.LIVE
            else -> State.ENDED
        }
    }
}
