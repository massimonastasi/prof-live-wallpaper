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
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last uncaught exception, written to a file so the next launch can offer to report it.
 *
 * There is no crash reporting SDK here and there will not be one. Every option on the market
 * brings Play Services, a background process that wakes on its own and network traffic the
 * user never asked for - which contradicts the one promise this application makes on its
 * settings screen, would need a privacy policy that is no longer one sentence, and makes it
 * unacceptable on F-Droid. This file is the whole of the alternative: about forty lines, no
 * permission, no network, and nothing leaves the device unless the user presses send in their
 * own browser, having read what it says.
 *
 * ## Where it writes
 *
 * `filesDir`, which is private to the application and removed when it is uninstalled. Not the
 * cache: the report has to survive until the user next opens the settings, and the system is
 * free to empty a cache directory whenever it likes - including during the crash loop that
 * would produce the report worth reading.
 *
 * ## One file, overwritten
 *
 * The newest crash replaces the older one. A directory of them would need pruning, a UI to
 * choose between them and a rule for what to do when they disagree; what someone reporting a
 * problem needs is the crash that just happened, and what a maintainer needs is one trace
 * rather than nine. If the same fault recurs the file simply says so again.
 */
object CrashLog {

    private const val FILE = "last-crash.txt"

    private var installed = false

    /**
     * Chains rather than replaces: the default handler is what shows the "app has stopped"
     * dialog and ends the process. Dropping it would leave a wallpaper service that has
     * thrown and does not die, which is worse than the crash.
     *
     * Called from both entry points - the service and the settings screen - because there is
     * no Application subclass and adding one would be a class and a manifest entry to hold a
     * single line. They share a process, so whichever starts first wins and the flag makes the
     * second call free; without it the handlers would chain onto each other and one crash
     * would be written twice.
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        file(context).writeText("$when_\nthread: ${thread.name}\n\n$trace")
    }

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** The stored trace, or null if this installation has never crashed. */
    fun read(context: Context): String? =
        file(context).takeIf { it.isFile }?.runCatching { readText() }?.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /**
     * A GitHub issue, filled in and left for the user to send.
     *
     * The whole exchange happens in their browser: this builds a URL, the browser opens it,
     * and GitHub receives the URL parameters when the page loads. SettingsActivity must
     * show the payload and obtain consent before opening this URL. Publishing the issue
     * is a separate action in the browser.
     *
     * The facts included are the ones that have actually been needed to reproduce something
     * here: the device and its Android version, which build is running, the frame rate, and
     * which sprite set - a WAD the maintainer does not have explains a class of report by
     * itself. Nothing identifies the person, and there is no identifier of any kind for the
     * installation, because there is nothing to correlate reports against.
     */
    fun issueUrl(context: Context, prefs: android.content.SharedPreferences): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        val sprites = if (Settings.useUserWad(prefs)) "an imported WAD" else "bundled Freedoom"
        val trace = read(context)

        val body = issueBody(
            device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            android = "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
            app = version,
            fps = Settings.fps(prefs),
            sprites = sprites,
            trace = trace,
        )
        val title = if (trace != null) "Crash on ${android.os.Build.MODEL}" else ""
        return context.getString(R.string.repo_url) + "/issues/new" +
            "?title=" + android.net.Uri.encode(title) +
            "&body=" + android.net.Uri.encode(body)
    }

    /**
     * The body text, built from plain values so it can be read and tested without a device.
     *
     * Everything above the rule is the user's to write; everything below it is what they would
     * otherwise be asked for in the first reply.
     */
    fun issueBody(
        device: String,
        android: String,
        app: String,
        fps: Int,
        sprites: String,
        trace: String?,
    ): String = buildString {
        appendLine("<!-- What happened, and what you expected instead. -->")
        appendLine()
        appendLine()
        appendLine("---")
        appendLine("- Device: $device")
        appendLine("- Android: $android")
        appendLine("- App: $app")
        appendLine("- Frame rate: $fps fps")
        appendLine("- Sprites: $sprites")
        if (trace != null) {
            appendLine()
            appendLine("<details><summary>Last crash</summary>")
            appendLine()
            appendLine("```")
            // Truncated: a URL has a practical length limit in browsers and servers alike, and
            // the top of a stack trace is where the fault is. The whole file stays on the
            // device for anyone who asks for the rest.
            appendLine(safeTrace(trace).take(TRACE_LIMIT))
            appendLine("```")
            appendLine("</details>")
        }
    }

    /** Keep exception types and stack frames, never exception messages or file paths. */
    fun safeTrace(trace: String): String = trace.lineSequence().mapNotNull { line ->
        val trimmed = line.trim()
        when {
            trimmed.matches(Regex("at [A-Za-z0-9_.$/]+\\([^\\r\\n/\\\\]*\\)")) -> trimmed
            else -> Regex("^(?:Caused by: |Suppressed: )?([A-Za-z_$][A-Za-z0-9_.$]*(?:Exception|Error))(?::|$)")
                .find(trimmed)?.groupValues?.get(1)
        }
    }.joinToString("\n")

    const val TRACE_LIMIT = 4000
}
