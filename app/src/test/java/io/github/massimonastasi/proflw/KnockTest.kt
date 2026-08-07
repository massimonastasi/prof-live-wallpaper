package io.github.massimonastasi.proflw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sequence that reveals the debug row: readout x4, god x2, 10 fps, 20 fps.
 *
 * It is worth a test because it is the one piece of the settings screen that remembers
 * anything between taps, and because getting it wrong fails silently - the row simply never
 * appears, and there is nothing to see in a log.
 */
class KnockTest {

    private val K = Settings.Knock

    private fun run(vararg steps: Int): Int = steps.fold(0, K::advance)

    @Test
    fun `the sequence opens the row`() {
        val at = run(K.READOUT, K.READOUT, K.READOUT, K.READOUT, K.GOD, K.GOD, K.TEN, K.TWENTY)
        assertTrue("the full sequence should complete", K.complete(at))
    }

    @Test
    fun `nothing short of the sequence opens it`() {
        // Every proper prefix, one at a time.
        for (n in 0 until K.SEQUENCE.size) {
            val at = K.SEQUENCE.take(n).fold(0, K::advance)
            assertFalse("$n steps should not complete", K.complete(at))
        }
    }

    @Test
    fun `a wrong step drops back to the start`() {
        // Three of the four readout taps, then god mode too early.
        assertEquals(0, run(K.READOUT, K.READOUT, K.READOUT, K.GOD))
        // And the sequence then has to be given in full from there.
        assertFalse(K.complete(run(K.READOUT, K.READOUT, K.READOUT, K.GOD, K.GOD, K.TEN, K.TWENTY)))
    }

    @Test
    fun `a fifth tap on the opening row counts as the first of a new attempt`() {
        // Four taps land at 4; the fifth is wrong there, but it is the opening step, so it
        // restarts the count at one rather than at nothing - three more taps, not four.
        assertEquals(1, run(K.READOUT, K.READOUT, K.READOUT, K.READOUT, K.READOUT))
        val at = run(
            K.READOUT, K.READOUT, K.READOUT, K.READOUT, K.READOUT,
            K.READOUT, K.READOUT, K.READOUT, K.GOD, K.GOD, K.TEN, K.TWENTY,
        )
        assertTrue(K.complete(at))
    }

    @Test
    fun `only the readout row starts it`() {
        // Nothing else moves off zero, which is what lets the screen ignore the other
        // controls entirely until an attempt is under way.
        for (step in intArrayOf(K.GOD, K.TEN, K.TWENTY)) {
            assertEquals("step $step should not start the sequence", 0, K.advance(0, step))
        }
        assertEquals(1, K.advance(0, K.READOUT))
    }

    @Test
    fun `the frame rate steps are not interchangeable`() {
        // 20 fps before 10 fps is not the sequence.
        val at = run(K.READOUT, K.READOUT, K.READOUT, K.READOUT, K.GOD, K.GOD, K.TWENTY, K.TEN)
        assertFalse(K.complete(at))
    }

    @Test
    fun `the middle frame rate ends the attempt`() {
        // 15 fps shares its listener with the two that are in the sequence, so it has to be
        // told apart from them: standing in for 20 fps would open the row.
        val at = run(K.READOUT, K.READOUT, K.READOUT, K.READOUT, K.GOD, K.GOD, K.TEN, K.NONE)
        assertFalse(K.complete(at))
        assertEquals(0, at)
    }

    @Test
    fun `the switches are left as they were found`() {
        // Four taps on a switch and two on another are both even, so the sequence borrows
        // nothing it does not give back. This is a property of the table, so assert it there.
        val counts = K.SEQUENCE.toList().groupingBy { it }.eachCount()
        assertEquals(0, counts.getValue(K.READOUT) % 2)
        assertEquals(0, counts.getValue(K.GOD) % 2)
    }
}
