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

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.nio.channels.FileChannel

/**
 * The user's own IWAD: imported, checked, and used in place of the bundled assets.
 *
 * **Nothing here is ever redistributed.** The file is read from the device the user chose it
 * on, copied into this application's private storage so it can be memory-mapped, and never
 * leaves. That is the whole legal position: a commercial IWAD may not be shipped, but there
 * is nothing wrong with reading one the user already owns.
 *
 * Copied rather than read through the content resolver because the loader maps the file and
 * seeks around it constantly â€” random access through a ContentResolver stream is slow, and a
 * document URI's permission can be revoked between one launch and the next.
 */
object WadStore {

    private const val DIR = "wads"
    private const val TAG = "ProfLW"

    private const val KEY_NAME = "wad_name"
    private const val KEY_FORMAT = "wad_format"
    private const val KEY_STALE = "wad_stale"

    /**
     * Which set of reduction rules the stored copy was built by. **Raise this whenever those
     * rules change**, and see WadStoreTest, which fails when they change without it.
     *
     * The copy in files/wads is not the user's file, it is what [WadReducer] made of it: the
     * lumps this wallpaper reads, chosen by rules that live in the app. Those rules move -
     * FloorPicker went from picking five flats by chroma to picking nine by colour family -
     * and the copy does not move with them. Measured on a real device: a full IWAD reduced by
     * the old rules kept nine flats, and two of the nine the current rules want were simply
     * not in the file. The floors were being chosen from a pool that no longer matched, and
     * nothing about it looked broken.
     *
     * 1: the original, flats chosen by chroma for five skill levels.
     * 2: flats filtered by band, channel ceiling and relative contrast, all of them kept.
     */
    const val REDUCTION_FORMAT = 2

    /**
     * The one imported WAD, or null when the bundled assets are in use.
     *
     * A copy built by older rules is deleted here rather than reported, because there is
     * nothing useful to do with it: the original file is the user's and its permission may be
     * long gone, so it cannot be reduced again. Deleting inside the accessor is deliberate -
     * it is the single door every caller comes through, and a check placed beside it instead
     * would be one more thing to remember at each call site.
     */
    fun active(context: Context): File? {
        val file = File(File(context.filesDir, DIR), "active.wad")
        if (!file.isFile || file.length() <= 0) return null

        val prefs = Settings.of(context)
        if (prefs.getInt(KEY_FORMAT, 1) == REDUCTION_FORMAT) return file

        Log.i(TAG, "discarding a WAD reduced by format ${prefs.getInt(KEY_FORMAT, 1)}")
        file.delete()
        // Remembered so the settings screen can say what happened. Silently losing a file the
        // user imported is worse than the stale floors it was causing.
        prefs.edit { remove(KEY_NAME); remove(KEY_FORMAT); putBoolean(KEY_STALE, true) }
        return null
    }

    /** True once, after a stale copy was discarded, so the screen can explain it. */
    fun takeStaleNotice(context: Context): Boolean {
        val prefs = Settings.of(context)
        if (!prefs.getBoolean(KEY_STALE, false)) return false
        prefs.edit { remove(KEY_STALE) }
        return true
    }

    /**
     * What the user called it, so the settings can name the file rather than describe it.
     *
     * The copy is always stored as active.wad, which is right for the loader and useless to
     * read: someone who has imported two WADs over a week needs to see which one is in, and
     * "your own WAD" answers a question nobody asked.
     */
    fun name(context: Context): String? =
        Settings.of(context).getString(KEY_NAME, null)?.takeIf { active(context) != null }

    /**
     * Reads the display name the document provider offers.
     *
     * A content URI is not a path and its last segment is often an opaque id, so the name
     * has to be asked for rather than parsed out.
     */
    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    fun clear(context: Context) {
        active(context)?.delete()
        Settings.of(context).edit { remove(KEY_NAME); remove(KEY_FORMAT); remove(KEY_STALE) }
    }

    /**
     * Copies [uri] in, checks it, and keeps it only if it can actually drive the wallpaper.
     *
     * Returns null on success, or a reason to show the user. A rejected file is deleted
     * rather than left behind: a WAD is tens of megabytes, and one that cannot be used is
     * just occupied storage.
     */
    fun import(context: Context, uri: Uri): String? {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val staged = File(dir, "staged.wad")

        try {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return context.getString(R.string.wad_unreadable)
                staged.outputStream().use { input.copyTo(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WAD copy failed", e)
            staged.delete()
            return context.getString(R.string.wad_unreadable)
        }

        val problem = check(context, staged)
        if (problem != null) {
            staged.delete()
            return problem
        }

        // Kept in reduced form, never whole. An IWAD is mostly maps, wall textures, menu
        // graphics, music and sound, none of which a wallpaper draws: keeping the original
        // would occupy tens of megabytes of the user's storage to use about two percent of
        // it. The file they chose is untouched where it lives; this is our copy.
        val target = File(dir, "active.wad")
        val full = staged.length()
        val reduced = try {
            val wad = staged.inputStream().use { s ->
                WadFile(s.channel.map(FileChannel.MapMode.READ_ONLY, 0, staged.length()))
            }
            WadReducer.reduce(wad, target)
        } catch (e: Exception) {
            Log.w(TAG, "WAD reduction failed", e)
            -1
        }
        staged.delete()

        if (reduced <= 0) {
            target.delete()
            return context.getString(R.string.wad_unreadable)
        }
        // Stamped with the rules that produced it, so a later version can tell whether what
        // is on disk is still what it would build today.
        Settings.of(context).edit {
            putString(KEY_NAME, displayName(context, uri) ?: context.getString(R.string.wad_unnamed))
            putInt(KEY_FORMAT, REDUCTION_FORMAT)
            remove(KEY_STALE)
        }
        Log.i(TAG, "WAD imported: $reduced lumps, ${full / 1024} KB -> ${target.length() / 1024} KB")
        return null
    }

    /**
     * Opens the file the way the wallpaper will and asks it for what the wallpaper needs.
     *
     * Checking the header alone would accept a WAD full of Heretic or Hexen sprites, whose
     * names are entirely different, and the wallpaper would come up empty. So the test is
     * the real one: the palette must be there, and the creatures must resolve.
     */
    private fun check(context: Context, file: File): String? = try {
        val wad = file.inputStream().use { s ->
            WadFile(s.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()))
        }

        var present = 0
        var decoded = 0
        for (prefix in GameData.spritePrefixes) {
            val set = SpriteSet(wad, prefix)
            if (set.frameCount == 0) continue
            present++
            // Names are not enough. A lump can carry the right name and contents the patch
            // decoder cannot read, and that failure would otherwise surface inside the draw
            // loop rather than here. Every frame of every creature is decoded now, once,
            // where a rejection is still possible.
            for (f in 0 until set.frameCount) {
                val packed = set.resolve(f, 1)
                if (packed >= 0 && set.sprite(packed shr 1) != null) decoded++
            }
        }

        when {
            present == 0 -> context.getString(R.string.wad_no_sprites)
            present < GameData.spritePrefixes.size / 2 -> context.getString(R.string.wad_too_few, present)
            decoded == 0 -> context.getString(R.string.wad_undecodable)
            else -> null
        }
    } catch (e: WadFile.NotAWadException) {
        // The header the file turned out to have is diagnostics, not an explanation. It goes to
        // the log, where it is worth something; the user is told what to pick instead.
        Log.w(TAG, "not a WAD: ${e.message}")
        context.getString(R.string.wad_not_a_wad)
    } catch (e: Exception) {
        Log.w(TAG, "WAD check failed", e)
        context.getString(R.string.wad_unreadable)
    }
}
