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

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowInsets
import android.view.WindowManager
import java.nio.channels.FileChannel
import kotlin.math.abs

/** The original engine runs at 35 tics per second. All game logic advances at that rate, always. */
const val TICRATE = 35


private const val TAG = "ProfLW"

class ProfWallpaperService : WallpaperService() {

    /** One SpriteSet per prefix, indexed like GameData.spritePrefixes. */
    private var sprites: List<SpriteSet> = emptyList()
    /** Colour of the death wash, straight from the active WAD's palette. */
    private var deathTint = 0xFFB30000.toInt()

    /** The winning wash: the palette's own green, the one the armour readout uses. */
    private var winTint = 0xFF77FF6F.toInt()

    /** Floor texture per skill level, tiled behind the scene. Null entries when absent. */
    private var floorTiles: Array<Bitmap?> = arrayOfNulls(GameData.skills.size)

    /** Status bar numerals from the WAD. */
    private var digits: Array<Bitmap>? = null

    /**
     * Readout colours, replaced by the active WAD's palette once it loads.
     *
     * Health is the blue and armour the green, matching PALETTE_HEALTH and PALETTE_ARMOR.
     * These two defaults used to hold the opposite pair, which no drawn frame could reveal
     * because the readout is skipped entirely when the numerals fail to load â€” wrong in a
     * place that never shows is still wrong, and it would have surfaced the moment anything
     * else started using them.
     */
    private var healthColor = 0xFF7373FF.toInt()
    private var armorColor = 0xFF77FF6F.toInt()

    /** Which WAD the loaded sprites came from, so a change can be noticed cheaply. */
    private var loadedWad: String? = null

    /**
     * The active WAD's PLAYPAL, kept so a flat backdrop colour can be named by palette index.
     *
     * An index rather than an ARGB value means the choice follows the WAD: pick "red" and a
     * commercial IWAD gives its own red, not Freedoom's.
     */
    private var palette = IntArray(256)

    fun paletteColour(index: Int): Int = palette[index.coerceIn(0, 255)]

    /**
     * The imported WAD, but only while it is the one selected.
     *
     * Importing keeps a file; a separate choice says whether it is drawn. That is what lets
     * the bundled assets stay an option the user can return to without importing again.
     */
    private fun activeWad() =
        if (Settings.useUserWad(Settings.of(this))) WadStore.active(this) else null

    override fun onCreate() {
        super.onCreate()
        // Before loadWad, which reads a file the user chose and is the most likely thing here
        // to throw on a device nobody tested it on.
        CrashLog.install(this)
        loadWad()
    }

    /**
     * Loads the user's IWAD when there is one, and the bundled assets otherwise.
     *
     * Everything the scene draws comes from the same file, so swapping it swaps the whole
     * look at once â€” sprites, palette, floors and the readout numerals. That is why nothing
     * here has a per-IWAD compatibility table: the file declares what it contains and the
     * loader takes what it finds.
     */
    private fun loadWad() {
        val user = activeWad()
        val source = user?.absolutePath ?: BUNDLED
        try {
            val buf = if (user != null) {
                user.inputStream().use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, user.length()) }
            } else {
                // Never copied: mapped straight out of the assets, which build.gradle.kts
                // excludes from compression so it is readable in place.
                val afd = assets.openFd(BUNDLED)
                afd.createInputStream().use { stream ->
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
                }
            }
            val w = WadFile(buf)
            palette = IntArray(256) { w.paletteColor(it) }
            deathTint = w.paletteColor(GameData.PALETTE_DEATH)
            winTint = w.paletteColor(GameData.PALETTE_ARMOR)
            floorTiles = loadFloors(w)
            loadDigits(w)
            sprites = GameData.spritePrefixes.map { SpriteSet(w, it) }
            val missing = GameData.spritePrefixes.filterIndexed { i, _ -> sprites[i].frameCount == 0 }
            loadedWad = source
            Log.i(TAG, "WAD loaded from $source: ${w.lumpCount} lumps, " +
                "${sprites.size - missing.size}/${sprites.size} sprites" +
                if (missing.isEmpty()) "" else " (missing: $missing)")
        } catch (e: Exception) {
            // Without a WAD the wallpaper stays alive and shows the placeholder rather
            // than disappearing. A user file that fails here was already checked on import,
            // so this is the case where it has since been corrupted or removed.
            Log.e(TAG, "WAD not loaded from $source", e)
            if (user != null) {
                WadStore.clear(this)
                loadedWad = null
                loadWad()
            }
        }
    }

    /**
     * Which creatures the loaded WAD has sprites for.
     *
     * Driven entirely by what the file turned out to contain, which is the reason this
     * application names no IWAD anywhere: there is no list of known files and no per-file
     * special case, only sprite prefixes that either resolved or did not.
     */
    private fun drawableCreatures() = BooleanArray(GameData.creatures.size) { i ->
        val set = sprites.getOrNull(GameData.creatures[i].spriteIndex)
        set != null && set.frameCount > 0
    }

    /**
     * Which creatures rest tall enough to be drawn under the fight, by sprite index.
     *
     * The yardstick is the standing marine of the same file, so the rule carries to a WAD
     * nobody has seen: the same creature is not the same size twice. Measured across the two
     * files here, the Overlord rests 100 pixels tall in Freedoom and 38 in Phase 2, and the
     * Cyberdemon 80 and 134 - opposite ways round.
     *
     * The frame measured is the one the death animation ends on, which is the body that
     * stays. Read from the lump header, so nothing is decoded to answer this.
     */
    private fun tallCorpses(): BooleanArray {
        val out = BooleanArray(GameData.spritePrefixes.size)
        val marine = sprites.getOrNull(GameData.player.spriteIndex)?.frameHeight(0, 1) ?: 0
        if (marine <= 0) return out                 // no yardstick: nobody moves
        for (c in GameData.creatures) {
            val set = sprites.getOrNull(c.spriteIndex) ?: continue
            val resting = c.death.frames.last()
            val h = set.frameHeight(resting, 1)
            out[c.spriteIndex] = h >= marine * Scene.TALL_CORPSE
        }
        return out
    }

    /** Reloads if the active WAD has changed since the sprites were built. */
    private fun reloadWadIfChanged() {
        val wanted = activeWad()?.absolutePath ?: BUNDLED
        if (wanted != loadedWad) loadWad()
    }

    /**
     * One floor flat per skill level, so the ground reports the difficulty.
     *
     * A wallpaper sits *behind* the launcher icons, so a backdrop has to stay quiet. Every
     * flat in the IWAD was measured on mean luminance, spread and chroma, and then the
     * shortlist was decoded and **looked at**, which is the step that mattered: FLOOR1_7
     * measures as an ordinary dark red and is really two glaring panels, and GATE1 is a
     * circular emblem that tiles into a repeating logo.
     *
     * The five chosen all sit between 28 and 38 luminance, so the ladder climbs by hue while
     * the contrast behind the icons stays put.
     *
     * Each falls back down a shared chain, because a user-supplied WAD need not carry them
     * all â€” a WAD with only one usable flat simply shows the same ground at every level.
     */
    private fun loadFloors(w: WadFile): Array<Bitmap?> {
        // Chosen by measuring this WAD's own flats rather than by asking for names it may
        // not have. See FloorPicker for what is measured and why.
        val chosen = FloorPicker.choose(w)

        return Array(GameData.skills.size) { skill ->
            val flat = chosen.getOrNull(skill) ?: chosen.lastOrNull()
            if (flat == null) {
                Log.i(TAG, "no floor for ${GameData.skills[skill]}")
                return@Array null
            }
            Log.i(TAG, "floor for ${GameData.skills[skill]}: ${flat.name} " +
                "(luminance ${"%.1f".format(flat.luminance)}, chroma ${"%.1f".format(flat.chroma)})")

            val f = w.decodeFlat(flat.index)
            // Kept at full strength. The dimming used to be baked in here, and later drawn as
            // a layer over the background; both are gone. Android dims the whole wallpaper
            // surface itself when it wants the icons to win, and doing it twice left this at
            // 16% of itself.
            Bitmap.createBitmap(f.pixels, f.width, f.height, Bitmap.Config.ARGB_8888)
        }
    }

    /**
     * Loads the status bar numerals, so the corner readout is drawn with the WAD's own
     * glyphs rather than a bundled font. They decode with the ordinary patch reader, and a
     * user-supplied IWAD brings its own digits along with its own sprites.
     */
    private fun loadDigits(w: WadFile) {
        fun lump(name: String): Bitmap? {
            val i = w.indexOf(name)
            if (i < 0) return null
            val p = w.decodePatch(i)
            return Bitmap.createBitmap(p.pixels, p.width, p.height, Bitmap.Config.ARGB_8888)
        }
        val loaded = Array(10) { lump("STTNUM$it") ?: return }
        digits = loaded
        healthColor = w.paletteColor(GameData.PALETTE_HEALTH)
        armorColor = w.paletteColor(GameData.PALETTE_ARMOR)
    }

    override fun onCreateEngine(): Engine = ProfEngine()

    private inner class ProfEngine : WallpaperService.Engine() {

        // ponytail: a Handler on the wallpaper process main looper, not a dedicated
        // thread. The process is ours alone, so no synchronisation with surfaceDestroyed
        // is needed.
        private val handler = Handler(Looper.getMainLooper())

        /**
         * No colour filter on either paint: lumps are drawn exactly as the WAD stores them,
         * so these stay on the cheapest draw path there is.
         */
        private val paint = Paint().apply { isFilterBitmap = false }

        /** Tiled floor. Its shader matrix carries the home screen parallax. */
        private val floorPaint = Paint().apply { isFilterBitmap = false }
        private val floorMatrix = Matrix()
        private var offset = 0.5f

        private val matrix = Matrix()
        private val frame = Rect()

        private var visible = false
        private var tic = 0
        private var lastNanos = 0L
        private var ticAccumulator = 0L
        private var scene: Scene? = null

        /**
         * Both scales follow the display density, so a map unit and a sprite pixel keep a
         * constant *physical* size on any screen.
         *
         * Without this the constants are raw pixels: on a 560 dpi phone the marine would be
         * two thirds the size he is here, and on a 240 dpi tablet half again as large. The
         * world, measured in map units, is then free to vary with the physical size of the
         * screen, which is what should happen â€” a bigger display shows more of the scene
         * rather than the same scene magnified.
         *
         * The reference is the density these values were tuned on.
         */
        private val densityScale = resources.displayMetrics.density / REFERENCE_DENSITY
        private val pxPerUnit = PX_PER_UNIT * densityScale
        private val spriteScale = SPRITE_SCALE * densityScale

        private val drawRunnable = Runnable { step() }

        // isPowerSaveMode is an IPC call into PowerManagerService: querying it every frame
        // meant 20 transactions per second for a value that changes maybe once a day.
        // Sampled once per second instead.
        private val powerManager by lazy { getSystemService(POWER_SERVICE) as PowerManager }
        private var powerSave = false
        private var powerSaveCheckedAt = 0L

        /** Frame rate last declared to the compositor, so we only declare it on change. */
        private var declaredFps = 0f

        /**
         * The user's choices, re-read whenever the wallpaper becomes visible.
         *
         * Not a listener: leaving the settings screen makes the wallpaper visible again, so
         * that moment already is the notification, and it costs one preferences read at a
         * point where the engine is waking up anyway. A change made while the wallpaper is
         * hidden lands the instant it is seen, which is the only time it could matter.
         */
        private val prefs = Settings.of(this@ProfWallpaperService)
        private var chosenFps = Settings.DEFAULT_FPS
        private var readoutVisible = true
        private var overlayVisible = false
        private var debugVisible = false

        /**
         * Held here rather than only on the scene, because the scene is rebuilt on every
         * surface change and a value that lives only there does not survive it.
         */
        private var godMode = false

        /** Completions already written to the preferences, so each one is counted once. */
        private var seenCompletions = 0

        /** Where the finger went down, and when a tap was last acted on, from either source. */
        private var downX = 0f
        private var downY = 0f
        private var lastTapAt = 0L

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            setOffsetNotificationsEnabled(true)
            // On, because the launcher's command cannot be relied on. WALLPAPER_TAP is sent
            // at the launcher's discretion, and a device whose home screen claims the tap
            // for itself - a Galaxy with double-tap-to-sleep, for one - never forwards it,
            // so nothing was ever dropped there.
            //
            // The cost this was avoiding is real: raw delivery wakes this process for every
            // finger movement on the home screen. onTouchEvent therefore does the least it
            // can - it looks at UP alone and ignores the rest.
            setTouchEventsEnabled(true)
        }

        /**
         * The launcher's own channel for wallpaper interaction: a tap on empty space, and an
         * icon being dropped. Coordinates arrive in display pixels, and the render scale
         * cancels out when converting to map units, because the world was sized from the
         * reduced surface using the same factor.
         */
        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: android.os.Bundle?,
            resultRequested: Boolean,
        ): android.os.Bundle? {
            tapWorld(x, y, action)
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        /** Screen pixels to map units, then on to whichever of the two gestures it was. */
        private fun tapWorld(x: Int, y: Int, action: String?) {
            val s = scene
            if (s != null) {
                // pxPerUnit, not PX_PER_UNIT. The world is sized with the density-scaled
                // factor, so dividing the touch point by the bare constant lands it off by
                // exactly the density ratio - which on a device whose density differs from
                // the reference put every dropped item down and to the right of the finger,
                // by a little on a close density and by a lot on a far one.
                val wx = (x / pxPerUnit * GameData.FRACUNIT).toInt()
                val wy = (y / pxPerUnit * GameData.FRACUNIT).toInt()
                when (action) {
                    WALLPAPER_TAP -> {
                        // One tap, one drop, whichever channel reports it.
                        //
                        // Both do, on a launcher that forwards WALLPAPER_TAP, and the order
                        // is not fixed: the raw ACTION_UP usually arrives first and the
                        // command follows once the launcher has decided it was a tap. The
                        // earlier guard only suppressed a touch that came *after* a command,
                        // so in the ordinary order nothing was suppressed and every tap
                        // dropped twice. One timestamp, written and checked here, covers both
                        // orders because it does not care which side it came from.
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastTapAt < COMMAND_WINDOW_MS) return
                        lastTapAt = now
                        s.tapAt(wx, wy)
                    }
                    // Not deduplicated: a drop has no touch-event counterpart, and two icons
                    // dropped in quick succession are two events that both happened.
                    HOME_DROP -> s.dropAt(wx, wy)
                }
            }
        }

        /**
         * A tap the launcher did not forward.
         *
         * Only ACTION_UP, and only when the finger barely moved: anything else is a scroll or
         * a long press that belongs to the launcher, not to us.
         *
         * A device that does send WALLPAPER_TAP would otherwise drop twice for one tap, so a
         * command seen recently suppresses the touch that follows it.
         */
        override fun onTouchEvent(event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    // The de-duplication lives in tapWorld, so both channels go through it.
                    val moved = abs(event.x - downX) + abs(event.y - downY)
                    if (moved <= TAP_SLOP) tapWorld(event.x.toInt(), event.y.toInt(), WALLPAPER_TAP)
                }
            }
            super.onTouchEvent(event)
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xStep: Float,
            yStep: Float,
            xPixels: Int,
            yPixels: Int,
        ) {
            offset = xOffset
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                applySettings()
                // Cheap wake-up: resume from the frozen state, rebuild nothing. This
                // matters on the lock screen, where it fires on every notification.
                lastNanos = System.nanoTime()
                step()
            } else {
                handler.removeCallbacks(drawRunnable)
            }
        }

        private fun applySettings() {
            chosenFps = Settings.fps(prefs)
            readoutVisible = Settings.readout(prefs)
            overlayVisible = Settings.overlay(prefs)
            debugVisible = Settings.debug(prefs)
            godMode = Settings.godMode(prefs)

            // A different WAD means every sprite, colour and floor is different, so the
            // shader built from the old tiles has to go with them.
            val before = loadedWad
            reloadWadIfChanged()
            if (loadedWad != before) shaderSkill = -1

            // After the reload, never before it: what the scene is told about the WAD has to
            // be what was just loaded.
            syncScene()

            background = Settings.background(prefs)
            backgroundColour = paletteColour(Settings.backgroundColour(prefs))

            // Held only while it is the chosen backdrop: a decoded photograph is the largest
            // bitmap this process ever owns, and keeping it after the setting changed would
            // be several megabytes retained for something nobody is looking at. Keyed on the
            // file's timestamp so choosing a different photo actually replaces it.
            val stamp = PhotoStore.file(this@ProfWallpaperService)?.lastModified() ?: 0L
            if (background != Settings.Background.PHOTO) {
                photo = null
                photoStamp = 0L
            } else if (photo == null || stamp != photoStamp) {
                photoStamp = stamp
                photo = PhotoStore.load(
                    this@ProfWallpaperService,
                    frame.width().coerceAtLeast(1),
                    frame.height().coerceAtLeast(1),
                )
            }
        }

        private var photoStamp = 0L

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            visible = false
            handler.removeCallbacks(drawRunnable)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)

            // Do not reach for setFixedSize here. Drawing onto a half-resolution surface and
            // letting the compositor scale it up saved two thirds of the graphics memory,
            // and it worked perfectly in the picker preview â€” but a real wallpaper engine
            // throws UnsupportedOperationException, "Wallpapers currently only support
            // sizing from layout". The preview and the live engine are different surface
            // paths, and only the live one enforces this.
            Log.i(TAG, "drawing surface: ${width}x$height")
            frame.set(0, 0, width, height)
            shaderSkill = -1                       // force the floor shader to be rebuilt
            debugTop = (getSystemService(WindowManager::class.java)
                ?.currentWindowMetrics
                ?.windowInsets
                ?.getInsets(WindowInsets.Type.statusBars())
                ?.top ?: 0) + DEBUG_CLOCK_DROP * densityScale
            // The new scene starts at zero completions, so the tally of what has already been
            // written has to start there too. Left behind, the two disagreed from the first
            // tic and every rotation re-armed a completion that had not happened.
            seenCompletions = 0
            scene = Scene(
                worldWidth = (width / pxPerUnit).toInt(),
                worldHeight = (height / pxPerUnit).toInt(),
                // The picker preview is the shop window, and it is watched for seconds, not
                // minutes: the opening wave arrives at once rather than after the usual pause.
                instantStart = isPreview,
            )
            syncScene()
        }

        /**
         * Everything the scene holds that this engine, not the scene, is the source of.
         *
         * It exists because a value written onto the scene does not survive the scene, and the
         * scene is rebuilt on every surface change. God mode was lost exactly that way:
         * applySettings runs when the wallpaper becomes visible, so on the paths where the
         * surface arrives afterwards it set the flag on a scene that did not exist yet, and
         * the one built a moment later began invulnerable = false. Rotating the phone lost it
         * too.
         *
         * The two masks had the mirror-image fault: they were fixed at construction and the
         * WAD can be swapped without rebuilding the scene, so after an import the fight went
         * on judging itself against the sprites of the file before it. On a Phase 2 to Phase 1
         * switch that means spawning creatures the new file cannot draw - invisible things
         * that still shoot, which is the exact failure the masks were written to prevent.
         *
         * Called after every reload and after every rebuild, so neither can outlive the other.
         */
        private fun syncScene() {
            val s = scene ?: return
            s.invulnerable = godMode
            // Only the creatures this WAD can draw. Spawning one it cannot would put an
            // invisible thing in the fight.
            s.drawable = drawableCreatures()
            // And only the pickups it can draw: a Phase 1 IWAD has no super shotgun.
            s.drawableItems = BooleanArray(GameData.items.size) { i ->
                val set = sprites.getOrNull(GameData.items[i].spriteIndex)
                set != null && set.frameCount > 0
            }
            s.tallCorpses = tallCorpses()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            draw()
        }

        /** One turn: advance the elapsed tics, draw once, reschedule. */
        private fun step() {
            if (!visible) return

            val now = System.nanoTime()
            val elapsed = now - lastNanos
            lastNanos = now

            // Fixed-step accumulator: game speed does not depend on the draw rate.
            // Clamped to half a second so a long pause does not replay 1000 tics at once.
            ticAccumulator += elapsed.coerceAtMost(500_000_000L)
            val nanosPerTic = 1_000_000_000L / TICRATE
            while (ticAccumulator >= nanosPerTic) {
                ticAccumulator -= nanosPerTic
                tic++
                scene?.tick(tic)
            }

            draw()

            if (now - powerSaveCheckedAt > 1_000_000_000L) {
                powerSaveCheckedAt = now
                powerSave = powerManager.isPowerSaveMode
            }
            // Drawing is decoupled from TICRATE: the scene always thinks 35 times a second,
            // this is only how often it is painted. Measured on a Pixel 6a, 8 actors, visible:
            //   20 fps -> 12.0% of one core (gfxinfo: 5 ms/frame, 0 janky frames)
            //   10 fps ->  9.2% of one core
            // Not linear - about 6.4% is fixed and independent of the rate, still
            // unidentified. Perfetto profiling belongs to phase 7, where the plan puts it.
            val fps = if (powerSave) chosenFps / 2 else chosenFps
            declareFrameRate(fps.toFloat())
            handler.postDelayed(drawRunnable, 1000L / fps)
        }

        /**
         * Tells the compositor how often this surface actually produces content.
         *
         * Measured A/B on a Pixel 6a: without it the pipeline runs at
         * `mActiveRenderFrameRate = 60` for a wallpaper that changes 20 times a second;
         * with it the figure drops to 20, and this process goes from 13.0% to 11.2% of one
         * core. SurfaceFlinger itself was unchanged at ~12.1%, and the panel never moved:
         * this device has a single fixed 60 Hz mode, so the "supported refresh rates
         * 60/30/20" it advertises are pipeline throttling rather than panel modes. The
         * display power saving one might hope for therefore does not appear here, though it
         * should on hardware with genuine multi-rate panels.
         *
         * FIXED_SOURCE because the rate really is fixed, and the seamless strategy so the
         * system only switches when it can do so without a visible glitch.
         */
        private fun declareFrameRate(fps: Float) {
            if (fps == declaredFps) return
            val surface = surfaceHolder.surface
            if (!surface.isValid) return
            surface.setFrameRate(
                fps,
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
            )
            declaredFps = fps
        }

        private fun draw() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockHardwareCanvas()
                if (canvas != null) drawScene(canvas)
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun drawScene(canvas: Canvas) {
            drawFloor(canvas)

            // A wash over the background, off unless asked for. Android dims the whole
            // wallpaper surface itself when it wants the icons to win - dark theme, and
            // Bedtime mode at 0.6 - so on, this is the second dark layer and the floor is
            // left at a fraction of itself. That is why it defaults off; it is here for the
            // screens where the launcher's own dim is not enough.
            if (overlayVisible) {
                overlay.shader = null
                overlay.color = Color.BLACK
                overlay.alpha = SCRIM_ALPHA
                canvas.drawRect(0f, 0f, frame.width().toFloat(), frame.height().toFloat(), overlay)
            }

            val s = scene
            if (s == null || sprites.isEmpty()) {
                drawPlaceholder(canvas)
                return
            }

            // Two passes over the same list, both already depth-sorted by Scene.tick: the big
            // bodies first, then everything that is still standing. Within each pass whoever
            // is in front covers those behind, as before.
            //
            // A pass rather than a different sort order, because the order of that list is
            // the simulation's - the tick walks it and removes by index - and depth is what
            // it means. Which layer a body belongs in is a question about the picture.
            for (i in s.actors.indices) if (s.restsBelow(s.actors[i])) drawActor(canvas, s.actors[i])
            for (i in s.actors.indices) if (!s.restsBelow(s.actors[i])) drawActor(canvas, s.actors[i])

            drawReadout(canvas, s)

            // Marine death, and the opposite outcome: a glow that breathes along the edge of
            // the device rather than a wash over the whole screen. The colours are not
            // invented - PLAYPAL 8, the original damage flash, and the green the armour
            // readout uses - but a full-screen sheet of either was a wall you could not read
            // the scene through, and a wallpaper has to stay usable at its most dramatic.
            if (s.dying) drawBorderGlow(canvas, deathTint, s.glowPulse)
            else if (s.winning) drawBorderGlow(canvas, winTint, s.glowPulse)

            // Then the curtain, over the glow and over everything: one second to black on a
            // death or a finished table, one second back on the ground that follows.
            val cover = s.coverFade
            if (cover > 0f) {
                overlay.shader = null
                overlay.color = Color.BLACK
                overlay.alpha = (cover * 255f).toInt().coerceIn(0, 255)
                canvas.drawRect(0f, 0f, frame.width().toFloat(), frame.height().toFloat(), overlay)
            }

            if (debugVisible) drawDebug(canvas, s)
            // Counted here rather than in the scene: the scene is rebuilt on every surface
            // change and would forget, and this is the side that owns the preferences.
            if (s.completions > seenCompletions) {
                seenCompletions = s.completions
                Settings.addCompletion(prefs)
            }
        }

        private fun drawActor(canvas: Canvas, a: Actor) {
            val set = sprites[a.spriteIndex]
            val packed = set.resolve(a.frame(tic), a.spriteRotation())
            if (packed < 0) return
            // Null when the lump refused to decode, which only a user-supplied WAD can
            // cause. One actor goes undrawn rather than the wallpaper going down.
            val sprite = set.sprite(packed shr 1) ?: return
            val flip = packed and 1 == 1

            // Oblique projection: x horizontal, y into the depth. The sprite anchor
            // point (the feet) lands on the actor position.
            //
            // drawHeight lifts it off that point without moving it: the projection has no
            // third axis, so height can only exist here, in the drawing. It is why a
            // fireball leaves the chest and still flies the trajectory it was given, and
            // why it still sorts and collides at the position it really occupies.
            val ax = (a.x.toFloat() / GameData.FRACUNIT) * pxPerUnit
            val ay = (a.y.toFloat() / GameData.FRACUNIT - a.drawHeight) * pxPerUnit

            matrix.setScale(if (flip) -spriteScale else spriteScale, spriteScale)
            matrix.postTranslate(
                if (flip) ax + sprite.xOffset * spriteScale else ax - sprite.xOffset * spriteScale,
                ay - sprite.yOffset * spriteScale,
            )
            canvas.drawBitmap(sprite.bitmap, matrix, paint)
        }

        /**
         * The two readings, split across the bottom corners: armour on the left, health on
         * the right, drawn with the WAD's own status bar numerals.
         *
         * Splitting them beats stacking them. Stacked, the two numbers sat one above the
         * other in a single corner and could be read as one figure; apart, there is nothing
         * to confuse and each has the whole width of its own corner. Both stay along the
         * bottom, clear of the status bar and of the top row of icons.
         *
         * Deliberately not a home screen widget: a widget runs in another process, so it
         * would need a channel out of the wallpaper and a push on every change, and an
         * update arriving while the wallpaper is not even running would undo the whole
         * battery premise. Drawn inside the scene it is a handful of small bitmaps on a
         * frame we are already drawing.
         */
        private fun drawReadout(canvas: Canvas, s: Scene) {
            if (!readoutVisible) return
            val glyphs = digits ?: return

            val scale = spriteScale * READOUT_SCALE
            val gw = glyphs[0].width * scale
            val gh = glyphs[0].height * scale
            val pad = READOUT_PADDING * scale
            val baseline = frame.height() - gh - pad

            // With god mode on the two numbers say nothing: armour is untouched and health
            // never moves off a hundred. They say what is going on instead, one word each, in
            // the corners the numbers were already using and in their colours.
            //
            // Drawn with the platform font rather than the WAD's, for the same reason the
            // debug overlay is: STTNUM covers the ten digits and nothing else, and this needs
            // letters. Freedoom does carry a full font in STCFN, but pulling it in would put a
            // second glyph loader and 94 more lumps in the asset to write two words.
            if (godMode) {
                wordPaint.textSize = gh
                wordPaint.color = armorColor
                canvas.drawText("GOD", pad, baseline + gh, wordPaint)
                wordPaint.color = healthColor
                val w = wordPaint.measureText("MODE")
                canvas.drawText("MODE", frame.width() - w - pad, baseline + gh, wordPaint)
                return
            }

            val health = s.playerHealth
            val armor = s.playerArmor

            // Colour is what tells the two apart, which is why the percent sign is gone: it
            // occupied a glyph's width to say nothing. Position now says it too.
            hudPaint.colorFilter = armorFilter
            drawNumber(canvas, armor, pad, baseline, scale)

            // Digit counting rather than toString: the draw loop allocates nothing anywhere
            // else, and a throwaway string forty times a second is not the place to start.
            // The right-hand block is measured so it ends at the margin however wide it is.
            hudPaint.colorFilter = healthFilter
            drawNumber(canvas, health, frame.width() - digitCount(health) * gw - pad, baseline, scale)
        }

        /** GOD and MODE, in the corners the readout numbers otherwise occupy. */
        private val wordPaint = Paint().apply {
            isAntiAlias = true
            isFakeBoldText = true
        }

        private fun digitCount(value: Int) = when {
            value >= 100 -> 3
            value >= 10 -> 2
            else -> 1
        }

        private fun drawNumber(canvas: Canvas, value: Int, x: Float, y: Float, scale: Float) {
            val glyphs = digits ?: return
            var cursor = x
            var divisor = 1
            repeat(digitCount(value) - 1) { divisor *= 10 }
            while (divisor > 0) {
                val g = glyphs[(value / divisor) % 10]
                matrix.setScale(scale, scale)
                matrix.postTranslate(cursor, y)
                canvas.drawBitmap(g, matrix, hudPaint)
                cursor += g.width * scale
                divisor /= 10
            }
        }

        /**
         * Tiled floor texture, shifted by the home screen paging. The shader repeats the
         * 64x64 flat, so the whole background is one draw call whatever the screen size.
         */
        /** Which skill's tile the shader currently holds, so it is rebuilt only on change. */
        private var shaderSkill = -1

        /** What the user chose to sit behind the fight, re-read with the other settings. */
        private var background = Settings.Background.DYNAMIC
        private var backgroundColour = 0
        private var photo: Bitmap? = null
        private val photoMatrix = Matrix()

        private fun drawFloor(canvas: Canvas) {
            when (background) {
                Settings.Background.COLOUR -> {
                    // Undimmed, unlike the dungeon floor. A flat colour was picked
                    // deliberately and showing something else would be a worse answer; a
                    // uniform field also has no texture to compete with the icons.
                    canvas.drawColor(backgroundColour)
                    return
                }
                Settings.Background.PHOTO -> {
                    val p = photo
                    if (p != null) {
                        // Scaled to cover and centred, so no edge of the screen is ever left
                        // bare whatever shape the photograph is.
                        val scale = maxOf(
                            frame.width().toFloat() / p.width,
                            frame.height().toFloat() / p.height,
                        )
                        photoMatrix.setScale(scale, scale)
                        photoMatrix.postTranslate(
                            (frame.width() - p.width * scale) / 2f,
                            (frame.height() - p.height * scale) / 2f,
                        )
                        canvas.drawBitmap(p, photoMatrix, null)
                        return
                    }
                    // Chosen but missing: fall through to the dungeon floor rather than
                    // leaving the screen blank.
                }
                Settings.Background.DYNAMIC -> Unit
            }

            // The ground changes with the difficulty. Rebuilding a BitmapShader is cheap but
            // not free, and the skill changes a handful of times an hour, so it is keyed on
            // the value rather than done per frame.
            val skill = scene?.skill ?: 0
            if (skill != shaderSkill) {
                shaderSkill = skill
                floorPaint.shader = floorTiles.getOrNull(skill)?.let {
                    BitmapShader(it, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                }
            }

            val shader = floorPaint.shader
            if (shader == null) {
                canvas.drawColor(BACKDROP)
                return
            }
            floorMatrix.setScale(FLOOR_SCALE, FLOOR_SCALE)
            floorMatrix.postTranslate((0.5f - offset) * PARALLAX_PX, 0f)
            shader.setLocalMatrix(floorMatrix)
            canvas.drawRect(0f, 0f, frame.width().toFloat(), frame.height().toFloat(), floorPaint)
        }

        /** Solid colour overlays: the black curtain and the background wash. */
        private val overlay = Paint()

        /**
         * The border glow: an inner glow, not a vignette.
         *
         * A blurred stroke laid on the frame's own edge, twice as thick as it needs to be so
         * its outer half falls off the screen and only the inward feather is seen. A radial
         * gradient was tried first and washed the middle of the screen as well; this one stays
         * where a glow belongs, against the border.
         */
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private var glowDepth = 0f

        private fun drawBorderGlow(canvas: Canvas, color: Int, pulse: Float) {
            val w = frame.width().toFloat()
            val h = frame.height().toFloat()
            if (w <= 0f || h <= 0f) return
            val depth = minOf(w, h) * GLOW_DEPTH
            if (depth != glowDepth) {
                glowDepth = depth
                glowPaint.strokeWidth = depth * 2f
                glowPaint.maskFilter = BlurMaskFilter(depth / 2f, BlurMaskFilter.Blur.NORMAL)
            }
            glowPaint.color = color
            // Never all the way off: the glow breathes between these two, so the border keeps
            // saying what happened for the whole time the curtain takes to close.
            glowPaint.alpha =
                (GLOW_MIN_ALPHA + (GLOW_MAX_ALPHA - GLOW_MIN_ALPHA) * pulse).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, w, h, glowPaint)
        }

        /**
         * What the fight will not say by itself: which rung it is on, which wave, and how
         * long until the next drop. Off by default and plain system text - the WAD's numerals
         * are the ten digits and nothing else, so they cannot spell any of this.
         */
        private val debugPaint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            textSize = 28f * densityScale
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }

        /**
         * How far down the debug block starts: clear of the status bar, and clear of the
         * clock the launcher puts under it.
         *
         * The inset is a measurement and is asked for rather than guessed - once per surface,
         * in onSurfaceChanged, because it only changes when the surface does and reading it
         * per frame meant a system service lookup and an Insets object on every draw. What
         * sits below it is not measurable: the at-a-glance clock is the launcher's, has no
         * inset of its own and is not visible from here, so [DEBUG_CLOCK_DROP] is a tuned
         * number and the knob to turn if some launcher puts its clock lower still.
         */
        private var debugTop = DEBUG_CLOCK_DROP * densityScale

        private fun drawDebug(canvas: Canvas, s: Scene) {
            val x = debugPaint.textSize / 2f
            val step = debugPaint.textSize * 1.2f
            var y = debugTop + debugPaint.textSize
            canvas.drawText("wave ${s.wave + 1}/${GameData.waves.size}", x, y, debugPaint)
            y += step
            canvas.drawText("skill ${s.skill + 1} ${GameData.skills[s.skill]}", x, y, debugPaint)
            canvas.drawText("drop in ${s.ticsToDrop / TICRATE}s", x, y + step, debugPaint)
        }

        /**
         * The readout is drawn without the scene dimming. That filter exists so the
         * wallpaper does not fight the launcher icons, but these digits are information
         * rather than decoration, and Freedoom's status bar numerals are already a dark red:
         * dimming them a further 38% left them barely legible against the floor.
         */
        private val hudPaint = Paint().apply { isFilterBitmap = false }

        /**
         * Recolours the red numerals into a palette colour.
         *
         * The glyphs are essentially a red ramp, so moving the red channel into the target
         * colour's proportions keeps every bit of the shading inside each digit. Flattening
         * with a SRC_IN filter would replace it with a flat silhouette. It is set on the
         * paint once, so recolouring costs nothing per frame.
         */
        // Built once, on first draw, by which time the WAD has been read and the colours
        // are known. Rebuilding them per frame would be two allocations forty times a
        // second for a value that never changes.
        private val healthFilter by lazy { tint(healthColor) }
        private val armorFilter by lazy { tint(armorColor) }

        private fun tint(color: Int) = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    Color.red(color) / 255f, 0f, 0f, 0f, 0f,
                    Color.green(color) / 255f, 0f, 0f, 0f, 0f,
                    Color.blue(color) / 255f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )

        /**
         * What is drawn when the WAD is missing: the reason, and where to report it.
         *
         * Plain system text, like the debug readout and for the same reason - the WAD's own
         * glyphs are exactly what is unavailable here.
         */
        private val errorPaint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            textSize = 28f * densityScale
            textAlign = Paint.Align.CENTER
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }

        private fun drawPlaceholder(canvas: Canvas) {
            // ponytail: split per frame, three short lines at 40fps costs nothing measurable.
            val lines = getString(R.string.wallpaper_error).split('\n')
            val step = errorPaint.textSize * 1.4f
            val x = frame.width() / 2f
            var y = (frame.height() - (lines.size - 1) * step) / 2f
            for (line in lines) {
                canvas.drawText(line, x, y, errorPaint)
                y += step
            }
        }
    }

    private companion object {
        /**
         * Scene zoom: how many pixels one map unit is worth.
         *
         * Speeds stay the original ones *in map units* â€” this value only decides how fast
         * they appear, i.e. how wide a slice of the world is framed.
         */

        const val PX_PER_UNIT = 1.5f

        /**
         * Sprite magnification. Deliberately different from PX_PER_UNIT: the original sprites were
         * drawn for a 320x200 screen and would be unreadable at 1.5x on a modern phone.
         * The proportions between monsters stay correct relative to each other.
         */
        const val SPRITE_SCALE = 3f

        /** The IWAD shipped in assets, used until the user supplies one of their own. */
        const val BUNDLED = "freedoom2.wad"

        /** The optional wash over the background: 60% black, the figure it had before. */
        const val SCRIM_ALPHA = 153

        /**
         * The inner glow: how far in from the edge it reaches, and the two opacities it
         * breathes between.
         *
         * It replaced a wash over the whole screen. A wash says the same thing by hiding the
         * fight; a glow says it around the edge, and the fight stays visible until the black
         * curtain takes it. The depth is a fraction of the shorter side, so it is the same
         * band of screen on any device.
         */
        const val GLOW_DEPTH = 0.05f
        const val GLOW_MIN_ALPHA = 60f
        const val GLOW_MAX_ALPHA = 190f

        /** Magnification of the 64x64 floor tile. */
        const val FLOOR_SCALE = 1.5f

        /** How far the floor slides across the full home screen paging range, in surface pixels. */
        const val PARALLAX_PX = 240f

        /** Display density the pixel scales above were tuned at: a Pixel 6a, 420 dpi. */
        const val REFERENCE_DENSITY = 2.625f

        /** Used when the WAD has no usable flat. */
        const val BACKDROP = 0xFF201814.toInt()

        /**
         * How far below the status bar the debug block starts, in dp at the reference density.
         *
         * The clock a launcher draws under the status bar was covering it. This clears a
         * typical at-a-glance widget; raise it if one sits lower.
         */
        const val DEBUG_CLOCK_DROP = 96f

        /** Size of the corner readout, relative to the sprite scale. */
        const val READOUT_SCALE = 0.7f

        /**
         * Padding around the readout, in glyph-scale units. Generous at the bottom, where
         * the navigation bar and the dock both encroach.
         */
        const val READOUT_PADDING = 14f

        /** Commands the launcher sends to the wallpaper. */
        const val WALLPAPER_TAP = "android.wallpaper.tap"
        const val HOME_DROP = "android.home.drop"

        /** How far a finger may travel and still count as a tap, in pixels. */
        const val TAP_SLOP = 24f

        /** A touch this soon after a launcher command is the same gesture arriving twice. */
        const val COMMAND_WINDOW_MS = 400L
    }
}
