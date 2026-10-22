package com.colonysim.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.colonysim.core.Colony
import com.colonysim.core.Feature
import com.colonysim.core.LogKind
import com.colonysim.core.MapGen
import com.colonysim.core.Pawn
import com.colonysim.core.TICKS_PER_DAY
import com.colonysim.core.Tool
import java.io.File
import java.util.Locale

class MainActivity : Activity(), ColonyView.Listener {

    private lateinit var colony: Colony
    private lateinit var colonyView: ColonyView
    private lateinit var hud: TextView
    private lateinit var info: TextView
    private lateinit var pauseButton: Button
    private lateinit var speedButton: Button
    private lateinit var expandButton: Button
    private val toolButtons = mutableMapOf<Tool, Button>()
    private var gameOverShown = false

    private val saveFile: File get() = File(filesDir, "colony.json")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        colony = load() ?: MapGen.newColony()
        setContentView(buildLayout())
        colonyView.colony = colony
        selectTool(Tool.INSPECT)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    // ------------------------------------------------------------ persistence

    private fun load(): Colony? = try {
        if (saveFile.exists()) Colony.fromJson(saveFile.readText()) else null
    } catch (e: Exception) {
        Log.w(TAG, "Could not load save, starting fresh", e)
        null
    }

    private fun save() {
        try {
            val tmp = File(filesDir, "colony.json.tmp")
            tmp.writeText(colony.toJson())
            tmp.renameTo(saveFile)
        } catch (e: Exception) {
            Log.w(TAG, "Could not save", e)
        }
    }

    private fun newColony() {
        colony = MapGen.newColony()
        gameOverShown = false
        colonyView.colony = colony
        colonyView.paused = false
        save()
        refresh()
    }

    // ------------------------------------------------------------ layout

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        hud = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(PANEL)
        }
        root.addView(hud, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        colonyView = ColonyView(this).also { it.listener = this }
        root.addView(colonyView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        info = TextView(this).apply {
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 12.5f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(PANEL)
            minLines = 6
            maxLines = 6
            setOnClickListener { showLog() }
        }
        root.addView(info, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (t in Tool.entries) {
            val b = smallButton(t.label) { selectTool(t) }
            toolButtons[t] = b
            tools.addView(b)
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tools)
        })

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pauseButton = smallButton("Pause") {
            colonyView.paused = !colonyView.paused
            refresh()
        }
        speedButton = smallButton("1x") {
            colonyView.speed = when (colonyView.speed) {
                1 -> 2
                2 -> 4
                4 -> 8
                else -> 1
            }
            refresh()
        }
        expandButton = smallButton("Expand") { tryExpand() }
        controls.addView(pauseButton)
        controls.addView(speedButton)
        controls.addView(expandButton)
        controls.addView(smallButton("Log") { showLog() })
        controls.addView(smallButton("Help") { showHelp() })
        controls.addView(smallButton("New") { confirmNewColony() })
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(controls)
        })

        // Android 15 draws apps edge-to-edge; keep our UI clear of the system bars.
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        return root
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(Color.WHITE)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = buttonBackground(false)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        setOnClickListener { onClick() }
    }

    private fun buttonBackground(active: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(if (active) 0xFF3D6E9E.toInt() else 0xFF333A44.toInt())
    }

    private fun selectTool(t: Tool) {
        colonyView.tool = t
        if (t != Tool.INSPECT) colonyView.clearSelection()
        toolButtons.forEach { (tool, b) -> b.background = buttonBackground(tool == t) }
        if (t != Tool.INSPECT) {
            Toast.makeText(this, "${t.label}: drag to paint, two fingers to pan", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    // ------------------------------------------------------------ actions

    private fun tryExpand() {
        val (w, s) = colony.expandCost()
        if (!colony.expand()) {
            Toast.makeText(this, "Expanding needs $w wood and $s stone", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    private fun confirmNewColony() {
        AlertDialog.Builder(this)
            .setTitle("Start a new colony?")
            .setMessage("Your current colony will be lost.")
            .setPositiveButton("New colony") { _, _ -> newColony() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLog() {
        val sb = SpannableStringBuilder()
        for (e in colony.log.asReversed()) appendLogLine(sb, "Day ${e.tick / TICKS_PER_DAY + 1}: ${e.text}", e.kind)
        val tv = TextView(this).apply {
            text = sb
            setPadding(dp(16), dp(8), dp(16), dp(8))
            textSize = 13f
        }
        AlertDialog.Builder(this)
            .setTitle("Colony log")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("How to play")
            .setMessage(
                """
                Your colonists look after themselves: they eat, sleep and work on whatever you order.

                • Chop/Mine — mark trees and rocks for wood and stone.
                • Farm — plant fields. Colonists harvest ripe crops automatically. Nothing grows in winter, and stored food slowly spoils.
                • Bed — costs ${Colony.BED_WOOD} wood. Sleeping on the ground makes colonists unhappy.
                • Wall — costs ${Colony.WALL_STONE} stone.
                • Demolish / Cancel — remove buildings or orders.
                • Expand — claim more land.

                Spare beds and a happy colony attract wanderers. Unhappy colonists can have mental breaks; starving ones die.

                Tap a colonist to see their needs. Bars: orange = food, blue = rest, green = mood. Drag to pan, pinch to zoom. Tap the info panel for the full log.
                """.trimIndent()
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    // ------------------------------------------------------------ HUD

    override fun onTicked() {
        refresh()
        if (colony.isGameOver && !gameOverShown) {
            gameOverShown = true
            AlertDialog.Builder(this)
                .setTitle("The colony has fallen")
                .setMessage(
                    "Survived ${colony.day} days.\n" +
                        "Peak population: ${colony.stats.peakPopulation}\n" +
                        "Colonists lost: ${colony.stats.colonistsLost}\n" +
                        "Food harvested: ${colony.stats.foodHarvested}"
                )
                .setPositiveButton("New colony") { _, _ -> newColony() }
                .setCancelable(false)
                .show()
        }
    }

    override fun onSelectionChanged() = refresh()

    private fun refresh() {
        val c = colony
        val minute = ((c.tick % TICKS_PER_DAY) * 24 * 60 / TICKS_PER_DAY) % 60
        hud.text = String.format(
            Locale.US,
            "Day %d  %02d:%02d  %s Y%d\nFood %d  Wood %d  Stone %d  Pop %d  Beds %d",
            c.day, c.hour, minute, c.season.label, c.year,
            c.food, c.wood, c.stone, c.pawns.size, c.count(Feature.BED),
        )
        pauseButton.text = if (colonyView.paused) "Play" else "Pause"
        pauseButton.background = buttonBackground(colonyView.paused)
        speedButton.text = "${colonyView.speed}x"
        val (w, s) = c.expandCost()
        expandButton.text = "Expand (${w}w ${s}s)"
        info.text = infoText()
    }

    private fun infoText(): CharSequence {
        val c = colony
        val pawn = c.pawns.firstOrNull { it.id == colonyView.selectedPawnId }
        if (pawn != null) return pawnInfo(pawn)
        val pos = colonyView.selectedTile
        if (pos != null) {
            val t = c.tile(pos.x, pos.y)
            val sb = StringBuilder()
            sb.append("Tile ${pos.x},${pos.y}: ${t.terrain.name.lowercase()}")
            if (t.feature != Feature.NONE) sb.append(", ${t.feature.name.lowercase().replace('_', ' ')}")
            if (t.feature == Feature.FIELD || t.feature == Feature.BERRY_BUSH) sb.append(" (${t.growth.toInt()}% grown)")
            t.designation?.let { sb.append("\nOrder: ${it.label}") }
            c.pawnAt(pos.x, pos.y)?.let { sb.append("\n${it.name} is here") }
            return sb
        }
        val sb = SpannableStringBuilder()
        if (colonyView.tool != Tool.INSPECT) {
            sb.append("Tool: ${colonyView.tool.label} — drag on the map to paint orders.\n")
        }
        for (e in c.log.takeLast(5).asReversed()) appendLogLine(sb, e.text, e.kind)
        return sb
    }

    private fun pawnInfo(p: Pawn): CharSequence {
        val sb = SpannableStringBuilder()
        sb.append("${p.name}, ${p.age} — ${p.trait.label} (${p.trait.description})\n")
        sb.append("${p.status()}\n")
        sb.append("Food ${p.hunger.toInt()}  Rest ${p.rest.toInt()}  Mood ${p.mood.toInt()}  Health ${p.health.toInt()}\n")
        val mods = p.moodModifiers()
        if (mods.isEmpty()) {
            sb.append("Feeling neutral.")
        } else {
            mods.forEachIndexed { i, (label, value) ->
                val start = sb.length
                sb.append(if (value >= 0) "+$value $label" else "$value $label")
                sb.setSpan(ForegroundColorSpan(if (value >= 0) GOOD else BAD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (i < mods.size - 1) sb.append(",  ")
            }
        }
        return sb
    }

    private fun appendLogLine(sb: SpannableStringBuilder, line: String, kind: LogKind) {
        val start = sb.length
        sb.append(line).append('\n')
        val color = when (kind) {
            LogKind.GOOD -> GOOD
            LogKind.BAD -> BAD
            LogKind.INFO -> 0xFFB0BEC5.toInt()
        }
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    companion object {
        private const val TAG = "ColonySim"
        private const val BG = 0xFF1B1F24.toInt()
        private const val PANEL = 0xFF242A31.toInt()
        private const val GOOD = 0xFF81C784.toInt()
        private const val BAD = 0xFFE57373.toInt()
    }
}
