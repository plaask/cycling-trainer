package io.github.cyclingtrainer.app.session

/**
 * Power zone model based on the athlete's FTP — 7-zone scale (percentages
 * of FTP) used by the train UI:
 *
 *   Z1 0–55 %     Active Recovery  主动恢复
 *   Z2 56–75 %    Endurance        耐力
 *   Z3 76–90 %    Tempo            节奏
 *   Z4 91–105 %   Threshold        阈值
 *   Z5 106–120 %  VO2 Max          最大摄氧
 *   Z6 121–150 %  Anaerobic        无氧
 *   Z7 >=151 %    Neuromuscular    神经肌肉
 *
 * Zones are open at the top (Z7 ">= low").
 */
object PowerZones {

    data class Zone(
        val index: Int,
        /** Percent of FTP at which the zone starts (inclusive). */
        val lowPct: Int,
        /** Percent of FTP at which the zone ends (inclusive); null = open top. */
        val highPct: Int?,
        val label: String,
    )

    fun table(): List<Zone> = listOf(
        Zone(1, 0, 55, "主动恢复"),
        Zone(2, 56, 75, "耐力"),
        Zone(3, 76, 90, "节奏"),
        Zone(4, 91, 105, "阈值"),
        Zone(5, 106, 120, "最大摄氧"),
        Zone(6, 121, 150, "无氧"),
        Zone(7, 151, null, "神经肌肉"),
    )

    /** Zone number (1..7) containing [watts]; the table starts at 0 % so any
     *  reading maps to some zone. Uses the exact percentage, matching the
     *  calibrated bpm columns (e.g. 205 W @ FTP 170 = 120.6 % -> Z6). */
    fun zoneOf(ftpWatts: Int, watts: Int): Int? {
        if (ftpWatts <= 0) return null
        val pct = watts * 100.0 / ftpWatts
        return table().firstOrNull { z ->
            z.highPct == null || pct <= z.highPct
        }?.index
    }

    /** Short text: "Z3". */
    fun zoneLabel(ftpWatts: Int, watts: Int): String {
        val z = zoneOf(ftpWatts, watts) ?: return "—"
        return "Z$z"
    }

    /** Descriptive text: "Z3 节奏 76–90% FTP". */
    fun zoneText(ftpWatts: Int, watts: Int): String {
        val z = zoneOf(ftpWatts, watts) ?: return "—"
        val zone = table().first { it.index == z }
        val range = if (zone.highPct == null) "≥${zone.lowPct}%" else "${zone.lowPct}–${zone.highPct}%"
        return "Z$z ${zone.label} $range"
    }
}
