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

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Check on the WAD parser: if anyone breaks the column decoding or the sprite naming
 * convention, this fails.
 *
 * The .wad is not under version control, so the test skips itself rather than failing when
 * the file is missing.
 */
class WadFileTest {

    private val wadFile = File("src/main/assets/freedoom2.wad")

    private fun openWad(): WadFile {
        assumeTrue("freedoom2.wad missing: test skipped", wadFile.exists())
        val ch = RandomAccessFile(wadFile, "r").channel
        return WadFile(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()))
    }

    @Test
    fun `the shipped WAD satisfies everything the code asks for`() {
        val wad = openWad()

        // The bundled WAD is reduced at build time by the reduceWad task, which keeps its
        // own list of lump names. This is the check that catches the list drifting away
        // from the code: it asks for exactly what the wallpaper asks for at runtime.
        assertTrue(wad.indexOf("PLAYPAL") >= 0, "PLAYPAL missing: no palette, no colours")
        // The floors are no longer named anywhere, so what is checked is that the shipped
        // WAD still lets the app choose a full set. This is the seam the build script cannot
        // share: reduceWad measures the flats itself, because GameData is not on its
        // classpath, and this is what fails if the two measurements ever disagree.
        assertEquals(
            GameData.skills.size,
            FloorPicker.choose(wad).size,
            "the shipped WAD no longer offers one floor per skill",
        )
        for (d in 0..9) assertTrue(wad.indexOf("STTNUM$d") >= 0, "readout digit $d missing")

        // Not every prefix: which sprites exist is a fact of the WAD rather than something
        // this project declares. The super shotgun arrived with Phase 2, so a Phase 1 IWAD
        // like the bundled one carries no SGN2 and must not be required to. What has to hold
        // is that the file can drive a scene — the marine, every creature, and at least one
        // weapon past the pistol.
        // The wave table names every creature in the bestiary, five of which arrived with
        // Phase 2 and are simply absent from a Phase 1 IWAD like this one. Presence is
        // therefore the wrong question: what has to hold is that the marine can be drawn,
        // and that every creature a wave names resolves - through Scene.substitute where
        // the file has never heard of it - to something this WAD can actually show.
        assertTrue(
            wad.lumpsStartingWith(GameData.player.lumpPrefix).isNotEmpty(),
            "no marine sprite at all",
        )
        val drawable = BooleanArray(GameData.creatures.size) {
            wad.lumpsStartingWith(GameData.creatures[it].lumpPrefix).isNotEmpty()
        }
        assertTrue(drawable.any { it }, "the shipped WAD draws no creature at all")
        val scene = Scene(1080, 2400, drawable = drawable)
        for (w in GameData.waves) for (i in w.order) {
            assertTrue(drawable[scene.substitute(i)], "wave creature $i resolves to nothing drawable")
        }
        val weaponPickups = GameData.items.filter { it.kind == GameData.ITEM_WEAPON }
        assertTrue(
            weaponPickups.count { wad.lumpsStartingWith(it.lumpPrefix).isNotEmpty() } >= 1,
            "the shipped WAD offers no weapon beyond the pistol",
        )

        // Stronger than presence: every frame each creature can be drawn in must resolve at
        // each of the four angles still in use. A missing rotation would show up on screen
        // as a creature vanishing at one heading.
        // Only what this WAD carries: a creature it has never heard of is substituted away
        // at runtime, and demanding its frames here would just be asking a Phase 1 file for
        // Phase 2 art.
        // The missiles, which the loop below would never reach: they are not creatures, and
        // nothing else asked the shipped WAD whether it can draw one. A projectile always
        // asks for rotation 1 - it never sets facing, and spriteRotation answers 1 for
        // DI_NODIR - so that is the angle that has to resolve, for the flight frames and for
        // the blast frames both.
        for (p in GameData.projectiles.filter { wad.lumpsStartingWith(it.lumpPrefix).isNotEmpty() }) {
            val set = SpriteSet(wad, p.lumpPrefix)
            val frames = p.anim.frames.toList() + (p.burst?.frames?.toList() ?: emptyList())
            for (f in frames.distinct()) {
                assertTrue(
                    set.resolve(f, 1) >= 0,
                    "${p.lumpPrefix}: frame $f has no sprite at the angle a missile asks for",
                )
            }
        }

        for (c in (GameData.creatures + GameData.player).filter { wad.lumpsStartingWith(it.lumpPrefix).isNotEmpty() }) {
            val set = SpriteSet(wad, c.lumpPrefix)
            val frames = (0 until c.walkFrames).toList() +
                c.attack.frames.toList() + c.pain.frames.toList() + c.death.frames.toList()
            for (f in frames.distinct()) {
                for (rot in intArrayOf(1, 3, 5, 7)) {
                    assertTrue(
                        set.resolve(f, rot) >= 0,
                        "${c.name}: frame $f has no sprite at rotation $rot",
                    )
                }
            }
        }
    }

    @Test
    fun `decodes a patch with sensible size and pixels`() {
        val wad = openWad()
        val i = wad.indexOf("TROOA1")
        assertTrue(i >= 0, "TROOA1 missing")
        val p = wad.decodePatch(i)
        assertTrue(p.width in 8..200, "suspicious width: ${p.width}")
        assertTrue(p.height in 8..200, "suspicious height: ${p.height}")
        assertEquals(p.width * p.height, p.pixels.size)
        // A monster is not entirely transparent: if it were, the posts were not read.
        val opaque = p.pixels.count { it ushr 24 != 0 }
        assertTrue(opaque > p.pixels.size / 10, "almost fully transparent: $opaque opaque pixels")
        // ...nor entirely opaque: the column format leaves the corners transparent.
        assertTrue(opaque < p.pixels.size, "no transparent pixel: columns read incorrectly")
    }

    @Test
    fun `every skill floor is an opaque 64x64 tile inside one brightness band`() {
        val wad = openWad()

        // The design claim is that the ladder climbs by hue while the contrast behind the
        // launcher icons stays put. That only holds if the five sit in one narrow brightness
        // band, so the band is what is asserted — not neutrality, which the ladder gives up
        // on purpose once the ground takes on colour.
        val chosen = FloorPicker.choose(wad)
        assertEquals(GameData.skills.size, chosen.size, "not one floor per skill")
        assertEquals(chosen.size, chosen.map { it.name }.toSet().size, "two skills share a floor")

        for (flat in chosen) {
            val f = wad.decodeFlat(flat.index)
            assertEquals(64, f.width, "${flat.name} is not 64 wide")
            assertEquals(64, f.height, "${flat.name} is not 64 high")
            // Flats are raw palette indices with no transparency: every pixel must be
            // opaque, otherwise the backdrop would show holes when tiled.
            assertTrue(f.pixels.all { it ushr 24 == 0xFF }, "${flat.name} must be fully opaque")
            assertTrue(
                flat.luminance in 20.0..45.0,
                "${flat.name} at ${flat.luminance} is outside the 20-45 backdrop band",
            )
        }

        // And the ladder has to actually climb: the last floor carries more colour than the
        // first, or the whole per-skill idea shows nothing.
        assertTrue(
            chosen.last().chroma > chosen.first().chroma,
            "the ladder does not gain colour: ${chosen.map { "${it.name} ${it.chroma.toInt()}" }}",
        )
    }

    @Test
    fun `mirrored rotations share a single lump`() {
        val wad = openWad()
        val set = SpriteSet(wad, "TROO")
        assertTrue(set.frameCount >= 4, "not enough walk frames: ${set.frameCount}")

        // TROOA3A7: the same lump for angles 3 and 7, the second one mirrored. The pair used
        // to be 2 and 8, but those are diagonal views and no longer ship.
        val a3 = set.resolve(0, 3)
        val a7 = set.resolve(0, 7)
        assertTrue(a3 >= 0 && a7 >= 0, "frame A rotations 3/7 missing")
        assertEquals(a3 shr 1, a7 shr 1, "the two rotations should share the lump")
        assertEquals(0, a3 and 1, "rotation 3 must not be mirrored")
        assertEquals(1, a7 and 1, "rotation 7 must be mirrored")
        assertEquals("TROOA3A7", wad.nameAt(a3 shr 1))
    }
}
