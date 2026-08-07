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
import android.graphics.Color
import android.os.Bundle
import android.view.View
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import java.nio.channels.FileChannel

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

    /** The palette of whichever WAD is active, so the swatches show real colours. */
    private var palette = IntArray(256) { Color.BLACK }

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
        toast(problem ?: getString(R.string.wad_imported))
        if (problem == null) {
            // A new WAD brings its own palette, so the swatches have to be re-read.
            loadPalette()
            prefs.edit { putString(Settings.KEY_SPRITES, Settings.SPRITES_USER) }
        }
        showSprites()
        showBackground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        setContentView(R.layout.settings)
        loadPalette()


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

        findViewById<MaterialButton>(R.id.set_wallpaper).setOnClickListener {
            SetupActivity.open(this) { setWallpaper.launch(it) }
        }
        findViewById<MaterialButton>(R.id.reset).setOnClickListener { confirmReset() }
        findViewById<MaterialButton>(R.id.import_wad).setOnClickListener {
            chooseWad.launch(arrayOf("*/*"))
        }

        showFrameRate()
        showSwitches()
        showBackground()
        showSprites()
        showAbout()

        shapeGroup(R.id.switch_group)
        shapeGroup(R.id.background_group)
        shapeGroup(R.id.overlay_group)
        shapeGroup(R.id.about_group)

        // After showSprites, which is the call that notices: the file is discarded the first
        // time anybody asks for it, and this is where the user finds out why it is gone.
        if (WadStore.takeStaleNotice(this)) toast(getString(R.string.wad_stale))
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
            wadRow.setOnClickListener {
                prefs.edit { putString(Settings.KEY_SPRITES, Settings.SPRITES_USER) }
                showSprites()
            }
            findViewById<View>(R.id.row_bundled).setOnClickListener {
                prefs.edit { putString(Settings.KEY_SPRITES, Settings.SPRITES_BUNDLED) }
                showSprites()
            }
        }
    }

    private fun showAbout() {
        showCompleted()

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
     * The completion marker, and the tooltip that carries what it knows.
     *
     * Absent until the wave table has been finished at the hardest skill. There is no row
     * saying "not yet": a screen that lists things that have not happened is a screen making
     * promises, and this is the one thing here that is earned rather than set.
     *
     * An assist chip rather than a list row because of what it holds: a count and a date are
     * a record, and a list row is a control. It is not clickable - there is nothing to open.
     *
     * ## Not a tooltip
     *
     * A tooltip was the first shape tried, and Material 3 for Views has no public one. The
     * library carries `TooltipDrawable` and a `Widget.Material3.Tooltip` style, which look
     * like the obvious answer until lint refuses them in thirteen places: both are
     * `@RestrictedApi(LIBRARY_GROUP)`, built for the Slider rather than for applications.
     *
     * The chip is better anyway. A tooltip hides its content behind a gesture almost nobody
     * performs, and this is the one thing on the screen worth being seen.
     */
    private fun showCompleted() {
        val chip = findViewById<Chip>(R.id.completed)
        val runs = Settings.completions(prefs)
        if (runs <= 0) {
            chip.visibility = View.GONE
            return
        }
        // Formatted by the platform, so the order of day and month is the reader's own.
        val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG)
            .format(java.util.Date(Settings.firstCompletion(prefs)))
        chip.text = resources.getQuantityString(R.plurals.settings_completed_record, runs, runs, date)
        chip.visibility = View.VISIBLE
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
                loadPalette()
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
                loadPalette()
                // Recreated rather than refreshed: every row's value has changed underneath
                // the views, and rebuilding is the honest way to show that.
                recreate()
                toast(getString(R.string.settings_reset_done))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Reads the active WAD's palette, so the swatches are the wallpaper's own colours. */
    private fun loadPalette() {
        palette = try {
            val user = WadStore.active(this)
            val buf = if (user != null) {
                user.inputStream().use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, user.length()) }
            } else {
                val afd = assets.openFd("freedoom2.wad")
                afd.createInputStream().use { s ->
                    s.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
                }
            }
            val wad = WadFile(buf)
            IntArray(256) { wad.paletteColor(it) }
        } catch (e: Exception) {
            IntArray(256) { Color.BLACK }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

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
