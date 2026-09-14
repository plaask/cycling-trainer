package io.github.cyclingtrainer.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Calibration tables agreed with the athlete:
 *  power:  Z1 0-55%  Z2 56-75%  Z3 76-90%  Z4 91-105%  Z5 106-120%  Z6 121-150%  Z7 151%+
 *  HR LTHR: Z1 0-80%  Z2 81-88%  Z3 89-93%  Z4 94-99%  Z5 100-102%  Z6 103-105%  Z7 106%+
 *  HR Max: classic 50/60/70/80/90 five zones.
 */
class ZoneCalibrationTest {

    // ---- power: FTP 170 -> example boundaries from the table ----
    @Test
    fun `power zone boundaries follow the 7-zone FTP table`() {
        val ftp = 170
        // table examples: 93/127/153/178/204/255 are zone *tops* (55/75/90/105/120/150 %)
        assertEquals(1, PowerZones.zoneOf(ftp, 93))
        assertEquals(2, PowerZones.zoneOf(ftp, 94))
        assertEquals(2, PowerZones.zoneOf(ftp, 127))
        assertEquals(3, PowerZones.zoneOf(ftp, 128))
        assertEquals(3, PowerZones.zoneOf(ftp, 153))
        assertEquals(4, PowerZones.zoneOf(ftp, 154))
        assertEquals(4, PowerZones.zoneOf(ftp, 178))
        assertEquals(5, PowerZones.zoneOf(ftp, 179))
        assertEquals(5, PowerZones.zoneOf(ftp, 204))
        assertEquals(6, PowerZones.zoneOf(ftp, 205))
        assertEquals(6, PowerZones.zoneOf(ftp, 255))
        assertEquals(7, PowerZones.zoneOf(ftp, 256))
        // table starts at 0 % so 0 W is Z1
        assertEquals(1, PowerZones.zoneOf(ftp, 0))
        assertNull(PowerZones.zoneOf(0, 100)) // no FTP configured
        // zoneText carries no unit suffix: the UI appends the live %FTP itself
        assertEquals("Z1 主动恢复 0–55%", PowerZones.zoneText(ftp, 50))
    }

    // ---- HR LTHR: percentages of the configured LTHR are authoritative
    // (the bpm columns in the agreed table are that table applied to one
    // athlete). At LTHR 163 the zone breakpoints fall on
    // 130 | 143 | 151 | 161 | 166 | 171 bpm.
    @Test
    fun `lthr zones follow the 7-zone LTHR table`() {
        val ref = HrZones.Reference.LTHR
        val lthr = 163
        assertEquals(1, HrZones.zoneOf(ref, lthr, 130))
        assertEquals(2, HrZones.zoneOf(ref, lthr, 131))
        assertEquals(2, HrZones.zoneOf(ref, lthr, 143))
        assertEquals(3, HrZones.zoneOf(ref, lthr, 144))
        assertEquals(3, HrZones.zoneOf(ref, lthr, 151))
        assertEquals(4, HrZones.zoneOf(ref, lthr, 152))
        assertEquals(4, HrZones.zoneOf(ref, lthr, 161))
        assertEquals(5, HrZones.zoneOf(ref, lthr, 162))
        assertEquals(5, HrZones.zoneOf(ref, lthr, 166))
        assertEquals(6, HrZones.zoneOf(ref, lthr, 167))
        assertEquals(6, HrZones.zoneOf(ref, lthr, 171))
        assertEquals(7, HrZones.zoneOf(ref, lthr, 172))
        assertEquals("Z7 无氧 ≥106%", HrZones.zoneText(ref, lthr, 180))
    }

    @Test
    fun `max hr zones stay on the classic 5-zone table`() {
        val ref = HrZones.Reference.MAX_HR
        val max = 190
        assertEquals(1, HrZones.zoneOf(ref, max, 100))
        assertEquals(2, HrZones.zoneOf(ref, max, 120))
        assertEquals(3, HrZones.zoneOf(ref, max, 140))
        assertEquals(4, HrZones.zoneOf(ref, max, 160))
        assertEquals(5, HrZones.zoneOf(ref, max, 180))
        assertEquals(5, HrZones.zoneOf(ref, max, 220)) // above max stays in Z5
    }
}
