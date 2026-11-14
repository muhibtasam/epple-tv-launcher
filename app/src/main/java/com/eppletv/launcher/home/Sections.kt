package com.eppletv.launcher.home

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.eppletv.launcher.BuildConfig
import com.eppletv.launcher.apps.AppEntry
import com.eppletv.launcher.content.Apps
import com.eppletv.launcher.content.Catalog
import com.eppletv.launcher.content.ContentItem
import com.eppletv.launcher.content.Details
import com.eppletv.launcher.content.Sources
import com.eppletv.launcher.content.UserLibrary
import com.eppletv.launcher.motion.FrameClock
import com.eppletv.launcher.motion.Motion
import com.eppletv.launcher.motion.Spring
import com.eppletv.launcher.ui.Colors
import com.eppletv.launcher.ui.Pill
import com.eppletv.launcher.ui.Txt
import com.eppletv.launcher.ui.style

// ------------------------------------------------------------------------------------------------ Details

/**
 * A movie or series: artwork, logo, metadata, where it streams, Play buttons for the apps on this TV, Watchlist and
 * Favourite toggles, then seasons and episodes (series) and "More Like This". Opened over any section; BACK closes it.
 */
class DetailScreen(nav: Nav, private val item: ContentItem) : Screen(nav), FrameClock.Tick {
    override val view = FrameLayout(nav.ctx).apply { clipChildren = false }
    private val top = LinearLayout(nav.ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val info = InfoBlock(nav.ctx, l, nav.clock, nav.content, summaryLines = 4)
    private val where = TextView(nav.ctx).apply { style(Txt.META, l.u, Colors.TEXT_DIM); text = "Checking where it streams…" }
    private val buttons = LinearLayout(nav.ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
    private val stack = ShelfStack(nav.ctx, l, nav.clock, l.px(800))
    private val pills = ArrayList<Pill>()
    private var b = 0
    private var inRows = false
    private val shift = Spring(0f, Motion.SCROLL_DAMPING, Motion.SCROLL_STIFFNESS)
    private var details: Details? = null
    private var episodes: Shelf? = null
    private val type = item.id.substringBefore(':')
    private val tmdbId = item.id.substringAfter(':').toIntOrNull() ?: 0

    init {
        top.addView(info, LinearLayout.LayoutParams(l.px(1300), WRAP_CONTENT))
        top.addView(where, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = l.px(20) })
        top.addView(buttons, LinearLayout.LayoutParams(WRAP_CONTENT, l.px(72)).apply { topMargin = l.px(30) })
        view.addView(top, FrameLayout.LayoutParams(l.px(1500), WRAP_CONTENT).apply { leftMargin = l.left; topMargin = l.px(Look.INFO_TOP + 20) })
        view.addView(stack, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        info.show(item, if (type == "tv") "Series" else "Movie")
        addButtons(null)
        nav.user.add(UserLibrary.Kind.RECENT, plain(item))
        if (nav.catalog.ready && tmdbId > 0) nav.content.work("details:${item.id}", { nav.catalog.details(type, tmdbId) }) { d ->
            if (d == null) where.text = "Details are unavailable right now." else bind(d)
        } else where.text = ""
    }

    /** A copy without per-row decoration, for the user's lists. */
    private fun plain(i: ContentItem) = ContentItem(i.id, i.title, i.meta, i.imageUrl, null, null, "", i.summary)

    private fun bind(d: Details) {
        details = d
        info.show(ContentItem(d.item.id, d.item.title, d.meta, d.item.imageUrl.ifEmpty { item.imageUrl }, null, null, "", d.item.summary), d.tagline.ifEmpty { if (type == "tv") "Series" else "Movie" })
        val installed = nav.apps.map { it.packageName }.toSet()
        where.text = if (d.providers.isEmpty()) "Not on a streaming service in your region" else "Streaming on " + d.providers.joinToString(", ") { it.name }
        addButtons(d.providers.filter { it.pkg != null && it.pkg in installed }.distinctBy { it.pkg }.map { it.name to it.pkg!! } +
            (if (Apps.STREMIO in installed && d.imdb.isNotEmpty()) listOf("Stremio" to Apps.STREMIO) else emptyList()))
        stack.clear()
        if (d.seasons.isNotEmpty()) {
            val seasons = stack.add(Shelf(nav.ctx, l, nav.clock, "Seasons", Look.SMALL_W, Look.SMALL_H, titled = false))
            seasons.set(d.seasons.map { s -> ContentItem("season:$tmdbId:${s.number}", s.name, "${s.episodes} episodes", "", null, null) }) { c, x ->
                c.bind(x.title, "", "${x.title}\n${x.meta}")
                c.setOnClickListener { loadSeason(x.id.substringAfterLast(':').toInt(), focus = true) }
            }
            seasons.col = d.seasons.size - 1
            episodes = stack.add(Shelf(nav.ctx, l, nav.clock, "Episodes", Look.SMALL_W, Look.SMALL_H).apply { loader = { c -> thumb(c) } })
            loadSeason(d.seasons.last().number, focus = false)
        }
        if (d.more.isNotEmpty()) stack.add(Shelf(nav.ctx, l, nav.clock, "More Like This").apply {
            loader = { c -> thumb(c) }
            set(d.more) { c, x -> c.bind(x.title, x.meta.split("  ·  ").take(2).joinToString(" · ")); c.setOnClickListener { nav.details(x) } }
        })
        stack.relayout()
    }

    private fun thumb(c: Card) { val it = c.item ?: return; nav.content.thumb(Catalog.cardUrl(it.imageUrl)) { bm -> if (bm != null) c.setBitmap(bm) } }

    private fun loadSeason(n: Int, focus: Boolean) {
        val shelf = episodes ?: return
        nav.content.work("season:$tmdbId:$n", { nav.catalog.season(tmdbId, n) }) { eps ->
            if (eps == null) { shelf.failed("Episodes are unavailable right now."); return@work }
            shelf.col = 0
            shelf.set(eps, "No episodes listed") { c, x -> c.bind(x.title, x.meta); c.setOnClickListener { playEpisode(x) } }
            stack.relayout()
            if (focus && inRows) { stack.r = stack.shelves.indexOf(shelf); stack.focus() }
        }
    }

    private fun playEpisode(ep: ContentItem) {
        val d = details ?: return
        val (_, s, e) = ep.id.removePrefix("ep:").split(":").let { Triple(it[0], it[1], it[2]) }
        val target = playTargets().firstOrNull() ?: return nav.toast("No app on this TV streams ${item.title}")
        if (target.second == Apps.STREMIO) nav.play(stremio(d, "${d.imdb}:$s:$e"), Apps.STREMIO, "Stremio") else nav.play(plain(d.item), target.second, target.first)
    }

    private var targets: List<Pair<String, String>> = emptyList()
    private fun playTargets() = targets

    private fun stremio(d: Details, video: String? = null) = ContentItem(d.item.id, d.item.title, d.meta, d.item.imageUrl, 
        if (d.type == "movie") "stremio:///detail/movie/${d.imdb}/${d.imdb}" else "stremio:///detail/series/${d.imdb}" + (video?.let { "/$it" } ?: ""),
        Apps.STREMIO, "", d.item.summary)

    private fun addButtons(play: List<Pair<String, String>>?) {
        targets = play ?: emptyList()
        buttons.removeAllViews(); pills.clear()
        fun pill(label: String, action: (Pill) -> Unit) {
            val p = Pill(nav.ctx, label, l.pxf(27f), l.pxf(36f), l.px(34), nav.clock, Colors.FILL, Colors.WHITE, 0.06f)
            p.setOnClickListener { action(p) }
            buttons.addView(p, LinearLayout.LayoutParams(WRAP_CONTENT, l.px(72)).apply { if (pills.isNotEmpty()) leftMargin = l.px(18) })
            pills.add(p)
        }
        play?.forEach { (name, pkg) ->
            pill(if (pkg == Apps.STREMIO) "Open in Stremio" else "▶  Watch on $name") {
                val d = details ?: return@pill
                if (pkg == Apps.STREMIO) nav.play(stremio(d), pkg, name) else nav.play(plain(d.item), pkg, name)
            }
        }
        val u = nav.user
        pill(if (u.contains(UserLibrary.Kind.WATCHLIST, item.id)) "✓  In Watchlist" else "+  Watchlist") { p ->
            val on = u.toggle(UserLibrary.Kind.WATCHLIST, plain(details?.item ?: item)); p.text = if (on) "✓  In Watchlist" else "+  Watchlist"
        }
        pill(if (u.contains(UserLibrary.Kind.FAVOURITES, item.id)) "♥  Favourite" else "♡  Favourite") { p ->
            val on = u.toggle(UserLibrary.Kind.FAVOURITES, plain(details?.item ?: item)); p.text = if (on) "♥  Favourite" else "♡  Favourite"
        }
        b = b.coerceIn(0, pills.size - 1)
        // Rebuilding removed the focused button; give focus to its replacement while this page is the one on screen.
        if (view.isAttachedToWindow && view.visibility == View.VISIBLE && !inRows) pills[b].requestFocus()
    }

    override fun show() { nav.art(item, immediate = true) }

    override fun focus(): Boolean { if (inRows && stack.focus()) return true; inRows = false; pills.getOrNull(b)?.requestFocus(); setShift(); return true }

    override fun key(dx: Int, dy: Int): Boolean {
        if (inRows) {
            if (!stack.key(dx, dy)) { inRows = false; focus() }
            return true
        }
        if (dx != 0) { b = (b + dx).coerceIn(0, pills.size - 1); pills[b].requestFocus() }
        else if (dy > 0 && stack.firstNavigable() >= 0) { inRows = true; stack.focus(); setShift() }
        return true
    }

    override fun back(): Boolean = if (inRows) { inRows = false; focus(); true } else false

    private fun setShift() { shift.target = if (inRows) 1f else 0f; nav.clock.add(this) }

    override fun tick(dt: Float): Boolean {
        shift.step(dt)
        val v = shift.value
        top.translationY = -v * l.pxf(260f); top.alpha = 1f - 0.85f * v
        stack.translationY = -v * l.pxf(260f)
        return !shift.atRest()
    }
}

// ------------------------------------------------------------------------------------------------ Apps

/**
 * Every installed app (TV and phone apps), discovered and refreshed automatically by AppRepository, in one grid
 * under the heading and the app count. Same tiles, artwork and focus behaviour as the top row; holding OK opens
 * app management. Each package appears exactly once (AppRepository lists one launch entry per package).
 */
class AppsScreen(nav: Nav) : Screen(nav), TileHost, FrameClock.Tick {
    override val view = FrameLayout(nav.ctx).apply { clipChildren = false }
    override var dirX = 0; private set
    override var dirY = 0; private set
    private val heading = TextView(nav.ctx).apply { style(Txt.HERO, l.u); text = "Apps" }
    private val count = TextView(nav.ctx).apply { style(Txt.META, l.u, Colors.TEXT_DIM) }
    private val grid = FrameLayout(nav.ctx).apply { clipChildren = false; clipToPadding = false }
    private val none = TextView(nav.ctx).apply { style(Txt.ROW, l.u, Colors.TEXT_DIM); text = "No apps installed"; visibility = View.GONE }
    private val tiles = ArrayList<Tile>()
    private var r = 0; private var c = 0
    private val sy = Spring(0f, Motion.SCROLL_DAMPING, Motion.SCROLL_STIFFNESS, Motion.REST_PX)
    private val gridTop = l.px(330)
    private var built: List<AppEntry>? = null

    init {
        view.addView(heading, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = l.left; topMargin = l.px(150) })
        view.addView(count, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = l.left; topMargin = l.px(244) })
        view.addView(grid, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        view.addView(none, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = l.left; topMargin = gridTop })
    }

    /** Rebuilds the grid when the installed apps changed. */
    fun rebuild(force: Boolean = false) {
        val list = nav.apps.distinctBy { it.packageName }
        if (!force && built == list) return
        val keep = tiles.getOrNull(r * Layout.COLS + c)?.entry?.packageName
        built = list
        grid.removeAllViews(); tiles.clear()
        val extra = l.px(22)
        list.forEachIndexed { i, app ->
            val x = l.left + (i % Layout.COLS) * l.pitchX; val y = gridTop + (i / Layout.COLS) * l.rowPitch
            val label = TextView(nav.ctx).apply { text = app.label; style(Txt.LABEL, l.u); alpha = 0f; gravity = Gravity.CENTER; setShadowLayer(l.pxf(10f), 0f, l.pxf(2f), 0x99000000.toInt()) }
            grid.addView(label, FrameLayout.LayoutParams(l.tileW + 2 * extra, l.labelH).apply { leftMargin = x - extra; topMargin = y + l.tileH + l.labelTop })
            val t = Tile(nav.ctx, app, nav.tileShadow(), label, l, nav.clock, this)
            grid.addView(t, FrameLayout.LayoutParams(l.tileW, l.tileH).apply { leftMargin = x; topMargin = y })
            tiles.add(t)
        }
        val k = keep?.let { p -> list.indexOfFirst { it.packageName == p } } ?: -1
        val idx = if (k >= 0) k else (r * Layout.COLS + c).coerceIn(0, maxOf(0, list.size - 1))
        r = idx / Layout.COLS; c = idx % Layout.COLS
        count.text = "${list.size} installed app${if (list.size == 1) "" else "s"}"
        none.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        place()
    }

    override fun show() { rebuild() }
    override fun featuredBitmap(): Bitmap? = tiles.getOrNull(r * Layout.COLS + c)?.entry?.backdrop

    override fun focus(): Boolean {
        if (tiles.isEmpty()) return false
        tiles[(r * Layout.COLS + c).coerceIn(0, tiles.size - 1)].requestFocus(); place(); return true
    }

    override fun key(dx: Int, dy: Int): Boolean {
        dirX = dx; dirY = dy
        if (tiles.isEmpty()) return dy >= 0
        val rows = (tiles.size + Layout.COLS - 1) / Layout.COLS
        if (dx != 0) { val n = c + dx; if (n in 0 until Layout.COLS && r * Layout.COLS + n < tiles.size) { c = n; focus() }; return true }
        val nr = r + dy
        if (nr < 0) return false                       // up from the first row: the tab bar
        if (nr >= rows) return true
        r = nr; c = c.coerceAtMost(tiles.size - 1 - r * Layout.COLS); focus(); return true
    }

    override fun back(): Boolean = if (r > 0) { r = 0; focus(); true } else false

    private fun place() { sy.target = -(maxOf(0, r - 1) * l.rowPitch).toFloat(); nav.clock.add(this) }
    override fun tick(dt: Float): Boolean { sy.step(dt); grid.translationY = sy.value; return !sy.atRest() }

    override fun onTileFocused(tile: Tile) { nav.artBitmap(tile.entry.backdrop) }
    override fun onTileClicked(tile: Tile) { nav.launch(tile.entry) }
    override fun onTileLongPressed(tile: Tile) { nav.appMenu(tile.entry) }
    override val wantsArt = false
}

// ------------------------------------------------------------------------------------------------ Search

/** Unified search: apps on this TV (instant, local) and movies and series (TMDB, debounced, cached). */
class SearchScreen(nav: Nav) : Screen(nav) {
    override val view = FrameLayout(nav.ctx).apply { clipChildren = false }
    private val query = TextView(nav.ctx).apply { style(Txt.HERO, l.u); text = "" }
    private val hint = TextView(nav.ctx).apply { style(Txt.META, l.u, Colors.TEXT_DIM); text = "Search apps, movies and TV shows" }
    private val keyboard = Keyboard(nav.ctx, l, nav.clock) { k -> type(k) }
    private val stack = ShelfStack(nav.ctx, l, nav.clock, l.px(610))
    private val appsRow = Shelf(nav.ctx, l, nav.clock, "Apps", Look.SMALL_W, Look.SMALL_H)
    private val moviesRow = Shelf(nav.ctx, l, nav.clock, "Movies", Look.SMALL_W, Look.SMALL_H)
    private val tvRow = Shelf(nav.ctx, l, nav.clock, "TV Shows", Look.SMALL_W, Look.SMALL_H)
    private val h = Handler(Looper.getMainLooper())
    private var q = ""
    private var inResults = false
    private val run = Runnable { search() }

    init {
        view.addView(query, FrameLayout.LayoutParams(l.px(1700), WRAP_CONTENT).apply { leftMargin = l.left; topMargin = l.px(140) })
        view.addView(hint, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = l.left; topMargin = l.px(236) })
        view.addView(keyboard, FrameLayout.LayoutParams(MATCH_PARENT, l.px(290)).apply { leftMargin = l.left; topMargin = l.px(296) })
        view.addView(stack, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        listOf(appsRow, moviesRow, tvRow).forEach { s -> stack.add(s); s.visibility = View.GONE }
        moviesRow.loader = { c -> thumb(c) }; tvRow.loader = { c -> thumb(c) }
        stack.onFocus = { _, c -> c.item?.let { if (it.id.startsWith("app:")) (c.tag2 as? AppEntry)?.let { e -> nav.artBitmap(e.backdrop) } else nav.art(it) } }
    }

    private fun thumb(c: Card) { val it = c.item ?: return; nav.content.thumb(Catalog.cardUrl(it.imageUrl)) { b -> if (b != null) c.setBitmap(b) } }

    private fun type(k: String) {
        q = when (k) { Keyboard.DEL -> q.dropLast(1); Keyboard.CLEAR -> ""; Keyboard.SPACE -> if (q.isEmpty() || q.endsWith(" ")) q else "$q "; else -> if (q.length < 40) q + k else q }
        query.text = q
        hint.text = if (q.isEmpty()) "Search apps, movies and TV shows" else ""
        searchApps()
        h.removeCallbacks(run); h.postDelayed(run, 550)       // one request after the user pauses typing
    }

    private fun searchApps() {
        val t = q.trim().lowercase()
        val hits = if (t.isEmpty()) emptyList() else nav.apps.filter { it.label.lowercase().contains(t) }
            .sortedBy { if (it.label.lowercase().startsWith(t)) 0 else 1 }.take(20)
        appsRow.set(hits.map { ContentItem("app:${it.packageName}", it.label, "", "", null, null) }) { c, it ->
            val e = hits.first { a -> "app:${a.packageName}" == it.id }
            c.tag2 = e; c.bind(e.label); c.setBitmap(e.banner); c.setOnClickListener { nav.launch(e) }
        }
        stack.relayout()
    }

    private fun search() {
        val t = q.trim()
        if (t.length < 2 || !nav.catalog.ready) { moviesRow.set(emptyList()) { _, _ -> }; tvRow.set(emptyList()) { _, _ -> }; stack.relayout(); return }
        nav.content.work("search:${t.lowercase()}", { nav.catalog.search(t) }) { res ->
            if (q.trim() != t) return@work
            val (movies, tv) = res ?: (emptyList<ContentItem>() to emptyList())
            moviesRow.set(movies, if (res == null) "Search is unavailable right now" else null) { c, x -> c.bind(x.title, x.meta.split("  ·  ").take(1).joinToString()); c.setOnClickListener { nav.details(x) } }
            tvRow.set(tv) { c, x -> c.bind(x.title, x.meta.split("  ·  ").take(1).joinToString()); c.setOnClickListener { nav.details(x) } }
            stack.relayout()
            if (movies.isEmpty() && tv.isEmpty() && appsRow.cards.isEmpty()) hint.text = "No results for “$t”"
        }
    }

    override fun focus(): Boolean { if (inResults && stack.focus()) return true; inResults = false; keyboard.focus(); return true }

    override fun key(dx: Int, dy: Int): Boolean {
        if (inResults) { if (!stack.key(dx, dy)) { inResults = false; keyboard.focus() }; return true }
        if (!keyboard.key(dx, dy)) {
            if (dy < 0) return false
            if (dy > 0 && stack.firstNavigable() >= 0) { inResults = true; stack.r = stack.firstNavigable(); stack.focus() }
        }
        return true
    }

    override fun back(): Boolean = when {
        inResults -> { inResults = false; keyboard.focus(); true }
        q.isNotEmpty() -> { type(Keyboard.CLEAR); true }
        else -> false
    }
    override fun hide() { h.removeCallbacks(run) }
    /** Another tab was chosen: the search starts empty next time. */
    override fun reset() {
        h.removeCallbacks(run); inResults = false; keyboard.home()
        if (q.isNotEmpty()) type(Keyboard.CLEAR)
        h.removeCallbacks(run)
        moviesRow.set(emptyList()) { _, _ -> }; tvRow.set(emptyList()) { _, _ -> }; stack.relayout()
    }
    override val wantsArt = false
}

// ------------------------------------------------------------------------------------------------ Settings

/** Launcher settings in tvOS form: a list on the right, the focused row's explanation on the left. */
class SettingsScreen(nav: Nav, private val onChanged: (Prefs.Choice) -> Unit) : Screen(nav), FrameClock.Tick {
    override val view = FrameLayout(nav.ctx).apply { clipChildren = false }
    private val title = TextView(nav.ctx).apply { style(Txt.HERO, l.u); text = "Settings" }
    private val about = TextView(nav.ctx).apply { style(Txt.BODY, l.u, Colors.TEXT_DIM); maxLines = 6 }
    private val list = FrameLayout(nav.ctx).apply { clipChildren = false; clipToPadding = false }
    private class Row(val pill: ValuePill, val help: String, val value: () -> String, val act: () -> Unit) { var slide: ((Int) -> Unit)? = null }
    private val rows = ArrayList<Row>()
    private var i = 0
    private val sy = Spring(0f, Motion.SCROLL_DAMPING, Motion.SCROLL_STIFFNESS, Motion.REST_PX)
    private val rowY = HashMap<Row, Int>()
    private val picker = WallpaperPicker(nav)

    init {
        view.addView(title, FrameLayout.LayoutParams(l.px(560), WRAP_CONTENT).apply { leftMargin = l.left; topMargin = l.px(170) })
        view.addView(about, FrameLayout.LayoutParams(l.px(560), WRAP_CONTENT).apply { leftMargin = l.left; topMargin = l.px(290) })
        // Bottom left: the logo and version, so Settings always says what it belongs to.
        val sign = LinearLayout(nav.ctx).apply { orientation = LinearLayout.VERTICAL }
        sign.addView(ImageView(nav.ctx).apply { setImageResource(com.eppletv.launcher.R.drawable.logo_wordmark); scaleType = ImageView.ScaleType.FIT_START; alpha = 0.9f },
            LinearLayout.LayoutParams(l.px(178), l.px(55)))
        sign.addView(TextView(nav.ctx).apply { style(Txt.META, l.u, Colors.TEXT_DIM); text = "Version ${BuildConfig.VERSION_NAME}" },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = l.px(12) })
        view.addView(sign, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { leftMargin = l.left; bottomMargin = l.px(70) })
        // Rows scrolling up fade out before they reach the tab bar instead of being cut in half by it (see [fadeTop]).
        val frame = FrameLayout(nav.ctx).apply { clipChildren = false; clipToPadding = false }
        frame.addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { leftMargin = l.px(40); rightMargin = l.px(40) })
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fadeTop(list, l) }
        view.addView(frame, FrameLayout.LayoutParams(l.px(1140), MATCH_PARENT).apply { leftMargin = l.px(720) })
        view.addView(picker.view, FrameLayout.LayoutParams(l.px(1140), MATCH_PARENT).apply { leftMargin = l.px(720) })
        picker.onDone = { frame.visibility = View.VISIBLE; refreshValues(); focus() }
        picker.onOpen = { frame.visibility = View.INVISIBLE; about.text = "Choose the picture to remove. Back returns to Settings." }
        build()
    }

    private var y = 0
    private fun header(t: String) {
        val v = TextView(nav.ctx).apply { style(Txt.CAPTION, l.u, Colors.TEXT_DIM); text = t }
        y += if (y == 0) l.px(190) else l.px(36)          // the first header clears the tab bar
        // An exact height: a wrap-content view placed below the frame's own height would measure to nothing.
        list.addView(v, FrameLayout.LayoutParams(WRAP_CONTENT, l.px(40)).apply { leftMargin = l.px(30); topMargin = y })
        y += l.px(46)
    }
    private fun row(label: String, help: String, value: () -> String, act: () -> Unit) {
        val p = ValuePill(nav.ctx, l, label, nav.clock)
        val r = Row(p, help, value, act)
        p.setOnClickListener { r.act(); refreshValues() }
        list.addView(p, FrameLayout.LayoutParams(MATCH_PARENT, l.px(80)).apply { topMargin = y })
        rowY[r] = y; y += l.px(92); rows.add(r)
    }
    /** A 0-100 slider row: Left and Right change it in steps of 5 and apply at once. */
    private fun slider(label: String, help: String, get: () -> Int, set: (Int) -> Unit) {
        val p = SliderPill(nav.ctx, l, label, nav.clock).apply { level = get() }
        val r = Row(p, help, { "" }, {})
        r.slide = { d -> val v = (get() + d * 5).coerceIn(0, 100); set(v); p.level = v }
        list.addView(p, FrameLayout.LayoutParams(MATCH_PARENT, l.px(80)).apply { topMargin = y })
        rowY[r] = y; y += l.px(92); rows.add(r)
    }
    private fun choice(c: Prefs.Choice, help: String) = row(c.label, help + if (c.restart) "\n\nEpple TV restarts its screens to apply this." else "",
        { c.options[nav.prefs.get(c)] }) { nav.prefs.put(c, (nav.prefs.get(c) + 1) % c.options.size); onChanged(c) }

    private fun mb(b: Long) = if (b < 1024 * 1024) "${b / 1024} KB" else "%.1f MB".format(b / 1048576.0)

    private fun build() {
        header("Launcher")
        row("Home screen", "Which launcher the Home button opens. Epple TV turns off the Google TV home screen (it stays installed) " +
            "and asks this TV to allow debugging once; choosing the TV's default turns it back on.", {
            if (isHome()) "Epple TV" else "TV's default" }) {
            // Only the change is offered, after "Keep" (first, so a stray OK changes nothing).
            val home = isHome()
            nav.menu("Launcher", listOf(
                (if (home) "Keep Epple TV" else "Keep the TV's Launcher") to {},
                if (home) "Use the TV's Default Launcher" to { nav.switchLauncher(false) } else "Use Epple TV as Launcher" to { nav.switchLauncher(true) }))
        }
        header("Appearance")
        choice(Prefs.CLOCK, "How the time is shown in the corner and in Control Center.")
        choice(Prefs.ROTATE, "How often the artwork behind the top row moves to the next title.")
        slider("Glass", "The material of the tab bar, the top row, the clock and Control Center. Press Left or Right: 0 is clear glass, " +
            "letting what is behind show through with bright edges; 100 is fully frosted.", { nav.prefs.glass }) { v -> nav.prefs.glass = v; GlassStyle.amount = v / 100f }
        row("Wallpapers", "Your own pictures behind Home while the Home tab is selected; a different one each time Home opens. " +
            "Add them from your phone, or copy them from a computer with scripts/wallpapers.sh. Streaming apps show their own titles when you move to them.", {
            nav.wallpapers.count().let { if (it == 0) "None yet" else if (it == 1) "1 picture" else "$it pictures" } }) { wallpaperMenu() }
        row("Profile picture", "The picture in the round badge next to the clock. Add it from your phone.", {
            if (nav.profile.exists()) "Set" else "None" }) { nav.profileMenu() }
        header("Typography")
        choice(Prefs.TEXT, "Makes every label, title and description larger for easier reading from the sofa.")
        choice(Prefs.WEIGHT, "Uses heavier weights of every typeface.")
        header("Content")
        choice(Prefs.REGION, "Which country's streaming catalogues and availability are shown. Automatic uses this TV's region (${Sources.region}).")
        choice(Prefs.INDIAN, "The mix of Bollywood and regional Indian titles against Hollywood titles in the top row's showcases.")
        row("Content providers", "API keys for TMDB, Watchmode, Trakt and YouTube: enter, test, replace or remove them. Keys are encrypted on this TV and shown only masked.", {
            "${com.eppletv.launcher.content.Credentials.connected()} of 4 connected" }) { nav.openSetup(1, one = true) }
        row("Notifications", "Shows other apps' notifications in Epple TV: a short popup at the top right (OK opens it, Back dismisses it), " +
            "a dot on the clock and the list in Control Center. They stay on this TV, in memory only.", {
            if (com.eppletv.launcher.system.NotificationHub.allowed(nav.ctx)) "Allowed" else "Not allowed" }) { nav.allowNotifications() }
        row("TV listings", "Lets Epple TV read what your apps publish to Android TV: Prime Video's own rows and Continue Watching, as Google TV shows them. Nothing leaves the TV.", {
            if (com.eppletv.launcher.content.TvListings.allowed(nav.ctx)) "Allowed" else "Not allowed" }) { nav.askListings() }
        row("Indian languages", "The Indian languages included in Movies, TV Shows and the top row's showcases. Select to choose.", {
            nav.prefs.langs.size.let { if (it == 0) "None" else "$it selected" } }) { nav.openSetup(2, one = true) }
        header("Apps")
        row("Reset top row", "Puts Netflix, Prime Video, Crunchyroll, YouTube, Stremio and Tapmad back in the top row.", { "" }) {
            nav.library.reset(); nav.appsChanged(); nav.toast("Top row reset") }
        row("Android app settings", "Storage, permissions and updates for every installed app.", { "" }) {
            try { nav.ctx.startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { nav.toast("Not available on this TV") } }
        header("Storage")
        row("Clear image cache", "Artwork and title logos stored on this TV. They download again when needed. Kept under 40 MB automatically.", { mb(nav.content.imageCacheBytes()) }) {
            nav.content.clearImages(); nav.toast("Image cache cleared") }
        row("Refresh content", "Fetches every row, showcase and details page again instead of using saved copies.", { mb(nav.content.listCacheBytes()) }) {
            nav.content.clearLists(); nav.toast("Content will refresh") }
        header("Screensaver")
        choice(Prefs.SAVER, "Shows full-screen artwork after Epple TV has been idle. Any button returns where you were.")
        choice(Prefs.SAVER_STYLE, "Slow pan moves gently across each picture; Still shows it unmoving.")
        row("Preview screensaver", "Starts the screensaver now.", { "" }) { nav.startSaver() }
        header("About")
        row("Epple TV", "A private, tvOS-inspired launcher for this TV. Settings > Home screen chooses whether it or Google TV opens on Home.", { "Version ${BuildConfig.VERSION_NAME}" }) {}
        row("Device", "This TV, its Android version and the screen size Epple TV draws at.", { "${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${nav.ctx.resources.displayMetrics.widthPixels}×${nav.ctx.resources.displayMetrics.heightPixels}" }) {}
        row("Diagnostics", "Network requests made since Epple TV opened, and memory in use.", {
            "${nav.content.net.calls.get()} requests · ${(Debug.getPss() / 1024)} MB" }) {}
        row("Run setup again", "Walks through the first-launch setup: providers, what you watch and the Home screen.", { "" }) { nav.openSetup(0, one = false) }
        row("Data attribution", "This product uses the TMDB API but is not endorsed or certified by TMDB.", { "TMDB" }) {}
        refreshValues()
    }

    private fun wallpaperMenu() {
        val n = nav.wallpapers.count()
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("Add from Phone" to { nav.openPhone() })
        if (n > 1) items.add("Show Another Now" to { nav.nextWallpaper(); nav.toast("Wallpaper changed") })
        if (n > 0) items.add("Remove a Picture…" to { picker.open() })
        if (n > 1) items.add("Remove All Pictures" to {
            nav.menu("Remove all $n pictures from this TV?", listOf(
                "Cancel" to {},                                   // first, so a stray OK keeps the pictures
                "Remove All" to { nav.wallpapers.removeAll(); nav.nextWallpaper(); refreshValues(); nav.toast("Pictures removed") }))
        })
        nav.menu("Wallpapers", items)
    }

    private fun isHome(): Boolean {
        val rm = nav.ctx.getSystemService(android.app.role.RoleManager::class.java)
        return rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) && rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)
    }

    private fun refreshValues() { rows.forEach { it.pill.value = it.value() }; rows.getOrNull(i)?.let { about.text = it.help } }

    override fun show() { refreshValues(); fadeTop(list, l) }
    override fun focus(): Boolean { rows.getOrNull(i)?.pill?.requestFocus(); place(); return true }
    override fun key(dx: Int, dy: Int): Boolean {
        if (picker.active) return picker.key(dx, dy)
        if (dy == 0) { if (dx != 0) rows.getOrNull(i)?.slide?.invoke(dx); return true }
        val n = i + dy
        if (n < 0) return false
        if (n >= rows.size) return true
        i = n; focus(); return true
    }
    override fun back(): Boolean = when {
        picker.active -> { picker.close(); true }
        i > 0 -> { i = 0; focus(); true }
        else -> false
    }
    override fun reset() { if (picker.active) picker.close() }

    private fun place() {
        val r = rows.getOrNull(i) ?: return
        about.text = r.help
        sy.target = -maxOf(0, rowY.getValue(r) - l.px(620)).toFloat(); nav.clock.add(this)
    }
    override fun tick(dt: Float): Boolean { sy.step(dt); list.translationY = sy.value; fadeTop(list, l); return !sy.atRest() }
    override val wantsArt = false
}

// ------------------------------------------------------------------------------------------------ Screensaver

/**
 * The screensaver (design "Living wallpaper"): your own Home pictures, one every 20 seconds with a slow crossfade and
 * a slow zoom, never titles or posters. A large clock and the date bottom left, Wi-Fi top left, the Epple TV logo top
 * right, each over a soft radial shade so white stays readable on any picture. Without pictures it shows the clock on
 * graphite. Only two pictures are in memory; pictures are decoded on a worker. Any button dismisses it.
 */
class Screensaver(private val nav: Nav) : FrameClock.Tick {
    private val l get() = nav.l
    val view = FrameLayout(nav.ctx).apply { setBackgroundColor(0xFF0E0E10.toInt()); visibility = View.GONE; alpha = 0f }
    private val a = ImageView(nav.ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val b = ImageView(nav.ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
    private val time = TextView(nav.ctx).apply {
        typeface = com.eppletv.launcher.ui.Type.of(300, com.eppletv.launcher.ui.Type.Face.NUM); setTextColor(Colors.WHITE)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, l.pxf(168f)); letterSpacing = -0.02f; includeFontPadding = false
        isSingleLine = true; ellipsize = null
        setShadowLayer(l.pxf(24f), 0f, 0f, 0x66000000) }
    private val date = TextView(nav.ctx).apply {
        typeface = com.eppletv.launcher.ui.Type.of(500); setTextColor(0xEBFFFFFF.toInt())
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, l.pxf(34f)); setShadowLayer(l.pxf(16f), 0f, 0f, 0x80000000.toInt()) }
    private val wifi = WifiBadge(nav.ctx, l)
    private val logo = ImageView(nav.ctx).apply { setImageResource(com.eppletv.launcher.R.drawable.logo_wordmark); scaleType = ImageView.ScaleType.FIT_END }
    private val h = Handler(Looper.getMainLooper())
    private val fade = Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 0.3f)
    private val show = Spring(0f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS)
    private var files: List<java.io.File> = emptyList()
    private var n = 0
    private var front = a
    private var t = 0f
    var showing = false; private set
    var clockText: () -> String = { "" }
    /** The Wi-Fi network's name, or null when the TV isn't on Wi-Fi (then the badge hides). */
    var wifiName: () -> String? = { null }

    init {
        view.addView(a, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)); view.addView(b, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Soft radial shades under each corner's white text and marks (and a gentle bottom gradient under the clock).
        view.addView(Shade(nav.ctx, l), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val clock = LinearLayout(nav.ctx).apply { orientation = LinearLayout.VERTICAL }
        // A fixed, generous width: a wrap-content clock kept the width of the time it first showed ("7:28") and cut a
        // wider one ("8:01" showed as "8:0").
        clock.addView(time, LinearLayout.LayoutParams(l.px(900), WRAP_CONTENT)); clock.addView(date, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = l.px(6) })
        view.addView(clock, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { leftMargin = l.px(120); bottomMargin = l.px(100) })
        view.addView(wifi, FrameLayout.LayoutParams(WRAP_CONTENT, l.px(56), Gravity.TOP or Gravity.START).apply { leftMargin = l.px(110); topMargin = l.px(80) })
        view.addView(logo, FrameLayout.LayoutParams(l.px(210), l.px(65), Gravity.TOP or Gravity.END).apply { rightMargin = l.px(120); topMargin = l.px(76) })
    }

    private val next = object : Runnable { override fun run() { advance(); h.postDelayed(this, 20_000) } }
    private val minute = object : Runnable { override fun run() { stamp(); h.postDelayed(this, 15_000) } }

    private fun stamp() {
        time.text = clockText().replace(Regex("\\s*(am|pm|AM|PM)$"), "")
        date.text = java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale.getDefault()).format(java.util.Date())
        val w = wifiName(); wifi.visibility = if (w == null) View.GONE else View.VISIBLE; wifi.name = w ?: ""
    }

    fun start() {
        if (showing) return
        files = nav.wallpapers.files().shuffled()
        showing = true; n = 0
        view.visibility = View.VISIBLE; show.target = 1f
        stamp(); h.post(minute)
        advance(); h.postDelayed(next, 20_000)
        nav.clock.add(this)
    }

    fun stop() {
        if (!showing) return
        showing = false; h.removeCallbacks(next); h.removeCallbacks(minute); show.target = 0f; nav.clock.add(this)
    }

    private fun advance() {
        val f = files.getOrNull(n++ % maxOf(1, files.size)) ?: return
        nav.content.run({ nav.wallpapers.load(f, scrim = false) ?: throw IllegalStateException() }) { bmp ->
            if (!showing || bmp == null) return@run
            val back = if (front === a) b else a
            back.setImageBitmap(bmp); back.alpha = 0f; back.scaleX = 1f; back.scaleY = 1f; back.translationX = 0f
            fade.snapTo(0f); fade.target = 1f; t = 0f
            front = back; nav.clock.add(this)
        }
    }

    override fun tick(dt: Float): Boolean {
        show.step(dt); fade.step(dt)
        view.alpha = show.value
        front.alpha = fade.value
        val other = if (front === a) b else a
        if (fade.atRest() && other.drawable != null) other.setImageDrawable(null)        // release the previous picture
        val pan = showing && nav.prefs.get(Prefs.SAVER_STYLE) == 0
        if (pan) { t += dt; val k = (t / 20f).coerceAtMost(1f); val s = 1f + 0.07f * k; front.scaleX = s; front.scaleY = s; front.translationX = -l.pxf(36f) * k }
        if (!showing && show.atRest()) { view.visibility = View.GONE; a.setImageDrawable(null); b.setImageDrawable(null); return false }
        return pan || !show.atRest() || !fade.atRest()       // a still screensaver requests no frames between pictures
    }

    /** Radial shades in three corners and a low gradient: drawn once per size into one bitmap. */
    @android.annotation.SuppressLint("ViewConstructor")
    private class Shade(ctx: android.content.Context, private val l: Layout) : View(ctx) {
        private var bmp: android.graphics.Bitmap? = null
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            if (w <= 0 || h <= 0) return
            bmp?.recycle()
            bmp = android.graphics.Bitmap.createBitmap(w / 2, h / 2, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
                val c = android.graphics.Canvas(b); val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                fun spot(x: Float, y: Float, r: Float, a: Int) {
                    p.shader = android.graphics.RadialGradient(x, y, r, intArrayOf(a shl 24, ((a * 0.45f).toInt()) shl 24, 0), floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
                    c.drawRect(0f, 0f, b.width.toFloat(), b.height.toFloat(), p)
                }
                val sx = b.width.toFloat(); val sy = b.height.toFloat()
                p.shader = android.graphics.LinearGradient(0f, sy * 0.55f, 0f, sy, 0, 0x8C000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, sx, sy, p)
                spot(sx * 0.2f, sy * 0.86f, sx * 0.34f, 0x66)           // clock and date
                spot(sx * 0.08f, sy * 0.1f, sx * 0.16f, 0x59)           // Wi-Fi
                spot(sx * 0.9f, sy * 0.1f, sx * 0.2f, 0x59)             // logo
            }
        }
        override fun onDraw(c: android.graphics.Canvas) { bmp?.let { c.drawBitmap(it, null, android.graphics.Rect(0, 0, width, height), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)) } }
    }
}

/** Wi-Fi mark and the network's name, for the screensaver's top-left corner. */
@android.annotation.SuppressLint("ViewConstructor")
class WifiBadge(ctx: android.content.Context, private val l: Layout) : View(ctx) {
    var name = ""
        set(v) { if (field != v) { field = v; requestLayout(); invalidate() } }
    private val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEBFFFFFF.toInt(); textSize = l.pxf(28f); typeface = com.eppletv.launcher.ui.Type.of(500)
        setShadowLayer(l.pxf(10f), 0f, 0f, 0x80000000.toInt()) }
    private val icon get() = l.pxf(40f)
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((icon + l.pxf(16f) + text.measureText(name) + l.pxf(12f)).toInt(), MeasureSpec.getSize(h))
    override fun onDraw(c: android.graphics.Canvas) {
        Glyphs.draw(Glyphs.WIFI, c, icon / 2f + l.pxf(4f), height / 2f, icon, Colors.WHITE, stroke, fill)
        c.drawText(name, icon + l.pxf(16f), height / 2f - (text.descent() + text.ascent()) / 2f, text)
    }
}

// ------------------------------------------------------------------------------------------------ Wallpaper picker

/**
 * Settings > Wallpapers > Remove a Picture: the pictures as a grid of thumbnails, so it is clear which one goes.
 * Thumbnails are decoded small on a worker and released when the picker closes. OK asks before deleting.
 */
class WallpaperPicker(private val nav: Nav) : FrameClock.Tick {
    private val l get() = nav.l
    val view = FrameLayout(nav.ctx).apply { clipChildren = false; clipToPadding = false; visibility = View.GONE }
    private val grid = FrameLayout(nav.ctx).apply { clipChildren = false; clipToPadding = false }
    private val heading = TextView(nav.ctx).apply { style(Txt.CAPTION, l.u, Colors.TEXT_DIM) }
    private val tiles = ArrayList<Thumb>()
    private var files: List<java.io.File> = emptyList()
    private var i = 0
    private val sy = Spring(0f, Motion.SCROLL_DAMPING, Motion.SCROLL_STIFFNESS, Motion.REST_PX)
    private val main = Handler(Looper.getMainLooper())
    private var gen = 0
    var active = false; private set
    var onDone: () -> Unit = {}
    var onOpen: () -> Unit = {}

    private val tw get() = l.px(336); private val th get() = l.px(189); private val gap get() = l.px(30)

    init {
        val frame = FrameLayout(nav.ctx).apply { clipChildren = false; clipToPadding = false }
        frame.addView(grid, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { leftMargin = l.px(70) })
        grid.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fadeTop(grid, l) }
        view.addView(frame, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    fun open() {
        files = nav.wallpapers.files()
        if (files.isEmpty()) return
        active = true; i = 0; sy.snapTo(0f); grid.translationY = 0f
        build(); view.visibility = View.VISIBLE; onOpen(); focus()
    }

    fun close() {
        if (!active) return
        active = false; gen++
        view.visibility = View.GONE
        tiles.forEach { it.release() }; tiles.clear(); grid.removeAllViews()
        onDone()
    }

    private fun build() {
        tiles.forEach { it.release() }; tiles.clear(); grid.removeAllViews()
        heading.text = "REMOVE A PICTURE  ·  ${files.size}"
        grid.addView(heading, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = l.px(190) })
        val cur = nav.wallpapers.current()
        files.forEachIndexed { n, f ->
            val t = Thumb(nav.ctx, l, nav.clock, f.name == cur)
            t.setOnClickListener { confirm(n) }
            grid.addView(t, FrameLayout.LayoutParams(tw, th).apply {
                leftMargin = (n % COLS) * (tw + gap); topMargin = l.px(250) + (n / COLS) * (th + gap) })
            tiles.add(t)
        }
        val g = ++gen
        val w = tw; val h = th
        Thread {
            files.forEachIndexed { n, f ->
                if (g != gen) return@Thread
                val b = nav.wallpapers.thumb(f, w, h)
                main.post { if (g == gen) tiles.getOrNull(n)?.set(b) else b?.recycle() }
            }
        }.apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    private fun confirm(n: Int) {
        val f = files.getOrNull(n) ?: return
        nav.menu("Remove this picture?", listOf(
            "Cancel" to { focus() },                                  // first, so a stray OK keeps it
            "Remove" to {
                val wasShown = f.name == nav.wallpapers.current()
                nav.wallpapers.remove(f)
                nav.toast("Picture removed")
                if (wasShown) nav.nextWallpaper()
                files = nav.wallpapers.files()
                if (files.isEmpty()) close()
                else { i = i.coerceAtMost(files.size - 1); build(); focus() }
            }))
    }

    fun focus() { tiles.getOrNull(i)?.requestFocus(); place() }

    fun key(dx: Int, dy: Int): Boolean {
        val n = files.size; if (n == 0) return true
        val col = i % COLS
        val next = when {
            dx > 0 -> if (col < COLS - 1 && i + 1 < n) i + 1 else i
            dx < 0 -> if (col > 0) i - 1 else i
            dy > 0 -> if (i + COLS < n) i + COLS else if (i / COLS < (n - 1) / COLS) n - 1 else i
            dy < 0 -> if (i - COLS >= 0) i - COLS else return false.also { close() }   // up from the first row: back to Settings
            else -> i
        }
        i = next; focus(); return true
    }

    private fun place() {
        val row = i / COLS
        sy.target = -maxOf(0, l.px(250) + row * (th + gap) - l.px(560)).toFloat(); nav.clock.add(this)
    }
    override fun tick(dt: Float): Boolean { sy.step(dt); grid.translationY = sy.value; fadeTop(grid, l); return !sy.atRest() }

    /** One thumbnail: rounded picture, a white ring and a slight lift when focused, "On screen" on the current one. */
    @android.annotation.SuppressLint("ViewConstructor")
    private class Thumb(ctx: android.content.Context, private val l: Layout, private val clock: FrameClock, private val shown: Boolean) : View(ctx), FrameClock.Tick {
        private var b: Bitmap? = null
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; color = Colors.WHITE }
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
        private val badge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
        private val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Colors.WHITE; textSize = l.pxf(20f); typeface = com.eppletv.launcher.ui.Type.of(600, com.eppletv.launcher.ui.Type.Face.UI) }
        private val r = android.graphics.RectF()
        private val f = Spring(0f, 0.8f, 380f)
        init { isFocusable = true }
        fun set(bm: Bitmap?) { b = bm; paint.shader = bm?.let { android.graphics.BitmapShader(it, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP) }; invalidate() }
        fun release() { b?.recycle(); b = null; paint.shader = null }
        override fun onFocusChanged(g: Boolean, d: Int, p: android.graphics.Rect?) { super.onFocusChanged(g, d, p); f.target = if (g) 1f else 0f; clock.add(this) }
        override fun tick(dt: Float): Boolean {
            f.step(dt); val s = 1f + 0.06f * f.value; scaleX = s; scaleY = s
            translationZ = l.pxf(12f) * f.value; invalidate(); return !f.atRest()
        }
        override fun onDraw(c: android.graphics.Canvas) {
            val rad = l.pxf(18f)
            r.set(0f, 0f, width.toFloat(), height.toFloat())
            c.drawRoundRect(r, rad, rad, if (b != null) paint else fill)
            if (shown) {
                val label = "On screen"; val pad = l.pxf(12f); val tw = text.measureText(label)
                r.set(pad, pad, pad + tw + pad * 2, pad + l.pxf(36f))
                c.drawRoundRect(r, r.height() / 2, r.height() / 2, badge)
                c.drawText(label, pad * 2, r.centerY() + text.textSize * 0.35f, text)
            }
            if (f.value > 0.01f) {
                val sw = l.pxf(4f); ring.strokeWidth = sw; ring.alpha = (255 * f.value.coerceIn(0f, 1f)).toInt()
                r.set(sw / 2, sw / 2, width - sw / 2, height - sw / 2)
                c.drawRoundRect(r, rad - sw / 2, rad - sw / 2, ring)
            }
        }
    }

    companion object { private const val COLS = 3 }
}

/**
 * A scrolling settings list never shows a control cut in half under the tab bar: anything whose top edge has
 * scrolled above the line just below the bar is hidden, and the rows just under the line fade in over a short
 * distance. Plain alpha per child: no offscreen layer, no clipping.
 */
fun fadeTop(list: android.view.ViewGroup, l: Layout, line: Int = l.px(150), fade: Int = l.px(70)) {
    val dy = list.translationY
    for (k in 0 until list.childCount) {
        val v = list.getChildAt(k)
        v.alpha = ((v.top + dy - line) / fade).coerceIn(0f, 1f)
    }
}

/** A settings row with a 0-100 slider drawn on its right: track, filled part and knob, with the number beside it. */
@android.annotation.SuppressLint("ViewConstructor")
class SliderPill(ctx: android.content.Context, private val l: Layout, label: String, clock: FrameClock) : ValuePill(ctx, l, label, clock) {
    var level = 0
        set(v) { field = v.coerceIn(0, 100); invalidate() }
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val num = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = l.pxf(27f) * com.eppletv.launcher.ui.Type.scale; typeface = com.eppletv.launcher.ui.Type.of(600, com.eppletv.launcher.ui.Type.Face.NUM)
        textAlign = android.graphics.Paint.Align.RIGHT }
    private val r = android.graphics.RectF()
    override fun onDraw(c: android.graphics.Canvas) {
        super.onDraw(c)
        val col = currentTextColor
        val right = width - l.pxf(30f); val tw = l.pxf(260f); val th = l.pxf(6f); val cy = height / 2f
        val left = right - tw
        p.color = col; p.alpha = 60
        r.set(left, cy - th / 2, right, cy + th / 2); c.drawRoundRect(r, th / 2, th / 2, p)
        val x = left + tw * level / 100f
        p.alpha = 255
        r.set(left, cy - th / 2, x, cy + th / 2); c.drawRoundRect(r, th / 2, th / 2, p)
        c.drawCircle(x, cy, l.pxf(11f), p)
        num.color = col; num.alpha = 200
        c.drawText("$level", left - l.pxf(24f), cy - (num.descent() + num.ascent()) / 2f, num)
    }
}
