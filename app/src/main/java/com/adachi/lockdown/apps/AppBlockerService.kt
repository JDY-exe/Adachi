package com.adachi.lockdown.apps

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.adachi.lockdown.data.AppRule
import com.adachi.lockdown.data.BlockLog
import com.adachi.lockdown.data.RuleType
import com.adachi.lockdown.data.RulesRepository
import com.adachi.lockdown.data.UsageLedger
import com.adachi.lockdown.rules.RuleEngine
import com.adachi.lockdown.unlock.UnlockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * App enforcement. Watches foreground-app changes via accessibility events and
 * bounces the user to the home screen (with a full-screen overlay) when the
 * current app is blocked, outside its allowed window, or over its daily quota.
 *
 * A 30s ticker also re-evaluates the *current* foreground app so windows
 * expiring and quotas exhausting take effect while the user stays in the app.
 *
 * Quota accounting: foreground time per package per local day, flushed to the
 * usage ledger every minute.
 *
 * Safety: never blocks Adachi itself, the launcher, or SystemUI — even under
 * a "*" wildcard rule — so the phone always remains operable.
 */
class AppBlockerService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repo: RulesRepository
    private lateinit var wm: WindowManager

    @Volatile private var rules: List<AppRule> = emptyList()
    @Volatile private var paused = false
    @Volatile private var currentForeground: String? = null
    @Volatile private var overlay: View? = null

    /** packageName -> seconds used today (in-memory; flushed to Room). */
    private val usageSeconds = ConcurrentHashMap<String, Int>()

    @Volatile private var usageDate: String = LocalDate.now().toString()
    private var foregroundSinceMs: Long = 0

    private val neverBlock = mutableSetOf<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        repo = RulesRepository.get(this)
        wm = getSystemService(WindowManager::class.java)
        computeNeverBlock()
        observeState()
        startTicker()
        Log.i(TAG, "App blocker connected")
    }

    private fun computeNeverBlock() {
        neverBlock.add(packageName)
        neverBlock.add("com.android.systemui")
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.let { neverBlock.add(it) }
    }

    // ---------------- Event handling ----------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == currentForeground) return
        onForegroundChanged(pkg)
    }

    private fun onForegroundChanged(pkg: String) {
        closeSession()
        currentForeground = pkg
        foregroundSinceMs = System.currentTimeMillis()
        evaluate(pkg)
    }

    /** Accumulate the elapsed foreground time for the previous app. */
    private fun closeSession() {
        resetUsageIfNewDay()
        val pkg = currentForeground ?: return
        if (foregroundSinceMs <= 0) return
        val elapsedSec = ((System.currentTimeMillis() - foregroundSinceMs) / 1000).toInt()
        if (elapsedSec > 0 && matchesQuotaRule(pkg)) {
            usageSeconds.merge(pkg, elapsedSec, Int::plus)
        }
    }

    /** In-memory counters are date-scoped: clear them when the day rolls over. */
    private fun resetUsageIfNewDay() {
        val today = LocalDate.now().toString()
        if (today != usageDate) {
            usageSeconds.clear()
            usageDate = today
        }
    }

    private fun matchesQuotaRule(pkg: String): Boolean =
        rules.any { it.enabled && it.type == RuleType.QUOTA && RuleEngine.matchesApp(it.packageName, pkg) }

    private fun evaluate(pkg: String) {
        if (paused || pkg in neverBlock) {
            hideOverlay()
            return
        }
        val verdict = RuleEngine.evaluateApp(pkg, rules, LocalDateTime.now(), usageMinutesToday())
        when (verdict) {
            is RuleEngine.Verdict.Block -> blockApp(pkg, verdict)
            RuleEngine.Verdict.Allow -> hideOverlay()
        }
    }

    private fun usageMinutesToday(): Map<Long, Int> {
        resetUsageIfNewDay()
        val result = mutableMapOf<Long, Int>()
        for (rule in rules) {
            if (!rule.enabled || rule.type != RuleType.QUOTA) continue
            // Aggregate minutes across all packages matched by this rule.
            val seconds = usageSeconds.filterKeys { RuleEngine.matchesApp(rule.packageName, it) }
                .values.sum()
            result[rule.id] = seconds / 60
        }
        return result
    }

    private fun blockApp(pkg: String, verdict: RuleEngine.Verdict.Block) {
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val reason = when (verdict.reason) {
            RuleEngine.Reason.BLOCKED -> "This app is blocked"
            RuleEngine.Reason.OUTSIDE_WINDOW -> "Allowed only during its scheduled window"
            RuleEngine.Reason.QUOTA_EXHAUSTED -> "Daily time limit reached"
        }
        showOverlay(label, reason)
        performGlobalAction(GLOBAL_ACTION_HOME)
        scope.launch {
            runCatching {
                repo.logBlock(
                    BlockLog(
                        epochMs = System.currentTimeMillis(),
                        kind = "APP",
                        target = label,
                        reason = verdict.reason.name,
                    ),
                )
            }
        }
    }

    // ---------------- Overlay ----------------

    private fun showOverlay(appLabel: String, reason: String) {
        hideOverlay()
        val view = buildOverlayView(appLabel, reason)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        runCatching {
            wm.addView(view, params)
            overlay = view
        }.onFailure { Log.w(TAG, "Failed to show overlay", it) }
    }

    private fun hideOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    private fun buildOverlayView(appLabel: String, reason: String): View {
        val bg = GradientDrawable().apply { setColor(0xFF121318.toInt()) }
        val title = TextView(this).apply {
            text = appLabel
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFE2E2E9.toInt())
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = reason
            textSize = 16f
            setTextColor(0xFFC4C6D0.toInt())
            gravity = Gravity.CENTER
        }
        val hint = TextView(this).apply {
            text = "Blocked by Adachi"
            textSize = 13f
            setTextColor(0xFF8A8F9E.toInt())
            gravity = Gravity.CENTER
        }
        val help = Button(this).apply {
            text = "Something's wrong?"
            setOnClickListener {
                val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    putExtra("route", "unlock")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent != null) startActivity(intent)
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = bg
            val pad = (32 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(title)
            addView(subtitle)
            addView(hint)
            addView(help)
        }
    }

    // ---------------- State observation & ticker ----------------

    private fun observeState() {
        scope.launch {
            combine(
                repo.appRules(),
                repo.unlockState(),
            ) { rules, unlock -> rules to unlock }
                .collect { (rules, unlock) ->
                    this@AppBlockerService.rules = rules.filter { it.enabled }
                    paused = UnlockManager.isActive(unlock, System.currentTimeMillis())
                    if (paused) hideOverlay()
                }
        }
    }

    private fun startTicker() {
        scope.launch {
            while (true) {
                delay(30_000)
                // Unlock state may expire by time alone.
                paused = UnlockManager.isActive(repo.unlockStateNow(), System.currentTimeMillis())
                closeSession()
                foregroundSinceMs = System.currentTimeMillis()
                currentForeground?.let { evaluate(it) }
                flushUsage()
            }
        }
    }

    private suspend fun flushUsage() {
        val today = LocalDate.now().toString()
        usageSeconds.forEach { (pkg, seconds) ->
            runCatching {
                repo.saveUsage(UsageLedger(key = "app:$pkg", date = today, minutesUsed = seconds / 60))
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        hideOverlay()
        scope.launch { flushUsage() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AppBlockerService"
    }
}
