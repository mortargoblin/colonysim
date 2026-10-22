package com.colonysim.core

/** A connected area bounded by walls, doors and rock. */
class Room(val id: Int, val indoors: Boolean) {
    var size = 0
    var floored = 0
    var beds = 0
    var tables = 0
    var statues = 0

    /** 0 for outdoors; indoor rooms score for floors, decoration and a sensible size. */
    val impressiveness: Int
        get() {
            if (!indoors) return 0
            var score = floored * 10 / size.coerceAtLeast(1)
            score += statues * 6
            score += tables * 2
            if (size in 6..60) score += 3
            if (size < 4) score -= 3
            return score
        }

    val description: String
        get() = when {
            !indoors -> "Outdoors"
            else -> "Indoor room: $size tiles, impressiveness $impressiveness" +
                if (impressiveness >= NICE_ROOM) " (nice)" else ""
        }

    companion object {
        const val NICE_ROOM = 12
        /** Enclosed areas larger than this are treated as outdoors (e.g. a walled-in field). */
        const val MAX_INDOOR_SIZE = 160
    }
}

class RoomMap(colony: Colony) {
    /** Room id per tile, or -1 for walls and doors. */
    private val roomOf = IntArray(colony.width * colony.height) { -1 }
    private val rooms = ArrayList<Room>()
    private val width = colony.width

    init {
        val w = colony.width
        val h = colony.height
        val queue = ArrayDeque<Int>()
        val members = ArrayList<Int>()
        for (start in 0 until w * h) {
            if (roomOf[start] != -1 || colony.tiles[start].feature.roomBoundary) continue
            val id = rooms.size
            members.clear()
            var touchesEdge = false
            roomOf[start] = id
            queue.add(start)
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                members.add(i)
                val x = i % w
                val y = i / w
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touchesEdge = true
                for (n in colony.neighbors(x, y)) {
                    val ni = n.y * w + n.x
                    if (roomOf[ni] != -1 || colony.tiles[ni].feature.roomBoundary) continue
                    roomOf[ni] = id
                    queue.add(ni)
                }
            }
            val room = Room(id, indoors = !touchesEdge && members.size <= Room.MAX_INDOOR_SIZE)
            room.size = members.size
            for (i in members) {
                val t = colony.tiles[i]
                if (t.terrain.isFloor) room.floored++
                when (t.feature) {
                    Feature.BED -> room.beds++
                    Feature.TABLE -> room.tables++
                    Feature.STATUE -> room.statues++
                    else -> {}
                }
            }
            rooms.add(room)
        }
    }

    fun at(x: Int, y: Int): Room? = roomOf[y * width + x].let { if (it >= 0) rooms[it] else null }
}
