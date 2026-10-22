package com.colonysim.core

import kotlin.math.floor
import kotlin.random.Random

/**
 * Seeded value-noise terrain. Noise is a pure function of (seed, x, y), so tiles
 * generated later by expanding the map line up with the existing terrain.
 */
object MapGen {

    fun generateTile(seed: Long, x: Int, y: Int): Tile {
        val water = noise(seed, x, y, 9.0)
        val rock = noise(seed + 101, x, y, 7.0)
        val forest = noise(seed + 202, x, y, 6.0)
        val detail = hash(seed + 303, x, y)

        val tile = Tile()
        when {
            water < 0.25 -> tile.terrain = Terrain.WATER
            water < 0.30 -> tile.terrain = Terrain.SAND
            rock > 0.68 -> {
                tile.terrain = Terrain.DIRT
                // Metal ore veins sit deep inside rock formations.
                tile.feature = if (rock > 0.74 && detail < 0.25) Feature.ORE else Feature.ROCK
            }
            rock > 0.62 -> tile.terrain = Terrain.DIRT
        }
        if (tile.terrain == Terrain.GRASS) {
            when {
                forest > 0.6 && detail < 0.55 -> tile.feature = Feature.TREE
                detail < 0.04 -> tile.feature = Feature.TREE
                detail > 0.975 -> {
                    tile.feature = Feature.BERRY_BUSH
                    tile.growth = ((detail - 0.975) * 4000).toFloat().coerceAtMost(100f)
                }
            }
        }
        return tile
    }

    fun newColony(seed: Long = Random.nextLong(), width: Int = 40, height: Int = 40): Colony {
        val tiles = MutableList(width * height) { i -> generateTile(seed, i % width, i / width) }
        val colony = Colony(width = width, height = height, tiles = tiles, seed = seed)

        // Clear a small landing zone in the middle and guarantee some food nearby.
        val cx = width / 2
        val cy = height / 2
        for (y in cy - 3..cy + 3) for (x in cx - 3..cx + 3) {
            val t = colony.tile(x, y)
            t.terrain = Terrain.GRASS
            t.feature = Feature.NONE
        }
        colony.tile(cx - 3, cy - 3).apply { feature = Feature.BERRY_BUSH; growth = 100f }
        colony.tile(cx + 3, cy + 3).apply { feature = Feature.BERRY_BUSH; growth = 60f }
        colony.tile(cx + 3, cy - 3).apply { feature = Feature.TREE }

        val rng = Random(seed)
        repeat(3) { i ->
            colony.addPawn(colony.randomPawn(rng, cx - 1 + i, cy))
        }
        colony.log("Three colonists crash-landed. Build beds, plant fields, survive.", LogKind.INFO)
        return colony
    }

    private fun noise(seed: Long, x: Int, y: Int, scale: Double): Double {
        val fx = x / scale
        val fy = y / scale
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = smooth(fx - x0)
        val ty = smooth(fy - y0)
        val a = hash(seed, x0, y0)
        val b = hash(seed, x0 + 1, y0)
        val c = hash(seed, x0, y0 + 1)
        val d = hash(seed, x0 + 1, y0 + 1)
        val top = a + (b - a) * tx
        val bottom = c + (d - c) * tx
        return top + (bottom - top) * ty
    }

    private fun smooth(t: Double) = t * t * (3 - 2 * t)

    /** Deterministic hash of a lattice point to [0, 1). */
    private fun hash(seed: Long, x: Int, y: Int): Double {
        var h = seed xor (x.toLong() * 0x9E3779B97F4A7C15uL.toLong()) xor (y.toLong() * 0xC2B2AE3D27D4EB4FuL.toLong())
        h = (h xor (h ushr 33)) * 0xFF51AFD7ED558CCDuL.toLong()
        h = (h xor (h ushr 33)) * 0xC4CEB9FE1A85EC53uL.toLong()
        h = h xor (h ushr 33)
        return (h ushr 11).toDouble() / (1L shl 53).toDouble()
    }
}

object Names {
    private val first = listOf(
        "Ada", "Bram", "Cleo", "Dax", "Edda", "Finn", "Gus", "Hana", "Ivo", "Juno",
        "Kai", "Lena", "Milo", "Nell", "Otto", "Pia", "Quin", "Rosa", "Sven", "Tess",
        "Ugo", "Vera", "Wren", "Xia", "Yuri", "Zoe", "Abe", "Bea", "Cyrus", "Dot",
        "Elio", "Faye", "Gil", "Hux", "Isla", "Jem", "Kit", "Lux", "Moss", "Nia",
    )

    fun random(rng: Random, taken: Collection<String>): String {
        val free = first.filter { it !in taken }
        if (free.isNotEmpty()) return free.random(rng)
        return "${first.random(rng)} ${('A'..'Z').random(rng)}."
    }
}
