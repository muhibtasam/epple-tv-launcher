package com.eppletv.launcher.home

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.eppletv.launcher.content.Catalog
import com.eppletv.launcher.content.Credentials
import com.eppletv.launcher.pair.PhoneLink
import com.eppletv.launcher.ui.Colors
import com.eppletv.launcher.ui.Pill
import com.eppletv.launcher.ui.Txt
import com.eppletv.launcher.ui.style

/**
 * First-launch setup: Welcome, content providers (API keys, validated on request), content preferences,
 * default Home screen, finish. Progress is saved after every step, so an interrupted setup resumes where it stopped.
 * The same flow opens from Settings to change keys (providers step only) or to run setup again.
 * It uses the platform's focus handling, so the system keyboard works in the key field.
 */
class SetupFlow(private val nav: Nav, private val host: Host) {
    interface Host {
        fun isDefaultHome(): Boolean
        /** Opens the system's own Home-screen choice; false when this TV offers none to apps. */
        fun requestDefaultHome(): Boolean
        fun setupFinished(changedKeys: Boolean)
        fun setupClosed(changedKeys: Boolean)
        /** Pictures were added to the Home wallpapers from the phone. */
        fun wallpapersAdded()
    }

    private val l = nav.l
    private val ctx = nav.ctx
    private val state = ctx.getSharedPreferences("setup", Context.MODE_PRIVATE)
    val view = FrameLayout(ctx).apply { setBackgroundColor(0xF7050608.toInt()); visibility = View.GONE; isClickable = true }
    /** Views behind setup: they cannot take focus while setup is open (OK must never reach the top row behind it). */
    var behind: List<View> = emptyList()
        set(v) { field = v; block(active) }
    private fun block(on: Boolean) = behind.forEach { (it as? android.view.ViewGroup)?.descendantFocusability = if (on) android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS else android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS; if (it !is android.view.ViewGroup) it.isFocusable = !on }
    private val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val stepLabel = TextView(ctx).apply { style(Txt.CAPTION, l.u, Colors.TEXT_DIM) }
    private val title = TextView(ctx).apply { style(Txt.HERO, l.u) }
    private val body = TextView(ctx).apply { style(Txt.BODY, l.u, 0xD9FFFFFF.toInt()); maxLines = 5 }
    private val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
    private val brand = TextView(ctx).apply { style(Txt.CLOCK, l.u, 0x26FFFFFF); text = "Epple TV" }

    private var step = 0
    private var single = false
    private var changedKeys = false
    private val checks = HashMap<Credentials.Key, Credentials.Check>()
    private var editing: Credentials.Key? = null
    val active get() = view.visibility == View.VISIBLE
    val done get() = state.getBoolean("done", false)

    // Phone setup (QR code): the panel redraws on every phase change; a 1 s tick updates only the countdown.
    private var phoneMode = false
    /** Opened straight to the phone screen (Settings > Wallpapers): leaving it closes setup. */
    private var phoneOnly = false
    private val phone by lazy {
        PhoneLink(ctx, nav.content.net, nav.wallpapers) { if (phoneMode) render() }.apply {
            onSaved = { s -> if (s.check.ok) { checks[s.key] = s.check; changedKeys = true } }
        }
    }
    private var countdown: TextView? = null
    private val tick = object : Runnable {
        override fun run() {
            val c = countdown ?: return
            val s = (phone.remainingMs / 1000).toInt()
            c.text = "Code expires in ${s / 60}:${"%02d".format(s % 60)}"
            if (phoneMode && phone.live) view.postDelayed(this, 1000)
        }
    }

    init {
        column.addView(stepLabel)
        column.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = l.px(14) })
        column.addView(body, LinearLayout.LayoutParams(l.px(1100), WRAP_CONTENT).apply { topMargin = l.px(18) })
        column.addView(content, LinearLayout.LayoutParams(l.px(1100), WRAP_CONTENT).apply { topMargin = l.px(34) })
        column.addView(buttons, LinearLayout.LayoutParams(WRAP_CONTENT, l.px(72)).apply { topMargin = l.px(40) })
        view.addView(column, FrameLayout.LayoutParams(l.px(1300), WRAP_CONTENT).apply { leftMargin = l.px(160); topMargin = l.px(150) })
        view.addView(brand, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { rightMargin = l.px(100); bottomMargin = l.px(70) })
    }

    /** Opens setup at the saved step (first launch) or at [at]; [one] = just that step, from Settings. */
    fun start(at: Int = state.getInt("step", 0), one: Boolean = false) {
        single = one; changedKeys = false; editing = null
        step = at.coerceIn(0, 4)
        view.visibility = View.VISIBLE; block(true)
        render()
    }

    /** Opens only the phone screen (from Settings), with a fresh code. */
    fun startPhone() {
        single = true; phoneOnly = true; changedKeys = false; editing = null; step = 1
        view.visibility = View.VISIBLE; block(true)
        phoneMode = true; render(); phone.start()
    }

    fun resetForRerun() { state.edit().putBoolean("done", false).putInt("step", 0).apply() }

    /** BACK: closes the key editor, then goes to the previous step. On the first step it does nothing (setup must finish). */
    fun back(): Boolean {
        if (phoneMode) { leavePhone(); if (phoneOnly) close() else render(); return true }
        if (editing != null) { editing = null; render(); return true }
        if (single) { close(); return true }
        if (step > 0) { go(step - 1) }
        return true
    }

    /** The user returned from the system's Home-screen choice. */
    fun refresh() { if (active && step == 3 && editing == null) render() }

    private fun go(n: Int) { step = n; if (!single) state.edit().putInt("step", n).apply(); render() }

    private fun close() {
        leavePhone(); phoneOnly = false
        view.visibility = View.GONE; block(false)
        photosSeen()
        host.setupClosed(changedKeys)
    }

    /** Tells Home once about pictures added from the phone since setup opened. */
    private fun photosSeen() { if (phone.photosTotal > 0) { phone.photosTotal = 0; host.wallpapersAdded() } }

    /** The TV left the foreground: stop listening; the user starts a new code when they return. */
    fun pause() { if (phoneMode) { leavePhone(); render() } }

    private fun leavePhone() {
        phoneMode = false
        phone.stop()
        countdown = null
        view.removeCallbacks(tick)
        view.keepScreenOn = false
    }

    private fun finish() {
        leavePhone()
        state.edit().putBoolean("done", true).putInt("step", 4).apply()
        view.visibility = View.GONE; block(false)
        photosSeen()
        host.setupFinished(changedKeys)
    }

    // ---- rendering ----

    private fun button(label: String, primary: Boolean = false, action: () -> Unit): Pill {
        val p = Pill(ctx, label, l.pxf(27f), l.pxf(36f), l.px(40), nav.clock, if (primary) 0x40FFFFFF else Colors.FILL, Colors.WHITE, 0.06f)
        p.setOnClickListener { action() }
        buttons.addView(p, LinearLayout.LayoutParams(WRAP_CONTENT, l.px(72)).apply { if (buttons.childCount > 0) leftMargin = l.px(18) })
        return p
    }

    private fun row(label: String, value: String, action: () -> Unit): ValuePill {
        val p = ValuePill(ctx, l, label, nav.clock).apply { this.value = value; setOnClickListener { action() } }
        content.addView(p, LinearLayout.LayoutParams(MATCH_PARENT, l.px(80)).apply { if (content.childCount > 0) topMargin = l.px(12) })
        return p
    }

    private fun note(text: String, color: Int = Colors.TEXT_DIM): TextView = TextView(ctx).apply {
        style(Txt.META, l.u, color); isSingleLine = false; maxLines = 4; this.text = text
        content.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (content.childCount > 0) topMargin = l.px(16) })
    }

    private fun render() {
        content.removeAllViews(); buttons.removeAllViews()
        stepLabel.text = if (single) "SETTINGS" else "STEP ${step + 1} OF 5"
        var first: View? = null
        countdown = null
        if (phoneMode) { first = renderPhone() } else if (editing != null) { first = renderEditor(editing!!) } else when (step) {
            0 -> {
                title.text = "Welcome to Epple TV"
                body.text = "A cinematic home for this TV: your apps, what's new on your services and everything you're watching, in one place.\n\n" +
                    "Setup takes about a minute. Connect your content providers, choose what you like to watch, and decide whether Epple TV should be your Home screen."
                first = button("Get Started", primary = true) { go(1) }
            }
            1 -> {
                title.text = "Content Providers"
                body.text = "Epple TV uses these services for artwork, new releases and trends. Keys are encrypted on this TV and only the last four characters are ever shown. Skip any you don't have."
                first = row("Set up with your phone", "Scan a code") { phoneMode = true; render(); phone.start() }
                Credentials.Key.values().forEach { k ->
                    val r = row(k.label, status(k)) { editing = k; render() }
                    if (first == null) first = r
                }
                note("TMDB powers Movies, TV Shows, Search and details. Without it those sections stay empty.")
                if (single) button("Done", primary = true) { close() }
                else { button("Back") { go(0) }; button(if (Credentials.hasAny(Credentials.Key.TMDB)) "Continue" else "Skip for Now", primary = true) { go(2) } }
            }
            2 -> {
                title.text = "What You Watch"
                body.text = "Epple TV balances Bollywood and regional Indian cinema with Hollywood. Choose the mix and the Indian languages you want to see."
                val mix = row(Prefs.INDIAN.label, Prefs.INDIAN.options[nav.prefs.get(Prefs.INDIAN)]) {}
                mix.setOnClickListener { changedKeys = true; nav.prefs.put(Prefs.INDIAN, (nav.prefs.get(Prefs.INDIAN) + 1) % Prefs.INDIAN.options.size); mix.value = Prefs.INDIAN.options[nav.prefs.get(Prefs.INDIAN)] }
                first = mix
                val region = row(Prefs.REGION.label, Prefs.REGION.options[nav.prefs.get(Prefs.REGION)]) {}
                region.setOnClickListener { changedKeys = true; nav.prefs.put(Prefs.REGION, (nav.prefs.get(Prefs.REGION) + 1) % Prefs.REGION.options.size); region.value = Prefs.REGION.options[nav.prefs.get(Prefs.REGION)] }
                note("Languages")
                languageChips()
                if (single) button("Done", primary = true) { close() } else { button("Back") { go(1) }; button("Continue", primary = true) { go(3) } }
            }
            3 -> {
                title.text = "Home Screen"
                val isHome = host.isDefaultHome()
                body.text = if (isHome) "Epple TV is your Home screen. The Home button on your remote brings you here."
                    else "Epple TV can open when you press the Home button on your remote. Android asks you to confirm this on its own screen; Epple TV never changes it by itself. You can keep Google TV as your Home screen and open Epple TV from your apps instead."
                note(if (isHome) "✓  Epple TV is the default Home screen" else "Current Home screen: the TV's own launcher", if (isHome) 0xFF7CE38B.toInt() else Colors.TEXT_DIM)
                if (!isHome) first = button("Make Epple TV the Home Screen", primary = true) {
                    if (!host.requestDefaultHome()) {
                        note("This TV doesn't let apps offer a Home-screen choice. On Google TV this setting is managed by the system; " +
                            "you can still open Epple TV from your apps row.", 0xFFFFB86B.toInt())
                    }
                }
                button("Back") { go(2) }
                val next = button(if (isHome) "Continue" else "Not Now", primary = isHome) { go(4) }
                if (first == null) first = next
            }
            else -> {
                title.text = "You're All Set"
                body.text = "Your choices are saved. You can change everything later in Settings, including your provider keys, or run this setup again."
                note("Providers connected: ${Credentials.connected()} of 4")
                note("Indian languages: " + nav.prefs.langs.let { l -> if (l.isEmpty()) "none (Hollywood only)" else l.joinToString(", ") { Catalog.languageName(it) } })
                note("Home screen: " + if (host.isDefaultHome()) "Epple TV" else "the TV's own launcher")
                button("Back") { go(3) }
                first = button("Start Epple TV", primary = true) { finish() }
            }
        }
        view.post { (first ?: buttons.getChildAt(buttons.childCount - 1))?.requestFocus() }
    }

    /** Phone setup: the QR code on the left, what is happening on the right. Returns the view to focus. */
    private fun renderPhone(): View? {
        title.text = if (phoneOnly) "Add from Your Phone" else "Set Up with Your Phone"
        body.text = "Scan the code with your phone's camera. Your phone must be on the same Wi-Fi as this TV. " +
            "On your phone you can enter provider keys and add pictures for the Home screen; they go straight to this TV over your home network."
        val p = phone.phase
        val green = 0xFF7CE38B.toInt(); val orange = 0xFFFFB86B.toInt(); val red = 0xFFFF8A80.toInt()
        val side = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        val box = l.px(420)
        val showQr = p == PhoneLink.Phase.WAITING || p == PhoneLink.Phase.CONFIRMING
        val image = android.widget.ImageView(ctx).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = GradientDrawable().apply { cornerRadius = l.pxf(24f); setColor(if (showQr) Colors.WHITE else Colors.FILL) }
            val q = phone.qr
            if (q != null && showQr) setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, qrBitmap(q, box)).apply { isFilterBitmap = false })
        }
        if (showQr) side.addView(image, LinearLayout.LayoutParams(box, box))       // a used or ended code leaves no empty box
        val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        side.addView(info, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (showQr) leftMargin = l.px(48) })
        fun line(text: String, color: Int = Colors.TEXT_DIM, size: Txt = Txt.META) = TextView(ctx).apply {
            style(size, l.u, color); isSingleLine = false; maxLines = 4; this.text = text
            info.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (info.childCount > 0) topMargin = l.px(14) })
        }
        content.addView(side, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        var focus: View? = null
        when (p) {
            PhoneLink.Phase.IDLE, PhoneLink.Phase.STARTING -> line("Starting…")
            PhoneLink.Phase.NO_NETWORK -> line("This TV isn't on a Wi-Fi or Ethernet network. Connect it, then choose New Code.", orange)
            PhoneLink.Phase.FAILED -> line("Phone setup couldn't start on this network. Choose New Code to try again.", orange)
            PhoneLink.Phase.WAITING -> {
                line("Waiting for your phone…", Colors.WHITE, Txt.TITLE)
                line("Make sure your phone uses the same Wi-Fi network as this TV, not mobile data.")
                countdown = line("")
            }
            PhoneLink.Phase.CONFIRMING -> {
                line("A phone wants to connect", Colors.WHITE, Txt.TITLE)
                line(phone.code.orEmpty().toCharArray().joinToString("  "), Colors.WHITE, Txt.HERO)
                line("Allow it only if your phone shows the same code.")
                countdown = line("")
                focus = button("Allow", primary = true) { phone.allow() }
                button("Deny") { phone.deny() }
            }
            PhoneLink.Phase.PAIRED -> {
                line("✓  Phone connected", green, Txt.TITLE)
                line("Enter keys or choose pictures on the phone. Each key is tested before it is stored.")
                for (s in phone.saved) line(if (s.check.ok) "✓  ${s.key.label}: connected and saved" else "✕  ${s.key.label}: ${s.check.message}", if (s.check.ok) green else red)
                if (phone.photos > 0) line("✓  ${phone.photos} ${if (phone.photos == 1) "picture" else "pictures"} added to Home", green)
                phone.photoError?.let { line("✕  Picture: $it", red) }
            }
            PhoneLink.Phase.DENIED -> line("Connection refused. Choose New Code to try again.", orange)
            PhoneLink.Phase.EXPIRED -> line("This code has ended. Choose New Code to get a fresh one.", orange)
            PhoneLink.Phase.LOST -> line("The network connection was lost. Check the Wi-Fi, then choose New Code.", orange)
            PhoneLink.Phase.DONE -> line("Phone setup finished.", green)
        }
        if (p != PhoneLink.Phase.CONFIRMING) {
            val again = button("New Code") { phone.start() }
            if (p != PhoneLink.Phase.PAIRED && p != PhoneLink.Phase.WAITING) focus = again
        }
        val done = button(if (p == PhoneLink.Phase.PAIRED) "Done" else "Back") { leavePhone(); if (phoneOnly) close() else render() }
        if (focus == null) focus = done
        view.keepScreenOn = phone.live || p == PhoneLink.Phase.STARTING
        view.removeCallbacks(tick)
        if (countdown != null) tick.run()
        return focus
    }

    /** Crisp QR bitmap: whole pixels per module and a 4-module quiet zone, no scaling when drawn. */
    private fun qrBitmap(q: com.eppletv.launcher.pair.Qr, box: Int): android.graphics.Bitmap {
        val modules = q.size + 8
        val scale = maxOf(1, (box - l.px(24)) / modules)
        val px = modules * scale
        val pixels = IntArray(px * px)
        for (y in 0 until px) for (x in 0 until px) {
            pixels[y * px + x] = if (q.isDark(x / scale - 4, y / scale - 4)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return android.graphics.Bitmap.createBitmap(pixels, px, px, android.graphics.Bitmap.Config.ARGB_8888)
    }

    private fun languageChips() {
        val chosen = nav.prefs.langs.toMutableSet()
        // Two rows of four, so every name fits; a checkmark marks the chosen languages.
        Catalog.INDIAN.chunked(4).forEach { line ->
            val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
            line.forEach { code ->
                val name = Catalog.languageName(code)
                val chip = Pill(ctx, name, l.pxf(25f), l.pxf(28f), l.px(26), nav.clock, Colors.FILL, Colors.TEXT_DIM, 0.08f)
                fun paint() { val on = code in chosen; chip.selectedState = on; chip.text = if (on) "✓  $name" else name }
                paint()
                chip.setOnClickListener {
                    if (!chosen.remove(code)) chosen.add(code)
                    paint(); nav.prefs.langs = Catalog.INDIAN.filter { it in chosen }; changedKeys = true
                }
                wrap.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, l.px(58)).apply { if (wrap.childCount > 0) leftMargin = l.px(12) })
            }
            content.addView(wrap, LinearLayout.LayoutParams(WRAP_CONTENT, l.px(58)).apply { topMargin = l.px(12) })
        }
    }

    private fun status(k: Credentials.Key): String {
        val c = checks[k]
        val m = Credentials.masked(k)
        return when {
            c != null && c.ok -> "$m  ·  Connected"
            c != null -> c.message
            m.isEmpty() -> "Not set"
            else -> "$m  ·  ${Credentials.source(k)}"
        }
    }

    /** Key editor: a password field (the system keyboard types into it), Save & Test, Test, Remove. */
    private fun renderEditor(k: Credentials.Key): View {
        title.text = "${k.label} API Key"
        body.text = "${k.purpose}. Paste or type the key with the on-screen or a connected keyboard. It is tested with one request and stored encrypted on this TV."
        val field = EditText(ctx).apply {
            // Single-line first: setting it after the password type would reset the masking.
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = FullMask                 // never echoes the last typed character, unlike the platform's mask
            typeface = com.eppletv.launcher.ui.Type.of(500)
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            hint = if (Credentials.masked(k).isEmpty()) "Enter key" else "Current key ${Credentials.masked(k)}. Enter a new one to replace it"
            setTextColor(Colors.WHITE); setHintTextColor(Colors.TEXT_DIM); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, l.pxf(29f) * com.eppletv.launcher.ui.Type.scale)
            background = GradientDrawable().apply { cornerRadius = l.pxf(22f); setColor(Colors.FILL); setStroke(l.px(2), Colors.HAIRLINE) }
            setPadding(l.px(28), 0, l.px(28), 0)
        }
        content.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, l.px(84)))
        val result = note(checks[k]?.let { if (it.ok) "✓  Connected" else "✕  ${it.message}" } ?: "", if (checks[k]?.ok == true) 0xFF7CE38B.toInt() else 0xFFFF8A80.toInt())
        fun test(value: String, save: Boolean) {
            result.setTextColor(Colors.TEXT_DIM); result.text = "Testing…"
            nav.content.run({ Credentials.validate(nav.content.net, k, value) }) { c ->
                val check = c ?: Credentials.Check(false, "Connection failed")
                // The list shows the status of the key in use: a rejected new key leaves it untouched.
                if (!save || check.ok) checks[k] = check
                if (check.ok && save) { Credentials.set(k, value); changedKeys = true }
                if (editing == k) {
                    result.setTextColor(if (check.ok) 0xFF7CE38B.toInt() else 0xFFFF8A80.toInt())
                    result.text = if (check.ok) (if (save) "✓  Connected and saved" else "✓  Connected") else "✕  ${check.message}" + if (save) ". Not saved" else ""
                }
            }
        }
        field.setOnEditorActionListener { _, _, _ -> if (field.text.isNotBlank()) test(field.text.toString(), save = true); true }
        button("Save & Test", primary = true) { if (field.text.isNotBlank()) test(field.text.toString(), save = true) else { result.text = "Enter a key first"; result.setTextColor(0xFFFFB86B.toInt()) } }
        if (Credentials.hasAny(k)) button("Test Current Key") { test(Credentials.get(k), save = false) }
        if (Credentials.isSaved(k)) button("Remove") { Credentials.clear(k); checks.remove(k); changedKeys = true; result.setTextColor(Colors.TEXT_DIM); result.text = "Removed. " + if (k.built.isNotEmpty()) "The built-in key is used again." else "" }
        button("Back") { editing = null; render() }
        return field
    }
}

/** Masks every character of a key field at all times (the platform's password mask briefly shows the last one). */
private object FullMask : android.text.method.TransformationMethod {
    private class Masked(val s: CharSequence) : CharSequence {
        override val length get() = s.length
        override fun get(index: Int) = '\u2022'
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = "\u2022".repeat(endIndex - startIndex)
        override fun toString() = "\u2022".repeat(length)
    }
    override fun getTransformation(source: CharSequence, view: View): CharSequence = Masked(source)
    override fun onFocusChanged(view: View, sourceText: CharSequence, focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {}
}
