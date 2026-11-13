package com.eppletv.launcher.home

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
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
import android.widget.TextView
import android.widget.Toast
import com.eppletv.launcher.BuildConfig
import com.eppletv.launcher.R
import com.eppletv.launcher.apps.AppEntry
import com.eppletv.launcher.apps.AppRepository
import com.eppletv.launcher.apps.Library
import com.eppletv.launcher.content.ContentRepository
import com.eppletv.launcher.content.Sources
import com.eppletv.launcher.motion.FrameClock
import com.eppletv.launcher.motion.Motion
import com.eppletv.launcher.motion.Spring
import com.eppletv.launcher.ui.Colors
import com.eppletv.launcher.ui.Pill
import com.eppletv.launcher.ui.Txt
import com.eppletv.launcher.ui.Type
import com.eppletv.launcher.ui.style
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The launcher's single screen: cinematic artwork, the top row of pinned apps, the app grid and overlay panels.
 * All D-pad movement is routed here, so focus is deterministic and always restorable.
 * Nothing animates at rest except a slow artwork change every few seconds while the top row is in view.
 */
class HomeActivity : Activity(), PageHost {
    override var dirX = 0; private set
    override var dirY = 0; private set

    /** CONTENT = a tile has focus. HERO = full-screen artwork, left/right browse it. PILL = the clock pill. */
    private enum class Zone { CONTENT, HERO, PILL }

    private val clock = FrameClock()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var l: Layout
    private lateinit var library: Library
    private lateinit var repo: AppRepository
    private lateinit var content: ContentRepository
    private lateinit var hero: Hero
    private lateinit var dim: View
    private lateinit var page: HomePage
    private lateinit var pill: Pill
    private lateinit var panel: MenuPanel
    private lateinit var empty: TextView
    private lateinit var anchor: View

    private var apps: List<AppEntry> = emptyList()
    private var zone = Zone.CONTENT
    private var backHandled = false
    private var timeText = ""
    private var resumed = false

    private val dimP = Spring(0f, Motion.SCROLL_DAMPING, Motion.SCROLL_STIFFNESS)
    private val dimTick = object : FrameClock.Tick {
        override fun tick(dt: Float): Boolean { dimP.step(dt); dim.alpha = dimP.value; pill.alpha = (1f - dimP.value * 2f).coerceIn(0f, 1f); return !dimP.atRest() }
    }

    // The artwork follows focus after a short pause, so fast navigation doesn't flicker the whole screen.
    private var pending: Tile? = null
    private val applyPending = Runnable {
        val t = pending ?: return@Runnable
        val e = t.entry
        if (!t.inDock) { hero.field(e); return@Runnable }
        var answered = false
        content.items(e.packageName) { items -> answered = true; if (pending === t) hero.bind(e, items) }
        if (!answered) hero.bind(e, emptyList())     // the app's own colours while its content loads
    }

    // Slow rotation through the focused app's content, only while the artwork is on screen.
    private val rotate = object : Runnable {
        override fun run() {
            if (resumed && !panel.open && (zone == Zone.HERO || zone == Zone.CONTENT && page.atTop())) hero.step(1, wrap = true)
            main.postDelayed(this, ROTATE_MS)
        }
    }
    private fun restartRotation() { main.removeCallbacks(rotate); if (resumed) main.postDelayed(rotate, ROTATE_MS) }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateTime()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Type.init(this)
        val dm = resources.displayMetrics
        l = Layout.forWindow(dm.widthPixels, dm.heightPixels)
        library = Library(this)
        content = ContentRepository(this, Sources.all(if (BuildConfig.DEBUG) intent.getStringExtra("cinemetaFor") else null))

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); clipChildren = false }
        hero = Hero(this, l, clock, content, root)
        dim = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        root.addView(dim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        anchor = View(this).apply { isFocusable = true }          // holds focus in full-screen artwork mode
        root.addView(anchor, FrameLayout.LayoutParams(1, 1))

        page = HomePage(this, l, clock, this, makeShadow(l))
        root.addView(page.view, FrameLayout.LayoutParams(MATCH_PARENT, l.height(1)))

        pill = Pill(this, "", l.pxf(Txt.META.px), l.pxf(30f), l.px(28), clock, Colors.GLASS, Colors.WHITE, 0.06f)
        pill.setOnClickListener { openControlCenter() }
        root.addView(pill, FrameLayout.LayoutParams(WRAP_CONTENT, l.px(60), Gravity.TOP or Gravity.END).apply { topMargin = l.px(56); rightMargin = l.px(64) })

        empty = TextView(this).apply { setText(R.string.no_apps); style(Txt.ROW, l.u, Colors.TEXT_DIM); visibility = View.GONE }
        root.addView(empty, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))

        panel = MenuPanel(this, l, clock) { restoreFocus() }
        root.addView(panel.dim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(panel.panel, panel.params())

        setContentView(root)
        val repeat = if (BuildConfig.DEBUG) intent.getIntExtra("repeat", 1) else 1
        // Artwork is rendered 20 % larger than the resting tile so the focused, scaled-up tile stays sharp.
        repo = AppRepository(this, l.px(300), l.px(169), { onApps(it) }, repeat)
        repo.start()
    }

    private fun onApps(list: List<AppEntry>) {
        apps = list
        refresh(null)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Rebuilds the page from the library; optionally moves focus to [focusPkg]. */
    private fun refresh(focusPkg: String?) {
        val pinned = library.pinned(apps)
        page.setApps(pinned, library.rest(apps))
        content.warm(pinned.map { it.packageName })
        if (focusPkg != null && page.locate(focusPkg)) zone = Zone.CONTENT
        restoreFocus()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        updateTime()
        resumed = true; restartRotation()
    }

    override fun onStop() {
        resumed = false; main.removeCallbacks(rotate)
        unregisterReceiver(timeReceiver)
        super.onStop()
    }

    override fun onDestroy() { main.removeCallbacks(applyPending); repo.stop(); content.stop(); super.onDestroy() }

    private fun updateTime() {
        timeText = DateFormat.getTimeFormat(this).format(Date())
        pill.text = timeText; pill.contentDescription = getString(R.string.cc_label, timeText)
    }

    // ---- focus ----

    private fun restoreFocus() {
        if (panel.open) return
        when (zone) {
            Zone.CONTENT -> if (!page.focusCurrent()) { zone = Zone.PILL; pill.requestFocus() }
            Zone.HERO -> anchor.requestFocus()
            Zone.PILL -> pill.requestFocus()
        }
    }

    private fun setShelf(on: Boolean) { page.shelf(on); hero.shelf(on) }
    private fun enterHero() { zone = Zone.HERO; anchor.requestFocus(); setShelf(true) }
    private fun enterContent() { setShelf(false); zone = Zone.CONTENT; if (!page.focusCurrent()) { zone = Zone.PILL; pill.requestFocus() } }
    private fun enterPill() { zone = Zone.PILL; pill.requestFocus() }

    override fun onPageDepth(deep: Boolean) { dimP.target = if (deep) 0.5f else 0f; clock.add(dimTick) }

    // ---- tiles ----

    override fun onTileFocused(tile: Tile) {
        val first = pending == null
        pending = tile
        main.removeCallbacks(applyPending)
        if (first) applyPending.run() else main.postDelayed(applyPending, 140)
    }

    override fun onTileClicked(tile: Tile) { launch(tile.entry, tile) }

    override fun onTileLongPressed(tile: Tile) {
        val e = tile.entry; val pkg = e.packageName
        val header = listOf<View>(TextView(this).apply { text = e.label; style(Txt.TITLE, l.u) })
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add(getString(R.string.menu_open) to { launch(e, null); Unit })
        if (library.isPinned(apps, pkg)) {
            if (library.canMove(apps, pkg, -1)) items.add(getString(R.string.menu_left) to { library.move(apps, pkg, -1); refresh(pkg) })
            if (library.canMove(apps, pkg, 1)) items.add(getString(R.string.menu_right) to { library.move(apps, pkg, 1); refresh(pkg) })
            items.add(getString(R.string.menu_unpin) to { library.remove(apps, pkg); refresh(null) })
        } else {
            items.add(getString(R.string.menu_pin) to { library.add(apps, pkg); refresh(null) })
        }
        items.add(getString(R.string.menu_info) to {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)), e.label); Unit
        })
        panel.show(header, items, atCorner = false)
    }

    private fun launch(e: AppEntry, from: View?): Boolean {
        val opts = from?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
        return open(e.launchIntent, e.label, opts)
    }

    /** OK on full-screen artwork: open that title in its app when the app supports it, otherwise open the app. */
    private fun openHeroItem() {
        val e = pending?.entry ?: return
        val link = hero.item?.intent()
        if (link != null) {
            try { startActivity(link); return } catch (_: ActivityNotFoundException) {} catch (_: SecurityException) {}
        }
        launch(e, null)
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
            TextView(this).apply { text = timeText; style(Txt.CLOCK, l.u) },
            TextView(this).apply { text = date; style(Txt.META, l.u, Colors.TEXT_DIM); setPadding(0, l.px(10), 0, 0) },
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
        val footer = if (!Sources.usesTmdb) null else TextView(this).apply {
            setText(R.string.tmdb_credit); style(Txt.FOOT, l.u, Colors.TEXT_DIM); setPadding(l.px(6), l.px(22), l.px(6), 0)
        }
        if (items.isNotEmpty()) panel.show(header, items, atCorner = true, footer = footer)
    }

    // ---- keys ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val dx = when (k) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
        val dy = when (k) { KeyEvent.KEYCODE_DPAD_UP -> -1; KeyEvent.KEYCODE_DPAD_DOWN -> 1; else -> 0 }
        val dpad = dx != 0 || dy != 0
        val select = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (down) restartRotation()

        if (panel.open) {
            if (select) return super.dispatchKeyEvent(event)
            if (k == KeyEvent.KEYCODE_BACK && down) backHandled = true
            return if (dpad || k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU) panel.key(event) else super.dispatchKeyEvent(event)
        }
        if (select && zone == Zone.HERO) {
            if (!down && !event.isCanceled) openHeroItem()
            return true
        }
        if (k == KeyEvent.KEYCODE_MENU) {
            if (down && event.repeatCount == 0) openControlCenter()
            return true
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            if (down && event.repeatCount == 0) backHandled = handleBack()
            return if (backHandled) true else super.dispatchKeyEvent(event)
        }
        if (!dpad) return super.dispatchKeyEvent(event)
        if (!down) return true
        dirX = dx; dirY = dy
        when (zone) {
            Zone.CONTENT -> if (!page.move(dx, dy)) { if (hero.hasItems) enterHero() else enterPill() }
            Zone.HERO -> if (dx != 0) hero.step(dx) else if (dy < 0) enterPill() else enterContent()
            Zone.PILL -> if (dy > 0) { if (hero.hasItems) enterHero() else enterContent() }
        }
        return true
    }

    /** BACK walks up: grid -> top row; full-screen artwork or the clock -> top row. At the top row it leaves the app. */
    private fun handleBack(): Boolean = when (zone) {
        Zone.CONTENT -> if (!page.atTop()) { dirX = 0; dirY = -1; page.toTop(); true } else false
        else -> { enterContent(); true }
    }

    private companion object { const val ROTATE_MS = 9000L }
}
