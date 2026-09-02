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

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * When the rung moves, and so when the background does.
 *
 * Two rules, and they pull in opposite directions on purpose. Up: only the kill that empties
 * the last wave of the table climbs a rung, never a wave and never a creature - the floor
 * kept turning over mid-table and it was reported as a fault. Down: a death gives back the
 * whole ladder at once. That second one was tried both ways. Keeping the rung across a death
 * reads well written down and plays badly, because a marine who dies on the hardest rung is
 * handed it again on the next tic and dies there for as long as the wallpaper is on.
 */
class SkillLadderTest {

    @Test
    fun `the rung only moves on the last wave of the table`() {
        GameData.clearRandom()
        val scene = Scene(720, 1600)

        var previousWave = scene.wave
        var previousSkill = scene.skill
        for (t in 1..TICRATE * 900) {
            scene.tick(t)
            // Climbs only. A fall is the other rule and has its own test: it happens on the
            // restart after a death, which is any wave at all.
            if (scene.skill > previousSkill) {
                // The counter has not moved yet: the wave just cleared is still the current one.
                assertEquals(
                    GameData.waves.lastIndex,
                    previousWave,
                    "tic $t: the rung moved on wave $previousWave, mid-table",
                )
            }
            previousWave = scene.wave
            previousSkill = scene.skill
        }
        assertTrue(scene.skill >= 0, "the rung went below the bottom")
    }

    @Test
    fun `a death gives the whole ladder back`() {
        GameData.clearRandom()
        val scene = Scene(720, 1600)
        // The only way to see a marine on a rung above the first: an honest one dies long
        // before he finishes a table. Switched off again below, which is what makes the
        // death this test is about possible at all.
        scene.invulnerable = true

        var t = 0
        while (scene.skill == 0 && t < TICRATE * 3600) scene.tick(++t)
        assertTrue(scene.skill > 0, "the ladder never left the first rung in an hour")

        scene.invulnerable = false
        var skillAtDeath = -1
        while (!scene.dying && t < TICRATE * 7200) {
            skillAtDeath = scene.skill
            scene.tick(++t)
        }
        assertTrue(scene.dying, "the marine never died with the invulnerability off")
        assertTrue(skillAtDeath > 0, "he died on the first rung, which proves nothing")

        // Through the black curtain to the restart on the other side of it.
        while (scene.dying && t < TICRATE * 7200) scene.tick(++t)
        assertEquals(0, scene.skill, "the death left the ladder on rung ${scene.skill + 1}")
    }
}
