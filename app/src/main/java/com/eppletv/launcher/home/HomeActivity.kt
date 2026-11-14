package com.eppletv.launcher.home

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
    private var wallBmp: Bitmap? = null
    private var wallGen = 0
    private lateinit var repo: AppRepository
    private lateinit var titles: TitleLauncher
    private lateinit var hero: Hero
    private lateinit var page: HomePage
    private lateinit var homeLayer: FrameLayout
    private lateinit var sectionLayer: FrameLayout
    private lateinit var tabs: TabBar
    private lateinit var pill: Pill
    private lateinit var panel: MenuPanel
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
    private var tabIdx = 0                          // 0..6 tabs, 7 = the clock (Control Center)
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
    private val shell = object : FrameClock.Tick {
        override fun tick(dt: Float): Boolean {
            dimP.step(dt); tabsP.step(dt); homeP.step(dt)
            hero.backdrop.dim(dimP.value)
            tabs.alpha = tabsP.value; tabs.translationY = -(1f - tabsP.value) * l.pxf(30f)
            tabs.visibility = if (tabsP.value > 0.01f) View.VISIBLE else View.INVISIBLE
            val homeClock = if (section == 0 && zone != Zone.FEED && zone != Zone.DETAIL) (1f - dimP.value * 2f).coerceIn(0f, 1f) else 0f
            pill.alpha = maxOf(tabsP.value, homeClock)
            homeLayer.alpha = homeP.value; homeLayer.translationY = -(1f - homeP.value) * l.pxf(260f)
            homeLayer.visibility = if (homeP.value > 0.01f) View.VISIBLE else View.INVISIBLE
            return !(dimP.atRest() && tabsP.atRest() && homeP.atRest())
        }
    }
    private fun animateShell() = clock.add(shell)

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
            if (prefs.rotateMs > 0) main.postDelayed(this, prefs.rotateMs)
        }
    }
    private fun restartRotation() { main.removeCallbacks(rotate); if (resumed && prefs.rotateMs > 0) main.postDelayed(rotate, prefs.rotateMs) }

    private val idle = Runnable { if (resumed && !panel.open && !setup.active) saver.start() }
    private fun restartIdle() { main.removeCallbacks(idle); if (resumed && prefs.saverMs > 0) main.postDelayed(idle, prefs.saverMs) }

    private val timeReceiver = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = updateTime() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        wallpapers = Wallpapers(this)
        Type.init(this); Type.scale = prefs.textScale; Type.boldDelta = if (prefs.boldText) 150 else 0
        Sources.override = prefs.regionCode
        Sources.indianLangs = prefs.langs
        com.eppletv.launcher.content.Credentials.init(this)
        val dm = resources.displayMetrics
        l = Layout.forWindow(dm.widthPixels, dm.heightPixels)
        library = Library(this)
        user = UserLibrary(this)
        content = ContentRepository(this, Sources.all(if (BuildConfig.DEBUG) intent.getStringExtra("cinemetaFor") else null), Scrim::paint)
        content.indianMode = prefs.get(Prefs.INDIAN)
        catalog = Catalog(content.net, com.eppletv.launcher.content.Credentials.get(com.eppletv.launcher.content.Credentials.Key.TMDB)) { Sources.region }
        titles = TitleLauncher(this, content.net, com.eppletv.launcher.content.Credentials.get(com.eppletv.launcher.content.Credentials.Key.WATCHMODE), Sources.region)
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

        tabs = TabBar(this, l, clock, TAB_NAMES, TAB_ICONS, hero.backdrop).apply { alpha = 0f; visibility = View.INVISIBLE }
        root.addView(tabs, FrameLayout.LayoutParams(WRAP_CONTENT, l.px(108), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = l.px(36) })

        pill = Pill(this, "", l.pxf(Txt.META.px), l.pxf(30f), l.px(28), clock, Colors.GLASS, Colors.WHITE, 0.06f)
        pill.setOnClickListener { openControlCenter() }
        root.addView(pill, FrameLayout.LayoutParams(WRAP_CONTENT, l.px(60), Gravity.TOP or Gravity.END).apply { topMargin = l.px(56); rightMargin = l.px(64) })

        empty = TextView(this).apply { setText(R.string.no_apps); style(Txt.ROW, l.u, Colors.TEXT_DIM); visibility = View.GONE }
        root.addView(empty, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))

        panel = MenuPanel(this, l, clock) { restoreFocus() }
        root.addView(panel.dim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(panel.panel, panel.params())

        saver = Screensaver(this).apply { clockText = { timeText } }
        root.addView(saver.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        setup = SetupFlow(this, this)
        root.addView(setup.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (!setup.done) setup.start()                     // first launch, or an interrupted setup resumes
        setup.behind = listOf(anchor, homeLayer, sectionLayer, tabs, pill)
        setup.view.background = android.graphics.drawable.BitmapDrawable(resources, neutral).apply { isFilterBitmap = true }

        setContentView(root)
        // Home tab selected, its wallpaper behind: posters appear only once a streaming app is focused.
        tabsP.target = 1f; animateShell()
        showWallpaper(); nextWallpaper()
        val repeat = if (BuildConfig.DEBUG) intent.getIntExtra("repeat", 1) else 1
        val artScale = l.px(300) / l.tileW.toFloat()     // art is rendered larger than the tile, so it stays sharp when focused
        repo = AppRepository(this, l.px(300), l.px(169), { onApps(it) }, repeat,
            decorate = { c, w, h -> paintTileSurface(c, w, h, l.corner * artScale, l.pxf(2f).coerceAtLeast(1f) * artScale) },
            backdropFilter = Scrim::paint)
        repo.start()
    }

    private fun onApps(list: List<AppEntry>) {
        apps = list
        refresh(null)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        (screens[4] as? AppsScreen)?.rebuild()
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
    }

    override fun onStop() {
        resumed = false; main.removeCallbacks(rotate); main.removeCallbacks(idle); saver.stop()
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
        pill.text = timeText; pill.contentDescription = getString(R.string.cc_label, timeText)
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
        content.run({ wallpapers.importDropped(); val f = if (newest) wallpapers.newest() else wallpapers.pick(); f?.let { wallpapers.load(it) } ?: neutral }) { b ->
            if (gen != wallGen) return@run
            wallBmp = if (b === neutral) null else b         // the previous one is left to the GC: a backdrop layer may still hold it
            if (section == 0 && hero.resting) showWallpaper()
        }
    }

    override fun openPhone() { setup.startPhone() }
    override fun wallpapersAdded() { nextWallpaper(newest = true); (screens[6] as? SettingsScreen)?.show() }

    // ---- focus and zones ----

    private fun restoreFocus() {
        if (panel.open || (::setup.isInitialized && setup.active)) return      // setup owns focus while it is open
        when (zone) {
            Zone.CONTENT -> if (!page.focusCurrent()) enterTabs(section)
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
        if (!page.focusCurrent()) enterTabs(0)
        animateShell()
    }

    private fun enterTabs(i: Int) {
        zone = Zone.TABS; tabIdx = i; tabsP.target = 1f; animateShell(); focusTab()
        if (section == 0 && !hero.resting) { setShelf(false); showWallpaper() }
    }
    private fun focusTab() { if (tabIdx >= TAB_NAMES.size) pill.requestFocus() else tabs.tabs[tabIdx].requestFocus() }

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
        if (i == section && (i == 0 || screens[i]?.view?.visibility == View.VISIBLE)) return
        screens[section]?.let { it.hide(); it.view.visibility = View.GONE }
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
        1 -> BrowseScreen(this, catalog.movieRows().map { Rows.catalog(this, it) })
        2 -> BrowseScreen(this, catalog.tvRows().map { Rows.catalog(this, it) })
        3 -> LibraryScreen(this)
        4 -> AppsScreen(this)
        5 -> SearchScreen(this)
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
        content.image(it.imageUrl) { b -> if (b != null && gen == artGen) hero.backdrop.show(b) }
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
    private fun neutralBackdrop(): Bitmap {
        val b = Bitmap.createBitmap(96, 54, Bitmap.Config.ARGB_8888)
        android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1C2A40.toInt(), 0xFF0C1220.toInt(), 0xFF05070C.toInt()))
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
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add(getString(R.string.menu_open) to { launch(e) })
        if (library.isPinned(apps, pkg)) {
            if (library.canMove(apps, pkg, -1)) items.add(getString(R.string.menu_left) to { library.move(apps, pkg, -1); appsChanged() })
            if (library.canMove(apps, pkg, 1)) items.add(getString(R.string.menu_right) to { library.move(apps, pkg, 1); appsChanged() })
            items.add(getString(R.string.menu_unpin) to { library.remove(apps, pkg); appsChanged() })
        } else items.add(getString(R.string.menu_pin) to { library.add(apps, pkg); appsChanged() })
        items.add(getString(R.string.menu_info) to { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)), e.label); Unit })
        items.add("Uninstall" to { open(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)), e.label); Unit })
        menu(e.label, items)
    }

    override fun menu(title: String, items: List<Pair<String, () -> Unit>>) {
        panel.show(listOf<View>(TextView(this).apply { text = title; style(Txt.TITLE, l.u) }), items, atCorner = false)
    }

    override fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    override fun appsChanged() { refresh(null); (screens[4] as? AppsScreen)?.rebuild(force = true) }
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

    /** Android's own confirmation (RoleManager), else the system Home settings screen; never changed silently. */
    override fun requestDefaultHome(): Boolean {
        val rm = getSystemService(android.app.role.RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
            val i = rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME)
            if (packageManager.resolveActivity(i, 0) != null) { startActivityForResult(i, REQ_HOME); return true }
        }
        val s = Intent(Settings.ACTION_HOME_SETTINGS)
        if (packageManager.resolveActivity(s, 0) != null) { startActivityForResult(s, REQ_HOME); return true }
        return false
    }

    override fun setupFinished(changedKeys: Boolean) { recreate() }       // providers, region and languages apply on a fresh start
    override fun setupClosed(changedKeys: Boolean) { if (changedKeys) recreate() else restoreFocus() }

    @Deprecated("Activity result API is not available without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_HOME) setup.refresh()
    }

    override fun onResume() { super.onResume(); if (::setup.isInitialized) setup.refresh() }

    /** The Home button while Epple TV is the Home screen: back to Home's top row. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME) && !setup.active) {
            if (panel.open) panel.hide()
            while (details.isNotEmpty()) sectionLayer.removeView(details.removeAt(details.size - 1).view)
            if (zone == Zone.FEED) leaveFeed()
            showSection(0); enterTabs(0); nextWallpaper()
        }
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
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts); true
    } catch (e: ActivityNotFoundException) {
        toast(getString(R.string.cannot_open, name)); false
    } catch (e: SecurityException) {
        toast(getString(R.string.cannot_open, name)); false
    }

    // ---- Control Center ----

    private fun openControlCenter() {
        val date = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
        val header = listOf<View>(
            TextView(this).apply { text = timeText; style(Txt.CLOCK, l.u) },
            TextView(this).apply { text = date; style(Txt.META, l.u, Colors.TEXT_DIM); setPadding(0, l.px(10), 0, 0) },
        )
        val quick = listOf<Pair<String, () -> Unit>>(
            "Search" to { goTab(5) },
            "Epple TV Settings" to { goTab(6) },
            "Start Screensaver" to { startSaver() },
        )
        val system = listOf(
            R.string.cc_settings to Settings.ACTION_SETTINGS,
            R.string.cc_network to Settings.ACTION_WIFI_SETTINGS,
            R.string.cc_display to Settings.ACTION_DISPLAY_SETTINGS,
            R.string.cc_sound to Settings.ACTION_SOUND_SETTINGS,
            R.string.cc_accessories to Settings.ACTION_BLUETOOTH_SETTINGS,
        ).filter { packageManager.resolveActivity(Intent(it.second), 0) != null }
            .map { (label, action) -> getString(label).let { name -> name to { open(Intent(action), name); Unit } } }
        val footer = if (!Sources.usesTmdb) null else TextView(this).apply {
            setText(R.string.tmdb_credit); style(Txt.FOOT, l.u, Colors.TEXT_DIM); setPadding(l.px(6), l.px(22), l.px(6), 0)
        }
        panel.show(header, quick + system, atCorner = true, footer = footer)
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

        if (panel.open) {
            if (select) return super.dispatchKeyEvent(event)
            if (k == KeyEvent.KEYCODE_BACK && down) backHandled = true
            return if (dpad || k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU) panel.key(event) else super.dispatchKeyEvent(event)
        }
        if (select && zone == Zone.HERO) { if (!down && !event.isCanceled) openHeroItem(); return true }
        if (select && zone == Zone.TABS && tabIdx < TAB_NAMES.size) {
            if (down && event.repeatCount == 0) { main.removeCallbacks(switchTab); showSection(tabIdx); intoSection() }
            return true
        }
        if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_SETTINGS) { if (down && event.repeatCount == 0) openControlCenter(); return true }
        if (k == KeyEvent.KEYCODE_SEARCH) { if (down && event.repeatCount == 0) goTab(5); return true }
        if (k == KeyEvent.KEYCODE_BACK) {
            if (down && event.repeatCount == 0) backHandled = handleBack()
            return if (backHandled) true else super.dispatchKeyEvent(event)
        }
        if (!dpad) return super.dispatchKeyEvent(event)
        if (!down) return true
        dirX = dx; dirY = dy
        when (zone) {
            Zone.CONTENT -> when {
                dy > 0 && page.atTop() -> enterFeed()
                !page.move(dx, dy) -> if (hero.hasItems) enterHero() else enterTabs(0)
            }
            Zone.HERO -> if (dx != 0) hero.step(dx) else if (dy < 0) enterTabs(0) else enterContent()
            Zone.TABS -> when {
                dx != 0 -> {
                    tabIdx = (tabIdx + dx).coerceIn(0, TAB_NAMES.size); focusTab()
                    main.removeCallbacks(switchTab); if (tabIdx < TAB_NAMES.size) main.postDelayed(switchTab, 260)
                }
                dy > 0 -> { main.removeCallbacks(switchTab); if (tabIdx < TAB_NAMES.size) showSection(tabIdx); intoSection() }
            }
            Zone.FEED -> if (!feed.key(dx, dy)) enterContent()
            Zone.SECTION -> if (screens[section]?.key(dx, dy) == false) enterTabs(section)
            Zone.DETAIL -> details.lastOrNull()?.key(dx, dy)
        }
        return true
    }

    /** From the tab bar down into the selected section (Home: back to its artwork and top row). */
    private fun intoSection() {
        if (section == 0) { tabsP.target = 0f; animateShell(); if (hero.hasItems) enterHero() else enterContent(); return }
        zone = Zone.SECTION
        screens[section]?.focus()
    }

    /** BACK walks up: details -> section -> tab bar -> Home's top row; at the top row it leaves the app. */
    private fun handleBack(): Boolean = when (zone) {
        Zone.DETAIL -> { closeDetail(); true }
        Zone.SECTION -> { if (screens[section]?.back() != true) enterTabs(section); true }
        Zone.FEED -> { if (!feed.back()) enterContent(); true }
        Zone.TABS -> if (section != 0) { showSection(0); enterTabs(0); true } else false
        Zone.CONTENT -> { if (!page.atTop()) { dirX = 0; dirY = -1; page.toTop() } else enterTabs(0); true }
        Zone.HERO -> { enterContent(); true }
    }

    private companion object {
        val TAB_NAMES = listOf("Home", "Movies", "TV Shows", "Library", "Apps", "Search", "Settings")
        val TAB_ICONS = listOf(TabIcons.HOME, TabIcons.MOVIES, TabIcons.TV, TabIcons.LIBRARY, TabIcons.APPS, TabIcons.SEARCH, TabIcons.SETTINGS)
        const val REQ_HOME = 41
    }
}
