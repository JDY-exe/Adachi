package com.adachi.lockdown.vpn

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.adachi.lockdown.AdachiApp
import com.adachi.lockdown.R
import com.adachi.lockdown.data.BlockLog
import com.adachi.lockdown.data.EventLogger
import com.adachi.lockdown.data.RuleWithTargets
import com.adachi.lockdown.data.RuleCheckIn
import com.adachi.lockdown.data.RulesRepository
import com.adachi.lockdown.data.UnlockState
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
import java.time.LocalDateTime
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
    @Volatile private var forwarder: DnsForwarder? = null
    private var output: FileOutputStream? = null

    @Volatile private var lastRebuildAt = 0L
    @Volatile private var currentNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var rules: List<RuleWithTargets> = emptyList()
    @Volatile private var checkIns: Map<Long, RuleCheckIn> = emptyMap()
    @Volatile private var unlockState: UnlockState? = null

    /** target -> last log epoch ms, to throttle block logging. */
    private val recentLogs = ConcurrentHashMap<String, Long>()


    override fun onCreate() {
        super.onCreate()
        repo = RulesRepository.get(this)
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(AdachiApp.NOTIF_ID_VPN, buildNotification())
        if (!running) {
            if (crashLooping()) {
                Log.e(TAG, "Crash loop detected — failing open (not establishing VPN)")
                EventLogger.log(EventLogger.Kind.VPN, EventLogger.Level.ERROR, "crash loop detected — failing open, VPN not established")
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
                EventLogger.log(EventLogger.Kind.VPN, EventLogger.Level.ERROR, "establish() returned null — VPN permission revoked?")
                stopSelf()
                return
            }
            tun = tunFd
            output = FileOutputStream(tunFd.fileDescriptor)
            running = true
            SystemStatus.setVpnRunning(this, true)
            startReader(tunFd)
            watchNetworks()
            Log.i(TAG, "VPN established")
            EventLogger.log(EventLogger.Kind.VPN, EventLogger.Level.INFO, "VPN established (DNS-only, upstream 1.1.1.1, ${rules.size} enabled domain rules)")
            // Socket creation must not run on the main thread: debug builds
            // enforce StrictMode death-on-network (NetworkOnMainThreadException).
            scope.launch { initForwarder() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN", e)
            EventLogger.log(
                EventLogger.Kind.VPN, EventLogger.Level.ERROR,
                "failed to establish VPN (${e.javaClass.simpleName}): ${e.message}",
            )
            recordCrash()
            stopSelf()
        }
    }

    private fun buildForwarder(): DnsForwarder =
        DnsForwarder(
            protect = { protect(it) },
            scope = scope,
            onDead = { rebuildForwarder("upstream unresponsive") },
        ) { level, msg ->
            EventLogger.log(EventLogger.Kind.UPSTREAM, EventLogger.Level.valueOf(level), msg)
        }

    /** Create the upstream socket off the main thread. Called from Dispatchers.IO. */
    private fun initForwarder() {
        try {
            forwarder = buildForwarder()
            // A healthy establish proves the crash loop is over; reset the counter.
            clearCrashes()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create upstream socket", e)
            EventLogger.log(
                EventLogger.Kind.VPN, EventLogger.Level.ERROR,
                "failed to create upstream DNS socket (${e.javaClass.simpleName}): ${e.message}",
            )
            recordCrash()
            stopSelf()
        }
    }

    /**
     * The protected upstream socket is bound to the network it was created on
     * and dies silently when the device switches networks — every query then
     * times out, which looks exactly like "everything is blocked". Rebuild it
     * on network changes and when the forwarder reports itself dead. Throttled
     * so flapping networks can't churn sockets.
     */
    private fun rebuildForwarder(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastRebuildAt < 30_000) return
        lastRebuildAt = now
        EventLogger.log(EventLogger.Kind.VPN, EventLogger.Level.INFO, "rebuilding upstream DNS socket ($reason)")
        // Swap only when the new socket is ready, so DNS is never dropped mid-flight.
        scope.launch {
            runCatching {
                val new = buildForwarder()
                val old = forwarder
                forwarder = new
                runCatching { old?.close() }
            }.onFailure { e ->
                EventLogger.log(
                    EventLogger.Kind.VPN, EventLogger.Level.ERROR,
                    "failed to rebuild upstream DNS socket (${e.javaClass.simpleName}): ${e.message}",
                )
            }
        }
    }

    private fun watchNetworks() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = currentNetwork
                currentNetwork = network
                if (previous != null && previous != network) rebuildForwarder("network switch")
            }

            // Deliberately no onLost handling: on a handoff, onLost(old) often
            // arrives AFTER onAvailable(new). Clearing currentNetwork there
            // would mask the switch and skip the socket rebuild. The forwarder's
            // send-error/timeout detection covers any remaining gaps.
        }
        networkCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    private fun startReader(tunFd: ParcelFileDescriptor) {
        val input = FileInputStream(tunFd.fileDescriptor)
        thread(name = "adachi-vpn-reader", isDaemon = true) {
            val buf = ByteArray(32767)
            try {
                while (running) {
                    val n = input.read(buf)
                    if (n <= 0) {
                        // read() on a tun fd normally blocks; a non-positive
                        // return on a live fd would spin this thread at 100%
                        // CPU, so back off instead of continuing hot.
                        Thread.sleep(10)
                        continue
                    }
                    handlePacket(buf.copyOf(n))
                }
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "Tunnel reader died", e)
                    EventLogger.log(
                        EventLogger.Kind.VPN, EventLogger.Level.ERROR,
                        "tunnel reader died (${e.javaClass.simpleName}): ${e.message}",
                    )
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
            RuleEngine.evaluateDomain(query.name, rules, checkIns, now, System.currentTimeMillis())
        }

        when (verdict) {
            is RuleEngine.Verdict.Block -> {
                EventLogger.log(
                    EventLogger.Kind.DNS, EventLogger.Level.BLOCK,
                    "${query.name} → NXDOMAIN (${verdict.reason}, rule #${verdict.ruleId})",
                    throttleKey = "blk:${query.name}", throttleMs = 5_000,
                )
                logBlock(query.name, verdict.reason.name)
                respond(dgram, DnsCodec.buildNxdomain(query))
            }
            RuleEngine.Verdict.Allow -> {
                EventLogger.log(
                    EventLogger.Kind.DNS, EventLogger.Level.ALLOW,
                    if (paused) "${query.name} → allowed (enforcement paused)"
                    else "${query.name} → allowed",
                    throttleKey = "ok:${query.name}:${paused}", throttleMs = 60_000,
                )
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
                repo.rules(),
                repo.checkIns(),
                repo.unlockState(),
            ) { rules, checkIns, unlock -> Triple(rules, checkIns, unlock) }
                .collect { (rules, grants, unlock) ->
                    val enabled = rules.filter { it.rule.enabled }
                    if (enabled != this@AdachiVpnService.rules) {
                        EventLogger.log(
                            EventLogger.Kind.VPN, EventLogger.Level.INFO,
                            "domain rules reloaded: ${enabled.size} enabled",
                        )
                    }
                    this@AdachiVpnService.rules = enabled
                    this@AdachiVpnService.checkIns = grants.associateBy { it.ruleId }
                    this@AdachiVpnService.unlockState = unlock
                    setPaused(UnlockManager.isActive(unlock, System.currentTimeMillis()))
                }
        }
        // The unlock window expires by time alone; re-check the cached state
        // periodically. Pure in-memory comparison — no DB read.
        scope.launch {
            while (true) {
                delay(15_000)
                setPaused(UnlockManager.isActive(unlockState, System.currentTimeMillis()))
            }
        }
    }

    private fun setPaused(value: Boolean) {
        if (value == paused) return
        paused = value
        EventLogger.log(
            EventLogger.Kind.VPN, EventLogger.Level.INFO,
            if (value) "enforcement PAUSED (unlock window)" else "enforcement resumed",
        )
        refreshNotification()
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

    private fun clearCrashes() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_CRASHES).apply()
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
        EventLogger.log(EventLogger.Kind.VPN, EventLogger.Level.INFO, "VPN revoked by system/user")
        running = false
        SystemStatus.setVpnRunning(this, false)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        EventLogger.log(EventLogger.Kind.VPN, EventLogger.Level.INFO, "VPN stopped")
        running = false
        networkCallback?.let {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        }
        networkCallback = null
        currentNetwork = null
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
