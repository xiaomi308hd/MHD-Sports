package com.mhd.sports

import java.text.Normalizer

enum class Sport(val label: String) {
    FOOTBALL("Bóng đá"),
    BASKETBALL("Bóng rổ"),
    VOLLEYBALL("Bóng chuyền"),
    TENNIS("Tennis"),
    BADMINTON("Cầu lông")
}

/** Đoán bộ môn từ tên giải/trận/kênh (bỏ dấu, không phân biệt hoa thường). */
object SportDetector {
    private val MARKS = Regex("""\p{M}+""")
    private val RULES = listOf(
        // Các môn khác bóng đá được kiểm tra trước để "Volleyball Nations League" không bị tính là bóng đá
        Sport.BASKETBALL to Regex("""\b(nba|wnba|vba|fiba|nbl|euroleague|basketball|bong ro)\b"""),
        Sport.VOLLEYBALL to Regex("""\b(volleyball|volley|bong chuyen|vnl|fivb)\b"""),
        Sport.TENNIS to Regex("""\b(tennis|atp|wta|wimbledon|roland garros|davis cup|billie jean king|grand slam|us open|australian open|itf)\b"""),
        Sport.BADMINTON to Regex("""\b(badminton|cau long|bwf|all england|thomas cup|uber cup|sudirman)\b"""),
        Sport.FOOTBALL to Regex("""\b(football|soccer|bong da|premier league|epl|la ?liga|serie a|bundesliga|ligue 1|champions league|europa league|conference league|uefa|fifa|world cup|v ?league|afc|copa|mls|j ?league|k ?league|fa cup|carabao|efl|nations league|ngoai hang|fc)\b""")
    )

    private fun fold(t: String): String =
        Normalizer.normalize(t, Normalizer.Form.NFD).replace(MARKS, "")
            .replace('đ', 'd').replace('Đ', 'D').lowercase()

    fun detect(vararg texts: String?): Sport? {
        val joined = fold(texts.filterNotNull().joinToString(" | "))
        if (joined.isBlank()) return null
        return RULES.firstOrNull { it.second.containsMatchIn(joined) }?.first
    }
}
