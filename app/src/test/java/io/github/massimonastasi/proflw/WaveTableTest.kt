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

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shape of the wave table, asserted rather than described.
 *
 * The table is written by hand and read by eye, which is how it came to have two properties
 * nobody wanted: the same creature arriving twice in a row, and no two enemies ever arriving
 * together in twenty-six waves. Both were visible on screen long before anyone found them in
 * the source, because a comment saying "paired arrivals near the end" is not a check.
 *
 * These four assertions are that check. They are constraints on the table, in the spirit of
 * describing content by its rules rather than by its contents - the useful half of what
 * constraint-based generation offers. The generating itself is not worth having here: the
 * sequence is one-dimensional and its rules are local, so there is nothing to propagate and
 * no dead end to back out of, and a table generated fresh each run would turn the measured
 * survival curve into a distribution over tables.
 */
class WaveTableTest {

    /** Every arrival in the table, in the order the scene delivers them, one per second. */
    private fun allArrivals(): List<Int> = GameData.waves.flatMap { it.order.toList() }

    @Test
    fun `no creature arrives twice in a row`() {
        val arrivals = allArrivals()
        for (i in 1 until arrivals.size) {
            assertTrue(
                arrivals[i] != arrivals[i - 1],
                "arrival $i repeats ${GameData.creatures[arrivals[i]].name} " +
                    "immediately after itself: $arrivals",
            )
        }
    }

    @Test
    fun `enemies do share the field`() {
        // Arrivals are a second apart now rather than landing together, so a wave of two is
        // two on screen at once for as long as the first one lives - which is what the old
        // burst was reaching for. Twelve of the twenty-six, which is every escort wave.
        val doubles = GameData.waves.count { it.order.size >= 2 }
        assertTrue(doubles > 0, "no wave ever puts two enemies on the field")
        assertTrue(
            doubles >= GameData.waves.size / 3,
            "only $doubles of ${GameData.waves.size} waves deliver a pair",
        )
    }

    @Test
    fun `every creature enters alone before it is escorted`() {
        for (c in GameData.creatures.indices) {
            if (GameData.creatures[c] === GameData.player) continue
            val alone = GameData.waves.indexOfFirst { it.order.size == 1 && it.order[0] == c }
            if (alone < 0) continue                 // the last two close the table by themselves
            val escorted = GameData.waves.indexOfFirst { it.order.size > 1 && c in it.order }
            if (escorted >= 0) {
                assertTrue(
                    alone < escorted,
                    "${GameData.creatures[c].name} is escorted in wave ${escorted + 1} " +
                        "before it is ever seen alone in wave ${alone + 1}",
                )
            }
        }
    }

    @Test
    fun `the roster never walks back down the bestiary`() {
        // The bestiary is in health order, so the hardest creature in a wave is the highest
        // index in it. That figure may stand still - a creature is escorted by the one below
        // it - but it may never fall, or the table would be getting easier as it goes.
        var highest = -1
        GameData.waves.forEachIndexed { i, w ->
            val top = w.order.max()
            assertTrue(top >= highest, "wave ${i + 1} steps back down to $top from $highest")
            highest = top
        }
        assertEquals(
            GameData.creatures.indexOfFirst { it === GameData.creatures.last() },
            GameData.waves.last().order.max(),
            "the table does not end on the last creature in the bestiary",
        )
    }
}
