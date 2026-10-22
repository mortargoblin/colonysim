package com.colonysim.core

import kotlinx.serialization.Serializable

@Serializable
enum class Terrain { GRASS, DIRT, SAND, WATER }

@Serializable
enum class Feature { NONE, TREE, ROCK, BERRY_BUSH, BED, WALL, FIELD }

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
) {
    val passable: Boolean
        get() = terrain != Terrain.WATER &&
            feature != Feature.TREE && feature != Feature.ROCK && feature != Feature.WALL

    val buildable: Boolean
        get() = terrain != Terrain.WATER && feature == Feature.NONE
}

/** Tools the player can paint onto the map. */
enum class Tool(val label: String) {
    INSPECT("Inspect"),
    HARVEST("Chop/Mine"),
    FIELD("Farm"),
    BED("Bed"),
    WALL("Wall"),
    DEMOLISH("Demolish"),
    CANCEL("Cancel"),
}

enum class Season(val label: String, val growthFactor: Float) {
    SPRING("Spring", 1.0f),
    SUMMER("Summer", 1.3f),
    FALL("Fall", 0.8f),
    WINTER("Winter", 0f),
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
