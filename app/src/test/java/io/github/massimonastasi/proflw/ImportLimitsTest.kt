package io.github.massimonastasi.proflw

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImportLimitsTest {
    @Test fun `panoramic images remain inside pixel budget`() {
        for ((width, height) in listOf(200000 to 1000, 1000 to 200000, 12000 to 9000)) {
            val sample = ImportLimits.photoSample(width, height, 1080, 2400)
            assertTrue(((width.toLong() + sample - 1) / sample) *
                ((height.toLong() + sample - 1) / sample) <= ImportLimits.MAX_PHOTO_PIXELS)
        }
    }

    @Test fun `small photos keep their resolution`() {
        assertEquals(1, ImportLimits.photoSample(640, 480, 1080, 2400))
    }

    @Test fun `oversized input stops before writing beyond limit`() {
        val output = ByteArrayOutputStream()
        assertFailsWith<IllegalArgumentException> {
            ImportLimits.copy(ByteArrayInputStream(ByteArray(20000)), output, 9000)
        }
        assertTrue(output.size() <= 9000)
    }

    @Test fun `overflowing WAD directory is rejected before allocation`() {
        val bytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("IWAD".toByteArray()).putInt(Int.MAX_VALUE).putInt(12)
        assertFailsWith<WadFile.NotAWadException> { WadFile(bytes) }
    }

    @Test fun `WAD lumps outside the file are rejected`() {
        val bytes = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("IWAD".toByteArray()).putInt(1).putInt(12)
        bytes.putInt(Int.MAX_VALUE).putInt(768).put("PLAYPAL\u0000".toByteArray())
        assertFailsWith<WadFile.NotAWadException> { WadFile(bytes) }
    }
}
