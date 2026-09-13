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

/**
 * Constants extracted from the original id source (linuxdoom-1.10, GPL-2.0).
 *
 * No value here is invented or estimated: each one carries the file and symbol it came
 * from. That is also the project's legal footing — every number has a traceable, licensed
 * provenance, independent of any other implementation.
 */
object GameData {

    // m_fixed.h
    const val FRACBITS = 16
    const val FRACUNIT = 1 shl FRACBITS

    // p_local.h:57
    const val MELEERANGE = 64 * FRACUNIT

    /**
     * p_enemy.c: the 8 movement directions of the monsters. No trigonometry, just a
     * table. 47000 is the diagonal (~0.717, slightly above 0.707: that is id's original
     * approximation, not a transcription error).
     *
     * The table is kept whole because it is a verbatim copy of the source, but this scene
     * only ever selects the four cardinal entries: diagonal movement was dropped because
     * axial movement reads far more clearly at this scale, and it halves the sprite angles
     * in play.
     */
    val xspeed = intArrayOf(FRACUNIT, 47000, 0, -47000, -FRACUNIT, -47000, 0, 47000)
    val yspeed = intArrayOf(0, 47000, FRACUNIT, 47000, 0, -47000, -FRACUNIT, -47000)

    /** p_enemy.c: opposite[] — the reverse direction. DI_NODIR (8) stays 8. */
    val opposite = intArrayOf(4, 5, 6, 7, 0, 1, 2, 3, 8)

    const val DI_NODIR = 8

    /**
     * m_random.c: rndtable[]. The original engine uses no generator but a fixed 256-byte table with an
     * advancing index. Deterministic, reproducible for debugging, and cheaper than
     * kotlin.random.Random.
     */
    val rndtable = intArrayOf(
        0, 8, 109, 220, 222, 241, 149, 107, 75, 248, 254, 140, 16, 66,
        74, 21, 211, 47, 80, 242, 154, 27, 205, 128, 161, 89, 77, 36,
        95, 110, 85, 48, 212, 140, 211, 249, 22, 79, 200, 50, 28, 188,
        52, 140, 202, 120, 68, 145, 62, 70, 184, 190, 91, 197, 152, 224,
        149, 104, 25, 178, 252, 182, 202, 182, 141, 197, 4, 81, 181, 242,
        145, 42, 39, 227, 156, 198, 225, 193, 219, 93, 122, 175, 249, 0,
        175, 143, 70, 239, 46, 246, 163, 53, 163, 109, 168, 135, 2, 235,
        25, 92, 20, 145, 138, 77, 69, 166, 78, 176, 173, 212, 166, 113,
        94, 161, 41, 50, 239, 49, 111, 164, 70, 60, 2, 37, 171, 75,
        136, 156, 11, 56, 42, 146, 138, 229, 73, 146, 77, 61, 98, 196,
        135, 106, 63, 197, 195, 86, 96, 203, 113, 101, 170, 247, 181, 113,
        80, 250, 108, 7, 255, 237, 129, 226, 79, 107, 112, 166, 103, 241,
        24, 223, 239, 120, 198, 58, 60, 82, 128, 3, 184, 66, 143, 224,
        145, 224, 81, 206, 163, 45, 63, 90, 168, 114, 59, 33, 159, 95,
        28, 139, 123, 98, 125, 196, 15, 70, 194, 253, 54, 14, 109, 226,
        71, 17, 161, 93, 186, 87, 244, 138, 20, 52, 123, 251, 26, 36,
        17, 46, 52, 231, 232, 76, 31, 221, 84, 37, 216, 165, 212, 106,
        197, 242, 98, 43, 39, 175, 254, 145, 190, 84, 118, 222, 187, 136,
        120, 163, 236, 249
    )

    private var prndindex = 0

    /** m_random.c: P_Random(). */
    fun pRandom(): Int {
        prndindex = (prndindex + 1) and 0xff
        return rndtable[prndindex]
    }

    fun clearRandom() {
        prndindex = 0
    }

    /**
     * A sequence from states[]: frame indices and how many tics each one lasts.
     * A tic value of -1 means "stays forever" (the last death frame: the corpse).
     */
    class Anim(val frames: IntArray, val tics: IntArray) {
        val length get() = frames.size
    }

    /**
     * A creature: the values come from info.c, mobjinfo[] and states[].
     *
     * [lumpPrefix] is the sprite name, identical in every IWAD this loader accepts because the
     * engine hardcodes it in sprnames[].
     * [walkTics] and [walkFrames] give the walk animation: the original engine repeats each
     * frame twice (A,A,B,B,...), so every frame lasts walkTics*2.
     *
     * Attacks: [damage] is what one attack deals, **fixed**, exactly as the marine's weapons
     * are fixed - no roll, no multiplier, no shot count. Which of [melee], [hitscan] and
     * [projectile] is set decides only *how* it is delivered: melee needs contact, hitscan
     * lands at once, a projectile flies. A creature with both melee and a projectile uses
     * melee when the target is in reach, and both deal the same [damage].
     *
     * The originals rolled: `(P_Random() % 8 + 1) * 3` for a claw, `((P_Random() % 5) + 1) * 3`
     * per instant shot, `((P_Random() % 8) + 1) * base` for a missile. Those are gone with the
     * skill levers, and for the same reason - a wallpaper nobody is playing has nothing to do
     * with the spread, and the numbers below can be read against the health beside them.
     */
    class Creature(
        val name: String,
        val lumpPrefix: String,
        val speed: Int,
        val health: Int,
        val radius: Int,
        val walkFrames: Int,
        val walkTics: Int,
        val attack: Anim,
        val pain: Anim,
        val death: Anim,
        val painChance: Int,
        val damage: Int = 0,
        val melee: Boolean = false,
        val hitscan: Boolean = false,
        val projectile: Int = -1,
    ) {
        var spriteIndex = -1

        /**
         * Position in [creatures], or -1 for the marine, who is not in the bestiary.
         *
         * Statistics are kept per creature and this is what indexes them. Assigned in the
         * same init block as [spriteIndex] rather than searched for, so recording a kill
         * costs an array write instead of a scan of fourteen — this runs inside the tick
         * loop, thirty-five times a second.
         */
        var index = -1
    }

    /** Fireball in flight: 2 frames of 4 tics looping (S_TBALL1/2, S_BRBALL1/2). */
    val ballAnim = Anim(intArrayOf(0, 1), intArrayOf(4, 4))

    /**
     * The rocket in flight: one frame, S_ROCKET, sprite MISL frame A.
     *
     * Unlike every other missile here, MISL's second and third frames are not more flight -
     * they are S_EXPLODE1..3, the blast. Looping the fireball's two frames over it made the
     * rocket flicker into its own explosion for half of every travelling tic.
     */
    val rocketAnim = Anim(intArrayOf(0), intArrayOf(4))

    /**
     * The blast a missile leaves where it landed: states S_EXPLODE1..3, sprite MISL frames
     * B,C,D at 8, 6 and 4 tics - the frames the flight loop above had to stop borrowing.
     */
    val rocketBurst = Anim(intArrayOf(1, 2, 3), intArrayOf(8, 6, 4))

    /**
     * A fireball going out: S_TBALLX1..3 and S_BRBALLX1..3, frames C,D,E at 6 tics each.
     * Both sequences are the same shape, and both live in the fireball's own sprite.
     */
    val ballBurst = Anim(intArrayOf(2, 3, 4), intArrayOf(6, 6, 6))

    /**
     * info.c: the missiles. Their `speed` is already in FRACUNIT, unlike the monsters'.
     *
     * A missile carries no damage of its own: whoever fired it says what it deals, the same
     * figure that shooter would have dealt by hand. mobjinfo's `damage` was a multiplicand for
     * a roll that no longer happens, so it is gone with the roll.
     *
     * [anim] is the flight loop, the fireball's two frames unless a missile says otherwise.
     *
     * [burst] is what is drawn where it lands, and is null for four of the seven. In the
     * engine those four explode into sprites of their own - PLSE for the plasma, APBX for the
     * arachnotron's, FBXP for the tracer - and a missile whose blast is not in its own sprite
     * would have to add a prefix to spritePrefixes, to the reducer's keep-list in
     * build.gradle.kts, and to the count WadStore checks on import.
     * ponytail: the four fade out as they always did; add the prefixes if the plasma's blast
     * turns out to be missed.
     */
    class Projectile(
        val lumpPrefix: String,
        val speed: Int,
        val anim: Anim = ballAnim,
        val burst: Anim? = null,
    ) {
        var spriteIndex = -1
    }

    // info.c mobjinfo[]: MT_TROOPSHOT and MT_BRUISERSHOT are the monsters' fireballs,
    // MT_PLASMA and MT_ROCKET the marine's. Speeds verbatim from the table.
    val projectiles = listOf(
        Projectile("BAL1", speed = 10, burst = ballBurst),  // MT_TROOPSHOT
        Projectile("BAL7", speed = 15, burst = ballBurst),  // MT_BRUISERSHOT
        Projectile("PLSS", speed = 25),      // MT_PLASMA, which explodes into PLSE
        Projectile("MISL", speed = 20, anim = rocketAnim, burst = rocketBurst), // MT_ROCKET
        Projectile("FATB", speed = 10),      // MT_TRACER, without the homing
        Projectile("MANF", speed = 20),      // MT_FATSHOT
        Projectile("APLS", speed = 25),      // MT_ARACHPLAZ
    )

    const val PROJECTILE_PLASMA = 2
    const val PROJECTILE_ROCKET = 3
    const val PROJECTILE_TRACER = 4
    const val PROJECTILE_FATSHOT = 5
    const val PROJECTILE_ARACHPLAZ = 6

    /** Blood: states S_BLOOD1..3, sprite BLUD, frames C,B,A at 8 tics each. */
    val bloodAnim = Anim(intArrayOf(2, 1, 0), intArrayOf(8, 8, 8))

    /** Teleport fog: states S_TFOG*, 6 tics per frame. */
    val fogAnim = Anim(IntArray(10) { it }, IntArray(10) { 6 })

    /**
     * The bestiary, ordered by escalation.
     *
     * Health orders all of it: the roster climbs from the Zombie to the Overlord, which
     * closes it. These health values are rebalanced for the wallpaper and are ours, not the
     * original's - the info.c figures they descend from are recorded in docs/BALANCE.md. The
     * order still has to mean something, because substitute walks it looking for a creature
     * the WAD can draw.
     *
     * Names are ours. Where Freedoom has one it is used; the rest are descriptive, and none
     * are the trademarked ones. Every other value beside them - speed, radius, timings, attack,
     * pain chance - is from info.c, with the provenance in the comment above each Creature.
     *
     * Which of these ever appears is decided by the loaded WAD alone. Freedoom Phase 1 and a
     * Phase 1 IWAD carry exactly the same roster - measured, not assumed - and a Phase 2 IWAD
     * adds five more of the entries below. Nothing here records which file has what.
     */
    val creatures = listOf(
        // mobjinfo[MT_POSSESSED]; S_POSS_RUN 4 tics, ATK 10/8/8, PAIN 3+3, DIE 5,5,5,5,-1
        Creature(
            "Zombie", "POSS", speed = 8, health = 1, radius = 20, walkFrames = 4, walkTics = 4,
            attack = Anim(intArrayOf(4, 5, 4), intArrayOf(10, 8, 8)),
            pain = Anim(intArrayOf(6, 6), intArrayOf(3, 3)),
            death = Anim(intArrayOf(7, 8, 9, 10, 11), intArrayOf(5, 5, 5, 5, -1)),
            painChance = 200, damage = 1, hitscan = true,
        ),
        // mobjinfo[MT_SHOTGUY]; A_SPosAttack fires 3 shots
        Creature(
            "Shotgun Zombie", "SPOS", speed = 8, health = 2, radius = 20, walkFrames = 4, walkTics = 3,
            attack = Anim(intArrayOf(4, 5, 4), intArrayOf(10, 10, 10)),
            pain = Anim(intArrayOf(6, 6), intArrayOf(3, 3)),
            death = Anim(intArrayOf(7, 8, 9, 10, 11), intArrayOf(5, 5, 5, 5, -1)),
            painChance = 170, damage = 2, hitscan = true,
        ),
        // mobjinfo[MT_TROOP]; A_TroopAttack: melee, otherwise MT_TROOPSHOT
        Creature(
            "Serpentipede", "TROO", speed = 8, health = 3, radius = 20, walkFrames = 4, walkTics = 3,
            attack = Anim(intArrayOf(4, 5, 6), intArrayOf(8, 8, 6)),
            pain = Anim(intArrayOf(7, 7), intArrayOf(2, 2)),
            death = Anim(intArrayOf(8, 9, 10, 11, 12), intArrayOf(8, 8, 6, 6, -1)),
            painChance = 200, damage = 3, melee = true, projectile = 0,
        ),
        // mobjinfo[MT_CHAINGUY]; A_CPosAttack, a hitscan burst.
        // Phase 2 and later: absent from Phase 1 and from Freedoom Phase 1.
        Creature(
            "Chaingun Zombie", "CPOS", speed = 8, health = 4, radius = 20, walkFrames = 4, walkTics = 3,
            attack = Anim(intArrayOf(4, 5, 4), intArrayOf(10, 4, 4)),
            pain = Anim(intArrayOf(6, 6), intArrayOf(3, 3)),
            death = Anim(intArrayOf(7, 8, 9, 10, 11, 12, 13), intArrayOf(5, 5, 5, 5, 5, 5, -1)),
            painChance = 170, damage = 4, hitscan = true,
        ),
        // mobjinfo[MT_SKULL]; charges rather than walks, and damages by collision.
        // Present in every IWAD this loader accepts.
        Creature(
            "Charger", "SKUL", speed = 8, health = 5, radius = 16, walkFrames = 2, walkTics = 3,
            attack = Anim(intArrayOf(2, 3, 2), intArrayOf(10, 4, 4)),
            pain = Anim(intArrayOf(4, 4), intArrayOf(3, 3)),
            // info.c S_SKULL_DIE1..DIE6: the last one is `{SPR_SKUL, 32775, 6, {NULL}, S_NULL}`
            // - it runs for six tics and then goes to S_NULL, which removes the thing. This
            // one does not die, it detonates: the six frames are the skull bursting into a
            // ball of fire that fades to a ring and is gone, and every frame is fullbright.
            //
            // The last tic was -1 here, our marker for "stays forever", which is right for
            // every other creature and wrong for this one: it left the final frame of an
            // explosion - 103 by 90 pixels of red ring in Phase 2, wider than the marine is
            // tall - lying on the ground for thirty seconds, on top of whatever walked over
            // it. That was read as a corpse with a blood splat beside it. There is no corpse.
            death = Anim(intArrayOf(5, 6, 7, 8, 9, 10), intArrayOf(6, 6, 6, 6, 6, 6)),
            painChance = 256, damage = 5, melee = true,
        ),
        // mobjinfo[MT_SERGEANT] speed 10; A_SargAttack: melee only
        Creature(
            "Flesh Worm", "SARG", speed = 10, health = 8, radius = 30, walkFrames = 4, walkTics = 2,
            attack = Anim(intArrayOf(4, 5, 6), intArrayOf(8, 8, 8)),
            pain = Anim(intArrayOf(7, 7), intArrayOf(2, 2)),
            death = Anim(intArrayOf(8, 9, 10, 11, 12, 13), intArrayOf(8, 8, 4, 4, 4, -1)),
            painChance = 180, damage = 6, melee = true,
        ),
        // mobjinfo[MT_UNDEAD]; A_SkelFist punches, A_SkelMissile fires
        // MT_TRACER. The tracer's homing is not reproduced: it flies straight here.
        // Phase 2 and later.
        Creature(
            "Bone Stalker", "SKEL", speed = 10, health = 10, radius = 20, walkFrames = 6, walkTics = 2,
            attack = Anim(intArrayOf(6, 7, 8), intArrayOf(6, 6, 6)),
            pain = Anim(intArrayOf(10, 10), intArrayOf(5, 5)),
            death = Anim(intArrayOf(11, 12, 13, 14, 15, 16), intArrayOf(7, 7, 7, 7, 7, -1)),
            painChance = 100, damage = 7, melee = true, projectile = PROJECTILE_TRACER,
        ),
        // mobjinfo[MT_HEAD]; A_HeadAttack: melee, otherwise MT_HEADSHOT.
        // ponytail: reuses BAL1 instead of BAL2 - one fireball sprite less to handle, and
        // BAL2 is not guaranteed to exist in every IWAD.
        Creature(
            "Trilobite", "HEAD", speed = 8, health = 15, radius = 31, walkFrames = 1, walkTics = 3,
            attack = Anim(intArrayOf(1, 2, 3), intArrayOf(5, 5, 5)),
            pain = Anim(intArrayOf(4, 4), intArrayOf(3, 3)),
            death = Anim(intArrayOf(6, 7, 8, 9, 10, 11), intArrayOf(8, 8, 8, 8, 8, -1)),
            painChance = 128, damage = 8, melee = true, projectile = 0,
        ),
        // mobjinfo[MT_KNIGHT]; the same attack as MT_BRUISER at half the health.
        // Phase 2 and later.
        Creature(
            "Lesser Lord", "BOS2", speed = 8, health = 20, radius = 24, walkFrames = 4, walkTics = 3,
            attack = Anim(intArrayOf(4, 5, 6), intArrayOf(8, 8, 8)),
            pain = Anim(intArrayOf(7, 7), intArrayOf(2, 2)),
            death = Anim(intArrayOf(8, 9, 10, 11, 12, 13, 14), intArrayOf(8, 8, 8, 8, 8, 8, -1)),
            painChance = 50, damage = 9, melee = true, projectile = 1,
        ),
        // mobjinfo[MT_BABY]; A_BspiAttack fires MT_ARACHPLAZ. Phase 2 and later.
        Creature(
            "Spiderling", "BSPI", speed = 12, health = 25, radius = 64, walkFrames = 6, walkTics = 3,
            attack = Anim(intArrayOf(0, 6, 7), intArrayOf(20, 4, 4)),
            pain = Anim(intArrayOf(8, 8), intArrayOf(3, 3)),
            death = Anim(intArrayOf(9, 10, 11, 12, 13, 14, 15), intArrayOf(20, 7, 7, 7, 7, 7, -1)),
            painChance = 128, damage = 10, projectile = PROJECTILE_ARACHPLAZ,
        ),
        // mobjinfo[MT_FATSO]; A_FatAttack fires MT_FATSHOT in a spread, modelled as one.
        // Phase 2 and later.
        Creature(
            "Bloater", "FATT", speed = 8, health = 30, radius = 48, walkFrames = 6, walkTics = 4,
            attack = Anim(intArrayOf(6, 7, 8), intArrayOf(20, 10, 5)),
            pain = Anim(intArrayOf(9, 9), intArrayOf(3, 3)),
            death = Anim(intArrayOf(10, 11, 12, 13, 14, 15, 16, 17, 18), intArrayOf(6, 6, 6, 6, 6, 6, 6, 6, -1)),
            painChance = 80, damage = 11, projectile = PROJECTILE_FATSHOT,
        ),
        // mobjinfo[MT_BRUISER]; A_BruisAttack: melee, otherwise MT_BRUISERSHOT
        Creature(
            "Pain Lord", "BOSS", speed = 8, health = 45, radius = 24, walkFrames = 4, walkTics = 3,
            attack = Anim(intArrayOf(4, 5, 6), intArrayOf(8, 8, 8)),
            pain = Anim(intArrayOf(7, 7), intArrayOf(2, 2)),
            death = Anim(intArrayOf(8, 9, 10, 11, 12, 13, 14), intArrayOf(8, 8, 8, 8, 8, 8, -1)),
            painChance = 50, damage = 12, melee = true, projectile = 1,
        ),
        // mobjinfo[MT_CYBORG]; fires MT_ROCKET. Present in every IWAD this loader accepts.
        // At 30% of the screen width it is barely larger than the PainLord already drawn
        // at 23%.
        Creature(
            "Cyberlord", "CYBR", speed = 16, health = 60, radius = 40, walkFrames = 4, walkTics = 3,
            attack = Anim(intArrayOf(4, 5, 4), intArrayOf(6, 12, 12)),
            pain = Anim(intArrayOf(6, 6), intArrayOf(10, 10)),
            death = Anim(intArrayOf(7, 8, 9, 10, 11, 12, 13, 14), intArrayOf(10, 10, 10, 10, 10, 10, 10, -1)),
            painChance = 20, damage = 13, projectile = PROJECTILE_ROCKET,
        ),
        // mobjinfo[MT_SPIDER]; A_SPosAttack with A_SpidRefire, so a hitscan burst. Present
        // in every IWAD this loader accepts. It is the largest thing drawn by a distance -
        // 71% of the screen width against the PainLord's 23% - and is here because that was
        // asked for after the measurement, not in spite of it.
        Creature(
            "Overlord", "SPID", speed = 12, health = 70, radius = 128, walkFrames = 6, walkTics = 3,
            attack = Anim(intArrayOf(0, 6, 7), intArrayOf(20, 4, 4)),
            pain = Anim(intArrayOf(8, 8), intArrayOf(3, 3)),
            death = Anim(intArrayOf(9, 10, 11, 12, 13, 14, 15, 16, 17, 18), intArrayOf(20, 10, 10, 10, 10, 10, 10, 10, 10, -1)),
            painChance = 40, damage = 14, hitscan = true,
        ),
    )

    val player = Creature(
        "Player", "PLAY", speed = 8, health = 100, radius = 16, walkFrames = 4, walkTics = 4,
        attack = Anim(intArrayOf(4, 5, 4), intArrayOf(4, 6, 4)),
        pain = Anim(intArrayOf(6, 6), intArrayOf(4, 4)),
        death = Anim(intArrayOf(7, 8, 9, 10, 11, 12, 13), intArrayOf(10, 10, 10, 10, 10, 10, -1)),
        // hitscan only says he attacks at range at all; the weapon in hand says what it deals,
        // so [damage] stays 0 here and is never read.
        painChance = 255, hitscan = true,
    )

    /**
     * Palette entries used to recolour the corner readout.
     *
     * Chosen by measuring PLAYPAL rather than by eye, because on this palette saturation and
     * legibility pull against each other. The blue ramp runs 193 (199,199,255) down to 200
     * (0,0,255): it gets bluer by *removing* red and green, so it darkens as it saturates.
     * Pure blue at 200 has a luminance of 29 and is the least legible entry in the set on a
     * dark backdrop.
     *
     * 198 is (55,55,255) at luminance 78 — unmistakably a saturated blue rather than the
     * washed-out periwinkle of 196, and level with the WAD's own red numerals at 76, which
     * are already accepted as readable. That is the floor this sits on, not a guess.
     *
     * Note the potion itself could not be sampled: the engine's health bonus is the blue vial, but
     * Freedoom redrew it green — decoding BON1A0 gives dominant colours around (75,159,63).
     * The palette is shared, so the ramp is the right source; the sprite is not.
     *
     * The green at 112 needs no such compromise, at luminance 198.
     */
    const val PALETTE_HEALTH = 198          // 55, 55, 255
    const val PALETTE_ARMOR = 112           // 119, 255, 111

    /**
     * The death wash, 179,0,0 — a deep red with no desaturation needed.
     *
     * Taken from the palette rather than computed from PLAYPAL's damage ramp, which is what
     * this used to do: the ramp's flash is 255,25,25 at ninety percent saturation, right for
     * a brief flash across a game viewport and far too loud over a home screen. Reading a
     * palette entry directly makes the colour a fact of the active WAD, like the two readout
     * colours, instead of an arithmetic adjustment layered on one.
     *
     * The ramp runs from 203,0,0 at index 180 down to 67,0,0 at 191, all pure red with no
     * green or blue at all. 187 sits in the lower half at luminance 34 — dark enough that
     * reaching full opacity leaves a deep blood red rather than a bright one.
     */
    const val PALETTE_DEATH = 187           // 115, 0, 0

    // ------------------------------------------------------------------ weapons

    /**
     * The marine's weapons, in order of power: the arsenal always reaches for the highest
     * one that is loaded, so this list is the ranking.
     *
     * A hitscan weapon lands its [damage] at once; a weapon with a [projectile] fires one
     * missile that carries the same figure, which is the mechanism the monsters' fireballs
     * use too.
     *
     * Which of them can appear depends on the loaded WAD: the super shotgun is Phase 2 and
     * later, so a Phase 1 IWAD simply never drops one. Nothing here lists which file has
     * what - the sprite either resolves or it does not.
     */
    class Weapon(
        val name: String,
        val lumpPrefix: String,
        val ammo: Int,              // index into AMMO_*; -1 = consumes nothing
        val attack: Anim,
        val damage: Int,            // fixed damage per trigger pull; see docs/BALANCE.md
        val projectile: Int = -1,   // set for a missile weapon: index into [projectiles]
    ) {
        /**
         * Damage per second, which is what "the most powerful weapon" has to mean: the arsenal
         * reaches for the highest one loaded. Per second rather than per shot because the
         * chaingun's whole advantage is its rate, and a whole number per second rather than a
         * fraction per tic because nothing here needs the fraction - the ranking is the same.
         */
        val damagePerSecond: Int by lazy { damage * TICRATE / attack.tics.sum().coerceAtLeast(1) }
    }

    const val AMMO_BULLETS = 0
    const val AMMO_SHELLS = 1
    const val AMMO_CELLS = 2
    const val AMMO_ROCKETS = 3

    /** p_inter.c: maxammo[NUMAMMO] = {200, 50, 300, 50}. */
    val maxAmmo = intArrayOf(200, 50, 300, 50)

    /** p_inter.c: clipammo[NUMAMMO] = {10, 4, 20, 1}. A weapon carries two clip loads. */
    val clipAmmo = intArrayOf(10, 4, 20, 1)

    val weapons = listOf(
        // The pistol consumes no ammo: in a wallpaper, being disarmed forever would be a
        // deadlock, and the marine cannot go looking for ammo the way a player would.
        Weapon("Pistol", "PIST", -1, Anim(intArrayOf(4, 5, 4), intArrayOf(6, 8, 6)), damage = 1),
        Weapon("Shotgun", "SHOT", AMMO_SHELLS, Anim(intArrayOf(4, 5, 4), intArrayOf(6, 10, 8)), damage = 7),
        Weapon("Chaingun", "MGUN", AMMO_BULLETS, Anim(intArrayOf(4, 5), intArrayOf(3, 3)), damage = 1),
        Weapon("Super Shotgun", "SGN2", AMMO_SHELLS, Anim(intArrayOf(4, 5, 4), intArrayOf(6, 12, 10)), damage = 20),
        Weapon("Plasma Rifle", "PLAS", AMMO_CELLS, Anim(intArrayOf(4, 5), intArrayOf(3, 3)), damage = 2, projectile = PROJECTILE_PLASMA),
        Weapon("Rocket Launcher", "LAUN", AMMO_ROCKETS, Anim(intArrayOf(4, 5, 4), intArrayOf(8, 12, 10)), damage = 10, projectile = PROJECTILE_ROCKET),
    )

    const val WEAPON_PISTOL = 0
    const val WEAPON_SHOTGUN = 1
    const val WEAPON_CHAINGUN = 2

    // ------------------------------------------------------------------ items

    const val ITEM_HEALTH = 0
    const val ITEM_ARMOR = 1
    const val ITEM_WEAPON = 2

    /**
     * The pickups. Values from p_inter.c: stimpack 10, medikit 25, green armour 100 points
     * of type 1, blue 200 of type 2, and a picked-up weapon carries two clip loads.
     */
    class Item(
        val lumpPrefix: String,
        val kind: Int,
        val amount: Int,
        /** Armour type or weapon index, depending on [kind]. */
        val extra: Int = 0,
        val frames: Int = 1,
        /** Relative share of the drops. See [dropTable]. */
        val weight: Int = 1,
    ) {
        var spriteIndex = -1

        /** Position in [items], for the same reason [Creature.index] exists. */
        var index = -1
    }

    /**
     * The pickups, and how often each one is dropped.
     *
     * There is no ammunition here on purpose. Ammo only ever arrives inside a weapon now,
     * which is what makes an empty weapon a real loss rather than a pause: it cannot be
     * refilled where it lies, so the marine falls back down his arsenal and has to find
     * another one.
     *
     * The weights lean towards staying alive, in that order: healing first, then armour,
     * then the guns, with the more powerful one of each pair rarer than the plain one.
     */
    val items = listOf(
        Item("STIM", ITEM_HEALTH, 10, weight = 10),                       // stimpack
        Item("MEDI", ITEM_HEALTH, 25, weight = 8),                        // medikit
        Item("ARM1", ITEM_ARMOR, 100, extra = 1, frames = 2, weight = 8),  // green armour
        Item("ARM2", ITEM_ARMOR, 200, extra = 2, frames = 2, weight = 4),  // blue armour
        // One per weapon past the pistol, each carrying five clip loads. The weights are
        // scaled up from the old pair rather than squeezed, so healing still outweighs
        // armour and armour still outweighs the guns with five of them in the table.
        Item("SHOT", ITEM_WEAPON, 5, extra = 1, weight = 3),               // shotgun
        Item("MGUN", ITEM_WEAPON, 5, extra = 2, weight = 2),               // chaingun
        Item("SGN2", ITEM_WEAPON, 5, extra = 3, weight = 2),               // super shotgun
        Item("PLAS", ITEM_WEAPON, 5, extra = 4, weight = 2),               // plasma rifle
        Item("LAUN", ITEM_WEAPON, 5, extra = 5, weight = 1),               // rocket launcher
    )

    /**
     * The weighted draw, flattened once at startup: an item index repeated as many times as
     * its weight, so choosing one is a single indexed read of P_Random rather than a walk
     * over a cumulative total on every drop.
     */
    val dropTable: IntArray = items.indices
        .flatMap { i -> List(items[i].weight) { i } }
        .toIntArray()

    /**
     * The same draw, split in two: what keeps him alive, and what he kills with.
     *
     * The scene alternates between them, so a run cannot deal him four guns in a row while
     * his health falls, and cannot bury a weapon under stimpacks either. Derived from the
     * one table above rather than written out again - the weights inside each half are still
     * the ones declared on the items.
     */
    val supplyTable: IntArray = dropTable.filter { items[it].kind != ITEM_WEAPON }.toIntArray()
    val weaponTable: IntArray = dropTable.filter { items[it].kind == ITEM_WEAPON }.toIntArray()

    /** p_inter.c: armour absorbs a third of the damage when green, half when blue. */
    fun armorSaved(damage: Int, armorType: Int): Int =
        if (armorType == 1) damage / 3 else damage / 2

    // ------------------------------------------------------------------ skill

    // Tics between automatic drops: the wallpaper's own difficulty lever, not the engine's.
    // The engine varies difficulty by what a map contains, and this scene has no map.

    /** Seconds between drops on the lowest rung, and on the highest. */
    private const val DROP_EASIEST = 10
    private const val DROP_HARDEST = 30

    /**
     * Tics between drops at [skill], which is the whole of what the ladder now changes.
     *
     * One lever rather than a table of them: supplies arrive every ten seconds at the
     * bottom and every thirty at the top, so the same fight starves the higher up it is
     * played. The creatures, their damage and the wave table are identical on every rung -
     * they are the fight, and rewriting them per rung was nine games to balance instead of
     * one. Linear across the nine, so no rung is a cliff.
     */
    fun dropInterval(skill: Int): Int {
        val top = skills.size - 1
        val s = skill.coerceIn(0, top)
        return TICRATE * (DROP_EASIEST + (DROP_HARDEST - DROP_EASIEST) * s / top)
    }

    /**
     * The ladder: g_game.c skill_t, with a rung of our own between each pair. The five odd
     * names are the engine's, the four even ones this wallpaper's.
     *
     * Names, and the drop interval that goes with the position - see [dropInterval]. The
     * combat levers that once separated the rungs are gone; what is left is how often the
     * ground gives him something, and which floor he is standing on.
     */
    val skills = listOf(
        "I'm too young to die",
        "Pity me",
        "Hey, not too rough",
        "Rough it",
        "Hurt me plenty",
        "Only killing",
        "Ultra-Violence",
        "Final undoing",
        "Nightmare!",
    )

    // ------------------------------------------------------------------ waves

    /**
     * A wave: which creatures, in what order, and how long a pause it earns.
     *
     * [order] is the arrival sequence, [rest] the pause once the wave has been cleared.
     * The spacing between arrivals is [SPAWN_DELAY] for every wave in the table.
     */
    class Wave(
        val order: IntArray,
        val rest: Int = TICRATE * 5 / 4,
    )

    /**
     * One second between arrivals, everywhere.
     *
     * It was a per-wave figure walking from two seconds down to three quarters, which is four
     * numbers on every row to express one idea. `SPAWN_MIN_DISTANCE = 280` is about a second
     * of walking, so anything shorter puts the next body on the field before the last one has
     * crossed the ground it arrived on. The pacing lives in the wave's contents now, not in
     * the gaps between them.
     */
    const val SPAWN_DELAY = TICRATE

    /**
     * info.c: `reactiontime` is 8 for every creature we use. A monster that has just
     * appeared stands still for that many tics before moving — the reference documentation describes it
     * as "newly spawned monsters initially stand idle". It reinforces the staggered
     * arrival: first it materialises in the fog, then it wakes up.
     */
    const val REACTION_TIME = 8

    /**
     * The waves.
     *
     * One axis of tension now, not four: the **total health** a wave puts on the field, rising
     * and never falling across the twenty-six. The delay is one second everywhere and the
     * pauses are equal, so what escalates is what arrives, which is the only thing anyone
     * watching can actually see.
     *
     * The shape alternates: a creature enters **alone**, in a wave of one, so it gets looked
     * at instead of lost in a crowd, and is escorted in the wave after by something below it.
     * The escort is chosen for weight rather than for rank — it is whatever keeps that wave's
     * total from overshooting the next new creature's own health, which is what lets a solo
     * arrival stay a step up rather than a dip. The Overlord closes the table by itself.
     *
     * The table is deliberately thin — 38 arrivals across the waves, where an earlier form
     * had 61. That was measured, not felt: at 61 the table needed 123 seconds of pure waiting
     * before any fighting, against a mean life of 64 seconds, so no life in 400 runs ever
     * reached the last wave. The table has to be something a life can outlast.
     */
    val waves = listOf(
        // The table names every creature in the bestiary, in health order, and each one
        // enters **alone** in a wave of its own before being escorted in the wave after.
        // Nothing in here asks whether the loaded WAD has that creature: five of them exist
        // only in a Phase 2 IWAD, and on a Phase 1 file Scene.substitute walks down to
        // something it can draw. One table, every IWAD.
        //
        // Twenty-six waves and never more than two arrivals at once, where it used to be
        // sixteen reaching three with a burst of two - six bodies on screen against two.
        // Crowding was the complaint, so the count on screen at once was capped at two. Every
        // skill runs all twenty-six waves and meets the whole bestiary.
        //
        // The escort waves arrive as a pair - burst 2 - and the solo waves do not. That is
        // one change fixing two complaints at once. With burst 1 everywhere, two enemies
        // never appeared together in the whole table, so the wallpaper had no doubles at all;
        // and the second wave, which is one creature twice, delivered the same creature in
        // two consecutive arrivals, which is what read as a repetition. Arriving together it
        // is a pair rather than a stutter.
        //
        // WaveTableTest asserts the shape rather than trusting this paragraph: no creature in
        // two consecutive arrivals, every creature alone once and escorted once, doubles
        // present, and the roster never walking back down the bestiary.
        //
        // Compressing the pacing was tried and measured: shrinking the delays and rests
        // took the mean life from 130 s to 85 s and the easiest skill from 29% to 17%. The
        // marine needs the gaps. They stayed, as one figure rather than a walk.
        //
        // The trailing column is the wave's total health, and it is the point of the table:
        // it never falls. The old table dipped every time a new creature entered alone -
        // wave 6 put 5 on the field and wave 7 put 4 - because the escort was always the
        // creature one rung down, whatever that weighed. Here the escort is picked so the
        // pair lands on exactly the health of the next creature to arrive alone, and the
        // solo wave after it steps level rather than backwards.
        //   creatures            total HP
        Wave(intArrayOf(0)), //          1
        Wave(intArrayOf(1)), //          2
        Wave(intArrayOf(0, 1)), //       3
        Wave(intArrayOf(2)), //          3
        Wave(intArrayOf(0, 2)), //       4
        Wave(intArrayOf(3)), //          4
        Wave(intArrayOf(0, 3)), //       5
        Wave(intArrayOf(4)), //          5
        Wave(intArrayOf(2, 4)), //       8
        Wave(intArrayOf(5)), //          8
        Wave(intArrayOf(1, 5)), //      10
        Wave(intArrayOf(6)), //         10
        Wave(intArrayOf(4, 6)), //      15
        Wave(intArrayOf(7)), //         15
        Wave(intArrayOf(4, 7)), //      20
        Wave(intArrayOf(8)), //         20
        Wave(intArrayOf(4, 8)), //      25
        Wave(intArrayOf(9)), //         25
        Wave(intArrayOf(4, 9)), //      30
        Wave(intArrayOf(10)), //        30
        Wave(intArrayOf(7, 10)), //     45
        Wave(intArrayOf(11)), //        45
        Wave(intArrayOf(7, 11)), //     60
        Wave(intArrayOf(12), rest = TICRATE * 2), //  60
        Wave(intArrayOf(6, 12)), //     70
        // The Overlord closes it alone. It takes 71% of the screen width - measured, not
        // guessed - so an escort would leave nowhere to look.
        Wave(intArrayOf(13), rest = TICRATE * 3), //  70
    )

    /**
     * Every sprite prefix in use, in a stable order. Each Creature/Projectile/Item stores
     * its own index here, so the draw loop needs no lookup at all.
     */
    val spritePrefixes: List<String> =
        creatures.map { it.lumpPrefix } + player.lumpPrefix +
            projectiles.map { it.lumpPrefix } + items.map { it.lumpPrefix } +
            listOf("BLUD", "TFOG")

    val bloodSpriteIndex = spritePrefixes.indexOf("BLUD")
    val fogSpriteIndex = spritePrefixes.indexOf("TFOG")

    // This must stay the last block in the file: the properties of an object initialise in
    // declaration order, and assigning the indices before spritePrefixes exists would let
    // the default values overwrite them.
    init {
        for (c in creatures) c.spriteIndex = spritePrefixes.indexOf(c.lumpPrefix)
        player.spriteIndex = spritePrefixes.indexOf(player.lumpPrefix)
        for (p in projectiles) p.spriteIndex = spritePrefixes.indexOf(p.lumpPrefix)
        for (i in items) i.spriteIndex = spritePrefixes.indexOf(i.lumpPrefix)
        creatures.forEachIndexed { n, c -> c.index = n }
        items.forEachIndexed { n, i -> i.index = n }
    }
}
