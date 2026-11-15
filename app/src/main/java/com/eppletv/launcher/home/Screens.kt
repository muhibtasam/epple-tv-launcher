package com.eppletv.launcher.home

import com.eppletv.launcher.content.Resume
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
import com.eppletv.launcher.ui.sentences

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
    fun menu(title: String, items: List<Pair<String, () -> Unit>>, icons: List<Int> = emptyList())
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
    /** Recommendations: refresh the deck in the background if due ([force]: now, e.g. after "More like this"). */
    fun refreshRecs(force: Boolean)
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
    /** Whether a card of this section has focus (its title's Play chip may show). */
    open fun chips(on: Boolean) {}
    /** A ready bitmap for this section's backdrop instead of a title (e.g. the focused app's colours). */
    open fun featuredBitmap(): Bitmap? = null
}

/** Title block over the artwork: eyebrow, logo (or typeset title), metadata and a two-line summary. */
@SuppressLint("ViewConstructor")
class InfoBlock(ctx: Context, private val l: Layout, private val clock: FrameClock, private val content: ContentRepository, private val summaryLines: Int = 2) :
    LinearLayout(ctx), FrameClock.Tick {
    private val eyebrow = TextView(ctx).apply { style(Txt.CAPTION, l.u, 0xD9FFFFFF.toInt()); isAllCaps = false; shadow(this, 8f) }
    private val title = TextView(ctx).apply { style(Txt.HERO, l.u); shadow(this, 20f) }
    private val logo = ImageView(ctx).apply { adjustViewBounds = true; maxWidth = l.px(720); maxHeight = l.px(180); scaleType = ImageView.ScaleType.FIT_START; visibility = GONE }
    private val meta = MetaRow(ctx, l)
    /** A recommendation confirmed on a pinned app: "On Netflix · Availability: JustWatch". Nothing otherwise. */
    private val avail = TextView(ctx).apply { style(Txt.META, l.u, 0xD9FFFFFF.toInt()); setShadowLayer(l.pxf(8f), 0f, l.pxf(2f), 0x99000000.toInt()); visibility = GONE }
    val summary = TextView(ctx).apply { style(Txt.BODY, l.u, 0xD9FFFFFF.toInt()); maxLines = summaryLines; shadow(this, 10f) }
    private val hints = KeyHints(ctx, l).apply { visibility = GONE; alpha = 0f }
    private val a = Spring(1f, Motion.FADE_DAMPING, Motion.FADE_STIFFNESS * 2f)
    private var shownId: String? = null

    private fun shadow(t: TextView, r: Float) = t.setShadowLayer(l.pxf(r), 0f, l.pxf(2f), 0x99000000.toInt())

    init {
        orientation = VERTICAL
        addView(eyebrow, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(title, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = l.px(10) })
        addView(logo, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = l.px(16); bottomMargin = l.px(4) })
        addView(meta, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = l.px(12) })
        addView(avail, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = l.px(10) })
        addView(summary, LayoutParams(l.px(860), WRAP_CONTENT).apply { topMargin = l.px(12) })
        addView(hints, LayoutParams(MATCH_PARENT, l.px(56)).apply { topMargin = l.px(20) })
    }

    /** [play]: the label of the Play chip ("Play", "Resume") when OK launches this title; null shows no chip. */
    fun show(item: ContentItem?, kind: String, play: String? = null, service: String? = null) {
        if (item == null) { visibility = INVISIBLE; return }
        visibility = VISIBLE
        avail.text = service?.let { "On $it  ·  ${com.eppletv.launcher.content.Availability.ATTRIBUTION}" }.orEmpty()
        avail.visibility = if (service == null) GONE else VISIBLE
        playLabel = play
        play?.let { hints.set(it) }
        reveal()
        // With the Play chip the block keeps to the same height: a slightly smaller logo (smaller still in a budget).
        logo.maxHeight = l.px(if (budget > 0) (if (play != null) 96 else 140) else (if (play != null) 110 else 180))
        val fresh = item.id != shownId
        shownId = item.id
        eyebrow.text = Brand.eyebrow(kind); eyebrow.visibility = if (kind.isEmpty()) GONE else VISIBLE
        title.text = item.title; meta.set(item.meta)
        // The summary gets the lines left in the budget (up to [summaryLines]) and only whole sentences fit in them.
        val lines = if (budget <= 0) summaryLines else ((budget - headHeight(play != null)) / summary.lineHeight).coerceIn(0, summaryLines)
        summary.sentences(item.summary, l.px(860), lines, longest = if (budget <= 0) lines + 1 else lines)
        summary.visibility = if (summary.text.isEmpty()) GONE else VISIBLE
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

    /** Browse screens: the block's height (px), which it never exceeds; 0 = no limit (details page). */
    var budget = 0

    /** Everything above the summary, as it will be laid out: the logo's slot is reserved whether or not it has arrived. */
    private fun headHeight(play: Boolean): Int {
        val w = MeasureSpec.makeMeasureSpec(l.px(1300), MeasureSpec.AT_MOST); val u = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        fun top(v: View) = (v.layoutParams as MarginLayoutParams).let { it.topMargin + it.bottomMargin }
        fun h(v: View) = if (v.visibility == GONE) 0 else { v.measure(w, u); v.measuredHeight + top(v) }
        title.measure(w, u)
        val name = maxOf(logo.maxHeight + top(logo), title.measuredHeight + top(title))
        return h(eyebrow) + name + h(meta) + h(avail) + top(summary) + (if (play) l.px(56) + top(hints) else 0)
    }

    override fun tick(dt: Float): Boolean { a.step(dt); alpha = a.value; return !a.atRest() }

    private var playLabel: String? = null
    private var chipsOn = false
    private val showChip = Runnable { if (chipsOn && playLabel != null) { hints.visibility = VISIBLE; hints.animate().alpha(1f).setDuration(160).start() } }
    /** A card of this block's section has focus or not: the chip shows only for a focused, launchable title. */
    fun chips(on: Boolean) { if (chipsOn != on) { chipsOn = on; reveal() } }
    /** Hidden at once; shown once focus rests on the title for a moment (not on every card passed while scrolling). */
    private fun reveal() {
        removeCallbacks(showChip); hints.animate().cancel(); hints.alpha = 0f
        if (playLabel == null) hints.visibility = GONE else { hints.visibility = INVISIBLE; if (chipsOn) postDelayed(showChip, 280) }
    }
}

/**
 * What the remote does on the focused title, as two small chips in the Epple TV icon style: OK resumes it, holding OK
 * opens its options. Not focusable: it describes the keys, it isn't a button.
 */
@SuppressLint("ViewConstructor")
class KeyHints(ctx: Context, private val l: Layout) : View(ctx) {
    private val I = com.eppletv.launcher.ui.EppleIcons
    private var chips = listOf(Triple(I.PLAY, "Play", true), Triple(I.MORE, "Hold OK for more", false))
    fun set(play: String, more: Boolean = true) {
        val n = listOf(Triple(I.PLAY, play, true)) + if (more) listOf(Triple(I.MORE, "Hold OK for more", false)) else emptyList()
        if (n != chips) { chips = n; invalidate() }
    }
    private val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = l.pxf(24f) * com.eppletv.launcher.ui.Type.scale; typeface = com.eppletv.launcher.ui.Type.of(700) }
    private val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val icon = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val r = android.graphics.RectF()

    override fun onDraw(c: android.graphics.Canvas) {
        val h = height.toFloat(); val pad = l.pxf(20f); val ic = l.pxf(28f); val gap = l.pxf(10f)
        var x = 0f
        for ((id, label, primary) in chips) {
            val w = pad + ic + gap + text.measureText(label) + pad
            r.set(x, 0f, x + w, h)
            bg.color = if (primary) Colors.WHITE else 0x2EFFFFFF; c.drawRoundRect(r, l.pxf(10f), l.pxf(10f), bg)
            val ink = if (primary) Colors.INK else Colors.WHITE
            icon.color = ink; com.eppletv.launcher.ui.EppleIcons.draw(id, c, x + pad + ic / 2f, h / 2f, ic, icon, 1f)
            text.color = ink; c.drawText(label, x + pad + ic + gap, h / 2f - (text.descent() + text.ascent()) / 2f, text)
            x += w + l.pxf(14f)
        }
    }
}

/** A source of one row: [load] calls back with the items, or null on failure. */
class RowSource(val title: String, val dynamic: Boolean = false, val emptyText: String? = null,
                /** Drawn as Continue Watching (design B): landscape cards with logos and progress. */
                val cont: Boolean = false,
                /** A recommendations row: its reason shows above the focused title; "Not for me" / "More like this". */
                val rec: Boolean = false,
                /** Fewer titles than this (after de-duplication) hides the row. */
                val min: Int = 0,
                /** The row's title once loaded, when it depends on what loaded ("Because you watched …"). */
                val label: (() -> String?)? = null,
                val load: ((List<ContentItem>?) -> Unit) -> Unit)

/**
 * Browse layout shared by Home's feed, Movies, TV Shows and Library: the focused title's artwork fills the screen,
 * its info sits top-left, rows of cards below. Rows load lazily as the user approaches them and are de-duplicated
 * against the rows above. OK opens the details page; holding OK offers Watchlist / Favourites.
 */
open class BrowseScreen(nav: Nav, private val sources: List<RowSource>, private val emptyAll: String? = null) : Screen(nav) {
    final override val view = FrameLayout(nav.ctx).apply { clipChildren = false }
    protected val info = InfoBlock(view.context, l, nav.clock, nav.content, summaryLines = 3)
    protected val stack = ShelfStack(view.context, l, nav.clock, l.px(Look.ROWS_TOP))
    private val empty = TextView(view.context).apply { style(Txt.ROW, l.u, Colors.TEXT_DIM); text = emptyAll ?: ""; gravity = Gravity.CENTER; visibility = View.GONE
        // Empty state: the Epple TV icon for watching above the line.
        setCompoundDrawablesRelative(null, com.eppletv.launcher.ui.IconDrawable(com.eppletv.launcher.ui.EppleIcons.TV, l.px(72), 0f).apply { setBounds(0, 0, l.px(72), l.px(72)); color = Colors.TEXT_DIM }, null, null)
        compoundDrawablePadding = l.px(20) }
    private val requested = HashSet<Int>()
    private var built = false
    private var version = -1
    private var active = false

    init {
        // The info block is anchored to its bottom, a fixed gap above the first row's title, inside a band of fixed
        // height: however tall its content, it never reaches the row (its summary gives way first).
        val band = l.px(Look.ROWS_TOP - Look.INFO_GAP - Look.INFO_CEIL)
        info.budget = band
        view.addView(FrameLayout(view.context).apply { clipChildren = false
            addView(info, FrameLayout.LayoutParams(l.px(1300), WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        }, FrameLayout.LayoutParams(l.px(1300) + l.left, band).apply { leftMargin = l.left; topMargin = l.px(Look.INFO_CEIL) })
        view.addView(stack, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        view.addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        stack.onFocus = { s, c ->
            // Continue Watching names the app to go back to and shows what OK and a long OK do.
            val app = if (s.cont) c.item?.kind?.removePrefix("Continue in ")?.takeIf { it.isNotEmpty() } else null
            val it = c.item
            // The eyebrow names the app, so the meta line doesn't repeat it.
            val shown = if (app == null || it == null) it else ContentItem(it.id, it.title,
                it.meta.split("  ·  ").filter { p -> p.trim() != app }.joinToString("  ·  "), it.imageUrl, it.linkUri, it.linkPkg,
                it.kind, it.summary, it.weight, it.origin, it.logoUrl, it.posterUrl)
            // A recommendation's reason ("Because you watched …") is its eyebrow; nothing is added to the posters.
            val rec = sources.getOrNull(stack.shelves.indexOf(s))?.rec == true
            val why = if (rec) it?.kind.orEmpty() else ""
            // Only a title the saved deck confirms on a pinned app names its service (with JustWatch's credit).
            info.show(shown, app?.let { a -> "Continue in $a" } ?: why, play = playLabel(s, it),
                service = if (rec) it?.let { x -> com.eppletv.launcher.content.Recommender.badge(x.id) } else null)
            nav.art(c.item)
        }
        stack.onRow = { r -> for (i in r..minOf(sources.size - 1, r + 3)) load(i) }
    }

    private fun build() {
        built = true; recsSeen = com.eppletv.launcher.content.Recommender.version
        sources.forEach { src -> stack.add(Shelf(view.context, l, nav.clock, src.title, poster = true, cont = src.cont).apply { loader = { c -> loadThumb(c) } }) }
        // The first rows, plus every row built from the user's own lists (local, instant).
        sources.forEachIndexed { i, s -> if (i < 3 || s.dynamic) load(i) }
    }

    /** "Play" / "Resume" when OK on this title launches it in its app (not just its details page); null otherwise. */
    protected open fun playLabel(s: Shelf, it: ContentItem?): String? = null

    protected open fun loadThumb(c: Card) {
        val it = c.item ?: return
        if (c is ContinueCard) {
            // Decoded at the focused card's size (no upscaling), from the same disk cache; the logo from the shared cache.
            nav.content.thumb(Catalog.cardUrl(it.imageUrl), nav.content.WIDE_THUMB) { b -> if (b != null && c.item?.id == it.id) c.setBitmap(b) }
            nav.content.logo(it) { b -> if (c.item?.id == it.id) c.setLogo(b) }
            return
        }
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
            val items = list.filter { it.id !in seen }.let { if (it.size < src.min) emptyList() else it }
            src.label?.invoke()?.let { shelf.retitle(it) }
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
        val I = com.eppletv.launcher.ui.EppleIcons
        val extra = extraMenu(it)
        nav.menu(it.title, listOf(
            "Details" to { nav.details(it) },
            (if (inList) "Remove from Watchlist" else "Add to Watchlist") to { u.toggle(UserLibrary.Kind.WATCHLIST, it); Unit },
            (if (fav) "Remove from Favourites" else "Add to Favourites") to { u.toggle(UserLibrary.Kind.FAVOURITES, it); Unit },
        ) + extra.map { e -> e.first to e.third },
            listOf(I.INFO, if (inList) I.WATCHLIST_ON else I.WATCHLIST, if (fav) I.FAVOURITE_ON else I.FAVOURITE) + extra.map { e -> e.second })
    }

    /** More actions for [it]: label, [com.eppletv.launcher.ui.EppleIcons] id, action. On a recommendations row: feedback. */
    protected open fun extraMenu(it: ContentItem): List<Triple<String, Int, () -> Unit>> {
        if (sources.getOrNull(stack.r)?.rec != true) return emptyList()
        val I = com.eppletv.launcher.ui.EppleIcons
        return listOf(
            Triple("Not for me", I.REMOVE) {
                com.eppletv.launcher.content.Taste.dismiss(nav.ctx, it.id)
                nav.content.run({ com.eppletv.launcher.content.Recommender.drop(nav.ctx, it.id); true }) { reloadRecs(force = true) }
                nav.toast("${it.title} won't be recommended again")
            },
            Triple("More like this", I.MORE) {
                com.eppletv.launcher.content.Taste.moreLike(nav.ctx, it)
                com.eppletv.launcher.content.Recommender.markDirty(nav.ctx)
                nav.refreshRecs(force = true)
                nav.toast("More like ${it.title} in your next picks")
            },
        )
    }

    private var recsSeen = -1
    /** New recommendations are in: their rows reload, except one the user is on ([force]: that one too). */
    fun recsChanged() { recsSeen = com.eppletv.launcher.content.Recommender.version; reloadRecs(force = false) }
    protected fun reloadRecs(force: Boolean) {
        if (!built) return
        sources.forEachIndexed { i, s -> if (s.rec && (force || !stack.shelves[i].hasFocus())) load(i, force = true) }
    }

    override fun chips(on: Boolean) = info.chips(on)
    override fun featured(): ContentItem? = stack.current?.item ?: stack.shelves.getOrNull(stack.firstNavigable())?.item

    override fun show() {
        active = true; moved = false
        if (!built) build()
        // Rows built from the user's own lists are rebuilt when those lists changed.
        if (version != nav.user.version) {
            version = nav.user.version
            sources.forEachIndexed { i, s -> if (s.dynamic && i in requested) load(i, force = true) }
        }
        // A newer recommendations deck than the rows show: they reload (nothing is focused here yet).
        if (recsSeen != com.eppletv.launcher.content.Recommender.version) { recsSeen = com.eppletv.launcher.content.Recommender.version; reloadRecs(force = false) }
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

    /**
     * A recommendations row from the saved deck (read on the worker, never before it is needed). Hidden when
     * recommendations are off, there is no deck yet, or fewer than [min] titles remain. [label] names the row.
     */
    fun rec(nav: Nav, title: String, min: Int, label: ((com.eppletv.launcher.content.Recommender.Deck) -> String?)? = null,
            pick: (com.eppletv.launcher.content.Recommender.Deck) -> List<ContentItem>): RowSource {
        var named: String? = null
        return RowSource(title, rec = true, min = min, label = { named }) { cb ->
            if (nav.prefs.get(Prefs.RECS) != 0) { cb(emptyList()); return@RowSource }
            nav.content.run<Any>({ com.eppletv.launcher.content.Recommender.deck(nav.ctx) ?: false }) { d ->
                val deck = d as? com.eppletv.launcher.content.Recommender.Deck
                named = deck?.let { label?.invoke(it) }
                cb(if (deck == null) emptyList() else pick(deck))
            }
        }
    }
    /** Movies or TV Shows: "For You", the deck's picks of that type (at least 8). */
    fun forYou(nav: Nav, type: String) = rec(nav, "For You", 8) { d -> com.eppletv.launcher.content.Recommender.items(nav.ctx, d, if (type == "movie") d.movie else d.tv) }
}

/** Home's rows under the top row: Continue Watching, Top Picks for You, personal recommendation rows and the Watchlist. */
class HomeFeed(nav: Nav) : BrowseScreen(nav, listOf(
    // What you left half-way in any app (the TV's own Watch Next list, as Google TV shows it), then titles opened from Epple TV.
    RowSource("Continue Watching", dynamic = true, cont = true) { cb ->
        // Twice: at once with the apps' own pictures, then again once TMDB posters are found (cached after the first time).
        for (posters in listOf(false, true)) nav.content.run({ ContinueWatching.load(nav.ctx, nav.content.net, posters) }) { tv ->
            cb(ContinueWatching.merge(nav.ctx, tv.orEmpty(), nav.user.items(UserLibrary.Kind.CONTINUE)))
        }
    },
    // Recommendations worked out on this TV (no AI): 16 dealt from the saved deck each visit.
    Rows.rec(nav, "Top Picks for You", 1) { d -> com.eppletv.launcher.content.Recommender.deal(nav.ctx, d) },
    // Personal rows when the deck has them ("Because you watched …", "More from …", "With …").
    *(0 until 4).map { k -> Rows.rec(nav, "", 6, label = { d -> d.rows.getOrNull(k)?.first }) { d ->
        d.rows.getOrNull(k)?.let { com.eppletv.launcher.content.Recommender.items(nav.ctx, d, it.second) } ?: emptyList() } }.toTypedArray(),
    Rows.user(nav, "Your Watchlist", UserLibrary.Kind.WATCHLIST),
)) {
    override fun wire(c: Card) {
        super.wire(c)
        // Continue Watching resumes in the app it was opened in.
        // A title from an app's own lists opens in that app (Continue Watching resumes it); others open their details.
        c.setOnClickListener {
            val it = c.item ?: return@setOnClickListener
            val pkg = it.linkPkg ?: return@setOnClickListener nav.details(it)
            val app = if (it.kind.startsWith("Continue in ")) it.kind.removePrefix("Continue in ") else nav.apps.firstOrNull { a -> a.packageName == pkg }?.label ?: pkg
            nav.play(it, pkg, app)
        }
    }
    override fun playLabel(s: Shelf, it: ContentItem?): String? = when {
        it?.linkPkg == null -> null
        s.cont -> "Resume"
        else -> "Play"
    }
    /** The TV's Watch Next list changes outside Epple TV: it is read again every time Home's rows open. */
    private var shownOnce = false
    override fun show() { super.show(); if (shownOnce) { toFirst += 0; reload(0) }; shownOnce = true }
    /** Back from a title or app: only Continue Watching is read again (its order changed) and starts at its new first. */
    fun refreshContinue() { toFirst += 0; reload(0) }

    /** Every title in Continue Watching can be removed, wherever it came from (Watch Next, an app's row, Epple's list). */
    override fun extraMenu(it: ContentItem) =
        if (stack.current?.title == "Continue Watching") listOf(Triple("Remove from Continue Watching", com.eppletv.launcher.ui.EppleIcons.REMOVE, { removeContinue(it) })) else super.extraMenu(it)

    private fun removeContinue(it: ContentItem) {
        com.eppletv.launcher.content.Recency.remove(nav.ctx, it.linkPkg, it.title)
        nav.user.items(UserLibrary.Kind.CONTINUE).filter { m -> ContinueWatching.norm(m.title) == ContinueWatching.norm(it.title) }
            .forEach { m -> nav.user.remove(UserLibrary.Kind.CONTINUE, m.id) }
        reload(0)                                             // the row loses it now; focus stays at the same place
    }
}

/**
 * Continue Watching from the TV itself: the system Watch Next list (where Prime Video, YouTube and others note what you
 * left half-way) plus apps' own "Continue Watching" channels (Stremio), newest first, at most two per app so one show
 * doesn't fill the row. Opening one goes back into that app at that title (its own link).
 */
object ContinueWatching {
    /** Shown in the row; the TV's lists and Epple's own history are kept whole underneath. */
    const val SHOWN = 10
    private const val MAX = 60
    fun norm(t: String) = t.trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")

    /**
     * The apps' rows and the titles opened from Epple TV in one list, the most recent first. Each title's time is the
     * newest of: when the TV's Watch Next says it was watched, when it was last watched (by app and name, see Recency),
     * and when its app's player last stopped on it (see Resume). Titles with none of these are not shown.
     */
    fun merge(ctx: android.content.Context, tv: List<ContentItem>, mine: List<ContentItem>): List<ContentItem> {
        val R = com.eppletv.launcher.content.Recency
        val keyed = ArrayList<Pair<ContentItem, Long>>(); val seen = HashSet<String>()
        val untimed = ArrayList<String>()
        for (it in tv) {
            if (R.removed(ctx, it.linkPkg, it.title) || !seen.add(norm(it.title))) continue
            val wn = R.acceptedTime(ctx, it.linkPkg, it.title, engaged[it.id] ?: 0L)
            if (wn < 0) continue                                  // appeared without verified viewing
            val own = maxOf(wn, R.titleTime(ctx, it.linkPkg, it.title), Resume.saved(ctx, it.linkPkg, it.title)?.at ?: 0L)
            // Only the title's own activity counts: a row with no time of its own (an app's channel lists old titles
            // too) can't show it was watched in the last 14 days, so the app's last use is never lent to it.
            if (own > 0) keyed += it to own else untimed += it.title
        }
        // Titles opened from Epple TV count only once their app reported 30 seconds of playing (a verified stamp);
        // the rest stay in the history underneath, unshown.
        for (it in mine) {
            val t = maxOf(R.titleTime(ctx, it.linkPkg, it.title), Resume.saved(ctx, it.linkPkg, it.title)?.at ?: 0L)
            if (t > 0 && !R.removed(ctx, it.linkPkg, it.title) && seen.add(norm(it.title))) keyed += it to t
        }
        // Only titles watched today or in the 13 days before (display only: the history underneath is kept).
        val now = System.currentTimeMillis()
        val old = keyed.filter { !Resume.inWindow(it.second, now) }
        if (untimed.isNotEmpty()) android.util.Log.i("EppleContinue", "no activity time: " + untimed.joinToString())
        if (old.isNotEmpty()) android.util.Log.i("EppleContinue", "older than ${Resume.DAYS} days: " + old.joinToString { it.first.title })
        val sorted = (keyed - old.toSet()).sortedByDescending { it.second }.take(SHOWN)
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT)
        android.util.Log.i("EppleContinue", "order (${sorted.size} shown of ${keyed.size}): " + sorted.take(10).mapIndexed { i, (it, t) ->
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
        val rows = all.filter { ((it.watchNext && it.watched) || com.eppletv.launcher.content.TvListings.isContinueChannel(it.channel)) &&
            it.wide != null && com.eppletv.launcher.content.TvListings.linkAllowed(it.link, it.pkg) &&
            (pm.getLeanbackLaunchIntentForPackage(it.pkg) ?: pm.getLaunchIntentForPackage(it.pkg)) != null }
            .sortedByDescending { it.engaged }
        // Rows without times (an app's own Continue Watching channel) list the newest first: a new first title was just watched.
        rows.filter { !it.watchNext }.groupBy { it.pkg }.forEach { (pkg, l) -> com.eppletv.launcher.content.Recency.front(ctx, pkg, l.first().title) }
        val seen = HashSet<String>()
        val tm = com.eppletv.launcher.content.Sources.tmdb
        val offline = !posters        // the quick first pass reads TMDB's stored copies only; the second may ask
        return rows.filter { seen.add(it.pkg + "|" + norm(it.title)) }.take(MAX).mapNotNull { p ->
            engaged["wn:${p.pkg}:${norm(p.title)}"] = p.engaged
            val movie = p.isMovie
            val rowS = p.season?.toIntOrNull(); val rowE = p.episode?.toIntOrNull()
            val saved = Resume.saved(ctx, p.pkg, p.title)
            // The app's own data first; TMDB (by the exact IMDb id the app publishes) only fills what is missing.
            val imdb = (p.providerId ?: p.link)?.let { Regex("tt\\d{5,10}").find(it)?.value }
            fun <T> look(f: () -> T?): T? = try { f() } catch (e: Exception) { null }
            val found = if (tm == null || imdb == null) null else look { tm.byImdb(net, imdb, movie, offline) }
            val series = if (movie || found == null) null else look { tm!!.series(net, found.first, offline) }
            val seasons = HashMap<Int, Map<Int, Triple<String, String, Long>>?>()
            fun eps(sn: Int?) = if (sn == null || found == null || movie) null else seasons.getOrPut(sn) { look { tm!!.season(net, found.first, sn, offline) } }
            val savedEp = if (saved == null || movie || saved.name.isEmpty()) null
                else (rowS ?: 1).let { sn -> eps(sn)?.entries?.firstOrNull { it.value.first.equals(saved.name, ignoreCase = true) }?.let { sn to it.key } }
            val shown = Resume.decide(movie, rowS, rowE, p.position, p.duration, saved, savedEp, series)
            if (shown == null) { android.util.Log.i("EppleContinue", "left out ${p.title}: finished, or not out yet"); return@mapNotNull null }
            val ep = eps(shown.s)?.get(shown.e)
            val duration = if (shown.duration > 0) shown.duration else ep?.third ?: 0L
            val pr = Resume.progress(shown.position, duration)
            val pct = if (pr > 0) "${(pr * 100).toInt()}% watched" else ""
            val name = Resume.fill(shown.name, ep?.first).takeUnless { Regex("(?i)episode \\d+").matches(it) }.orEmpty()
            val episode = if (movie) "" else Resume.label(shown.s, shown.e).ifEmpty { p.episode?.takeIf { e -> !e.all(Char::isDigit) }.orEmpty() }
            // Moved on to another episode than the app's row: open that one (Stremio's documented detail link only).
            val link = if (shown.s != null && shown.e != null && rowS != null && rowE != null && (shown.s != rowS || shown.e != rowE) && p.link?.startsWith("stremio:///detail/series/") == true)
                p.link.replace("%3A$rowS%3A$rowE", "%3A${shown.s}%3A${shown.e}") else p.link
            ContentItem("wn:${p.pkg}:${norm(p.title)}", p.title, listOf(app(p.pkg), name, pct).filter { it.isNotEmpty() }.joinToString("  ·  "),
                com.eppletv.launcher.content.TvListings.cleanArt(p.wide!!).first, link, p.pkg, "Continue in ${app(p.pkg)}",
                Resume.fill(p.description, ep?.second, series?.overview, found?.second),
                // Portrait art for the row's narrow cards: the app's own, else TMDB's by IMDb id, else TMDB's by name.
                posterUrl = p.tall?.let { com.eppletv.launcher.content.TvListings.cleanArt(it).first } ?: found?.third?.ifEmpty { null }
                    ?: (if (!posters) null else look { com.eppletv.launcher.content.Sources.posterFor(net, p.title, p.isMovie) }) ?: "",
                episode = episode)
        }
    }
}
