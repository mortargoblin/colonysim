package com.colonysim.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.random.Random

const val TICKS_PER_DAY = 240
const val DAYS_PER_SEASON = 8
private const val MAX_LOG = 60

@Serializable
class Colony(
    var width: Int,
    var height: Int,
    var tiles: MutableList<Tile>,
    val seed: Long,
    val pawns: MutableList<Pawn> = mutableListOf(),
    var food: Int = 24,
    var wood: Int = 12,
    var stone: Int = 0,
    var tick: Long = 0,
    var nextPawnId: Int = 1,
    var nextEventTick: Long = TICKS_PER_DAY.toLong(),
    var coldSnapTicks: Int = 0,
    val log: MutableList<LogEntry> = mutableListOf(),
    val stats: Stats = Stats(),
) {
    @Transient
    var rng: Random = Random(seed)

    // ---------------------------------------------------------------- time

    val day: Int get() = (tick / TICKS_PER_DAY).toInt() + 1
    val hour: Int get() = ((tick % TICKS_PER_DAY) * 24 / TICKS_PER_DAY).toInt()
    val isNight: Boolean get() = hour < 6 || hour >= 21
    val season: Season get() = Season.entries[((day - 1) / DAYS_PER_SEASON) % 4]
    val year: Int get() = (day - 1) / (DAYS_PER_SEASON * 4) + 1
    val isGameOver: Boolean get() = pawns.isEmpty()

    /** 0 = full daylight, 1 = darkest night. */
    val darkness: Float
        get() {
            val h = (tick % TICKS_PER_DAY) * 24f / TICKS_PER_DAY
            return when {
                h < 5f -> 1f
                h < 7f -> 1f - (h - 5f) / 2f
                h < 19f -> 0f
                h < 21f -> (h - 19f) / 2f
                else -> 1f
            }
        }

    // ---------------------------------------------------------------- grid

    fun inBounds(x: Int, y: Int) = x in 0 until width && y in 0 until height
    fun tile(x: Int, y: Int): Tile = tiles[y * width + x]
    fun passable(x: Int, y: Int) = inBounds(x, y) && tile(x, y).passable

    fun count(feature: Feature) = tiles.count { it.feature == feature }

    fun pawnAt(x: Int, y: Int): Pawn? = pawns.firstOrNull { it.x == x && it.y == y }

    // ---------------------------------------------------------------- log

    fun log(text: String, kind: LogKind = LogKind.INFO) {
        log.add(LogEntry(tick, text, kind))
        while (log.size > MAX_LOG) log.removeAt(0)
    }

    // ---------------------------------------------------------------- pawns

    fun randomPawn(rng: Random, x: Int, y: Int): Pawn {
        val palette = listOf(
            0xFFE57373, 0xFF64B5F6, 0xFFFFD54F, 0xFFBA68C8, 0xFF4DB6AC,
            0xFFFF8A65, 0xFFA1887F, 0xFF90A4AE, 0xFFF06292, 0xFFAED581,
        )
        return Pawn(
            id = nextPawnId++,
            name = Names.random(rng, pawns.map { it.name }),
            x = x,
            y = y,
            age = rng.nextInt(18, 66),
            trait = Trait.entries.random(rng),
            color = palette.random(rng).toInt(),
            hunger = rng.nextInt(60, 90).toFloat(),
            rest = rng.nextInt(60, 90).toFloat(),
        )
    }

    fun addPawn(p: Pawn) {
        pawns.add(p)
        stats.colonistsJoined++
        stats.peakPopulation = maxOf(stats.peakPopulation, pawns.size)
    }

    /** A passable tile on the map edge (or anywhere passable, as a fallback). */
    fun randomEdgeSpot(): Pos? {
        repeat(200) {
            val (x, y) = when (rng.nextInt(4)) {
                0 -> rng.nextInt(width) to 0
                1 -> rng.nextInt(width) to height - 1
                2 -> 0 to rng.nextInt(height)
                else -> width - 1 to rng.nextInt(height)
            }
            if (passable(x, y)) return Pos(x, y)
        }
        repeat(500) {
            val x = rng.nextInt(width)
            val y = rng.nextInt(height)
            if (passable(x, y)) return Pos(x, y)
        }
        return null
    }

    // ---------------------------------------------------------------- player orders

    /** Applies a tool to a tile. Returns true if anything changed. */
    fun applyTool(tool: Tool, x: Int, y: Int): Boolean {
        if (!inBounds(x, y)) return false
        val t = tile(x, y)
        val order: JobType? = when (tool) {
            Tool.INSPECT -> return false
            Tool.CANCEL -> {
                val had = t.designation != null
                t.designation = null
                return had
            }
            Tool.HARVEST -> when (t.feature) {
                Feature.TREE, Feature.BERRY_BUSH -> JobType.CHOP
                Feature.ROCK -> JobType.MINE
                else -> null
            }
            Tool.FIELD -> if (t.buildable && t.terrain != Terrain.SAND) JobType.PLANT_FIELD else null
            Tool.BED -> if (t.buildable) JobType.BUILD_BED else null
            Tool.WALL -> if (t.buildable) JobType.BUILD_WALL else null
            Tool.DEMOLISH -> when (t.feature) {
                Feature.BED, Feature.WALL, Feature.FIELD -> JobType.DEMOLISH
                else -> null
            }
        }
        if (order == null || t.designation == order) return false
        t.designation = order
        return true
    }

    fun expandCost(): Pair<Int, Int> = (20 + stats.expansions * 15) to (10 + stats.expansions * 10)

    /** Grows the map by 8 tiles to the right and bottom. Returns false if unaffordable. */
    fun expand(): Boolean {
        val (w, s) = expandCost()
        if (wood < w || stone < s) return false
        wood -= w
        stone -= s
        val nw = width + 8
        val nh = height + 8
        val newTiles = MutableList(nw * nh) { i ->
            val x = i % nw
            val y = i / nw
            if (x < width && y < height) tile(x, y) else MapGen.generateTile(seed, x, y)
        }
        width = nw
        height = nh
        tiles = newTiles
        stats.expansions++
        log("The colony claimed new land. The map is now ${width}x$height.", LogKind.GOOD)
        return true
    }

    // ---------------------------------------------------------------- simulation

    fun update() {
        if (isGameOver) return
        tick++
        updateEnvironment()
        // Iterate over a copy: pawns can die or join mid-update.
        for (p in pawns.toList()) updatePawn(p)
        if (tick >= nextEventTick) {
            Events.fire(this)
            nextEventTick = tick + rng.nextInt(TICKS_PER_DAY * 2 / 3, TICKS_PER_DAY * 2)
        }
        if (tick % TICKS_PER_DAY == 0L) {
            // A little food spoils every day, so hoarding has limits and winter bites.
            food -= food * 3 / 100
            val d = day
            if ((d - 1) % DAYS_PER_SEASON == 0) log("${season.label} of year $year has begun.", LogKind.INFO)
        }
    }

    private fun updateEnvironment() {
        val factor = if (coldSnapTicks > 0) 0f else season.growthFactor
        if (coldSnapTicks > 0) coldSnapTicks--
        for (t in tiles) {
            when (t.feature) {
                Feature.FIELD -> t.growth = (t.growth + 0.2f * factor).coerceAtMost(100f)
                Feature.BERRY_BUSH -> t.growth = (t.growth + 0.15f * factor).coerceAtMost(100f)
                else -> {}
            }
        }
        // Slow natural regrowth keeps the map from ever running dry.
        if (factor > 0f && rng.nextFloat() < 0.05f) {
            val x = rng.nextInt(width)
            val y = rng.nextInt(height)
            val t = tile(x, y)
            if (t.terrain == Terrain.GRASS && t.feature == Feature.NONE && t.designation == null &&
                pawnAt(x, y) == null
            ) {
                val nearTree = neighbors(x, y).any { tile(it.x, it.y).feature == Feature.TREE }
                when {
                    nearTree -> t.feature = Feature.TREE
                    rng.nextFloat() < 0.15f -> { t.feature = Feature.BERRY_BUSH; t.growth = 0f }
                }
            }
        }
    }

    private fun updatePawn(p: Pawn) {
        p.prevX = p.x
        p.prevY = p.y

        // Needs.
        val hungerRate = if (p.trait == Trait.GLUTTON) 0.55f else 0.4f
        p.hunger = (p.hunger - hungerRate).coerceAtLeast(0f)
        if (!p.sleeping) {
            val restRate = if (p.trait == Trait.NIGHT_OWL) 0.22f else 0.3f
            p.rest = (p.rest - restRate).coerceAtLeast(0f)
        }

        // Health.
        if (p.hunger <= 0f) p.health -= 0.15f
        if (p.sickTicks > 0) {
            p.sickTicks--
            p.health -= 0.03f
            if (p.sickTicks == 0) log("${p.name} has recovered from illness.", LogKind.GOOD)
        } else if (p.hunger > 30f && p.health < 100f) {
            p.health = (p.health + if (p.sleeping) 0.12f else 0.04f).coerceAtMost(100f)
        }
        if (p.health <= 0f) {
            killPawn(p, if (p.hunger <= 0f) "starved to death" else "succumbed to their wounds")
            return
        }

        // Mood.
        p.thoughts.forEach { it.ticksLeft-- }
        p.thoughts.removeAll { it.ticksLeft <= 0 }
        val target = p.targetMood()
        p.mood += (target - p.mood).coerceIn(-0.4f, 0.4f)

        if (p.breakTicks > 0) {
            p.breakTicks--
            if (p.breakTicks == 0) {
                p.addThought("Catharsis", 15, TICKS_PER_DAY)
                p.job = null
                p.path.clear()
            }
        } else if (p.mood < 20f && rng.nextFloat() < 0.004f) {
            startMentalBreak(p)
        }

        // Idle pawns re-plan every few ticks rather than every tick; searching the
        // whole map per pawn per tick gets expensive once the colony is large.
        if (p.job == null && (tick + p.id) % 3 == 0L) decide(p)
        val job = p.job ?: return

        if (p.path.isNotEmpty()) {
            val next = p.path.first()
            if (!passable(next.x, next.y)) {
                // Something got built in the way; think again next tick.
                p.job = null
                p.path.clear()
                return
            }
            p.path.removeAt(0)
            p.x = next.x
            p.y = next.y
            return
        }
        perform(p, job)
    }

    private fun startMentalBreak(p: Pawn) {
        p.breakTicks = rng.nextInt(80, 180)
        p.job = null
        p.path.clear()
        if (food >= 3 && rng.nextBoolean()) {
            val eaten = minOf(food, rng.nextInt(3, 7))
            food -= eaten
            p.hunger = 100f
            log("${p.name} snapped and went on a food binge, eating $eaten meals.", LogKind.BAD)
        } else {
            log("${p.name} is having a mental break and wanders in a daze.", LogKind.BAD)
        }
    }

    private fun killPawn(p: Pawn, cause: String) {
        pawns.remove(p)
        stats.colonistsLost++
        log("${p.name} $cause.", LogKind.BAD)
        for (other in pawns) other.addThought("${p.name} died", -15, TICKS_PER_DAY * 3)
        if (pawns.isEmpty()) {
            log("The colony has fallen after ${day} days.", LogKind.BAD)
        }
    }

    // ---------------------------------------------------------------- AI

    private fun decide(p: Pawn) {
        if (p.inMentalBreak) {
            wander(p, 6)
            return
        }
        if (p.hunger < 30f) {
            if (food > 0) {
                p.job = Job(JobType.EAT, p.x, p.y)
                return
            }
            if (findWork(p, foodOnly = true)) return
        }
        val sleepy = p.rest < 20f || (isNight && p.rest < 75f)
        if (sleepy && p.hunger > 10f) {
            goToSleep(p)
            return
        }
        if (findWork(p, foodOnly = false)) return
        if (rng.nextFloat() < 0.15f) wander(p, 4)
    }

    private fun wander(p: Pawn, radius: Int) {
        repeat(10) {
            val x = p.x + rng.nextInt(-radius, radius + 1)
            val y = p.y + rng.nextInt(-radius, radius + 1)
            if (passable(x, y)) {
                val path = Pathfinder.find(this, p.x, p.y, maxNodes = 400) { cx, cy -> cx == x && cy == y }
                if (path != null) {
                    p.job = Job(JobType.WANDER, x, y)
                    p.path.clear()
                    p.path.addAll(path)
                    return
                }
            }
        }
    }

    private fun goToSleep(p: Pawn) {
        val taken = pawns.filter { it !== p && it.job?.type == JobType.SLEEP }
            .map { Pos(it.job!!.x, it.job!!.y) }.toSet()
        val path = Pathfinder.find(this, p.x, p.y) { x, y ->
            tile(x, y).feature == Feature.BED && Pos(x, y) !in taken
        }
        p.path.clear()
        if (path != null) {
            val dest = path.lastOrNull() ?: Pos(p.x, p.y)
            p.job = Job(JobType.SLEEP, dest.x, dest.y, inBed = true)
            p.path.addAll(path)
        } else {
            p.job = Job(JobType.SLEEP, p.x, p.y, inBed = false)
        }
    }

    /** What job (if any) is currently available at a tile. */
    private fun availableJob(t: Tile, foodOnly: Boolean, foodLow: Boolean): JobType? {
        if (t.feature == Feature.FIELD && t.growth >= 100f) return JobType.HARVEST_FIELD
        if (t.feature == Feature.BERRY_BUSH && t.growth >= 100f && (foodOnly || foodLow)) return JobType.FORAGE
        if (foodOnly) return null
        return when (val d = t.designation) {
            null -> null
            JobType.BUILD_BED -> if (wood >= BED_WOOD && t.buildable) d else null
            JobType.BUILD_WALL -> if (stone >= WALL_STONE && t.buildable) d else null
            JobType.PLANT_FIELD -> if (t.buildable) d else null
            else -> d
        }
    }

    private fun findWork(p: Pawn, foodOnly: Boolean): Boolean {
        val foodLow = food < pawns.size * 5
        val claimed = HashSet<Int>()
        for (o in pawns) {
            val j = o.job ?: continue
            if (o !== p && j.type.isWork) claimed.add(j.y * width + j.x)
        }
        val jobs = HashMap<Int, JobType>()
        for (i in tiles.indices) {
            if (i in claimed) continue
            val jt = availableJob(tiles[i], foodOnly, foodLow) ?: continue
            jobs[i] = jt
        }
        if (jobs.isEmpty()) return false

        var target = -1
        val path = Pathfinder.find(this, p.x, p.y) { x, y ->
            target = workTargetFrom(x, y, jobs)
            target >= 0
        } ?: return false
        val type = jobs.getValue(target)
        p.job = Job(type, target % width, target / width)
        p.path.clear()
        p.path.addAll(path)
        return true
    }

    /** A job tile workable while standing at (x, y): the tile itself or one of its 4 neighbours. */
    private fun workTargetFrom(x: Int, y: Int, jobs: Map<Int, JobType>): Int {
        val here = y * width + x
        if (here in jobs) return here
        for (n in neighbors(x, y)) {
            val i = n.y * width + n.x
            if (i in jobs) return i
        }
        return -1
    }

    fun neighbors(x: Int, y: Int): List<Pos> = buildList {
        if (x > 0) add(Pos(x - 1, y))
        if (x < width - 1) add(Pos(x + 1, y))
        if (y > 0) add(Pos(x, y - 1))
        if (y < height - 1) add(Pos(x, y + 1))
    }

    private fun perform(p: Pawn, job: Job) {
        when (job.type) {
            JobType.WANDER -> p.job = null
            JobType.EAT -> {
                job.progress += 1f
                if (job.progress >= job.type.work) {
                    if (food > 0) {
                        food--
                        p.hunger = (p.hunger + 55f).coerceAtMost(100f)
                    }
                    p.job = null
                }
            }
            JobType.SLEEP -> {
                if (job.inBed && tile(job.x, job.y).feature != Feature.BED) {
                    p.job = null
                    return
                }
                p.rest = (p.rest + if (job.inBed) 1.1f else 0.7f).coerceAtMost(100f)
                val rested = p.rest >= 100f || (!isNight && p.rest >= 80f)
                if (rested || p.hunger < 8f) {
                    if (job.inBed) p.addThought("Slept in a bed", 4, TICKS_PER_DAY)
                    else p.addThought("Slept on the ground", -8, TICKS_PER_DAY)
                    p.job = null
                }
            }
            else -> doWork(p, job)
        }
    }

    private fun doWork(p: Pawn, job: Job) {
        val t = tile(job.x, job.y)
        val stillValid = abs(p.x - job.x) + abs(p.y - job.y) <= 1 &&
            availableJob(t, foodOnly = job.type == JobType.FORAGE, foodLow = true) == job.type
        if (!stillValid) {
            p.job = null
            return
        }
        job.progress += p.workSpeed
        if (job.progress < job.type.work) return
        p.job = null
        when (job.type) {
            JobType.CHOP -> {
                val gained = if (t.feature == Feature.TREE) 6 else 1
                wood += gained
                t.feature = Feature.NONE
                t.growth = 0f
            }
            JobType.MINE -> {
                stone += 5
                t.feature = Feature.NONE
                t.terrain = Terrain.DIRT
            }
            JobType.PLANT_FIELD -> {
                t.feature = Feature.FIELD
                t.terrain = Terrain.DIRT
                t.growth = 0f
            }
            JobType.BUILD_BED -> {
                wood -= BED_WOOD
                t.feature = Feature.BED
            }
            JobType.BUILD_WALL -> {
                stone -= WALL_STONE
                t.feature = Feature.WALL
            }
            JobType.DEMOLISH -> {
                when (t.feature) {
                    Feature.BED -> wood += BED_WOOD / 2
                    Feature.WALL -> stone += WALL_STONE / 2
                    else -> {}
                }
                t.feature = Feature.NONE
            }
            JobType.HARVEST_FIELD -> {
                val gained = if (p.trait == Trait.GREEN_THUMB) 7 else 5
                food += gained
                stats.foodHarvested += gained
                t.growth = 0f
            }
            JobType.FORAGE -> {
                val gained = if (p.trait == Trait.GREEN_THUMB) 4 else 3
                food += gained
                stats.foodHarvested += gained
                t.growth = 0f
            }
            else -> {}
        }
        if (t.designation == job.type) t.designation = null
    }

    // ---------------------------------------------------------------- persistence

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val BED_WOOD = 6
        const val WALL_STONE = 3

        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): Colony {
            val c = json.decodeFromString(serializer(), text)
            c.rng = Random(c.seed + c.tick)
            return c
        }
    }
}
