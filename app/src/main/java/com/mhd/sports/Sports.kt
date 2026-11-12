package com.mhd.sports

import java.text.Normalizer

enum class Sport(val label: String) {
    FOOTBALL("Bóng đá"),
    BASKETBALL("Bóng rổ"),
    VOLLEYBALL("Bóng chuyền"),
    TENNIS("Tennis"),
    BADMINTON("Cầu lông"),
    BILLIARDS("Billiards"),
    ESPORTS("E-Sports")
}

/** Đoán bộ môn từ tên giải/trận/kênh (bỏ dấu, không phân biệt hoa thường). */
object SportDetector {
    private val MARKS = Regex("""\p{M}+""")
    // Giải bóng rổ + đội NBA/WNBA (nguồn m3u8 thường chỉ ghi "Atlanta Hawks vs Brooklyn Nets" mà không có chữ NBA).
    // Chỉ dùng tên đầy đủ với các biệt danh dễ trùng bóng đá (Nets, Heat, Magic, Kings, Suns, Spurs, Jazz, Thunder, Bulls, Hawks, Hornets...).
    private val BASKETBALL_RE = Regex(
        """\b(""" +
            "nba|wnba|g league|nba2k|ncaab|ncaa basketball|vba|fiba|nbl|cba|kbl|pba|bsn|acb|liga acb|bcl|eurocup|eurobasket|" +
            "euroleague|basketball|basket|bong ro|b ?league|lega basket|vtb united|" +
            "atlanta hawks|boston celtics|brooklyn nets|charlotte hornets|chicago bulls|cleveland cavaliers|dallas mavericks|" +
            "denver nuggets|detroit pistons|golden state warriors|houston rockets|indiana pacers|la clippers|los angeles clippers|" +
            "los angeles lakers|la lakers|memphis grizzlies|miami heat|milwaukee bucks|minnesota timberwolves|new orleans pelicans|" +
            "new york knicks|oklahoma city thunder|okc thunder|orlando magic|philadelphia 76ers|phoenix suns|portland trail blazers|" +
            "sacramento kings|san antonio spurs|toronto raptors|utah jazz|washington wizards|" +
            "las vegas aces|new york liberty|seattle storm|chicago sky|connecticut sun|atlanta dream|indiana fever|minnesota lynx|" +
            "phoenix mercury|washington mystics|dallas wings|los angeles sparks|golden state valkyries|" +
            "lakers|celtics|cavaliers|mavericks|nuggets|pistons|clippers|grizzlies|timberwolves|pelicans|knicks|76ers|sixers|" +
            "trail blazers|raptors|pacers|bucks|timberwolves" +
            """)\b"""
    )

    private val RULES = listOf(
        // Các môn khác bóng đá được kiểm tra trước để "Volleyball Nations League" không bị tính là bóng đá
        Sport.BASKETBALL to BASKETBALL_RE,
        Sport.VOLLEYBALL to Regex("""\b(volleyball|volley|bong chuyen|vnl|fivb)\b"""),
        Sport.TENNIS to Regex("""\b(tennis|atp|wta|wimbledon|roland garros|davis cup|billie jean king|grand slam|us open|australian open|itf)\b"""),
        Sport.BADMINTON to Regex("""\b(badminton|cau long|bwf|all england|thomas cup|uber cup|sudirman)\b"""),
        Sport.BILLIARDS to Regex("""\b(billiards?|bida|bi[ -]a|snooker|carom|three cushion|3 cushion|nine ball|eight ball|ten ball|[89] ?-?ball|10 ?-?ball|pool billiards?|wpa|uk pool)\b"""),
        Sport.ESPORTS to Regex("""\b(esports?|e ?-?sports?|league of legends|lol|lck|lec|lcs|vcs|msi|dota ?2?|cs ?go|cs ?2|counter ?-?strike|valorant|vct|pubg|pmgc|mobile legends|mlbb|lien quan|aov|arena of valor|honor of kings|free fire|fortnite|overwatch|rainbow six|apex legends|starcraft|rocket league|tft|pgl|esl|iem|fifa online|ea fc online|fc online)\b"""),
        Sport.FOOTBALL to Regex("""\b(football|soccer|bong da|premier league|epl|la ?liga|serie a|bundesliga|ligue 1|champions league|europa league|conference league|uefa|fifa|world cup|v ?league|afc|copa|mls|j ?league|k ?league|fa cup|carabao|efl|nations league|ngoai hang|giao huu|friendly|cup of nations|afcon|wk ?league|w ?league|futsal|fc|u ?\d{2})\b""")
    )

    private fun fold(t: String): String =
        Normalizer.normalize(t, Normalizer.Form.NFD).replace(MARKS, "")
            .replace('đ', 'd').replace('Đ', 'D').lowercase()

    // Môn không có trong bộ lọc của app (cricket, bóng bầu dục, đua xe...): không đoán bừa là bóng đá.
    private val OTHER_RE = Regex("""\b(cricket|ipl|t20|odi|bbl|psl|rugby|baseball|mlb|nfl|nhl|hockey|mma|ufc|boxing|golf|nascar|f1|formula 1|motogp|handball|table tennis|bong ban|darts|cycling|swimming)\b""")

    fun detect(vararg texts: String?): Sport? {
        val joined = fold(texts.filterNotNull().joinToString(" | "))
        if (joined.isBlank()) return null
        return RULES.firstOrNull { it.second.containsMatchIn(joined) }?.first
    }

    /**
     * Link nguồn mới ghi tên giải bằng nhiều ngôn ngữ ("Nữ Mexico", "Giao Hữu Quốc Tế", "Aus Champ", "WK-League"...)
     * nên chỉ dựa vào từ khoá sẽ bỏ sót phần lớn trận bóng đá và bộ lọc môn trở nên trống.
     * Không nhận ra môn nào (và không phải môn ngoài danh sách) => coi là bóng đá, giống cách đọc nguồn JSON.
     */
    fun detectOrFootball(vararg texts: String?): Sport? {
        detect(*texts)?.let { return it }
        val joined = fold(texts.filterNotNull().joinToString(" | "))
        if (joined.isBlank()) return Sport.FOOTBALL
        return if (OTHER_RE.containsMatchIn(joined)) null else Sport.FOOTBALL
    }

    /** Đọc thuộc tính x-sport do playlist ghi: FOOTBALL / BASKETBALL... hoặc tên tiếng Việt ("Bóng đá"). */
    fun fromTag(tag: String?): Sport? {
        val t = tag?.trim().orEmpty()
        if (t.isEmpty()) return null
        Sport.values().firstOrNull { it.name.equals(t, ignoreCase = true) }?.let { return it }
        return detect(t)
    }
}
