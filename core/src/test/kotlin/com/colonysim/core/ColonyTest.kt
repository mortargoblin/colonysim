package com.colonysim.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColonyTest {

    private fun orderBasics(c: Colony) {
        val cx = c.width / 2
        val cy = c.height / 2
        for (i in -2..2) c.applyTool(Tool.Plant(Crop.POTATO), cx + i, cy + 2)
        for (i in -2..0) c.applyTool(Tool.Build(Building.BED), cx + i, cy - 2)
        for (y in 0 until c.height) for (x in 0 until c.width) {
            if (c.tile(x, y).feature == Feature.TREE && (x + y) % 3 == 0) c.applyTool(Tool.Harvest, x, y)
        }
    }

    /** A flat, empty test map so construction tests don't depend on terrain generation. */
    private fun flatColony(): Colony {
        val c = MapGen.newColony(seed = 1)
        for (t in c.tiles) {
            t.terrain = Terrain.GRASS
            t.feature = Feature.NONE
            t.designation = null
        }
        c.markRoomsDirty()
        c.nextEventTick = Long.MAX_VALUE
        Resource.entries.forEach { c.stock[it] = 200 }
        return c
    }

    private fun runUntil(c: Colony, maxTicks: Int, done: () -> Boolean) {
        repeat(maxTicks) {
            if (done()) return
            c.update()
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
    fun demolishRemovesStructuresAndRefundsHalf() {
        val c = flatColony()
        val cx = c.width / 2
        val cy = c.height / 2 - 4
        for (i in 0..2) c.applyTool(Tool.Build(Building.STONE_WALL), cx + i, cy)
        c.applyTool(Tool.Build(Building.BED), cx, cy + 2)
        c.applyTool(Tool.Plant(Crop.CORN), cx + 2, cy + 2)
        runUntil(c, 2000) { c.count(Feature.WALL) == 3 && c.count(Feature.BED) == 1 && c.count(Feature.FIELD) == 1 }
        assertEquals(3, c.count(Feature.WALL))

        // Ripe fields used to get harvested instead of demolished.
        c.tile(cx + 2, cy + 2).growth = 100f
        val stoneBefore = c[Resource.STONE]
        for (i in 0..2) assertTrue(c.applyTool(Tool.Demolish, cx + i, cy))
        assertTrue(c.applyTool(Tool.Demolish, cx, cy + 2))
        assertTrue(c.applyTool(Tool.Demolish, cx + 2, cy + 2))
        runUntil(c, 2000) { c.count(Feature.WALL) == 0 && c.count(Feature.BED) == 0 && c.count(Feature.FIELD) == 0 }
        assertEquals(0, c.count(Feature.WALL))
        assertEquals(0, c.count(Feature.BED))
        assertEquals(0, c.count(Feature.FIELD))
        assertEquals(stoneBefore + 3 * 2, c[Resource.STONE])
        assertTrue(c.tiles.none { it.designation == JobType.DEMOLISH })
    }

    @Test
    fun demolishOnBlueprintCancelsItImmediately() {
        val c = flatColony()
        c.applyTool(Tool.Build(Building.TABLE), 5, 5)
        assertEquals(JobType.BUILD, c.tile(5, 5).designation)
        assertTrue(c.applyTool(Tool.Demolish, 5, 5))
        assertNull(c.tile(5, 5).designation)
        assertNull(c.tile(5, 5).blueprint)
    }

    @Test
    fun doorsArePassableAndEncloseRooms() {
        val c = flatColony()
        // A 3x3 room with walls around it and a door on the south side.
        for (x in 9..13) for (y in 9..13) {
            val edge = x == 9 || x == 13 || y == 9 || y == 13
            if (!edge) continue
            c.tile(x, y).feature = if (x == 11 && y == 13) Feature.DOOR else Feature.WOOD_WALL
        }
        c.markRoomsDirty()
        assertTrue(c.isIndoors(11, 11))
        assertEquals(9, c.roomAt(11, 11)!!.size)
        assertFalse(c.isIndoors(11, 15))
        assertTrue(c.passable(11, 13))
        assertFalse(c.passable(9, 11))
        // A pawn outside can walk in through the door.
        val path = Pathfinder.find(c, 11, 16) { x, y -> x == 11 && y == 11 }
        assertTrue(path != null && Pos(11, 13) in path)

        // Without the door the room is sealed.
        c.tile(11, 13).feature = Feature.WOOD_WALL
        assertNull(Pathfinder.find(c, 11, 16) { x, y -> x == 11 && y == 11 })
    }

    @Test
    fun builtDoorsAndWallsMakeABedroom() {
        val c = flatColony()
        val ox = 4
        val oy = 4
        for (x in ox..ox + 4) for (y in oy..oy + 4) {
            val edge = x == ox || x == ox + 4 || y == oy || y == oy + 4
            if (!edge) continue
            val b = if (x == ox + 2 && y == oy + 4) Building.DOOR else Building.WOOD_WALL
            c.applyTool(Tool.Build(b), x, y)
        }
        c.applyTool(Tool.Build(Building.BED), ox + 2, oy + 2)
        runUntil(c, 4000) { c.tiles.none { it.designation == JobType.BUILD } }
        assertEquals(1, c.count(Feature.DOOR))
        assertTrue(c.isIndoors(ox + 2, oy + 2))
        assertEquals(1, c.roomAt(ox + 2, oy + 2)!!.beds)
        // Nobody got walled in.
        c.pawns.forEach { assertTrue(c.passable(it.x, it.y) || c.tile(it.x, it.y).feature == Feature.DOOR) }
    }

    @Test
    fun campfireCooksMeals() {
        val c = flatColony()
        c.stock[Resource.MEAL] = 0
        c.stock[Resource.FOOD] = 30
        c.applyTool(Tool.Build(Building.CAMPFIRE), c.width / 2, c.height / 2 - 3)
        runUntil(c, 1500) { c[Resource.MEAL] >= 4 }
        assertTrue(c[Resource.MEAL] >= 4, "meals=${c[Resource.MEAL]}")
    }

    @Test
    fun herbsTreatSickColonists() {
        val c = flatColony()
        val p = c.pawns.first()
        p.sickTicks = 1000
        val herbs = c[Resource.HERBS]
        runUntil(c, 50) { c[Resource.HERBS] < herbs }
        assertEquals(herbs - 1, c[Resource.HERBS])
        assertTrue(p.sickTicks < 300)
    }

    @Test
    fun saveAndLoadRoundTrips() {
        val c = MapGen.newColony(seed = 3)
        orderBasics(c)
        c.tile(2, 2).apply { terrain = Terrain.GRASS; feature = Feature.NONE }
        assertTrue(c.applyTool(Tool.Build(Building.DOOR), 2, 2))
        repeat(500) { c.update() }
        val loaded = Colony.fromJson(c.toJson())
        assertEquals(c.tick, loaded.tick)
        assertEquals(c.pawns.map { it.name }, loaded.pawns.map { it.name })
        assertEquals(c.stock, loaded.stock)
        assertEquals(c.tile(2, 2).blueprint, loaded.tile(2, 2).blueprint)
        assertEquals(c.tile(2, 2).feature, loaded.tile(2, 2).feature)
        repeat(100) { loaded.update() }
    }

    @Test
    fun oldSavesAreMigrated() {
        val c = MapGen.newColony(seed = 5)
        c.tile(3, 3).feature = Feature.NONE
        c.tile(3, 3).designation = JobType.BUILD_WALL
        // Rewrite the save into the old format: flat food/wood/stone counts, no stock.
        val obj = Json.parseToJsonElement(c.toJson()).jsonObject
        val old = JsonObject(
            obj - "stock" + mapOf("food" to JsonPrimitive(42), "wood" to JsonPrimitive(7), "stone" to JsonPrimitive(3))
        )
        val loaded = Colony.fromJson(old.toString())
        assertEquals(42, loaded[Resource.FOOD])
        assertEquals(7, loaded[Resource.WOOD])
        assertEquals(3, loaded[Resource.STONE])
        assertEquals(0, loaded[Resource.METAL])
        assertEquals(JobType.BUILD, loaded.tile(3, 3).designation)
        assertEquals(Building.STONE_WALL, loaded.tile(3, 3).blueprint)
        repeat(50) { loaded.update() }
    }

    @Test
    fun expandGrowsMapAndKeepsTiles() {
        val c = MapGen.newColony(seed = 9)
        c.stock[Resource.WOOD] = 100
        c.stock[Resource.STONE] = 100
        c.tile(1, 1).feature = Feature.WALL
        assertTrue(c.expand())
        assertEquals(48, c.width)
        assertEquals(48, c.height)
        assertEquals(Feature.WALL, c.tile(1, 1).feature)
        assertEquals(48 * 48, c.tiles.size)
        c.roomAt(47, 47)
    }
}
