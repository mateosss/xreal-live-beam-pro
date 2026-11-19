package com.xreal.stereoview.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ZoomGroupTest {
    @Test
    fun unlinkingKeepsTheZoomInEveryTile() {
        val g = ZoomGroup(linked = true)
        g.shared.set(3f, 0.3f, 0.6f)
        g.setLinked(false, 3)
        assertNotSame(g.stateFor(0), g.stateFor(1))
        for (i in 0 until 3) {
            assertEquals(3f, g.stateFor(i).scale)
            assertEquals(0.3f, g.stateFor(i).u)
            assertEquals(0.6f, g.stateFor(i).v)
        }
    }

    @Test
    fun linkingMovesEveryTileToCam0() {
        val g = ZoomGroup(linked = false)
        g.stateFor(0).set(2f, 0.4f, 0.4f)
        g.stateFor(1).set(8f, 0.9f, 0.1f)
        g.setLinked(true, 2)
        assertSame(g.stateFor(0), g.stateFor(1))
        assertEquals(2f, g.stateFor(1).scale)
        assertEquals(0.4f, g.stateFor(1).u)
        assertEquals(true, g.anyZoomed(2))
    }

    @Test
    fun listenersFollowTilesCreatedLater() {
        val g = ZoomGroup(linked = false)
        var calls = 0
        g.addListener { calls++ }
        g.stateFor(4).set(2f, 0.5f, 0.5f)
        assertEquals(1, calls)
    }
}
