// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Massimo Nastasi
package io.github.massimonastasi.proflw

import java.io.InputStream
import java.io.OutputStream

internal object ImportLimits {
    const val MAX_FILE_BYTES = 128L * 1024 * 1024
    const val MAX_PHOTO_PIXELS = 4L * 1024 * 1024

    fun copy(input: InputStream, output: OutputStream, limit: Long = MAX_FILE_BYTES) {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            total += count
            require(total <= limit) { "Import exceeds size limit" }
            output.write(buffer, 0, count)
        }
    }

    fun photoSample(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        require(width > 0 && height > 0 && targetWidth > 0 && targetHeight > 0)
        var sample = 1
        fun pixels(): Long = ((width.toLong() + sample - 1) / sample) *
            ((height.toLong() + sample - 1) / sample)
        while (pixels() > MAX_PHOTO_PIXELS ||
            (width.toLong() / (sample.toLong() * 2) >= targetWidth &&
                height.toLong() / (sample.toLong() * 2) >= targetHeight)) {
            sample *= 2
        }
        return sample
    }
}
