/*
 * Prof Live Wallpaper
 * Copyright (C) 2026 Massimo Nastasi
 *
 * This program is free software; you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details. You should have received a copy in
 * the file LICENSE; see also NOTICE.md for the third-party notices this work depends on.
 *
 * It is GPL-2.0 because it reproduces gameplay constants and tables from the id Software
 * engine source release (linuxdoom-1.10), which is GPL-2.0. Every such value carries a
 * comment naming the file and symbol it came from; those comments are the attribution the
 * licence requires and must not be removed.
 */
package io.github.massimonastasi.proflw

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isEmpty
import androidx.core.view.isVisible
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import java.nio.channels.FileChannel
import kotlin.math.abs

/**
 * The settings screen, reached from the wallpaper picker's own Settings button and from the
 * launcher icon.
 *
 * One activity over one layout. There is no preference library: the whole page is
 * res/layout/settings.xml, which means Android Studio draws it and it can be corrected by
 * looking rather than by installing. Every control is a Material 3 widget; the values go
 * straight to and from [Settings], which is three lines per row and is what the library was
 * doing underneath anyway.
 *
 * Choices apply as they are made. They were briefly staged behind a Save button, which is not
 * the Android convention and bought nothing: the wallpaper reads its settings when it next
 * becomes visible, so a staged copy was only a second state to keep in step with the first.
 */
class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { Settings.of(this) }

    /** Set once the tabs exist; see [swipeBetweenTabs]. */
    private var swipe: GestureDetector? = null

    /** The palette of whichever WAD is active, so the swatches show real colours. */
    private var palette = IntArray(256) { Color.BLACK }

    /**
     * One [SpriteSet] per sprite prefix, from the same WAD as [palette].
     *
     * Kept in a field on purpose: each set owns an LruCache of its own decoded frames, which
     * only earns anything if the set outlives the row that asked for a portrait. Null until
     * [loadWad] has run, and null again if it failed.
     */
    private var sprites: List<SpriteSet>? = null

    private val choosePhoto = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val ok = PhotoStore.import(this, uri)
        // The choice follows the file, not the tap that opened the picker: a cancelled or
        // unreadable pick leaves the background exactly where it was.
        if (ok) prefs.edit { putString(Settings.KEY_BACKGROUND, "photo") }
        toast(if (ok) getString(R.string.photo_imported) else getString(R.string.photo_unreadable))
        showBackground()
    }

    /**
     * The wallpaper picker, started for a result so this screen can get out of the way.
     *
     * Setting the wallpaper used to take four steps: the button, the system preview, choosing
     * where to apply it, and then closing this screen by hand - because the picker returned to
     * the settings it had been opened from, which is not where anyone wants to end up after
     * setting a wallpaper. The picker reports RESULT_OK only when the wallpaper was actually
     * applied, so finishing on that is the whole fix, and cancelling still comes back here.
     */
    private val setWallpaper =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) finish()
        }

    private val chooseWad = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val problem = WadStore.import(this, uri)
        if (problem != null) {
            explain(R.string.wad_rejected, problem)
            showSprites()
            return@registerForActivityResult
        }
        toast(getString(R.string.wad_imported))
        // Selected first, so the re-read below sees the file it is about to draw with.
        prefs.edit { putString(Settings.KEY_SPRITES, Settings.SPRITES_USER) }
        // A new WAD brings its own palette and its own sprites, so both are re-read - and the
        // statistics are re-drawn, because the portraits there come from this file too. The
        // counts do not move: they are keyed by lump name, not by which WAD is loaded.
        loadWad()
        showSprites()
        showBackground()
        showStatistics()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        setContentView(R.layout.settings)
        loadWad()


        // Read from the package rather than written down, so it cannot disagree with the
        // build that is running.
        findViewById<TextView>(R.id.header_caption).text = getString(
            R.string.settings_version,
            packageManager.getPackageInfo(packageName, 0).versionName,
        )

        // The bar sits on the very bottom of the window and keeps its own content clear of
        // the navigation bar, rather than being pushed up and leaving a strip of the page
        // showing underneath. The page reserves the same height at its end.
        val bar = findViewById<View>(R.id.button_bar)
        ViewCompat.setOnApplyWindowInsetsListener(bar) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottom + view.paddingTop)
            insets
        }
        bar.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val spacer = findViewById<View>(R.id.bottom_spacer)
            val height = bottom - top
            if (spacer.layoutParams.height != height) {
                spacer.layoutParams = spacer.layoutParams.also { it.height = height }
                spacer.requestLayout()
            }
        }

        // The scrim is measured from the status bar, which only the system knows: the same
        // number differs with the cutout, the gesture mode and the OEM. It is drawn twice
        // that tall so the gradient has room to reach nothing before the page resumes - the
        // dark part still covers the clock, and the fade below it has no edge.
        val scrim = findViewById<View>(R.id.status_scrim)
        ViewCompat.setOnApplyWindowInsetsListener(scrim) { view, insets ->
            val height = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top * SCRIM_REACH
            if (view.layoutParams.height != height) {
                view.layoutParams = view.layoutParams.also { it.height = height }
            }
            insets
        }

        pinAndFadeHeader()
        keepTheArtworkAtTheTop()
        keepPageScrollable()


        findViewById<MaterialButton>(R.id.set_wallpaper).setOnClickListener {
            SetupActivity.open(this) { setWallpaper.launch(it) }
        }
        findViewById<MaterialButton>(R.id.reset).setOnClickListener { confirmReset() }
        findViewById<MaterialButton>(R.id.import_wad).setOnClickListener {
            chooseWad.launch(arrayOf("*/*"))
        }

        showFrameRate()
        showZoom()
        showSwitches()
        showBackground()
        showSprites()
        showAbout()

        shapeGroup(R.id.switch_group)
        shapeGroup(R.id.background_group)
        shapeGroup(R.id.overlay_group)
        shapeGroup(R.id.about_group)

        showTabs()

        // After showSprites, which is the call that notices: the file is discarded the first
        // time anybody asks for it, and this is where the user finds out why it is gone.
        if (WadStore.takeStaleNotice(this)) explain(R.string.wad_stale_title, getString(R.string.wad_stale))
    }

    /**
     * Every page is at least tall enough to scroll the header away.
     *
     * The header collapses on what the page scrolls, so a page that ends before the header has
     * finished leaves it half open - the artwork stops a band short of gone and the tabs pin
     * underneath it. Statistics on a fresh install is three lines long, which is exactly when
     * it showed. The minimum is the viewport plus the header's travel, so the last row can
     * still reach the top of the screen.
     *
     * Re-applied on layout rather than once: the three pages differ in height and the tabs
     * swap between them.
     */
    private fun keepPageScrollable() {
        val page = findViewById<View>(R.id.page)
        val content = findViewById<View>(R.id.page_content)
        val bar = findViewById<AppBarLayout>(R.id.app_bar)
        page.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val least = (bottom - top) + bar.totalScrollRange
            if (content.minimumHeight != least) content.minimumHeight = least
        }
    }

    /**
     * The header fits the system windows, and keeps none of the padding that implies.
     *
     * It declares the attribute for what it tells the app bar above it - do not offset the
     * children, do shorten the scroll range - and a FrameLayout would otherwise answer by
     * padding itself out of the status bar, which is the one thing the artwork must not do.
     * Returning the insets untouched is what refuses that padding; nothing else consumes them,
     * so the scrim and the button bar still get their own.
     */
    private fun keepTheArtworkAtTheTop() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.header)) { _, insets -> insets }
    }

    /**
     * The header stays where it is and fades out instead of scrolling away.
     *
     * An AppBarLayout always keeps the top inset back from its scroll range - it is built for
     * a toolbar pinned under the status bar, and there is no toolbar here. Measured on a Pixel
     * 6a: the artwork stops 132px short of gone and that band, the bottom of the image with
     * the title on it, sits behind the clock above the pinned tabs. Nothing in the layout
     * fixes that, because the header is never asked to leave completely.
     *
     * So it is asked to leave visibly rather than physically. translationY cancels the scroll
     * exactly, which is where this page started before there were tabs: the image holds still
     * while the page and the strip climb over it. The alpha does the leaving, and at rest the
     * band behind the clock is the app bar's own opaque surface.
     *
     * The status scrim goes with it, on the same fraction. It is the last child of the layout
     * and therefore over everything, including the strip once that has pinned - a dark
     * gradient across the top of the tabs, which reads as a piece of the header showing
     * through them. It exists to keep white title text off the clock, and by then there is no
     * title left to protect.
     */
    private fun pinAndFadeHeader() {
        val scrim = findViewById<View>(R.id.status_scrim)
        val header = findViewById<View>(R.id.header)
        findViewById<AppBarLayout>(R.id.app_bar).addOnOffsetChangedListener(
            AppBarLayout.OnOffsetChangedListener { bar, offset ->
                val range = bar.totalScrollRange
                val gone = if (range == 0) 0f else abs(offset).toFloat() / range
                header.translationY = -offset.toFloat()
                header.alpha = 1f - gone
                scrim.alpha = 1f - gone
            }
        )
    }

    /**
     * The three pages, and the one mechanism behind them.
     *
     * Each tab owns a child of the scrolling column and the listener shows one and hides the
     * other two. No pager, no fragments: those exist to keep pages alive off-screen and to
     * animate between them, and these pages are three views that cost nothing to keep and do
     * not animate.
     *
     * Statistics is filled on selection rather than up front, because the wallpaper is still
     * running while this screen is open and the numbers move underneath it.
     */
    private fun showTabs() {
        val pages = listOf(R.id.tab_settings, R.id.tab_statistics, R.id.tab_about)
        val labels = listOf(R.string.tab_settings, R.string.tab_statistics, R.string.tab_about)
        val tabs = findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabs)

        for (label in labels) tabs.addTab(tabs.newTab().setText(label))

        fun show(index: Int) {
            pages.forEachIndexed { i, id -> findViewById<View>(id).isVisible = i == index }
            if (index == 1) showStatistics()
        }

        tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) = show(tab.position)
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
            // Re-reads the numbers, which is the only thing tapping the current tab could mean.
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) = show(tab.position)
        })
        show(0)

        swipeBetweenTabs(tabs, pages.size)
    }

    /**
     * A horizontal fling anywhere on the page moves to the next tab.
     *
     * ponytail: a fling, not a drag - the page does not follow the finger, because following
     * it is what a ViewPager2 is for, and that is a dependency and a fragment host this app
     * does not have for three views that are shown and hidden.
     *
     * Fed from [dispatchTouchEvent] rather than a touch listener on the scrolling view: the
     * rows are clickable and swallow the gesture before any parent sees it, so a swipe that
     * began on a row did nothing. Dispatch sees every event whoever ends up consuming it, and
     * nothing is consumed here - the detector only reads what goes past.
     */
    private fun swipeBetweenTabs(tabs: com.google.android.material.tabs.TabLayout, count: Int) {
        val slop = ViewConfiguration.get(this).scaledMinimumFlingVelocity * 2
        swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                // Horizontal has to win outright, or a fast diagonal scroll changes the tab.
                if (abs(vx) < slop || abs(vx) < abs(vy) * 2) return false
                val next = tabs.selectedTabPosition + if (vx < 0) 1 else -1
                if (next in 0 until count) tabs.selectTab(tabs.getTabAt(next))
                return false
            }
        })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        swipe?.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    // ------------------------------------------------------------------ sections

    private fun showFrameRate() {
        val group = findViewById<MaterialButtonToggleGroup>(R.id.fps_group)
        val ids = intArrayOf(R.id.fps_10, R.id.fps_15, R.id.fps_20)
        val values = intArrayOf(10, 15, 20)
        group.check(ids[values.indexOf(Settings.fps(prefs)).coerceAtLeast(0)])
        group.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val fps = values[ids.indexOf(id).coerceAtLeast(0)]
            prefs.edit { putString(Settings.KEY_FPS, fps.toString()) }
            knock(
                when (id) {
                    R.id.fps_10 -> Settings.Knock.TEN
                    R.id.fps_20 -> Settings.Knock.TWENTY
                    // 15 fps is in neither position, so it ends the attempt rather than
                    // standing in for the one that is expected next.
                    else -> Settings.Knock.NONE
                },
            )
        }
    }

    /**
     * The sprite zoom, driven exactly like the frame rate above and stored the same way.
     *
     * It is not part of the knock sequence: that reads three controls and adding a fourth
     * would change a sequence people have written down.
     */
    private fun showZoom() {
        val group = findViewById<MaterialButtonToggleGroup>(R.id.zoom_group)
        val ids = intArrayOf(R.id.zoom_075, R.id.zoom_100, R.id.zoom_125, R.id.zoom_150)
        val values = floatArrayOf(0.75f, 1f, 1.25f, 1.5f)
        group.check(ids[values.indexOfFirst { it == Settings.zoom(prefs) }.coerceAtLeast(0)])
        group.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val zoom = values[ids.indexOf(id).coerceAtLeast(0)]
            prefs.edit { putString(Settings.KEY_ZOOM, zoom.toString()) }
        }
    }

    private fun showSwitches() {
        switchRow(
            R.id.row_readout, R.string.settings_readout, R.string.settings_readout_note,
            Settings.KEY_READOUT, default = true, onTap = { knock(Settings.Knock.READOUT) },
        )
        switchRow(
            R.id.row_god, R.string.settings_god_mode, R.string.settings_god_mode_note,
            Settings.KEY_GOD_MODE, default = false, onTap = { knock(Settings.Knock.GOD) },
        )

        val unlocked = Settings.debugUnlocked(prefs)
        findViewById<View>(R.id.row_debug).isVisible = unlocked
        if (unlocked) switchRow(
            R.id.row_debug, R.string.settings_debug, R.string.settings_debug_note,
            Settings.KEY_DEBUG, default = false,
        )
    }

    // ------------------------------------------------------------------ the knock

    /*
     * The debug overlay is a developer's readout, so its row is not on the screen until
     * somebody who knows where to press finds it. The sequence and the counting rule are in
     * [Settings.Knock]; what is left here is only the part that needs an Activity.
     */

    private var knocked = 0

    private val disarm = Runnable { knocked = 0 }

    /**
     * A step of the sequence, and nothing at all until the first one.
     *
     * While [knocked] is zero this returns on the opening line for every control except the
     * readout row: there is deliberately no watcher counting taps across the screen, only a
     * count that the readout row starts and that forgets itself after
     * [KNOCK_TIMEOUT_MS] or on the first control that is not next in the sequence.
     */
    private fun knock(step: Int) {
        if (Settings.debugUnlocked(prefs)) return
        if (knocked == 0 && step != Settings.Knock.SEQUENCE[0]) return

        val row = findViewById<View>(R.id.row_readout)
        row.removeCallbacks(disarm)
        knocked = Settings.Knock.advance(knocked, step)
        if (!Settings.Knock.complete(knocked)) {
            if (knocked > 0) row.postDelayed(disarm, KNOCK_TIMEOUT_MS)
            return
        }

        knocked = 0
        prefs.edit { putBoolean(Settings.KEY_DEBUG_UNLOCKED, true) }
        showSwitches()
        shapeGroup(R.id.switch_group)
        toast(getString(R.string.settings_debug_unlocked))
    }

    /**
     * The background rows, and what the photo row says about itself.
     *
     * Once an image is chosen the row shows its file name rather than "from your device",
     * which tells nobody which image is in use, and its chevron becomes a bin - the same
     * gesture as the imported WAD, because it is the same kind of thing: a file the user put
     * there and is the only one who can take away.
     */
    private fun showBackground() {
        // The veil belongs to this section rather than to the visual switches: it is about the
        // background, whichever of the three the rows above chose.
        switchRow(
            R.id.row_overlay, R.string.settings_overlay, R.string.settings_overlay_note,
            Settings.KEY_OVERLAY, default = false,
        )

        val rows = intArrayOf(R.id.row_floor, R.id.row_colour, R.id.row_photo)
        val values = arrayOf("dynamic", "colour", "photo")
        val photo = PhotoStore.name(this)
        // "photo" with no photo behind it is what the wallpaper draws as the dungeon floor,
        // so it is what the screen shows selected too. The two used to disagree.
        val chosen = prefs.getString(Settings.KEY_BACKGROUND, "dynamic")
            .takeUnless { it == "photo" && photo == null } ?: "dynamic"

        row(R.id.row_floor, R.string.background_floor, getString(R.string.background_floor_note))
        row(R.id.row_colour, R.string.background_colour, getString(R.string.background_colour_note))
        row(R.id.row_photo, R.string.background_photo, photo ?: getString(R.string.background_photo_note))

        // The swatches live inside the flat-colour row, which is where the design puts them
        // and the only arrangement in which the radio clearly owns them.
        val extra = findViewById<View>(R.id.row_colour)
            .findViewById<android.widget.FrameLayout>(R.id.row_extra)
        if (extra.isEmpty()) {
            val grid = SwatchGrid(
                this,
                resources.getTextArray(R.array.palette_labels),
                resources.getTextArray(R.array.palette_values),
            )
            grid.colourOf = { palette[it.coerceIn(0, 255)] }
            grid.onChosen = { prefs.edit { putString(Settings.KEY_BACKGROUND_COLOUR, it) } }
            grid.show(prefs.getString(Settings.KEY_BACKGROUND_COLOUR, "0"))
            // match_parent, so the grid is measured against the width the row has left rather
            // than against its own children. The frame around it stays wrap_content: it is
            // shared with every other row and its width is not this one's business.
            extra.addView(
                grid,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        extra.visibility = View.VISIBLE

        // Choosing "Image" with nothing behind it would show nothing, so it asks; once there
        // is one, the trailing button removes it.
        if (photo != null) {
            action(R.id.row_photo, R.drawable.ic_delete) {
                PhotoStore.clear(this)
                // The image it pointed at is gone, so the choice goes with it. Left alone the
                // row stayed selected over nothing, and the dungeon floor - which is what the
                // wallpaper actually draws in that state - showed as unselected.
                prefs.edit { remove(Settings.KEY_BACKGROUND) }
                showBackground()
            }
        } else {
            pointer(R.id.row_photo)
        }

        rows.forEachIndexed { i, id ->
            select(id, values[i] == chosen)
            findViewById<View>(id).setOnClickListener {
                if (values[i] == "photo" && photo == null) {
                    choosePhoto.launch(arrayOf("image/*"))
                    return@setOnClickListener
                }
                prefs.edit { putString(Settings.KEY_BACKGROUND, values[i]) }
                showBackground()
            }
        }
    }

    /** The sprite sets on disk: the bundled one, plus an imported WAD if there is one. */
    private fun showSprites() {
        val user = WadStore.active(this)
        val useUser = user != null && Settings.useUserWad(prefs)

        row(R.id.row_bundled, R.string.sprites_bundled, getString(R.string.sprites_bundled_note))
        select(R.id.row_bundled, !useUser)

        val wadRow = findViewById<View>(R.id.row_wad)
        // The row exists only when the file does. Nothing is bundled beyond Freedoom, so at
        // first launch there is one option and it is not offered as a choice.
        wadRow.visibility = if (user == null) View.GONE else View.VISIBLE
        shapeGroup(R.id.sprites_group)
        findViewById<View>(R.id.row_bundled).findViewById<View>(R.id.row_radio).isEnabled = user != null

        if (user != null) {
            // Named, not described: someone who has imported more than one over a week needs
            // to see which file is in, and "your own WAD" answers no question.
            val label = WadStore.name(this) ?: getString(R.string.wad_unnamed)
            val row = wadRow.findViewById<TextView>(R.id.row_label)
            row.text = label
            caption(R.id.row_wad, getString(R.string.sprites_size, user.length() / 1024))
            select(R.id.row_wad, useUser)
            action(R.id.row_wad, R.drawable.ic_delete) { confirmDeleteWad() }
            shapeGroup(R.id.sprites_group)
            // These two rows change which WAD is active, so everything read from a WAD has to
            // follow: the palette behind the swatches and the portraits in the statistics. They
            // used to refresh only the radios, which left both showing the other file's
            // artwork until the screen was reopened.
            wadRow.setOnClickListener { chooseSprites(Settings.SPRITES_USER) }
            findViewById<View>(R.id.row_bundled).setOnClickListener {
                chooseSprites(Settings.SPRITES_BUNDLED)
            }
        }
    }

    /**
     * Switches between the bundled sprites and an imported WAD, and re-reads everything that
     * comes out of one.
     *
     * The counts are untouched by design: statistics are keyed by lump name, so changing WAD
     * changes every portrait on the statistics page and moves no number on it.
     */
    private fun chooseSprites(which: String) {
        prefs.edit { putString(Settings.KEY_SPRITES, which) }
        loadWad()
        showSprites()
        showBackground()
        showStatistics()
    }

    private fun showAbout() {

        // Not a courtesy: GPL-2.0 section 3 requires that whoever receives the binary can get
        // the corresponding source, and for an application handed out as an APK this row is
        // how that is offered. It stays enabled from here on.
        link(R.id.row_source, R.string.settings_source, getString(R.string.settings_source_note)) {
            open(getString(R.string.repo_url))
        }

        link(R.id.row_licences, R.string.settings_licences, getString(R.string.settings_licences_note)) {
            openLicences()
        }

        // The report says something different when there is a trace waiting, because that is
        // the one moment the row is worth noticing rather than being a permanent offer.
        val crashed = CrashLog.read(this) != null
        val note = if (crashed) R.string.settings_report_crash_note else R.string.settings_report_note
        link(R.id.row_report, R.string.settings_report, getString(note)) {
            open(CrashLog.issueUrl(this, prefs))
            // Cleared on the way out, not on return: the user has seen it, and a trace that
            // reappears every time the screen opens reads as an app still broken.
            CrashLog.clear(this)
            showAbout()
        }
    }

    /** A row that goes somewhere: no radio, a chevron, and the whole row is the target. */
    private fun link(id: Int, label: Int, note: String, onClick: () -> Unit) {
        row(id, label, note)
        findViewById<View>(id).findViewById<View>(R.id.row_radio).visibility = View.GONE
        pointer(id)
        findViewById<View>(id).setOnClickListener { onClick() }
    }

    /**
     * Hands a URL to whatever the user browses with.
     *
     * No in-app browser and no custom tab: this application requests no permissions and opens
     * no network connection of its own, and both of those would make that sentence false. The
     * page is theirs to read, in their browser, signed into their own account.
     */
    private fun open(url: String) {
        // Try and catch, not resolveActivity. Since Android 11 an application sees only the
        // packages it declares in <queries>, so resolveActivity returns null whether or not a
        // browser exists - measured here: it returned null on a device with Vivaldi installed
        // and handling https, and the row silently reported that nothing could open a page.
        // Declaring <queries> would fix the query; not asking the question fixes the feature.
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: android.content.ActivityNotFoundException) {
            toast(getString(R.string.no_browser))
        }
    }

    /**
     * The completion record, as the first row of the Statistics tab.
     *
     * Absent until the wave table has been finished at the hardest skill. There is no row
     * saying "not yet": a screen that lists things that have not happened is a screen making
     * promises, and this is the one thing here that is earned rather than set.
     *
     * ## It used to be a chip, and the chip truncated
     *
     * An assist chip was the shape for a while, on the argument that a count and a date are a
     * record while a list row is a control. The argument was sound and the widget was not:
     * `Chip` is single-line by construction, `ChipDrawable` ellipsizes the label to whatever
     * width it is handed, and `maxLines`, `singleLine` and `ellipsize` are all overridden or
     * ignored. "Finished 2 times, first on 28 July 2026" does not fit a 360dp phone and no
     * attribute was going to make it.
     *
     * On a page of records the old objection stops applying, so the count is the label and
     * the date is the caption, and both wrap.
     */
    private fun showCompleted() {
        val group = findViewById<LinearLayout>(R.id.record_group)
        group.removeAllViews()
        val runs = Settings.completions(prefs)
        // The heading goes with the row: "Record" over nothing reads like a missing record
        // rather than like a marine who has not finished the hardest table yet.
        findViewById<View>(R.id.record_header).isVisible = runs > 0
        if (runs <= 0) {
            group.isVisible = false
            return
        }
        group.isVisible = true
        // Formatted by the platform, so the order of day and month is the reader's own.
        val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG)
            .format(java.util.Date(Settings.firstCompletion(prefs)))
        // The date is the supporting line, not a trailing column: as trailing text it took
        // the whole row and folded "Finished once" into a stack one letter wide.
        statRow(
            group,
            resources.getQuantityString(R.plurals.settings_completed_record, runs, runs),
            getString(R.string.settings_completed_first, date),
            sprite = null,
            scale = 1f,
            icon = R.drawable.ic_completed,
        )
        shapeGroup(R.id.record_group)
    }

    /**
     * One statistics row: a sprite, a line of text, and a second line when there is one.
     *
     * [scale] is the section's own, not the row's: every sprite in a list is drawn at the
     * same magnification so their sizes mean something next to each other. Without it a
     * stimpack, which is 19x10 pixels, was blown up to the same box as a Cyberlord.
     */
    private fun statRow(
        group: LinearLayout,
        label: String,
        caption: String?,
        sprite: Bitmap?,
        scale: Float,
        icon: Int = 0,
    ) {
        val row = layoutInflater.inflate(R.layout.stat_row, group, false)
        val image = row.findViewById<ImageView>(R.id.stat_sprite)
        val box = resources.getDimensionPixelSize(R.dimen.stat_sprite_size).toFloat()

        if (sprite != null) {
            // A BitmapDrawable rather than setImageBitmap, so the filter can be turned off:
            // these are small sprites blown up on a 3x screen, and bilinear filtering turns
            // pixel art into porridge. The wallpaper draws them the same way.
            image.setImageDrawable(BitmapDrawable(resources, sprite).apply { isFilterBitmap = false })
            image.imageTintList = null
            image.imageMatrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate((box - sprite.width * scale) / 2f, (box - sprite.height * scale) / 2f)
            }
        } else {
            // A tally outlives the file that earned it - the keys are lump names - so a WAD
            // that does not carry this creature still has to show its row. The slot keeps its
            // width either way, or the column of pictures would break wherever one is missing.
            image.setImageResource(if (icon != 0) icon else R.drawable.ic_no_sprite)
            image.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(image, com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
            image.imageMatrix = Matrix().apply {
                val d = image.drawable ?: return@apply
                val k = box / maxOf(d.intrinsicWidth, d.intrinsicHeight).toFloat()
                setScale(k, k)
                postTranslate((box - d.intrinsicWidth * k) / 2f, (box - d.intrinsicHeight * k) / 2f)
            }
        }

        row.findViewById<TextView>(R.id.stat_label).text = label
        row.findViewById<TextView>(R.id.stat_caption).apply {
            text = caption.orEmpty()
            isVisible = !caption.isNullOrEmpty()
        }
        row.contentDescription = if (caption != null) "$label. $caption" else label

        if (group.childCount > 0) {
            (row.layoutParams as? ViewGroup.MarginLayoutParams)
                ?.topMargin = resources.getDimensionPixelSize(R.dimen.list_row_gap)
        }
        group.addView(row)
    }

    /**
     * Fills the Statistics tab from the preferences.
     *
     * Called on every tab selection rather than once: the wallpaper is still running while this
     * screen is open, so the numbers move underneath it.
     */
    private fun showStatistics() {
        showCompleted()

        val met = Statistics.encounters(prefs)
        val pickups = Statistics.pickups(prefs)

        // One scale per section, from the tallest sprite in it: see statRow.
        val faces = met.map { portrait(it.creature.spriteIndex) }
        // The creatures are drawn half again as large as they would fit: a bestiary that
        // spans a zombie and an Overlord leaves the small ones too small to tell apart, and
        // the tallest overflowing its box a little costs less than that. The pickups need no
        // such help - they are all within a factor of three of each other.
        fill(R.id.kills_group, R.id.kills_header, magnify = 1.5f, rows = met.mapIndexed { i, e ->
            // Bare numbers: the legend under the heading says which is which, so every row
            // does not have to repeat it.
            Row(
                e.killed.toString(),
                // A creature that has never killed him says so by not saying it.
                if (e.killedBy > 0) e.killedBy.toString() else null,
                faces[i],
            )
        })

        val things = pickups.map { portrait(it.first.spriteIndex) }
        fill(R.id.pickups_group, R.id.pickups_header, pickups.mapIndexed { i, (_, n) ->
            Row(n.toString(), null, things[i])
        })

        // One sentence instead of empty headings: a statistics page with nothing under any
        // of them reads like a fault rather than like a marine who has just started.
        val anything = Settings.completions(prefs) > 0 || met.isNotEmpty() || pickups.isNotEmpty()
        findViewById<View>(R.id.stats_empty).isVisible = !anything
    }

    /** What a statistics row shows: a line, a second line when there is one, a picture. */
    private class Row(val label: String, val caption: String?, val sprite: Bitmap?)

    private fun fill(groupId: Int, headerId: Int, rows: List<Row>, magnify: Float = 1f) {
        val group = findViewById<LinearLayout>(groupId)
        group.removeAllViews()
        findViewById<View>(headerId).isVisible = rows.isNotEmpty()
        group.isVisible = rows.isNotEmpty()

        // The scale the whole section is drawn at: the tallest sprite fills the box and the
        // rest keep their height relative to it.
        //
        // Height, not the larger of the two sides. The Overlord is 256 pixels wide against
        // the 102 of the next biggest - it is a spider, drawn legs out - and scaling to fit
        // that width left a zombie nine dp tall. Height is what the eye compares between
        // figures standing on a floor, and the one creature wider than its box is clipped at
        // the sides, which costs it some legs and no recognition.
        val box = resources.getDimensionPixelSize(R.dimen.stat_sprite_size).toFloat()
        val tallest = rows.mapNotNull { it.sprite }.maxOfOrNull { it.height } ?: 1
        val scale = box / tallest

        for (r in rows) statRow(group, r.label, r.caption, r.sprite, scale * magnify)
        if (rows.isNotEmpty()) shapeGroup(groupId)
    }

    private fun openLicences() = startActivity(Intent(this, LicencesActivity::class.java))

    /**
     * Gives every visible row in a group the corner shape for where it sits.
     *
     * Material ships the four shapes - Single, First, Middle, Last - and a segmented list is
     * just the right one on each row: big corners at the ends of the group, small in between.
     * Doing it here rather than in the layout is what lets a row appear and disappear, which
     * two of these groups do: the WAD row exists only once one is imported, and the group has
     * to close up around it.
     */
    private fun shapeGroup(groupId: Int) {
        val group = findViewById<android.view.ViewGroup>(groupId)
        val rows = (0 until group.childCount)
            .map { group.getChildAt(it) }
            .filter { it.isVisible }
            .filterIsInstance<com.google.android.material.card.MaterialCardView>()

        rows.forEachIndexed { i, card ->
            val style = when {
                rows.size == 1 -> com.google.android.material.R.style.ShapeAppearance_Material3_ListItem_Single
                i == 0 -> com.google.android.material.R.style.ShapeAppearance_Material3_ListItem_First
                i == rows.lastIndex -> com.google.android.material.R.style.ShapeAppearance_Material3_ListItem_Last
                else -> com.google.android.material.R.style.ShapeAppearance_Material3_ListItem_Middle
            }
            card.shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel
                .builder(this, style, 0)
                .build()
        }
    }

    // ------------------------------------------------------------------ row helpers

    /** Label and supporting line. The row is one included layout, so this is how it is filled. */
    private fun row(id: Int, label: Int, caption: String?) {
        val row = findViewById<View>(id)
        row.findViewById<TextView>(R.id.row_label).setText(label)
        caption(id, caption)
    }

    private fun caption(id: Int, text: String?) {
        findViewById<View>(id).findViewById<TextView>(R.id.row_caption).apply {
            this.text = text
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    /** activated, not selected: selected is a transient touch state, activated persists. */
    private fun select(id: Int, on: Boolean) {
        val row = findViewById<View>(id)
        row.isActivated = on
        row.findViewById<com.google.android.material.radiobutton.MaterialRadioButton>(R.id.row_radio)
            .isChecked = on
    }

    /**
     * A trailing action with its own target: a button, for deleting a file the user imported.
     */
    private fun action(id: Int, icon: Int, onClick: () -> Unit) {
        val row = findViewById<View>(id)
        row.findViewById<View>(R.id.row_icon).visibility = View.GONE
        row.findViewById<MaterialButton>(R.id.row_action).apply {
            visibility = View.VISIBLE
            setIconResource(icon)
            setOnClickListener { onClick() }
        }
    }

    /**
     * A trailing icon that only points: the row itself is what gets tapped.
     *
     * Not a button. It has no container of its own and is outside the accessibility tree,
     * because a control announced beside a row that does the same thing is one target too
     * many.
     */
    private fun pointer(id: Int) {
        val row = findViewById<View>(id)
        row.findViewById<MaterialButton>(R.id.row_action).visibility = View.GONE
        row.findViewById<android.widget.ImageView>(R.id.row_icon).apply {
            visibility = View.VISIBLE
            setImageResource(R.drawable.ic_chevron)
        }
    }

    private fun switchRow(
        id: Int,
        label: Int,
        caption: Int,
        key: String,
        default: Boolean,
        onTap: (() -> Unit)? = null,
    ) {
        val root = findViewById<View>(id)
        root.findViewById<View>(R.id.row_radio).visibility = View.GONE
        row(id, label, getString(caption))

        val toggle = root.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.row_switch)
        toggle.visibility = View.VISIBLE
        var on = prefs.getBoolean(key, default)
        toggle.isChecked = on
        root.isActivated = on
        root.setOnClickListener {
            on = !on
            prefs.edit { putBoolean(key, on) }
            toggle.isChecked = on
            root.isActivated = on
            onTap?.invoke()
        }
    }

    // ------------------------------------------------------------------ actions

    private fun confirmDeleteWad() {
        AlertDialog.Builder(this)
            .setTitle(R.string.wad_delete)
            .setMessage(R.string.wad_delete_confirm)
            .setPositiveButton(R.string.wad_delete) { _, _ ->
                WadStore.clear(this)
                prefs.edit { putString(Settings.KEY_SPRITES, Settings.SPRITES_BUNDLED) }
                loadWad()
                showSprites()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Puts everything back as it was installed, files included.
     *
     * The imported WAD and photo go too: they are the only things here that occupy real
     * storage, and a reset that left tens of megabytes behind would not be one.
     */
    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_reset)
            .setMessage(R.string.settings_reset_confirm)
            .setPositiveButton(R.string.settings_reset) { _, _ ->
                prefs.edit { clear() }
                WadStore.clear(this)
                PhotoStore.clear(this)
                loadWad()
                // Recreated rather than refreshed: every row's value has changed underneath
                // the views, and rebuilding is the honest way to show that.
                recreate()
                toast(getString(R.string.settings_reset_done))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Reads the active WAD once, for both the swatches and the statistics portraits.
     *
     * One read rather than two because [WadStore.active] can *delete* a stale file as a side
     * effect and the notice it leaves is one-shot: two separate reads could see two different
     * answers about which WAD is in play.
     *
     * The condition is the wallpaper's own - `useUserWad` and then `active` - which it was not
     * before. Reading `active` alone meant that importing a WAD and then selecting Bundled left
     * the swatches showing the imported palette while the wallpaper drew Freedoom's. The
     * portraits would have inherited that, and would have shown creatures from a file that is
     * not on the home screen.
     */
    private fun loadWad() {
        // Dropped before the new ones are built, so the old SpriteSets and their caches go.
        sprites = null
        palette = try {
            val user = if (Settings.useUserWad(prefs)) WadStore.active(this) else null
            val buf = if (user != null) {
                user.inputStream().use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, user.length()) }
            } else {
                val afd = assets.openFd("freedoom2.wad")
                afd.createInputStream().use { s ->
                    s.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
                }
            }
            val wad = WadFile(buf)
            // Built once and kept: each SpriteSet holds an LruCache of its own frames, which is
            // only worth anything if the object outlives the row that asked for it. Rebuilding
            // them per tab selection would re-decode every portrait every time.
            sprites = GameData.spritePrefixes.map { SpriteSet(wad, it) }
            IntArray(256) { wad.paletteColor(it) }
        } catch (e: Exception) {
            IntArray(256) { Color.BLACK }
        }
    }

    /**
     * The portrait for a creature or a pickup, or null when this WAD cannot draw it.
     *
     * Frame A, rotation 1 - standing, facing the viewer. It is the pair the rest of the app
     * already treats as "show me this actor", and rotation 1 survives the WAD reducer, so it is
     * there in the bundled asset and in anything imported.
     *
     * Three separate ways to come back empty, all of them real: the WAD has no lumps for this
     * prefix, the prefix is there but that frame is not, or the lump refused to decode.
     */
    private fun portrait(spriteIndex: Int): Bitmap? {
        val set = sprites?.getOrNull(spriteIndex) ?: return null
        if (set.frameCount <= 0) return null
        val packed = set.resolve(0, 1)
        if (packed < 0) return null
        return set.sprite(packed shr 1)?.bitmap
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    /**
     * For what the user must not miss, where a toast would not do.
     *
     * A toast is not shown at all when the user has refused this app's notifications, which is
     * what happens by default on Android 13 - the message is composed, handed to the system and
     * dropped. A rejected WAD import announced that way is indistinguishable from an import that
     * silently did nothing, which is exactly how it was reported to us. A dialog cannot be
     * suppressed by a permission, so anything that explains a refusal goes through here.
     */
    private fun explain(title: Int, message: String) = AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(android.R.string.ok, null)
        .show()

    private companion object {
        /**
         * How far past the status bar the scrim reaches, as a multiple of its height.
         *
         * The dark end of the gradient covers the clock; the rest is the fade, and the fade
         * needs somewhere to happen. One would put the edge exactly where the status bar ends,
         * which is the flat band this replaced.
         */
        const val SCRIM_REACH = 2

        /** How long the knock waits for its next step before forgetting it was started. */
        const val KNOCK_TIMEOUT_MS = 5_000L
    }
}
