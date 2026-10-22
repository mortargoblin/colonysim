package com.colonysim.core

object Pathfinder {
    /**
     * Breadth-first search over passable tiles from (sx, sy) to the nearest tile
     * satisfying [goal]. Returns the steps to take (excluding the start), an empty
     * list if the start already satisfies the goal, or null if nothing is reachable.
     * The start tile is always allowed, so pawns can walk out of freshly built walls.
     */
    fun find(
        colony: Colony,
        sx: Int,
        sy: Int,
        maxNodes: Int = Int.MAX_VALUE,
        goal: (Int, Int) -> Boolean,
    ): List<Pos>? {
        val w = colony.width
        val start = sy * w + sx
        if (goal(sx, sy)) return emptyList()
        val cameFrom = IntArray(w * colony.height) { -1 }
        cameFrom[start] = start
        val queue = ArrayDeque<Int>()
        queue.add(start)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val cur = queue.removeFirst()
            visited++
            val cx = cur % w
            val cy = cur / w
            for (n in colony.neighbors(cx, cy)) {
                val ni = n.y * w + n.x
                if (cameFrom[ni] != -1 || !colony.passable(n.x, n.y)) continue
                cameFrom[ni] = cur
                if (goal(n.x, n.y)) return buildPath(cameFrom, start, ni, w)
                queue.add(ni)
            }
        }
        return null
    }

    private fun buildPath(cameFrom: IntArray, start: Int, end: Int, w: Int): List<Pos> {
        val path = ArrayList<Pos>()
        var cur = end
        while (cur != start) {
            path.add(Pos(cur % w, cur / w))
            cur = cameFrom[cur]
        }
        path.reverse()
        return path
    }
}
