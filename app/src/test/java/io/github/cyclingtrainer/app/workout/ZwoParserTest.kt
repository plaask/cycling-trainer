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
    fun `keeps freeride as a zero-target timeline block and reads metadata`() {
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
        // FreeRide is a real (zero-target) block: it must occupy its time on
        // the timeline, otherwise every later segment would shift earlier.
        assertEquals(2, w.segments.size)
        assertEquals(120, w.totalDurationSeconds)
        assertEquals("FreeRide", w.segments.first().name)
    }

    /**
     * Parses the project's own fixture files from the test classpath, i.e. the
     * same path a course takes when the app reads a .zwo from a real folder:
     * bytes -> InputStream -> parser.
     *
     * These fixtures live in app/src/test/resources and are written for this
     * project; the app ships no bundled workouts, and courses are supplied by
     * the user from a folder they pick.
     */
    @Test
    fun `parses fixture files from the classpath`() {
        val loader = javaClass.classLoader

        val all = loader.getResourceAsStream("fixture-all-elements.zwo")
        assertNotNull("missing fixture-all-elements.zwo", all)
        val w = ZwoParser.parse(all!!)

        assertEquals("Fixture All Elements", w.name)
        assertEquals("test-fixture", w.author)
        assertEquals("bike", w.sportType)
        // Warmup, FreeRide, 2x(interval+recovery), SteadyState, Cooldown
        assertEquals(8, w.segments.size)
        // 600 + 120 + 2*(60+120) + 120 + 120
        assertEquals(1320, w.totalDurationSeconds)
        // A ramp keeps its authored start/end powers.
        assertEquals(0.40, w.segments.first().powerStart, 1e-9)
        assertEquals(0.75, w.segments.first().powerEnd, 1e-9)
        // FreeRide occupies time with no target rather than being dropped.
        assertEquals(SegmentType.FREE_RIDE, w.segments[1].type)
        assertEquals(0.0, w.segments[1].powerStart, 1e-9)
        // An interval pair keeps its on/off powers.
        assertEquals(1.05, w.segments[2].powerStart, 1e-9)
        assertEquals(0.55, w.segments[3].powerStart, 1e-9)
        // Cadence is carried through.
        assertEquals(90, w.segments[6].cadence)

        val single = ZwoParser.parse(loader.getResourceAsStream("fixture-single-segment.zwo")!!)
        assertEquals(1, single.segments.size)
        assertEquals(300, single.totalDurationSeconds)
        // UTF-8 in the name survives the stream round trip.
        assertEquals("Fixture 单段课程", single.name)
    }
}
