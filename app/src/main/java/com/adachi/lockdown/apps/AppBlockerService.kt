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
import com.adachi.lockdown.data.BlockLog
import com.adachi.lockdown.data.EventLogger
import com.adachi.lockdown.data.RuleWithTargets
import com.adachi.lockdown.data.RuleCheckIn
import com.adachi.lockdown.data.RulesRepository
import com.adachi.lockdown.rules.RuleEngine
import com.adachi.lockdown.unlock.UnlockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
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

    @Volatile private var rules: List<RuleWithTargets> = emptyList()
    @Volatile private var checkIns: Map<Long, RuleCheckIn> = emptyMap()
    @Volatile private var paused = false
    @Volatile private var currentForeground: String? = null
    @Volatile private var overlay: View? = null


    private val neverBlock = mutableSetOf<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        repo = RulesRepository.get(this)
        wm = getSystemService(WindowManager::class.java)
        computeNeverBlock()
        observeState()
        startTicker()
        Log.i(TAG, "App blocker connected")
        EventLogger.log(EventLogger.Kind.APP, EventLogger.Level.INFO, "app blocker connected")
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
        currentForeground = pkg
        evaluate(pkg)
    }

    private fun evaluate(pkg: String) {
        if (paused || pkg in neverBlock) {
            hideOverlay()
            return
        }
        val verdict = RuleEngine.evaluateApp(pkg, rules, checkIns, LocalDateTime.now(), System.currentTimeMillis())
        when (verdict) {
            is RuleEngine.Verdict.Block -> blockApp(pkg, verdict)
            RuleEngine.Verdict.Allow -> hideOverlay()
        }
    }


    private fun blockApp(pkg: String, verdict: RuleEngine.Verdict.Block) {
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val reason = when (verdict.reason) {
            RuleEngine.Reason.BLOCKED -> "This app is blocked"
            RuleEngine.Reason.OUTSIDE_TIME_FRAME -> "Outside its scheduled time frame"
            RuleEngine.Reason.CHECK_IN_REQUIRED -> "Check in to use this rule"
        }
        showOverlay(label, reason)
        performGlobalAction(GLOBAL_ACTION_HOME)
        EventLogger.log(
            EventLogger.Kind.APP, EventLogger.Level.BLOCK,
            "$label ($pkg) blocked — ${verdict.reason}, rule #${verdict.ruleId}",
            throttleKey = "appblk:$pkg", throttleMs = 30_000,
        )
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
                repo.rules(),
                repo.checkIns(),
                repo.unlockState(),
            ) { rules, grants, unlock -> Triple(rules, grants, unlock) }
                .collect { (rules, grants, unlock) ->
                    this@AppBlockerService.rules = rules.filter { it.rule.enabled }
                    this@AppBlockerService.checkIns = grants.associateBy { it.ruleId }
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
                currentForeground?.let { evaluate(it) }
            }
        }
    }


    override fun onInterrupt() {}

    override fun onDestroy() {
        hideOverlay()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AppBlockerService"
    }
}
