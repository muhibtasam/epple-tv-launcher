package com.eppletv.launcher.home

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.eppletv.launcher.R
import com.eppletv.launcher.apps.AppEntry
import com.eppletv.launcher.apps.AppRepository
import com.eppletv.launcher.motion.FrameClock
import com.eppletv.launcher.motion.Motion
import com.eppletv.launcher.motion.Spring
import java.util.Date

/**
 * Home screen: cinematic backdrop, top shelf with the focused app's title and the clock, and the app grid.
 * Idle cost is zero: no animation is running and the clock redraws once a minute.
 */
class HomeActivity : Activity(), TileHost {
    override var dirX = 0; private set
    override var dirY = 0; private set

    private val clock = FrameClock()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var l: Layout
    private lateinit var grid: AppGrid
    private lateinit var backdrop: Backdrop
    private lateinit var hero: HeroTitle
    private lateinit var dim: View
    private lateinit var time: TextView
    private lateinit var empty: TextView
    private lateinit var repo: AppRepository
    private var backConsumed = false

    // Shelf: 1 while focus is in the first row (title visible, backdrop bright), 0 deeper in the grid.
    private val shelf = Spring(1f, Motion.SCROLL_DAMPING, Motion.SCROLL_STIFFNESS)
    private val shelfTick = object : FrameClock.Tick {
        override fun tick(dt: Float): Boolean {
            shelf.step(dt)
            hero.view.alpha = shelf.value
            hero.view.translationY = (1f - shelf.value) * -l.pxf(40f)
            dim.alpha = 0.10f + 0.40f * (1f - shelf.value)
            return !shelf.atRest()
        }
    }

    // Backdrop and title follow focus after a short pause, so fast navigation doesn't flicker the whole screen.
    private var pending: AppEntry? = null
    private val applyPending = Runnable { pending?.let { backdrop.show(it.backdrop); hero.show(it.label) } }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateTime()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dm = resources.displayMetrics
        l = Layout.forWindow(dm.widthPixels, dm.heightPixels)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); clipChildren = false }
        backdrop = Backdrop(this, clock)
        root.addView(backdrop.bottom, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(backdrop.top, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Vertical scrim: keeps text readable and lets the backdrop glow at the top like a top shelf.
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x33000000, 0x8C000000.toInt(), 0xE0000000.toInt()))
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        dim = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0.10f }
        root.addView(dim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        hero = HeroTitle(this, l, clock)
        root.addView(hero.view, FrameLayout.LayoutParams(l.px(1200), l.px(120)).apply {
            leftMargin = l.left; topMargin = l.top - l.px(150)
        })

        grid = AppGrid(this, l, clock, this)
        root.addView(grid, AppGrid.params(l))

        time = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX, l.pxf(34f))
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setShadowLayer(l.pxf(10f), 0f, l.pxf(2f), 0x88000000.toInt())
        }
        root.addView(time, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = l.px(48); rightMargin = l.px(80)
        })

        empty = TextView(this).apply {
            setText(R.string.no_apps); setTextColor(0xB3FFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_PX, l.pxf(34f))
            visibility = View.GONE
        }
        root.addView(empty, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        setContentView(root)
        repo = AppRepository(this, l.tileW, l.tileH) { apps -> onApps(apps) }
        repo.start()
    }

    private fun onApps(apps: List<AppEntry>) {
        grid.setApps(apps)
        empty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        if (apps.isEmpty()) hero.show("")
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        updateTime()
    }

    override fun onStop() {
        unregisterReceiver(timeReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacks(applyPending)
        repo.stop()
        super.onDestroy()
    }

    private fun updateTime() { time.text = DateFormat.getTimeFormat(this).format(Date()) }

    // ---- TileHost ----

    override fun onTileFocused(tile: Tile) {
        grid.onFocused(tile)
        shelf.target = if (tile.index < Layout.COLS) 1f else 0f
        clock.add(shelfTick)
        val first = pending == null
        pending = tile.entry
        main.removeCallbacks(applyPending)
        if (first) applyPending.run() else main.postDelayed(applyPending, 120)
    }

    override fun onTileClicked(tile: Tile) {
        try {
            val opts = ActivityOptions.makeScaleUpAnimation(tile, 0, 0, tile.width, tile.height)
            startActivity(tile.entry.launchIntent, opts.toBundle())
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.cannot_open, tile.entry.label), Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.cannot_open, tile.entry.label), Toast.LENGTH_SHORT).show()
        }
    }

    // ---- keys ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // BACK deep in the grid returns to the first row; at the top it behaves normally.
            val t = currentFocus as? Tile
            if (event.action == KeyEvent.ACTION_DOWN) {
                backConsumed = t != null && t.index >= Layout.COLS
                if (backConsumed) { dirX = 0; dirY = -1; grid.focusFirstRow(t!!.index % Layout.COLS); return true }
            } else if (event.action == KeyEvent.ACTION_UP && backConsumed) {
                backConsumed = false; return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { dirX = -1; dirY = 0 }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { dirX = 1; dirY = 0 }
            KeyEvent.KEYCODE_DPAD_UP -> { dirX = 0; dirY = -1 }
            KeyEvent.KEYCODE_DPAD_DOWN -> { dirX = 0; dirY = 1 }
        }
        return super.dispatchKeyEvent(event)
    }
}
