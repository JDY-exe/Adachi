# Adachi

A no-mercy lockdown system for your Google Pixel. Block distracting domains and
apps, put them on schedules or daily time quotas, and make it genuinely hard to
bypass — with a once-per-week emergency unlock when you truly need it.

Single offline app. No server, no accounts, no data leaves your phone.

---

## Table of contents

- [How it works](#how-it-works)
- [Rules: the four types](#rules-the-four-types)
- [Installing](#installing)
- [Phase 1 — Trial setup](#phase-1--trial-setup)
- [Phase 2 — Full lockdown (device owner)](#phase-2--full-lockdown-device-owner)
- [Daily use](#daily-use)
- [Emergency unlock & pauses](#emergency-unlock--pauses)
- [Travel & timezone](#travel--timezone)
- [Turning it off (teardown)](#turning-it-off-teardown)
- [Known limitations](#known-limitations)
- [Troubleshooting](#troubleshooting)

---

## How it works

Adachi enforces your rules on three independent layers:

| Layer | What it does | What it controls |
|---|---|---|
| **Domain filter** | A local VPN that answers your phone's DNS lookups itself. Blocked domains get NXDOMAIN (site doesn't exist); everything else is forwarded to Cloudflare (1.1.1.1). | Websites, device-wide |
| **App blocker** | An accessibility service that sees which app is in the foreground and bounces you to the home screen (with a blocking screen) if it's not allowed right now. | Apps |
| **Anti-tamper** | Android *device owner* mode. Blocks uninstalling, force-stopping, safe mode, ADB, and clock changes. Factory reset stays possible — it's your ultimate escape hatch. | Adachi itself |

Because the VPN only handles DNS lookups (not your actual traffic), it costs
almost no battery and cannot break your internet. If it ever crash-loops, it
fails *open* — you keep connectivity, unfiltered.

## Rules: the four types

Every domain rule and app rule has one of four types:

| Type | Meaning | Example |
|---|---|---|
| **Block** | Never allowed, any time. | `reddit.com` is always dead |
| **Allow** | Always allowed — beats every other rule. Useful with `*` blocks. | Allow `wikipedia.org` while blocking `*` |
| **Window** | Allowed only inside a weekly schedule (days + from/until time). Outside the window it's blocked. | `youtube.com` allowed 20:00–22:00, weekends only |
| **Quota** | Allowed until a daily time budget runs out, then blocked until midnight. | Instagram, max 15 min/day |

Pattern details:

- **Domains:** `reddit.com` also covers `old.reddit.com`, `i.reddit.com`, etc.
  `*.reddit.com` works the same. `*` matches **every domain** (total block mode —
  pair it with Allow rules for a whitelist phone).
- **Apps:** you pick from your installed apps, or choose "All apps" (`*`).
  Adachi never blocks itself, your launcher, or the system UI — even under `*` —
  so the phone always stays operable.
- **Windows may wrap midnight**: `20:00–2:00` marked Friday means Friday
  20:00–24:00 *and* Saturday 00:00–2:00.
- **Rule precedence:** Allow > Block > Window/Quota > (no rule) allowed.
  If several Window/Quota rules match, **all** of them must pass.
- **Stricter anytime:** adding blocks, shrinking windows, lowering quotas — you
  can always do these. *Relaxing* anything (deleting a block, widening a window,
  raising a quota, adding an Allow rule) requires the **emergency unlock**.

> **Quotas are approximate.** App quotas count foreground minutes (accurate).
> Domain quotas count minutes with DNS activity for that domain (±20–30% —
> shared CDNs and background sync blur it). Think binge-stopper, not stopwatch.
> Blocks and windows are exact.

## Installing

You need a computer with `adb` (Android platform-tools) and a USB cable.

### Option A: build from source

Requirements: JDK 17 and the Android SDK (platform `android-34`).

```bash
git clone <this repo>
cd adachi
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Option B: install a prebuilt APK

```bash
adb install -r adachi.apk
```

> The examples below use the **debug** package name
> (`com.adachi.lockdown.debug`). If you build a release APK, the provisioning
> command must use the plain package name (`com.adachi.lockdown`) — the app
> shows the exact right command on its Setup screen.

## Phase 1 — Trial setup

**Do not skip this.** Run Adachi as a normal app for a few days first. If
anything misbehaves, you can still uninstall normally.

1. **Open Adachi.** Allow the notification permission when asked.
2. **Add rules.** On the *Domains* and *Apps* tabs, tap **+**. Start small:
   block your top 3 time-wasters. Remember: making rules stricter is always
   free; only relaxing them needs the weekly unlock — so don't over-configure
   on day one.
3. **Start the domain filter.** Dashboard → **Start domain protection** →
   accept the VPN consent dialog. (It's a local VPN; no traffic leaves your
   phone through it except forwarded DNS.)
   - Test: open a browser and visit a blocked domain. It should fail to load
     ("site can't be reached"). An allowed domain should work normally.
4. **Enable the app blocker.** Setup tab → **Open accessibility settings** →
   find **Adachi** → turn it on.
   - Test: open a blocked app. You should get bounced to the home screen with
     a blocking screen.
5. **Test the emergency unlock** (see below) once so you know how it feels.
   It's once per ISO week — using it today means waiting until Monday for the
   next one.

Live with this for a few days. Tune your rules. Only proceed when it feels
solid.

## Phase 2 — Full lockdown (device owner)

This is the "no mercy" step. After it:

- Adachi **cannot be uninstalled, force-stopped, or cleared**.
- **Safe mode** and **ADB** are disabled (no side-channel bypass).
- **VPN settings** are locked, and Adachi's VPN is set always-on.
- **Date & time settings** are locked (no clock-gaming your schedules).
- **Factory reset remains possible.** It's your ultimate escape hatch.

Steps:

1. **Back up anything you care about.** You must remove all Google accounts
   from the phone during provisioning (Android requirement).
2. On the phone: **Settings → Passwords, passkeys & accounts** → remove
   *every* account.
3. Enable **USB debugging** (Developer options) and connect to your computer.
4. Run the command shown on Adachi's **Setup** screen. For a debug install:

   ```bash
   adb shell dpm set-device-owner com.adachi.lockdown.debug/.admin.AdminReceiver
   ```

5. In the app: **Setup → Apply lockdown restrictions**.
6. Re-add your Google account afterwards if you want it.

Verify it's real: try uninstalling Adachi (blocked), reboot (protection comes
back on its own), try safe mode (disabled), try `adb shell` from your computer
(unauthorized — ADB is off).

**For the first 48 hours after provisioning**, full deactivation is freely
available from the Unlock tab — no weekly unlock needed. Use that window to
confirm everything works.

## Daily use

- **Dashboard (Home tab):** enforcement status, blocked-count today, protection
  checklist, recent blocks.
- **Add a block/window/quota:** Domains or Apps tab → **+**. Always allowed,
  even while locked down.
- **Relax a rule** (delete, disable, widen a window, raise a quota, add an
  Allow rule): needs an active emergency unlock window. Without one, the app
  tells you it's locked.
- **Rule conflicts:** if you create contradictory rules (e.g. Block and Window
  for the same domain), the stricter interpretation wins. Check the summary
  line under each rule to confirm what it does.

## Emergency unlock & pauses

**Weekly unlock** — your one deliberate escape per week:

- Unlock tab → **hold the button for 3 seconds**.
- Enforcement pauses for **30 minutes**; a notification counts down.
- During the window you can edit/relax rules, change the timezone manually,
  re-enable ADB, or fully deactivate Adachi.
- Once per ISO week (Mon–Sun). Used is used — no early refills. It survives
  reboots, and changing the phone's date to cheat it is both blocked and
  punished (a date jump consumes the week's unlock automatically).

**Malfunction pause** — for when Adachi misbehaves:

- Unlock tab → **Pause 10 min (malfunction)**, or the *Something's wrong?*
  button on any blocking screen.
- Pauses enforcement for 10 minutes, **once per day**, without spending your
  weekly unlock. Logged.

## Travel & timezone

- Automatic timezone is forced on before lockdown — for normal travel, the
  phone follows you and nothing is needed. Schedules always mean *local* time.
- To **manually override** the timezone: Unlock tab → **Travel mode**. This
  unlocks the date & time settings for 5 minutes, free of charge.
- **Warning:** changing the *date* (not the timezone) during those 5 minutes
  consumes the week's emergency unlock. The app warns you before you tap.

## Turning it off (teardown)

From easiest to most drastic — you can never be trapped:

1. **Pause:** malfunction pause (10 min/day) or weekly unlock (30 min/week).
2. **Full deactivation:** Unlock tab → **Deactivate completely**, available
   during the 48-hour grace period or inside any unlock window. This stops the
   VPN, lifts every restriction, relinquishes device ownership, and makes
   Adachi uninstallable again. Then uninstall normally from Settings.
   (Re-locking later requires the ADB provisioning command again.)
3. **Factory reset** — always available, always works:
   Settings → System → Reset options → Erase all data, or from recovery mode
   (power off → hold Power + Volume Down → Recovery → Wipe data/factory reset).
   This wipes the phone; afterwards you sign in with your Google account (FRP).
   Heavy friction by design — but an ironclad guarantee you keep your phone.

## Known limitations

- **Domain quotas are approximate** (±20–30%): measured from DNS activity, not
  screen time. Hard blocks and windows are exact.
- **Apps with their own encrypted DNS** (rare; e.g. some apps bundle DoH) can
  bypass domain rules. Block the *app* for those.
- **Domain rules are device-wide.** "Block youtube.com in Chrome but allow it
  in another browser" is not supported (block the YouTube *app* instead).
- **TCP DNS and cached DNS:** blocking applies at lookup time; connections an
  app already had open may linger briefly.
- The first 48 hours after provisioning are a grace period — deactivation is
  free then. Plan your provisioning day accordingly.

## Troubleshooting

**A blocked site still loads.** Its DNS answer may be cached. Wait a minute and
retry, or toggle airplane mode once. If it uses its own encrypted DNS, block
the app instead.

**An allowed app is being bounced.** Check for a `*` app rule with missing
Allow exceptions, or an exhausted quota (the blocking screen says the reason).
Use *Something's wrong?* for a 10-minute pause if Adachi is misbehaving.

**No internet at all.** Open Adachi — the dashboard shows VPN status. The VPN
fails open on crash-loops, so this should be rare; toggling airplane mode or
rebooting re-establishes the tunnel.

**I want my timezone back.** Unlock tab → Travel mode (free, 5 minutes).

**I need ADB for development.** Spend the weekly unlock → *Re-enable ADB* —
it's on for 30 minutes, then locked again automatically.

**I forgot the provisioning command / package name.** It's always on the
app's Setup screen, exact for your install.

**I truly want out.** Unlock tab → Deactivate (grace period or unlock window),
or factory reset. See [Teardown](#turning-it-off-teardown).
