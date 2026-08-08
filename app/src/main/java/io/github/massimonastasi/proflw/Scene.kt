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

import io.github.massimonastasi.proflw.GameData.DI_NODIR
import io.github.massimonastasi.proflw.GameData.FRACUNIT
import io.github.massimonastasi.proflw.GameData.MELEERANGE
import io.github.massimonastasi.proflw.GameData.opposite
import io.github.massimonastasi.proflw.GameData.pRandom
import io.github.massimonastasi.proflw.GameData.xspeed
import io.github.massimonastasi.proflw.GameData.yspeed
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/** What an actor is doing. Mirrors the states[] groups of the engine. */
enum class Mode { WALK, ATTACK, PAIN, DEATH, PROJECTILE, EFFECT, ITEM }

/**
 * What the marine is carrying. Kept apart from [Actor] because only one actor in the scene
 * ever has it: every blood splat, fog puff and fireball is an actor too, and giving them
 * all an ammo array was an allocation per spawn for state they can never use.
 */
class Loadout {
    var armorPoints = 0
    var armorType = 0

    /**
     * One bit per weapon: the marine carries an arsenal, not a single gun, and always
     * reaches for the most powerful thing in it.
     *
     * A set rather than a "highest owned" index, because a weapon that runs dry is taken
     * away entirely. With ammunition no longer dropped on its own, an empty gun can never be
     * refilled where it stands, so keeping it would only leave a hole in the arsenal that
     * nothing could fill. The pistol is never in the set — it is the floor everything falls
     * back to, and it needs no ammunition.
     */
    var owned = 0

    val ammo = IntArray(GameData.maxAmmo.size)

    fun has(weapon: Int) = owned and (1 shl weapon) != 0

    fun take(weapon: Int) { owned = owned or (1 shl weapon) }

    fun drop(weapon: Int) { owned = owned and (1 shl weapon).inv() }

}

/**
 * An actor in the scene: creature, projectile, effect or pickup.
 *
 * Positions are in map units, 16.16 fixed-point like the original: no drift, and the
 * numbers stay comparable 1:1 with the id source.
 */
class Actor(val spriteIndex: Int) {
    var x = 0
    var y = 0
    var moveDir = DI_NODIR
    var moveCount = 0
    var spawnTic = 0

    var mode = Mode.WALK
    var health = 0
    var creature: GameData.Creature? = null
    var isPlayer = false

    /** Animation in progress for every mode other than WALK. */
    var anim: GameData.Anim? = null
    var animStep = 0
    var animTics = 0

    /** Projectiles only: momentum and damage. */
    var momX = 0
    var momY = 0
    var damage = 0
    var firedByPlayer = false

    var targetX = 0
    var targetY = 0
    var dead = false

    /**
     * How far above its own position this is drawn, in map units. Rendering only.
     *
     * The world here has no third dimension: x is across and y is depth, and an actor's
     * position is where it stands. A shot leaving the marine's chest is therefore not
     * expressible as a position at all - it is the same spot on the floor, drawn higher.
     *
     * Writing the height into y instead was tried and is wrong in a way that does not show
     * up as a wrong picture: y is depth, so it decides the drawing order, the distance to a
     * target and whether a projectile has reached one. A fireball raised that way flies at a
     * depth thirty-four units behind where it appears to be, sorts against the wrong actors,
     * and can pass through something narrow enough that the radius test misses. This field
     * keeps the simulation reading the position the actor actually occupies.
     */
    var drawHeight = 0

    /** Tics of stillness after appearing: info.c mobjinfo.reactiontime. */
    var reactionTime = 0

    /**
     * Direction the sprite is drawn facing, which is not always the direction of travel.
     * It follows movement while walking, and snaps to the target when an attack starts —
     * that is what A_FaceTarget does in the engine. Without it the marine, who backs away
     * while shooting, was drawn with his back to the demon he was firing at.
     */
    var facing = DI_NODIR

    /** Marine only: everything he is carrying. Null for every other actor. */
    var loadout: Loadout? = null

    /** Pickups lying on the ground only. */
    var item: GameData.Item? = null

    val radius get() = creature?.radius ?: 6

    /** The frame to draw right now. */
    fun frame(tic: Int): Int {
        val a = anim
        if (a != null) return a.frames[animStep]
        item?.let { return if (it.frames > 1) ((tic - spawnTic) / 6) % it.frames else 0 }
        val c = creature ?: return 0
        // Walking: the original engine repeats each frame twice (A,A,B,B,...), so it lasts walkTics*2.
        val per = c.walkTics * 2
        return ((tic - spawnTic) / per) % c.walkFrames
    }

    /**
     * Sprite rotation (1-8) as seen from a fixed camera below the scene.
     *
     * Read off the artwork rather than derived, because deriving it got the handedness
     * wrong twice. Decoding the eight rotations of the walk frame and looking at them shows
     * the convention plainly:
     *
     *   1 faces the camera, 5 faces away, 3 is a profile facing left, 7 a profile facing
     *   right, and the four in between are the diagonals.
     *
     * With DI_NORTH moving towards +y and screen y growing downwards, DI_NORTH is towards
     * the camera and must give 1, DI_WEST must give 3, DI_SOUTH 5 and DI_EAST 7. Those four
     * fix the mapping as a rotation by two eighths.
     *
     * The engine formula in r_things.c looks like a reflection instead, because the original
     * measures angles anticlockwise with +y pointing north on the map, while our screen has
     * +y pointing down. That flip of handedness reverses the direction of rotation, and
     * ignoring it produced a version with the vertical directions right and the horizontal
     * ones mirrored.
     */
    fun spriteRotation(): Int {
        if (facing == DI_NODIR) return 1
        return ((facing - 2) and 7) + 1
    }
}

/**
 * The scene: actors, movement, combat.
 *
 * The world is the original engine's x/y plane in map units. The projection onto the screen is oblique:
 * x horizontal, y into the depth. There are no walls, so the P_CheckSight line-of-sight
 * test is unnecessary — here the firing line really is always clear, it is not a shortcut.
 */
class Scene(
    private val worldWidth: Int,
    private val worldHeight: Int,
    /**
     * Skip the wait before the very first arrival.
     *
     * For the picker preview, where the wallpaper has a few seconds to make its case and
     * three of them spent watching one marine stand alone is most of that budget. Only the
     * opening wave is rushed; everything after it keeps the authored pacing, so the preview
     * is not a different game.
     */
    instantStart: Boolean = false,
    /** Which creatures the active WAD can draw. See [drawable]. */
    drawable: BooleanArray? = null,
) {

    val actors = ArrayList<Actor>()

    /** Setting: the marine never dies. Used by the balance measurements. */
    var invulnerable = false
        set(value) {
            // Latched here rather than sampled per tic: the run is tainted the moment god
            // mode is switched on, and turning it off again before the last wave must not
            // launder it. The latch is cleared only by a restart, which is a new run.
            if (value) cheated = true
            field = value
        }

    /**
     * Whether god mode has been on at any point in the run now in progress.
     *
     * A table finished without dying is only worth counting if dying was possible: with god
     * mode on, reaching the end is a matter of waiting rather than of surviving.
     */
    var cheated = false
        private set

    /**
     * Which creatures the active WAD can actually draw, by index into GameData.creatures.
     *
     * A user's IWAD need not carry all of them: the shareware release has no Trilobite or
     * PainLord, and a partial or unusual one may be missing more. Without this they were
     * still spawned and still fought — the draw loop skips a sprite it cannot resolve, so
     * they were invisible rather than absent, which is far worse than not appearing at all.
     *
     * Null when every creature is available, which is what keeps Scene testable with no WAD
     * in sight.
     */
    var drawable: BooleanArray? = drawable

    /**
     * Which pickups the active WAD can draw, by index into GameData.items.
     *
     * The same problem as the creatures, and the reason the arsenal can grow at all: the
     * super shotgun arrived with Phase 2, so a Phase 1 IWAD has no SGN2 sprite and the weapon
     * must simply never be dropped. Nothing anywhere lists which file carries what.
     */
    var drawableItems: BooleanArray? = null

    /**
     * Which creatures leave a body big enough to be drawn under everything else.
     *
     * Measured from the loaded WAD rather than named here, because it is a fact about the
     * artwork and the artwork is swappable: the Overlord rests 100 pixels tall in Freedoom
     * and 38 in Phase 2, and the Cyberdemon the other way round. See the wallpaper service,
     * which owns the sprites and therefore the measurement.
     *
     * Indexed by sprite, not by creature, because that is the index an actor already carries:
     * this is read for every body on every frame, and a lookup by identity would be a scan of
     * the bestiary each time.
     *
     * Null when nothing has been measured, which is what keeps Scene testable with no WAD.
     */
    var tallCorpses: BooleanArray? = null

    /**
     * True when this actor's body should be drawn beneath the fight rather than in it.
     *
     * Only once it has settled on its resting frame: a death animation belongs where the
     * fight is, and only what it leaves behind is furniture. That also settles the Charger
     * without naming it - it detonates and is removed rather than resting, so it never
     * qualifies, and its explosion plays where everything else can see it.
     */
    fun restsBelow(a: Actor): Boolean =
        a.mode == Mode.DEATH &&
            a.animTics == -1 &&
            tallCorpses?.getOrNull(a.spriteIndex) == true

    /** Current wave, zero-based. */
    var wave = 0
        private set

    /**
     * Current skill level, an index into [GameData.skills]. One rung per finished table.
     *
     * It moves on the kill that empties the last wave, and at no other moment - not per wave,
     * not on a death. The floor is drawn from it, so this is also the rule for the background:
     * it turns over when the ladder does, and stays put for everything else.
     *
     * One way down, and it is the top: finishing the table on the last rung ends the run, and
     * the fight that follows the curtain starts from the first again. The drop interval is
     * read from this, so it comes back with it.
     */
    var skill = 0
        private set

    /**
     * The tic the marine is due to arrive on, held empty until then.
     *
     * The preview has no time to spend on an empty floor, so it skips the wait entirely, and
     * the fight it is advertising starts on its first tic.
     */
    private var playerDueAt = if (instantStart) 0 else ARRIVAL_DELAY

    /** What the corner readout shows. Zero while the marine is down. */
    val playerHealth: Int get() = player?.takeIf { !it.dead }?.health?.coerceAtLeast(0) ?: 0
    val playerArmor: Int get() = player?.takeIf { !it.dead }?.loadout?.armorPoints ?: 0

    private var tic = 0
    private var player: Actor? = null
    private var demonCount = 0
    private var nextWaveAt = 0
    private var deadUntil = 0

    /** Arrival queue for the current wave, and how far along it we are. */
    private val queue = ArrayList<Int>()
    private var spawnIndex = 0
    private var nextSpawnAt = 0

    /**
     * The black curtain: 0 with the scene fully visible, 1 with it fully hidden.
     *
     * Four seconds closing over a death or the finished hardest table, two opening again on
     * the fresh ground. The two endings are the only things that draw it: a table finished on
     * any lower rung laps straight into the next one, with no curtain and nothing to lift.
     *
     * Uneven on purpose: the closing is the ending being watched, the opening is only
     * the way back to the fight. It is black rather than coloured because the colour is now
     * carried by the glow at the border and saying it twice made both weaker.
     */
    val coverFade: Float
        get() {
            if (uncoverAt > 0) {
                val done = tic - uncoverAt
                return if (done >= COVER_OUT_TICS) 0f else 1f - done.toFloat() / COVER_OUT_TICS
            }
            if (coverAt == 0) return 0f
            return ((tic - coverAt).toFloat() / COVER_IN_TICS).coerceIn(0f, 1f)
        }

    /** True while the red border glow belongs on screen: from the death to the restart. */
    val dying: Boolean get() = deadUntil > 0

    /** The same, for the green one: from the kill that ends the hardest table until its window runs out. */
    val winning: Boolean get() = wonUntil > 0 && tic < wonUntil

    /**
     * The glow's own breathing, 0 to 1 and back once every [PULSE_TICS].
     *
     * Counted from the tic the curtain started closing, not from the scene's own tic, so the
     * ending always opens on a dark border and the three breaths of [DEATH_DELAY] are three
     * whole ones rather than whatever the clock happened to be in the middle of.
     */
    val glowPulse: Float
        get() = (1f - cos(2.0 * PI * ((tic - coverAt) % PULSE_TICS) / PULSE_TICS)).toFloat() / 2f

    /** Tables finished at the hardest skill since this scene was built. */
    var completions = 0
        private set

    /** Whether the table now being finished was the one on the hardest rung. */
    private var clearedHardest = false

    private var wonUntil = 0
    private var coverAt = 0
    private var uncoverAt = 0
    private var restartAt = 0

    /** Drops made this run, which is what decides whether the next one is a supply or a gun. */
    private var dropsMade = 0
    private var nextDropAt = 0

    /** Tics until the ground gives him something. For the debug readout. */
    val ticsToDrop: Int get() = (nextDropAt - tic).coerceAtLeast(0)

    /** The last creature queued, so the next wave knows what it must not repeat. */
    private var lastQueued = -1

    fun tick(now: Int) {
        tic = now

        updateWaves()

        // An item every now and then: it gives the marine a reason to cross the scene and
        // makes the fighting less predictable. Over a table lasting minutes this is what
        // decides whether he survives it, far more than what he is fighting.
        //
        // Only once he is on the field, so supplies do not materialise onto empty ground with
        // nobody there to want them.
        if (player != null && deadUntil == 0 && tic >= nextDropAt) {
            nextDropAt = tic + GameData.dropInterval(skill)
            // Strictly alternating, starting on a supply: the two halves of the table take
            // turns rather than being drawn from at random, so no run can hand him four guns
            // while his health falls, and none can bury the shotgun under stimpacks.
            val supply = dropsMade++ % 2 == 0
            spawnItem(table = if (supply) GameData.supplyTable else GameData.weaponTable)
        }

        var i = 0
        while (i < actors.size) {
            val a = actors[i]
            when (a.mode) {
                Mode.ITEM -> if (!updateItem(a)) { actors.removeAt(i); continue }
                Mode.EFFECT -> if (!advanceAnim(a)) { actors.removeAt(i); continue }
                Mode.PROJECTILE -> if (!moveProjectile(a)) { actors.removeAt(i); continue }
                Mode.DEATH -> if (!advanceCorpse(a)) { actors.removeAt(i); continue }
                Mode.PAIN -> if (!advanceAnim(a)) { a.mode = Mode.WALK; a.anim = null }
                Mode.ATTACK -> advanceAttack(a)
                Mode.WALK -> chase(a, i)
            }
            i++
        }

        sortByDepth()
    }

    /**
     * The pace of the game: the marine arrives first and stays alone for a few seconds,
     * then the enemies come in one at a time. The next wave only starts once nobody is
     * left. If the marine falls, the screen goes red and everything restarts.
     */
    private fun updateWaves() {
        if (deadUntil > 0) {
            if (tic < deadUntil) return                  // no arrivals during the death fade
            restart()
            return
        }
        // The finished table gets the same held moment the death does, rather than restarting
        // on the tic it was won: the curtain needs somewhere to close before it opens again.
        if (restartAt > 0) {
            if (tic < restartAt) return
            restartAt = 0
            restart()
            return
        }

        val p = player
        if (p == null) {
            // The scene opens on empty ground for a moment before he arrives, so his
            // teleport fog is something that happens rather than something already there
            // when you looked. The wave is only armed once he is in, so the opening shifts
            // with him rather than being squeezed, and he keeps the tic he lands on.
            if (tic < playerDueAt) return
            spawnPlayer()
            startWave()
            // The first drop is a whole interval away, so the fight opens on what he arrived
            // with rather than on a gift lying at his feet.
            nextDropAt = tic + GameData.dropInterval(skill)
            return
        }
        if (p.dead) {
            deadUntil = tic + DEATH_DELAY
            coverAt = tic
            uncoverAt = 0
            return
        }

        // Staggered arrivals: until the whole wave is in, we do not judge it finished.
        if (spawnIndex < queue.size) {
            if (tic >= nextSpawnAt) {
                val c = queue[spawnIndex++]
                spawnDemon(GameData.creatures[c])
                // A repeat in the queue lands together rather than a second later. Only a
                // compensated substitution ever queues one - the table itself never repeats -
                // and delivered one at a time it would be the same creature twice in a row,
                // which is precisely what the compensation was written to avoid looking like.
                if (spawnIndex < queue.size && queue[spawnIndex] == c) {
                    spawnDemon(GameData.creatures[queue[spawnIndex++]])
                }
                nextSpawnAt = tic + GameData.SPAWN_DELAY
            }
            return
        }

        if (demonCount > 0) {
            nextWaveAt = 0
            return
        }
        // Wave cleared: the pause is the one belonging to the wave just finished. Breathing
        // room between waves matters, otherwise the rhythm turns into continuous noise.
        if (nextWaveAt == 0) {
            nextWaveAt = tic + GameData.waves[wave].rest
            // The rung, if this was the last wave: earned by the kill that emptied the ground,
            // so it is taken here rather than a few seconds later with the restart.
            if (wave == GameData.waves.lastIndex) {
                // Whether that kill was the last one there is: the table finished on the
                // hardest rung, with nothing above it to climb to. Noted before the rung
                // moves, because moving it would make every table look like that one.
                clearedHardest = skill == GameData.skills.lastIndex
                skill = (skill + 1).coerceAtMost(GameData.skills.lastIndex)
            }
            return
        }
        if (tic >= nextWaveAt) {
            nextWaveAt = 0
            wave++
            if (wave >= GameData.waves.size) {
                // The whole table cleared in one life. On any rung but the last that is a
                // lap: the waves start over one level harder and nothing is announced, the
                // way it already works when a wave ends. Only on the hardest rung is there
                // nothing left to climb to, and only then does the scene call it winning -
                // the green glow, the black curtain and the count are all that one moment.
                if (!clearedHardest) {
                    wave = 0
                    startWave()
                    return
                }
                if (!cheated) completions++
                wonUntil = tic + DEATH_DELAY
                restartAt = tic + DEATH_DELAY
                coverAt = tic
                uncoverAt = 0
                return
            }
            startWave()
        }
    }

    /**
     * After death everything restarts: first wave, and with it the lowest rung.
     *
     * g_game.c G_PlayerReborn memsets the whole player struct and hands back the pistol, so
     * death costs the armour and the arsenal alike. Nothing survives here either, which is
     * why there is no state to carry: the new marine simply gets a fresh Loadout.
     */
    private fun restart() {
        actors.clear()
        player = null
        demonCount = 0
        deadUntil = 0
        nextWaveAt = 0
        wave = 0
        // Not skill: it is climbed by finishing the table and nothing takes it back, so the
        // background survives the death that sends the waves back to the first. The one
        // exception is the top of the ladder, which has nowhere left to climb: finishing
        // the table there is the end of the run, and what follows is a new one from the
        // first rung - same floor, same drop rate, same everything the very first tic had.
        // Read from the win rather than from clearedHardest: that flag is still set through
        // the pause after the final wave, and a stray projectile landing in it is a death,
        // not a victory. Only the branch that lit the glow resets the ladder.
        if (wonUntil != 0) {
            skill = 0
            wonUntil = 0
        }
        // The black cover is at its darkest by now - a death or a finished table put it
        // there - and this is the moment it starts lifting again, on the empty ground the
        // marine is about to walk onto.
        uncoverAt = tic
        coverAt = 0
        // The drop cycle restarts on a supply, so a fresh run always opens with something
        // that keeps him alive rather than a weapon he cannot use yet.
        dropsMade = 0
        // Not false: god mode is a setting, not an event, so a run that begins with it
        // already on is tainted from its first tic without anything being switched.
        cheated = invulnerable
        // He takes the same moment to arrive after dying as he did at the start. The red
        // wash has already faded by now, so this is a beat of quiet ground, not a second
        // pause stacked on the first.
        playerDueAt = tic + ARRIVAL_DELAY
    }

    /**
     * Arms the wave: the first creature arrives at once, the rest a second apart. The queue
     * is the wave's own order, each creature swapped for one the active WAD can draw.
     */
    private fun startWave() {
        val w = GameData.waves[wave]
        queue.clear()
        for (c in w.order) {
            // What went in last, so a run of missing creatures does not collapse onto one
            // survivor. It carries across waves as well as within them, because that is where
            // it was worst: three identical solo waves two apart.
            val s = substitute(c, avoid = queue.lastOrNull() ?: lastQueued)
            repeat(compensation(c, s)) { queue.add(s) }
        }
        queue.lastOrNull()?.let { lastQueued = it }
        spawnIndex = 0
        nextSpawnAt = tic
    }

    /**
     * The nearest creature the active WAD can draw.
     *
     * Steps down the bestiary first, so a missing PainLord becomes the next thing below it
     * rather than a Zombie: the wave keeps roughly the weight it was written with. Only if
     * nothing below exists does it look upwards.
     */
    internal fun substitute(index: Int, avoid: Int = -1): Int {
        val ok = drawable ?: return index
        if (ok.getOrElse(index) { true }) return index

        // Down first, and past [avoid] if there is anywhere else to go. Without that, a file
        // missing several creatures in a row collapses them all onto the same survivor:
        // measured on a Phase 1 roster, waves 17 to 22 became six consecutive waves of
        // Trilobites, three of them identical two apart. Stepping over what just arrived
        // costs one more rung of weight and buys a table that still changes.
        for (pass in 0..1) {
            for (i in index - 1 downTo 0) {
                if (!ok.getOrElse(i) { false }) continue
                // A boss is never a substitute. It is the shape of the wave it belongs to,
                // and dropping one into an ordinary wave would make that wave the finale.
                if (GameData.creatures[i].health >= BOSS_FROM) continue
                if (pass == 0 && i == avoid) continue
                return i
            }
        }
        for (i in index + 1 until GameData.creatures.size) if (ok.getOrElse(i) { false }) return i
        return index
    }

    /**
     * How far a substitution had to fall, in places down the bestiary.
     *
     * The wave loses weight in proportion, and that is paid back in bodies rather than in
     * kind: two or more places down and the arrival is doubled. A Phase 1 file has no Bloater,
     * and the nearest thing it can draw is three places below - one Trilobite in its place is
     * a wave that has quietly stopped escalating.
     */
    private fun compensation(from: Int, to: Int): Int = if (from - to >= DEEP_SUBSTITUTION) 2 else 1

    private fun BooleanArray.getOrElse(i: Int, fallback: () -> Boolean) =
        if (i in indices) this[i] else fallback()

    // ---------------------------------------------------------------- spawning

    /**
     * The spawn margin, clamped so it can never exceed a quarter of the world.
     *
     * SPAWN_MARGIN is derived from the widest sprite and is right for a phone, where the
     * world is around 720 by 1600 units. It is not right for every surface the wallpaper can
     * be handed: split screen, a foldable cover display or a picker thumbnail can be small
     * enough that twice the margin exceeds the whole world, at which point the spawn range
     * inverts and actors are placed outside it. Measured, not imagined: a 180 by 320 world
     * put them off screen within seconds.
     */
    private val marginX = minOf(SPAWN_MARGIN, worldWidth / 4)

    /**
     * Vertical margins, which are **not** symmetric, because a sprite is anchored at its
     * feet and grows upwards from there.
     *
     * At the bottom edge the anchor can sit almost on the boundary and the whole sprite is
     * still drawn above it. At the top the sprite reaches far past the actor's own position,
     * so an actor standing near y=0 is drawn entirely off the screen — which is exactly what
     * was reported: the marine vanishing off the top. Bounding movement by the actor radius
     * alone allowed it, since the marine's radius is 16 units and his sprite reaches 108.
     *
     * See [TOP_MARGIN] and [BOTTOM_MARGIN] for where the numbers come from.
     */
    private val marginTop = minOf(TOP_MARGIN, worldHeight / 3)
    private val marginBottom = minOf(BOTTOM_MARGIN, worldHeight / 8)

    /**
     * Where an actor may *appear*, which is not the same as where it may walk.
     *
     * Movement only has to keep the sprite on screen, and at the bottom that costs very
     * little. Arriving is different: a creature that materialises right on the bottom edge
     * is technically drawn in full but reads as appearing out of the dock, so an arrival
     * keeps the full spawn margin there and walks out of it afterwards if it wants to.
     */
    private val spawnMarginBottom = minOf(SPAWN_MARGIN, worldHeight / 4)

    // The single set of bounds every actor obeys, in fixed-point. Movement, spawning,
    // wandering targets and the retreat step all used to derive these separately, in four
    // different ways, which is how the top edge came to be wrong in only one of them.
    private val minX = marginX * FRACUNIT
    private val maxX = (worldWidth - marginX) * FRACUNIT
    private val minY = marginTop * FRACUNIT
    private val maxY = (worldHeight - marginBottom) * FRACUNIT

    /**
     * P_Random returns 0-255: in the original engine it serves probabilities and small
     * offsets, never the choice of a point on a map. Using it with `%` over a range larger
     * than 256 would confine everything to one corner, so it gets scaled instead.
     */
    private fun randomIn(min: Int, max: Int): Int =
        if (max <= min) min else min + pRandom() * (max - min) / 256

    private fun spawnPlayer() {
        val a = newCreature(GameData.player)
        a.isPlayer = true
        // He always starts as the original starts him: pistol, no armour, no shells.
        a.loadout = Loadout()
        a.x = randomIn(marginX, worldWidth - marginX) * FRACUNIT
        a.y = randomIn(marginTop, worldHeight - spawnMarginBottom) * FRACUNIT
        // Longer than the creatures' reactiontime of 8 tics, which at under a quarter of a
        // second reads as no pause at all. He arrives alone and the first enemy is seconds
        // away, so he can afford to stand in the fog long enough to be noticed.
        a.reactionTime = PLAYER_REACTION
        spawnFog(a.x, a.y)
        actors.add(a)
        player = a
        newTarget(a)
    }

    /** Brings an actor into the scene at its current position: fog, list, first target. */
    private fun materialise(a: Actor) {
        spawnFog(a.x, a.y)
        actors.add(a)
        if (a.creature != null && !a.isPlayer) demonCount++
        newTarget(a)
    }

    private fun newCreature(c: GameData.Creature): Actor {
        val a = Actor(c.spriteIndex)
        a.creature = c
        // The rebalanced fixed value from the table; see GameData.creatures and docs/BALANCE.md.
        a.health = c.health
        a.spawnTic = tic
        a.reactionTime = GameData.REACTION_TIME
        return a
    }


    private fun spawnDemon(c: GameData.Creature) {
        val a = newCreature(c)
        // Anywhere in the field, provided it is not on top of the marine.
        //
        // Arrivals used to come from one of the two side edges, then from the vertical half
        // the marine was not in. Both tied the spawn to his position: the half held no real
        // distance when he sat on the midline, so the arrivals read as mirroring him. This
        // instead places a creature freely and only rejects points closer than
        // SPAWN_MIN_DISTANCE, keeping the farthest tried as a fallback so a world smaller
        // than that radius still yields its most distant point instead of looping.
        //
        // The margins still hold. A sprite is anchored at its feet and drawn upwards, so
        // spawning flush against an edge puts half the creature off screen - and a quick kill
        // could then remove it before it had ever properly been seen.
        val p = player
        val minDist = SPAWN_MIN_DISTANCE * FRACUNIT
        var bestX = 0
        var bestY = 0
        var bestD = -1
        repeat(SPAWN_TRIES) {
            val x = randomIn(marginX, worldWidth - marginX) * FRACUNIT
            val y = randomIn(marginTop, worldHeight - spawnMarginBottom) * FRACUNIT
            if (p == null) { a.x = x; a.y = y; materialise(a); return }
            val dx = abs(x - p.x)
            val dy = abs(y - p.y)
            val d = if (dx > dy) dx + (dy shr 1) else dy + (dx shr 1)
            if (d >= minDist) { a.x = x; a.y = y; materialise(a); return }
            if (d > bestD) { bestD = d; bestX = x; bestY = y }
        }
        a.x = bestX
        a.y = bestY
        materialise(a)
    }

    private fun spawnEffect(
        spriteIndex: Int,
        anim: GameData.Anim,
        x: Int,
        y: Int,
        drawHeight: Int = 0,
    ) {
        val a = Actor(spriteIndex)
        begin(a, Mode.EFFECT, anim)
        a.x = x
        a.y = y
        a.drawHeight = drawHeight
        a.spawnTic = tic
        actors.add(a)
    }

    /** Teleport fog is on the ground, because that is where somebody arrives. */
    private fun spawnFog(x: Int, y: Int) = spawnEffect(GameData.fogSpriteIndex, GameData.fogAnim, x, y)

    /** Blood is where the shot landed, which is the height the shot was fired at. */
    private fun spawnBlood(x: Int, y: Int) =
        spawnEffect(GameData.bloodSpriteIndex, GameData.bloodAnim, x, y, MUZZLE_HEIGHT)

    /** Drops a pickup somewhere on the map, or at a chosen spot. */
    private fun spawnItem(
        x: Int = randomIn(marginX, worldWidth - marginX) * FRACUNIT,
        y: Int = randomIn(marginTop, worldHeight - spawnMarginBottom) * FRACUNIT,
        /** Which half of the pickups to draw from. The whole table for a hand-placed drop. */
        table: IntArray = GameData.dropTable,
    ) {
        if (table.isEmpty()) return
        // Redrawn until the weighted pick lands on something droppable. Bounded by the
        // table's own size rather than looping forever: with every weapon missing, the
        // health and armour entries still make up most of the table, so a handful of tries
        // always finds one. Redrawn from the same table, so a rerolled supply stays a
        // supply and the alternation the caller set up survives.
        var choice = table[pRandom() % table.size]
        var tries = 0
        while (!droppable(choice) && tries < table.size) {
            choice = table[pRandom() % table.size]
            tries++
        }
        if (!droppable(choice)) return
        val it = GameData.items[choice]
        val a = Actor(it.spriteIndex)
        a.mode = Mode.ITEM
        a.item = it
        a.x = clampX(x)
        a.y = clampY(y)
        a.spawnTic = tic
        actors.add(a)
    }

    /**
     * Whether a pickup may drop: the WAD can draw it, and the floor is not already carrying
     * [MAX_SAME_ON_FLOOR] of it.
     *
     * The cap is on what is lying there at this moment, not on what the run has produced. As
     * soon as one is collected or times out the type is free again, so this thins out the
     * clutter of five identical armours without ever making a type unreachable.
     */
    private fun droppable(choice: Int): Boolean {
        if (drawableItems?.getOrElse(choice) { true } == false) return false
        val item = GameData.items[choice]
        var n = 0
        for (a in actors) if (a.mode == Mode.ITEM && a.item === item) n++
        return n < MAX_SAME_ON_FLOOR
    }

    private fun clampX(x: Int) = x.coerceIn(minX, maxX)

    private fun clampY(y: Int) = y.coerceIn(minY, maxY)

    // ---------------------------------------------------------------- interaction

    /**
     * The user tapped the home screen: drop a pickup where they touched.
     *
     * Ignored while the marine is dead, so the death stays a pause rather than something
     * the user can litter with items nobody will collect.
     */
    fun tapAt(x: Int, y: Int) {
        if (deadUntil > 0) return
        spawnItem(clampX(x), clampY(y))
    }

    /**
     * An icon was dropped on the home screen: send demons to that spot.
     *
     * They arrive outside the wave sequence, so this never disturbs the wave pacing: the
     * current wave still has to be cleared before the next begins, these are simply extra.
     */
    fun dropAt(x: Int, y: Int) {
        if (deadUntil > 0) return
        val count = 1 + pRandom() % 2
        repeat(count) {
            val c = GameData.creatures[pRandom() % 3]        // only the lighter creatures
            val a = newCreature(c)
            a.x = clampX(x + (pRandom() - 128) * FRACUNIT / 2)
            a.y = clampY(y + (pRandom() - 128) * FRACUNIT / 2)
            materialise(a)
        }
    }

    /** false once the pickup has been taken or has sat on the ground for too long. */
    private fun updateItem(a: Actor): Boolean {
        val p = player ?: return true
        if (!p.dead && approxDistance(a, p) < (p.radius + 24) * FRACUNIT) {
            if (pickUp(p, a.item ?: return false)) return false
        }
        return tic - a.spawnTic < ITEM_LIFETIME
    }

    /** Everything below only ever runs for the marine, the one actor with a loadout. */

    /**
     * Pickup, following the p_inter.c rules: health never exceeds 100, armour is refused
     * when the one already worn is better, and a weapon carries two clip loads.
     * Returns false when the item is not needed and should stay on the ground.
     */
    /** Test hook: pickUp is the rule set worth pinning, and it is otherwise unreachable. */
    internal fun pickUpForTest(p: Actor, item: GameData.Item) = pickUp(p, item)

    private fun pickUp(p: Actor, it: GameData.Item): Boolean {
        val kit = p.loadout ?: return false
        return when (it.kind) {
            GameData.ITEM_HEALTH -> {
                if (p.health >= GameData.player.health) false
                else { p.health = minOf(GameData.player.health, p.health + it.amount); true }
            }
            GameData.ITEM_ARMOR -> {
                if (kit.armorPoints >= it.amount) false
                else { kit.armorPoints = it.amount; kit.armorType = it.extra; true }
            }
            else -> {
                // One bit per weapon, so a second shotgun never becomes a second shotgun: it
                // replaces the one carried and reloads it, which is the only source of
                // ammunition left now that none is dropped on its own.
                //
                // Taken only if it actually gives something. It used to be taken always, so
                // a marine already holding the weapon with full ammunition walked over it and
                // it vanished for nothing.
                val reloaded = giveAmmo(kit, GameData.weapons[it.extra].ammo, it.amount)
                val isNew = !kit.has(it.extra)
                if (reloaded || isNew) { kit.take(it.extra); true } else false
            }
        }
    }

    private fun giveAmmo(kit: Loadout, type: Int, clips: Int): Boolean {
        if (type < 0) return false
        if (kit.ammo[type] >= GameData.maxAmmo[type]) return false
        val given = clips * GameData.clipAmmo[type]
        kit.ammo[type] = minOf(GameData.maxAmmo[type], kit.ammo[type] + given)
        return true
    }

    /**
     * The weapon in hand: the most powerful one carried that still has ammunition,
     * otherwise the pistol he started with.
     */
    private fun currentWeaponIndex(p: Actor): Int {
        val kit = p.loadout ?: return GameData.WEAPON_PISTOL
        // By damage per second, not by position in the list. The original's slot order
        // puts the rocket launcher near the end and it is the weakest thing in this scene,
        // because splash is not modelled; ranking by position had the marine reach for it
        // over a super shotgun. See Weapon.damagePerSecond.
        var best = GameData.WEAPON_PISTOL
        var bestRate = GameData.weapons[GameData.WEAPON_PISTOL].damagePerSecond
        for (i in GameData.weapons.indices) {
            if (i == GameData.WEAPON_PISTOL || !kit.has(i)) continue
            val w = GameData.weapons[i]
            if (w.ammo >= 0 && kit.ammo[w.ammo] <= 0) continue
            if (w.damagePerSecond > bestRate) { bestRate = w.damagePerSecond; best = i }
        }
        return best
    }


    // ---------------------------------------------------------------- animation

    /**
     * Starts an animation.
     *
     * One place, so no caller can set three of the four fields and forget the fourth. That
     * is not hypothetical: leaving the pain state without clearing `anim` left the index
     * pointing past the end of a finished sequence, and crashed the renderer.
     */
    private fun begin(a: Actor, mode: Mode, anim: GameData.Anim) {
        a.mode = mode
        a.anim = anim
        a.animStep = 0
        a.animTics = anim.tics[0]
    }

    /**
     * Advances the animation. false once it has finished.
     *
     * Invariant: animStep always stays a valid index, even when the sequence is exhausted.
     * Letting it run past the last frame used to crash the renderer whenever a caller
     * forgot to clear anim.
     */
    private fun advanceAnim(a: Actor): Boolean {
        val anim = a.anim ?: return false
        if (--a.animTics > 0) return true
        if (a.animStep + 1 >= anim.length) return false
        a.animStep++
        a.animTics = anim.tics[a.animStep]
        return true
    }

    /**
     * Death: the last frame has a tic value of -1, meaning it stays forever — in the engine
     * corpses never disappear. In a wallpaper they would pile up, so they fade after a while.
     */
    private fun advanceCorpse(a: Actor): Boolean {
        val anim = a.anim ?: return false
        if (a.animTics == -1) return tic - a.spawnTic < CORPSE_LIFETIME
        if (--a.animTics > 0) return true
        a.animStep++
        if (a.animStep >= anim.length) return false
        a.animTics = anim.tics[a.animStep]
        if (a.animTics == -1) a.spawnTic = tic          // the corpse countdown starts here
        return true
    }

    /** The attack lands on the last frame, as in the engine (the action sits in S_*_ATK3). */
    private fun advanceAttack(a: Actor) {
        val anim = a.anim ?: return
        val last = a.animStep == anim.length - 1
        if (!advanceAnim(a)) {
            a.mode = Mode.WALK
            a.anim = null
            return
        }
        if (!last && a.animStep == anim.length - 1) fireAttack(a)
    }

    // ---------------------------------------------------------------- combat

    private fun enemyOf(a: Actor): Actor? =
        if (a.isPlayer) nearestDemon(a) else player?.takeIf { !it.dead }

    private fun nearestItem(from: Actor): Actor? {
        var best: Actor? = null
        var bestDist = Int.MAX_VALUE
        for (o in actors) {
            if (o.mode != Mode.ITEM) continue
            val d = approxDistance(from, o)
            if (d < bestDist) { bestDist = d; best = o }
        }
        return best
    }

    private fun nearestDemon(from: Actor): Actor? {
        var best: Actor? = null
        var bestDist = Int.MAX_VALUE
        for (o in actors) {
            if (o.creature == null || o.isPlayer || o.dead) continue
            val d = approxDistance(from, o)
            if (d < bestDist) { bestDist = d; best = o }
        }
        return best
    }

    /** P_AproxDistance (m_fixed.c): dx + dy/2 when dx > dy. No square root. */
    private fun approxDistance(a: Actor, b: Actor): Int {
        val dx = abs(a.x - b.x)
        val dy = abs(a.y - b.y)
        return if (dx > dy) dx + (dy shr 1) else dy + (dx shr 1)
    }

    /**
     * P_CheckMissileRange (p_enemy.c): the chance of firing falls with distance.
     * One line produces a varied combat rhythm with no timers or extra state.
     */
    private fun checkMissileRange(a: Actor, target: Actor): Boolean {
        var dist = approxDistance(a, target) - 64 * FRACUNIT
        val c = a.creature ?: return false
        if (!c.melee) dist -= 128 * FRACUNIT
        dist = dist shr 16
        if (dist < 0) dist = 0
        if (dist > 200) dist = 200
        return pRandom() >= dist
    }

    private fun startAttack(a: Actor) {
        val c = a.creature ?: return
        // The marine uses the rate of fire of the weapon in hand: the chaingun is far
        // faster than the shotgun.
        begin(a, Mode.ATTACK, if (a.isPlayer) GameData.weapons[currentWeaponIndex(a)].attack else c.attack)
    }

    private fun fireAttack(a: Actor) {
        val c = a.creature ?: return
        val target = enemyOf(a) ?: return

        // Melee when the target is in reach: P_CheckMeleeRange uses MELEERANGE.
        if (c.melee && approxDistance(a, target) < MELEERANGE + target.radius * FRACUNIT) {
            damageActor(target, c.damage)
            return
        }
        // The marine fires whatever he is holding, which may be hitscan or a missile; a
        // monster fires whatever its own entry says. Splitting on the actor rather than on
        // the creature is what lets the arsenal grow without touching the bestiary.
        if (a.isPlayer) {
            val i = currentWeaponIndex(a)
            val w = GameData.weapons[i]
            val kit = a.loadout
            if (w.ammo >= 0 && kit != null) {
                // Firing the last round costs him the gun: it drops out of the arsenal and
                // he falls back to whatever he still has that is loaded.
                if (--kit.ammo[w.ammo] <= 0) kit.drop(i)
            }
            if (w.projectile >= 0) {
                spawnMissile(a, target, GameData.projectiles[w.projectile], w.damage)
            } else {
                // Fixed damage per trigger pull, no roll; see GameData.weapons.
                damageActor(target, w.damage)
            }
            return
        }

        if (c.hitscan) {
            // Instant shot: no projectile to simulate, damage applied directly.
            damageActor(target, c.damage)
            return
        }
        if (c.projectile >= 0) {
            spawnMissile(a, target, GameData.projectiles[c.projectile], c.damage)
        }
    }

    /** P_SpawnMissile: constant speed along the direction of the target. */
    private fun spawnMissile(from: Actor, target: Actor, p: GameData.Projectile, damage: Int) {
        val m = Actor(p.spriteIndex)
        m.mode = Mode.PROJECTILE
        m.anim = GameData.ballAnim
        m.animTics = GameData.ballAnim.tics[0]
        m.x = from.x
        m.y = from.y
        // Leaves the chest and stays there for the whole flight: the height is drawn, not
        // travelled, so the trajectory and everything it collides with are unchanged.
        m.drawHeight = MUZZLE_HEIGHT
        m.spawnTic = tic
        m.damage = damage
        m.firedByPlayer = from.isPlayer

        val dx = target.x - from.x
        val dy = target.y - from.y
        val dist = approxDistance(from, target).coerceAtLeast(1)
        // Momentum is in fixed-point: p.speed is units per tic, as in mobjinfo where the
        // missiles have speed 10*FRACUNIT (monsters instead carry a plain integer).
        val v = (p.speed * FRACUNIT).toLong()
        m.momX = (v * dx / dist).toInt()
        m.momY = (v * dy / dist).toInt()
        actors.add(m)
    }

    /** false once the projectile is done (off the field or exploded). */
    private fun moveProjectile(a: Actor): Boolean {
        if (a.anim != null && !advanceAnim(a)) {
            // The two fireball images are a loop, not a sequence.
            a.animStep = 0
            a.animTics = GameData.ballAnim.tics[0]
        }
        a.x += a.momX
        a.y += a.momY
        if (a.x < 0 || a.y < 0 || a.x > worldWidth * FRACUNIT || a.y > worldHeight * FRACUNIT) return false
        // Safety net: a projectile fired at zero distance would carry no momentum and stay
        // in the scene forever. In the engine a wall would stop it; here there are none.
        if (tic - a.spawnTic > TICRATE * 5) return false

        for (o in actors) {
            if (o.creature == null || o.dead) continue
            if (o.isPlayer == a.firedByPlayer) continue          // no friendly fire
            if (approxDistance(a, o) < o.radius * FRACUNIT) {
                damageActor(o, a.damage)
                return false
            }
        }
        return true
    }

    private fun damageActor(target: Actor, amount: Int) {
        if (target.dead) return
        if (target.isPlayer && invulnerable) return
        val c = target.creature ?: return

        // p_inter.c: armour absorbs a third of the damage when green, half when blue, and
        // is consumed by the same amount. Once it runs out, the type is cleared too. The
        // marine takes the hit in full: no skill scaling sits between the monster and him.
        var amount = amount
        val kit = target.loadout
        if (kit != null && kit.armorType > 0) {
            var saved = GameData.armorSaved(amount, kit.armorType)
            if (kit.armorPoints <= saved) {
                saved = kit.armorPoints
                kit.armorType = 0
            }
            kit.armorPoints -= saved
            amount -= saved
        }

        target.health -= amount
        spawnBlood(target.x, target.y)

        if (target.health <= 0) {
            target.dead = true
            if (!target.isPlayer) demonCount--
            begin(target, Mode.DEATH, c.death)
            target.spawnTic = tic
            return
        }
        // painchance: the odds of being interrupted by a hit.
        if (target.mode != Mode.ATTACK && pRandom() < c.painChance) {
            begin(target, Mode.PAIN, c.pain)
        }
    }

    // ---------------------------------------------------------------- movement

    /**
     * A_Chase (p_enemy.c): decide whether to attack, otherwise step forward and recompute
     * the direction whenever movecount runs out or the step fails.
     */
    private fun chase(a: Actor, index: Int) {
        val c = a.creature ?: return

        // A monster that has just appeared stands still: it materialises in the fog and
        // then wakes up, instead of bursting into a run out of nowhere.
        if (a.reactionTime > 0) {
            a.reactionTime--
            return
        }

        val target = enemyOf(a)
        val dist = if (target != null) approxDistance(a, target) else Int.MAX_VALUE

        // Below half health the marine breaks off and goes for supplies rather than trading
        // shots: staying in the fight while hurt is how he dies, and there is usually
        // something on the ground worth reaching.
        val hurt = a.isPlayer && a.health * 2 < GameData.player.health
        val supply = if (a.isPlayer && (hurt || dist > KEEP_AWAY)) nearestItem(a) else null
        val breakingOff = hurt && supply != null

        if (target != null) {
            if (a.isPlayer && dist < KEEP_AWAY) {
                // The marine does not walk into the demons: he backs off and shoots from a
                // distance. Charging them, he died every twenty seconds and never got past
                // the fifth wave.
                a.targetX = clampX(2 * a.x - target.x)
                a.targetY = clampY(2 * a.y - target.y)
            } else {
                a.targetX = target.x
                a.targetY = target.y
            }
            val inMelee = dist < MELEERANGE + target.radius * FRACUNIT
            val attacks = !breakingOff && (
                (c.melee && inMelee) ||
                    ((c.hitscan || c.projectile >= 0) && checkMissileRange(a, target))
                )
            if (attacks) {
                a.facing = dirTo(a, target)          // A_FaceTarget: turn to shoot
                startAttack(a)
                return
            }
        } else if (actors.size > 0 && tic % actors.size == index && reachedTarget(a)) {
            // Work amortisation taken from P_LookForPlayers, which checks only 2 players per
            // call by cycling on lastlook: the expensive work is spread across several tics.
            newTarget(a)
        }

        // Heading for a pickup overrides whatever movement target was chosen above.
        if (supply != null) {
            a.targetX = supply.x
            a.targetY = supply.y
        }

        // P_TryWalk rearms movecount with P_Random()&15, so the pathfinding runs on average
        // once every 8 tics rather than 35 times a second.
        a.moveCount--
        if (a.moveCount < 0 || !move(a)) newChaseDir(a)
    }

    private fun reachedTarget(a: Actor): Boolean =
        abs(a.targetX - a.x) < 32 * FRACUNIT && abs(a.targetY - a.y) < 32 * FRACUNIT

    private fun newTarget(a: Actor) {
        a.targetX = randomIn(marginX, worldWidth - marginX) * FRACUNIT
        a.targetY = randomIn(marginTop, worldHeight - marginBottom) * FRACUNIT
    }

    /** P_Move: a step in the current direction via the xspeed/yspeed tables, all integer. */
    private fun move(a: Actor): Boolean {
        val d = a.moveDir
        if (d >= 8) return false
        if (a.creature == null) return false
        // One speed for everyone, rather than Creature.speed. The bestiary is ordered by
        // health and info.c's speeds rise alongside it - 8 for the Zombie, 16 for the
        // Cyberlord - so reading the field made the fight get quicker the further it went,
        // which is what was reported. The field stays: it is the engine's number and carries
        // the attribution, it is simply no longer what moves anything.
        val tryX = a.x + MOVE_SPEED * xspeed[d]
        val tryY = a.y + MOVE_SPEED * yspeed[d]
        if (tryX < minX || tryX > maxX) return false
        if (tryY < minY || tryY > maxY) return false
        a.x = tryX
        a.y = tryY
        a.facing = d                 // walking: the sprite looks where it is going
        return true
    }

    /**
     * The direction from one actor to another, used as A_FaceTarget when an attack starts.
     *
     * Cardinal only, like movement. The engine resolves eight octants here via
     * R_PointToAngle, but returning a diagonal would put the diagonal sprite views back on
     * screen through the back door: an actor can be walking north and turn north-east to
     * shoot. Snapping to the dominant axis costs a little aiming precision that nothing in
     * the scene depends on, since damage is applied directly rather than along the facing.
     */
    private fun dirTo(from: Actor, to: Actor): Int {
        val dx = to.x - from.x
        val dy = to.y - from.y
        return if (abs(dx) >= abs(dy)) {
            if (dx > 0) 0 else 4                            // DI_EAST / DI_WEST
        } else {
            if (dy > 0) 2 else 6                            // DI_NORTH / DI_SOUTH
        }
    }

    /** P_TryWalk: when the step succeeds, rearm movecount with a random pause. */
    private fun tryWalk(a: Actor): Boolean {
        if (!move(a)) return false
        a.moveCount = pRandom() and 15
        return true
    }

    /**
     * P_NewChaseDir (p_enemy.c), in the original order: diagonal towards the target, then
     * the dominant axis, then the previous direction, then all 8 in random order.
     * **It never turns around** while an alternative exists: that is why the original monsters feel
     * alive instead of remote-controlled.
     */
    private fun newChaseDir(a: Actor) {
        val oldDir = a.moveDir
        val turnAround = opposite[oldDir]

        val deltaX = a.targetX - a.x
        val deltaY = a.targetY - a.y

        var d1 = when {
            deltaX > 10 * FRACUNIT -> 0                     // DI_EAST
            deltaX < -10 * FRACUNIT -> 4                    // DI_WEST
            else -> DI_NODIR
        }
        var d2 = when {
            deltaY < -10 * FRACUNIT -> 6                    // DI_SOUTH
            deltaY > 10 * FRACUNIT -> 2                     // DI_NORTH
            else -> DI_NODIR
        }

        // The engine tries the diagonal first here. Nobody in this scene moves diagonally at
        // all: everything reads more clearly along the axes, and it halves the sprite angles
        // in play, so only rotations 1, 3, 5 and 7 are ever decoded.
        if (pRandom() > 200 || abs(deltaY) > abs(deltaX)) {
            val t = d1; d1 = d2; d2 = t
        }
        if (d1 == turnAround) d1 = DI_NODIR
        if (d2 == turnAround) d2 = DI_NODIR

        if (d1 != DI_NODIR) { a.moveDir = d1; if (tryWalk(a)) return }
        if (d2 != DI_NODIR) { a.moveDir = d2; if (tryWalk(a)) return }
        if (oldDir != DI_NODIR) { a.moveDir = oldDir; if (tryWalk(a)) return }

        // Exhaustive fallback, still cardinals only. The random direction of travel through
        // the list is the engine's, and keeps a blocked actor from always picking the same
        // way out.
        if (pRandom() and 1 != 0) {
            for (d in CARDINALS) { if (d == turnAround) continue; a.moveDir = d; if (tryWalk(a)) return }
        } else {
            for (i in CARDINALS.indices.reversed()) {
                val d = CARDINALS[i]
                if (d == turnAround) continue
                a.moveDir = d
                if (tryWalk(a)) return
            }
        }

        if (turnAround != DI_NODIR) { a.moveDir = turnAround; if (tryWalk(a)) return }
        a.moveDir = DI_NODIR
    }

    /**
     * Sorts by depth (increasing y = further away). Insertion sort: the list is almost
     * always sorted already and the actors are few, so it costs O(n) and allocates nothing.
     */
    private fun sortByDepth() {
        for (i in 1 until actors.size) {
            val a = actors[i]
            var j = i - 1
            while (j >= 0 && actors[j].y > a.y) {
                actors[j + 1] = actors[j]
                j--
            }
            actors[j + 1] = a
        }
    }

    // internal rather than private so the tests can assert against these tuning values
    // instead of restating them, which would let the two drift apart silently.
    internal companion object {
        /**
         * How long a body stays before it is removed.
         *
         * ponytail: corpses in the original stay forever; in a wallpaper they would pile up.
         * Raised from twelve seconds to thirty, which the thinned wave table pays for: at 61
         * arrivals the bodies were the thing keeping the actor list long, and at 30 they are
         * what keeps the ground from looking empty between waves.
         */
        const val CORPSE_LIFETIME = TICRATE * 30

        /**
         * Map units per tic, for everything that walks.
         *
         * info.c MT_TROOP: the Zombie's own speed, and the one the earliest waves always
         * moved at. Every creature now moves at it, so the fight does not accelerate as the
         * table goes on.
         */
        const val MOVE_SPEED = 8

        /**
         * How long the black curtain takes to close over the ending. Four seconds.
         *
         * It has to stay inside [DEATH_DELAY], which is the pause it punctuates.
         */
        const val COVER_IN_TICS = TICRATE * 4

        /** And to open again on the fresh ground: two seconds, half the closing. */
        const val COVER_OUT_TICS = TICRATE * 2

        /** One breath of the border glow: three of them fill a [DEATH_DELAY]. */
        const val PULSE_TICS = TICRATE * 3

        /**
         * How tall a resting body has to be, against the standing marine of the same WAD, to
         * be drawn beneath the fight instead of in it.
         *
         * One means "as tall lying down as the marine is standing up", which is a strange
         * thing for a corpse to be and exactly what makes these worth moving. Measured on the
         * two files: it takes the Overlord (1.79) and the Cyberlord (1.43) out of Freedoom,
         * the Cyberdemon (2.39) out of Phase 2, and the Charger from both - its detonation
         * measures 1.14 and 1.61, and it is the widest thing on the screen.
         *
         * Big bodies used to be handled by removing them sooner, ten seconds against thirty.
         * That traded one problem for another: they still covered what they landed on, and
         * they vanished while the fight around them was still going. Everything lasts thirty
         * seconds again.
         */
        const val TALL_CORPSE = 1.0

        /**
         * How long the death - or the finished table - holds the screen before the restart.
         *
         * Nine seconds: three breaths of the border glow at [PULSE_TICS] each, with the four
         * second black fade closing inside them. Four and a half was half of that and read as
         * a stumble rather than as an ending.
         */
        const val DEATH_DELAY = TICRATE * 9

        /** Below this distance the marine backs off instead of closing in. */
        const val KEEP_AWAY = 220 * FRACUNIT

        /**
         * How far inside the edge a creature appears, in map units.
         *
         * It has to come from the sprite, not the collision radius: the widest creature has
         * a radius of 31 but an 83-pixel sprite anchored 50 pixels from its left edge, and
         * sprites are drawn at twice the world scale. Fifty anchor pixels are therefore a
         * hundred map units of screen space, which is what this has to clear.
         */
        const val SPAWN_MARGIN = 100

        /**
         * How far below the top edge an actor may stand, in map units.
         *
         * A sprite hangs above its anchor, so this is what keeps a whole creature on screen
         * at the top; the sideways margin above cannot serve, since the vertical reach is
         * larger and the two edges are not symmetric.
         *
         * Measured on the shipped assets rather than estimated. Reading the patch headers,
         * the tallest reach above the anchor is the PainLord at 74 pixels, against 61 for
         * the ShotgunZombie and 54 for the marine. A sprite pixel is two map units, because
         * the renderer draws sprites at SPRITE_SCALE and the world at PX_PER_UNIT, a ratio
         * of 3 to 1.5 that holds at every display density. Seventy-four pixels are therefore
         * 148 map units.
         *
         * If SPRITE_SCALE or PX_PER_UNIT ever change, this changes with them.
         */
        const val TOP_MARGIN = 148

        /**
         * How far above the bottom edge an actor may stand, in map units.
         *
         * Small on purpose: the sprite is drawn upwards from the anchor, so at the bottom
         * only the few pixels that hang below the feet can be clipped. Measured the same
         * way, the worst is the Trilobite at 8 pixels, so 16 map units.
         */
        const val BOTTOM_MARGIN = 16

        /**
         * How far from the marine a creature must arrive, in map units.
         *
         * A base creature covers 8 units a tic, so 280 is about one second of walking at
         * TICRATE: far enough that an arrival reads as coming from elsewhere and never
         * materialises on top of him. This is the only reason the rule exists; it replaced a
         * branch that put every creature in the vertical half the marine was not in, which
         * held no real distance when he sat on the midline and read as the spawns mirroring
         * his position.
         *
         * ponytail: 280 is the starting value, tuned on the JVM; the device decides the
         * final one, measured against how long a creature then takes to reach him.
         */
        const val SPAWN_MIN_DISTANCE = 280

        /**
         * How many random points to try before settling for the farthest one seen.
         *
         * The field is large next to SPAWN_MIN_DISTANCE, so the first or second point almost
         * always clears it. The cap matters only for a world small enough that the whole of
         * it sits inside that radius — a split screen, a foldable cover, a picker thumbnail —
         * where it degrades to "as far from him as this surface allows" instead of looping.
         */
        const val SPAWN_TRIES = 8

        /**
         * How far above an actor's feet a shot leaves it, and where blood appears when one
         * lands. In map units.
         *
         * An actor's position is its anchor, which is where it stands: sprites are drawn
         * upwards from there. Firing from that point put every fireball on the floor. This is
         * roughly the chest of the marine, whose sprite stands about 100 units tall at the
         * scale the scene draws - the creatures vary, but they all fire from the same third
         * of their height, so one number reads correctly for all of them.
         */
        const val MUZZLE_HEIGHT = 34

        /** Spawn health at or above which a creature is a boss and never a stand-in. */
        const val BOSS_FROM = 45

        /** How far a substitution has to fall before the arrival is doubled to make up for it. */
        const val DEEP_SUBSTITUTION = 2

        /** How long the marine stands still after materialising. */
        const val PLAYER_REACTION = TICRATE / 2

        /**
         * Empty ground before the marine arrives, at the start and after every death.
         *
         * Two seconds. Everything downstream shifts with it rather than being squeezed: the
         * wave is armed when he lands, so the opening keeps its shape wherever it starts.
         */
        const val ARRIVAL_DELAY = TICRATE * 2

        /** The only directions anything moves in: east, north, west, south. */
        val CARDINALS = intArrayOf(0, 2, 4, 6)

        /** How long an item stays if nobody picks it up. How often one drops is per skill. */
        const val ITEM_LIFETIME = TICRATE * 40

        /** How many of one pickup may lie on the floor at once before drops of it are rerolled. */
        const val MAX_SAME_ON_FLOOR = 2
    }
}
