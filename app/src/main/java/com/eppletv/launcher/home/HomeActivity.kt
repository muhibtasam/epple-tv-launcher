package com.eppletv.launcher.home

import com.eppletv.launcher.system.NotificationHub
import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
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
import com.eppletv.launcher.content.Catalog
import com.eppletv.launcher.content.ContentItem
import com.eppletv.launcher.content.ContentRepository
import com.eppletv.launcher.content.Sources
import com.eppletv.launcher.content.TitleLauncher
import com.eppletv.launcher.content.UserLibrary
import com.eppletv.launcher.motion.FrameClock
import com.eppletv.launcher.motion.Motion
import com.eppletv.launcher.motion.Spring
import com.eppletv.launcher.ui.EppleIcons
import com.eppletv.launcher.ui.Colors
import com.eppletv.launcher.ui.Pill
import com.eppletv.launcher.ui.Txt
import com.eppletv.launcher.ui.Type
import com.eppletv.launcher.ui.style
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The launcher's single activity and navigation shell. Home (cinematic artwork, the top row, and content rows below
 * it) plus six sections reached from the tab bar, a details page that opens over any of them, Control Center and the
 * screensaver. All D-pad movement is routed here, so focus is deterministic and restorable everywhere.
 * Nothing animates at rest except a slow artwork change while Home's top row is in view.
 */
class HomeActivity : Activity(), PageHost, Nav, SetupFlow.Host {
    override var dirX = 0; private set
    override var dirY = 0; private set

    /** CONTENT = a top-row tile. HERO = full-screen artwork. TABS = the tab bar (or the clock). FEED = Home's rows.
     *  SECTION = inside another tab. DETAIL = a details page. */
    private enum class Zone { CONTENT, HERO, TABS, FEED, SECTION, DETAIL }

    override val ctx: Context get() = this
    override val clock = FrameClock()
    private val main = Handler(Looper.getMainLooper())
    override lateinit var l: Layout; private set
    override lateinit var library: Library; private set
    override lateinit var content: ContentRepository; private set
    override lateinit var catalog: Catalog; private set
    override lateinit var user: UserLibrary; private set
    override lateinit var prefs: Prefs; private set
    override lateinit var wallpapers: Wallpapers; private set
    override lateinit var saverPictures: Wallpapers; private set
    override lateinit var profile: Profile; private set
    override lateinit var launcher: com.eppletv.launcher.system.LauncherMode; private set
    private var wallBmp: Bitmap? = null
    private var wallGen = 0
    private var startedOnce = false
    private lateinit var repo: AppRepository
    private lateinit var titles: TitleLauncher
    private lateinit var hero: Hero
    private lateinit var page: HomePage
    private lateinit var homeLayer: FrameLayout
    private lateinit var sectionLayer: FrameLayout
    private lateinit var tabs: SideBar
    private lateinit var pill: ClockPill
    private lateinit var panel: MenuPanel
    private lateinit var cc: ControlCenter
    private lateinit var tv: com.eppletv.launcher.system.TvControl
    private lateinit var empty: TextView
    private lateinit var anchor: View
    private lateinit var feed: HomeFeed
    private lateinit var saver: Screensaver
    private lateinit var shadowBmp: Bitmap
    private lateinit var setup: SetupFlow
    private lateinit var neutral: Bitmap

    override var apps: List<AppEntry> = emptyList(); private set
    private var zone = Zone.TABS                     // Epple TV opens on the Home tab, over a wallpaper
    private var section = 0
    private var tabIdx = 0                          // 0..5 menu entries (by section), 6 = the clock (PILL)
    private var menuOpen = false                    // TABS with the menu closed = its hint pill (Home's resting focus)
    private val screens = arrayOfNulls<Screen>(7)
    private val details = ArrayList<DetailScreen>()
    private var detailReturn = Zone.SECTION
    private var backHandled = false
    private var swallowUp = false
    private var timeText = ""
    private var resumed = false

    private val dimP = Spring(0f, Motion.SCROLL_DAMPING, Motion.SCROLL_STIFFNESS)
    private val tabsP = Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 2f)
    private val homeP = Spring(1f, Motion.SCROLL_DAMPING, Motion.SCROLL_STIFFNESS)      // the top row and its artwork text
    private val railP = Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 2f)     // the side menu's closed rail
    private val shell = object : FrameClock.Tick {
        override fun tick(dt: Float): Boolean {
            dimP.step(dt); tabsP.step(dt); homeP.step(dt); railP.step(dt)
            hero.backdrop.dim(dimP.value)
            val rail = railP.value                 // sections own the left edge: there the menu shows only when opened
            tabs.alpha = rail; tabs.translationX = -(1f - rail) * l.pxf(30f)
            tabs.visibility = if (rail > 0.01f || railP.target > 0f) View.VISIBLE else View.INVISIBLE
            val homeClock = if (section == 0 && zone != Zone.FEED && zone != Zone.DETAIL) (1f - dimP.value * 2f).coerceIn(0f, 1f) else 0f
            pill.alpha = if (::cc.isInitialized && cc.open) 1f else maxOf(tabsP.value, homeClock)
            homeLayer.alpha = homeP.value; homeLayer.translationY = -(1f - homeP.value) * l.pxf(260f)
            homeLayer.visibility = if (homeP.value > 0.01f) View.VISIBLE else View.INVISIBLE
            return !(dimP.atRest() && tabsP.atRest() && homeP.atRest() && railP.atRest())
        }
    }
    private fun animateShell() { syncSide(); syncChips(); clock.add(shell) }

    /** The menu opens while focus is in it; on Home's top level its closed rail stays in view, elsewhere it hides. */
    private fun syncSide() {
        if (!::tabs.isInitialized) return
        tabs.open = zone == Zone.TABS && menuOpen && tabIdx < TAB_NAMES.size
        tabs.hint = zone == Zone.TABS && !menuOpen && tabIdx < TAB_NAMES.size
        railP.target = if (zone == Zone.TABS || section != SETTINGS && zone != Zone.DETAIL && zone != Zone.FEED) 1f else 0f
        // A hidden view can't take focus: the menu is made visible at once when focus is going to it (it then fades in).
        if (railP.target > 0f && tabs.visibility != View.VISIBLE) { tabs.visibility = View.VISIBLE; tabs.alpha = maxOf(tabs.alpha, 0.01f) }
    }

    // The artwork follows focus after a short pause, so fast navigation doesn't flicker the whole screen.
    private var pending: Tile? = null
    private var focusedPkg: String? = null
    private var switched = false
    private val applyPending = Runnable {
        val t = pending ?: return@Runnable
        val e = t.entry
        if (!t.inDock) { hero.field(e); return@Runnable }
        val restart = switched; switched = false
        if (restart) restartRotation()
        // Only a streaming app with titles shows posters; any other app (and one still loading) keeps the wallpaper.
        var answered = false
        content.items(e.packageName) { items ->
            answered = true
            if (pending === t && section == 0 && zone != Zone.TABS) { if (items.isEmpty()) showWallpaper() else hero.bind(e, items, restart) }
        }
        if (!answered) showWallpaper()
    }

    private val rotate = object : Runnable {
        override fun run() {
            if (resumed && !panel.open && section == 0 && (zone == Zone.HERO || zone == Zone.CONTENT && page.atTop())) hero.step(1, wrap = true)
            if (prefs.rotateMs > 0) { main.postDelayed(this, prefs.rotateMs); hero.rotationTimer(prefs.rotateMs) }
        }
    }
    private fun restartRotation() {
        main.removeCallbacks(rotate)
        if (resumed && prefs.rotateMs > 0) { main.postDelayed(rotate, prefs.rotateMs); hero.rotationTimer(prefs.rotateMs) } else hero.rotationTimer(0)
    }

    private val idle = Runnable { if (resumed && !panel.open && !cc.open && !setup.active) saver.start() }
    private fun restartIdle() { main.removeCallbacks(idle); if (resumed && prefs.saverMs > 0) main.postDelayed(idle, prefs.saverMs) }

    private val timeReceiver = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = updateTime() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        GlassStyle.amount = prefs.glass / 100f
        wallpapers = Wallpapers(this)
        saverPictures = Wallpapers(this, "screensaver")
        profile = Profile(this)
        launcher = com.eppletv.launcher.system.LauncherMode(this)
        guardCrashes()
        Type.init(this); Type.scale = prefs.textScale; Type.boldDelta = if (prefs.boldText) 150 else 0
        Sources.override = prefs.regionCode
        Sources.indianLangs = prefs.langs
        com.eppletv.launcher.content.Credentials.init(this)
        val dm = resources.displayMetrics
        l = Layout.forWindow(dm.widthPixels, dm.heightPixels)
        library = Library(this)
        user = UserLibrary(this)
        content = ContentRepository(this, Sources.all(this, if (BuildConfig.DEBUG) intent.getStringExtra("cinemetaFor") else null)) { b -> Scrim.paint(b); Frost.prepare(b) }
        content.indianMode = prefs.get(Prefs.INDIAN)
        catalog = Catalog(content.net, { com.eppletv.launcher.content.Credentials.get(com.eppletv.launcher.content.Credentials.Key.TMDB) }) { Sources.region }
        titles = TitleLauncher(this, content.net, { com.eppletv.launcher.content.Credentials.get(com.eppletv.launcher.content.Credentials.Key.WATCHMODE) }) { Sources.region }
        // No link to the title itself: say so, instead of passing the app's home screen off as the title.
        titles.onAppOnly = { pkg, title -> toast("$title can't be opened directly in ${apps.firstOrNull { it.packageName == pkg }?.label ?: "the app"}: opened the app") }
        shadowBmp = makeShadow(l)
        neutral = neutralBackdrop()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); clipChildren = false }
        hero = Hero(this, l, clock, content, root)
        anchor = View(this).apply { isFocusable = true }
        root.addView(anchor, FrameLayout.LayoutParams(1, 1))

        homeLayer = FrameLayout(this).apply { clipChildren = false }
        page = HomePage(this, l, clock, this, shadowBmp, hero.backdrop)
        homeLayer.addView(page.view, FrameLayout.LayoutParams(MATCH_PARENT, l.height(1)))
        root.addView(homeLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        sectionLayer = FrameLayout(this).apply { clipChildren = false }
        root.addView(sectionLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        feed = HomeFeed(this)

        tabs = SideBar(this, l, clock, TAB_NAMES, TAB_ICONS, MENU_ORDER, hero.backdrop).apply { alpha = 0f; visibility = View.INVISIBLE }
        root.addView(tabs, FrameLayout.LayoutParams(tabs.w1, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { topMargin = l.px(48); leftMargin = l.px(64) })

        // Control Center sits under the clock badge (which stays bright above its dim) and under menus and setup.
        tv = com.eppletv.launcher.system.TvControl(this)
        NotificationHub.init(this)
        Watchers.startWifi(this, prefs)
        cc = ControlCenter(this, l, clock, hero.backdrop) { url, cb -> content.thumb(url, cb) }
        cc.onOpenNote = { n -> cc.hide(); openNote(n) }
        cc.onDismissNote = { n -> NotificationHub.dismiss(n.key) }
        cc.onClearAll = { NotificationHub.clearAll() }
        root.addView(cc.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        buildControlCenter()
        cc.onTop = { pill.part = 1; pill.requestFocus() }
        pill = ClockPill(this, l, clock, hero.backdrop)
        // OK: Control Center, or on the profile orb Epple TV's settings; holding OK on the orb opens the TV's settings.
        pill.setOnClickListener { if (pill.part == 1) goTab(SETTINGS) else if (cc.open) cc.hide() else openControlCenter() }
        pill.setOnLongClickListener { if (pill.part == 1) { open(Intent(Settings.ACTION_SETTINGS), "TV Settings"); true } else false }
        root.addView(pill, FrameLayout.LayoutParams(WRAP_CONTENT, l.px(70), Gravity.TOP or Gravity.END).apply { topMargin = l.px(49); rightMargin = l.px(64) })

        empty = TextView(this).apply { setText(R.string.no_apps); style(Txt.ROW, l.u, Colors.TEXT_DIM); visibility = View.GONE }
        root.addView(empty, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))

        banner = NotifBanner(this, l, clock, hero.backdrop)
        root.addView(banner, FrameLayout.LayoutParams(l.px(600), WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { topMargin = l.px(132); rightMargin = l.px(64) })
        NotificationHub.onChange = { n ->
            pill.dot = NotificationHub.unread > 0
            if (cc.open) cc.changed()
            if (n != null && resumed && !setup.active && !saver.showing) banner.show(n)
            if (n == null) (screens[SETTINGS] as? SettingsScreen)?.show()      // "Allowed" once the TV connects the reader
        }

        panel = MenuPanel(this, l, clock) { restoreFocus() }
        root.addView(panel.dim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(panel.panel, panel.params())

        saver = Screensaver(this).apply {
            clockText = { timeText }
            wifiName = { val cm = getSystemService(android.net.ConnectivityManager::class.java)
                if (cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true) (if (::ccWifi.isInitialized) ccWifi.sub.takeIf { it.isNotEmpty() && it != "Off" } else null) ?: "Wi-Fi" else null }
        }
        root.addView(saver.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        setup = SetupFlow(this, this)
        root.addView(setup.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (!setup.done) setup.start()                     // first launch, or an interrupted setup resumes
        setup.behind = listOf(anchor, homeLayer, sectionLayer, tabs, pill)

        setContentView(root)
        startLog("view tree built")
        root.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
            var done = false
            override fun onDraw() { if (done) return; done = true; main.post { startLog("first frame"); root.viewTreeObserver.removeOnDrawListener(this) } }
        })
        // Home tab selected, its wallpaper behind: posters appear only once a streaming app is focused.
        tabsP.target = 1f; animateShell()
        showWallpaper(); nextWallpaper()
        content.run({ profile.load() ?: Unit }) { profileChanged() }
        // Opened from the Epple TV icon in the TV's apps (not the Home button): its settings first, as a setup app would.
        if (fromAppIcon(intent) && setup.done) main.post { goTab(SETTINGS) }
        val repeat = if (BuildConfig.DEBUG) intent.getIntExtra("repeat", 1) else 1
        val artScale = l.px(300) / l.tileW.toFloat()     // art is rendered larger than the tile, so it stays sharp when focused
        repo = AppRepository(this, l.px(300), l.px(169), { onApps(it) }, repeat,
            decorate = { c, w, h -> paintTileSurface(c, w, h, l.corner * artScale, l.pxf(2f).coerceAtLeast(1f) * artScale) },
            backdropFilter = { b -> Scrim.paint(b) },
            warm = { list -> library.pinned(list) + library.rest(list) }, warmOne = { e -> Frost.prepare(e.backdrop) },
            first = { library.wanted() }, onFirst = { part -> onFirstApps(part) })
        // The top row's frame and tile shapes right away (no waiting for the apps); the real tiles replace them.
        page.skeleton(library.wanted().size)
        repo.start()
    }

    /** Startup timeline in logcat (tag EppleStart): milliseconds since the process started. */
    private fun startLog(what: String) = android.util.Log.i("EppleStart", "$what +${android.os.SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()} ms")
    private var appsShown = false

    /** The top row's apps, ahead of the full list (first load only): shown at once if the full list isn't in yet. */
    private fun onFirstApps(part: List<AppEntry>) {
        if (appsShown) return
        appsShown = true; main.post { startLog("app row shown (${part.size} first apps)") }
        page.setApps(library.pinned(part), emptyList()); restoreFocus()
    }

    private fun onApps(list: List<AppEntry>) {
        if (!appsShown) { appsShown = true; main.post { startLog("app row shown (${list.size} apps)") } }
        apps = list
        content.run({ Brand.learn(list); true }) {}             // brand colours for the hero's "New on …" names
        refresh(null)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        (screens[APPS] as? AppsScreen)?.rebuild()
    }

    /** Rebuilds the top row from the library; optionally moves focus to [focusPkg]. */
    private fun refresh(focusPkg: String?) {
        val pinned = library.pinned(apps)
        page.setApps(pinned, emptyList())             // every app lives in the Apps section; Home keeps the top row
        content.warm(pinned.map { it.packageName })
        if (focusPkg != null && page.locate(focusPkg) && section == 0) zone = Zone.CONTENT
        restoreFocus()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        updateTime()
        resumed = true; restartRotation(); restartIdle()
        hero.backdrop.pauseDrift(false)
        // Pictures copied from a computer (scripts/wallpapers.sh) arrive while another app is in front: take them in now.
        if (startedOnce) content.run({ wallpapers.importDropped() + wallpapers.importBundled() }) { n -> if ((n ?: 0) > 0) nextWallpaper(newest = true) }
        startedOnce = true                                   // the first start's import is part of onCreate's pick
    }

    override fun onStop() {
        resumed = false; main.removeCallbacks(rotate); main.removeCallbacks(idle); main.removeCallbacks(recsLater); saver.stop()
        hero.backdrop.pauseDrift(true)
        setup.pause()                                        // phone setup stops listening when the TV leaves Epple TV
        unregisterReceiver(timeReceiver)
        super.onStop()
    }

    override fun onDestroy() { main.removeCallbacks(applyPending); main.removeCallbacks(artRun); repo.stop(); content.stop(); titles.stop(); super.onDestroy() }

    /** In the background (e.g. while Netflix plays) the launcher keeps only what is on its own screen. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            content.trimMemory()
            screens.forEachIndexed { i, s -> if (i != section) (s as? BrowseScreen)?.hide() }
        }
    }

    private fun updateTime() {
        val now = Date()
        timeText = when (prefs.get(Prefs.CLOCK)) {
            1 -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
            2 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            else -> DateFormat.getTimeFormat(this).format(now)
        }
        pill.label = timeText; pill.contentDescription = getString(R.string.cc_label, timeText)
    }

    // ---- launcher mode ----

    private fun fromAppIcon(i: Intent?) = i?.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER) == true || i?.hasCategory(Intent.CATEGORY_LAUNCHER) == true

    /** Crashes are counted; three in five minutes while Epple TV is the launcher hand Home back to the TV's own. */
    private fun guardCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> try { launcher.noteCrash() } catch (_: Throwable) {}; previous?.uncaughtException(t, e) }
        if (launcher.crashLoop()) Thread({ launcher.useDefault {}; launcher.clearCrashes() }, "epple-recover").start()
    }

    /** Runs a launcher switch with its progress in a panel; [epple] = Epple TV as the launcher, else the TV's default. */
    private var switching = false
    override fun switchLauncher(epple: Boolean, done: (() -> Unit)?) {
        if (switching) return
        switching = true
        val title = TextView(this).apply { text = if (epple) "Setting Epple TV as Launcher" else "Restoring the TV's Launcher"; style(Txt.TITLE, l.u) }
        val line = TextView(this).apply { style(Txt.META, l.u, Colors.TEXT_DIM); isSingleLine = false; maxLines = 5; setPadding(0, l.px(14), 0, 0); text = "Starting…" }
        panel.show(listOf(title, line), emptyList(), atCorner = false)
        line.isFocusable = true; line.requestFocus()                 // OK while it runs must not reach the row behind
        content.run({ (if (epple) launcher.useEpple { m -> main.post { line.text = m } } else launcher.useDefault { m -> main.post { line.text = m } }) ?: "" }) { err ->
            switching = false
            val ok = err.isNullOrEmpty()
            val msg = if (ok) (if (epple) "Epple TV is now your launcher. The Home button brings you here."
                else "The TV's own launcher is back on the Home button.") else err!!
            if (ok) launcher.clearCrashes()
            panel.show(listOf(TextView(this).apply { text = if (ok) "Done" else "Not Changed"; style(Txt.TITLE, l.u) },
                TextView(this).apply { text = msg; style(Txt.META, l.u, Colors.TEXT_DIM); isSingleLine = false; maxLines = 6; setPadding(0, l.px(14), 0, 0) }),
                listOf("OK" to {}), atCorner = false)
            (screens[SETTINGS] as? SettingsScreen)?.show()
            done?.invoke()
        }
    }

    // ---- Home wallpaper ----

    /** Home's resting picture: the current wallpaper, or the neutral field until one exists. */
    private fun showWallpaper() { hero.rest(wallBmp ?: neutral) }

    /** What Home shows behind its rows: the wallpaper on the tab bar, else the focused top-row app's titles. */
    private fun homeArt() {
        if (zone == Zone.TABS || pending == null) { showWallpaper(); return }
        main.removeCallbacks(applyPending); applyPending.run(); hero.reshow()
    }

    /** Loads another wallpaper off the main thread (taking in any copied from a computer first) and shows it on Home. */
    override fun nextWallpaper(newest: Boolean) {
        val gen = ++wallGen
        content.run({
            try { wallpapers.importDropped(); wallpapers.importBundled(); val f = if (newest) wallpapers.newest() else wallpapers.pick(); f?.let { wallpapers.load(it) } ?: neutral }
            catch (e: Throwable) { android.util.Log.w("EppleHome", "wallpaper failed: ${e.javaClass.simpleName}"); throw e }
        }) { b ->
            if (gen != wallGen) return@run
            wallBmp = if (b === neutral) null else b         // the previous one is left to the GC: a backdrop layer may still hold it
            if (section == 0 && hero.resting) showWallpaper()
        }
    }

    override fun openPhone() { setup.startPhone() }
    override fun profileChanged() { pill.avatar = profile.cached; (screens[SETTINGS] as? SettingsScreen)?.show() }

    /** Control Center / Settings: the profile picture's choices. */
    override fun profileMenu() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        val icons = arrayListOf(EppleIcons.PROFILE)
        items.add("Add from Phone" to { openPhone() })
        if (profile.exists()) icons += EppleIcons.TRASH
        if (profile.exists()) items.add("Remove Picture" to {
            menu("Remove your profile picture?", listOf(
                "Cancel" to {},                                   // first, so a stray OK keeps it
                "Remove" to { content.run({ profile.remove() }) { profileChanged() } }))
        })
        menu("Profile Picture", items, icons)
    }
    override fun wallpapersAdded() { nextWallpaper(newest = true); (screens[SETTINGS] as? SettingsScreen)?.show() }

    // ---- focus and zones ----

    private fun restoreFocus() {
        if (panel.open || (::cc.isInitialized && cc.open) || (::setup.isInitialized && setup.active)) return      // setup owns focus while it is open
        when (zone) {
            Zone.CONTENT -> if (!page.focusCurrent()) toNav()
            Zone.HERO -> anchor.requestFocus()
            Zone.TABS -> focusTab()
            Zone.FEED -> if (!feed.focus()) enterContent()
            Zone.SECTION -> screens[section]?.focus()
            Zone.DETAIL -> details.lastOrNull()?.focus() ?: run { zone = detailReturn; restoreFocus() }
        }
    }

    private fun setShelf(on: Boolean) { page.shelf(on); hero.shelf(on) }
    private fun enterHero() { zone = Zone.HERO; anchor.requestFocus(); setShelf(true); tabsP.target = 0f; animateShell() }
    private fun enterContent() {
        setShelf(false); zone = Zone.CONTENT; tabsP.target = 0f
        if (homeP.target < 1f) leaveFeed()
        if (!page.focusCurrent()) toNav()
        animateShell()
    }

    private fun enterTabs(i: Int) {
        zone = Zone.TABS; tabIdx = i; tabsP.target = 1f; page.blur(); animateShell(); focusTab()
        if (section == 0 && !hero.resting) { setShelf(false); showWallpaper() }
    }
    private fun focusTab() {
        syncSide()
        when { tabIdx >= TAB_NAMES.size -> pill.requestFocus(); menuOpen -> tabs.tabs[tabIdx].requestFocus(); else -> tabs.requestFocus() }
    }

    /** Home's resting place: the hint pill ("Home · Press Back for menu") focused, the menu closed, the wallpaper behind. */
    private fun enterHint() { menuOpen = false; enterTabs(section) }
    private fun toNav() { if (section == SETTINGS) openMenu() else enterHint() }

    /** Opens the menu with section [i] highlighted. Nothing changes until a section is chosen (OK or Right). */
    private fun openMenu(i: Int = section) { menuOpen = true; enterTabs(if (i in MENU_ORDER) i else 0) }

    /** Chooses the highlighted section: the menu closes and focus goes into it. */
    private fun chooseMenu() { val i = tabIdx; menuOpen = false; showSection(i); tabIdx = i; intoSection() }

    /** Closes the menu without changing anything: back to the hint on Home, or into the section. */
    private fun closeMenu() { menuOpen = false; tabIdx = section; enterTabs(section) }

    /** Home's rows: the top row and its artwork text slide away, the rows take the screen. */
    private fun enterFeed() {
        zone = Zone.FEED; homeP.target = 0f; dimP.target = 0.25f; hero.quiet(true)
        if (feed.view.parent == null) sectionLayer.addView(feed.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        feed.view.visibility = View.VISIBLE
        feed.show(); tabArt(feed); if (!feed.focus()) { /* rows still loading: focus arrives with the first row */ }
        animateShell()
    }
    private fun leaveFeed() {
        homeP.target = 1f; dimP.target = 0f; hero.quiet(false); feed.view.visibility = View.GONE; feed.hide()
        artGen++; main.removeCallbacks(artRun)
        homeArt()                                       // the top row's artwork comes back
        animateShell()
    }

    /** Shows tab [i]. Home restores the top row; other tabs build lazily and keep their state while hidden. */
    private fun showSection(i: Int) {
        if (cc.open) cc.hide()
        if (i == section && (i == 0 || screens[i]?.view?.visibility == View.VISIBLE)) return
        screens[section]?.let { it.hide(); it.view.visibility = View.GONE; it.reset() }
        if (section == 0) { feed.reset(); focusedPkg = null }       // Home's rows and its carousel start again too
        section = i; tabs.selected = i
        artGen++; main.removeCallbacks(artRun)              // nothing requested for the previous tab may land now
        if (i == 0) {
            homeP.target = 1f; dimP.target = 0f; hero.quiet(false); feed.view.visibility = View.GONE
            homeArt()
        } else {
            homeP.target = 0f; hero.quiet(true); feed.view.visibility = View.GONE
            val s = screens[i] ?: create(i).also { screens[i] = it; sectionLayer.addView(it.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
            s.view.visibility = View.VISIBLE
            dimP.target = if (s.wantsArt) 0.2f else 0.55f
            s.show()
            tabArt(s)
        }
        animateShell()
    }

    private fun create(i: Int): Screen = when (i) {
        1 -> BrowseScreen(this, listOf(Rows.forYou(this, "movie")) + catalog.movieRows().map { Rows.catalog(this, it) })
        2 -> BrowseScreen(this, listOf(Rows.forYou(this, "tv")) + catalog.tvRows().map { Rows.catalog(this, it) })
        APPS -> AppsScreen(this)
        SEARCH -> SearchScreen(this)
        else -> SettingsScreen(this) { c -> onSetting(c) }
    }

    private fun onSetting(c: Prefs.Choice) {
        when (c) {
            Prefs.CLOCK -> updateTime()
            Prefs.ROTATE -> restartRotation()
            Prefs.SAVER -> restartIdle()
            Prefs.INDIAN -> { content.indianMode = prefs.get(Prefs.INDIAN); content.resetPicks(); content.warm(library.pinned(apps).map { it.packageName }) }
            else -> if (c.restart) restartUi()
        }
    }

    private val switchTab = Runnable { if (zone == Zone.TABS && tabIdx < TAB_NAMES.size) showSection(tabIdx) }

    // ---- details ----

    override fun details(item: ContentItem) {
        if (!Regex("^(movie|tv):\\d+$").matches(item.id)) { toast("Details aren't available for this title"); return }
        com.eppletv.launcher.content.Taste.details(this, item)                // a weak taste signal (recommendations)
        if (details.isEmpty()) detailReturn = zone
        details.lastOrNull()?.view?.visibility = View.GONE
        (if (zone == Zone.FEED) feed else screens[section])?.let { it.hide(); it.view.visibility = View.GONE }
        val d = DetailScreen(this, item)
        details.add(d)
        sectionLayer.addView(d.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        zone = Zone.DETAIL; tabsP.target = 0f; dimP.target = 0f; homeP.target = 0f; hero.quiet(true)
        d.show(); d.focus(); animateShell()
    }

    private fun closeDetail() {
        val d = details.removeLastOrNull() ?: return
        sectionLayer.removeView(d.view)
        val prev = details.lastOrNull()
        if (prev != null) { prev.view.visibility = View.VISIBLE; prev.show(); prev.focus(); return }
        zone = detailReturn
        when (zone) {
            Zone.FEED -> { feed.view.visibility = View.VISIBLE; dimP.target = 0.25f; feed.show(); tabArt(feed) }
            Zone.SECTION, Zone.TABS -> screens[section]?.let { it.view.visibility = View.VISIBLE; dimP.target = if (it.wantsArt) 0.2f else 0.55f; it.show(); tabArt(it); if (zone == Zone.TABS) tabsP.target = 1f }
            else -> { homeP.target = 1f; hero.quiet(false); artGen++; homeArt() }
        }
        if (zone == Zone.SECTION) tabsP.target = 1f
        animateShell(); restoreFocus()
    }

    // ---- Nav ----

    /**
     * Backdrop requests carry a generation number: every new request (and every tab switch) bumps it, and an image
     * that finishes loading for an older generation is dropped, so a slow load can never replace newer artwork.
     */
    private var artGen = 0
    private var artItem: ContentItem? = null
    private var artBmp: Bitmap? = null
    private val artRun = Runnable {
        artBmp?.let { hero.backdrop.show(it); artBmp = null; return@Runnable }
        val it = artItem ?: return@Runnable
        val gen = artGen
        var full = false
        content.image(it.imageUrl) { b -> if (b != null && gen == artGen) { full = true; hero.backdrop.show(b) } }
        // Large artwork (Crunchyroll sends 2-3 MB PNGs) takes a moment: meanwhile the title's own card picture, enlarged,
        // stands in, so the backdrop never keeps showing the previous title's artwork under this one's name.
        if (!full) content.cardBackdrop(com.eppletv.launcher.content.Catalog.cardUrl(it.imageUrl)) { b -> if (b != null && gen == artGen && !full) hero.backdrop.show(b) }
    }
    /** The backdrop follows the focused title after a short pause (fast scrolling doesn't churn full-screen images). */
    override fun art(item: ContentItem?, immediate: Boolean) {
        if (item == null || item.imageUrl.isEmpty()) return
        artGen++; artItem = item; artBmp = null; main.removeCallbacks(artRun)
        if (!immediate) { main.postDelayed(artRun, 260); return }
        // Immediately: the image if it is already in memory, otherwise this section's neutral backdrop until it loads.
        val cached = content.cachedImage(item.imageUrl)
        if (cached != null) hero.backdrop.show(cached) else { hero.backdrop.show(neutral); artRun.run() }
    }
    override fun artBitmap(b: Bitmap) { artGen++; artItem = null; artBmp = b; main.removeCallbacks(artRun); main.postDelayed(artRun, 140) }

    /** A section's own artwork, applied the moment it opens: its featured title, its bitmap, or the neutral backdrop. */
    private fun tabArt(s: Screen) {
        s.featuredBitmap()?.let { artGen++; main.removeCallbacks(artRun); hero.backdrop.show(it); return }
        val f = s.featured()
        if (f != null) art(f, immediate = true) else { artGen++; main.removeCallbacks(artRun); hero.backdrop.show(neutral) }
    }

    /** Deep blue-black field with the scrim baked in: the backdrop of sections without artwork, and while art loads. */
    /** Graphite, for the black-and-white theme: Home without a wallpaper, Settings, sections still loading. */
    private fun neutralBackdrop(): Bitmap {
        val b = Bitmap.createBitmap(96, 54, Bitmap.Config.ARGB_8888)
        android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF26262A.toInt(), 0xFF0E0E10.toInt(), 0xFF000000.toInt()))
            .apply { setBounds(0, 0, 96, 54); draw(android.graphics.Canvas(b)) }
        Scrim.paint(b); b.prepareToDraw()
        return b
    }

    override fun launch(e: AppEntry) { open(e.launchIntent, e.label) }

    override fun play(item: ContentItem, pkg: String, appName: String) {
        user.add(UserLibrary.Kind.CONTINUE, ContentItem(item.id, item.title, item.meta, item.imageUrl, item.linkUri, pkg, "Continue in $appName", item.summary))
        val app = apps.firstOrNull { it.packageName == pkg }
        titles.open(item, pkg) { if (app != null) launch(app) else toast(getString(R.string.cannot_open, appName)) }
    }

    override fun appMenu(e: AppEntry) {
        val pkg = e.packageName
        val items = ArrayList<Pair<String, () -> Unit>>(); val icons = ArrayList<Int>()
        fun add(icon: Int, label: String, action: () -> Unit) { items.add(label to action); icons.add(icon) }
        add(EppleIcons.OPEN, getString(R.string.menu_open)) { launch(e) }
        if (library.isPinned(apps, pkg)) {
            if (library.canMove(apps, pkg, -1)) add(EppleIcons.LEFT, getString(R.string.menu_left)) { library.move(apps, pkg, -1); appsChanged() }
            if (library.canMove(apps, pkg, 1)) add(EppleIcons.RIGHT, getString(R.string.menu_right)) { library.move(apps, pkg, 1); appsChanged() }
            add(EppleIcons.PIN, getString(R.string.menu_unpin)) { library.remove(apps, pkg); appsChanged() }
        } else add(EppleIcons.PIN, getString(R.string.menu_pin)) { library.add(apps, pkg); appsChanged() }
        add(EppleIcons.INFO, getString(R.string.menu_info)) { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)), e.label) }
        add(EppleIcons.TRASH, "Uninstall") { open(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)), e.label) }
        menu(e.label, items, icons)
    }

    override fun menu(title: String, items: List<Pair<String, () -> Unit>>, icons: List<Int>) {
        panel.show(listOf<View>(TextView(this).apply { text = title; style(Txt.TITLE, l.u) }), items, atCorner = false, icons = icons)
    }

    override fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    override fun appsChanged() { refresh(null); (screens[APPS] as? AppsScreen)?.rebuild(force = true) }
    override fun restartUi() { recreate() }
    override fun startSaver() { main.postDelayed({ saver.start() }, 300) }
    override fun tileShadow(): Bitmap = shadowBmp
    override fun openSetup(step: Int, one: Boolean) { if (!one) setup.resetForRerun(); setup.start(step, one) }

    // ---- setup host ----

    override fun isDefaultHome(): Boolean {
        val rm = getSystemService(android.app.role.RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) return rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)
        val ri = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == packageName
    }

    override fun setupFinished(prefsChanged: Boolean, providers: Set<com.eppletv.launcher.content.Credentials.Key>) {
        applyChanges(prefsChanged, providers)
        while (details.isNotEmpty()) sectionLayer.removeView(details.removeAt(details.size - 1).view)
        if (zone == Zone.FEED) leaveFeed()
        showSection(0); enterHint()                          // a finished setup lands on Home
    }
    override fun setupClosed(prefsChanged: Boolean, providers: Set<com.eppletv.launcher.content.Credentials.Key>) { applyChanges(prefsChanged, providers) }

    /**
     * Applies provider keys and content preferences changed in setup, without restarting: only the sources that use a
     * changed key are reset (region or languages reset all), the top row reloads just those apps, and the TMDB-based
     * sections (Movies, TV Shows, Search, Home's rows) are rebuilt the next time they open.
     */
    private fun applyChanges(prefsChanged: Boolean, providers: Set<com.eppletv.launcher.content.Credentials.Key>) {
        if (!prefsChanged && providers.isEmpty()) { restoreFocus(); return }
        if (prefsChanged) { Sources.override = prefs.regionCode; Sources.indianLangs = prefs.langs; content.indianMode = prefs.get(Prefs.INDIAN) }
        val reset = content.invalidate(providers, all = prefsChanged)
        if (prefsChanged || com.eppletv.launcher.content.Credentials.Key.TMDB in providers) dropCatalogScreens()
        if (reset.isNotEmpty()) {
            content.warm(library.pinned(apps).map { it.packageName })      // reloads only what was reset; the rest is cached
            if (section == 0 && pending != null && zone != Zone.TABS) { main.removeCallbacks(applyPending); applyPending.run() }
        }
        (screens[SETTINGS] as? SettingsScreen)?.show()
        restoreFocus()
    }

    /** Movies, TV Shows, Search and Home's rows are built from TMDB: forget them so they are built again when next opened. */
    private fun dropCatalogScreens() {
        for (i in intArrayOf(1, 2, 5)) {
            if (i == section) continue
            screens[i]?.let { it.hide(); sectionLayer.removeView(it.view) }; screens[i] = null
        }
        if (zone != Zone.FEED) { feed.hide(); sectionLayer.removeView(feed.view); feed = HomeFeed(this) }
    }

    @Deprecated("Activity result API is not available without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_HOME) setup.refresh()
    }

    override fun onResume() {
        super.onResume()
        // Back from something opened from here: what was watched moves to the front of Continue Watching (that row only).
        if (com.eppletv.launcher.content.Recency.returned(this) != null && ::feed.isInitialized) feed.refreshContinue()
        if (::setup.isInitialized) setup.refresh()
        // Epple TV's own notes: a new release, new episodes of followed shows (each at most once a day).
        Watchers.checkUpdate(this, content, prefs, BuildConfig.VERSION_NAME)
        Watchers.checkFollowed(this, content, catalog, user, prefs)
        if (::setup.isInitialized && setup.done && !askedListings()) askListings()
        refreshChannels()
        // Recommendations refresh well after the first frame (at most once a day; the saved deck shows meanwhile).
        main.removeCallbacks(recsLater); main.postDelayed(recsLater, 4000)
    }

    private val recsLater = Runnable { refreshRecs(force = false) }
    override fun refreshRecs(force: Boolean) {
        if (prefs.get(Prefs.RECS) != 0) return
        com.eppletv.launcher.content.Recommender.refresh(this, content, catalog, user, content.indianMode,
            watchNext = { ContinueWatching.load(this, content.net, false).map { it.title } }, force = force) { ok ->
            if (!ok) return@refresh
            if (::feed.isInitialized) feed.recsChanged()
            screens.forEach { (it as? BrowseScreen)?.recsChanged() }
        }
    }

    /** Which apps publish their own rows is read again on every return: a newly signed-in service starts recommending. */
    private fun refreshChannels() {
        val ch = com.eppletv.launcher.content.Sources.channels(this)
        content.run({ ch.refresh() }) { changed -> if (changed == true) refresh(null) }
    }

    /**
     * Android TV's own lists (Prime Video's rows, Watch Next) need READ_TV_LISTINGS, a normal runtime permission: the
     * TV asks once, after setup; Settings > Content > TV listings asks again or explains how to allow it.
     */
    private fun askedListings() = getSharedPreferences("perm", MODE_PRIVATE).getBoolean("listings", false)
    override fun askListings() {
        if (com.eppletv.launcher.content.TvListings.allowed(this)) return
        getSharedPreferences("perm", MODE_PRIVATE).edit().putBoolean("listings", true).apply()
        if (askedListingsBefore && !shouldShowRequestPermissionRationale(com.eppletv.launcher.content.TvListings.PERMISSION)) {
            toast("Allow it in TV Settings > Apps > Epple TV > Permissions")
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) } catch (_: Exception) {}
            return
        }
        askedListingsBefore = true
        requestPermissions(arrayOf(com.eppletv.launcher.content.TvListings.PERMISSION), REQ_LISTINGS)
    }
    private var askedListingsBefore = false

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != REQ_LISTINGS) return
        val ok = results.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (ok) refreshChannels()                             // apps' own rows take over from the showcase
        (screens[SETTINGS] as? SettingsScreen)?.show()
    }

    /** The Home button while Epple TV is the Home screen: back to Home's top row. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (fromAppIcon(intent) && !setup.active) { closeOverlays(); if (zone == Zone.FEED) leaveFeed(); goTab(SETTINGS); return }
        if (intent.hasCategory(Intent.CATEGORY_HOME) && !setup.active) goHome()
    }

    /** Control Center, menus and the screensaver close when the Home button or another screen takes over. */
    private fun closeOverlays() { if (cc.open) cc.hide(); if (panel.open) panel.hide(); if (saver.showing) saver.stop() }

    /**
     * The Home button: Home tab, its wallpaper and the top row, from any state. Home's layer is restored explicitly,
     * because pressing Home from a details page opened on Home itself never goes through a tab change (it used to
     * leave the top row hidden until some later action brought it back).
     */
    private fun goHome() {
        closeOverlays()
        while (details.isNotEmpty()) sectionLayer.removeView(details.removeAt(details.size - 1).view)
        // Once: the open section, back at its top with its pill focused. Again (already there, or in Settings): Home.
        val atTop = zone == Zone.TABS && !menuOpen && tabIdx == section
        if (zone == Zone.FEED) leaveFeed()
        if (section != 0 && (atTop || section == SETTINGS)) showSection(0)
        else if (section == 0) { homeP.target = 1f; dimP.target = 0f; hero.quiet(false); feed.view.visibility = View.GONE; feed.hide() }
        else screens[section]?.reset()
        menuOpen = false
        enterHint()                                          // menu closed; Home keeps the same wallpaper (a new one only when Epple TV starts)
        animateShell()
    }

    // ---- tiles (top row) ----

    override fun onPageDepth(deep: Boolean) { if (section == 0 && zone != Zone.FEED) { dimP.target = if (deep) 0.5f else 0f; animateShell() } }

    override fun onTileFocused(tile: Tile) {
        val first = pending == null
        pending = tile
        if (tile.entry.packageName != focusedPkg) { focusedPkg = tile.entry.packageName; switched = true }
        main.removeCallbacks(applyPending)
        if (first) applyPending.run() else main.postDelayed(applyPending, 140)
    }

    override fun onTileClicked(tile: Tile) { launch(tile.entry, tile) }
    override fun onTileLongPressed(tile: Tile) { appMenu(tile.entry) }

    private fun launch(e: AppEntry, from: View?): Boolean {
        val opts = from?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
        return open(e.launchIntent, e.label, opts)
    }

    private fun openHeroItem() {
        val e = pending?.entry ?: return
        val item = hero.item
        if (item != null && item.linkUri == null && item.linkPkg == null) user.add(UserLibrary.Kind.CONTINUE,
            ContentItem(item.id, item.title, item.meta, item.imageUrl, null, e.packageName, "Continue in ${e.label}", item.summary))
        titles.open(item, e.packageName) { launch(e, null) }
    }

    private fun open(intent: Intent, name: String, opts: Bundle? = null): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts)
        com.eppletv.launcher.content.Recency.opened(this, intent.component?.packageName ?: intent.`package`, null)
        com.eppletv.launcher.content.Taste.appOpened(this, intent.component?.packageName ?: intent.`package`); true
    } catch (e: ActivityNotFoundException) {
        toast(getString(R.string.cannot_open, name)); false
    } catch (e: SecurityException) {
        toast(getString(R.string.cannot_open, name)); false
    }

    // ---- Control Center ----

    // ---- Control Center ----

    private lateinit var ccWifi: CcTile; private lateinit var ccInputs: CcTile
    private lateinit var ccVolume: CcTile; private lateinit var ccSleep: CcTile
    private var sleepAt = 0L                                  // elapsedRealtime of the sleep timer, 0 = off
    private val sleepRun = Runnable { sleepAt = 0L; content.run({ tv.standby() ?: "" }) { e -> if (!e.isNullOrEmpty()) toast(e) } }

    private fun buildControlCenter() {
        fun act(a: () -> Unit) = { cc.hide(); a() }
        cc.build(listOf(
            ControlCenter.Item(EppleIcons.WIFI, "Wi-Fi", action = act { open(Intent(Settings.ACTION_WIFI_SETTINGS), "Wi-Fi") }),
            ControlCenter.Item(EppleIcons.POWER, "Standby") { cc.hide(); content.run({ tv.standby() ?: "" }) { e -> if (!e.isNullOrEmpty()) toast(e) } },
            ControlCenter.Item(EppleIcons.INPUT, "Inputs", action = act { inputsMenu() }),
            // OK starts adjusting (Left/Right change the volume with the TV's own volume bar); OK, Up, Down or Back stop.
            ControlCenter.Item(EppleIcons.VOLUME, "Volume") {
                val am = getSystemService(android.media.AudioManager::class.java)
                if (am == null || am.isVolumeFixed) { toast("This TV's volume can't be changed from here. Use the remote's volume buttons."); return@Item }
                ccVolume.adjusting = !ccVolume.adjusting
                if (ccVolume.adjusting) am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_SAME, android.media.AudioManager.FLAG_SHOW_UI)
                volumeSub()
            },
            ControlCenter.Item(EppleIcons.MOON, "Sleep Timer") { cycleSleep() },
            ControlCenter.Item(EppleIcons.PICTURE, "Screensaver", round = true, action = act { startSaver() }),
            ControlCenter.Item(EppleIcons.BLUETOOTH, "Bluetooth & Accessories", round = true, action = act { openBluetooth() }),
            ControlCenter.Item(EppleIcons.SETTINGS, "Epple TV Settings", round = true, action = act { goTab(SETTINGS) }),
            ControlCenter.Item(EppleIcons.SLIDERS, "TV Settings", round = true, action = act { open(Intent(Settings.ACTION_SETTINGS), "Settings") }),
        ))
        ccWifi = cc.tiles[0]; ccInputs = cc.tiles[2]; ccVolume = cc.tiles[3]; ccSleep = cc.tiles[4]
        ccVolume.adjust = { d -> changeVolume(d) }
        cc.tiles.firstOrNull { it.title == "Epple TV Settings" }?.art = getDrawable(R.drawable.logo_mark)?.mutate()
    }

    /** One step of the TV's volume (2 % of its range), with the system's own volume bar; raising unmutes. */
    private fun changeVolume(d: Int) {
        val am = getSystemService(android.media.AudioManager::class.java) ?: return
        val s = android.media.AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(s).coerceAtLeast(1); val step = (max / 50).coerceAtLeast(1)
        try {
            if (d > 0 && am.isStreamMute(s)) am.adjustStreamVolume(s, android.media.AudioManager.ADJUST_UNMUTE, 0)
            am.setStreamVolume(s, (am.getStreamVolume(s) + d * step).coerceIn(0, max), android.media.AudioManager.FLAG_SHOW_UI)
        } catch (_: SecurityException) { toast("The TV didn't allow changing the volume.") }
        volumeSub()
    }

    /**
     * Bluetooth & Accessories: the platform's Bluetooth settings where they exist; on Google TV (which has none) the TV
     * settings' accessories list (a maker's own screen first, e.g. TCL's), then Google TV's "Pair accessory"; else the
     * settings home.
     */
    private fun openBluetooth() {
        val tries = listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), Intent("com.android.tv.settings.ACCESSORIES_CONNECTED_DEVICES"),
            Intent("com.google.android.intent.action.CONNECT_INPUT"), Intent(Settings.ACTION_SETTINGS))
        for (i in tries) {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: ActivityNotFoundException) {} catch (_: SecurityException) {}
        }
        toast(getString(R.string.cannot_open, "Accessories"))
    }

    private lateinit var banner: NotifBanner

    /** Opens a notification as its app intended (its own tap action), else the app itself. */
    private fun openNote(n: NotificationHub.Note) {
        // Epple TV's own notes stay (read) and do what they are about.
        if (n.own) {
            NotificationHub.markRead(n.key)
            val a = n.action.orEmpty()
            when {
                a == "wifi" -> open(Intent(Settings.ACTION_WIFI_SETTINGS), "Wi-Fi")
                a.startsWith("update:") -> Watchers.install(this, n.key, a.removePrefix("update:"))
                // The followed title itself when it's still in the lists, else the title by its TMDB id (a new movie
                // of a followed franchise, or a show no longer followed): the details page loads everything from the id.
                a.startsWith("details:") -> { val id = a.removePrefix("details:")
                    details((user.items(UserLibrary.Kind.WATCHLIST) + user.items(UserLibrary.Kind.FAVOURITES)).firstOrNull { it.id == id }
                        ?: com.eppletv.launcher.content.ContentItem(id, n.title.substringAfter(" · ").takeIf { id.startsWith("movie:") } ?: n.title.substringBefore(" · "), "", "", null, null)) }
            }
            return
        }
        NotificationHub.dismiss(n.key)
        val ok = try { n.intent?.send(); n.intent != null } catch (_: Exception) { false }
        if (!ok) apps.firstOrNull { it.packageName == n.pkg }?.let { launch(it) } ?: toast(n.app)
    }

    /** Control Center > Notifications: newest first; OK opens one, Clear All empties Epple TV's list. */
    private fun notificationsMenu() {
        if (!NotificationHub.allowed(this)) {
            menu("Notifications", listOf("Allow Notifications" to { allowNotifications() }, "Not Now" to {})); return
        }
        NotificationHub.markRead()
        val list = NotificationHub.notes
        if (list.isEmpty()) { toast("No notifications"); return }
        menu("Notifications", list.take(8).map { n -> "${n.app}: ${n.title}" to { openNote(n) } } + listOf("Clear All" to { NotificationHub.clearAll() }))
    }

    override fun allowNotifications() {
        if (NotificationHub.allowed(this)) return
        content.run({ launcher.allowNotifications { } ?: "" }) { err ->
            if (!err.isNullOrEmpty()) toast(err)
            else { android.service.notification.NotificationListenerService.requestRebind(NotificationHub.component(this)); toast("Notifications allowed") }
            (screens[SETTINGS] as? SettingsScreen)?.show()
        }
    }

    private fun openControlCenter() {
        if (cc.open) return
        cc.emptyText = if (NotificationHub.allowed(this)) "You're all caught up" else "You're all caught up.\nOther apps' notifications are off: Settings > Notifications."
        ccRefresh()
        cc.show { restoreFocus() }
    }

    /** Status lines: network, inputs, volume, sleep timer. The Wi-Fi name arrives a moment later when the TV allows it. */
    private fun ccRefresh() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val wifi = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        // The last known network name stays shown while it is looked up again (no "Connected" -> name flicker).
        ccWifi.sub = when {
            caps == null -> "Not connected"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> wifiName ?: "Connected"
        }
        if (!wifi) wifiName = null
        else if (!wifiLookup) { wifiLookup = true
            content.run({ tv.wifiName() ?: "" }) { n -> wifiLookup = false; if (!n.isNullOrEmpty()) { wifiName = n; ccWifi.sub = n } } }
        val n = tvInputs().size
        ccInputs.sub = if (n == 0) "TV inputs" else "$n ${if (n == 1) "input" else "inputs"}"
        volumeSub()
        sleepSub()
    }
    private var wifiName: String? = null
    private var wifiLookup = false

    /** The Volume tile's status line alone (a volume change touches nothing else in Control Center). */
    private fun volumeSub() {
        val am = getSystemService(android.media.AudioManager::class.java) ?: return
        val s = android.media.AudioManager.STREAM_MUSIC
        ccVolume.sub = if (am.isStreamMute(s)) "Muted" else "${100 * am.getStreamVolume(s) / am.getStreamMaxVolume(s).coerceAtLeast(1)} %"
    }
    private fun sleepSub() {
        ccSleep.sub = if (sleepAt == 0L) "Off" else "${((sleepAt - android.os.SystemClock.elapsedRealtime()) / 60_000L + 1).coerceAtLeast(1)} min left"
    }

    /** Off, 15, 30, 60, 90 minutes, then off again; at the end the TV goes to standby. */
    private fun cycleSleep() {
        val left = if (sleepAt == 0L) 0L else (sleepAt - android.os.SystemClock.elapsedRealtime()) / 60_000L + 1
        val next = SLEEP_STEPS.firstOrNull { it > left } ?: 0L
        main.removeCallbacks(sleepRun)
        sleepAt = if (next == 0L) 0L else android.os.SystemClock.elapsedRealtime() + next * 60_000L
        if (sleepAt != 0L) main.postAtTime(sleepRun, sleepAt)
        sleepSub()
    }

    private fun tvInputs(): List<android.media.tv.TvInputInfo> = try {
        getSystemService(android.media.tv.TvInputManager::class.java)?.tvInputList.orEmpty()
            .filter { it.type != android.media.tv.TvInputInfo.TYPE_TUNER && !it.isHidden(this) }
    } catch (_: Exception) { emptyList() }

    /** The TV's inputs (HDMI and others) as a menu; choosing one opens it in the TV's own player. */
    private fun inputsMenu() {
        val list = tvInputs()
        if (list.isEmpty()) { open(Intent(Settings.ACTION_SETTINGS), "Settings"); return }
        menu("Inputs", list.map { info ->
            val name = info.loadCustomLabel(this)?.toString()?.takeIf { it.isNotBlank() } ?: info.loadLabel(this).toString()
            name to { open(Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.buildChannelUriForPassthroughInput(info.id)), name); Unit }
        })
    }

    /** Jumps straight into a tab (from Control Center). */
    private fun goTab(i: Int) {
        while (details.isNotEmpty()) { sectionLayer.removeView(details.removeAt(details.size - 1).view) }
        if (zone == Zone.FEED) feed.view.visibility = View.GONE
        tabIdx = i; showSection(i); zone = Zone.SECTION; tabsP.target = 1f; animateShell()
        main.post { screens[i]?.focus() }
    }

    // ---- keys ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down) { restartIdle() }
        // Any button wakes the screensaver and does nothing else.
        if (saver.showing) { if (down) { saver.stop(); swallowUp = true }; return true }
        // A notification popup: OK opens it, Back dismisses it; every other button works as usual.
        if (banner.showing && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BACK)) {
            if (down && event.repeatCount == 0) { val n = banner.note; banner.hide(); if (k != KeyEvent.KEYCODE_BACK && n != null) openNote(n) }
            return true
        }
        if (swallowUp) { if (!down) swallowUp = false; return true }
        // Setup uses the platform's focus handling (and the system keyboard for API keys).
        if (setup.active) {
            if (k == KeyEvent.KEYCODE_BACK) { if (down && event.repeatCount == 0) setup.back(); return true }
            return super.dispatchKeyEvent(event)
        }

        val dx = when (k) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
        val dy = when (k) { KeyEvent.KEYCODE_DPAD_UP -> -1; KeyEvent.KEYCODE_DPAD_DOWN -> 1; else -> 0 }
        val dpad = dx != 0 || dy != 0
        val select = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (down) restartRotation()

        if (cc.open && !panel.open) {
            // The profile orb sits above Control Center: Left/Right switch between it and the switches, Down goes back.
            if (pill.isFocused && dpad) {
                if (down) when { dy > 0 -> { pill.part = 0; cc.key(event) }; dx > 0 -> pill.part = 1; dx < 0 -> pill.part = 0 }
                return true
            }
            if (select) return super.dispatchKeyEvent(event)
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_SETTINGS) { if (down) cc.key(event); return true }
            return if (dpad) cc.key(event) else super.dispatchKeyEvent(event)
        }
        if (panel.open) {
            if (select) return super.dispatchKeyEvent(event)
            if (k == KeyEvent.KEYCODE_BACK && down) backHandled = true
            return if (dpad || k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU) panel.key(event) else super.dispatchKeyEvent(event)
        }
        if (select && zone == Zone.HERO) { if (!down && !event.isCanceled) openHeroItem(); return true }
        if (select && zone == Zone.TABS && tabIdx < TAB_NAMES.size) {
            if (down && event.repeatCount == 0) { if (menuOpen) chooseMenu() else openMenu() }
            return true
        }
        if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_SETTINGS) { if (down && event.repeatCount == 0) openControlCenter(); return true }
        if (k == KeyEvent.KEYCODE_SEARCH) { if (down && event.repeatCount == 0) goTab(SEARCH); return true }
        if (k == KeyEvent.KEYCODE_BACK) {
            if (down && event.repeatCount == 0) backHandled = handleBack()
            return if (backHandled) true else super.dispatchKeyEvent(event)
        }
        if (!dpad) return super.dispatchKeyEvent(event)
        if (!down) return true
        dirX = dx; dirY = dy
        when (zone) {
            Zone.CONTENT -> when {
                dy < 0 && page.atTop() && !hero.hasItems -> enterHint()
                dy > 0 && page.atTop() -> enterFeed()
                !page.move(dx, dy) -> if (hero.hasItems) enterHero() else enterHint()
            }
            Zone.HERO -> if (dx != 0) hero.nav(dx) else { hero.leave(); if (dy < 0) enterHint() else enterContent() }
            // The clock: Right moves to the profile orb, Left back to Control Center and then to the menu; Down leaves.
            // The clock (top right): Right selects the profile orb, Left goes back to Control Center, then to the menu's hint;
            // Down goes into the page below it.
            Zone.TABS -> if (tabIdx >= TAB_NAMES.size) when {
                dx > 0 -> pill.part = 1
                dx < 0 -> if (pill.part == 1) pill.part = 0 else if (section != SETTINGS) enterHint()
                dy > 0 -> intoSection()
            } else if (!menuOpen) when {
                // The hint pill (closed menu): Down into the section, Up or Right to the clock, Left opens the menu.
                dy > 0 -> intoSection()
                dx > 0 || dy < 0 -> { tabIdx = PILL; pill.part = if (dy < 0) 1 else 0; focusTab() }
                dx < 0 -> openMenu()
            } else when {
                // The open menu, as on tvOS: Up/Down only highlight (Up past Search reaches the clock); OK or Right chooses.
                dy != 0 -> {
                    val pos = MENU_ORDER.indexOf(tabIdx) + dy
                    if (pos < 0) { menuOpen = false; tabIdx = PILL; focusTab() }
                    else if (pos < MENU_ORDER.size) { tabIdx = MENU_ORDER[pos]; focusTab() }
                }
                dx > 0 -> chooseMenu()
            }
            Zone.FEED -> if (!feed.key(dx, dy)) { if (dx < 0) openMenu() else enterContent() }
            // Up or Left out of a section opens the menu (as the Apple TV app's sidebar does).
            Zone.SECTION -> if (screens[section]?.key(dx, dy) == false) when {
                dy < 0 -> if (section == SETTINGS) { tabIdx = PILL; pill.part = 1; zone = Zone.TABS; focusTab() } else enterHint()
                dx < 0 && section != SETTINGS -> openMenu()
            }
            Zone.DETAIL -> details.lastOrNull()?.key(dx, dy)
        }
        syncChips()
        return true
    }

    /** The Play chip belongs to the focused title only: the hero's while the hero has focus, a row's while a card does. */
    private fun syncChips() {
        hero.chip(zone == Zone.HERO && section == 0)
        if (::feed.isInitialized) feed.chips(zone == Zone.FEED)
        screens.forEachIndexed { i, s -> if (i != 0) s?.chips(zone == Zone.SECTION && i == section) }
    }

    /** From the tab bar down into the selected section (Home: back to its artwork and top row). */
    private fun intoSection() {
        menuOpen = false
        if (section == 0) { tabsP.target = 0f; animateShell(); if (hero.hasItems) enterHero() else enterContent(); return }
        zone = Zone.SECTION
        screens[section]?.focus(); animateShell()
    }

    /** BACK walks up: details -> section -> tab bar -> Home's top row; at the top row it leaves the app. */
    private fun handleBack(): Boolean = when (zone) {
        Zone.DETAIL -> { closeDetail(); true }
        // Back walks up to the open tab's pill (it never opens the menu); Settings (not a tab) goes back Home.
        Zone.SECTION -> { if (screens[section]?.back() != true) { if (section == SETTINGS) { showSection(0); enterHint() } else enterHint() }; true }
        Zone.FEED -> { if (!feed.back()) enterContent(); true }
        // Back closes the open menu (nothing changes); from the clock it returns to where you were; on Home's hint it stays.
        Zone.TABS -> when {
            menuOpen -> { closeMenu(); true }
            tabIdx >= TAB_NAMES.size -> { if (section == SETTINGS) intoSection() else enterHint(); true }
            section != 0 -> { showSection(0); enterHint(); true }                      // another tab's pill: Back goes Home
            else -> true                                                               // Home's pill: Back does nothing
        }
        Zone.CONTENT -> { if (!page.atTop()) { dirX = 0; dirY = -1; page.toTop() } else enterHint(); true }
        Zone.HERO -> { enterContent(); true }
    }

    private companion object {
        // Settings is not a tab: it opens from the Epple TV icon in the TV's apps, and from Control Center (SETTINGS).
        // Library was removed: Watchlist and Favourites stay reachable from Home's rows below the top row.
        // Indexed by section; the side menu lists MENU_ORDER (Search at the bottom; Settings is not in it).
        val TAB_NAMES = listOf("Home", "Movies", "TV Shows", "Apps", "Search", "Settings")
        val TAB_ICONS = listOf(EppleIcons.HOME, EppleIcons.MOVIES, EppleIcons.TV, EppleIcons.APPS, EppleIcons.SEARCH, EppleIcons.SETTINGS)
        val MENU_ORDER = listOf(0, 1, 2, 3, 4)
        const val PILL = 6
        const val APPS = 3
        const val SEARCH = 4
        const val SETTINGS = 5
        private const val REQ_LISTINGS = 41
        val SLEEP_STEPS = listOf(15L, 30L, 60L, 90L)
        const val REQ_HOME = 41
    }
}
