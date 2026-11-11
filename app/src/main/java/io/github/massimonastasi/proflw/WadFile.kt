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

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A decoded image, in ARGB. Plain data with no Android types, so it can be tested on the JVM.
 *
 * [xOffset]/[yOffset] come from the patch format and mark the anchor point (for monsters,
 * their feet). Ignoring them makes sprites jitter while animating, because frames have
 * different sizes from one another.
 */
class Patch(
    val width: Int,
    val height: Int,
    val xOffset: Int,
    val yOffset: Int,
    val pixels: IntArray,
)


/**
 * Reader for WAD files in the classic IWAD format.
 *
 * A single load path serves both the bundled assets and a user-supplied IWAD: lump names
 * are identical across every IWAD of this family because the original engine hardcodes them
 * in sprnames[] (info.c), so swapping the sprites works by construction.
 *
 * ponytail: column-based patch format only, no PNG or TEXTURES lumps from the later source
 * ports. Every IWAD this reads uses the classic format.
 */
class WadFile(private val buf: ByteBuffer) {

    class NotAWadException(message: String) : Exception(message)

    private val lumpPos: IntArray
    private val lumpSize: IntArray
    private val lumpName: Array<String>
    private val byName: HashMap<String, Int>

    /** PLAYPAL palette 0, already converted to ARGB. */
    private val palette = IntArray(256)

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        if (buf.capacity() < 12) throw NotAWadException("file too short")
        val magic = StringBuilder(4).apply {
            for (i in 0..3) append(buf.get(i).toInt().toChar())
        }.toString()
        if (magic != "IWAD" && magic != "PWAD") throw NotAWadException("header '$magic', expected IWAD or PWAD")

        val count = buf.getInt(4)
        val dirOffset = buf.getInt(8)
        if (count !in 1..100000 || dirOffset < 12 || dirOffset.toLong() + count.toLong() * 16 > buf.limit()) {
            throw NotAWadException("invalid directory ($count lumps @ $dirOffset)")
        }

        lumpPos = IntArray(count)
        lumpSize = IntArray(count)
        lumpName = Array(count) { "" }
        byName = HashMap(count * 2)

        val name = StringBuilder(8)
        for (i in 0 until count) {
            val e = dirOffset + i * 16
            lumpPos[i] = buf.getInt(e)
            lumpSize[i] = buf.getInt(e + 4)
            if (lumpPos[i] < 0 || lumpSize[i] < 0 ||
                lumpPos[i].toLong() + lumpSize[i] > buf.limit()) {
                throw NotAWadException("invalid lump bounds")
            }
            name.setLength(0)
            for (c in 0..7) {
                val b = buf.get(e + 8 + c).toInt() and 0xFF
                if (b == 0) break
                name.append(b.toChar())
            }
            lumpName[i] = name.toString()
            // A duplicate name is not an error: the last one wins, as in the original engine.
            byName[lumpName[i]] = i
        }

        val pal = byName["PLAYPAL"] ?: throw NotAWadException("PLAYPAL missing")
        if (lumpSize[pal] < 768) throw NotAWadException("PLAYPAL too short")
        val p = lumpPos[pal]
        for (i in 0 until 256) {
            val r = buf.get(p + i * 3).toInt() and 0xFF
            val g = buf.get(p + i * 3 + 1).toInt() and 0xFF
            val b = buf.get(p + i * 3 + 2).toInt() and 0xFF
            palette[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        // PLAYPAL's other thirteen palettes are the damage, item and radiation-suit ramps.
        // Only palette 0 is read: the death wash used to be derived from the damage ramp and
        // is now a palette entry chosen directly, which left that derivation — a scan of all
        // 256 colours at load — computing a value nothing read.
    }

    val lumpCount: Int get() = lumpPos.size

    /** A colour straight out of palette 0, so callers pick from the active WAD's own set. */
    fun paletteColor(index: Int): Int = palette[index and 0xFF]

    fun indexOf(name: String): Int = byName[name] ?: -1

    fun nameAt(index: Int): String = lumpName[index]

    fun sizeAt(index: Int): Int = lumpSize[index]

    /** The lump's bytes, undecoded. Used when copying lumps into a reduced WAD. */
    fun rawLump(index: Int): ByteArray {
        val out = ByteArray(lumpSize[index])
        val p = lumpPos[index]
        for (i in out.indices) out[i] = buf.get(p + i)
        return out
    }

    /**
     * Decoded size of a patch in bytes, read from its four-byte header without decoding it.
     *
     * A sprite cache sized by a fixed constant is a cache sized for the smallest actor: the
     * Spider Mastermind's frames come to 2542 KB against a flat 1024 KB budget, so it evicted
     * and re-decoded every frame it drew. This is how an actor is asked what it needs.
     */
    /**
     * Height of a patch in pixels, from its header, without decoding it.
     *
     * Used to judge how much room a corpse takes before anything has died: decoding the
     * Overlord's resting frame to measure it would cost two and a half megabytes at load, to
     * read a number that sits in the first four bytes.
     */
    fun patchHeight(index: Int): Int {
        if (lumpSize[index] < 4) return 0
        val p = lumpPos[index]
        return (buf.get(p + 2).toInt() and 0xFF) or ((buf.get(p + 3).toInt() and 0xFF) shl 8)
    }

    fun patchBytes(index: Int): Int {
        val p = lumpPos[index]
        if (lumpSize[index] < 4) return 0
        val w = (buf.get(p).toInt() and 0xFF) or ((buf.get(p + 1).toInt() and 0xFF) shl 8)
        val h = (buf.get(p + 2).toInt() and 0xFF) or ((buf.get(p + 3).toInt() and 0xFF) shl 8)
        require(w in 1..4096 && h in 1..4096 && w.toLong() * h <= 1048576) { "patch too large" }
        return w * h * 4
    }

    /** Indices of every lump whose name starts with [prefix]. */
    fun lumpsStartingWith(prefix: String): List<Int> =
        lumpName.indices.filter { lumpName[it].startsWith(prefix) }

    /**
     * Index of a floor flat by name, or -1. Flats live between the F_START and F_END
     * markers and are raw 64x64 palette indices with no header, unlike the patch format.
     */
    fun flatIndex(name: String): Int {
        val start = byName["F_START"] ?: return -1
        val end = byName["F_END"] ?: return -1
        for (i in start + 1 until end) {
            if (lumpName[i] == name && lumpSize[i] == FLAT_SIZE * FLAT_SIZE) return i
        }
        return -1
    }

    /** Decodes a flat: 4096 raw bytes, one palette index per pixel. */
    fun decodeFlat(index: Int): Patch {
        require(lumpSize[index] == FLAT_SIZE * FLAT_SIZE) { "invalid flat size" }
        val p = lumpPos[index]
        val pixels = IntArray(FLAT_SIZE * FLAT_SIZE)
        for (i in pixels.indices) pixels[i] = palette[buf.get(p + i).toInt() and 0xFF]
        return Patch(FLAT_SIZE, FLAT_SIZE, 0, 0, pixels)
    }

    /**
     * Decodes a lump in the patch format.
     *
     * The format is natively sparse: each column is a list of posts (topdelta, length,
     * pixels) terminated by 0xFF, and uncovered areas stay transparent. No colour key
     * to deal with.
     */
    fun decodePatch(index: Int): Patch {
        require(lumpSize[index] >= 8) { "truncated patch" }
        val p = lumpPos[index]
        val w = buf.getShort(p).toInt() and 0xFFFF
        val h = buf.getShort(p + 2).toInt() and 0xFFFF
        val xOff = buf.getShort(p + 4).toInt()      // signed
        val yOff = buf.getShort(p + 6).toInt()
        require(w in 1..4096 && h in 1..4096) { "patch ${lumpName[index]} has size $w x $h" }
        require(w.toLong() * h <= 1048576 && 8L + w * 4L <= lumpSize[index]) { "invalid patch size" }
        val end = p + lumpSize[index]

        val pixels = IntArray(w * h)                 // 0 = transparent
        for (x in 0 until w) {
            val offset = buf.getInt(p + 8 + x * 4)
            require(offset >= 8 + w * 4 && offset < lumpSize[index]) { "invalid patch column" }
            var o = p + offset
            while (true) {
                require(o < end) { "unterminated patch column" }
                val topDelta = buf.get(o).toInt() and 0xFF
                if (topDelta == 0xFF) break
                require(o.toLong() + 4 <= end) { "truncated patch post" }
                val len = buf.get(o + 1).toInt() and 0xFF
                require(o.toLong() + len + 4 <= end) { "truncated patch pixels" }
                o += 3                                // skip the unused byte
                for (k in 0 until len) {
                    val y = topDelta + k
                    if (y < h) pixels[y * w + x] = palette[buf.get(o + k).toInt() and 0xFF]
                }
                o += len + 1                          // skip the trailing unused byte
            }
        }
        return Patch(w, h, xOff, yOff, pixels)
    }

    private companion object {
        const val FLAT_SIZE = 64
    }
}
