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
 * Scene has no Android dependency, so the whole simulation runs on the JVM: ten minutes of
 * play in a few milliseconds. This is the net that catches inconsistent-state crashes
 * (animation indices out of sequence, actors outside the world) before the phone does.
 */
class SceneTest {

    private val worldWidth = 720
    private val worldHeight = 1600

    @Test
    fun `ten minutes of simulation with no inconsistent state`() {
        GameData.clearRandom()
        val scene = Scene(worldWidth, worldHeight)

        for (t in 1..TICRATE * 600) {
            scene.tick(t)

            for (a in scene.actors) {
                val anim = a.anim
                if (anim != null) {
                    assertTrue(
                        a.animStep in anim.frames.indices,
                        "tic $t: animStep ${a.animStep} outside 0..${anim.length - 1}",
                    )
                }
                // frame() is called on every draw: it must never be able to throw.
                a.frame(t)

                if (a.creature != null) {
                    // Bounded by the drawing margins, not by the collision radius: the
                    // radius says how wide a thing is for hit tests, and says nothing about
                    // how far its sprite reaches, which is what has to stay on screen.
                    val x = a.x / GameData.FRACUNIT
                    val y = a.y / GameData.FRACUNIT
                    assertTrue(
                        x >= Scene.SPAWN_MARGIN && x <= worldWidth - Scene.SPAWN_MARGIN,
                        "tic $t: x=$x outside the drawable band",
                    )
                    assertTrue(
                        y >= Scene.TOP_MARGIN && y <= worldHeight - Scene.BOTTOM_MARGIN,
                        "tic $t: y=$y outside the drawable band",
                    )
                }
            }
        }
    }

    @Test
    fun `the scene stays populated and does not grow without bound`() {
        GameData.clearRandom()
        val scene = Scene(worldWidth, worldHeight)

        // A single instant is not enough: between waves there is a pause where zero demons
        // is correct. Look at the last half minute instead.
        var maxRecent = 0
        // Doubling the largest wave still catches a runaway spawn, which is what this bound
        // is for.
        val biggestWave = GameData.waves.maxOf { it.order.size } * 2
        for (t in 1..TICRATE * 600) {
            scene.tick(t)
            val n = scene.actors.count { it.creature != null && !it.isPlayer && !it.dead }
            if (t > TICRATE * 570) maxRecent = maxOf(maxRecent, n)
            assertTrue(n <= biggestWave, "tic $t: $n demons, the largest wave holds $biggestWave")
        }

        assertTrue(maxRecent > 0, "no demon appeared during the last half minute")
        // Corpses, projectiles and effects must not accumulate indefinitely.
        assertTrue(scene.actors.size < 60, "too many actors on stage: ${scene.actors.size}")
    }

    /**
     * The difficulty ladder: one rung per finished table, never off the table, and a death
     * does not take it back - the background it drives stays where the play put it.
     */
    @Test
    fun `the skill climbs one rung per finished table and a death keeps it`() {
        GameData.clearRandom()
        val scene = Scene(worldWidth, worldHeight)
        scene.invulnerable = true

        assertEquals(0, scene.skill, "the first run must start on the lowest skill")

        var t = 0
        var reached = 0
        // Long enough to climb several levels while nothing can kill him.
        while (t < TICRATE * 3600) {
            scene.tick(++t)
            assertTrue(scene.skill in GameData.skills.indices, "skill ${scene.skill} off the table")
            if (scene.skill > reached) reached = scene.skill
        }
        assertTrue(reached > 0, "the skill never rose in an hour of invulnerable play")

        // Now let him be killed. The run ends, and a run is what the ladder measures: the
        // waves go back to the first and the rung goes with them - see the comment on the
        // reset in Scene, which says why it is not kept. This used to assert the opposite and
        // passed only on timing: it read the skill in the window between the last wave ending
        // and the reset, and any change to the tic budget moved that window.
        scene.invulnerable = false
        while (t < TICRATE * 5400 && scene.wave > 0) scene.tick(++t)
        assertEquals(0, scene.wave, "he was never killed: this half of the test checked nothing")
        assertTrue(scene.skill <= reached, "a death cannot hand out a rung")
    }

    /**
     * Nobody may stand close enough to the top edge for their sprite to leave the screen.
     *
     * A sprite is anchored at the feet and drawn upwards, so an actor bounded only by its
     * collision radius can be positioned perfectly legally and still be painted entirely
     * above the visible area. That is what happened: the marine disappeared off the top.
     * The radius is 16 to 31 units, the tallest sprite reaches 148.
     */
    @Test
    fun `no sprite is ever drawn off the top of the screen`() {
        GameData.clearRandom()
        val scene = Scene(worldWidth, worldHeight)

        var closest = Int.MAX_VALUE
        for (t in 1..TICRATE * 600) {
            scene.tick(t)
            for (a in scene.actors) {
                if (a.creature == null) continue
                closest = minOf(closest, a.y)
                assertTrue(
                    a.y >= Scene.TOP_MARGIN * GameData.FRACUNIT,
                    "tic $t: ${a.creature?.name} at y=${a.y / GameData.FRACUNIT}, " +
                        "above the ${Scene.TOP_MARGIN} unit top margin, so its sprite is off screen",
                )
            }
        }
        // The bound must actually be exercised, or the test proves nothing.
        assertTrue(
            closest < (Scene.TOP_MARGIN + 80) * GameData.FRACUNIT,
            "nobody ever went near the top edge: closest was ${closest / GameData.FRACUNIT}",
        )
    }

    /**
     * The preview opens on a fight: it skips the empty ground the marine walks onto, and
     * nothing else. A preview that ran at a different pace would advertise a different
     * wallpaper, so the only thing [Scene.instantStart] touches is when he arrives.
     */
    @Test
    fun `an instant start fills the scene at once`() {
        fun demons(s: Scene) = s.actors.count { it.creature != null && !it.isPlayer && !it.dead }

        // The first tick brings the marine in and arms the wave, returning before arrivals
        // are considered, so the earliest anyone can enter is the tick after that.
        GameData.clearRandom()
        val normal = Scene(worldWidth, worldHeight)
        normal.tick(1); normal.tick(2)
        assertEquals(0, demons(normal), "the normal opening must leave the marine alone at first")

        GameData.clearRandom()
        val preview = Scene(worldWidth, worldHeight, instantStart = true)
        preview.tick(1); preview.tick(2)
        assertTrue(demons(preview) > 0, "the preview must open with an enemy already present")
    }

    /**
     * The scene opens on empty ground, and the marine still gets the tic he arrives on to
     * himself: the wave is armed by his arrival, so the first enemy comes the tic after.
     */
    @Test
    fun `the marine arrives after a pause and the wave shifts with him`() {
        GameData.clearRandom()
        val scene = Scene(worldWidth, worldHeight)

        for (t in 1 until Scene.ARRIVAL_DELAY) {
            scene.tick(t)
            assertTrue(scene.actors.isEmpty(), "tic $t: something was on stage before the marine")
        }

        scene.tick(Scene.ARRIVAL_DELAY)
        assertTrue(scene.actors.any { it.isPlayer }, "the marine must arrive on the delay")

        assertEquals(
            0, scene.actors.count { it.creature != null && !it.isPlayer },
            "an enemy arrived on the marine's own arrival tic",
        )

        scene.tick(Scene.ARRIVAL_DELAY + 1)
        assertEquals(
            1, scene.actors.count { it.creature != null && !it.isPlayer },
            "exactly one must arrive once the wave is armed",
        )
    }

    /**
     * Death costs everything: armour, weapons and ammunition alike.
     *
     * g_game.c G_PlayerReborn memsets the whole player struct and hands back the pistol, so
     * a reborn marine carries nothing at all. There is no exception now — the arsenal used
     * to be one, and is not any more.
     */
    @Test
    fun `nothing survives death`() {
        GameData.clearRandom()
        val scene = Scene(worldWidth, worldHeight)

        // Sampled every tic, so the comparison is against the state he was actually in when
        // he died rather than some earlier reading.
        var current: Actor? = null
        var hadSomething = false
        var checked = false

        var t = 0
        while (t < TICRATE * 2400 && !checked) {
            scene.tick(++t)
            val p = scene.actors.firstOrNull { it.isPlayer && !it.dead } ?: continue
            val kit = p.loadout ?: continue

            if (current != null && p !== current && hadSomething) {
                // A new marine, and the old one had something to lose.
                assertEquals(0, kit.armorPoints, "armour must not survive death")
                assertEquals(0, kit.armorType, "the armour type must go with the points")
                assertEquals(0, kit.owned, "the arsenal must not survive death")
                assertTrue(kit.ammo.all { it == 0 }, "ammunition must not survive death")
                checked = true
            }
            current = p
            hadSomething = kit.armorPoints > 0 || kit.owned != 0
        }
        assertTrue(checked, "no marine ever died carrying anything, so nothing was proven")
    }

    @Test
    fun `the arsenal keeps the best loaded weapon and falls back to the pistol`() {
        val kit = Loadout()
        assertEquals(0, kit.owned, "the marine starts with the pistol alone, which is not owned")

        kit.take(GameData.WEAPON_SHOTGUN)
        kit.take(GameData.WEAPON_CHAINGUN)
        assertTrue(kit.has(GameData.WEAPON_SHOTGUN) && kit.has(GameData.WEAPON_CHAINGUN), "both must be carried")

        // Emptying the chaingun takes it away, and the shotgun is what is left loaded.
        kit.drop(GameData.WEAPON_CHAINGUN)
        assertTrue(kit.has(GameData.WEAPON_SHOTGUN), "the shotgun must survive losing the chaingun")
        assertTrue(!kit.has(GameData.WEAPON_CHAINGUN), "an empty weapon must be gone, not merely unused")

        kit.drop(GameData.WEAPON_SHOTGUN)
        assertEquals(0, kit.owned, "with nothing loaded he is back to the pistol")
    }

    /**
     * A weapon already carried is replaced, not duplicated, and reloads what is there.
     *
     * The arsenal is a bitmask with one bit per weapon, so a second shotgun can never become
     * a second shotgun: it takes the place of the one held. Since no ammunition is dropped
     * on its own, that is also the only way to reload — and a pickup that would give nothing
     * at all must be left on the ground rather than swallowed.
     */
    @Test
    fun `a weapon already carried is replaced and reloaded, not stacked`() {
        GameData.clearRandom()
        val scene = Scene(worldWidth, worldHeight)
        val shotgun = GameData.items.first { it.kind == GameData.ITEM_WEAPON && it.extra == GameData.WEAPON_SHOTGUN }
        val shells = GameData.weapons[GameData.WEAPON_SHOTGUN].ammo

        val marine = Actor(0).apply {
            creature = GameData.player
            isPlayer = true
            loadout = Loadout()
        }
        val kit = marine.loadout!!

        assertTrue(scene.pickUpForTest(marine, shotgun), "the first shotgun must be taken")
        val afterFirst = kit.ammo[shells]
        assertTrue(afterFirst > 0, "a weapon must arrive loaded")
        val ownedAfterFirst = kit.owned

        // A second one: same single bit, more shells.
        assertTrue(scene.pickUpForTest(marine, shotgun), "a second shotgun must reload him")
        assertEquals(ownedAfterFirst, kit.owned, "the arsenal must not grow from a duplicate")
        assertTrue(kit.ammo[shells] > afterFirst, "the duplicate must add shells")

        // Full up: it now gives nothing, so it must stay where it lies.
        kit.ammo[shells] = GameData.maxAmmo[shells]
        assertTrue(!scene.pickUpForTest(marine, shotgun), "a pickup that gives nothing must be left alone")
        assertEquals(GameData.maxAmmo[shells], kit.ammo[shells], "and must not change anything")
    }

    /**
     * A WAD that cannot draw a creature never spawns it.
     *
     * The draw loop skips a sprite it cannot resolve, so an unavailable creature used to be
     * invisible rather than absent — still walking, still shooting, still having to be
     * killed before the wave would clear. Nothing about that reads as a missing sprite; it
     * reads as the wallpaper being broken.
     */
    @Test
    fun `creatures the WAD cannot draw are never spawned`() {
        // Only the two weakest exist, which is roughly what a partial IWAD leaves.
        val available = BooleanArray(GameData.creatures.size) { it < 2 }

        GameData.clearRandom()
        val scene = Scene(worldWidth, worldHeight, drawable = available)

        var seen = 0
        for (t in 1..TICRATE * 900) {
            scene.tick(t)
            for (a in scene.actors) {
                val c = a.creature ?: continue
                if (a.isPlayer) continue
                val i = GameData.creatures.indexOf(c)
                assertTrue(available[i], "tic $t: spawned ${c.name}, which this WAD cannot draw")
                seen++
            }
        }
        assertTrue(seen > 0, "no creature was spawned at all, so nothing was proven")
    }

    @Test
    fun `ammunition is never dropped on its own`() {
        assertTrue(
            GameData.items.none { it.kind !in intArrayOf(GameData.ITEM_HEALTH, GameData.ITEM_ARMOR, GameData.ITEM_WEAPON) },
            "the drop table must hold only health, armour and weapons",
        )
        // Healing outweighs armour, which outweighs the guns.
        fun share(kind: Int) = GameData.items.filter { it.kind == kind }.sumOf { it.weight }
        assertTrue(share(GameData.ITEM_HEALTH) > share(GameData.ITEM_ARMOR), "health must lead")
        assertTrue(share(GameData.ITEM_ARMOR) > share(GameData.ITEM_WEAPON), "armour must come before the guns")
    }

    @Test
    fun `the marine arrives first and enemies one at a time`() {
        GameData.clearRandom()
        val scene = Scene(worldWidth, worldHeight)

        fun demons() = scene.actors.count { it.creature != null && !it.isPlayer }

        // He is not there from the first frame: the ground is empty for ARRIVAL_DELAY.
        val arrives = Scene.ARRIVAL_DELAY
        for (t in 1..arrives) scene.tick(t)
        assertTrue(scene.actors.any { it.isPlayer }, "the marine must appear first")
        assertEquals(0, demons(), "no enemy alongside the marine")

        // The wave is armed by his arrival, so the first enemy comes the tic after it.
        scene.tick(arrives + 1)
        assertEquals(1, demons(), "exactly one must arrive once the wave is armed")

        // And they come one at a time: nothing else lands inside the next second.
        for (t in arrives + 2..arrives + GameData.SPAWN_DELAY) scene.tick(t)
        assertEquals(1, demons(), "a second enemy arrived inside the spawn interval")
    }

    @Test
    fun `waves get heavier as they progress`() {
        // The tension curve is the health a wave puts on the field, and it may stand still
        // but never fall. It used to be the spawn delay walking down from two seconds, which
        // was a curve nobody could see: what a phone screen shows is bodies, not tics.
        val weight = GameData.waves.map { w -> w.order.sumOf { GameData.creatures[it].health } }
        for (i in 1 until weight.size) {
            assertTrue(
                weight[i] >= weight[i - 1],
                "wave ${i + 1} drops to ${weight[i]} from ${weight[i - 1]}: $weight",
            )
        }
        assertTrue(weight.first() < weight.last(), "the table does not get heavier at all")
        // The crowd cap, which is what a phone screen actually shows: at most two arrivals
        // queued, so nothing can pile six bodies into a single wave the way an earlier table
        // did. Spacing them a second apart is what keeps them from landing as a crowd.
        for ((i, w) in GameData.waves.withIndex()) {
            assertTrue(w.order.size <= 2, "wave ${i + 1} queues ${w.order.size} arrivals")
        }
    }

    @Test
    fun `nobody arrives while the marine is dead`() {
        GameData.clearRandom()
        val scene = Scene(worldWidth, worldHeight)

        fun demons() = scene.actors.count { it.creature != null && !it.isPlayer && !it.dead }

        var previous = 0
        var deathTics = 0
        for (t in 1..TICRATE * 600) {
            scene.tick(t)
            val now = demons()
            if (scene.dying) {
                deathTics++
                assertTrue(
                    now <= previous,
                    "tic $t: ${now - previous} enemies arrived while the marine is down",
                )
            }
            previous = now
        }
        assertTrue(deathTics > 0, "the marine never died: this test verified nothing")
    }

    @Test
    fun `creatures appear well inside the visible area`() {
        GameData.clearRandom()
        val scene = Scene(worldWidth, worldHeight)
        val seen = HashSet<Actor>()
        // The widest sprite reaches about a hundred map units from its anchor, so anything
        // appearing closer than that to an edge starts partly off screen.
        val margin = 80 * GameData.FRACUNIT

        for (t in 1..TICRATE * 300) {
            scene.tick(t)
            for (a in scene.actors) {
                if (a.creature == null || !seen.add(a)) continue
                assertTrue(
                    a.x >= margin && a.x <= worldWidth * GameData.FRACUNIT - margin,
                    "tic $t: appeared at x=${a.x / GameData.FRACUNIT}, too close to the edge",
                )
                assertTrue(
                    a.y >= margin && a.y <= worldHeight * GameData.FRACUNIT - margin,
                    "tic $t: appeared at y=${a.y / GameData.FRACUNIT}, too close to the edge",
                )
            }
        }
        assertTrue(seen.size > 10, "too few spawns to judge: ${seen.size}")
    }

    @Test
    fun `the marine faces where he walks and turns only to shoot`() {
        GameData.clearRandom()
        val scene = Scene(worldWidth, worldHeight)

        var sawWalkFacing = false
        var sawAttackFacing = false
        var previous: Actor? = null
        var px = 0
        var py = 0

        for (t in 1..TICRATE * 300) {
            scene.tick(t)
            val p = scene.actors.firstOrNull { it.isPlayer && !it.dead }
            if (p == null) { previous = null; continue }

            // The invariant is about actually moving, not about being in WALK: on the tic a
            // pain state ends the actor is back in WALK without having moved that tic.
            if (p === previous && (p.x != px || p.y != py)) {
                assertEquals(p.moveDir, p.facing, "tic $t: moved but facing elsewhere")
                sawWalkFacing = true
            }
            // While firing he must look at the target, which is usually not where he is
            // heading, because he backs away as he shoots.
            if (p.mode == Mode.ATTACK && p.facing != p.moveDir) sawAttackFacing = true

            previous = p
            px = p.x
            py = p.y
        }
        assertTrue(sawWalkFacing, "the marine never moved facing his direction of travel")
        assertTrue(sawAttackFacing, "the marine never turned away from his path to shoot")
    }

    @Test
    fun `sprite rotation matches the artwork on all four axes`() {
        val a = Actor(0)

        // Taken from the sprites themselves, not from the engine formula: decoding the
        // eight rotations of the walk frame shows 1 facing the camera, 5 facing away,
        // 3 a profile facing left and 7 a profile facing right.
        //
        // Checking only the vertical pair is what let a mirrored horizontal mapping through
        // once already: 2 and 6 survive a reflection of the horizontal axis unchanged, so
        // they cannot tell a rotation from a reflection. All four are needed.
        a.facing = 2                                   // DI_NORTH, down the screen
        assertEquals(1, a.spriteRotation(), "walking towards the camera must show the front")

        a.facing = 6                                   // DI_SOUTH, up the screen
        assertEquals(5, a.spriteRotation(), "walking away must show the back")

        a.facing = 4                                   // DI_WEST, leftwards
        assertEquals(3, a.spriteRotation(), "walking left must show the left-facing profile")

        a.facing = 0                                   // DI_EAST, rightwards
        assertEquals(7, a.spriteRotation(), "walking right must show the right-facing profile")

        // The diagonals follow from the four above, and the eight must map one to one.
        assertEquals(8, (0..7).map { a.facing = it; a.spriteRotation() }.toSet().size)

        a.facing = GameData.DI_NODIR
        assertEquals(1, a.spriteRotation(), "a still actor faces the viewer")
    }

    @Test
    fun `tapping drops a pickup, dropping an icon sends demons`() {
        GameData.clearRandom()
        val scene = Scene(worldWidth, worldHeight)
        for (t in 1..TICRATE) scene.tick(t)

        val itemsBefore = scene.actors.count { it.mode == Mode.ITEM }
        scene.tapAt(300 * GameData.FRACUNIT, 800 * GameData.FRACUNIT)
        assertEquals(itemsBefore + 1, scene.actors.count { it.mode == Mode.ITEM }, "the tap dropped nothing")

        val demonsBefore = scene.actors.count { it.creature != null && !it.isPlayer }
        scene.dropAt(400 * GameData.FRACUNIT, 900 * GameData.FRACUNIT)
        assertTrue(
            scene.actors.count { it.creature != null && !it.isPlayer } > demonsBefore,
            "the icon drop summoned nobody",
        )

        // Even a tap in the corner has to land where the whole sprite is visible.
        scene.tapAt(0, 0)
        val corner = scene.actors.last { it.mode == Mode.ITEM }
        assertTrue(corner.x >= 80 * GameData.FRACUNIT, "item dropped too close to the edge")
        assertTrue(corner.y >= 80 * GameData.FRACUNIT, "item dropped too close to the edge")
    }

    @Test
    fun `interaction is ignored while the marine is dead`() {
        GameData.clearRandom()
        val scene = Scene(worldWidth, worldHeight)

        var checked = false
        for (t in 1..TICRATE * 600) {
            scene.tick(t)
            if (!scene.dying) continue
            // The death is a pause: it must not be possible to litter it with pickups
            // nobody can collect, or with demons attacking a corpse.
            val items = scene.actors.count { it.mode == Mode.ITEM }
            val demons = scene.actors.count { it.creature != null && !it.isPlayer }
            scene.tapAt(300 * GameData.FRACUNIT, 800 * GameData.FRACUNIT)
            scene.dropAt(300 * GameData.FRACUNIT, 800 * GameData.FRACUNIT)
            assertEquals(items, scene.actors.count { it.mode == Mode.ITEM }, "tic $t: tap accepted while dead")
            assertEquals(demons, scene.actors.count { it.creature != null && !it.isPlayer }, "tic $t: drop accepted while dead")
            checked = true
        }
        assertTrue(checked, "the marine never died: this test verified nothing")
    }

    @Test
    fun `nothing ever moves diagonally`() {
        GameData.clearRandom()
        val scene = Scene(worldWidth, worldHeight)

        var moves = 0
        for (t in 1..TICRATE * 600) {
            scene.tick(t)
            for (a in scene.actors) {
                if (a.creature == null || a.moveDir == GameData.DI_NODIR) continue
                moves++
                assertTrue(
                    a.moveDir % 2 == 0,
                    "tic $t: ${a.creature?.name} moving diagonally, moveDir=${a.moveDir}",
                )
                // Only the four axial sprite angles can ever be needed.
                assertTrue(
                    a.spriteRotation() in intArrayOf(1, 3, 5, 7),
                    "tic $t: rotation ${a.spriteRotation()} is a diagonal view",
                )
            }
        }
        assertTrue(moves > 5000, "not enough movement sampled: $moves")
    }

    /**
     * The retreat, measured over a real run rather than a built scene.
     *
     * It counts only the tics where healing is on the ground, because that is what buys the
     * retreat now: a weapon or an armour lying about is collected while fighting, not
     * instead of it.
     */
    @Test
    fun `a hurt marine goes for healing instead of shooting`() {
        GameData.clearRandom()
        val scene = Scene(worldWidth, worldHeight)

        var hurtTics = 0
        var attackingWhileHurt = 0
        for (t in 1..TICRATE * 600) {
            scene.tick(t)
            val p = scene.actors.firstOrNull { it.isPlayer && !it.dead } ?: continue
            if (p.health * 2 >= GameData.player.health) continue
            // Only counts when there is actually healing to go and fetch.
            if (scene.actors.none { it.mode == Mode.ITEM && it.item?.kind == GameData.ITEM_HEALTH }) continue
            hurtTics++
            if (p.mode == Mode.ATTACK) attackingWhileHurt++
        }
        assertTrue(hurtTics > 100, "the marine was never hurt with healing available")
        // He may still be finishing an attack begun before dropping below half health, so
        // this is about not starting new ones rather than never being in the state.
        assertTrue(
            attackingWhileHurt * 4 < hurtTics,
            "hurt with healing around but still shooting for $attackingWhileHurt of $hurtTics tics",
        )
    }

    @Test
    fun `the marine stands still for a moment after materialising`() {
        GameData.clearRandom()
        val scene = Scene(worldWidth, worldHeight)

        val arrives = Scene.ARRIVAL_DELAY
        for (t in 1..arrives) scene.tick(t)
        val p = scene.actors.first { it.isPlayer }
        val x = p.x
        val y = p.y

        // Long enough to read as an arrival: the creatures' own reactiontime of 8 tics is
        // under a quarter of a second and goes unnoticed.
        for (t in arrives + 1..arrives + TICRATE / 3) {
            scene.tick(t)
            assertEquals(x, p.x, "tic $t: the marine moved before his pause was over")
            assertEquals(y, p.y, "tic $t: the marine moved before his pause was over")
        }

        // ...and then he does get going.
        for (t in arrives + TICRATE / 3..arrives + TICRATE * 3) scene.tick(t)
        assertTrue(p.x != x || p.y != y, "the marine never started moving")
    }

    @Test
    fun `a small surface still keeps everyone on screen`() {
        // The world is derived from the surface, so it follows the display. What it must
        // also survive is a surface small enough that the spawn margin no longer fits:
        // split screen, a cover display, or the thumbnail in a wallpaper picker.
        GameData.clearRandom()
        val narrow = 180
        val short = 320
        val scene = Scene(narrow, short)

        for (t in 1..TICRATE * 120) {
            scene.tick(t)
            for (a in scene.actors) {
                assertTrue(
                    a.x >= 0 && a.x <= narrow * GameData.FRACUNIT,
                    "tic $t: x=${a.x / GameData.FRACUNIT} outside a $narrow unit world",
                )
                assertTrue(
                    a.y >= 0 && a.y <= short * GameData.FRACUNIT,
                    "tic $t: y=${a.y / GameData.FRACUNIT} outside a $short unit world",
                )
            }
        }
    }

    @Test
    fun `combat actually happens`() {
        GameData.clearRandom()
        val scene = Scene(worldWidth, worldHeight)
        var sawBlood = false
        var sawDeath = false
        var sawProjectile = false

        for (t in 1..TICRATE * 600) {
            scene.tick(t)
            for (a in scene.actors) {
                if (a.spriteIndex == GameData.bloodSpriteIndex) sawBlood = true
                if (a.mode == Mode.PROJECTILE) sawProjectile = true
                if (a.dead) sawDeath = true
            }
        }
        assertTrue(sawBlood, "no hit landed in ten minutes")
        assertTrue(sawProjectile, "no fireball was thrown")
        assertTrue(sawDeath, "nobody died")
    }

    /**
     * Shots leave and land at chest height, and that height lives in the drawing only.
     *
     * The distinction is the whole point. y is depth in this projection, so raising a
     * fireball by writing into y moves it thirty-four units back in the world: it sorts
     * against the wrong actors, its distance to the target is wrong, and it can pass through
     * something narrow enough for the radius test to miss. Drawn higher, it flies exactly the
     * trajectory it was given.
     *
     * So the assertion is not "it looks right" but "nothing in the world was moved to make it
     * look right": everything that fights or stands has no draw height at all, and only the
     * things that leave a barrel have one.
     */
    @Test
    fun `height is drawn, never simulated`() {
        GameData.clearRandom()
        val scene = Scene(worldWidth, worldHeight)
        var sawRaised = false

        for (t in 1..TICRATE * 600) {
            scene.tick(t)
            for (a in scene.actors) {
                val raised = a.drawHeight != 0
                if (raised) sawRaised = true
                // A missile's blast is at the height the missile was flying at, and carries
                // the missile's own sprite, which is how it is told from anything else.
                val blast = a.mode == Mode.EFFECT &&
                    GameData.projectiles.any { it.spriteIndex == a.spriteIndex }
                when {
                    a.mode == Mode.PROJECTILE || blast ||
                        a.spriteIndex == GameData.bloodSpriteIndex ->
                        assertEquals(
                            Scene.MUZZLE_HEIGHT, a.drawHeight,
                            "a shot, its blast or its blood is not at chest height at tic $t",
                        )
                    // Creatures, corpses, pickups and the teleport fog all stand on the floor.
                    else -> assertEquals(
                        0, a.drawHeight,
                        "something that belongs on the ground is raised at tic $t",
                    )
                }
            }
        }
        assertTrue(sawRaised, "nothing was ever drawn above the floor in ten minutes")
    }

    /**
     * The blast a rocket leaves where it landed, which is the whole of what an impact looks
     * like: MISL frames B,C,D, spawned on the hit and gone once they have run.
     */
    @Test
    fun `a missile that hits leaves an explosion behind it`() {
        GameData.clearRandom()
        val scene = Scene(720, 1600)
        val rocket = GameData.projectiles[GameData.PROJECTILE_ROCKET]
        val zombie = GameData.creatures[0]

        val shooter = Actor(GameData.player.spriteIndex).apply {
            creature = GameData.player
            isPlayer = true
            health = GameData.player.health
            x = 100 * GameData.FRACUNIT
            y = 100 * GameData.FRACUNIT
        }
        val target = Actor(zombie.spriteIndex).apply {
            creature = zombie
            health = zombie.health
            x = 140 * GameData.FRACUNIT
            y = 100 * GameData.FRACUNIT
        }
        scene.actors.add(shooter)
        scene.actors.add(target)
        scene.missileForTest(shooter, target, rocket)

        var t = 0
        while (t < 60 && scene.actors.none { it.mode == Mode.EFFECT }) scene.tick(++t)

        val blast = scene.actors.firstOrNull { it.mode == Mode.EFFECT && it.anim === rocket.burst }
        assertTrue(blast != null, "the rocket must leave its blast where it landed")
        assertTrue(scene.actors.none { it.mode == Mode.PROJECTILE }, "and stop being a rocket")

        // Its own tics, and then it is gone: an effect that outlived its animation would
        // stay on the field forever.
        val lifetime = rocket.burst!!.tics.sum()
        repeat(lifetime + 1) { scene.tick(++t) }
        assertTrue(scene.actors.none { it.mode == Mode.EFFECT && it.anim === rocket.burst },
            "the blast must clear itself once it has run")
    }

    /**
     * The debug readout's weapon line. It reports what [Scene.fireAttack] would reach for,
     * and the arsenal is ranked by damage per second, so the answer is worth showing - but
     * only while there is a marine to hold anything.
     */
    @Test
    fun `the weapon in hand is readable, and only while the marine stands`() {
        GameData.clearRandom()
        val scene = Scene(worldWidth, worldHeight)
        assertTrue(scene.playerWeapon == null, "nobody is on the field on the first tic")

        var t = 0
        while (t < TICRATE * 120 && scene.playerWeapon == null) scene.tick(++t)
        val held = scene.playerWeapon
        assertTrue(held != null, "the marine arrived and is holding nothing")
        assertTrue(held in GameData.weapons, "the weapon is not one from the table")
    }

    /**
     * Breaking off a fight costs the marine his gun, so only healing is worth it.
     *
     * It used to be any pickup at all, and nearestItem has no range: a weapon in the far
     * corner took him out of the fight and walked him across the map, which is what "the
     * marine stopped shooting for no reason" looked like from outside.
     */
    @Test
    fun `a hurt marine keeps firing unless there is healing to fetch`() {
        fun scene(pickup: GameData.Item): Scene {
            GameData.clearRandom()
            val s = Scene(worldWidth, worldHeight)
            var t = 0
            while (t < TICRATE * 120 && s.actors.none { it.isPlayer }) s.tick(++t)
            val marine = s.actors.first { it.isPlayer }
            // Hurt enough to want out, with a demon close enough to shoot at.
            marine.health = GameData.player.health / 4
            val zombie = GameData.creatures[0]
            s.actors.add(Actor(zombie.spriteIndex).apply {
                creature = zombie
                health = zombie.health
                x = marine.x + 200 * GameData.FRACUNIT
                y = marine.y
            })
            s.actors.add(Actor(pickup.spriteIndex).apply {
                mode = Mode.ITEM
                item = pickup
                x = marine.x
                y = marine.y + 300 * GameData.FRACUNIT
            })
            repeat(60) { s.tick(++t) }
            return s
        }

        val weapon = GameData.items.first { it.kind == GameData.ITEM_WEAPON }
        val stimpack = GameData.items.first { it.kind == GameData.ITEM_HEALTH }

        val withWeapon = scene(weapon).actors.first { it.isPlayer }
        assertTrue(
            withWeapon.mode == Mode.ATTACK,
            "a gun on the ground is no reason to stop shooting, but he is ${withWeapon.mode}",
        )

        val withHealing = scene(stimpack).actors.first { it.isPlayer }
        assertTrue(
            withHealing.mode != Mode.ATTACK,
            "healing within reach and hurt: he should be fetching it, not shooting",
        )
    }
}
