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
 * What the statistics count, and — the part that was actually missing — who gets the blame.
 *
 * A kill or a death is recorded in one place, [Scene.damageActor], and the only thing that
 * makes the record meaningful is the creature passed in with it. Three of the four callers
 * always had the attacker in hand; the projectile path threw it away at spawn time and had
 * to be taught to carry it, which is what most of this file is guarding.
 */
class StatisticsTest {

    private fun creatureActor(c: GameData.Creature) = Actor(c.spriteIndex).apply {
        creature = c
        health = c.health
    }

    private fun marine() = Actor(GameData.player.spriteIndex).apply {
        creature = GameData.player
        isPlayer = true
        health = GameData.player.health
        loadout = Loadout()
    }

    @Test
    fun `a kill is credited to the creature that died`() {
        GameData.clearRandom()
        val scene = Scene(720, 1600)
        val zombie = GameData.creatures[0]

        scene.damageForTest(creatureActor(zombie), 9999, GameData.player)

        assertEquals(1, scene.kills[zombie.index], "the dead creature's own tally must move")
        assertEquals(1, scene.kills.sum(), "and nothing else must move with it")
        assertEquals(0, scene.deaths.sum(), "a creature dying is not the marine dying")
    }

    @Test
    fun `a death names the creature that caused it`() {
        GameData.clearRandom()
        val scene = Scene(720, 1600)
        val killer = GameData.creatures[6]          // BoneStalker, a projectile user

        scene.damageForTest(marine(), 9999, killer)

        assertEquals(1, scene.deaths[killer.index], "the killer must be named")
        assertEquals(1, scene.deaths.sum(), "exactly one, and only the one")
        assertEquals(0, scene.kills.sum(), "the marine dying is not a kill")
    }

    /**
     * The regression this whole change exists for. A missile carries its shooter's creature
     * because the shooter may be dead by the time it lands; before that, the impact path
     * had only the missile Actor, whose own creature is null. Attributing *that* would
     * silently record nothing at all.
     */
    @Test
    fun `a death with nobody to blame is not recorded rather than misrecorded`() {
        GameData.clearRandom()
        val scene = Scene(720, 1600)

        scene.damageForTest(marine(), 9999, null)

        assertEquals(0, scene.deaths.sum(), "an unattributable death must not invent a killer")
    }

    @Test
    fun `god mode records nothing, as it already does for completions`() {
        GameData.clearRandom()
        val scene = Scene(720, 1600)
        scene.invulnerable = true                   // this is what sets `cheated`
        scene.invulnerable = false                  // and it stays set for the run

        scene.damageForTest(creatureActor(GameData.creatures[0]), 9999, GameData.player)

        assertEquals(0, scene.kills.sum(), "a run that cannot be lost is not a record")
        assertEquals(0, scene.statsVersion, "and nothing must look changed to the engine")
    }

    @Test
    fun `statsVersion moves only when a tally moves`() {
        GameData.clearRandom()
        val scene = Scene(720, 1600)

        val before = scene.statsVersion
        repeat(200) { scene.tick(it) }              // ordinary play, nothing dying yet
        val idle = scene.statsVersion

        scene.damageForTest(creatureActor(GameData.creatures[0]), 9999, GameData.player)

        assertEquals(idle + 1, scene.statsVersion, "a kill must move it by exactly one")
        assertTrue(idle >= before, "and it must never go backwards")
    }

    /**
     * The integration check: left alone with a mortal marine, all three tallies fill up.
     * This is what would catch a projectile death going uncounted in practice, since the
     * creatures that fire them arrive early and kill often.
     */
    @Test
    fun `a long run fills kills, deaths and pickups`() {
        GameData.clearRandom()
        val scene = Scene(720, 1600)

        // Twenty minutes of play at the original tic rate.
        repeat(35 * 60 * 20) { scene.tick(it) }

        assertTrue(scene.kills.sum() > 0, "the marine must have killed something")
        assertTrue(scene.deaths.sum() > 0, "and must have died to something named")
        assertTrue(scene.pickups.sum() > 0, "and must have picked something up")

        // Every recorded death belongs to a real creature, which is the invariant that
        // breaks if an index is ever derived from the wrong list.
        scene.deaths.forEachIndexed { i, n ->
            if (n > 0) assertTrue(i in GameData.creatures.indices, "death index $i is not a creature")
        }
    }

    /**
     * The order of the rows, which is the table's and not the tally's: the bestiary climbs
     * from the weakest creature to the strongest, and a page sorted by count would put a boss
     * killed twice above a zombie killed two hundred times.
     */
    @Test
    fun `rank keeps the table order and drops the zeros`() {
        val things = listOf("weakest", "middling", "never met", "strongest")
        val ranked = Statistics.rank(things, intArrayOf(200, 5, 0, 2))

        assertEquals(listOf("weakest", "middling", "strongest"), ranked.map { it.first },
            "the table's own order decides, not the counts")
        assertEquals(listOf(200, 5, 2), ranked.map { it.second }, "each keeps its own tally")
    }
}
