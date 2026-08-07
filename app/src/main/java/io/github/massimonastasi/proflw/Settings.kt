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

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The user's choices, and the only place their names are written down.
 *
 * ponytail: SharedPreferences directly, with no settings framework on top. There are three
 * values; a preference library would be a runtime dependency and a second way to describe
 * each of them, in exchange for widgets this does not need.
 */
object Settings {

    /** Shared with the preference screen, which is told to use this file rather than the default. */
    const val FILE = "settings"

    // Only the keys that store something. There used to be one per row, including rows that
    // store nothing at all - reset, set_wallpaper, source, notices, wad_delete - because the
    // preference library addressed every row by key. Nothing has addressed a row by key since
    // that library was dropped, and seven of these had no reader left anywhere.
    const val KEY_FPS = "fps"
    const val KEY_READOUT = "readout"
    const val KEY_BACKGROUND = "background"
    const val KEY_BACKGROUND_COLOUR = "background_colour"
    const val KEY_COMPLETIONS = "completions"
    const val KEY_FIRST_COMPLETION = "first_completion"
    const val KEY_GOD_MODE = "god_mode"
    const val KEY_OVERLAY = "overlay"
    const val KEY_DEBUG = "debug"
    const val KEY_DEBUG_UNLOCKED = "debug_unlocked"
    const val KEY_SPRITES = "sprites"

    const val SPRITES_BUNDLED = "bundled"
    const val SPRITES_USER = "user"

    /**
     * Which sprite set is in use. Importing a WAD keeps it; this says whether it is drawn,
     * so the bundled assets remain a choice rather than being replaced outright.
     */
    fun useUserWad(p: SharedPreferences): Boolean =
        p.getString(KEY_SPRITES, SPRITES_BUNDLED) == SPRITES_USER

    const val DEFAULT_FPS = 20

    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Stored as a string because that is what a ListPreference writes, and one format is
     * better than two with a conversion between them.
     */
    fun fps(p: SharedPreferences): Int =
        p.getString(KEY_FPS, null)?.toIntOrNull()?.takeIf { it in 5..60 } ?: DEFAULT_FPS

    /** Health and armour, drawn at the bottom of the screen. */
    fun readout(p: SharedPreferences): Boolean = p.getBoolean(KEY_READOUT, true)

    /**
     * The marine cannot be hurt.
     *
     * It defeats the rule that a death restarts at wave 1 on the lowest skill - with this on,
     * that rule simply never fires and the ladder only ever climbs. That is the point of it,
     * not an oversight.
     */
    fun godMode(p: SharedPreferences): Boolean = p.getBoolean(KEY_GOD_MODE, false)

    /**
     * A dark layer between the ground and the fight, for home screens whose icon labels are
     * losing against a bright flat.
     *
     * Off by default, and that is the whole of why it came back rather than being restored as
     * it was: Android already dims the wallpaper behind the launcher, so on it is two dark
     * layers, which is the reason it was removed. It is a choice for the few screens that
     * need it, not the default for everyone.
     */
    fun overlay(p: SharedPreferences): Boolean = p.getBoolean(KEY_OVERLAY, false)

    /**
     * Whether the debug row is shown at all. It is a developer's readout, not a feature, so
     * it is off the settings screen until the knock in [SettingsActivity] finds it.
     */
    fun debugUnlocked(p: SharedPreferences): Boolean = p.getBoolean(KEY_DEBUG_UNLOCKED, false)

    /**
     * Which rung, which wave, and how long until the next drop, drawn over the fight.
     *
     * Gated on the unlock rather than only hidden with it: a build that shipped with the
     * switch left on would otherwise draw the overlay over a screen that no longer has a way
     * to turn it off. One condition here is cheaper than clearing the stored value.
     */
    fun debug(p: SharedPreferences): Boolean =
        debugUnlocked(p) && p.getBoolean(KEY_DEBUG, false)


    /**
     * How many times the table has been finished at the hardest skill.
     *
     * Kept here rather than in the scene because the scene is rebuilt on every surface change
     * and would forget: this is the one thing in the wallpaper worth remembering between
     * runs, and it is the only number the settings screen reports back rather than sets.
     */
    fun completions(p: SharedPreferences): Int = p.getInt(KEY_COMPLETIONS, 0)

    /** When the table was first finished at the hardest skill, or 0 if it never has been. */
    fun firstCompletion(p: SharedPreferences): Long = p.getLong(KEY_FIRST_COMPLETION, 0L)

    fun addCompletion(p: SharedPreferences) = p.edit {
        putInt(KEY_COMPLETIONS, completions(p) + 1)
        // Written once and never again: it is the date of the first, not of the last.
        if (firstCompletion(p) == 0L) putLong(KEY_FIRST_COMPLETION, System.currentTimeMillis())
    }

    /**
     * The sequence that puts the debug row on the settings screen: the readout row four
     * times, god mode twice, then 10 fps and 20 fps.
     *
     * Here rather than in the settings screen because it is the only part of that screen with
     * a state machine in it, and here it can be tested without an Activity.
     *
     * The two switches are tapped an even number of times each, so the sequence gives back
     * every setting it borrows. The frame rate genuinely ends on 20 - it is the one control
     * in the sequence that is not a toggle, and 20 is the default.
     */
    object Knock {
        const val READOUT = 0
        const val GOD = 1
        const val TEN = 2
        const val TWENTY = 3

        /** A control that is in the sequence nowhere, such as 15 fps: it ends the attempt. */
        const val NONE = -1

        val SEQUENCE = intArrayOf(READOUT, READOUT, READOUT, READOUT, GOD, GOD, TEN, TWENTY)

        /**
         * How far along the sequence [step] leaves us, having been [at]. A wrong step drops
         * back to nothing, except when it is itself the opening step: otherwise a fifth tap on
         * the readout row would have to be followed by four more rather than three, which is
         * not how anybody counts.
         *
         * Zero means not started, and the readout row is the only step that leaves zero. The
         * caller is expected to not even ask about the other controls until it has: nothing
         * here counts taps that are not part of an attempt.
         */
        fun advance(at: Int, step: Int): Int = when {
            at >= SEQUENCE.size -> 0
            step == SEQUENCE[at] -> at + 1
            step == SEQUENCE[0] -> 1
            else -> 0
        }

        fun complete(at: Int): Boolean = at >= SEQUENCE.size
    }

    /** What is drawn behind the fight. */
    enum class Background { DYNAMIC, PHOTO, COLOUR }

    /**
     * The dynamic ground is the default and stays so: it is the only one that reports the
     * difficulty, and it is the thing the wallpaper was built around.
     */
    fun background(p: SharedPreferences): Background = when (p.getString(KEY_BACKGROUND, null)) {
        "photo" -> Background.PHOTO
        "colour" -> Background.COLOUR
        else -> Background.DYNAMIC
    }

    /**
     * Palette index for the flat colour, from the active WAD's own PLAYPAL rather than an
     * ARGB value, so the choice follows whichever WAD is loaded.
     */
    fun backgroundColour(p: SharedPreferences): Int =
        p.getString(KEY_BACKGROUND_COLOUR, null)?.toIntOrNull()?.coerceIn(0, 255) ?: 0
}
