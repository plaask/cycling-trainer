package io.github.cyclingtrainer.app.session

import java.io.ByteArrayOutputStream

/**
 * Minimal, dependency-free writer for Garmin .FIT activity files (FIT 2.0).
 *
 * The FIT SDKs are huge generated trees; this project keeps third-party
 * libraries out, so the few messages we need are encoded by hand. Field
 * numbers below were verified against the auto-generated Garmin FIT Java SDK
 * (profile 21.x) — do NOT "adjust" them from memory.
 *
 * Message order: FILE_ID (0) -> DEVICE_INFO (23) -> EVENT (21) ->
 * RECORD (20) xN -> LAP (19) -> SESSION (18) -> ACTIVITY (34).
 *
 * Wire format: a 14-byte header, then message blocks. Each message starts
 * with a Definition message (the first time a global message number appears)
 * listing (field-number, size, base-type) per field, followed by one Data
 * message holding the little-endian payload bytes in that order.
 *
 * Base-type byte = type code | 0x80 endian flag for multi-byte types:
 * enum=0x00, sint8=0x01, uint8=0x02, string=0x07, uint8z=0x0A, sint16=0x83,
 * uint16=0x84, sint32=0x85, uint32=0x86, uint32z=0x8C, float32=0x88.
 * All multi-byte values are little-endian.
 *
 * Fields whose unit has a scale (e.g. seconds stored as s x 1000 in a
 * uint32) must be pre-scaled by the caller; see the profile for scales.
 */
object FitWriter {

    // ---- base type wire bytes (type code | endian flag 0x80 for >1 byte) ----
    private const val BT_ENUM = 0x00
    private const val BT_SINT8 = 0x01
    private const val BT_UINT8 = 0x02
    private const val BT_SINT16 = 0x83
    private const val BT_UINT16 = 0x84
    private const val BT_SINT32 = 0x85
    private const val BT_UINT32 = 0x86
    private const val BT_STRING = 0x07
    private const val BT_UINT8Z = 0x0A
    private const val BT_UINT16Z = 0x8B
    private const val BT_UINT32Z = 0x8C
    private const val BT_FLOAT32 = 0x88
    private const val BT_FLOAT64 = 0x89

    // sizes in bytes
    private const val SZ_ENUM = 1
    private const val SZ_UINT8 = 1
    private const val SZ_UINT16 = 2
    private const val SZ_UINT32 = 4
    private const val SZ_SINT8 = 1
    private const val SZ_SINT16 = 2
    private const val SZ_SINT32 = 4
    private const val SZ_FLOAT32 = 4

    // ---- invalid sentinels (absent values) ----
    const val INVALID_UINT8 = 0xFF
    const val INVALID_UINT16 = 0xFFFF
    const val INVALID_UINT32 = 0xFFFFFFFFL
    const val INVALID_SINT8 = 0x7F
    const val INVALID_SINT16 = 0x7FFF
    const val INVALID_SINT32 = 0x7FFFFFFFL

    // Global message numbers per the FIT profile.
    const val MSG_FILE_ID = 0
    const val MSG_DEVICE_INFO = 23
    const val MSG_EVENT = 21
    const val MSG_RECORD = 20
    const val MSG_LAP = 19
    const val MSG_SESSION = 18
    const val MSG_ACTIVITY = 34

    // Value enums from the FIT profile.
    const val FILE_TYPE_ACTIVITY = 4
    const val MFG_DEVELOPMENT = 255
    const val SPORT_CYCLING = 2
    const val SUB_SPORT_GENERIC = 0
    const val EVENT_TIMER = 0
    const val EVENT_TYPE_START = 0
    const val EVENT_TYPE_STOP = 1
    const val INTENSITY_ACTIVE = 0
    const val LAP_TRIGGER_TIME = 1
    const val SESSION_TRIGGER_ACTIVITY_END = 0

    // ---- field definitions: (number, size, baseType, scale) ----
    private data class F(val num: Int, val size: Int, val base: Int, val scale: Double = 1.0)

    // FILE_ID (msg 0) — fields from FileIdMesg.java.
    private val fileIdFields = listOf(
        F(0, SZ_ENUM, BT_ENUM),       // type
        F(1, SZ_UINT16, BT_UINT16),   // manufacturer
        F(2, SZ_UINT16, BT_UINT16),   // product
        F(3, SZ_UINT32, BT_UINT32Z),  // serial_number
        F(4, SZ_UINT32, BT_UINT32),   // time_created
        F(8, 16, BT_STRING),          // product_name (optional; fixed 16B)
    )
    // DEVICE_INFO (msg 23) — fields from DeviceInfoMesg.java.
    private val deviceInfoFields = listOf(
        F(0, SZ_ENUM, BT_ENUM),       // device_index
        F(2, SZ_UINT16, BT_UINT16),   // manufacturer
        F(3, SZ_UINT32, BT_UINT32Z),  // serial_number
        F(4, SZ_UINT16, BT_UINT16),   // product
        F(5, SZ_UINT16, BT_UINT16, 100.0), // software_version (v x 100)
        F(27, 32, BT_STRING),         // product_name
    )
    // EVENT (msg 21) — fields from EventMesg.java (253 last, ascending).
    private val eventFields = listOf(
        F(0, SZ_ENUM, BT_ENUM),       // event
        F(1, SZ_ENUM, BT_ENUM),       // event_type
        F(2, SZ_ENUM, BT_ENUM),       // event_group (0 = default)
        F(253, SZ_UINT32, BT_UINT32), // timestamp
    )
    // RECORD (msg 20) — fields from RecordMesg.java (253 last, ascending).
    private val recordFields = listOf(
        F(0, SZ_UINT32, BT_SINT32),   // position_lat (invalid)
        F(1, SZ_UINT32, BT_SINT32),   // position_long (invalid)
        F(2, SZ_UINT16, BT_UINT16),   // altitude (invalid)
        F(3, 1, BT_UINT8),            // heart_rate
        F(4, 1, BT_UINT8),            // cadence
        F(5, SZ_UINT32, BT_UINT32),   // distance (invalid)
        F(6, SZ_UINT16, BT_UINT16, 1000.0), // speed (invalid)
        F(7, SZ_UINT16, BT_UINT16),   // power
        F(253, SZ_UINT32, BT_UINT32), // timestamp
    )
    // LAP (msg 19) — fields from LapMesg.java (253,254 last, ascending).
    private val lapFields = listOf(
        F(0, SZ_ENUM, BT_ENUM),       // event
        F(1, SZ_ENUM, BT_ENUM),       // event_type
        F(2, SZ_UINT32, BT_UINT32),   // start_time
        F(7, SZ_UINT32, BT_UINT32, 1000.0), // total_elapsed_time (s x1000)
        F(8, SZ_UINT32, BT_UINT32, 1000.0), // total_timer_time (s x1000)
        F(9, SZ_UINT32, BT_UINT32),   // total_distance (invalid)
        F(13, SZ_UINT16, BT_UINT16, 1000.0), // avg_speed (invalid)
        F(19, SZ_UINT16, BT_UINT16),  // avg_power
        F(20, SZ_UINT16, BT_UINT16),  // max_power
        F(23, SZ_ENUM, BT_ENUM),      // intensity
        F(24, SZ_ENUM, BT_ENUM),      // lap_trigger
        F(25, SZ_ENUM, BT_ENUM),      // sport
        F(253, SZ_UINT32, BT_UINT32), // timestamp
        F(254, SZ_UINT16, BT_UINT16), // message_index
    )
    // SESSION (msg 18) — fields from SessionMesg.java (253,254 last).
    private val sessionFields = listOf(
        F(0, SZ_ENUM, BT_ENUM),       // event
        F(1, SZ_ENUM, BT_ENUM),       // event_type
        F(2, SZ_UINT32, BT_UINT32),   // start_time
        F(5, SZ_ENUM, BT_ENUM),       // sport
        F(6, SZ_ENUM, BT_ENUM),       // sub_sport
        F(7, SZ_UINT32, BT_UINT32, 1000.0), // total_elapsed_time
        F(8, SZ_UINT32, BT_UINT32, 1000.0), // total_timer_time
        F(9, SZ_UINT32, BT_UINT32),   // total_distance (invalid)
        F(11, SZ_UINT16, BT_UINT16),  // total_calories (0)
        F(14, SZ_UINT16, BT_UINT16, 1000.0), // avg_speed (invalid)
        F(15, SZ_UINT16, BT_UINT16, 1000.0), // max_speed (invalid)
        F(16, 1, BT_UINT8),           // avg_heart_rate
        F(17, 1, BT_UINT8),           // max_heart_rate
        F(18, 1, BT_UINT8),           // avg_cadence
        F(19, 1, BT_UINT8),           // max_cadence
        F(20, SZ_UINT16, BT_UINT16),  // avg_power
        F(21, SZ_UINT16, BT_UINT16),  // max_power
        F(25, SZ_UINT16, BT_UINT16),  // first_lap_index
        F(26, SZ_UINT16, BT_UINT16),  // num_laps
        F(28, SZ_ENUM, BT_ENUM),      // trigger
        F(253, SZ_UINT32, BT_UINT32), // timestamp
        F(254, SZ_UINT16, BT_UINT16), // message_index
    )
    // ACTIVITY (msg 34) — fields from ActivityMesg.java (253 last).
    private val activityFields = listOf(
        F(0, SZ_UINT32, BT_UINT32, 1000.0), // total_timer_time (s x1000)
        F(1, SZ_UINT16, BT_UINT16),   // num_sessions
        F(2, SZ_ENUM, BT_ENUM),       // type
        F(3, SZ_ENUM, BT_ENUM),       // event
        F(4, SZ_ENUM, BT_ENUM),       // event_type
        F(5, SZ_UINT32, BT_UINT32),   // local_timestamp
        F(253, SZ_UINT32, BT_UINT32), // timestamp
    )

    /** Appends [value] as a little-endian unsigned int of [size] bytes. */
    private fun putUInt(out: ByteArrayOutputStream, value: Long, size: Int) {
        for (i in 0 until size) out.write(((value shr (8 * i)) and 0xFF).toInt())
    }

    /** Current local-message-type -> global-message-number mapping. */
    private val localTypes = HashMap<Int, Int>()

    /**
     * Definition messages already written in this file, keyed by global
     * message number. A Definition only has to appear once: after that the
     * local type alone identifies the layout, so repeating it before every
     * Data message would waste 6 + 3 x fieldCount bytes per record — for
     * RECORD that is 33 bytes on every single second of the ride.
     *
     * (Note: [localTypes] is a field of this object, so [encode] keeps it —
     * and therefore this set — consistent across calls.)
     */
    private val definedMessages = HashSet<Int>()

    /** Returns a stable local type (0..15) for [globalMsgNum]. */
    private fun localTypeFor(globalMsgNum: Int): Int {
        localTypes.entries.firstOrNull { it.value == globalMsgNum }?.let { return it.key }
        val next = localTypes.size
        require(next < 16) { "too many distinct message types (>16)" }
        localTypes[next] = globalMsgNum
        return next
    }

    /**
     * Writes one Data message whose payload is the concatenation of each
     * field value in definition order.
     * [values] entries: Long/Int (raw wire value) or ByteArray (string bytes).
     */
    private fun writeData(
        out: ByteArrayOutputStream,
        localType: Int,
        fields: List<F>,
        values: List<Any>,
    ) {
        require(fields.size == values.size)
        out.write(localType and 0x0F) // data header: local type, no compressed ts
        for ((f, v) in fields.zip(values)) {
            when (v) {
                is Long -> putUInt(out, v, f.size)
                is Int -> putUInt(out, v.toLong(), f.size)
                is ByteArray -> out.write(v)
                else -> throw IllegalArgumentException("bad value for field ${f.num}: $v")
            }
        }
    }

    /**
     * Writes a Data message, preceded by its Definition message the first time
     * this global message number appears in the file. Definition header
     * 0x40 | local type; then reserved, architecture (0 = little endian),
     * global message number LE16, field count, then per-field (field number,
     * size, base type). FIT requires fields sorted ascending by field number.
     */
    private fun writeMessage(
        out: ByteArrayOutputStream,
        globalMsgNum: Int,
        fields: List<F>,
        values: List<Any>,
    ) {
        require(fields.size == values.size) { "field/value mismatch in msg $globalMsgNum" }
        require(fields.zipWithNext().all { (a, b) -> a.num < b.num }) {
            "fields not sorted for message $globalMsgNum"
        }
        val lt = localTypeFor(globalMsgNum)
        if (definedMessages.add(globalMsgNum)) {
            out.write(0x40 or lt) // definition header, local type
            out.write(0x00) // reserved
            out.write(0x00) // architecture: little endian
            putUInt(out, globalMsgNum.toLong(), 2)
            out.write(fields.size)
            for (f in fields) {
                out.write(f.num and 0xFF)
                out.write(f.size and 0xFF)
                out.write(f.base and 0xFF)
            }
        }
        writeData(out, lt, fields, values)
    }

    /** Encodes a string field of exactly [size] bytes (null-terminated). */
    private fun stringField(value: String, size: Int): ByteArray {
        val b = ByteArray(size)
        val bytes = value.toByteArray(Charsets.UTF_8).copyOf(size)
        System.arraycopy(bytes, 0, b, 0, bytes.size)
        return b
    }

    /** Scale + round a double seconds value into the x1000 uint32 units. */
    private fun secsTo1000(secs: Double): Long =
        (secs * 1000.0).toLong().coerceIn(0, INVALID_UINT32 - 1)

    /**
     * Encodes a full activity .FIT payload (header + messages + CRC).
     *
     * [samples] are 1 Hz rows; the first sample is anchored at
     * [startEpochSec] (seconds since the FIT epoch 1989-12-31 UTC) and each
     * row's timestamp is start + elapsedSeconds. Rows whose sensors had no
     * reading carry the field's invalid sentinel.
     *
     * @param workoutName optional ride label; written to file_id.product_name
     *                    when short enough (20-char cap per profile).
     */
    fun encode(
        samples: List<RideSample>,
        startEpochSec: Long,
        serial: Long,
        workoutName: String?,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        localTypes.clear()
        definedMessages.clear()

        // ----- header (14 bytes, FIT 2.0) -----
        // [0] header size, [1] protocol version, [2..3] profile version LE16,
        // [4..7] data size u32 LE, [8..11] ".FIT", [12..13] header CRC.
        out.write(14)                  // header size
        out.write(0x20)                // protocol version 2.0
        out.write(0x84); out.write(0x00) // profile version 21.00 LE16
        out.write(0xE9); out.write(0x04); out.write(0x00); out.write(0x00) // data size LE32 placeholder
        out.write(0x2E)                // '.'
        out.write(0x46)                // 'F'
        out.write(0x49)                // 'I'
        out.write(0x54)                // 'T'
        // header CRC placeholder (bytes 12..13): computed below.
        out.write(0x00); out.write(0x00)
        val dataStart = out.size()

        // ----- messages -----
        val productName = workoutName?.take(20)
        // 1) FILE_ID — mandatory first message.
        writeMessage(
            out, MSG_FILE_ID, fileIdFields,
            listOf(
                FILE_TYPE_ACTIVITY.toLong(),
                MFG_DEVELOPMENT.toLong(),
                0L,
                serial,
                startEpochSec,
                if (productName != null) stringField(productName, 16) else ByteArray(16),
            )
        )
        // 2) DEVICE_INFO (best practice).
        writeMessage(
            out, MSG_DEVICE_INFO, deviceInfoFields,
            listOf(
                0L, // device_index = creator
                MFG_DEVELOPMENT.toLong(),
                serial,
                0L,
                100L, // software_version 1.00 (v x100)
                ByteArray(32), // product_name blank
            )
        )

        // 3) EVENT start.
        writeMessage(
            out, MSG_EVENT, eventFields,
            listOf(EVENT_TIMER.toLong(), EVENT_TYPE_START.toLong(), 0L, startEpochSec)
        )

        // 4) RECORD rows.
        for (s in samples) {
            val ts = startEpochSec + s.elapsedSeconds
            val hr = s.heartRateBpm?.coerceIn(0, 255)?.toLong() ?: INVALID_UINT8.toLong()
            val cad = s.cadenceRpm?.toInt()?.coerceIn(0, 255)?.toLong()
                ?: INVALID_UINT8.toLong()
            val pow = s.powerWatts?.coerceIn(0, 65535)?.toLong() ?: INVALID_UINT16.toLong()
            // position_lat/long, altitude, distance, speed left invalid
            // (indoor ride without GPS); speed would be 0 for stationary.
            writeMessage(
                out, MSG_RECORD, recordFields,
                listOf(
                    INVALID_SINT32,          // position_lat
                    INVALID_SINT32,          // position_long
                    INVALID_UINT16.toLong(), // altitude
                    hr,
                    cad,
                    INVALID_UINT32,          // distance
                    INVALID_UINT16.toLong(), // speed
                    pow,
                    ts,
                )
            )
        }
        val stopTs = startEpochSec + samples.size
        val totalSecs = samples.size.toDouble() // paused gaps omitted; 1 Hz

        // 5) LAP.
        val lapAvgPow = avgOrInvalid(samples.mapNotNull { it.powerWatts }, INVALID_UINT16)
        val lapMaxPow = samples.mapNotNull { it.powerWatts }.maxOrNull()
            ?.toLong() ?: INVALID_UINT16.toLong()
        writeMessage(
            out, MSG_LAP, lapFields,
            listOf(
                EVENT_TIMER.toLong(),
                EVENT_TYPE_STOP.toLong(),
                startEpochSec,
                secsTo1000(totalSecs),
                secsTo1000(totalSecs),
                INVALID_UINT32,
                INVALID_UINT16.toLong(),
                lapAvgPow,
                lapMaxPow,
                INTENSITY_ACTIVE.toLong(),
                LAP_TRIGGER_TIME.toLong(),
                SPORT_CYCLING.toLong(),
                stopTs,
                0L, // message_index
            )
        )

        // 6) SESSION.
        val avgHr = samples.mapNotNull { it.heartRateBpm }.takeIf { it.isNotEmpty() }
            ?.let { it.sum() / it.size }?.toLong() ?: INVALID_UINT8.toLong()
        val maxHr = samples.mapNotNull { it.heartRateBpm }.maxOrNull()
            ?.toLong() ?: INVALID_UINT8.toLong()
        val avgCad = samples.mapNotNull { it.cadenceRpm }.takeIf { it.isNotEmpty() }
            ?.let { (it.sum() / it.size).toInt() }?.toLong() ?: INVALID_UINT8.toLong()
        val maxCad = samples.mapNotNull { it.cadenceRpm }.maxOrNull()
            ?.let { it.toInt() }?.toLong() ?: INVALID_UINT8.toLong()
        val avgPow = avgOrInvalid(samples.mapNotNull { it.powerWatts }, INVALID_UINT16)
        val maxPow = samples.mapNotNull { it.powerWatts }.maxOrNull()
            ?.toLong() ?: INVALID_UINT16.toLong()
        writeMessage(
            out, MSG_SESSION, sessionFields,
            listOf(
                EVENT_TIMER.toLong(),
                EVENT_TYPE_STOP.toLong(),
                startEpochSec,
                SPORT_CYCLING.toLong(),
                SUB_SPORT_GENERIC.toLong(),
                secsTo1000(totalSecs),
                secsTo1000(totalSecs),
                INVALID_UINT32,
                0L, // total_calories
                INVALID_UINT16.toLong(), // avg_speed
                INVALID_UINT16.toLong(), // max_speed
                avgHr,
                maxHr,
                avgCad,
                maxCad,
                avgPow,
                maxPow,
                0L, // first_lap_index
                1L, // num_laps
                SESSION_TRIGGER_ACTIVITY_END.toLong(),
                stopTs,
                0L, // message_index
            )
        )

        // 7) ACTIVITY.
        writeMessage(
            out, MSG_ACTIVITY, activityFields,
            listOf(
                secsTo1000(totalSecs),
                1L, // num_sessions
                0L, // type = manual? activity type 0 general
                EVENT_TIMER.toLong(),
                EVENT_TYPE_STOP.toLong(),
                stopTs, // local_timestamp
                stopTs,
            )
        )

        // ----- patch data size + header CRC, append file CRC -----
        val data = out.toByteArray()
        val dataSize = data.size - dataStart
        // The header stores data size as a u32 — rides of any realistic
        // length fit; only >4 GiB would overflow.
        require(dataSize.toLong() <= 0xFFFFFFFFL) {
            "FIT data block too large ($dataSize bytes)"
        }
        data[4] = (dataSize and 0xFF).toByte()
        data[5] = ((dataSize shr 8) and 0xFF).toByte()
        data[6] = ((dataSize shr 16) and 0xFF).toByte()
        data[7] = ((dataSize shr 24) and 0xFF).toByte()
        // Header CRC covers bytes 0..11 of the 14-byte header.
        val headerCrc = crc16(data, 12)
        data[12] = (headerCrc and 0xFF).toByte()
        data[13] = ((headerCrc shr 8) and 0xFF).toByte()
        // File CRC covers the whole file incl. header (all 14 bytes).
        val crc = crc16(data, data.size)
        return data + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
    }

    private fun avgOrInvalid(values: List<Int>, invalid: Int): Long =
        if (values.isEmpty()) invalid.toLong()
        else (values.sum() / values.size).toLong()

    /** CRC-16/ARC (poly 0xA001 reflected), the FIT file checksum. */
    private fun crc16(data: ByteArray, len: Int): Int {
        var crc = 0
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (j in 0 until 8) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc
    }
}
