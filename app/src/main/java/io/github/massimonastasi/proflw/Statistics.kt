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

import android.content.SharedPreferences

/**
 * What the statistics tab shows, worked out away from the views that show it.
 *
 * The activity is already six hundred lines and this is the only part of the tab with any
 * decisions in it: which entries are worth a row, and in what order. Keeping it here means
 * [rank] can be tested on the JVM like the rest of the simulation, rather than needing a
 * device to prove that a zero does not get a row.
 */
object Statistics {

    /**
     * Pairs each thing with its tally, drops the ones that never happened, and puts the
     * commonest first.
     *
     * Dropping zeros is what keeps the page honest early on: fourteen creatures and nine
     * pickups would otherwise be a wall of noughts on a phone that has had the wallpaper for
     * an hour. The list grows as the marine earns it.
     */
    internal fun <T> rank(things: List<T>, counts: IntArray): List<Pair<T, Int>> =
        things.mapIndexed { i, t -> t to counts[i] }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }

    private fun read(size: Int, at: (Int) -> Int) = IntArray(size) { at(it) }

    fun kills(p: SharedPreferences): List<Pair<GameData.Creature, Int>> =
        rank(GameData.creatures, read(GameData.creatures.size) { Settings.kills(p, GameData.creatures[it]) })

    fun deaths(p: SharedPreferences): List<Pair<GameData.Creature, Int>> =
        rank(GameData.creatures, read(GameData.creatures.size) { Settings.deaths(p, GameData.creatures[it]) })

    fun pickups(p: SharedPreferences): List<Pair<GameData.Item, Int>> =
        rank(GameData.items, read(GameData.items.size) { Settings.pickups(p, GameData.items[it]) })

    /**
     * A readable name for a pickup.
     *
     * Weapons already have one on [GameData.Weapon]; the health and armour items have only a
     * WAD lump prefix, so the four names come from resources. Anything unexpected falls back
     * to the prefix rather than crashing or showing an empty row - a WAD is user-supplied
     * and this list follows what is in it.
     */
    fun itemName(item: GameData.Item): Int? = when (item.lumpPrefix) {
        "STIM" -> R.string.item_stim
        "MEDI" -> R.string.item_medi
        "ARM1" -> R.string.item_arm1
        "ARM2" -> R.string.item_arm2
        else -> null
    }
}
