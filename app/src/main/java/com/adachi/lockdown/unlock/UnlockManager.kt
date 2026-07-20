package com.adachi.lockdown.unlock

import com.adachi.lockdown.data.UnlockState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields

/**
 * Pure weekly-unlock bookkeeping and clock-tamper watermark logic.
 *
 *  - One emergency unlock per ISO week; spending it pauses enforcement for 30 min.
 *  - One 10-min "malfunction pause" per local day; never touches the weekly unlock.
 *  - 48h grace period after device-owner provisioning.
 *  - UTC watermark + monotonic anchor to detect manual clock changes
 *    (timezone changes do NOT affect UTC, so travel is unaffected).
 */
object UnlockManager {

    const val UNLOCK_DURATION_MS = 30L * 60 * 1000
    const val MALFUNCTION_PAUSE_MS = 10L * 60 * 1000
    const val GRACE_PERIOD_MS = 48L * 3600 * 1000
    const val CLOCK_TOLERANCE_MS = 15L * 60 * 1000

    private val dateFmt = DateTimeFormatter.ISO_LOCAL_DATE

    fun isoWeekId(date: LocalDate): String {
        val weekBasedYear = date.get(IsoFields.WEEK_BASED_YEAR)
        val week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        return "%04d-W%02d".format(weekBasedYear, week)
    }

    fun consumedSet(state: UnlockState): Set<String> =
        state.consumedWeeks.split(',').filter { it.isNotBlank() }.toSet()

    fun isActive(state: UnlockState?, nowMs: Long): Boolean =
        state != null && state.activeUntilMs > nowMs

    fun remainingMs(state: UnlockState?, nowMs: Long): Long =
        if (state == null) 0 else (state.activeUntilMs - nowMs).coerceAtLeast(0)

    fun canSpendWeeklyUnlock(state: UnlockState?, today: LocalDate): Boolean =
        isoWeekId(today) !in consumedSet(state ?: UnlockState())

    fun spendWeeklyUnlock(state: UnlockState?, nowMs: Long, today: LocalDate): UnlockState {
        val s = state ?: UnlockState()
        require(canSpendWeeklyUnlock(s, today)) { "Weekly unlock already consumed" }
        val weeks = (consumedSet(s) + isoWeekId(today)).sorted().joinToString(",")
        return s.copy(consumedWeeks = weeks, activeUntilMs = nowMs + UNLOCK_DURATION_MS)
    }

    /**
     * Consume the current week's unlock as a tamper consequence (does NOT
     * activate a pause window). No-op if already consumed.
     */
    fun consumeForTamper(state: UnlockState?, today: LocalDate): UnlockState {
        val s = state ?: UnlockState()
        val week = isoWeekId(today)
        if (week in consumedSet(s)) return s
        return s.copy(consumedWeeks = (consumedSet(s) + week).sorted().joinToString(","))
    }

    fun canMalfunctionPause(state: UnlockState?, today: LocalDate): Boolean =
        (state?.malfunctionPauseDate ?: "") != today.format(dateFmt)

    fun spendMalfunctionPause(state: UnlockState?, nowMs: Long, today: LocalDate): UnlockState {
        val s = state ?: UnlockState()
        require(canMalfunctionPause(s, today)) { "Malfunction pause already used today" }
        return s.copy(
            malfunctionPauseDate = today.format(dateFmt),
            activeUntilMs = nowMs + MALFUNCTION_PAUSE_MS,
        )
    }

    fun inGracePeriod(state: UnlockState?, nowMs: Long): Boolean =
        state != null && state.provisionedAtMs > 0 && nowMs < state.provisionedAtMs + GRACE_PERIOD_MS

    data class ClockCheck(val state: UnlockState, val tampered: Boolean)

    /**
     * Update the UTC watermark and report tampering.
     *
     *  - Rollback: UTC is well below the highest UTC ever seen -> tampered.
     *  - Forward jump: UTC advanced far beyond watermark + elapsed-realtime delta.
     *    Only valid within continuous uptime (elapsedRealtime must be >= the anchor);
     *    after a reboot, callers must re-anchor via [reanchorAfterBoot] before checking.
     */
    fun updateWatermark(state: UnlockState?, utcMs: Long, elapsedMs: Long): ClockCheck {
        val s = state ?: UnlockState()
        if (s.utcWatermarkMs <= 0) {
            return ClockCheck(s.copy(utcWatermarkMs = utcMs, watermarkElapsedMs = elapsedMs), false)
        }
        var tampered = false
        if (utcMs < s.utcWatermarkMs - CLOCK_TOLERANCE_MS) tampered = true
        if (elapsedMs >= s.watermarkElapsedMs) {
            val expectedUtc = s.utcWatermarkMs + (elapsedMs - s.watermarkElapsedMs)
            if (utcMs > expectedUtc + CLOCK_TOLERANCE_MS) tampered = true
        }
        val updated =
            if (utcMs > s.utcWatermarkMs) s.copy(utcWatermarkMs = utcMs, watermarkElapsedMs = elapsedMs)
            else s
        return ClockCheck(updated, tampered)
    }

    /**
     * After a reboot elapsedRealtime resets, so the forward-jump check is meaningless
     * for time that passed while powered off. Re-anchor elapsed at the current UTC,
     * still honoring the rollback check against the persisted watermark.
     */
    fun reanchorAfterBoot(state: UnlockState?, utcMs: Long, elapsedMs: Long): ClockCheck {
        val s = state ?: UnlockState()
        val tampered = s.utcWatermarkMs > 0 && utcMs < s.utcWatermarkMs - CLOCK_TOLERANCE_MS
        val newWatermark = maxOf(s.utcWatermarkMs, utcMs)
        return ClockCheck(s.copy(utcWatermarkMs = newWatermark, watermarkElapsedMs = elapsedMs), tampered)
    }
}
