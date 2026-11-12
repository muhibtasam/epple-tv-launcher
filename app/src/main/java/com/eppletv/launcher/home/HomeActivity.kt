package com.eppletv.launcher.home

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.eppletv.launcher.BuildConfig
import com.eppletv.launcher.R
import com.eppletv.launcher.apps.AppEntry
import com.eppletv.launcher.apps.AppRepository
import com.eppletv.launcher.apps.Library
import com.eppletv.launcher.motion.FrameClock
import com.eppletv.launcher.motion.Motion
import com.eppletv.launcher.motion.Spring
import com.eppletv.launcher.ui.Colors
import com.eppletv.launcher.ui.Type
import com.eppletv.launcher.ui.textStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The launcher's single screen: backdrop, top bar, two tabs (Home, Apps) and overlay panels.
 * All D-pad movement is routed here, so focus is deterministic and always restorable.
 * Idle cost is zero: no animation runs and the clock text changes once a minute.
 */
class HomeActivity : Activity(), PageHost {
    override var dirX = 0; private set
    override var dirY = 0; private set

    private enum class Zone { BAR, CONTENT }

    private val clock = FrameClock()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var l: Layout
    private lateinit var library: Library
    private lateinit var repo: AppRepository
    private lateinit var backdrop: Backdrop
    private lateinit var dim: View
    private lateinit var home: HomePage
    private lateinit var allApps: AppsPage
    private lateinit var bar: TopBar
    private lateinit var panel: MenuPanel
    private lateinit var empty: TextView
    private val pages get() = arrayOf<Page>(home, allApps)
    private val active: Page get() = if (bar.tab == 0) home else allApps

    private var apps: List<AppEntry> = emptyList()
    private var zone = Zone.CONTENT
    private var deep = false
    private var backHandled = false
    private var sectionsDirty = false
    private var started = false
    private var timeText = ""

    // Tab cross-fade and backdrop dimming.
    private val tabP = Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 3f)
    private val dimP = Spring(0f, Motion.SCROLL_DAMPING, Motion.SCROLL_STIFFNESS)
    private val chromeTick = object : FrameClock.Tick {
        override fun tick(dt: Float): Boolean {
            tabP.step(dt); dimP.step(dt)
            val p = tabP.value.coerceIn(0f, 1f)
            home.view.alpha = 1f - p; allApps.view.alpha = p
            home.view.visibility = if (p > 0.999f) View.INVISIBLE else View.VISIBLE
            allApps.view.visibility = if (p < 0.001f) View.INVISIBLE else View.VISIBLE
            dim.alpha = dimP.value
            return !(tabP.atRest() && dimP.atRest())
        }
    }

    // Backdrop and hero follow focus after a short pause, so fast navigation doesn't flicker the whole screen.
    private var pending: AppEntry? = null
    private val applyPending = Runnable {
        pending?.let { backdrop.show(it.backdrop); home.hero.show(library.categoryLabel(it.category), it.label) }
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateTime()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Type.init(this)
        val dm = resources.displayMetrics
        l = Layout.forWindow(dm.widthPixels, dm.heightPixels)
        library = Library(this)
        val shadow = makeShadow(l)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); clipChildren = false }
        backdrop = Backdrop(this, clock)
        root.addView(backdrop.bottom, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(backdrop.top, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Scrim: the artwork glows at the top like a top shelf and falls to near-black behind the rows.
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x1F000000, 0x80000000.toInt(), 0xEB000000.toInt()))
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        dim = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        root.addView(dim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        home = HomePage(this, l, clock, this, shadow)
        root.addView(home.view, FrameLayout.LayoutParams(MATCH_PARENT, l.homeHeight(1)))
        allApps = AppsPage(this, l, clock, this, shadow, getString(R.string.tab_apps))
        allApps.view.alpha = 0f; allApps.view.visibility = View.INVISIBLE
        root.addView(allApps.view, FrameLayout.LayoutParams(MATCH_PARENT, l.gridHeight(1)))

        bar = TopBar(this, l, clock, listOf(getString(R.string.tab_home), getString(R.string.tab_apps)),
            onTab = { onTabChanged() }, onConfirm = { enterContent() }, onStatus = { openControlCenter() })
        root.addView(bar.view, FrameLayout.LayoutParams(MATCH_PARENT, l.px(68)).apply { topMargin = l.px(40) })

        empty = TextView(this).apply { setText(R.string.no_apps); textStyle(l.pxf(34f), 400, Colors.TEXT_DIM); visibility = View.GONE }
        root.addView(empty, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))

        panel = MenuPanel(this, l, clock) { restoreFocus() }
        root.addView(panel.dim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(panel.panel, panel.params())

        setContentView(root)
        val repeat = if (BuildConfig.DEBUG) intent.getIntExtra("repeat", 1) else 1
        repo = AppRepository(this, l.tileW, l.tileH, { onApps(it) }, repeat)
        repo.start()
    }

    private fun onApps(list: List<AppEntry>) {
        apps = list
        home.setSections(library.sections(list))
        allApps.setApps(list)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (!started && list.isNotEmpty()) { started = true; zone = Zone.CONTENT }   // first load: focus starts in the top row
        restoreFocus()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        updateTime()
        if (sectionsDirty) { sectionsDirty = false; home.setSections(library.sections(apps)); restoreFocus() }
    }

    override fun onStop() { unregisterReceiver(timeReceiver); super.onStop() }

    override fun onDestroy() { main.removeCallbacks(applyPending); repo.stop(); super.onDestroy() }

    private fun updateTime() {
        timeText = DateFormat.getTimeFormat(this).format(Date())
        bar.setTime(timeText, getString(R.string.cc_label, timeText))
    }

    // ---- focus ----

    private fun restoreFocus() {
        if (panel.open) return
        if (zone == Zone.CONTENT && active.focusCurrent()) return
        zone = Zone.BAR
        bar.show(true); bar.refocus()
    }

    private fun enterContent() { if (active.focusCurrent()) zone = Zone.CONTENT }

    private fun onTabChanged() {
        tabP.target = bar.tab.toFloat()
        home.view.visibility = View.VISIBLE; allApps.view.visibility = View.VISIBLE
        deep = false
        updateDim()
    }

    private fun updateDim() {
        dimP.target = if (bar.tab == 1) (if (deep) 0.6f else 0.5f) else (if (deep) 0.45f else 0f)
        clock.add(chromeTick)
    }

    override fun onPageDepth(page: Page, deep: Boolean) {
        if (page !== active) return
        this.deep = deep
        bar.show(!deep)
        updateDim()
    }

    // ---- tiles ----

    override fun onTileFocused(tile: Tile) {
        val first = pending == null
        pending = tile.entry
        main.removeCallbacks(applyPending)
        if (first) applyPending.run() else main.postDelayed(applyPending, 120)
    }

    override fun onTileClicked(tile: Tile) = launch(tile.entry, tile)

    override fun onTileLongPressed(tile: Tile) {
        val e = tile.entry
        val header = listOf<View>(
            TextView(this).apply { text = e.label; textStyle(l.pxf(36f), 600) },
            TextView(this).apply { text = library.categoryLabel(e.category); textStyle(l.pxf(24f), 400, Colors.TEXT_DIM); setPadding(0, l.px(8), 0, 0) },
        ).filter { (it as TextView).text.isNotEmpty() }
        val pinned = library.isPinned(apps, e.packageName)
        panel.show(header, listOf(
            getString(R.string.menu_open) to { launch(e, null) },
            getString(if (pinned) R.string.menu_unpin else R.string.menu_pin) to {
                library.togglePin(apps, e.packageName); home.setSections(library.sections(apps)); restoreFocus()
            },
            getString(R.string.menu_info) to {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", e.packageName, null)), e.label)
            },
        ), atCorner = false)
    }

    private fun launch(e: AppEntry, from: View?) {
        val opts = from?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
        if (open(e.launchIntent, e.label, opts)) { library.recordLaunch(e.packageName); sectionsDirty = true }
    }

    private fun open(intent: Intent, name: String, opts: Bundle? = null): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts); true
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, getString(R.string.cannot_open, name), Toast.LENGTH_SHORT).show(); false
    } catch (e: SecurityException) {
        Toast.makeText(this, getString(R.string.cannot_open, name), Toast.LENGTH_SHORT).show(); false
    }

    // ---- Control Center ----

    private fun openControlCenter() {
        val date = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
        val header = listOf<View>(
            TextView(this).apply { text = timeText; textStyle(l.pxf(72f), 300); letterSpacing = -0.02f },
            TextView(this).apply { text = date; textStyle(l.pxf(26f), 400, Colors.TEXT_DIM); setPadding(0, l.px(10), 0, 0) },
        )
        val items = listOf(
            R.string.cc_settings to Settings.ACTION_SETTINGS,
            R.string.cc_network to Settings.ACTION_WIFI_SETTINGS,
            R.string.cc_display to Settings.ACTION_DISPLAY_SETTINGS,
            R.string.cc_sound to Settings.ACTION_SOUND_SETTINGS,
            R.string.cc_accessories to Settings.ACTION_BLUETOOTH_SETTINGS,
            R.string.cc_screensaver to Settings.ACTION_DREAM_SETTINGS,
            R.string.cc_apps to Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS,
        ).filter { packageManager.resolveActivity(Intent(it.second), 0) != null }
            .map { (label, action) -> getString(label).let { name -> name to { open(Intent(action), name); Unit } } }
        if (items.isNotEmpty()) panel.show(header, items, atCorner = true)
    }

    // ---- keys ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val dx = when (k) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
        val dy = when (k) { KeyEvent.KEYCODE_DPAD_UP -> -1; KeyEvent.KEYCODE_DPAD_DOWN -> 1; else -> 0 }
        val dpad = dx != 0 || dy != 0
        if (!dpad && k != KeyEvent.KEYCODE_BACK && k != KeyEvent.KEYCODE_MENU) return super.dispatchKeyEvent(event)

        if (panel.open) {
            if (k == KeyEvent.KEYCODE_BACK && down) backHandled = true
            return panel.key(event)
        }
        if (k == KeyEvent.KEYCODE_MENU) {
            if (down && event.repeatCount == 0) openControlCenter()
            return true
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            if (down && event.repeatCount == 0) backHandled = handleBack()
            return if (backHandled) true else super.dispatchKeyEvent(event)
        }
        if (!down) return true
        dirX = dx; dirY = dy
        if (zone == Zone.BAR) {
            if (dx != 0) bar.move(dx) else if (dy > 0) enterContent()
        } else if (!active.move(dx, dy)) {
            zone = Zone.BAR
            bar.show(true); bar.enter()
        }
        return true
    }

    /** BACK walks up: deep in a page -> its top row -> the top bar -> the Home tab. At the very top it leaves the app. */
    private fun handleBack(): Boolean {
        if (zone == Zone.CONTENT) {
            if (!active.atTop()) { dirX = 0; dirY = -1; active.toTop(); return true }
            if (bar.tab != 0) { zone = Zone.BAR; bar.show(true); bar.enter(); return true }
            return false
        }
        if (bar.tab != 0) { bar.select(0); bar.enter(); return true }
        return false
    }
}
