package com.colonysim.core

import kotlinx.serialization.Serializable

@Serializable
enum class Terrain(val isFloor: Boolean = false) {
    GRASS, DIRT, SAND, WATER,
    WOOD_FLOOR(isFloor = true),
    STONE_FLOOR(isFloor = true),
}

@Serializable
enum class Feature(
    val label: String,
    /** Pawns cannot walk through it. */
    val blocksMovement: Boolean = false,
    /** Encloses rooms: walls, doors and natural rock. */
    val roomBoundary: Boolean = false,
) {
    NONE("Nothing"),
    TREE("Tree", blocksMovement = true),
    ROCK("Rock", blocksMovement = true, roomBoundary = true),
    ORE("Metal ore", blocksMovement = true, roomBoundary = true),
    BERRY_BUSH("Berry bush"),
    FIELD("Field"),
    WALL("Stone wall", blocksMovement = true, roomBoundary = true),
    WOOD_WALL("Wood wall", blocksMovement = true, roomBoundary = true),
    DOOR("Door", roomBoundary = true),
    BED("Bed"),
    TABLE("Table", blocksMovement = true),
    STATUE("Statue", blocksMovement = true),
    CAMPFIRE("Campfire", blocksMovement = true),
    STOVE("Stove", blocksMovement = true),
}

@Serializable
data class Pos(val x: Int, val y: Int)

@Serializable
class Tile(
    var terrain: Terrain = Terrain.GRASS,
    var feature: Feature = Feature.NONE,
    /** Ripeness for fields and berry bushes, 0..100. */
    var growth: Float = 0f,
    /** Work the player has ordered on this tile. */
    var designation: JobType? = null,
    /** What to build here when [designation] is BUILD. */
    var blueprint: Building? = null,
    /** What grows (or will be planted) here. Null on an old-save field means potatoes. */
    var crop: Crop? = null,
) {
    val passable: Boolean
        get() = terrain != Terrain.WATER && !feature.blocksMovement

    fun canBuild(b: Building): Boolean {
        if (terrain == Terrain.WATER || feature != Feature.NONE) return false
        return b.floor == null || terrain != b.floor
    }

    /** Whether the player built what's on this tile (so it can be demolished). */
    val hasStructure: Boolean
        get() = Building.forFeature(feature) != null || feature == Feature.FIELD || terrain.isFloor
}

@Serializable
enum class LogKind { INFO, GOOD, BAD }

@Serializable
data class LogEntry(val tick: Long, val text: String, val kind: LogKind)

@Serializable
class Stats(
    var colonistsJoined: Int = 0,
    var colonistsLost: Int = 0,
    var peakPopulation: Int = 0,
    var foodHarvested: Int = 0,
    var expansions: Int = 0,
)

enum class Season(val label: String, val growthFactor: Float) {
    SPRING("Spring", 1.0f),
    SUMMER("Summer", 1.3f),
    FALL("Fall", 0.8f),
    WINTER("Winter", 0f),
}
