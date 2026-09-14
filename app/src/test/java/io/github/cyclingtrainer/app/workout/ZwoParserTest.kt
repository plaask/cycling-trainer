package io.github.cyclingtrainer.app.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZwoParserTest {

    @Test
    fun `parses minimal warmup steady cooldown`() {
        val xml = """
            <workout_file>
              <author>t</author>
              <name>W1</name>
              <description>desc</description>
              <sportType>bike</sportType>
              <workout>
                <Warmup Duration="600" PowerLow="0.4" PowerHigh="0.8"/>
                <SteadyState Duration="300" Power="0.9"/>
                <Cooldown Duration="300" PowerLow="0.8" PowerHigh="0.4"/>
              </workout>
            </workout_file>
        """.trimIndent()
        val w = ZwoParser.parse(xml)
        assertEquals("W1", w.name)
        assertEquals(3, w.segments.size)
        assertEquals(1200, w.totalDurationSeconds)
        assertEquals(0.4, w.segments[0].powerStart, 1e-9)
        assertEquals(0.8, w.segments[0].powerEnd, 1e-9)
        assertEquals(0.9, w.segments[1].powerStart, 1e-9)
    }

    @Test
    fun `expands intervals into on off segments`() {
        val xml = """
            <workout_file><name>I</name><workout>
              <IntervalsT Repeat="3" OnDuration="60" OnPower="1.1" OffDuration="30" OffPower="0.5"/>
            </workout></workout_file>
        """.trimIndent()
        val w = ZwoParser.parse(xml)
        assertEquals(6, w.segments.size)
        assertEquals(3 * (60 + 30), w.totalDurationSeconds)
        assertEquals(SegmentType.INTERVAL, w.segments[0].type)
        assertEquals(SegmentType.RECOVERY, w.segments[1].type)
    }

    @Test
    fun `power curve ramps linearly within warmup`() {
        val xml = """
            <workout_file><name>R</name><workout>
              <Warmup Duration="600" PowerLow="0.4" PowerHigh="0.8"/>
              <SteadyState Duration="100" Power="1.0"/>
            </workout></workout_file>
        """.trimIndent()
        val w = ZwoParser.parse(xml)
        assertEquals(0.4, w.powerFractionAt(0.0), 1e-9)
        assertEquals(0.6, w.powerFractionAt(300.0), 1e-9)
        assertEquals(0.8, w.powerFractionAt(600.0), 1e-9)
        assertEquals(1.0, w.powerFractionAt(650.0), 1e-9)
        // beyond end clamps to last
        assertEquals(1.0, w.powerFractionAt(5000.0), 1e-9)
    }

    @Test
    fun `rejects missing power attribute`() {
        val xml = """
            <workout_file><workout>
              <SteadyState Duration="60"/>
            </workout></workout_file>
        """.trimIndent()
        val thrown = try { ZwoParser.parse(xml); null } catch (e: ZwoParseException) { e }
        assertNotNull(thrown)
    }

    @Test
    fun `skips freeride and reads metadata`() {
        val xml = """
            <workout_file><author>me</author><sportType>bike</sportType>
              <workout>
                <FreeRide Duration="60" FlatRoad="1"/>
                <SteadyState Duration="60" Power="0.9"/>
              </workout>
            </workout_file>
        """.trimIndent()
        val w = ZwoParser.parse(xml)
        assertEquals("me", w.author)
        assertEquals(1, w.segments.size)
    }

    @Test
    fun `parses all preset workout files`() {
        val loader = javaClass.classLoader
        val names = listOf(
            "endurance_60min_z2.zwo", "ftp-ramp-test.zwo", "progressive_ramp_45min.zwo",
            "recovery_30min.zwo", "sweet_spot_3x8.zwo", "tempo_2x15.zwo",
            "threshold_2x10.zwo", "threshold_overunder_50min.zwo", "vo2max_5x3.zwo",
        )
        var total = 0
        for (n in names) {
            val res = loader.getResourceAsStream(n)
            assertNotNull("missing $n", res)
            val w = ZwoParser.parse(res!!)
            assertTrue("$n has segments", w.segments.isNotEmpty())
            assertTrue("$n total>0", w.totalDurationSeconds > 0)
            total += w.totalDurationSeconds
        }
        assertTrue(total > 0)
    }
}
