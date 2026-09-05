package io.github.massimonastasi.proflw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two text passes behind the licences screen, which is where report 6549 found M-03 and
 * where the GPL came out ragged. Both are pure string work, so they are checked here rather
 * than by reading the screen again.
 */
class LicenceTextTest {

    // ---------------------------------------------------------------- clean

    @Test
    fun `a heading keeps its words and loses its backticks`() {
        assertEquals(
            "id Software - linuxdoom-1.10 source release",
            clean("id Software - `linuxdoom-1.10` source release"),
        )
    }

    @Test
    fun `an autolink becomes the address it wraps`() {
        assertEquals(
            "Source: https://github.com/id-Software/DOOM, released under GPL-2.0.",
            clean("Source: <https://github.com/id-Software/DOOM>, released under GPL-2.0."),
        )
    }

    @Test
    fun `a labelled link becomes its label`() {
        assertEquals("full text is in LICENSE.", clean("full text is in [LICENSE](LICENSE)."))
    }

    // --------------------------------------------------------------- reflow

    @Test
    fun `a wrapped paragraph becomes one line`() {
        val wrapped = """
            |  The licenses for most software are designed to take away your
            |freedom to share and change it.  By contrast, the GNU General Public
            |License is intended to guarantee your freedom.
        """.trimMargin()
        assertEquals(
            "  The licenses for most software are designed to take away your freedom to " +
                "share and change it.  By contrast, the GNU General Public License is " +
                "intended to guarantee your freedom.",
            reflow(wrapped),
        )
    }

    @Test
    fun `a lettered clause does not run into the one above it`() {
        val clauses = """
            |    a) You must cause the modified files to carry prominent notices
            |    stating that you changed the files.
            |    b) You must cause any work that you distribute or publish to be
            |    licensed as a whole at no charge.
        """.trimMargin()
        assertEquals(2, reflow(clauses).lines().size)
        assertTrue(reflow(clauses).lines()[1].startsWith("    b)"))
    }

    /** "form)" and "otherwise)" open no clause, which is why CLAUSE takes a single letter. */
    @Test
    fun `a word ending in a bracket is not a clause`() {
        val wrapped = """
            |the source code distributed need not include anything that is normally
            |form) with the major components of the operating system.
        """.trimMargin()
        assertEquals(1, reflow(wrapped).lines().size)
    }

    @Test
    fun `headings and the signature block keep their own lines`() {
        val heading = """
            |                            Preamble
            |
            |  The licenses for most software are designed to take away your
            |freedom to share and change it.
        """.trimMargin()
        val lines = reflow(heading).lines()
        assertEquals("Preamble", lines[0])
        assertEquals("", lines[1])
        assertTrue(lines[2].endsWith("freedom to share and change it."))
    }

    @Test
    fun `blank lines survive as the paragraph breaks they are`() {
        assertEquals("one\n\ntwo", reflow("one\n\n\n\ntwo"))
    }

    /** NOTICE.md carries one table, and a row is a row however long the one above it was. */
    @Test
    fun `table rows keep one line each`() {
        val table = """
            || Taken | Origin |
            ||---|---|
            || The skill names, and G_PlayerReborn handing back the pistol | g_game.c |
            || Fixed-point movement, friction and stop speed | p_mobj.c |
        """.trimMargin()
        assertEquals(4, reflow(table).lines().size)
    }

    /**
     * The Freedoom licence sets three conditions, and it has to still set three. Reflowing
     * them without a marker to see turned the second and third into one.
     *
     * The sentence above the list is not decoration: clean() trims, so a block that opened on
     * an indented bullet would lose that indent and the item would not gather its own second
     * line. NOTICE.md introduces the list, as licences do, and the test reads what it says.
     */
    @Test
    fun `the three conditions of a bullet list stay three`() {
        val list = clean(
            """
            |> Redistribution and use in source and binary forms, with or without modification,
            |> are permitted provided that the following conditions are met:
            |>
            |>   * Redistributions of source code must retain the above copyright notice, this
            |>     list of conditions and the following disclaimer.
            |>   * Redistributions in binary form must reproduce the above copyright notice,
            |>     this list of conditions and the following disclaimer in the documentation
            |>     and/or other materials provided with the distribution.
            |>   * Neither the name of the Freedoom project nor the names of its contributors
            |>     may be used to endorse or promote products derived from this software.
            """.trimMargin()
        )
        val items = reflow(list).lines().filter { it.trim().startsWith("- ") }
        assertEquals(3, items.size)
        assertTrue(items[1].endsWith("provided with the distribution."))
        assertTrue(items[2].endsWith("derived from this software."))
    }

    @Test
    fun `a paragraph does not run into the table under it`() {
        val text = """
            |the original does, and the table below says which of them came from where in
            |that source, file by file.
            || Taken | Origin |
        """.trimMargin()
        val lines = reflow(text).lines()
        assertEquals(2, lines.size)
        assertTrue(lines[1].startsWith("| Taken"))
    }

    /**
     * The one thing this pass must never do. A licence that loses or gains a word is not the
     * licence any more, so the words are compared against the source with the layout removed.
     */
    @Test
    fun `no word is added, dropped or changed`() {
        fun words(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        assertEquals(words(GPL_EXCERPT), words(reflow(GPL_EXCERPT)))
        assertFalse(reflow(GPL_EXCERPT).contains("\n\n\n"))
    }

    private companion object {
        /** Kept here rather than as a fixture: it is eleven lines and it never changes. */
        val GPL_EXCERPT = """
            |                    GNU GENERAL PUBLIC LICENSE
            |                       Version 2, June 1991
            |
            | Copyright (C) 1989, 1991 Free Software Foundation, Inc.,
            | <https://fsf.org/>
            | Everyone is permitted to copy and distribute verbatim copies
            | of this license document, but changing it is not allowed.
            |
            |                            Preamble
            |
            |  The licenses for most software are designed to take away your
            |freedom to share and change it.  By contrast, the GNU General Public
            |License is intended to guarantee your freedom to share and change free
            |software--to make sure the software is free for all its users.
            |
            |    a) You must cause the modified files to carry prominent notices
            |    stating that you changed the files and the date of any change.
            |
            |    b) You must cause any work that you distribute or publish, that in
            |    whole or in part contains or is derived from the Program, to be
            |    licensed as a whole at no charge to all third parties.
        """.trimMargin()
    }
}
