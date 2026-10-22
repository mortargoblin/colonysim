package com.colonysim.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import com.colonysim.core.Colony
import com.colonysim.core.Crop
import com.colonysim.core.Feature
import com.colonysim.core.JobType
import com.colonysim.core.Pawn
import com.colonysim.core.Pos
import com.colonysim.core.Room
import com.colonysim.core.Terrain
import com.colonysim.core.Tile
import com.colonysim.core.Tool
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

/** Draws the colony, runs the simulation clock and handles pan / zoom / painting. */
class ColonyView(context: Context) : View(context) {

    interface Listener {
        fun onTicked()
        fun onSelectionChanged()
    }

    var colony: Colony? = null
        set(value) {
            field = value
            selectedPawnId = null
            selectedTile = null
            centerCamera()
        }
    var listener: Listener? = null
    var tool: Tool = Tool.Inspect
    var paused = false
    var speed = 1

    var selectedPawnId: Int? = null
        private set
    var selectedTile: Pos? = null
        private set

    private val density = resources.displayMetrics.density
    private var tileSize = 40f * density
    private var offsetX = 0f
    private var offsetY = 0f
    private var cameraReady = false

    // ------------------------------------------------------------ clock

    private var lastFrame = 0L
    private var accumulator = 0f

    private val frame = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = (now - lastFrame).coerceIn(0, 250)
            lastFrame = now
            val c = colony
            var ticked = false
            if (c != null && !paused && !c.isGameOver) {
                accumulator = (accumulator + dt * speed).coerceAtMost(TICK_MS * 8)
                while (accumulator >= TICK_MS) {
                    c.update()
                    accumulator -= TICK_MS
                    ticked = true
                }
            }
            if (ticked) listener?.onTicked()
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrame = SystemClock.uptimeMillis()
        postOnAnimation(frame)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------ camera

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!cameraReady) centerCamera()
    }

    private fun centerCamera() {
        val c = colony ?: return
        if (width == 0) return
        tileSize = width / 14f
        val focus = c.pawns.firstOrNull()?.let { Pos(it.x, it.y) } ?: Pos(c.width / 2, c.height / 2)
        offsetX = width / 2f - (focus.x + 0.5f) * tileSize
        offsetY = height / 2f - (focus.y + 0.5f) * tileSize
        cameraReady = true
        clampCamera()
    }

    private fun clampCamera() {
        val c = colony ?: return
        val mapW = c.width * tileSize
        val mapH = c.height * tileSize
        val margin = 2 * tileSize
        // Keep the map on screen; centre it when it is smaller than the view.
        offsetX = if (mapW + 2 * margin > width) offsetX.coerceIn(width - mapW - margin, margin) else (width - mapW) / 2
        offsetY = if (mapH + 2 * margin > height) offsetY.coerceIn(height - mapH - margin, margin) else (height - mapH) / 2
    }

    private fun tileAt(sx: Float, sy: Float): Pos? {
        val c = colony ?: return null
        val x = floor((sx - offsetX) / tileSize).toInt()
        val y = floor((sy - offsetY) / tileSize).toInt()
        return if (c.inBounds(x, y)) Pos(x, y) else null
    }

    // ------------------------------------------------------------ input

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var downX = 0f
    private var downY = 0f
    private var multiTouch = false
    private var movedFar = false
    private var lastPainted: Pos? = null
    private var paintedAny = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val newSize = (tileSize * d.scaleFactor).coerceIn(12f * density, 120f * density)
            val f = newSize / tileSize
            offsetX = d.focusX - (d.focusX - offsetX) * f
            offsetY = d.focusY - (d.focusY - offsetY) * f
            tileSize = newSize
            clampCamera()
            return true
        }
    })

    private fun focus(e: MotionEvent): Pair<Float, Float> {
        val skip = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            sx += e.getX(i)
            sy += e.getY(i)
            n++
        }
        return if (n == 0) e.x to e.y else sx / n to sy / n
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        val c = colony ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiTouch = false
                movedFar = false
                paintedAny = false
                lastPainted = null
                downX = e.x
                downY = e.y
                lastFocusX = e.x
                lastFocusY = e.y
                if (tool != Tool.Inspect) paint(c, e.x, e.y)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                multiTouch = true
                val (fx, fy) = focus(e)
                lastFocusX = fx
                lastFocusY = fy
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.x - downX, e.y - downY) > touchSlop) movedFar = true
                if (tool != Tool.Inspect && !multiTouch) {
                    paint(c, e.x, e.y)
                } else if (movedFar || multiTouch) {
                    val (fx, fy) = focus(e)
                    offsetX += fx - lastFocusX
                    offsetY += fy - lastFocusY
                    lastFocusX = fx
                    lastFocusY = fy
                    clampCamera()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (tool == Tool.Inspect && !multiTouch && !movedFar) select(c, e.x, e.y)
                if (paintedAny) listener?.onSelectionChanged()
            }
        }
        invalidate()
        return true
    }

    private fun paint(c: Colony, sx: Float, sy: Float) {
        val p = tileAt(sx, sy) ?: return
        if (p == lastPainted) return
        lastPainted = p
        if (c.applyTool(tool, p.x, p.y)) paintedAny = true
    }

    private fun select(c: Colony, sx: Float, sy: Float) {
        // Pawns are drawn where they are interpolated to, so pick the nearest one to the finger.
        val wx = (sx - offsetX) / tileSize
        val wy = (sy - offsetY) / tileSize
        val pawn = c.pawns.minByOrNull { hypot(it.x + 0.5f - wx, it.y + 0.5f - wy) }
        if (pawn != null && hypot(pawn.x + 0.5f - wx, pawn.y + 0.5f - wy) < 0.8f) {
            selectedPawnId = if (selectedPawnId == pawn.id) null else pawn.id
            selectedTile = null
        } else {
            val t = tileAt(sx, sy)
            selectedTile = if (t == selectedTile) null else t
            selectedPawnId = null
        }
        listener?.onSelectionChanged()
    }

    fun clearSelection() {
        selectedPawnId = null
        selectedTile = null
    }

    /** Selects a colonist and scrolls the camera to them. */
    fun selectPawn(id: Int) {
        val p = colony?.pawns?.firstOrNull { it.id == id } ?: return
        selectedPawnId = id
        selectedTile = null
        offsetX = width / 2f - (p.x + 0.5f) * tileSize
        offsetY = height / 2f - (p.y + 0.5f) * tileSize
        clampCamera()
        listener?.onSelectionChanged()
        invalidate()
    }

    // ------------------------------------------------------------ drawing

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF1B1F24.toInt())
        val c = colony ?: return
        val ts = tileSize
        val x0 = floor(-offsetX / ts).toInt().coerceAtLeast(0)
        val y0 = floor(-offsetY / ts).toInt().coerceAtLeast(0)
        val x1 = floor((width - offsetX) / ts).toInt().coerceAtMost(c.width - 1)
        val y1 = floor((height - offsetY) / ts).toInt().coerceAtMost(c.height - 1)

        for (y in y0..y1) for (x in x0..x1) {
            drawTile(canvas, c.tile(x, y), x, y, offsetX + x * ts, offsetY + y * ts, ts)
        }

        // Tint the room the selected tile belongs to, so enclosures are easy to check.
        val selectedRoom: Room? = selectedTile?.let { c.roomAt(it.x, it.y) }?.takeIf { it.indoors }
        if (selectedRoom != null) {
            fill.color = 0x3348C9FF
            for (y in y0..y1) for (x in x0..x1) {
                if (c.roomAt(x, y) === selectedRoom) {
                    canvas.drawRect(offsetX + x * ts, offsetY + y * ts, offsetX + (x + 1) * ts, offsetY + (y + 1) * ts, fill)
                }
            }
        }
        selectedTile?.let {
            stroke.color = Color.WHITE
            stroke.strokeWidth = 2.5f * density
            canvas.drawRect(offsetX + it.x * ts, offsetY + it.y * ts, offsetX + (it.x + 1) * ts, offsetY + (it.y + 1) * ts, stroke)
        }

        val frac = if (paused || c.isGameOver) 1f else (accumulator / TICK_MS).coerceIn(0f, 1f)
        for (p in c.pawns) drawPawn(canvas, p, frac, ts)

        val dark = c.darkness
        if (dark > 0f) {
            canvas.drawColor(Color.argb((dark * 110).toInt(), 10, 20, 60))
            // Firelight.
            fill.color = Color.argb((dark * 70).toInt(), 255, 170, 70)
            for (y in y0..y1) for (x in x0..x1) {
                val f = c.tile(x, y).feature
                if (f == Feature.CAMPFIRE || f == Feature.STOVE) {
                    canvas.drawCircle(offsetX + (x + 0.5f) * ts, offsetY + (y + 0.5f) * ts, ts * 2.5f, fill)
                }
            }
        }

        // Selected pawn's ring stays visible through the night tint.
        c.pawns.firstOrNull { it.id == selectedPawnId }?.let { p ->
            val (cx, cy) = pawnCenter(p, frac, ts)
            stroke.color = 0xFFFFEB3B.toInt()
            stroke.strokeWidth = 2.5f * density
            canvas.drawCircle(cx, cy, ts * 0.48f, stroke)
        }
    }

    private fun drawTile(canvas: Canvas, tile: Tile, x: Int, y: Int, l: Float, t: Float, ts: Float) {
        drawTerrain(canvas, tile.terrain, (x + y) % 2 == 0, l, t, ts)
        if (tile.feature != Feature.NONE) {
            val doorOpen = tile.feature == Feature.DOOR && colony?.pawnAt(x, y) != null
            drawFeature(canvas, tile.feature, tile, l, t, ts, doorOpen)
        }

        val d = tile.designation ?: return
        val blueprint = tile.blueprint
        if (d == JobType.BUILD && blueprint != null) {
            // Ghost of the thing to be built.
            canvas.saveLayerAlpha(l, t, l + ts, t + ts, 110)
            blueprint.floor?.let { drawTerrain(canvas, it, true, l, t, ts) }
            blueprint.feature?.let { drawFeature(canvas, it, null, l, t, ts) }
            canvas.restore()
        }
        val color = designationColor(d)
        if (d != JobType.BUILD) {
            fill.color = color and 0x00FFFFFF or 0x40000000
            canvas.drawRect(l, t, l + ts, t + ts, fill)
        }
        stroke.color = color
        stroke.strokeWidth = 2f * density
        val inset = ts * 0.08f
        canvas.drawRect(l + inset, t + inset, l + ts - inset, t + ts - inset, stroke)
    }

    private fun drawTerrain(canvas: Canvas, terrain: Terrain, checker: Boolean, l: Float, t: Float, ts: Float) {
        fill.color = when (terrain) {
            Terrain.GRASS -> if (checker) 0xFF6A9A4A.toInt() else 0xFF659447.toInt()
            Terrain.DIRT -> if (checker) 0xFF8D6E4A.toInt() else 0xFF876946.toInt()
            Terrain.SAND -> if (checker) 0xFFD8C48A.toInt() else 0xFFD2BE84.toInt()
            Terrain.WATER -> if (checker) 0xFF3F7FBF.toInt() else 0xFF3B79B8.toInt()
            Terrain.WOOD_FLOOR -> 0xFFB08050.toInt()
            Terrain.STONE_FLOOR -> 0xFFA7A9AC.toInt()
        }
        canvas.drawRect(l, t, l + ts, t + ts, fill)
        when (terrain) {
            Terrain.WOOD_FLOOR -> {
                fill.color = 0xFF8F643B.toInt()
                for (i in 1..2) canvas.drawRect(l, t + ts * i / 3f - ts * 0.02f, l + ts, t + ts * i / 3f + ts * 0.02f, fill)
            }
            Terrain.STONE_FLOOR -> {
                stroke.color = 0xFF85878A.toInt()
                stroke.strokeWidth = ts * 0.04f
                canvas.drawRect(l + ts * 0.04f, t + ts * 0.04f, l + ts * 0.5f, t + ts * 0.5f, stroke)
                canvas.drawRect(l + ts * 0.5f, t + ts * 0.5f, l + ts * 0.96f, t + ts * 0.96f, stroke)
            }
            else -> {}
        }
    }

    /** [tile] is null when drawing a blueprint ghost. */
    private fun drawFeature(
        canvas: Canvas, feature: Feature, tile: Tile?, l: Float, t: Float, ts: Float, doorOpen: Boolean = false,
    ) {
        when (feature) {
            Feature.NONE -> {}
            Feature.TREE -> {
                fill.color = 0xFF5A3A1E.toInt()
                canvas.drawRect(l + ts * 0.43f, t + ts * 0.55f, l + ts * 0.57f, t + ts * 0.92f, fill)
                fill.color = 0xFF2E6B2E.toInt()
                canvas.drawCircle(l + ts * 0.5f, t + ts * 0.42f, ts * 0.36f, fill)
            }
            Feature.ROCK, Feature.ORE -> {
                fill.color = 0xFF8A8A8A.toInt()
                rect.set(l + ts * 0.08f, t + ts * 0.12f, l + ts * 0.92f, t + ts * 0.9f)
                canvas.drawRoundRect(rect, ts * 0.2f, ts * 0.2f, fill)
                fill.color = 0xFFA5A5A5.toInt()
                rect.set(l + ts * 0.2f, t + ts * 0.2f, l + ts * 0.55f, t + ts * 0.45f)
                canvas.drawRoundRect(rect, ts * 0.1f, ts * 0.1f, fill)
                if (feature == Feature.ORE) {
                    fill.color = 0xFFE08A3C.toInt()
                    canvas.drawCircle(l + ts * 0.66f, t + ts * 0.62f, ts * 0.09f, fill)
                    canvas.drawCircle(l + ts * 0.35f, t + ts * 0.7f, ts * 0.07f, fill)
                    canvas.drawCircle(l + ts * 0.72f, t + ts * 0.32f, ts * 0.06f, fill)
                }
            }
            Feature.BERRY_BUSH -> {
                fill.color = 0xFF3F8F3F.toInt()
                canvas.drawCircle(l + ts * 0.5f, t + ts * 0.55f, ts * 0.32f, fill)
                if (tile != null && tile.growth >= 100f) {
                    fill.color = 0xFFD32F2F.toInt()
                    canvas.drawCircle(l + ts * 0.38f, t + ts * 0.48f, ts * 0.07f, fill)
                    canvas.drawCircle(l + ts * 0.6f, t + ts * 0.45f, ts * 0.07f, fill)
                    canvas.drawCircle(l + ts * 0.5f, t + ts * 0.66f, ts * 0.07f, fill)
                }
            }
            Feature.FIELD -> drawField(canvas, tile, l, t, ts)
            Feature.WALL -> {
                fill.color = 0xFF5A5A5A.toInt()
                canvas.drawRect(l, t, l + ts, t + ts, fill)
                fill.color = 0xFF777777.toInt()
                canvas.drawRect(l, t, l + ts, t + ts * 0.18f, fill)
            }
            Feature.WOOD_WALL -> {
                fill.color = 0xFF6D4C2E.toInt()
                canvas.drawRect(l, t, l + ts, t + ts, fill)
                fill.color = 0xFF56391F.toInt()
                for (i in 1..2) canvas.drawRect(l + ts * i / 3f - ts * 0.025f, t, l + ts * i / 3f + ts * 0.025f, t + ts, fill)
                fill.color = 0xFF8A6440.toInt()
                canvas.drawRect(l, t, l + ts, t + ts * 0.15f, fill)
            }
            Feature.DOOR -> {
                fill.color = 0xFF4A3220.toInt()
                canvas.drawRect(l, t, l + ts, t + ts, fill)
                fill.color = 0xFFA0703F.toInt()
                if (doorOpen) {
                    canvas.drawRect(l + ts * 0.08f, t + ts * 0.08f, l + ts * 0.24f, t + ts * 0.92f, fill)
                } else {
                    canvas.drawRect(l + ts * 0.08f, t + ts * 0.08f, l + ts * 0.92f, t + ts * 0.92f, fill)
                    fill.color = 0xFFFFD54F.toInt()
                    canvas.drawCircle(l + ts * 0.75f, t + ts * 0.52f, ts * 0.06f, fill)
                }
            }
            Feature.BED -> {
                fill.color = 0xFF8B5A2B.toInt()
                rect.set(l + ts * 0.15f, t + ts * 0.08f, l + ts * 0.85f, t + ts * 0.92f)
                canvas.drawRoundRect(rect, ts * 0.08f, ts * 0.08f, fill)
                fill.color = 0xFF5C7FB0.toInt()
                canvas.drawRect(l + ts * 0.2f, t + ts * 0.38f, l + ts * 0.8f, t + ts * 0.88f, fill)
                fill.color = Color.WHITE
                canvas.drawRect(l + ts * 0.25f, t + ts * 0.14f, l + ts * 0.75f, t + ts * 0.32f, fill)
            }
            Feature.TABLE -> {
                fill.color = 0xFF7A5230.toInt()
                canvas.drawRect(l + ts * 0.15f, t + ts * 0.6f, l + ts * 0.25f, t + ts * 0.92f, fill)
                canvas.drawRect(l + ts * 0.75f, t + ts * 0.6f, l + ts * 0.85f, t + ts * 0.92f, fill)
                fill.color = 0xFFA87545.toInt()
                rect.set(l + ts * 0.08f, t + ts * 0.25f, l + ts * 0.92f, t + ts * 0.65f)
                canvas.drawRoundRect(rect, ts * 0.06f, ts * 0.06f, fill)
            }
            Feature.STATUE -> {
                fill.color = 0xFF6E6E6E.toInt()
                canvas.drawRect(l + ts * 0.2f, t + ts * 0.75f, l + ts * 0.8f, t + ts * 0.92f, fill)
                fill.color = 0xFFDADADA.toInt()
                canvas.drawRect(l + ts * 0.4f, t + ts * 0.4f, l + ts * 0.6f, t + ts * 0.76f, fill)
                canvas.drawCircle(l + ts * 0.5f, t + ts * 0.28f, ts * 0.14f, fill)
            }
            Feature.CAMPFIRE -> {
                fill.color = 0xFF5A3A1E.toInt()
                canvas.drawRect(l + ts * 0.2f, t + ts * 0.7f, l + ts * 0.8f, t + ts * 0.82f, fill)
                fill.color = 0xFFFF7043.toInt()
                canvas.drawCircle(l + ts * 0.5f, t + ts * 0.55f, ts * 0.2f, fill)
                fill.color = 0xFFFFD54F.toInt()
                canvas.drawCircle(l + ts * 0.5f, t + ts * 0.6f, ts * 0.1f, fill)
            }
            Feature.STOVE -> {
                fill.color = 0xFF455A64.toInt()
                rect.set(l + ts * 0.1f, t + ts * 0.15f, l + ts * 0.9f, t + ts * 0.9f)
                canvas.drawRoundRect(rect, ts * 0.08f, ts * 0.08f, fill)
                fill.color = 0xFF263238.toInt()
                canvas.drawCircle(l + ts * 0.32f, t + ts * 0.38f, ts * 0.11f, fill)
                canvas.drawCircle(l + ts * 0.68f, t + ts * 0.38f, ts * 0.11f, fill)
                fill.color = 0xFFFF7043.toInt()
                canvas.drawRect(l + ts * 0.25f, t + ts * 0.62f, l + ts * 0.75f, t + ts * 0.8f, fill)
            }
        }
    }

    private fun drawField(canvas: Canvas, tile: Tile?, l: Float, t: Float, ts: Float) {
        fill.color = 0xFF6B4F33.toInt()
        canvas.drawRect(l + ts * 0.04f, t + ts * 0.04f, l + ts * 0.96f, t + ts * 0.96f, fill)
        val growth = tile?.growth ?: 0f
        val ripe = growth >= 100f
        val crop = tile?.crop ?: Crop.POTATO
        fill.color = when (crop) {
            Crop.POTATO -> if (ripe) 0xFFC9B458.toInt() else 0xFF7CB342.toInt()
            Crop.CORN -> if (ripe) 0xFFF2C94C.toInt() else 0xFF8BC34A.toInt()
            Crop.HEALROOT -> if (ripe) 0xFF4DD0C4.toInt() else 0xFF2E8B57.toInt()
        }
        val maxH = if (crop == Crop.CORN) 0.82f else 0.6f
        val h = ts * maxH * (0.15f + 0.85f * growth / 100f)
        for (i in 0..2) {
            val cx = l + ts * (0.22f + i * 0.28f)
            canvas.drawRect(cx - ts * 0.05f, t + ts * 0.9f - h, cx + ts * 0.05f, t + ts * 0.9f, fill)
            if (ripe && crop == Crop.HEALROOT) {
                fill.color = Color.WHITE
                canvas.drawCircle(cx, t + ts * 0.9f - h, ts * 0.06f, fill)
                fill.color = 0xFF4DD0C4.toInt()
            }
        }
    }

    private fun designationColor(d: JobType): Int = when (d) {
        JobType.CHOP, JobType.MINE -> 0xFFFFA726.toInt()
        JobType.PLANT_FIELD -> 0xFF9CCC65.toInt()
        JobType.BUILD -> 0xFF4FC3F7.toInt()
        JobType.DEMOLISH -> 0xFFEF5350.toInt()
        else -> Color.WHITE
    }

    private fun pawnCenter(p: Pawn, frac: Float, ts: Float): Pair<Float, Float> {
        val ix = p.prevX + (p.x - p.prevX) * frac
        val iy = p.prevY + (p.y - p.prevY) * frac
        return (offsetX + (ix + 0.5f) * ts) to (offsetY + (iy + 0.5f) * ts)
    }

    private fun drawPawn(canvas: Canvas, p: Pawn, frac: Float, ts: Float) {
        // Teleports (e.g. loading a save) shouldn't animate across the map.
        val f = if (abs(p.x - p.prevX) + abs(p.y - p.prevY) > 1) 1f else frac
        val (cx, cy) = pawnCenter(p, f, ts)
        val r = ts * 0.34f

        fill.color = 0x55000000
        canvas.drawCircle(cx, cy + r * 0.25f, r, fill)
        fill.color = p.color
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = if (p.inMentalBreak) 0xFFD32F2F.toInt() else 0xFF222222.toInt()
        stroke.strokeWidth = 1.5f * density
        canvas.drawCircle(cx, cy, r, stroke)

        text.color = 0xFF1B1B1B.toInt()
        text.textSize = r * 1.1f
        canvas.drawText(p.name.take(1), cx, cy + text.textSize * 0.36f, text)

        // Need bars: hunger, rest, mood.
        val bw = ts * 0.7f
        val bh = ts * 0.06f
        val bx = cx - bw / 2
        var by = cy + r + ts * 0.04f
        for ((value, color) in listOf(
            p.hunger to 0xFFFF9800.toInt(),
            p.rest to 0xFF42A5F5.toInt(),
            p.mood to 0xFF66BB6A.toInt(),
        )) {
            fill.color = 0xAA000000.toInt()
            canvas.drawRect(bx, by, bx + bw, by + bh, fill)
            fill.color = color
            canvas.drawRect(bx, by, bx + bw * (value / 100f).coerceIn(0f, 1f), by + bh, fill)
            by += bh * 1.15f
        }

        val badge = when {
            p.inMentalBreak -> "!"
            p.sleeping -> "z"
            p.sickTicks > 0 -> "+"
            else -> null
        }
        if (badge != null) {
            text.textSize = ts * 0.32f
            text.color = when (badge) {
                "!" -> 0xFFFF5252.toInt()
                "+" -> 0xFF76FF03.toInt()
                else -> Color.WHITE
            }
            canvas.drawText(badge, cx + r, cy - r * 0.6f, text)
        }
    }

    companion object {
        const val TICK_MS = 200f
    }
}
