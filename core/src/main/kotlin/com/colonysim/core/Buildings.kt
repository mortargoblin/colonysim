package com.colonysim.core

import kotlinx.serialization.Serializable

@Serializable
enum class Resource(val label: String, val icon: String) {
    FOOD("Raw food", "🍖"),
    MEAL("Meals", "🍲"),
    WOOD("Wood", "🪵"),
    STONE("Stone", "🪨"),
    METAL("Metal", "🔩"),
    HERBS("Herbs", "🌿"),
}

typealias Cost = Map<Resource, Int>

fun Cost.describe(): String =
    if (isEmpty()) "free" else entries.joinToString(" ") { (r, n) -> "$n ${r.label.lowercase()}" }

enum class BuildCategory(val label: String) {
    STRUCTURE("Build"),
    FURNITURE("Furniture"),
    PRODUCTION("Kitchen"),
}

@Serializable
enum class Building(
    val label: String,
    val description: String,
    val category: BuildCategory,
    val cost: Cost,
    val work: Float,
    /** The feature placed on the tile, or null for floors. */
    val feature: Feature? = null,
    /** The terrain laid down, for floors. */
    val floor: Terrain? = null,
) {
    WOOD_WALL("Wood wall", "Cheap wall. Walls and doors enclose rooms.", BuildCategory.STRUCTURE,
        mapOf(Resource.WOOD to 2), 15f, feature = Feature.WOOD_WALL),
    STONE_WALL("Stone wall", "Sturdy wall.", BuildCategory.STRUCTURE,
        mapOf(Resource.STONE to 3), 25f, feature = Feature.WALL),
    DOOR("Door", "Colonists walk through; keeps the room enclosed.", BuildCategory.STRUCTURE,
        mapOf(Resource.WOOD to 4), 20f, feature = Feature.DOOR),
    WOOD_FLOOR("Wood floor", "Makes rooms more impressive.", BuildCategory.STRUCTURE,
        mapOf(Resource.WOOD to 1), 6f, floor = Terrain.WOOD_FLOOR),
    STONE_FLOOR("Stone floor", "Makes rooms more impressive.", BuildCategory.STRUCTURE,
        mapOf(Resource.STONE to 1), 8f, floor = Terrain.STONE_FLOOR),
    BED("Bed", "Better sleep, best in an enclosed bedroom.", BuildCategory.FURNITURE,
        mapOf(Resource.WOOD to 6), 40f, feature = Feature.BED),
    TABLE("Table", "Colonists like eating at a table.", BuildCategory.FURNITURE,
        mapOf(Resource.WOOD to 5), 25f, feature = Feature.TABLE),
    STATUE("Statue", "Beautiful. Lifts the mood of anyone nearby.", BuildCategory.FURNITURE,
        mapOf(Resource.STONE to 15, Resource.METAL to 2), 80f, feature = Feature.STATUE),
    CAMPFIRE("Campfire", "Cooks raw food into meals (3 raw → 2 meals).", BuildCategory.PRODUCTION,
        mapOf(Resource.WOOD to 4), 15f, feature = Feature.CAMPFIRE),
    STOVE("Stove", "Cooks twice as fast as a campfire.", BuildCategory.PRODUCTION,
        mapOf(Resource.STONE to 6, Resource.METAL to 3), 40f, feature = Feature.STOVE);

    val blocksMovement: Boolean get() = feature?.blocksMovement == true

    companion object {
        fun forFeature(f: Feature): Building? = entries.firstOrNull { it.feature == f }
        fun forFloor(t: Terrain): Building? = entries.firstOrNull { it.floor == t }
    }
}

@Serializable
enum class Crop(
    val label: String,
    val description: String,
    /** Growth per tick at a season factor of 1 (100 = ripe). */
    val growRate: Float,
    val yields: Resource,
    val amount: Int,
) {
    POTATO("Potatoes", "Fast-growing food", 0.25f, Resource.FOOD, 4),
    CORN("Corn", "Slow, big harvests", 0.11f, Resource.FOOD, 9),
    HEALROOT("Healroot", "Herbal medicine", 0.12f, Resource.HERBS, 2),
}

/** What the player is painting onto the map. */
sealed class Tool(val label: String) {
    data object Inspect : Tool("Inspect")
    data object Harvest : Tool("Chop / Mine")
    data object Demolish : Tool("Demolish")
    data object Cancel : Tool("Cancel")
    data class Plant(val crop: Crop) : Tool(crop.label)
    data class Build(val building: Building) : Tool(building.label)
}
