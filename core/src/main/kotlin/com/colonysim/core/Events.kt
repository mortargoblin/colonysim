package com.colonysim.core

/** Random storyteller events. One fires every half day to two days. */
object Events {

    private class Event(val weight: (Colony) -> Int, val run: (Colony) -> Unit)

    private val events = listOf(
        // Wanderers are the main way the colony grows, so they get a big weight,
        // more so when there are spare beds and the colony is happy.
        Event({ c ->
            val spareBeds = c.count(Feature.BED) - c.pawns.size
            val avgMood = c.pawns.map { it.mood }.average()
            if (spareBeds <= 0) 1 else 3 + spareBeds.coerceAtMost(4) * 3 + if (avgMood > 55) 3 else 0
        }) { c ->
            val spot = c.randomEdgeSpot() ?: return@Event
            val p = c.randomPawn(c.rng, spot.x, spot.y)
            c.addPawn(p)
            c.log("${p.name} (${p.age}, ${p.trait.label.lowercase()}) wandered in and joined the colony.", LogKind.GOOD)
            c.pawns.forEach { if (it !== p) it.addThought("New colonist", 5, TICKS_PER_DAY) }
        },
        Event({ 2 }) { c ->
            val spot = c.randomEdgeSpot() ?: return@Event
            val p = c.randomPawn(c.rng, spot.x, spot.y)
            p.health = 45f
            p.hunger = 20f
            c.addPawn(p)
            c.log("A wounded refugee, ${p.name}, stumbled into the colony.", LogKind.GOOD)
        },
        Event({ 4 }) { c ->
            val f = c.rng.nextInt(5, 16)
            val w = c.rng.nextInt(0, 12)
            val s = c.rng.nextInt(0, 10)
            c.food += f; c.wood += w; c.stone += s
            c.log("A cargo pod crashed nearby: +$f food, +$w wood, +$s stone.", LogKind.GOOD)
        },
        Event({ c -> if (c.pawns.isNotEmpty()) 3 else 0 }) { c ->
            val p = c.pawns.random(c.rng)
            var dmg = c.rng.nextInt(15, 40).toFloat()
            if (p.trait == Trait.TOUGH) dmg /= 2
            p.health -= dmg
            p.addThought("Attacked by an animal", -10, TICKS_PER_DAY * 2)
            c.log("A wild boar attacked ${p.name}!", LogKind.BAD)
        },
        Event({ c -> if (c.pawns.isNotEmpty()) 2 else 0 }) { c ->
            val p = c.pawns.random(c.rng)
            p.sickTicks = c.rng.nextInt(TICKS_PER_DAY, TICKS_PER_DAY * 3)
            c.log("${p.name} has come down with the flu.", LogKind.BAD)
        },
        Event({ c -> if (c.count(Feature.FIELD) > 0) 2 else 0 }) { c ->
            var lost = 0
            for (t in c.tiles) if (t.feature == Feature.FIELD && t.growth > 0f) { t.growth = 0f; lost++ }
            c.log("Blight! $lost fields withered.", LogKind.BAD)
        },
        Event({ 3 }) { c ->
            for (t in c.tiles) {
                if (t.feature == Feature.BERRY_BUSH) t.growth = 100f
                if (t.feature == Feature.FIELD) t.growth = (t.growth + 40f).coerceAtMost(100f)
            }
            c.log("A warm rain fell. Crops and berries flourish.", LogKind.GOOD)
        },
        Event({ c -> if (c.season == Season.WINTER) 1 else 3 }) { c ->
            c.coldSnapTicks = TICKS_PER_DAY * 2
            c.pawns.forEach { it.addThought("Cold snap", -6, TICKS_PER_DAY * 2) }
            c.log("A cold snap! Nothing will grow for two days.", LogKind.BAD)
        },
        Event({ c -> if (c.wood >= 15 || c.stone >= 15) 3 else 0 }) { c ->
            if (c.wood >= c.stone) {
                c.wood -= 10; c.food += 10
                c.log("A trader caravan swapped 10 wood for 10 food.", LogKind.INFO)
            } else {
                c.stone -= 10; c.food += 12
                c.log("A trader caravan swapped 10 stone for 12 food.", LogKind.INFO)
            }
        },
        Event({ 2 }) { c ->
            val cx = c.rng.nextInt(c.width)
            val cy = c.rng.nextInt(c.height)
            var made = 0
            for (y in cy - 2..cy + 2) for (x in cx - 2..cx + 2) {
                if (!c.inBounds(x, y) || c.pawnAt(x, y) != null) continue
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) > 5) continue
                val t = c.tile(x, y)
                if (t.terrain == Terrain.WATER || t.feature == Feature.BED || t.feature == Feature.WALL) continue
                t.feature = Feature.ROCK
                t.terrain = Terrain.DIRT
                t.designation = null
                made++
            }
            if (made > 0) c.log("A meteorite slammed down, leaving a pile of rock.", LogKind.INFO)
        },
        Event({ c -> if (c.pawns.size >= 2) 3 else 0 }) { c ->
            c.pawns.forEach { it.addThought("Saw the aurora", 10, TICKS_PER_DAY) }
            c.log("An aurora lit up the sky. Everyone feels inspired.", LogKind.GOOD)
        },
        Event({ c -> if (c.pawns.isNotEmpty()) 2 else 0 }) { c ->
            val p = c.pawns.random(c.rng)
            p.addThought("Birthday", 12, TICKS_PER_DAY)
            c.pawns.forEach { if (it !== p) it.addThought("Birthday party", 4, TICKS_PER_DAY) }
            c.log("It's ${p.name}'s birthday! The colony celebrates.", LogKind.GOOD)
        },
        Event({ c -> if (c.food >= 10) 2 else 0 }) { c ->
            val lost = (c.food * c.rng.nextInt(15, 35)) / 100
            c.food -= lost
            c.log("Rats got into the stores and ate $lost food.", LogKind.BAD)
        },
    )

    fun fire(c: Colony) {
        if (c.pawns.isEmpty()) return
        val weights = events.map { it.weight(c).coerceAtLeast(0) }
        val total = weights.sum()
        if (total == 0) return
        var roll = c.rng.nextInt(total)
        for (i in events.indices) {
            roll -= weights[i]
            if (roll < 0) {
                events[i].run(c)
                return
            }
        }
    }
}
