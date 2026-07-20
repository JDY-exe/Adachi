package com.adachi.lockdown.vpn

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.adachi.lockdown.AdachiApp
import com.adachi.lockdown.R
import com.adachi.lockdown.data.BlockLog
import com.adachi.lockdown.data.DomainRule
import com.adachi.lockdown.data.RuleType
import com.adachi.lockdown.data.RulesRepository
import com.adachi.lockdown.data.UnlockState
import com.adachi.lockdown.data.UsageLedger
import com.adachi.lockdown.rules.RuleEngine
import com.adachi.lockdown.status.SystemStatus
import com.adachi.lockdown.unlock.UnlockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Domain enforcement via a local, DNS-only VPN.
 *
 * Only the virtual DNS server IP is routed into the tunnel — all other traffic
 * flows normally, so there is no userspace packet relay, negligible battery
 * cost, and connectivity cannot break. Domain rules are enforced at resolution
 * time (NXDOMAIN for blocked) plus quota accounting by DNS activity minutes.
 *
 * Fail-safe: if the service crash-loops (3 crashes / 5 min), it refuses to
 * re-establish, leaving the device unfiltered but online. See CRASH_WINDOW.
 */
class AdachiVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repo: RulesRepository

    @Volatile private var running = false
    @Volatile private var paused = false
    private var tun: ParcelFileDescriptor? = null
    private var forwarder: DnsForwarder? = null
    private var output: FileOutputStream? = null

    @Volatile private var rules: List<DomainRule> = emptyList()

    /** ruleId -> set of active minute-buckets today (domain quota accounting). */
    private val quotaMinutes = ConcurrentHashMap<Long, MutableSet<String>>()

    /** target -> last log epoch ms, to throttle block logging. */
    private val recentLogs = ConcurrentHashMap<String, Long>()

    private val minuteFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    override fun onCreate() {
        super.onCreate()
        repo = RulesRepository.get(this)
        observeState()
        startUsageFlusher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(AdachiApp.NOTIF_ID_VPN, buildNotification())
        if (!running) {
            if (crashLooping()) {
                Log.e(TAG, "Crash loop detected — failing open (not establishing VPN)")
                notifyFailsafe()
                SystemStatus.setVpnRunning(this, false)
                stopSelf()
            } else {
                establish()
            }
        }
        return START_STICKY
    }

    // ---------------- Tunnel ----------------

    private fun establish() {
        try {
            val tunFd = Builder()
                .setSession("Adachi")
                .addAddress(VIRTUAL_CLIENT_IP, 24)
                .addDnsServer(VIRTUAL_DNS_IP)
                .addRoute(VIRTUAL_DNS_IP, 32)
                .establish()
            if (tunFd == null) {
                Log.e(TAG, "establish() returned null (VPN permission revoked?)")
                stopSelf()
                return
            }
            tun = tunFd
            forwarder = DnsForwarder(protect = { protect(it) }, scope = scope)
            output = FileOutputStream(tunFd.fileDescriptor)
            running = true
            SystemStatus.setVpnRunning(this, true)
            startReader(tunFd)
            Log.i(TAG, "VPN established")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN", e)
            recordCrash()
            stopSelf()
        }
    }

    private fun startReader(tunFd: ParcelFileDescriptor) {
        val input = FileInputStream(tunFd.fileDescriptor)
        thread(name = "adachi-vpn-reader", isDaemon = true) {
            val buf = ByteArray(32767)
            try {
                while (running) {
                    val n = input.read(buf)
                    if (n <= 0) continue
                    handlePacket(buf.copyOf(n))
                }
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "Tunnel reader died", e)
                    recordCrash()
                }
            } finally {
                running = false
            }
        }
    }

    private fun handlePacket(packet: ByteArray) {
        val dgram = IpPacket.parseUdp(packet) ?: return
        if (dgram.dstPort != 53) return                    // only DNS is routed here
        val query = DnsCodec.parseQuery(dgram.payload) ?: return

        val now = LocalDateTime.now()
        val verdict = if (paused) {
            RuleEngine.Verdict.Allow
        } else {
            RuleEngine.evaluateDomain(query.name, rules, now, quotaUsedToday())
        }

        when (verdict) {
            is RuleEngine.Verdict.Block -> {
                logBlock(query.name, verdict.reason.name)
                respond(dgram, DnsCodec.buildNxdomain(query))
            }
            RuleEngine.Verdict.Allow -> {
                recordQuotaActivity(query.name)
                forward(dgram, query)
            }
        }
    }

    private fun forward(dgram: IpPacket.UdpDatagram, query: DnsCodec.Query) {
        val out = output ?: return
        forwarder?.forward(query.raw) { response ->
            synchronized(out) {
                runCatching {
                    out.write(
                        IpPacket.buildUdpPacket(
                            srcIp = dgram.dstIp, dstIp = dgram.srcIp,
                            srcPort = dgram.dstPort, dstPort = dgram.srcPort,
                            payload = response,
                        ),
                    )
                }
            }
        }
    }

    private fun respond(dgram: IpPacket.UdpDatagram, dnsResponse: ByteArray) {
        val out = output ?: return
        synchronized(out) {
            runCatching {
                out.write(
                    IpPacket.buildUdpPacket(
                        srcIp = dgram.dstIp, dstIp = dgram.srcIp,
                        srcPort = dgram.dstPort, dstPort = dgram.srcPort,
                        payload = dnsResponse,
                    ),
                )
            }
        }
    }

    // ---------------- Rules & state ----------------

    private fun observeState() {
        scope.launch {
            combine(
                repo.domainRules(),
                repo.unlockState(),
            ) { rules, unlock -> rules to unlock }
                .collect { (rules, unlock) ->
                    this@AdachiVpnService.rules = rules.filter { it.enabled }
                    paused = UnlockManager.isActive(unlock, System.currentTimeMillis())
                }
        }
        // The unlock window expires by time alone; check periodically.
        scope.launch {
            while (true) {
                delay(15_000)
                val s = repo.unlockStateNow()
                val wasPaused = paused
                paused = UnlockManager.isActive(s, System.currentTimeMillis())
                if (wasPaused != paused) refreshNotification()
            }
        }
    }

    private fun quotaUsedToday(): Map<Long, Int> {
        val todayPrefix = LocalDate.now().toString()
        return quotaMinutes.mapValues { entry -> entry.value.count { it.startsWith(todayPrefix) } }
    }

    private fun recordQuotaActivity(domain: String) {
        val now = LocalDateTime.now()
        val minuteBucket = now.format(minuteFmt)
        rules.filter { it.enabled && it.type == RuleType.QUOTA && RuleEngine.matchesDomain(it.pattern, domain) }
            .forEach { rule ->
                quotaMinutes.getOrPut(rule.id) { ConcurrentHashMap.newKeySet() }.add(minuteBucket)
            }
    }

    private fun startUsageFlusher() {
        scope.launch {
            while (true) {
                delay(60_000)
                flushUsage()
            }
        }
    }

    private suspend fun flushUsage() {
        val today = LocalDate.now().toString()
        // Drop minute-buckets from previous days so memory and counts stay date-scoped.
        quotaMinutes.values.forEach { set -> set.removeIf { !it.startsWith(today) } }
        quotaMinutes.forEach { (ruleId, minutes) ->
            runCatching {
                repo.saveUsage(
                    UsageLedger(
                        key = "dom:$ruleId",
                        date = today,
                        minutesUsed = minutes.count { it.startsWith(today) },
                    ),
                )
            }
        }
        runCatching { repo.pruneUsage(LocalDate.now().minusDays(7).toString()) }
        runCatching { repo.pruneBlocks(System.currentTimeMillis() - 14L * 24 * 3600 * 1000) }
    }

    private fun logBlock(target: String, reason: String) {
        val now = System.currentTimeMillis()
        val last = recentLogs.put(target, now) ?: 0
        if (now - last < 5_000) return
        scope.launch {
            runCatching {
                repo.logBlock(BlockLog(epochMs = now, kind = "DOMAIN", target = target, reason = reason))
            }
        }
    }

    // ---------------- Crash fail-safe ----------------

    private fun crashLooping(): Boolean {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val crashes = prefs.getString(KEY_CRASHES, "")!!
            .split(',').filter { it.isNotBlank() }.map { it.toLong() }
        return crashes.count { now - it < CRASH_WINDOW_MS } >= MAX_CRASHES
    }

    private fun recordCrash() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val crashes = (prefs.getString(KEY_CRASHES, "")!!
            .split(',').filter { it.isNotBlank() }.map { it.toLong() }
            .filter { now - it < CRASH_WINDOW_MS } + now)
        prefs.edit().putString(KEY_CRASHES, crashes.joinToString(",")).apply()
    }

    // ---------------- Notification & lifecycle ----------------

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, AdachiApp.CHANNEL_ENFORCEMENT)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(
                if (paused) "Adachi paused" else "Adachi protection active",
            )
            .setContentText(
                if (paused) "Domain rules are NOT being enforced"
                else "Domain rules are being enforced",
            )
            .setOngoing(true)
            .build()

    private fun refreshNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(AdachiApp.NOTIF_ID_VPN, buildNotification())
    }

    private fun notifyFailsafe() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(
            AdachiApp.NOTIF_ID_FAILSAFE,
            NotificationCompat.Builder(this, AdachiApp.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Adachi fail-safe triggered")
                .setContentText("The domain filter kept crashing and was stopped to keep you online.")
                .build(),
        )
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked")
        running = false
        SystemStatus.setVpnRunning(this, false)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        running = false
        scope.launch { flushUsage() }
        runCatching { forwarder?.close() }
        runCatching { tun?.close() }
        SystemStatus.setVpnRunning(this, false)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AdachiVpnService"
        private const val VIRTUAL_CLIENT_IP = "10.0.2.1"
        private const val VIRTUAL_DNS_IP = "10.0.2.2"
        private const val PREFS = "adachi_runtime"
        private const val KEY_CRASHES = "vpn_crashes"
        private const val CRASH_WINDOW_MS = 5 * 60 * 1000L
        private const val MAX_CRASHES = 3

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AdachiVpnService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AdachiVpnService::class.java))
        }
    }
}
