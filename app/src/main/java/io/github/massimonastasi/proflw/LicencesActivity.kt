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

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/**
 * What this application is built from, and under what terms.
 *
 * The text is read from the shipped NOTICE.md and LICENSE rather than restated here, so the
 * screen cannot drift from the files that carry the actual obligations. Sections are split on
 * the markdown headings; the headings become the labels the design draws above each block.
 *
 * This is a legal notice, so it is deliberately not summarised: the GPL requires the licence
 * text to travel with the work, and a paraphrase of an attribution is not an attribution.
 */
class LicencesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.licences)

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }

        val container = findViewById<LinearLayout>(R.id.blocks)
        val inflater = LayoutInflater.from(this)
        for ((label, body) in blocks()) {
            val block = inflater.inflate(R.layout.licence_block, container, false)
            block.findViewById<TextView>(R.id.block_label).text = label
            block.findViewById<TextView>(R.id.block_body).text = body
            container.addView(block)
        }
    }

/**
 * NOTICE.md split into labelled sections, then the licence itself.
 *
 * The markup is stripped rather than rendered - see clean().
 */
    private fun blocks(): List<Pair<String, String>> {
        val notice = read("NOTICE.md") ?: return listOf(
            getString(R.string.licences_heading) to getString(R.string.licences_missing, "NOTICE.md")
        )
        val out = ArrayList<Pair<String, String>>()

        // Everything before the first "## " heading is the statement of what licence this
        // work is under, which is the first thing a reader needs.
        val parts = notice.split("\n## ")
        out += getString(R.string.licences_heading) to reflow(clean(parts.first()))
        for (part in parts.drop(1)) {
            val label = clean(part.substringBefore('\n'))
            out += label to reflow(clean(part.substringAfter('\n')))
        }

        read("LICENSE")?.let { out += getString(R.string.licences_gpl) to reflow(it) }
        return out
    }

    private fun read(name: String): String? =
        try { assets.open(name).bufferedReader().use { it.readText() } } catch (e: Exception) { null }
}

/*
 * The two text passes below are outside the activity, and internal rather than private, for
 * one reason: they are the whole of what report 6549 found wrong on this screen, and a JVM
 * test can call them here without a device or a Robolectric runtime. Nothing else uses them.
 */

/**
 * Markdown to plain text, by hand.
 *
 * Not a renderer: this is four substitutions against a document whose markup is four
 * things, and a markdown library would be a dependency added so that a legal notice could
 * have bold text. What it must not do is change a word - the emphasis markers go, the
 * quote markers go, a link becomes its own label, an autolink becomes the address it
 * wraps, and nothing else moves.
 *
 * The headings go through it too. They are markdown like the rest, and a section label
 * that kept the backticks around linuxdoom-1.10 was the whole of report 6549's M-03.
 *
 * A list marker becomes a dash rather than nothing. It used to fall to the rule that removes
 * a single asterisk, which left the three conditions of the Freedoom licence looking like
 * indented prose - and, once reflow() ran over them, reading as two conditions instead of
 * three. A bullet has to survive as a bullet for the next pass to see where an item starts.
 */
internal fun clean(text: String): String = text
    .lineSequence()
    .filterNot { it.trim() == "---" || it.startsWith("# ") }
    .map { it.removePrefix("> ").removePrefix(">") }
    .joinToString("\n")
    .replace(Regex("""(?m)^(\s*)\*\s"""), "$1- ")
    .replace(Regex("""\[([^]]+)]\([^)]*\)"""), "$1")
    .replace(Regex("""<(https?://[^>]+)>"""), "$1")
    .replace("**", "")
    .replace("`", "")
    .replace(Regex("""(?<!\*)\*(?!\*)"""), "")
    .trim()

/**
 * Hard-wrapped text back into paragraphs.
 *
 * Both files this screen shows are wrapped for something that is not a phone - the GPL near
 * seventy columns for a terminal, NOTICE.md near ninety for an editor - and the phone then
 * wraps those short lines again. Every paragraph came out ragged, a line of eleven words
 * followed by a line of three. This puts a wrapped paragraph back into one line and lets the
 * TextView break it, that being the only thing here that knows how wide the screen is. No
 * word is changed and no line is dropped: the licence still travels verbatim, it is only
 * laid out.
 *
 * A line continues the one above when it is barely more indented, does not open a numbered
 * section, a lettered clause or a bullet, is no part of a table, and the line above was long
 * enough to have been wrapped rather than deliberately ended - which is what keeps headings,
 * the FSF address and the signature block on their own lines. Indentation past eight columns
 * is centring meant for eighty of them and goes; two and four are the paragraph and clause
 * indents, and stay.
 */
internal fun reflow(text: String): String {
    val out = ArrayList<String>()
    for (raw in text.replace("\t", "    ").lineSequence()) {
        val line = if (raw.indent() > CENTRED) raw.trimStart() else raw.trimEnd()
        if (line.isBlank()) {
            out += ""
            continue
        }
        val above = out.lastOrNull()
        val continues = above != null && above.isNotBlank() && above.length >= WRAPPED &&
            line.indent() <= above.indent() + HANGING && !CLAUSE.containsMatchIn(line) &&
            !line.startsWith("|") && !above.endsWith("|")
        if (continues) out[out.lastIndex] = above!!.trimEnd() + " " + line.trim() else out += line
    }
    return out.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim('\n')
}

private fun String.indent(): Int = length - trimStart().length

/** Past this, the leading spaces are centring for an eighty-column page, not structure. */
private const val CENTRED = 8

/**
 * A line this long was ended by the wrap, not by its author. Measured against the
 * source: the GPL wraps near seventy, and every line under sixty in it is a heading,
 * an address or a signature.
 */
private const val WRAPPED = 60

/**
 * How far past its first line a wrapped line may be indented and still belong to it: a list
 * item's text hangs under its marker, so its continuation sits two columns in. Applied to the
 * GPL it changes nothing - checked line for line against the file.
 */
private const val HANGING = 2

/**
 * What opens a block rather than continuing one: "a)", "3." and a bullet. "form)" and
 * "otherwise)" begin wrapped lines in sections 3 and 7 of the GPL and open nothing, which is
 * why a lettered clause here is one letter and not a word.
 */
private val CLAUSE = Regex("""^\s*(?:[a-z]\)|\d{1,2}[.)]|[-•])\s""")
