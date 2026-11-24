package com.xreal.stereoview.record

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EurocTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun meanRateIsNeverInfiniteOrNaN() {
        assertEquals(100.0, Euroc.meanRateHz(101, 0, 1_000_000_000), 1e-9)
        assertEquals(0.0, Euroc.meanRateHz(5, 7, 7), 0.0) // all at one instant
        assertEquals(0.0, Euroc.meanRateHz(5, 9, 7), 0.0) // span unknown
        assertEquals(0.0, Euroc.meanRateHz(1, 0, 0), 0.0)
    }

    @Test
    fun mergesGyroWithInterpolatedAccelerometer() {
        val gyro = tmp.newFile("gyroscope.csv").apply {
            writeText("timestamp_ns,arrival_ns,wx,wy,wz,accuracy\n5,0,0.1,0.2,0.3,3\n15,0,1,2,3,3\n25,0,4,5,6,3\n40,0,7,8,9,3\n")
        }
        val accel = tmp.newFile("accelerometer.csv").apply {
            writeText("timestamp_ns,arrival_ns,ax,ay,az,accuracy\n10,0,0,0,10,3\n20,0,2,4,10,3\n30,0,4,8,10,3\n")
        }
        val out = tmp.newFile("data.csv")
        val (rows, _) = Euroc.mergeImu(gyro, accel, out)
        // t=5 and t=40 lie outside the accelerometer's span.
        assertEquals(2L, rows)
        val lines = out.readLines()
        assertEquals(Euroc.IMU_HEADER, lines[0])
        assertEquals("15,1,2,3,1.0000000000,2.0000000000,10.0000000000", lines[1])
        assertEquals("25,4,5,6,3.0000000000,6.0000000000,10.0000000000", lines[2])
    }

    @Test
    fun sortsRowsByTimestamp() {
        val f = tmp.newFile("data.csv").apply { writeText("#timestamp [ns],filename\n30,30.png\n10,10.png\n20,20.png\n") }
        Euroc.sortByTimestamp(f)
        assertEquals(listOf("#timestamp [ns],filename", "10,10.png", "20,20.png", "30,30.png"), f.readLines())
    }
}
