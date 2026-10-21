# Blocker

Site and app blocking, plus home-grayscale, built into the launcher rather than as a
separate app. The launcher process is the least likely process on the phone to be killed,
and the accessibility service, usage-stats permission and prefs it needs were already here.

## What it does

Everything with a dial is in **Settings > Blocker**, next to the rest of Olauncher's
settings. Nothing needs a rebuild to change.

| Setting | Meaning |
|---|---|
| Blocked apps | Tick any installed apps. No free minutes inside the window. |
| Time limited apps | Tick any installed apps. They share one allowance per evening. |
| Time limit | The shared allowance for the limited apps. |
| Blocking starts / ends | One window for all of it. Crossing midnight is fine. |
| Home Wi-Fi (grayscale) | Tap while on your home network to set it. Long press clears it. |
| Teleport app | Where the "teleport me" button on the allowance-over screen goes. |
| Blocked words in browser | Comma-separated. Any page in a watched browser showing one of them is blocked. |
| Excuses file | Tap to share the log, long press to copy its path. |

An app in both lists counts as blocked — the stricter rule wins.

Blocked **domains** (`youtube.com`, `reddit.com`, …) stay in
`app/src/main/java/app/olauncher/data/BlockerConfig.kt`, along with which browsers get their
address bar read. Typing domains on a phone keyboard is worse than editing that list. An app
ticked in Settings is judged as an app even when it is also a watched browser.

## The two screens

**Entry gate** — first contact tonight with something that has no free minutes. Two gates,
both required: ten seconds have to pass **and** a reason of at least 15 characters has to be
typed. Buys five minutes.

**Allowance over** — a limited app's budget is spent, or five minutes ran out. Says how many
minutes went by and how often this has already happened tonight, asks whether they were well
spent, and offers three ways on:

- **Teleport me to <app>** — opens the app chosen in Settings.
- **Stop, switch the phone off** — leaves the app and locks the screen.
- **Keep hanging around, 5 more minutes** — the only one gated behind writing an answer.
  Teleporting and stopping are deliberately free; making the good exits harder than the bad
  one would be backwards.

## Blocked words

Separate from blocked domains: a domain blocks a site, a word blocks any page that shows it.
Typing `instagram` catches instagram.com, a Google result for it, and a page that embeds it.

The match is against the text the page **renders**, read off the accessibility tree, not its
HTML source. A word that only exists in a meta tag, in a script, or behind an unopened menu is
not there to be found. The flip side is that it matches wherever the word appears, so a news
article that merely mentions Instagram is blocked too. Pick words you never want to read,
rather than words that are merely associated with what you want to avoid.

The scan runs at most every 1.5 seconds and stops after 1500 nodes or 40 000 characters,
because it walks the page on the main thread. A long article past the cap is only partly
searched: a missed keyword costs nothing, a janky phone costs every app on it.

## The excuses log

A TSV in external app storage, so it can actually be read back:

```bash
adb pull /sdcard/Android/data/app.olauncher/files/excuses.tsv
```

`date, time, app, kind, unlock_no, minutes_used, action, answer`

`minutes_used` is the "how long did I actually use it afterwards" column: on an
`allowance_over` row it is the time spent in that app during the allowance that just ended.
`unlock_no` is how many times that app has been talked into tonight. Counters reset at the
start of every window, not at midnight.

## Setup after installing

Use `app.olauncher.debug` instead of `app.olauncher` for a debug build.

1. **Accessibility** — Settings > Accessibility > Olauncher > on. Same switch as the
   double-tap-to-lock gesture. Nothing is blocked without it.
2. **Usage access** — Settings > Apps > Special app access > Usage access > Olauncher.
   Needed for the app budgets.
3. **Display over other apps** — Settings > Apps > Special app access > Display over other
   apps > Olauncher. This is what lets the reflection screen come up over Chrome; without it
   the block silently does nothing.
4. **Grayscale permission**, over adb, once:
   ```bash
   adb shell pm grant app.olauncher android.permission.WRITE_SECURE_SETTINGS
   ```
   Survives reboots and `adb install -r`; a full uninstall clears it.
5. **Location** — the launcher asks on first open, once a home Wi-Fi is set. "While using the
   app" is enough. Reading a Wi-Fi SSID needs location permission on Android 10+; there is no
   way around it.

Optional, and the point of the whole exercise: block yourself from turning it off, using the
DeviceAdmin receiver already in the manifest.

`X_how_tos.md` in the parent folder has the build, emulator and phone commands.

## Turning it off again

**Stuck on a grey screen with the app already gone?** Settings > Accessibility > Colour
correction > off. No adb needed. Over adb instead:

```bash
adb shell settings put secure accessibility_display_daltonizer_enabled 0
```

That is the one thing here that outlives the app, and the rest of this section exists to keep
you from getting into that state.

### Without uninstalling anything

| To stop | Do this |
|---|---|
| All blocking | Settings > Accessibility > Olauncher > off |
| Grayscale | Long press the *Home Wi-Fi (grayscale)* row in Settings > Blocker |
| One app's rule | Untick it in *Blocked apps* or *Time limited apps* |
| A keyword | Clear it from *Blocked words in browser* |
| A domain | Edit `BlockerConfig.blockedDomains` and rebuild - domains are not in Settings |

The accessibility switch is the master off switch: the whole blocker runs from that service, so
turning it off stops app blocking, domain blocking and keyword blocking at once, and leaves
Olauncher working as an ordinary launcher. There is deliberately no separate "blocker off" row,
because a switch you can flick in two taps while standing in front of the thing you are trying
not to open is not a switch, it is a formality.

**Grayscale is not covered by that switch.** It runs from the launcher's `onResume`, not from
the accessibility service, so it has to be turned off on its own.

### Removing the app

Your existing Olauncher is not at risk. This build's application id is `app.olauncher.debug`
(`applicationIdSuffix ".debug"`), so it installs *beside* `app.olauncher` rather than replacing
it. Home apps, renames and every other setting live in the other app's data and are never
touched. Switching back is a matter of pointing the home role at it.

Do it in this order:

1. **Turn grayscale off first** - long press *Home Wi-Fi (grayscale)*, or Settings >
   Accessibility > Colour correction > off.
2. Keep the log if you want it, because uninstalling deletes it:
   ```bash
   adb pull /sdcard/Android/data/app.olauncher.debug/files/excuses.tsv
   ```
3. Settings > Apps > Default apps > Home app > your previous launcher.
4. Uninstall `app.olauncher.debug`.

Step 1 is not optional housekeeping. The colour correction setting belongs to the system, not
to this app: the app sets it, and uninstalling the app does not put it back. Uninstall while
grayscale is on and the phone stays grey with nothing left on it that knows how to undo that.

### What this app writes outside its own sandbox

Exactly one thing: the system colour-correction mode, as two keys in `Settings.Secure`
(`accessibility_display_daltonizer_enabled` and `accessibility_display_daltonizer`). It is the
same setting as Settings > Accessibility > Colour correction, so turning grayscale on overwrites
a correction mode set there by hand.

Everything else - the app lists, the window, the keywords, the unlock counters, the excuse log -
lives in the app's own storage and goes away with it. The `WRITE_SECURE_SETTINGS` grant goes
away with a full uninstall too, and has to be re-granted over adb on the next install.

### If you enabled uninstall protection

Device admin has to be deactivated before Android will let the uninstall through: Settings >
Security > Device admin apps > Olauncher > deactivate. That is the whole point of enabling it,
but it is worth knowing which switch is holding the door shut before you need to know.

## Known ceilings

- **Chrome view id.** Browser blocking reads `com.android.chrome:id/url_bar` out of the
  accessibility tree. A Chrome update that renames it stops browser blocking with no error.
  The robust alternative is SNI filtering in a `VpnService`; DNS filtering does *not* work,
  Chrome's Secure DNS bypasses it.
- **No package filter on the accessibility service.** Since the watched set is whatever is
  ticked in Settings, the service receives events from every app. `Blocker.isWatched` is the
  filter instead, and it runs on every event the phone produces, so it stays cheap.
- **Usage is read on a background thread**, because the service shares the launcher's process
  and querying an evening of the event log inline stalls accessibility dispatch badly enough
  to make the foreground app ANR. A budget gate can be one refresh (10s) late, which the 20s
  re-check picks up.
- **Grayscale is checked on launcher resume**, not the moment you walk in the door. SSID reads
  are redacted for background apps, so doing it live would need background location and a
  foreground service.
- **Grayscale shares one system setting** with Settings > Accessibility > Color correction, so
  it overwrites a correction mode set by hand there.
- **Audio keeps playing** behind the reflection screen until you dismiss it.
- **Dialogs in Settings need their window flag cleared.** MainActivity draws under the system
  bars via FLAG_LAYOUT_NO_LIMITS, and a dialog opened from it inherits that: its rows are drawn
  one status bar below the area that accepts touches, so every row answers a tap meant for the
  row above and the OKAY button cannot be hit at all. `showInPlace()` clears the flag. Any new
  dialog in this fragment has to go through it.
- **"Stop, switch the phone off" locks the screen, it does not power the phone down.** On
  Android 9 and up the accessibility service does it directly; below that it falls back to
  device admin, which is the permission behind the double-tap-to-lock setting, and says so in a
  toast if that is off too.
- **YouTube and Reddit are blocked in the browser only.** Their apps are not watched unless you
  tick them in Settings.
- **Reinstalling can leave the accessibility service enabled but not bound**, and then nothing
  is blocked and nothing is logged. After `install -r`, re-write the setting and open the
  launcher once:
  ```bash
  adb shell settings put secure enabled_accessibility_services \
      app.olauncher.debug/app.olauncher.helper.MyAccessibilityService
  adb shell settings put secure accessibility_enabled 1
  adb shell am start -n app.olauncher.debug/app.olauncher.MainActivity
  adb shell dumpsys accessibility | grep "Bound services"   # must not be empty
  ```

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

Covers the window maths (16:00–04:00 crosses midnight, so "today at 16:00" is wrong at 02:00)
and address-bar host parsing, in `BlockerRulesTest`.
