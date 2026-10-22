package com.colonysim.core

import kotlinx.serialization.Serializable

@Serializable
enum class JobType(val label: String, val isWork: Boolean) {
    CHOP("Chopping", true),
    MINE("Mining", true),
    PLANT_FIELD("Planting", true),
    BUILD("Building", true),
    DEMOLISH("Demolishing", true),
    HARVEST_FIELD("Harvesting", true),
    FORAGE("Foraging", true),
    COOK("Cooking", true),
    EAT("Eating", false),
    MEDICINE("Taking medicine", false),
    SLEEP("Sleeping", false),
    WANDER("Wandering", false),

    // Only present in saves from before generic construction; migrated on load.
    BUILD_BED("Building", true),
    BUILD_WALL("Building", true),
}

@Serializable
enum class Trait(val label: String, val description: String) {
    HARD_WORKER("Hard worker", "Works 50% faster"),
    LAZY("Lazy", "Works 30% slower"),
    GLUTTON("Glutton", "Gets hungry faster"),
    OPTIMIST("Optimist", "+10 mood"),
    PESSIMIST("Pessimist", "-10 mood"),
    NIGHT_OWL("Night owl", "Needs less sleep"),
    TOUGH("Tough", "Takes half damage"),
    GREEN_THUMB("Green thumb", "Harvests extra food"),
}

@Serializable
data class Thought(val label: String, val value: Int, var ticksLeft: Int)

@Serializable
data class Job(
    val type: JobType,
    val x: Int,
    val y: Int,
    var progress: Float = 0f,
    /** For SLEEP: whether the target is a bed. */
    val inBed: Boolean = false,
    /** For EAT: whether the pawn walked to a table. */
    val atTable: Boolean = false,
)

@Serializable
class Pawn(
    val id: Int,
    val name: String,
    var x: Int,
    var y: Int,
    val age: Int,
    val trait: Trait,
    val color: Int,
    var hunger: Float = 80f,
    var rest: Float = 80f,
    var mood: Float = 60f,
    var health: Float = 100f,
    var sickTicks: Int = 0,
    var breakTicks: Int = 0,
    var medicineCooldown: Int = 0,
    var job: Job? = null,
    val path: MutableList<Pos> = mutableListOf(),
    val thoughts: MutableList<Thought> = mutableListOf(),
) {
    /** Previous position, used by the renderer to interpolate movement. Not saved. */
    @kotlinx.serialization.Transient var prevX: Int = x
    @kotlinx.serialization.Transient var prevY: Int = y

    val sleeping: Boolean get() = job?.type == JobType.SLEEP && path.isEmpty()
    val inMentalBreak: Boolean get() = breakTicks > 0

    val workSpeed: Float
        get() {
            var s = when (trait) {
                Trait.HARD_WORKER -> 1.5f
                Trait.LAZY -> 0.7f
                else -> 1f
            }
            if (sickTicks > 0) s *= 0.6f
            if (mood < 25f) s *= 0.8f
            return s
        }

    fun addThought(label: String, value: Int, ticks: Int) {
        // Refresh an existing thought rather than stacking duplicates.
        thoughts.removeAll { it.label == label }
        thoughts.add(Thought(label, value, ticks))
    }

    /** Adds a thought, replacing any others from the same mutually exclusive group. */
    fun addThought(group: Collection<String>, label: String, value: Int, ticks: Int) {
        thoughts.removeAll { it.label in group }
        addThought(label, value, ticks)
    }

    fun status(): String = when {
        inMentalBreak -> "Mental break"
        job == null -> "Idle"
        path.isNotEmpty() && job?.type != JobType.WANDER -> "Walking (${job!!.type.label.lowercase()})"
        else -> job!!.type.label
    }

    /** Mood the pawn is currently drifting towards. */
    fun targetMood(): Float {
        var m = 50f + thoughts.sumOf { it.value }
        m += when (trait) {
            Trait.OPTIMIST -> 10f
            Trait.PESSIMIST -> -10f
            else -> 0f
        }
        if (hunger <= 0f) m -= 20f else if (hunger < 20f) m -= 10f
        if (rest < 15f) m -= 10f
        if (health < 50f) m -= 8f
        if (sickTicks > 0) m -= 5f
        return m.coerceIn(0f, 100f)
    }

    fun moodModifiers(): List<Pair<String, Int>> {
        val list = mutableListOf<Pair<String, Int>>()
        when (trait) {
            Trait.OPTIMIST -> list += "Optimist" to 10
            Trait.PESSIMIST -> list += "Pessimist" to -10
            else -> {}
        }
        if (hunger <= 0f) list += "Starving" to -20 else if (hunger < 20f) list += "Hungry" to -10
        if (rest < 15f) list += "Exhausted" to -10
        if (health < 50f) list += "In pain" to -8
        if (sickTicks > 0) list += "Sick" to -5
        thoughts.forEach { list += it.label to it.value }
        return list
    }
}
