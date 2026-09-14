package io.github.cyclingtrainer.app.workout

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.StringReader

/**
 * Parser for the Zwift Workout Format (.zwo).
 *
 * Supported elements inside <workout>:
 *  - <Warmup Duration PowerLow PowerHigh Cadence?>     linear ramp low -> high
 *  - <SteadyState Duration Power Cadence?>             constant power
 *  - <IntervalsT Repeat OnDuration OnPower OffDuration OffPower>  unrolled into
 *    `Repeat` alternating ON (interval) / OFF (recovery) segments
 *  - <Cooldown Duration PowerLow PowerHigh Cadence?>   linear ramp high -> low
 *  - <FreeRide Duration>                                zero-target block that
 *    still occupies its time, so later segments keep their authored offsets
 *
 * All target powers in .zwo are fractions of FTP and stay as-is.
 * Durations are seconds. Missing/invalid attributes throw [ZwoParseException]
 * with a descriptive message instead of silently producing a broken workout.
 */
object ZwoParser {

    fun parse(xml: String): Workout = parseXml(stripBom(xml))

    fun parse(input: InputStream): Workout = parseXml(input.readBytes().decodeToString())

    private fun stripBom(s: String): String = s.removePrefix("\uFEFF")

    private fun parseXml(xml: String): Workout {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))
        return parseDocument(parser)
    }

    private fun parseDocument(p: XmlPullParser): Workout {
        var name = ""
        var description = ""
        var author = ""
        var sportType = "bike"
        var inWorkout = false
        val segments = ArrayList<WorkoutSegment>()

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "workout" -> inWorkout = true
                    "name" -> if (!inWorkout) name = textOrEmpty(p)
                    "description" -> if (!inWorkout) description = textOrEmpty(p)
                    "author" -> if (!inWorkout) author = textOrEmpty(p)
                    "sportType" -> if (!inWorkout) sportType = textOrEmpty(p)
                    "Warmup", "SteadyState", "Cooldown", "IntervalsT", "FreeRide" ->
                        if (inWorkout) segments += parseTimedSegments(p)
                    // "tags", "tag" and everything else: ignored
                }
                XmlPullParser.END_TAG -> if (p.name == "workout") inWorkout = false
            }
            event = p.next()
        }
        if (segments.isEmpty()) throw ZwoParseException("no workout segments found")
        return Workout(
            name = name.ifBlank { "Untitled" },
            description = description,
            author = author,
            sportType = sportType,
            segments = segments,
        )
    }

    /** Reads a start tag's text content, then skips its end tag. */
    private fun textOrEmpty(p: XmlPullParser): String {
        var text = ""
        var event = p.next()
        if (event == XmlPullParser.TEXT) {
            text = p.text ?: ""
            event = p.next()
        }
        // advance past the end tag (or nested junk) to stay in sync
        while (event != XmlPullParser.END_TAG && event != XmlPullParser.END_DOCUMENT) {
            event = p.next()
        }
        return text.trim()
    }

    private fun parseTimedSegments(p: XmlPullParser): List<WorkoutSegment> {
        val attrs = attributes(p)
        val cadence = attrs["Cadence"]?.toIntOrNull()

        return when (p.name) {
            "Warmup" -> {
                val duration = intAttr(attrs, "Duration", p.name)
                listOf(
                    WorkoutSegment(
                        name = "Warmup", type = SegmentType.WARMUP,
                        durationSeconds = duration,
                        powerStart = doubleAttr(attrs, "PowerLow", p.name),
                        powerEnd = doubleAttr(attrs, "PowerHigh", p.name),
                        cadence = cadence,
                    )
                )
            }
            "Cooldown" -> {
                val duration = intAttr(attrs, "Duration", p.name)
                listOf(
                    WorkoutSegment(
                        name = "Cooldown", type = SegmentType.COOLDOWN,
                        durationSeconds = duration,
                        powerStart = doubleAttr(attrs, "PowerLow", p.name),
                        powerEnd = doubleAttr(attrs, "PowerHigh", p.name),
                        cadence = cadence,
                    )
                )
            }
            "SteadyState" -> {
                val duration = intAttr(attrs, "Duration", p.name)
                val power = doubleAttr(attrs, "Power", p.name)
                listOf(
                    WorkoutSegment(
                        name = attrs["name"] ?: "Steady",
                        type = SegmentType.STEADY_STATE,
                        durationSeconds = duration,
                        powerStart = power, powerEnd = power,
                        cadence = cadence,
                    )
                )
            }
            // FreeRide has no target power, but it still occupies time. It is
            // kept as a zero-target block so every later segment stays at its
            // authored offset instead of shifting earlier on the timeline.
            "FreeRide" -> {
                val duration = intAttr(attrs, "Duration", p.name)
                listOf(
                    WorkoutSegment(
                        name = "FreeRide", type = SegmentType.FREE_RIDE,
                        durationSeconds = duration,
                        powerStart = 0.0, powerEnd = 0.0,
                        cadence = cadence,
                    )
                )
            }
            "IntervalsT" -> {
                val repeat = intAttr(attrs, "Repeat", p.name)
                val onDur = intAttr(attrs, "OnDuration", p.name)
                val onPower = doubleAttr(attrs, "OnPower", p.name)
                val offDur = intAttr(attrs, "OffDuration", p.name)
                val offPower = doubleAttr(attrs, "OffPower", p.name)
                val cad = cadence
                List(repeat) { i ->
                    listOf(
                        WorkoutSegment(
                            name = "Interval ${i + 1}", type = SegmentType.INTERVAL,
                            durationSeconds = onDur,
                            powerStart = onPower, powerEnd = onPower,
                            cadence = cad,
                        ),
                        WorkoutSegment(
                            name = "Recovery ${i + 1}", type = SegmentType.RECOVERY,
                            durationSeconds = offDur,
                            powerStart = offPower, powerEnd = offPower,
                            cadence = cad,
                        ),
                    )
                }.flatten()
            }
            else -> throw ZwoParseException("unexpected element ${p.name}")
        }
    }

    private fun attributes(p: XmlPullParser): Map<String, String> {
        val map = HashMap<String, String>()
        for (i in 0 until p.attributeCount) {
            map[p.getAttributeName(i)] = p.getAttributeValue(i)
        }
        return map
    }

    private fun intAttr(attrs: Map<String, String>, key: String, tag: String): Int =
        attrs[key]?.toIntOrNull()
            ?: throw ZwoParseException("<$tag> missing or invalid integer attribute '$key'")

    private fun doubleAttr(attrs: Map<String, String>, key: String, tag: String): Double =
        attrs[key]?.toDoubleOrNull()
            ?: throw ZwoParseException("<$tag> missing or invalid numeric attribute '$key'")
}

class ZwoParseException(message: String) : Exception(message)
