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

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Checks an IWAD this project does not ship against what the wallpaper asks of it.
 *
 * Skipped unless -DforeignWad=<path> is given, because the file is the user's own and can
 * never live in this repository. Nothing is copied or written: the WAD is opened read-only
 * and only lump names, sizes and dimensions are reported.
 *
 *   gradlew testDebugUnitTest --tests '*ForeignWadTest*' -DforeignWad="C:\path\to\your.wad"
 *
 * It exists because "some monsters look wrong, the big ones especially" is not something a
 * bundled Phase 1 file can reproduce - the creatures in question are not in it.
 */
class ForeignWadTest {

    @Test
    fun `every frame the wallpaper draws resolves, and how much memory it costs`() {
        val path = System.getProperty("foreignWad") ?: run {
            println("ForeignWadTest skipped: pass -DforeignWad=<path to an IWAD>")
            return
        }
        val file = File(path)
        assertTrue(file.isFile, "no such WAD: $path")

        val wad = RandomAccessFile(file, "r").use { raf ->
            WadFile(raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()))
        }
        println("=== $path, ${file.length() / 1024 / 1024} MB")

        var worstActor = ""
        var worstBytes = 0L
        val missing = ArrayList<String>()

        for (c in GameData.creatures + GameData.player) {
            val lumps = wad.lumpsStartingWith(c.lumpPrefix)
            if (lumps.isEmpty()) {
                println("  %-14s absent from this WAD, substituted at runtime".format(c.name))
                continue
            }
            val frames = ((0 until c.walkFrames).toList() + c.attack.frames.toList() +
                c.pain.frames.toList() + c.death.frames.toList()).distinct()

            // What the four cardinal rotations cost if every one of them is held at once,
            // which is what an actor's cache is asked to do while it walks a full circle.
            val held = HashSet<Int>()
            var gaps = 0
            for (f in frames) for (rot in intArrayOf(1, 3, 5, 7)) {
                val packed = SpriteSet(wad, c.lumpPrefix).resolve(f, rot)
                if (packed < 0) { gaps++; continue }
                held += packed shr 1
            }
            var bytes = 0L
            var widest = 0
            for (lump in held) {
                bytes += wad.patchBytes(lump)
                val raw = wad.rawLump(lump)
                val w = (raw[0].toInt() and 0xFF) or ((raw[1].toInt() and 0xFF) shl 8)
                if (w > widest) widest = w
            }
            // The check this file exists for: an actor's cache has to hold the frames it
            // draws, or it evicts and re-decodes inside the draw loop. SpriteSet sizes
            // itself from every lump under its prefix, which is a superset of these.
            assertTrue(
                SpriteSet(wad, c.lumpPrefix).cacheBytes >= bytes,
                "${c.name} needs ${bytes / 1024} KB for its cardinal rotations, " +
                    "cache holds ${SpriteSet(wad, c.lumpPrefix).cacheBytes / 1024} KB",
            )
            if (bytes > worstBytes) { worstBytes = bytes; worstActor = c.name }
            if (gaps > 0) missing += "${c.name} ($gaps frame/rotation gaps)"
            println(
                "  %-14s %2d frames, %3d lumps held, %4d KB decoded, widest %d px"
                    .format(c.name, frames.size, held.size, bytes / 1024, widest)
            )
        }

        println("  worst actor: $worstActor at ${worstBytes / 1024} KB, and its cache is sized to hold it")
        if (missing.isNotEmpty()) println("  gaps: " + missing.joinToString("; "))
    }
}
