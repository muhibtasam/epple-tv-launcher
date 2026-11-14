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
}

/** One section of the launcher. Focus inside a section is explicit and survives leaving and returning. */
abstract class Screen(protected val nav: Nav) {
    protected val l get() = nav.l
    abstract val view: View
    open fun show() {}
    open fun hide() {}
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
        if (item.id.startsWith("movie:") || item.id.startsWith("tv:") || item.logoUrl.isNotEmpty()) {
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
        stack.onFocus = { s, c -> info.show(c.item, s.title); nav.art(c.item) }
        stack.onRow = { r -> for (i in r..minOf(sources.size - 1, r + 3)) load(i) }
    }

    private fun build() {
        built = true
        sources.forEach { src -> stack.add(Shelf(view.context, l, nav.clock, src.title).apply { loader = { c -> loadThumb(c) } }) }
        // The first rows, plus every row built from the user's own lists (local, instant).
        sources.forEachIndexed { i, s -> if (i < 3 || s.dynamic) load(i) }
    }

    protected open fun loadThumb(c: Card) { val it = c.item ?: return; nav.content.thumb(Catalog.cardUrl(it.imageUrl)) { b -> if (b != null) c.setBitmap(b) } }

    private fun load(i: Int, force: Boolean = false) {
        if (!force && !requested.add(i)) return
        val src = sources[i]; val shelf = stack.shelves[i]
        src.load { list ->
            if (list == null) { shelf.failed("Couldn't load this row. It will retry when you come back."); requested.remove(i); return@load }
            // de-duplicate against the rows above that are already showing
            val seen = HashSet<String>()
            for (j in 0 until i) stack.shelves[j].cards.forEach { c -> c.item?.id?.let { seen.add(it) } }
            val items = list.filter { it.id !in seen }
            shelf.set(items, src.emptyText) { c, it -> c.bind(it.title, it.meta.split("  ·  ").take(2).joinToString(" · ")); wire(c) }
            stack.relayout()
            updateEmpty()
            // An empty (hidden) row must not stall lazy loading: fetch the next one now.
            if (shelf.visibility == View.GONE) (i + 1 until sources.size).firstOrNull { it !in requested }?.let { load(it) }
            // The first row to arrive gives this section its artwork if nothing is focused here yet.
            if (active && !view.hasFocus() && i == stack.firstNavigable()) items.firstOrNull()?.let { nav.art(it, immediate = true) }
            if (stack.current == null || stack.r == i || !stack.shelves[stack.r].let { it.visibility == View.VISIBLE && it.cards.isNotEmpty() }) {
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
        active = true
        if (!built) build()
        // Rows built from the user's own lists are rebuilt when those lists changed.
        if (version != nav.user.version) {
            version = nav.user.version
            sources.forEachIndexed { i, s -> if (s.dynamic && i in requested) load(i, force = true) }
        }
        stack.current?.item?.let { info.show(it, stack.current!!.title) }
        stack.trim()                                   // artwork for the rows around the focus comes back
    }

    override fun focus(): Boolean { val ok = stack.focus(); if (!ok) { focusWanted = true; view.requestFocus() }; return ok }
    override fun hide() { active = false; stack.releaseAll() }      // a hidden section holds no artwork
    override fun key(dx: Int, dy: Int): Boolean = stack.key(dx, dy)
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
    Rows.user(nav, "Continue Watching", UserLibrary.Kind.CONTINUE),
    RowSource("Recently Added", dynamic = true) { cb ->
        // New releases on the user's own services (Watchmode "New on" lists already loaded for the top row).
        cb(nav.content.loadedItems().filter { it.kind.startsWith("New on") }.distinctBy { it.id }.take(20))
    },
    RowSource("Trending Now") { cb -> nav.content.work("row:trending-all", { nav.catalog.row(nav.catalog.trendingAll) }, cb) },
    RowSource("For You", dynamic = true) { cb ->
        val seed = (nav.user.items(UserLibrary.Kind.CONTINUE) + nav.user.items(UserLibrary.Kind.FAVOURITES) +
            nav.user.items(UserLibrary.Kind.WATCHLIST) + nav.user.items(UserLibrary.Kind.RECENT)).firstOrNull { Regex("^(movie|tv):\\d+$").matches(it.id) }
        if (seed == null) nav.content.work("row:discover/movie?with_original_language=hi", { nav.catalog.row(nav.catalog.movieRows()[2]) }, cb)
        else { val (t, id) = seed.id.split(":"); nav.content.work("recs:${seed.id}", { nav.catalog.recommendations(t, id.toInt()) }, cb) }
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
    override fun extraMenu(it: ContentItem) =
        if (nav.user.contains(UserLibrary.Kind.CONTINUE, it.id)) listOf("Remove from Continue Watching" to { nav.user.remove(UserLibrary.Kind.CONTINUE, it.id) }) else emptyList()
}
