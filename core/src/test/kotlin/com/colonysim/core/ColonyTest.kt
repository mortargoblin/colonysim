package com.colonysim.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColonyTest {

    private fun orderBasics(c: Colony) {
        val cx = c.width / 2
        val cy = c.height / 2
        for (i in -2..2) c.applyTool(Tool.FIELD, cx + i, cy + 2)
        for (i in -2..0) c.applyTool(Tool.BED, cx + i, cy - 2)
        for (y in 0 until c.height) for (x in 0 until c.width) {
            if (c.tile(x, y).feature == Feature.TREE && (x + y) % 3 == 0) c.applyTool(Tool.HARVEST, x, y)
        }
    }

    @Test
    fun newColonyHasThreePawnsOnPassableTiles() {
        val c = MapGen.newColony(seed = 42)
        assertEquals(3, c.pawns.size)
        c.pawns.forEach { assertTrue(c.passable(it.x, it.y)) }
    }

    @Test
    fun pawnsCompleteOrdersAndSurviveTenDays() {
        val c = MapGen.newColony(seed = 7)
        orderBasics(c)
        repeat(TICKS_PER_DAY * 10) { c.update() }
        assertTrue(c.pawns.isNotEmpty(), "colony died: ${c.log.joinToString { it.text }}")
        assertTrue(c.count(Feature.BED) >= 3, "beds not built")
        assertTrue(c.count(Feature.FIELD) >= 5, "fields not planted")
        assertTrue(c.stats.foodHarvested > 0)
    }

    @Test
    fun longRunDoesNotCrash() {
        for (seed in 1L..5L) {
            val c = MapGen.newColony(seed = seed)
            orderBasics(c)
            repeat(TICKS_PER_DAY * 60) {
                c.update()
                if (it % (TICKS_PER_DAY * 10) == 0) orderBasics(c)
            }
        }
    }

    @Test
    fun saveAndLoadRoundTrips() {
        val c = MapGen.newColony(seed = 3)
        orderBasics(c)
        repeat(500) { c.update() }
        val loaded = Colony.fromJson(c.toJson())
        assertEquals(c.tick, loaded.tick)
        assertEquals(c.pawns.map { it.name }, loaded.pawns.map { it.name })
        assertEquals(c.food, loaded.food)
        repeat(100) { loaded.update() }
    }

    @Test
    fun expandGrowsMapAndKeepsTiles() {
        val c = MapGen.newColony(seed = 9)
        c.wood = 100
        c.stone = 100
        c.tile(1, 1).feature = Feature.WALL
        assertTrue(c.expand())
        assertEquals(48, c.width)
        assertEquals(48, c.height)
        assertEquals(Feature.WALL, c.tile(1, 1).feature)
        assertEquals(48 * 48, c.tiles.size)
    }
}
