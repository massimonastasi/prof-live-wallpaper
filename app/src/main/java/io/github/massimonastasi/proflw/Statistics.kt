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
     * Pairs each thing with its tally, keeping the order of the table it came from.
     *
     * That order is the answer to "in what order", and it is already written down elsewhere:
     * [GameData.creatures] climbs from the Zombie to the Overlord, so the rows run from the
     * weakest thing on the field to the strongest and the two bosses fall to the bottom on
     * their own; [GameData.items] runs healing, then armour, then the guns, each group from
     * the plain one to the powerful one. Sorting by tally instead put a boss between two
     * zombies and a rocket launcher between two stimpacks, which is a ranking of luck.
     *
     * Zeros are kept. The list used to grow as the marine earned it, which read well on a
     * fresh install and badly afterwards: with no names on the page there was no way to tell
     * a creature that has never appeared from one this WAD cannot draw, and no sense of how
     * much of the bestiary was still out there. The whole table shows, and a nought is an
     * answer.
     */
    internal fun <T> rank(things: List<T>, counts: IntArray): List<Pair<T, Int>> =
        things.mapIndexed { i, t -> t to counts[i] }

    private fun read(size: Int, at: (Int) -> Int) = IntArray(size) { at(it) }

    /** One creature's two tallies: how many the marine killed, how often it killed him. */
    class Encounter(val creature: GameData.Creature, val killed: Int, val killedBy: Int)

    /**
     * The creatures met so far, each with both of its counts.
     *
     * One row per creature rather than two lists, because the page shows no names any more:
     * the names are ours and an imported WAD draws somebody else's bestiary, so the sprite
     * is the only honest label - and the same sprite appearing in two separate lists could
     * not be recognised as the same creature.
     *
     * The order is the bestiary's, as in [rank], and a creature neither killed nor guilty of
     * a death has no row.
     */
    fun encounters(p: SharedPreferences): List<Encounter> =
        GameData.creatures.map { Encounter(it, Settings.kills(p, it), Settings.deaths(p, it)) }

    fun pickups(p: SharedPreferences): List<Pair<GameData.Item, Int>> =
        rank(GameData.items, read(GameData.items.size) { Settings.pickups(p, GameData.items[it]) })

}
