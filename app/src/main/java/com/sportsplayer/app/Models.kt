package com.sportsplayer.app

object Config {
    /** Gắn link trực tiếp tại đây (m3u8 / m3u / JSON, link rút gọn cũng được). Để trống nếu muốn người dùng tự nhập. */
    const val DEFAULT_SOURCE_URL = ""

    /** Dòng chờ hiển thị trong ô nhập link. */
    const val LINK_HINT = "nhập link của bạn để tôi có thể nhận biết"

    /** Trận không có giờ kết thúc sẽ được tính là đang phát trong khoảng này (phút). */
    const val DEFAULT_DURATION_MIN = 120
}

enum class State { LIVE, UPCOMING, ENDED, ALWAYS }

data class Channel(
    val name: String,
    val logo: String?,
    val url: String,
    val group: String,
    val start: Long?,
    val end: Long?
) {
    fun state(now: Long): State {
        val s = start ?: return State.ALWAYS
        val e = end ?: (s + Config.DEFAULT_DURATION_MIN * 60_000L)
        return when {
            now < s -> State.UPCOMING
            now < e -> State.LIVE
            else -> State.ENDED
        }
    }
}
