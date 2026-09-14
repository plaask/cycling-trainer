package io.github.cyclingtrainer.app.session

/**
 * Heart-rate reference + zone model used by the train UI and settings.
 *
 * The athlete configures a lactate-threshold heart rate (LTHR) and/or a
 * maximum heart rate, then picks which one the zones derive from:
 *
 *  - LTHR -> 7-zone scale (percentages of LTHR):
 *      Z1 0–80 %      Active Recovery   恢复
 *      Z2 81–88 %     Aerobic           有氧
 *      Z3 89–93 %     Tempo             节奏
 *      Z4 94–99 %     SubThreshold      亚阈值
 *      Z5 100–102 %   SuperThreshold    超阈值
 *      Z6 103–105 %   Aerobic Capacity  有氧容量
 *      Z7 >=106 %     Anaerobic         无氧
 *  - MaxHR -> classic 5-zone scale (percentages of max HR):
 *      50-60 / 60-70 / 70-80 / 80-90 / 90-100 % (恢复/有氧/节奏/阈值/无氧)
 *
 * Boundaries are percentages of the reference value; the reference bpm the
 * user enters decides where they land. Zones are open at the top (Z7/Z5
 * ">= low").
 */
object HrZones {

    /** Which reference value the user's zones are based on. */
    enum class Reference { LTHR, MAX_HR }

    /** One zone: percentage span plus human label. */
    data class Zone(
        val index: Int,
        /** Percent of reference at which the zone starts (inclusive). */
        val lowPct: Int,
        /** Percent of reference at which the zone ends (inclusive); null = open top. */
        val highPct: Int?,
        val label: String,
    )

    /** Zone table per reference. Percent boundaries are user-facing and match
     *  the calibration tables agreed with the athlete. */
    fun table(reference: Reference): List<Zone> = when (reference) {
        Reference.LTHR -> listOf(
            Zone(1, 0, 80, "恢复"),
            Zone(2, 81, 88, "有氧"),
            Zone(3, 89, 93, "节奏"),
            Zone(4, 94, 99, "亚阈值"),
            Zone(5, 100, 102, "超阈值"),
            Zone(6, 103, 105, "有氧容量"),
            Zone(7, 106, null, "无氧"),
        )
        Reference.MAX_HR -> listOf(
            Zone(1, 50, 59, "恢复"),
            Zone(2, 60, 69, "有氧"),
            Zone(3, 70, 79, "节奏"),
            Zone(4, 80, 89, "阈值"),
            Zone(5, 90, null, "无氧"),
        )
    }

    /** Zone number (1..7 for LTHR / 1..5 for MaxHR) containing [bpm], or null
     *  when below the first zone's floor. Uses the exact percentage, so the
     *  tables' example bpm values (e.g. 131 bpm @ LTHR 163 = 80.4 % -> Z2)
     *  land in the same zone as their percent column. */
    fun zoneOf(reference: Reference, refBpm: Int, bpm: Int): Int? {
        if (refBpm <= 0) return null
        val pct = bpm * 100.0 / refBpm
        return table(reference).firstOrNull { z ->
            z.highPct == null || pct <= z.highPct
        }?.index
    }

    /** Short text: "Z3". */
    fun zoneLabel(reference: Reference, refBpm: Int, bpm: Int): String {
        val z = zoneOf(reference, refBpm, bpm) ?: return "—"
        return "Z$z"
    }

    /** Descriptive text: "Z3 节奏 89–93%" (percent span of the reference). */
    fun zoneText(reference: Reference, refBpm: Int, bpm: Int): String {
        val z = zoneOf(reference, refBpm, bpm) ?: return "—"
        val zone = table(reference).first { it.index == z }
        val range = if (zone.highPct == null) "≥${zone.lowPct}%" else "${zone.lowPct}–${zone.highPct}%"
        return "Z$z ${zone.label} $range"
    }
}
