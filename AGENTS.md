# Adachi

Android lockdown app (Kotlin, Jetpack Compose, Room) for a personal Google Pixel.
Blocks domains and apps on schedules/quotas, with device-owner anti-tamper and a
once-per-week emergency unlock. Single offline app, no server, no accounts.

## Build & test

- Toolchain: system JDK 17, Android SDK at `~/Android/Sdk` (platform android-34,
  build-tools 34.0.0). `local.properties` points at the SDK (gitignored).
- Build: `./gradlew :app:assembleDebug`
- Unit tests: `./gradlew :app:testDebugUnitTest` (pure JVM tests: RuleEngine,
  EditPolicy, UnlockManager, DnsCodec, IpPacket — no Android runtime needed)
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Debug builds use applicationId suffix `.debug` — device-owner provisioning
  command must match the installed applicationId.

## Architecture (package `com.adachi.lockdown`)

Enforcement layers:
- `vpn/AdachiVpnService` — local **DNS-only** VPN: routes only the virtual DNS
  IP (10.0.2.2) into the tunnel, NXDOMAINs blocked domains, forwards the rest
  to 1.1.1.1 over UDP via a protected socket. Domain quotas = minutes with DNS
  activity (approximate by design). Crash-loop (3/5min) => fail open, never
  brick connectivity. `vpn/DnsCodec`, `vpn/IpPacket` — pure, unit-tested.
- `apps/AppBlockerService` — AccessibilityService: on foreground-app change and
  a 30s ticker, blocks apps via full-screen overlay + GLOBAL_ACTION_HOME.
  App quotas = foreground minutes, date-scoped in memory. Never blocks Adachi
  itself, the launcher, or SystemUI (even under "*" wildcard).
- `admin/DeviceOwnerManager` — user restrictions (no ADB, no safe boot, no
  apps-control, no VPN config, no add-user, no config-date-time),
  uninstall-block, always-on VPN (WITHOUT lockdown flag, so a dead VPN can
  never kill connectivity). NOT applied: DISALLOW_FACTORY_RESET (user's
  ultimate escape hatch). `teardown()` = ordered full removal path.
- `unlock/` — once-per-ISO-week 30-min emergency unlock (pure `UnlockManager`),
  once-per-day 10-min malfunction pause, `ClockWatchdog` (UTC watermark +
  elapsedRealtime anchor; date jumps consume the weekly unlock; timezone
  changes are harmless), `UnlockWindowReactor` (lifts date-time restriction
  during unlock windows), `UnlockNotifier` (countdown notification).
- `rules/RuleEngine` — pure verdict logic. Precedence: ALLOW > BLOCK >
  WINDOW/QUOTA (all must pass) > default ALLOW. `rules/EditPolicy` classifies
  edits as relaxing (needs active unlock) vs restricting (always allowed);
  enforced in `data/RulesRepository`, which throws `RelaxationLockedException`.
- `data/` — Room: DomainRule, AppRule, UnlockState (singleton), UsageLedger,
  BlockLog.
- `ui/` — Compose single-activity nav: Dashboard, Domain rules, App rules,
  Unlock, Setup.

Key behaviors:
- "Stricter anytime": adding/tightening blocks never needs the unlock.
- Travel mode: lifts date-time restriction 5 min (free); ClockWatchdog consumes
  the weekly unlock if the DATE jumps. Auto-timezone is forced on before the
  restriction is applied, so normal travel needs nothing.
- 48h grace period after provisioning: full deactivation freely available;
  afterwards it requires the unlock window (or factory reset).

## Provisioning (Phase 2, after trial period)

1. Remove all accounts on the phone (or factory-reset state), enable USB debugging.
2. `adb shell dpm set-device-owner <applicationId>/.admin.AdminReceiver`
3. In app: Setup → "Apply lockdown restrictions".

## Invariants / gotchas when editing

- Keep `RuleEngine`, `EditPolicy`, `UnlockManager`, `DnsCodec`, `IpPacket` free
  of Android dependencies — their tests are pure JVM.
- Every relaxing mutation must go through `RulesRepository`'s EditPolicy gate;
  never bypass it from new UI.
- Never add DISALLOW_FACTORY_RESET, and never set always-on VPN with the
  lockdown flag — fail-open is a hard requirement (user must never be bricked).
- Quota/usage in-memory state is date-scoped; preserve the midnight reset when
  touching the VPN or app blocker.
- ClockWatchdog: re-anchor elapsed ONLY on boot; TIME_SET/TIMEZONE_CHANGED must
  run the check (re-anchoring would mask forward jumps).
