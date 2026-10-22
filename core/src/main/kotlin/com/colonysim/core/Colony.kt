package com.colonysim.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

const val TICKS_PER_DAY = 240
const val DAYS_PER_SEASON = 8
private const val MAX_LOG = 60

private val SLEEP_THOUGHTS = setOf(
    "Slept in a nice bedroom", "Slept in a bedroom", "Slept outside", "Slept on the ground",
)
private val MEAL_THOUGHTS = setOf("Ate raw food", "Ate a cooked meal")

fun startingStock(): MutableMap<Resource, Int> = mutableMapOf(
    Resource.FOOD to 24,
    Resource.MEAL to 0,
    Resource.WOOD to 20,
    Resource.STONE to 0,
    Resource.METAL to 0,
    Resource.HERBS to 3,
)

@Serializable
class Colony(
    var width: Int,
    var height: Int,
    var tiles: MutableList<Tile>,
    val seed: Long,
    val pawns: MutableList<Pawn> = mutableListOf(),
    val stock: MutableMap<Resource, Int> = startingStock(),
    var tick: Long = 0,
    var nextPawnId: Int = 1,
    var nextEventTick: Long = TICKS_PER_DAY.toLong(),
    var coldSnapTicks: Int = 0,
    val log: MutableList<LogEntry> = mutableListOf(),
    val stats: Stats = Stats(),
    // Resource counts from saves made before [stock] existed.
    @SerialName("food") private var legacyFood: Int? = null,
    @SerialName("wood") private var legacyWood: Int? = null,
    @SerialName("stone") private var legacyStone: Int? = null,
) {
    @Transient
    var rng: Random = Random(seed)

    @Transient
    private var roomMap: RoomMap? = null

    // ---------------------------------------------------------------- time

    val day: Int get() = (tick / TICKS_PER_DAY).toInt() + 1
    val hour: Int get() = ((tick % TICKS_PER_DAY) * 24 / TICKS_PER_DAY).toInt()
    val isNight: Boolean get() = hour < 6 || hour >= 21
    val season: Season get() = Season.entries[((day - 1) / DAYS_PER_SEASON) % 4]
    val year: Int get() = (day - 1) / (DAYS_PER_SEASON * 4) + 1
    val isGameOver: Boolean get() = pawns.isEmpty()
    val isCold: Boolean get() = season == Season.WINTER || coldSnapTicks > 0

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

    // ---------------------------------------------------------------- resources

    operator fun get(r: Resource): Int = stock[r] ?: 0

    fun add(r: Resource, n: Int) {
        stock[r] = max(0, this[r] + n)
    }

    fun canAfford(cost: Cost) = cost.all { (r, n) -> this[r] >= n }

    private fun spend(cost: Cost) = cost.forEach { (r, n) -> add(r, -n) }

    val edibleFood: Int get() = this[Resource.FOOD] + this[Resource.MEAL]

    // ---------------------------------------------------------------- grid

    fun inBounds(x: Int, y: Int) = x in 0 until width && y in 0 until height
    fun tile(x: Int, y: Int): Tile = tiles[y * width + x]
    fun passable(x: Int, y: Int) = inBounds(x, y) && tile(x, y).passable

    fun count(feature: Feature) = tiles.count { it.feature == feature }

    fun pawnAt(x: Int, y: Int): Pawn? = pawns.firstOrNull { it.x == x && it.y == y }

    fun neighbors(x: Int, y: Int): List<Pos> = buildList {
        if (x > 0) add(Pos(x - 1, y))
        if (x < width - 1) add(Pos(x + 1, y))
        if (y > 0) add(Pos(x, y - 1))
        if (y < height - 1) add(Pos(x, y + 1))
    }

    /** The room containing a tile (null for walls and doors). Recomputed lazily after construction. */
    fun roomAt(x: Int, y: Int): Room? {
        val map = roomMap ?: RoomMap(this).also { roomMap = it }
        return map.at(x, y)
    }

    fun isIndoors(x: Int, y: Int) = roomAt(x, y)?.indoors == true

    /** Call after anything changes walls, doors, floors or furniture. */
    fun markRoomsDirty() {
        roomMap = null
    }

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
        when (tool) {
            Tool.Inspect -> return false
            Tool.Cancel -> {
                val had = t.designation != null
                clearOrder(t)
                return had
            }
            Tool.Demolish -> {
                // Unbuilt blueprints and unplanted fields are simply removed.
                if (t.designation == JobType.BUILD || t.designation == JobType.PLANT_FIELD) {
                    clearOrder(t)
                    return true
                }
                val order = when {
                    t.hasStructure -> JobType.DEMOLISH
                    t.feature == Feature.TREE || t.feature == Feature.BERRY_BUSH -> JobType.CHOP
                    t.feature == Feature.ROCK || t.feature == Feature.ORE -> JobType.MINE
                    else -> return false
                }
                return setOrder(t, order)
            }
            Tool.Harvest -> {
                val order = when (t.feature) {
                    Feature.TREE, Feature.BERRY_BUSH -> JobType.CHOP
                    Feature.ROCK, Feature.ORE -> JobType.MINE
                    else -> return false
                }
                return setOrder(t, order)
            }
            is Tool.Plant -> {
                if (t.terrain == Terrain.SAND || t.terrain.isFloor) return false
                if (t.feature == Feature.FIELD) {
                    // Switching an existing field's crop replants it from scratch.
                    if (t.crop == tool.crop) return false
                    t.crop = tool.crop
                    t.growth = 0f
                    return true
                }
                if (t.terrain == Terrain.WATER || t.feature != Feature.NONE) return false
                if (t.designation == JobType.PLANT_FIELD && t.crop == tool.crop) return false
                clearOrder(t)
                t.designation = JobType.PLANT_FIELD
                t.crop = tool.crop
                return true
            }
            is Tool.Build -> {
                val b = tool.building
                if (!t.canBuild(b)) return false
                if (t.designation == JobType.BUILD && t.blueprint == b) return false
                clearOrder(t)
                t.designation = JobType.BUILD
                t.blueprint = b
                return true
            }
        }
    }

    private fun setOrder(t: Tile, order: JobType): Boolean {
        if (t.designation == order) return false
        clearOrder(t)
        t.designation = order
        return true
    }

    private fun clearOrder(t: Tile) {
        if (t.designation == JobType.PLANT_FIELD && t.feature != Feature.FIELD) t.crop = null
        t.designation = null
        t.blueprint = null
    }

    fun expandCost(): Cost = mapOf(
        Resource.WOOD to 20 + stats.expansions * 15,
        Resource.STONE to 10 + stats.expansions * 10,
    )

    /** Grows the map by 8 tiles to the right and bottom. Returns false if unaffordable. */
    fun expand(): Boolean {
        val cost = expandCost()
        if (!canAfford(cost)) return false
        spend(cost)
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
        markRoomsDirty()
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
            // Food spoils a little every day (cooked meals keep better), so hoarding
            // has limits and winter bites.
            add(Resource.FOOD, -this[Resource.FOOD] * 3 / 100)
            add(Resource.MEAL, -this[Resource.MEAL] / 100)
            if ((day - 1) % DAYS_PER_SEASON == 0) log("${season.label} of year $year has begun.", LogKind.INFO)
        }
    }

    private fun updateEnvironment() {
        val factor = if (coldSnapTicks > 0) 0f else season.growthFactor
        if (coldSnapTicks > 0) coldSnapTicks--
        for (t in tiles) {
            when (t.feature) {
                Feature.FIELD -> {
                    val rate = (t.crop ?: Crop.POTATO).growRate
                    t.growth = (t.growth + rate * factor).coerceAtMost(100f)
                }
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
                pawnAt(x, y) == null && !isIndoors(x, y)
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
        if (p.medicineCooldown > 0) p.medicineCooldown--

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
        if ((tick + p.id) % 30 == 0L && statueNear(p.x, p.y)) p.addThought("Admired a statue", 5, 60)
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

    private fun statueNear(x: Int, y: Int): Boolean {
        for (dy in -4..4) for (dx in -4..4) {
            val nx = x + dx
            val ny = y + dy
            if (inBounds(nx, ny) && tile(nx, ny).feature == Feature.STATUE) return true
        }
        return false
    }

    private fun startMentalBreak(p: Pawn) {
        p.breakTicks = rng.nextInt(80, 180)
        p.job = null
        p.path.clear()
        if (edibleFood >= 3 && rng.nextBoolean()) {
            var eaten = 0
            repeat(rng.nextInt(3, 7)) {
                val r = if (this[Resource.MEAL] > 0) Resource.MEAL else Resource.FOOD
                if (this[r] > 0) { add(r, -1); eaten++ }
            }
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
        if ((p.sickTicks > 0 || p.health < 60f) && this[Resource.HERBS] > 0 && p.medicineCooldown == 0) {
            p.job = Job(JobType.MEDICINE, p.x, p.y)
            return
        }
        if (p.hunger < 30f) {
            if (edibleFood > 0) {
                goEat(p)
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

    /** Eat at a nearby table if there is one, otherwise on the spot. */
    private fun goEat(p: Pawn) {
        p.path.clear()
        val path = Pathfinder.find(this, p.x, p.y, maxNodes = 900) { x, y ->
            neighbors(x, y).any { tile(it.x, it.y).feature == Feature.TABLE }
        }
        if (path != null) {
            val dest = path.lastOrNull() ?: Pos(p.x, p.y)
            p.job = Job(JobType.EAT, dest.x, dest.y, atTable = true)
            p.path.addAll(path)
        } else {
            p.job = Job(JobType.EAT, p.x, p.y)
        }
    }

    private fun goToSleep(p: Pawn) {
        val taken = pawns.filter { it !== p && it.job?.type == JobType.SLEEP }
            .map { Pos(it.job!!.x, it.job!!.y) }.toSet()
        // Prefer a bed indoors: search for one first, then any bed.
        val bedPath = Pathfinder.find(this, p.x, p.y) { x, y ->
            tile(x, y).feature == Feature.BED && Pos(x, y) !in taken && isIndoors(x, y)
        } ?: Pathfinder.find(this, p.x, p.y) { x, y ->
            tile(x, y).feature == Feature.BED && Pos(x, y) !in taken
        }
        p.path.clear()
        if (bedPath != null) {
            val dest = bedPath.lastOrNull() ?: Pos(p.x, p.y)
            p.job = Job(JobType.SLEEP, dest.x, dest.y, inBed = true)
            p.path.addAll(bedPath)
        } else {
            p.job = Job(JobType.SLEEP, p.x, p.y, inBed = false)
        }
    }

    /** What job (if any) is currently available at a tile. */
    private fun availableJob(t: Tile, foodOnly: Boolean, foodLow: Boolean): JobType? {
        // Player orders to tear something down take precedence over harvesting it.
        if (t.designation == JobType.DEMOLISH && !foodOnly) return if (t.hasStructure) JobType.DEMOLISH else null
        if (t.feature == Feature.FIELD && t.growth >= 100f) {
            val crop = t.crop ?: Crop.POTATO
            if (!foodOnly || crop.yields == Resource.FOOD) return JobType.HARVEST_FIELD
        }
        if (t.feature == Feature.BERRY_BUSH && t.growth >= 100f && (foodOnly || foodLow)) return JobType.FORAGE
        if (foodOnly) return null
        if ((t.feature == Feature.CAMPFIRE || t.feature == Feature.STOVE) &&
            this[Resource.FOOD] >= 3 && this[Resource.MEAL] < pawns.size * 4
        ) return JobType.COOK
        return when (val d = t.designation) {
            null -> null
            JobType.BUILD -> {
                val b = t.blueprint ?: return null
                if (canAfford(b.cost) && t.canBuild(b)) d else null
            }
            JobType.PLANT_FIELD -> if (t.feature == Feature.NONE) d else null
            JobType.CHOP -> if (t.feature == Feature.TREE || t.feature == Feature.BERRY_BUSH) d else null
            JobType.MINE -> if (t.feature == Feature.ROCK || t.feature == Feature.ORE) d else null
            else -> null
        }
    }

    private fun workRequired(type: JobType, t: Tile): Float = when (type) {
        JobType.CHOP -> if (t.feature == Feature.TREE) 30f else 8f
        JobType.MINE -> if (t.feature == Feature.ORE) 70f else 50f
        JobType.PLANT_FIELD -> 15f
        JobType.BUILD -> t.blueprint?.work ?: 20f
        JobType.DEMOLISH -> if (t.feature == Feature.NONE) 8f else 20f
        JobType.HARVEST_FIELD -> 12f
        JobType.FORAGE -> 8f
        JobType.COOK -> if (t.feature == Feature.STOVE) 10f else 20f
        else -> 6f
    }

    private fun findWork(p: Pawn, foodOnly: Boolean): Boolean {
        val foodLow = edibleFood < pawns.size * 5
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

    /**
     * A job tile workable while standing at (x, y): one of its 4 neighbours, or the
     * tile itself unless the job would put something solid where the pawn stands.
     */
    private fun workTargetFrom(x: Int, y: Int, jobs: Map<Int, JobType>): Int {
        val here = y * width + x
        if (here in jobs && !(jobs[here] == JobType.BUILD && tiles[here].blueprint?.blocksMovement == true)) {
            return here
        }
        for (n in neighbors(x, y)) {
            val i = n.y * width + n.x
            if (i in jobs) return i
        }
        return -1
    }

    private fun perform(p: Pawn, job: Job) {
        when (job.type) {
            JobType.WANDER -> p.job = null
            JobType.EAT -> {
                job.progress += 1f
                if (job.progress >= 6f) {
                    eat(p, job.atTable)
                    p.job = null
                }
            }
            JobType.MEDICINE -> {
                job.progress += 1f
                if (job.progress >= 5f) {
                    if (this[Resource.HERBS] > 0) {
                        add(Resource.HERBS, -1)
                        p.health = (p.health + 30f).coerceAtMost(100f)
                        p.sickTicks /= 4
                        p.medicineCooldown = TICKS_PER_DAY / 2
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
                    wakeUp(p, job)
                    p.job = null
                }
            }
            else -> doWork(p, job)
        }
    }

    private fun eat(p: Pawn, atTable: Boolean) {
        if (this[Resource.MEAL] > 0) {
            add(Resource.MEAL, -1)
            p.hunger = (p.hunger + 90f).coerceAtMost(100f)
            p.addThought(MEAL_THOUGHTS, "Ate a cooked meal", 3, TICKS_PER_DAY / 2)
        } else if (this[Resource.FOOD] > 0) {
            add(Resource.FOOD, -1)
            p.hunger = (p.hunger + 50f).coerceAtMost(100f)
            p.addThought(MEAL_THOUGHTS, "Ate raw food", -4, TICKS_PER_DAY / 2)
        } else {
            return
        }
        if (atTable) p.addThought("Ate at a table", 4, TICKS_PER_DAY / 2)
    }

    private fun wakeUp(p: Pawn, job: Job) {
        val room = roomAt(job.x, job.y)
        val indoors = room?.indoors == true
        when {
            job.inBed && indoors && room!!.impressiveness >= Room.NICE_ROOM ->
                p.addThought(SLEEP_THOUGHTS, "Slept in a nice bedroom", 8, TICKS_PER_DAY)
            job.inBed && indoors -> p.addThought(SLEEP_THOUGHTS, "Slept in a bedroom", 4, TICKS_PER_DAY)
            job.inBed -> p.addThought(SLEEP_THOUGHTS, "Slept outside", -2, TICKS_PER_DAY)
            else -> p.addThought(SLEEP_THOUGHTS, "Slept on the ground", -8, TICKS_PER_DAY)
        }
        if (!indoors && isCold) p.addThought("Cold night outside", -6, TICKS_PER_DAY)
    }

    private fun doWork(p: Pawn, job: Job) {
        val t = tile(job.x, job.y)
        val stillValid = abs(p.x - job.x) + abs(p.y - job.y) <= 1 &&
            availableJob(t, foodOnly = job.type == JobType.FORAGE, foodLow = true) == job.type
        if (!stillValid) {
            p.job = null
            return
        }
        val required = workRequired(job.type, t)
        if (job.progress < required) {
            job.progress += p.workSpeed
            return
        }
        // Don't wall someone in: wait until the tile is clear.
        if (job.type == JobType.BUILD && t.blueprint?.blocksMovement == true && pawnAt(job.x, job.y) != null) return
        p.job = null
        complete(p, job.type, t)
    }

    private fun complete(p: Pawn, type: JobType, t: Tile) {
        when (type) {
            JobType.CHOP -> {
                add(Resource.WOOD, if (t.feature == Feature.TREE) 6 else 1)
                t.feature = Feature.NONE
                t.growth = 0f
            }
            JobType.MINE -> {
                if (t.feature == Feature.ORE) {
                    add(Resource.METAL, 3)
                    add(Resource.STONE, 1)
                } else {
                    add(Resource.STONE, 5)
                }
                t.feature = Feature.NONE
                t.terrain = Terrain.DIRT
                markRoomsDirty()
            }
            JobType.PLANT_FIELD -> {
                t.feature = Feature.FIELD
                t.terrain = Terrain.DIRT
                t.growth = 0f
                if (t.crop == null) t.crop = Crop.POTATO
            }
            JobType.BUILD -> {
                val b = t.blueprint ?: return
                spend(b.cost)
                if (b.feature != null) t.feature = b.feature
                if (b.floor != null) t.terrain = b.floor
                t.blueprint = null
                markRoomsDirty()
            }
            JobType.DEMOLISH -> {
                val b = Building.forFeature(t.feature)
                when {
                    b != null -> {
                        refund(b)
                        t.feature = Feature.NONE
                    }
                    t.feature == Feature.FIELD -> {
                        t.feature = Feature.NONE
                        t.crop = null
                        t.growth = 0f
                    }
                    t.terrain.isFloor -> {
                        Building.forFloor(t.terrain)?.let { refund(it) }
                        t.terrain = Terrain.DIRT
                    }
                }
                markRoomsDirty()
            }
            JobType.HARVEST_FIELD -> {
                val crop = t.crop ?: Crop.POTATO
                var gained = crop.amount
                if (p.trait == Trait.GREEN_THUMB) gained += (crop.amount + 1) / 2
                add(crop.yields, gained)
                if (crop.yields == Resource.FOOD) stats.foodHarvested += gained
                t.growth = 0f
            }
            JobType.FORAGE -> {
                val gained = if (p.trait == Trait.GREEN_THUMB) 4 else 3
                add(Resource.FOOD, gained)
                stats.foodHarvested += gained
                t.growth = 0f
            }
            JobType.COOK -> {
                if (this[Resource.FOOD] >= 3) {
                    add(Resource.FOOD, -3)
                    add(Resource.MEAL, 2)
                }
            }
            else -> {}
        }
        if (t.designation == type) t.designation = null
    }

    /** Demolishing returns half the materials, rounded up. */
    private fun refund(b: Building) = b.cost.forEach { (r, n) -> add(r, (n + 1) / 2) }

    // ---------------------------------------------------------------- persistence

    fun toJson(): String = json.encodeToString(serializer(), this)

    /** Upgrades data from older save formats. */
    private fun migrate() {
        legacyFood?.let { stock[Resource.FOOD] = it }
        legacyWood?.let { stock[Resource.WOOD] = it }
        legacyStone?.let { stock[Resource.STONE] = it }
        legacyFood = null
        legacyWood = null
        legacyStone = null
        for (r in Resource.entries) stock.putIfAbsent(r, 0)
        for (t in tiles) {
            when (t.designation) {
                JobType.BUILD_BED -> { t.designation = JobType.BUILD; t.blueprint = Building.BED }
                JobType.BUILD_WALL -> { t.designation = JobType.BUILD; t.blueprint = Building.STONE_WALL }
                else -> {}
            }
            if (t.feature == Feature.FIELD && t.crop == null) t.crop = Crop.POTATO
        }
        for (p in pawns) {
            val type = p.job?.type
            if (type == JobType.BUILD_BED || type == JobType.BUILD_WALL) {
                p.job = null
                p.path.clear()
            }
        }
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        fun fromJson(text: String): Colony {
            val c = json.decodeFromString(serializer(), text)
            c.rng = Random(c.seed + c.tick)
            c.migrate()
            return c
        }
    }
}
