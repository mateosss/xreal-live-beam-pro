package com.xreal.stereoview.glasses

import java.util.zip.CRC32

/**
 * Wire formats of the XREAL Air 2 Ultra, as implemented by Monado's xreal_air driver
 * (BSL-1.0; camera descrambling by Luka Panio, packet parsing by Tobias Frisch):
 * https://gitlab.freedesktop.org/mateosss/monado/-/tree/mateosss/xreal/src/xrt/drivers/xreal_air
 *
 * Camera: a UVC stream announced as "YUY2 640×241" that really carries one 640×480 8-bit
 * image per frame, from the left and right tracking cameras alternately. The image is split
 * into 128 chunks of 2400 bytes sent in a fixed cyclic order ([CHUNK_MAP]), starting at a
 * chunk that varies per frame. A 1280-byte metadata row follows the image.
 *
 * IMU: HID reports on interface 2 (or 3). Commands start with 0xAA, a CRC-32 of the rest,
 * the length and a message id; sensor reports start with 0x01.
 *
 * Derived from Monado under the Boost Software License 1.0; see
 * docs/licenses/monado-xreal_air-BSL-1.0.txt.
 */
object GlassesProtocol {
    const val VENDOR_ID = 0x3318
    const val AIR_2_ULTRA = 0x0426

    const val WIDTH = 640
    const val HEIGHT = 480
    const val IMAGE_BYTES = WIDTH * HEIGHT
    const val FRAME_BYTES = IMAGE_BYTES + 2 * WIDTH
    private const val CHUNK_SIZE = 2400
    private const val CHUNKS = 128

    /** Order in which the image's chunks appear in a frame, cyclically (from Monado). */
    private val CHUNK_MAP = intArrayOf(
        119, 54, 21, 0, 108, 22, 51, 63, 93, 99, 67, 7, 32, 112, 52,
        43, 14, 35, 75, 116, 64, 71, 44, 89, 18, 88, 26, 61, 70, 56,
        90, 79, 87, 120, 81, 101, 121, 17, 72, 31, 53, 124, 127, 113, 111,
        36, 48, 19, 37, 83, 126, 74, 109, 5, 84, 41, 76, 30, 110, 29,
        12, 115, 28, 102, 105, 62, 103, 20, 3, 68, 49, 77, 117, 125, 106,
        60, 69, 98, 9, 16, 78, 47, 40, 2, 118, 34, 13, 50, 46, 80,
        85, 66, 42, 123, 122, 96, 11, 25, 97, 39, 6, 86, 1, 8, 82,
        92, 59, 104, 24, 15, 73, 65, 38, 58, 10, 23, 33, 55, 57, 107,
        100, 94, 27, 95, 45, 91, 4, 114,
    )

    /** What the metadata row says about a frame. */
    data class FrameInfo(val timestampNs: Long, val sequence: Long, val isRight: Boolean)

    fun frameInfo(frame: ByteArray): FrameInfo {
        val meta = IMAGE_BYTES
        return FrameInfo(
            timestampNs = le64(frame, meta),
            sequence = le32(frame, meta + 18),
            isRight = frame[meta + 0x3B].toInt() != 0,
        )
    }

    /**
     * Restores the 640×480 image of a raw frame. The first image chunk is recognized as the
     * one whose first 128 bytes sum lowest (as in Monado); the rest follow [CHUNK_MAP].
     */
    fun descramble(frame: ByteArray, out: ByteArray = ByteArray(IMAGE_BYTES)): ByteArray {
        var bestChunk = 0
        var bestSum = Int.MAX_VALUE
        for (c in 0 until CHUNKS) {
            var sum = 0
            val base = c * CHUNK_SIZE
            for (j in 0 until 128) sum += frame[base + j].toInt() and 0xff
            if (sum < bestSum) {
                bestSum = sum
                bestChunk = c
            }
        }
        val start = CHUNK_MAP.indexOf(bestChunk)
        for (i in 0 until CHUNKS) {
            val chunk = CHUNK_MAP[(start + i) % CHUNKS]
            System.arraycopy(frame, chunk * CHUNK_SIZE, out, i * CHUNK_SIZE, CHUNK_SIZE)
        }
        return out
    }

    /**
     * Turns a descrambled 640×480 image upright as Monado does: the left camera 90°
     * counter-clockwise, the right one 90° clockwise. The result is 480 wide, 640 high.
     */
    fun rotate(image: ByteArray, isRight: Boolean, out: ByteArray = ByteArray(IMAGE_BYTES)): ByteArray {
        val w = WIDTH
        val h = HEIGHT
        for (y in 0 until h) {
            val row = y * w
            if (isRight) {
                for (x in 0 until w) out[x * h + (h - 1 - y)] = image[row + x] // clockwise
            } else {
                for (x in 0 until w) out[(w - 1 - x) * h + y] = image[row + x] // counter-clockwise
            }
        }
        return out
    }

    // region IMU

    const val IMU_INTERFACE = 2
    const val IMU_INTERFACE_FALLBACK = 3
    const val SENSOR_REPORT_SIZE = 512

    const val MSG_GET_CAL_DATA_LENGTH = 0x14
    const val MSG_CAL_DATA_GET_NEXT_SEGMENT = 0x15
    const val MSG_START_IMU_DATA = 0x19
    const val MSG_GET_STATIC_ID = 0x1A

    /** A sensor command: 0xAA, CRC-32 of the rest, length (3 + data), message id, data. */
    fun sensorCommand(msgId: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val packetLength = 3 + data.size
        val out = ByteArray(5 + packetLength)
        out[0] = 0xAA.toByte()
        out[5] = packetLength.toByte()
        out[6] = (packetLength shr 8).toByte()
        out[7] = msgId.toByte()
        data.copyInto(out, 8)
        val crc = CRC32().apply { update(out, 5, packetLength) }.value
        for (k in 0..3) out[1 + k] = (crc ushr (8 * k)).toByte()
        return out
    }

    /** A reply to a sensor command (report starting with 0xAA). */
    class ControlReply(val msgId: Int, val data: ByteArray)

    fun parseControlReply(report: ByteArray, length: Int): ControlReply? {
        if (length < 8 || report[0] != 0xAA.toByte()) return null
        return ControlReply(report[7].toInt() and 0xff, report.copyOfRange(8, length))
    }

    /** One IMU report: raw readings, their scale factors, temperature and the glasses' timestamp. */
    data class ImuSample(
        val timestampNs: Long,
        val temperatureRaw: Int,
        val gyro: IntArray,
        val gyroMultiplier: Int,
        val gyroDivisor: Int,
        val accel: IntArray,
        val accelMultiplier: Int,
        val accelDivisor: Int,
        val mag: IntArray,
        val magMultiplier: Int,
        val magDivisor: Int,
    ) {
        /** Angular rate in rad/s and acceleration in m/s², in the sensor's own axes (no calibration). */
        fun gyroRadPerSec(i: Int) = gyro[i].toDouble() * gyroMultiplier / gyroDivisor * Math.PI / 180
        fun accelMps2(i: Int) = accel[i].toDouble() * accelMultiplier / accelDivisor * 9.80665
        fun magScaled(i: Int) = if (magDivisor != 0) mag[i].toDouble() * magMultiplier / magDivisor else 0.0

        /** ICM-42688-P: 132.48 LSB/°C around 25 °C (per Monado). */
        val temperatureC: Double get() = temperatureRaw / 132.48 + 25
    }

    /** Parses a sensor report (starts with 0x01, at least 64 bytes), or returns null. */
    fun parseImu(report: ByteArray, length: Int): ImuSample? {
        if (length < 64 || report[0].toInt() != 1) return null
        var p = 2
        fun i16(): Int = ((report[p].toInt() and 0xff) or (report[p + 1].toInt() shl 8)).also { p += 2 }
        fun i16Rev(): Int = ((report[p + 1].toInt() and 0xff) or (report[p].toInt() shl 8)).also { p += 2 }
        fun i32(): Int = le32(report, p).toInt().also { p += 4 }
        fun i32Rev(): Int = ((report[p + 3].toInt() and 0xff) or ((report[p + 2].toInt() and 0xff) shl 8) or
            ((report[p + 1].toInt() and 0xff) shl 16) or (report[p].toInt() shl 24)).also { p += 4 }
        fun i24(): Int = ((report[p].toInt() and 0xff) or ((report[p + 1].toInt() and 0xff) shl 8) or
            (report[p + 2].toInt() shl 16)).also { p += 3 } // top byte sign-extends
        fun i15(): Int = (((report[p].toInt() and 0xff) or ((report[p + 1].toInt() and 0xff) shl 8)) xor 0x8000).toShort().toInt()
            .also { p += 2 }

        val temperature = i16().toShort().toInt()
        val timestamp = le64(report, p).also { p += 8 }
        val gyroMul = i16().toShort().toInt()
        val gyroDiv = i32()
        val gyro = intArrayOf(i24(), i24(), i24())
        val accelMul = i16().toShort().toInt()
        val accelDiv = i32()
        val accel = intArrayOf(i24(), i24(), i24())
        val magMul = i16Rev().toShort().toInt()
        val magDiv = i32Rev()
        val mag = intArrayOf(i15(), i15(), i15())
        if (gyroDiv == 0 || accelDiv == 0) return null
        return ImuSample(timestamp, temperature, gyro, gyroMul, gyroDiv, accel, accelMul, accelDiv, mag, magMul, magDiv)
    }

    // endregion

    fun le32(b: ByteArray, i: Int): Long =
        (b[i].toLong() and 0xff) or ((b[i + 1].toLong() and 0xff) shl 8) or
            ((b[i + 2].toLong() and 0xff) shl 16) or ((b[i + 3].toLong() and 0xff) shl 24)

    fun le64(b: ByteArray, i: Int): Long = le32(b, i) or (le32(b, i + 4) shl 32)
}
