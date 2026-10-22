package com.colonysim.core

/** Random storyteller events. One fires every half day to two days. */
object Events {

    private class Event(val weight: (Colony) -> Int, val run: (Colony) -> Unit)

    private fun outdoorPawns(c: Colony) = c.pawns.filter { !c.isIndoors(it.x, it.y) }

    private fun injure(c: Colony, p: Pawn, min: Int, max: Int) {
        var dmg = c.rng.nextInt(min, max).toFloat()
        if (p.trait == Trait.TOUGH) dmg /= 2
        p.health -= dmg
        p.addThought("Attacked by an animal", -10, TICKS_PER_DAY * 2)
    }

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
            val loot = listOf(
                Resource.FOOD to c.rng.nextInt(4, 14),
                Resource.MEAL to c.rng.nextInt(0, 5),
                Resource.WOOD to c.rng.nextInt(0, 12),
                Resource.STONE to c.rng.nextInt(0, 10),
                Resource.METAL to c.rng.nextInt(0, 6),
                Resource.HERBS to c.rng.nextInt(0, 3),
            ).filter { it.second > 0 }
            loot.forEach { (r, n) -> c.add(r, n) }
            c.log("A cargo pod crashed nearby: " + loot.joinToString { (r, n) -> "+$n ${r.label.lowercase()}" } + ".", LogKind.GOOD)
        },
        Event({ c -> if (c.pawns.isNotEmpty()) 3 else 0 }) { c ->
            val targets = outdoorPawns(c)
            if (targets.isEmpty()) {
                c.log("A wild boar sniffed around, but everyone was safe indoors.", LogKind.INFO)
                return@Event
            }
            val p = targets.random(c.rng)
            injure(c, p, 15, 40)
            c.log("A wild boar attacked ${p.name}!", LogKind.BAD)
        },
        // Wolves hunt colonists who are outside at night. Walls and doors keep them out.
        Event({ c -> if (c.pawns.size >= 3 && c.isNight) 4 else 0 }) { c ->
            val targets = outdoorPawns(c).shuffled(c.rng).take(2)
            if (targets.isEmpty()) {
                c.log("Wolves howled outside, but the walls kept everyone safe.", LogKind.GOOD)
                return@Event
            }
            targets.forEach { injure(c, it, 10, 30) }
            c.log("A wolf pack mauled ${targets.joinToString(" and ") { it.name }} in the dark!", LogKind.BAD)
        },
        Event({ c -> if (c.pawns.isNotEmpty()) 2 else 0 }) { c ->
            val p = c.pawns.random(c.rng)
            p.sickTicks = c.rng.nextInt(TICKS_PER_DAY, TICKS_PER_DAY * 3)
            c.log("${p.name} has come down with the flu. Herbs will help.", LogKind.BAD)
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
            c.pawns.forEach { if (!c.isIndoors(it.x, it.y)) it.addThought("Cold snap", -6, TICKS_PER_DAY * 2) }
            c.log("A cold snap! Nothing will grow for two days. Sleep indoors to stay warm.", LogKind.BAD)
        },
        Event({ c -> if (c[Resource.WOOD] >= 15 || c[Resource.STONE] >= 15) 3 else 0 }) { c ->
            val give = if (c[Resource.WOOD] >= c[Resource.STONE]) Resource.WOOD else Resource.STONE
            val (get, n) = when (c.rng.nextInt(3)) {
                0 -> Resource.FOOD to 12
                1 -> Resource.METAL to 5
                else -> Resource.HERBS to 4
            }
            c.add(give, -10)
            c.add(get, n)
            c.log("A trader caravan swapped 10 ${give.label.lowercase()} for $n ${get.label.lowercase()}.", LogKind.INFO)
        },
        Event({ 2 }) { c ->
            val cx = c.rng.nextInt(c.width)
            val cy = c.rng.nextInt(c.height)
            var made = 0
            for (y in cy - 2..cy + 2) for (x in cx - 2..cx + 2) {
                if (!c.inBounds(x, y) || c.pawnAt(x, y) != null) continue
                val d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                if (d2 > 5) continue
                val t = c.tile(x, y)
                if (t.terrain == Terrain.WATER || t.hasStructure) continue
                t.feature = if (d2 <= 1) Feature.ORE else Feature.ROCK
                t.terrain = Terrain.DIRT
                t.designation = null
                t.blueprint = null
                made++
            }
            if (made > 0) {
                c.markRoomsDirty()
                c.log("A meteorite slammed down, leaving rock and metal ore.", LogKind.INFO)
            }
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
        Event({ c -> if (c[Resource.FOOD] >= 10) 2 else 0 }) { c ->
            val lost = (c[Resource.FOOD] * c.rng.nextInt(15, 35)) / 100
            c.add(Resource.FOOD, -lost)
            c.log("Rats got into the stores and ate $lost raw food.", LogKind.BAD)
        },
        Event({ c -> if (c.count(Feature.FIELD) > 0) 1 else 0 }) { c ->
            c.add(Resource.HERBS, 2)
            c.log("Colonists found wild healroot growing at the edge of the fields: +2 herbs.", LogKind.GOOD)
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
