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
 * Checks on the constants and on the movement maths.
 *
 * If anyone touches the fixed-point arithmetic, the direction tables or P_Random, these
 * fail: they are the safety net for fidelity to the original game.
 */
class GameDataTest {

    @Test
    fun `a cardinal step covers exactly speed units`() {
        // MT_TROOP has speed 8: in one tic heading east it must move exactly 8 units.
        val step = 8 * GameData.xspeed[0]
        assertEquals(8 * GameData.FRACUNIT, step, "an eastward step is not worth 8 units")
        assertEquals(0, 8 * GameData.yspeed[0], "an eastward step must not move y")
    }

    // The diagonal entries of the tables used to have a test of their own, asserting id's
    // 47000 approximation. Nothing moves diagonally any more, so it guarded numbers no code
    // reads. The transcription is still covered: the check below sums every direction with
    // its opposite, diagonals included.

    @Test
    fun `opposite directions are consistent`() {
        for (d in 0..7) {
            assertEquals(d, GameData.opposite[GameData.opposite[d]], "opposite is not involutive for $d")
            // The opposite direction cancels the movement out.
            assertEquals(0, GameData.xspeed[d] + GameData.xspeed[GameData.opposite[d]])
            assertEquals(0, GameData.yspeed[d] + GameData.yspeed[GameData.opposite[d]])
        }
        assertEquals(GameData.DI_NODIR, GameData.opposite[GameData.DI_NODIR])
    }

    @Test
    fun `P_Random reproduces the sequence from the id source`() {
        GameData.clearRandom()
        // rndtable[1..5] from the original m_random.c.
        val expected = intArrayOf(8, 109, 220, 222, 241)
        for (e in expected) assertEquals(e, GameData.pRandom())
        assertEquals(256, GameData.rndtable.size)
        // Deterministic: resetting the index replays it identically.
        GameData.clearRandom()
        assertEquals(8, GameData.pRandom())
    }

    @Test
    fun `creature speeds match info_c`() {
        fun speedOf(name: String) = GameData.creatures.first { it.name == name }.speed
        assertEquals(8, speedOf("Zombie"))
        assertEquals(8, speedOf("Serpentipede"))
        // The Flesh Worm (SARG) is the only faster one: speed 10.
        assertEquals(10, speedOf("Flesh Worm"))
        assertEquals(8, speedOf("Pain Lord"))
    }

    @Test
    fun `every sprite has a valid index`() {
        // The indices are assigned in an init block: if anyone moves it before
        // spritePrefixes is built they all fall back to -1 and the app crashes on start.
        val n = GameData.spritePrefixes.size
        assertTrue(GameData.bloodSpriteIndex in 0 until n, "blood: ${GameData.bloodSpriteIndex}")
        assertTrue(GameData.fogSpriteIndex in 0 until n, "fog: ${GameData.fogSpriteIndex}")
        assertTrue(GameData.player.spriteIndex in 0 until n, "player")
        for (c in GameData.creatures) {
            assertTrue(c.spriteIndex in 0 until n, "${c.name}: ${c.spriteIndex}")
            assertEquals(c.lumpPrefix, GameData.spritePrefixes[c.spriteIndex])
        }
        for (p in GameData.projectiles) {
            assertTrue(p.spriteIndex in 0 until n, "projectile ${p.lumpPrefix}")
            assertEquals(p.lumpPrefix, GameData.spritePrefixes[p.spriteIndex])
        }
        for (i in GameData.items) {
            assertTrue(i.spriteIndex in 0 until n, "item ${i.lumpPrefix}")
            assertEquals(i.lumpPrefix, GameData.spritePrefixes[i.spriteIndex])
        }
    }

    /**
     * A death animation ends in a body that stays, except the one that ends in nothing.
     *
     * The last tic of -1 is this codebase's marker for the resting frame, and it is right for
     * every creature the original leaves lying on the floor. The Charger is not one: info.c
     * sends S_SKULL_DIE6 to S_NULL, so the skull bursts into a ball of fire, fades to a ring
     * and is removed. Marked -1 it stayed instead - in Phase 2 that ring is 103 by 90 pixels,
     * wider than the marine is tall - and sat on the ground for thirty seconds under whatever
     * walked over it, which is what read as a blood splat that outlived its corpse.
     *
     * Pinned in both directions, because the interesting failure is either way round: a
     * creature that stops leaving a corpse is as wrong as an explosion that leaves one.
     */
    @Test
    fun `only the Charger leaves nothing behind`() {
        for (c in GameData.creatures) {
            val last = c.death.tics.last()
            if (c.lumpPrefix == "SKUL") {
                assertTrue(
                    last > 0,
                    "the Charger detonates and is removed: its death must not end on a " +
                        "resting frame (info.c S_SKULL_DIE6 -> S_NULL)",
                )
            } else {
                assertEquals(
                    -1, last,
                    "${c.name} must end its death on a resting frame that stays",
                )
            }
        }
    }

    @Test
    fun `every creature has consistent animations`() {
        for (c in GameData.creatures + GameData.player) {
            for (a in listOf(c.attack, c.pain, c.death)) {
                assertEquals(a.frames.size, a.tics.size, "${c.name}: frames and tics do not match")
                assertTrue(a.frames.isNotEmpty(), "${c.name}: empty animation")
            }
            // Whether the last death frame stays is asserted per creature above, in
            // `only the Charger leaves nothing behind`. It used to be asserted here as -1 for
            // everyone, which is where the Charger's explosion got its resting frame from.
        }
    }

    @Test
    fun `a Serpentipede covers 280 units per second`() {
        // speed 8 per tic at 35 tics/s = 280 units per second. If the fixed-point maths
        // breaks, this number changes.
        val perTic = 8 * GameData.xspeed[0] / GameData.FRACUNIT
        assertEquals(280, perTic * TICRATE)
    }
}
