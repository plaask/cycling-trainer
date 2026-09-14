package io.github.cyclingtrainer.app.ble

/** Little-endian reader over a notification payload. */
internal class LeReader(private val data: ByteArray) {
    private var pos = 0
    val remaining: Int get() = data.size - pos

    fun u8(): Int = data[pos++].toInt() and 0xFF

    fun u16(): Int {
        val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    fun u32(): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 4
        return v
    }

    fun skip(n: Int) { pos += n }

    fun has(n: Int): Boolean = pos + n <= data.size
}

/** GATT write value builder (little-endian). */
internal class LeWriter {
    private val out = ArrayList<Byte>(8)

    fun u8(v: Int): LeWriter { out += (v and 0xFF).toByte(); return this }
    fun i16(v: Int): LeWriter {
        out += (v and 0xFF).toByte()
        out += ((v shr 8) and 0xFF).toByte()
        return this
    }
    fun bytes(b: ByteArray): LeWriter { b.forEach { out += it }; return this }
    fun toByteArray(): ByteArray = out.toByteArray()
}
