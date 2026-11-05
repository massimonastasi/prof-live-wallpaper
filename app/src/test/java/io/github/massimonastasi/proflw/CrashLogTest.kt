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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The report body, which is the one part of problem reporting with a decision in it.
 *
 * What is not tested here is the file: writing a trace to `filesDir` and reading it back is
 * the platform's own code doing exactly what it says, and a test for it would only assert
 * that `File.writeText` works. The decisions are which facts go in, that a report without a
 * crash does not pretend to have one, and that a runaway trace cannot grow the URL without
 * limit.
 */
class CrashLogTest {

    @Test
    fun `shared traces omit messages paths and addresses`() {
        val text = body("java.lang.IllegalArgumentException: content://private/photo user@example.com\n" +
            "\tat io.github.massimonastasi.proflw.Scene.tick(Scene.kt:12)\n" +
            "Caused by: java.io.IOException: /storage/emulated/0/private.jpg")
        assertTrue("IllegalArgumentException" in text)
        assertTrue("Scene.kt:12" in text)
        assertFalse("user@example.com" in text)
        assertFalse("content://" in text)
        assertFalse("private.jpg" in text)
    }

    private fun body(trace: String?) = CrashLog.issueBody(
        device = "Google Pixel 6a",
        android = "16 (API 36)",
        app = "0.6",
        fps = 20,
        sprites = "bundled Freedoom",
        trace = trace,
    )

    @Test
    fun `the report carries what would otherwise be asked for in the first reply`() {
        val text = body(null)
        for (fact in listOf("Google Pixel 6a", "16 (API 36)", "0.6", "20 fps", "bundled Freedoom")) {
            assertTrue(fact in text, "the report does not mention $fact")
        }
    }

    @Test
    fun `a report with no crash does not claim one`() {
        val text = body(null)
        assertFalse("Last crash" in text, "a report with no trace opened a crash section")
        assertFalse("```" in text, "a report with no trace opened a code block")
    }

    @Test
    fun `a crash is attached, folded away rather than filling the page`() {
        val text = body("java.lang.IllegalStateException: surface gone\n\tat Scene.tick")
        assertTrue("<details>" in text, "the trace is not collapsible")
        assertTrue("IllegalStateException" in text, "the trace is not in the report")
    }

    /**
     * A URL has a practical length limit in browsers and servers alike, so a trace that grows
     * without bound - a StackOverflowError prints thousands of frames - must not take the
     * report with it. Percent-encoding roughly triples what is measured here, which is why the
     * limit is well under any of the limits it is protecting.
     */
    @Test
    fun `a runaway trace is cut, and the rest of the report survives it`() {
        val huge = "\tat io.github.massimonastasi.proflw.Scene.tick(Scene.kt:1)\n".repeat(5000)
        val text = body(huge)

        assertTrue(huge.length > CrashLog.TRACE_LIMIT, "the test's trace is not big enough to cut")
        assertTrue(
            text.length < CrashLog.TRACE_LIMIT + 1000,
            "the body grew to ${text.length}: the trace was not cut",
        )
        assertTrue("- Device: Google Pixel 6a" in text, "cutting the trace lost the facts")
        assertTrue("</details>" in text, "cutting the trace lost the closing tag")
    }
}
