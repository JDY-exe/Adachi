package com.adachi.lockdown

import com.adachi.lockdown.data.UnlockState
import com.adachi.lockdown.unlock.UnlockManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class UnlockManagerTest {

    private val today: LocalDate = LocalDate.of(2026, 7, 19) // Sunday

    // ---------- ISO week ----------

    @Test
    fun `iso week format`() {
        assertTrue(UnlockManager.isoWeekId(today).matches(Regex("\\d{4}-W\\d{2}")))
    }

    @Test
    fun `sunday and saturday share a week, monday starts a new one`() {
        val sun = LocalDate.of(2026, 7, 19)
        val sat = LocalDate.of(2026, 7, 18)
        val mon = LocalDate.of(2026, 7, 20)
        assertEquals(UnlockManager.isoWeekId(sun), UnlockManager.isoWeekId(sat))
        assertNotEquals(UnlockManager.isoWeekId(sun), UnlockManager.isoWeekId(mon))
    }

    @Test
    fun `iso year boundary`() {
        assertEquals("2026-W53", UnlockManager.isoWeekId(LocalDate.of(2027, 1, 1)))
        assertEquals("2027-W01", UnlockManager.isoWeekId(LocalDate.of(2027, 1, 4)))
    }

    // ---------- weekly unlock ----------

    @Test
    fun `unlock can be spent once per week`() {
        val monday = LocalDate.of(2026, 7, 20)
        var s = UnlockState()
        assertTrue(UnlockManager.canSpendWeeklyUnlock(s, monday))
        s = UnlockManager.spendWeeklyUnlock(s, 1_000_000L, monday)
        assertFalse(UnlockManager.canSpendWeeklyUnlock(s, monday))
        assertFalse(UnlockManager.canSpendWeeklyUnlock(s, monday.plusDays(3)))  // same ISO week
        // Next week frees up again.
        assertTrue(UnlockManager.canSpendWeeklyUnlock(s, monday.plusDays(7)))
    }

    @Test
    fun `spending unlock activates window for 30 minutes`() {
        val now = 1_700_000_000_000L
        val s = UnlockManager.spendWeeklyUnlock(UnlockState(), now, today)
        assertTrue(UnlockManager.isActive(s, now + 29 * 60 * 1000))
        assertFalse(UnlockManager.isActive(s, now + 31 * 60 * 1000))
        assertEquals(UnlockManager.UNLOCK_DURATION_MS, UnlockManager.remainingMs(s, now))
    }

    @Test
    fun `tamper consumption marks week without activating window`() {
        val now = 1_700_000_000_000L
        val s = UnlockManager.consumeForTamper(UnlockState(), today)
        assertFalse(UnlockManager.canSpendWeeklyUnlock(s, today))
        assertFalse(UnlockManager.isActive(s, now))
        // Idempotent.
        val s2 = UnlockManager.consumeForTamper(s, today)
        assertEquals(s.consumedWeeks, s2.consumedWeeks)
    }

    // ---------- malfunction pause ----------

    @Test
    fun `malfunction pause once per day and does not consume weekly unlock`() {
        var s = UnlockState()
        assertTrue(UnlockManager.canMalfunctionPause(s, today))
        s = UnlockManager.spendMalfunctionPause(s, 1000L, today)
        assertFalse(UnlockManager.canMalfunctionPause(s, today))
        assertTrue(UnlockManager.canMalfunctionPause(s, today.plusDays(1)))
        assertTrue(UnlockManager.canSpendWeeklyUnlock(s, today))
    }

    // ---------- grace period ----------

    @Test
    fun `grace period lasts 48 hours after provisioning`() {
        val provisionedAt = 5_000_000L
        val s = UnlockState(provisionedAtMs = provisionedAt)
        assertTrue(UnlockManager.inGracePeriod(s, provisionedAt + 47L * 3600 * 1000))
        assertFalse(UnlockManager.inGracePeriod(s, provisionedAt + 49L * 3600 * 1000))
        assertFalse(UnlockManager.inGracePeriod(UnlockState(provisionedAtMs = 0), 0))
    }

    // ---------- clock watermark ----------

    @Test
    fun `first watermark initializes clean`() {
        val check = UnlockManager.updateWatermark(null, 1000L, 100L)
        assertFalse(check.tampered)
        assertEquals(1000L, check.state.utcWatermarkMs)
    }

    @Test
    fun `clock rollback is flagged`() {
        val s = UnlockState(utcWatermarkMs = 1_000_000L, watermarkElapsedMs = 500L)
        val check = UnlockManager.updateWatermark(s, 1_000_000L - UnlockManager.CLOCK_TOLERANCE_MS - 1, 600L)
        assertTrue(check.tampered)
    }

    @Test
    fun `forward jump within same uptime is flagged`() {
        val s = UnlockState(utcWatermarkMs = 1_000_000L, watermarkElapsedMs = 500L)
        // elapsed advanced 1s but utc advanced an hour.
        val check = UnlockManager.updateWatermark(s, 1_000_000L + 3600_000L, 501L)
        assertTrue(check.tampered)
    }

    @Test
    fun `normal passage of time is not flagged`() {
        val s = UnlockState(utcWatermarkMs = 1_000_000L, watermarkElapsedMs = 500L)
        // 10 minutes pass on both clocks.
        val check = UnlockManager.updateWatermark(s, 1_000_000L + 600_000L, 500L + 600_000L)
        assertFalse(check.tampered)
        assertEquals(1_000_000L + 600_000L, check.state.utcWatermarkMs)
    }

    @Test
    fun `elapsed before anchor means no forward-jump check (post-boot safety)`() {
        val s = UnlockState(utcWatermarkMs = 1_000_000L, watermarkElapsedMs = 500_000L)
        // Right after reboot elapsed is small; utc may be far ahead legitimately (device was off).
        val check = UnlockManager.updateWatermark(s, 1_000_000L + 86_400_000L, 1_000L)
        assertFalse(check.tampered)
    }

    @Test
    fun `reanchor after boot keeps rollback detection`() {
        val s = UnlockState(utcWatermarkMs = 1_000_000L, watermarkElapsedMs = 999_999L)
        val rolled = UnlockManager.reanchorAfterBoot(s, 50_000L, 10L)
        assertTrue(rolled.tampered)
        val fine = UnlockManager.reanchorAfterBoot(s, 2_000_000L, 10L)
        assertFalse(fine.tampered)
        assertEquals(2_000_000L, fine.state.utcWatermarkMs)
        assertEquals(10L, fine.state.watermarkElapsedMs)
    }
}
