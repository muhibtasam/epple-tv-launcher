package com.eppletv.launcher.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.eppletv.launcher.apps.AppEntry
import com.eppletv.launcher.apps.Library
import com.eppletv.launcher.content.Catalog
import com.eppletv.launcher.content.ContentItem
import com.eppletv.launcher.content.ContentRepository
import com.eppletv.launcher.content.RowSpec
import com.eppletv.launcher.content.UserLibrary
import com.eppletv.launcher.motion.FrameClock
import com.eppletv.launcher.motion.Motion
import com.eppletv.launcher.motion.Spring
import com.eppletv.launcher.ui.Colors
import com.eppletv.launcher.ui.Txt
import com.eppletv.launcher.ui.style

/** What every section can ask of the launcher shell. */
interface Nav {
    val ctx: Context
    val l: Layout
    val clock: FrameClock
    val content: ContentRepository
    val catalog: Catalog
    val user: UserLibrary
    val prefs: Prefs
    val library: Library
    val apps: List<AppEntry>
    fun art(item: ContentItem?, immediate: Boolean = false)   // backdrop follows the focused title
    fun artBitmap(b: Bitmap)
    fun details(item: ContentItem)
    fun launch(e: AppEntry)
    fun play(item: ContentItem, pkg: String, appName: String)
    fun appMenu(e: AppEntry)
    fun menu(title: String, items: List<Pair<String, () -> Unit>>)
    fun toast(msg: String)
    fun appsChanged()
    fun restartUi()
    fun startSaver()
    fun tileShadow(): Bitmap
    /** Setup at [step]; [one] = only that step (from Settings), otherwise the whole flow again. */
    fun openSetup(step: Int, one: Boolean)
    /** Home's own pictures, and the phone screen that adds them. */
    val wallpapers: Wallpapers
    /** The screensaver's own pictures (Settings > Screensaver pictures), when it doesn't use the wallpapers. */
    val saverPictures: Wallpapers
    val profile: Profile
    /** The profile picture changed (set or removed). */
    fun profileChanged()
    fun profileMenu()
    fun openPhone()
    /** Shows another wallpaper now ([newest]: the most recently added one). */
    fun nextWallpaper(newest: Boolean = false)
    /** Making Epple TV the TV's launcher, and giving it back (Settings > Launcher). */
    val launcher: com.eppletv.launcher.system.LauncherMode
    fun switchLauncher(epple: Boolean, done: (() -> Unit)? = null)
    /** Asks the TV to let Epple TV read its listings (Prime Video's rows, Continue Watching). */
    fun askListings()
    /** Lets Epple TV read other apps' notifications (Settings > Notifications, Control Center). */
    fun allowNotifications()
}

/** One section of the launcher. Focus inside a section is explicit and survives leaving and returning. */
abstract class Screen(protected val nav: Nav) {
    protected val l get() = nav.l
    abstract val view: View
    open fun show() {}
    open fun hide() {}
    /** The section was left for another tab: back to its starting state (first titles, empty search). */
    open fun reset() {}
    /** Puts focus back where the user left it. */
    abstract fun focus(): Boolean
    /** D-pad; returns false when the user leaves through the top edge (to the tab bar). */
    abstract fun key(dx: Int, dy: Int): Boolean
    /** BACK inside the section; false = not handled (the shell moves to the tab bar). */
    open fun back(): Boolean = false
    /** Rows show the hero-style info block for the focused title. */
    open val wantsArt = true
    /** The title whose artwork this section shows when it opens (null: the section's neutral backdrop). */
    open fun featured(): ContentItem? = null
    /** A ready bitmap for this section's backdrop instead of a title (e.g. the focused app's colours). */
    open fun featuredBitmap(): Bitmap? = null
}

/** Title block over the artwork: eyebrow, logo (or typeset title), metadata and a two-line summary. */
@SuppressLint("ViewConstructor")
class InfoBlock(ctx: Context, private val l: Layout, private val clock: FrameClock, private val content: ContentRepository, summaryLines: Int = 2) :
    LinearLayout(ctx), FrameClock.Tick {
    private val eyebrow = TextView(ctx).apply { style(Txt.CAPTION, l.u, 0xD9FFFFFF.toInt()); shadow(this, 8f) }
    private val title = TextView(ctx).apply { style(Txt.HERO, l.u); shadow(this, 20f) }
    private val logo = ImageView(ctx).apply { adjustViewBounds = true; maxWidth = l.px(720); maxHeight = l.px(180); scaleType = ImageView.ScaleType.FIT_START; visibility = GONE }
    private val meta = MetaRow(ctx, l)
    val summary = TextView(ctx).apply { style(Txt.BODY, l.u, 0xD9FFFFFF.toInt()); maxLines = summaryLines; shadow(this, 10f) }
    private val a = Spring(1f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 2f)
    private var shownId: String? = null

    private fun shadow(t: TextView, r: Float) = t.setShadowLayer(l.pxf(r), 0f, l.pxf(2f), 0x99000000.toInt())

    init {
        orientation = VERTICAL
        addView(eyebrow, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(title, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = l.px(10) })
        addView(logo, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = l.px(16); bottomMargin = l.px(4) })
        addView(meta, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = l.px(12) })
        addView(summary, LayoutParams(l.px(860), WRAP_CONTENT).apply { topMargin = l.px(12) })
    }

    fun show(item: ContentItem?, kind: String) {
        if (item == null) { visibility = INVISIBLE; return }
        visibility = VISIBLE
        val fresh = item.id != shownId
        shownId = item.id
        eyebrow.text = kind; eyebrow.visibility = if (kind.isEmpty()) GONE else VISIBLE
        title.text = item.title; meta.set(item.meta); summary.text = item.summary
        summary.visibility = if (item.summary.isEmpty()) GONE else VISIBLE
        if (!fresh) return
        logo.setImageBitmap(null); logo.visibility = GONE; title.visibility = VISIBLE
        // TMDB titles, and titles from apps' rows and Watch Next (their logo is found by name): the logo when there is one.
        if (Regex("^(movie|tv|wn|app):").containsMatchIn(item.id) || item.logoUrl.isNotEmpty()) {
            val id = item.id
            var answered = false
            content.logo(item) { b ->
                answered = true
                if (shownId != id) return@logo
                if (b != null) { logo.setImageBitmap(b); logo.visibility = VISIBLE; title.visibility = GONE } else title.visibility = VISIBLE
            }
            // Logo still loading: hold the typeset title back briefly instead of flashing it and swapping it out.
            if (!answered) { title.visibility = INVISIBLE; postDelayed({ if (shownId == id && logo.visibility != VISIBLE) title.visibility = VISIBLE }, 350) }
        }
        a.value = 0.35f; a.target = 1f; clock.add(this)
    }

    override fun tick(dt: Float): Boolean { a.step(dt); alpha = a.value; return !a.atRest() }
}

/** A source of one row: [load] calls back with the items, or null on failure. */
class RowSource(val title: String, val dynamic: Boolean = false, val emptyText: String? = null, val load: ((List<ContentItem>?) -> Unit) -> Unit)

/**
 * Browse layout shared by Home's feed, Movies, TV Shows and Library: the focused title's artwork fills the screen,
 * its info sits top-left, rows of cards below. Rows load lazily as the user approaches them and are de-duplicated
 * against the rows above. OK opens the details page; holding OK offers Watchlist / Favourites.
 */
open class BrowseScreen(nav: Nav, private val sources: List<RowSource>, private val emptyAll: String? = null) : Screen(nav) {
    final override val view = FrameLayout(nav.ctx).apply { clipChildren = false }
    protected val info = InfoBlock(view.context, l, nav.clock, nav.content)
    protected val stack = ShelfStack(view.context, l, nav.clock, l.px(Look.ROWS_TOP))
    private val empty = TextView(view.context).apply { style(Txt.ROW, l.u, Colors.TEXT_DIM); text = emptyAll ?: ""; gravity = Gravity.CENTER; visibility = View.GONE }
    private val requested = HashSet<Int>()
    private var built = false
    private var version = -1
    private var active = false

    init {
        view.addView(info, FrameLayout.LayoutParams(l.px(1300), WRAP_CONTENT).apply { leftMargin = l.left; topMargin = l.px(Look.INFO_TOP) })
        view.addView(stack, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        view.addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        stack.onFocus = { s, c -> info.show(c.item, ""); nav.art(c.item) }
        stack.onRow = { r -> for (i in r..minOf(sources.size - 1, r + 3)) load(i) }
    }

    private fun build() {
        built = true
        sources.forEach { src -> stack.add(Shelf(view.context, l, nav.clock, src.title, poster = true).apply { loader = { c -> loadThumb(c) } }) }
        // The first rows, plus every row built from the user's own lists (local, instant).
        sources.forEachIndexed { i, s -> if (i < 3 || s.dynamic) load(i) }
    }

    protected open fun loadThumb(c: Card) {
        val it = c.item ?: return
        nav.content.thumb(Catalog.cardUrl(it.imageUrl)) { b -> if (b != null) c.setBitmap(b) }
        if (c is PosterCard && it.posterUrl.isNotEmpty()) nav.content.thumb(it.posterUrl) { b -> if (b != null) c.setPoster(b) }
    }

    /** Leaving the section: every row goes back to its first title and the first row comes back to the top. */
    override fun reset() { stack.reset() }

    protected fun reload(i: Int) { if (built) load(i, force = true) }
    /** Rows whose next load starts again at their first title (a refreshed Continue Watching shows its new first). */
    protected val toFirst = HashSet<Int>()

    private fun load(i: Int, force: Boolean = false) {
        if (!force && !requested.add(i)) return
        val src = sources[i]; val shelf = stack.shelves[i]
        src.load { list ->
            if (list == null) { shelf.failed("Couldn't load this row. It will retry when you come back."); requested.remove(i); return@load }
            // de-duplicate against the rows above that are already showing
            val seen = HashSet<String>()
            for (j in 0 until i) stack.shelves[j].cards.forEach { c -> c.item?.id?.let { seen.add(it) } }
            val items = list.filter { it.id !in seen }
            val hadFocus = shelf.hasFocus()                    // a row rebuilt under the focus keeps it
            nav.content.prefetchLogos(items, 4)               // the first titles' logos are ready when the row is reached
            shelf.set(items, src.emptyText) { c, it -> c.bind(it.title, it.meta.split("  ·  ").take(2).joinToString(" · ")); wire(c) }
            if (toFirst.remove(i)) shelf.reset()
            stack.relayout()
            if (hadFocus) stack.focus()
            updateEmpty()
            // An empty (hidden) row must not stall lazy loading: fetch the next one now.
            if (shelf.visibility == View.GONE) (i + 1 until sources.size).firstOrNull { it !in requested }?.let { load(it) }
            // The first row to arrive gives this section its artwork if nothing is focused here yet.
            if (active && !view.hasFocus() && i == stack.firstNavigable()) items.firstOrNull()?.let { nav.art(it, immediate = true) }
            // The first row arriving after a lower one (Continue Watching reads the TV's lists) still takes focus,
            // as long as the user hasn't moved yet.
            if (i < stack.r && !moved && items.isNotEmpty() && view.hasFocus()) { stack.r = i; stack.focus() }
            else if (stack.current == null || stack.r == i || !stack.shelves[stack.r].let { it.visibility == View.VISIBLE && it.cards.isNotEmpty() }) {
                if (view.hasFocus() || focusWanted) { focusWanted = false; stack.focus() }
            }
        }
    }

    private var focusWanted = false

    private fun updateEmpty() {
        val any = stack.shelves.any { it.visibility == View.VISIBLE && it.cards.isNotEmpty() }
        val allLoaded = stack.shelves.all { it.loaded }
        empty.visibility = if (!any && allLoaded && emptyAll != null) View.VISIBLE else View.GONE
        if (!any && allLoaded) info.show(null, "")
    }

    protected open fun wire(c: Card) {
        c.setOnClickListener { c.item?.let { nav.details(it) } }
        c.setOnLongClickListener { c.item?.let { itemMenu(it) }; true }
    }

    protected fun itemMenu(it: ContentItem) {
        val u = nav.user
        val inList = u.contains(UserLibrary.Kind.WATCHLIST, it.id); val fav = u.contains(UserLibrary.Kind.FAVOURITES, it.id)
        nav.menu(it.title, listOf(
            "Details" to { nav.details(it) },
            (if (inList) "Remove from Watchlist" else "Add to Watchlist") to { u.toggle(UserLibrary.Kind.WATCHLIST, it); Unit },
            (if (fav) "Remove from Favourites" else "Add to Favourites") to { u.toggle(UserLibrary.Kind.FAVOURITES, it); Unit },
        ) + extraMenu(it))
    }

    protected open fun extraMenu(it: ContentItem): List<Pair<String, () -> Unit>> = emptyList()

    override fun featured(): ContentItem? = stack.current?.item ?: stack.shelves.getOrNull(stack.firstNavigable())?.item

    override fun show() {
        active = true; moved = false
        if (!built) build()
        // Rows built from the user's own lists are rebuilt when those lists changed.
        if (version != nav.user.version) {
            version = nav.user.version
            sources.forEachIndexed { i, s -> if (s.dynamic && i in requested) load(i, force = true) }
        }
        stack.current?.item?.let { info.show(it, "") }
        stack.trim()                                   // artwork for the rows around the focus comes back
    }

    override fun focus(): Boolean { val ok = stack.focus(); if (!ok) { focusWanted = true; view.requestFocus() }; return ok }
    override fun hide() { active = false; stack.releaseAll() }      // a hidden section holds no artwork
    private var moved = false
    override fun key(dx: Int, dy: Int): Boolean { moved = true; return stack.key(dx, dy) }
    override fun back(): Boolean = if (!stack.atFirst()) { stack.r = stack.firstNavigable(); stack.focus(); true } else false
}

/** Adapters from the catalogue and the user's lists to row sources. */
object Rows {
    fun catalog(nav: Nav, spec: RowSpec) = RowSource(spec.title) { cb -> nav.content.work("row:${spec.path}", { nav.catalog.row(spec) }, cb) }
    fun user(nav: Nav, title: String, kind: UserLibrary.Kind, empty: String? = null) =
        RowSource(title, dynamic = true, emptyText = empty) { cb -> cb(nav.user.items(kind)) }
}

/** Home's rows under the top row: Continue Watching, Recently Added, Trending, For You and the Watchlist. */
class HomeFeed(nav: Nav) : BrowseScreen(nav, listOf(
    // What you left half-way in any app (the TV's own Watch Next list, as Google TV shows it), then titles opened from Epple TV.
    RowSource("Continue Watching", dynamic = true) { cb ->
        // Twice: at once with the apps' own pictures, then again once TMDB posters are found (cached after the first time).
        for (posters in listOf(false, true)) nav.content.run({ ContinueWatching.load(nav.ctx, nav.content.net, posters) }) { tv ->
            cb(ContinueWatching.merge(nav.ctx, tv.orEmpty(), nav.user.items(UserLibrary.Kind.CONTINUE)))
        }
    },
    // What every streaming app with its own Android TV rows recommends (Prime Video, Crunchyroll, Apple TV...), a few each.
    RowSource("From Your Apps", dynamic = true) { cb ->
        val ch = com.eppletv.launcher.content.Sources.channels(nav.ctx)
        nav.content.run({
            val per = ch.available.map { pkg -> try { ch.load(nav.content.net, pkg).take(5) } catch (_: Exception) { emptyList() } }
            (0 until (per.maxOfOrNull { it.size } ?: 0)).flatMap { i -> per.mapNotNull { it.getOrNull(i) } }
        }) { cb(it.orEmpty()) }
    },
    Rows.user(nav, "Your Watchlist", UserLibrary.Kind.WATCHLIST),
)) {
    override fun wire(c: Card) {
        super.wire(c)
        // Continue Watching resumes in the app it was opened in.
        c.setOnClickListener {
            val it = c.item ?: return@setOnClickListener
            val pkg = it.linkPkg
            if (stack.current?.title == "Continue Watching" && pkg != null) nav.play(it, pkg, it.kind.removePrefix("Continue in ")) else nav.details(it)
        }
    }
    /** The TV's Watch Next list changes outside Epple TV: it is read again every time Home's rows open. */
    private var shownOnce = false
    override fun show() { super.show(); if (shownOnce) { toFirst += 0; reload(0) }; shownOnce = true }
    /** Back from a title or app: only Continue Watching is read again (its order changed) and starts at its new first. */
    fun refreshContinue() { toFirst += 0; reload(0) }

    override fun extraMenu(it: ContentItem) =
        if (nav.user.contains(UserLibrary.Kind.CONTINUE, it.id)) listOf("Remove from Continue Watching" to { nav.user.remove(UserLibrary.Kind.CONTINUE, it.id) }) else emptyList()
}

/**
 * Continue Watching from the TV itself: the system Watch Next list (where Prime Video, YouTube and others note what you
 * left half-way) plus apps' own "Continue Watching" channels (Stremio), newest first, at most two per app so one show
 * doesn't fill the row. Opening one goes back into that app at that title (its own link).
 */
object ContinueWatching {
    private const val PER_APP = 2; private const val MAX = 12
    fun norm(t: String) = t.trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")

    /**
     * The apps' rows and the titles opened from Epple TV in one list, the most recent first. Each title's time is the
     * newest of: when the TV's Watch Next says it was watched, and when it was last watched from Epple TV (by app and
     * name, see Recency). Titles with neither follow their app's last use, in the app's own order, behind any title
     * of that app that has its own time; titles never watched from here keep the list's order at the end.
     */
    fun merge(ctx: android.content.Context, tv: List<ContentItem>, mine: List<ContentItem>): List<ContentItem> {
        val R = com.eppletv.launcher.content.Recency
        val keyed = ArrayList<Pair<ContentItem, Long>>(); val seen = HashSet<String>()
        val rank = HashMap<String, Int>()
        for (it in tv) if (seen.add(norm(it.title))) {
            val k = rank.merge(it.linkPkg ?: "", 1, Int::plus)!!
            val own = maxOf(engaged[it.id] ?: 0L, R.titleTime(ctx, it.linkPkg, it.title))
            keyed += it to (if (own > 0) own else R.appTime(ctx, it.linkPkg) - 1000L * k)
        }
        mine.forEachIndexed { i, it -> if (seen.add(norm(it.title))) keyed += it to R.titleTime(ctx, it.linkPkg, it.title).let { t -> if (t > 0) t else -1L - i } }
        val sorted = keyed.sortedByDescending { it.second }
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT)
        android.util.Log.i("EppleContinue", "order: " + sorted.take(8).mapIndexed { i, (it, t) ->
            "#$i ${it.title} (${(it.linkPkg ?: "").substringAfterLast('.')}) ${if (t > 0) fmt.format(java.util.Date(t)) else "-"}" }.joinToString(" | "))
        return sorted.map { it.first }
    }
    private val engaged = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun load(ctx: android.content.Context, net: com.eppletv.launcher.content.Net, posters: Boolean = true): List<ContentItem> {
        if (!com.eppletv.launcher.content.TvListings.allowed(ctx)) return emptyList()
        val all = try { com.eppletv.launcher.content.TvListings.read(ctx) } catch (e: Exception) { return emptyList() }
        val pm = ctx.packageManager
        val label = HashMap<String, String>()
        fun app(pkg: String) = label.getOrPut(pkg) { try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg } }
        val rows = all.filter { (it.watchNext || com.eppletv.launcher.content.TvListings.isContinueChannel(it.channel)) &&
            it.wide != null && com.eppletv.launcher.content.TvListings.linkAllowed(it.link, it.pkg) &&
            (pm.getLeanbackLaunchIntentForPackage(it.pkg) ?: pm.getLaunchIntentForPackage(it.pkg)) != null }
            .sortedByDescending { it.engaged }
        // Rows without times (an app's own Continue Watching channel) list the newest first: a new first title was just watched.
        rows.filter { !it.watchNext }.groupBy { it.pkg }.forEach { (pkg, l) -> com.eppletv.launcher.content.Recency.front(ctx, pkg, l.first().title) }
        val perApp = HashMap<String, Int>(); val seen = HashSet<String>()
        return rows.filter { seen.add(it.pkg + "|" + norm(it.title)) && (perApp.merge(it.pkg, 1, Int::plus) ?: 0) <= PER_APP }.take(MAX).map { p ->
            engaged["wn:${p.pkg}:${norm(p.title)}"] = p.engaged
            val pct = p.progress.let { if (it > 0) "${(it * 100).toInt()}% watched" else "" }
            ContentItem("wn:${p.pkg}:${norm(p.title)}", p.title, listOf(app(p.pkg), p.episode?.let { e -> if (e.all(Char::isDigit)) "Episode $e" else e } ?: "", pct).filter { it.isNotEmpty() }.joinToString("  ·  "),
                com.eppletv.launcher.content.TvListings.cleanArt(p.wide!!).first, p.link, p.pkg, "Continue in ${app(p.pkg)}", p.description ?: "",
                // Portrait art for the row's narrow cards: the app's own when it published one, else TMDB's poster by name.
                posterUrl = p.tall?.let { com.eppletv.launcher.content.TvListings.cleanArt(it).first }
                    ?: (if (!posters) null else try { com.eppletv.launcher.content.Sources.posterFor(net, p.title, p.isMovie) } catch (_: Exception) { null }) ?: "")
        }
    }
}
