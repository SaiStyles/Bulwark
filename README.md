# Bulwark

**Take your phone back — without root, without unlocking the bootloader, without buying a different phone.**

Bulwark removes preinstalled software, revokes permissions, cuts apps off the
internet, and shows you what those apps did while you weren't looking. It runs
on stock, locked, unrooted Android through
[Shizuku](https://github.com/RikkaApps/Shizuku), and it holds no `INTERNET`
permission — so it could not phone home even if it wanted to.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84.svg)](#getting-started)
[![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF.svg)](#how-it-is-built)
[![No network permission](https://img.shields.io/badge/INTERNET%20permission-none-success.svg)](#verify-it-instead-of-trusting-it)

---

## Why this exists

You own the hardware. You do not control the software on it.

You cannot remove what came preinstalled. You cannot stop an app talking to a
server you never chose. Every permission you grant stays granted unless you go
hunting for it. None of that is a bug — it is the arrangement, and nobody asked
you.

Android sandboxes every app properly. The problem is not missing security; it
is that the sandbox leaks through every "Allow" you tap, every preinstalled app
you cannot uninstall, and every tracker quietly working in the background.

Tools to plug those leaks exist, and they are good. They are also four separate
enthusiast apps and a wiki:

| The job | What people use today |
|---|---|
| Block apps from the internet | NetGuard / RethinkDNS |
| Kill trackers | TrackerControl |
| Remove bloatware without root | Canta / Universal Android Debloater |
| Lock down permissions for good | scattered, mostly manual |

Bulwark does all four jobs in one app. Everything in one place, every change
named, and every change reversible.

## What it does

- **Remove bloatware.** Switch an app off and put it back, or uninstall it and
  put it back where the phone still has a copy to restore from. Where it does
  not, Bulwark tells you instead of offering a button that would fail.
- **Revoke permissions** — one app at a time, or one permission across every
  app on the phone that holds it.
- **Close the accesses Android buries**: screen overlay, device admin,
  notification access, all-files. Plus one switch Android has no screen for at
  all — reading and writing your clipboard.
- **Cut an app off the internet.** A local VPN routes only the apps you blocked
  into a tunnel that goes nowhere. Every other app bypasses it entirely, at
  full speed — their traffic never passes through Bulwark at all. Blocks hold
  while Bulwark's tunnel is running; switch on Android's own always-on VPN and
  they hold across restarts too.
- **See what apps did while you weren't looking**: wakeups, sensor reads,
  standing location requests, battery exemptions, and Android's own app-op
  ledger — which the system records and shows nobody.
- **Spot known monitoring software**, matched entirely offline against
  [Echap's stalkerware indicators](https://github.com/Te-k/stalkerware-indicators)
  by package name *and* signing certificate, so renaming an app does not hide
  it.
- **Check your certificate store** for CAs somebody added, which is how traffic
  interception usually starts.

Every destructive action is confirmed by Android's own fingerprint or PIN
prompt, written to a local log, and listed by name on a screen with its own
undo.

## What it is, and what it is not

- ✅ **Stock, locked, unrooted Android** — flagship or budget, new or years old.
  No custom ROM, because most phones will never have one.
- ✅ **Free forever.** No accounts, no servers, no API keys, nothing to sell.
- ✅ **Keeps your OTA updates.** Shizuku gives ADB-level authority without root,
  so the bootloader stays locked and official updates keep arriving.
- ✅ **Reversible by construction.** Disabling comes before uninstalling, and
  nothing destructive ships without a tested undo.
- ➖ **Not GrapheneOS.** GrapheneOS replaces the operating system and is
  stronger for it — on the handful of phone models it supports. Bulwark turns
  the knobs a locked, stock phone already exposes, on very nearly all of them.
  Different jobs.
- ➖ **Not "untraceable".** Your carrier and the cell network still see you.
  Bulwark works on apps, not physics.

---

## Getting started

Bulwark does nothing until Shizuku is running. That is the whole setup.

### 1. Install Shizuku

From [Shizuku's releases](https://github.com/RikkaApps/Shizuku/releases),
F-Droid, or the Play Store. Shizuku is a separate, well-established project —
it is what gives Bulwark ADB-level authority without root.

### 2. Start Shizuku

**Android 11 and later — no computer needed.**

1. Enable developer options: **Settings → About phone → tap Build number seven
   times**.
2. **Settings → Developer options → Wireless debugging → on.** Your phone needs
   to be on Wi-Fi.
3. Open Shizuku and choose **Start via Wireless debugging**. It will ask you to
   pair: in **Wireless debugging → Pair device with pairing code**, a code and
   port appear — give those to Shizuku.
4. Shizuku says **"Shizuku is running"**. Done.

**Android 10 and earlier — a computer, once per boot.**

```
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

### 3. Install Bulwark, then restart Shizuku

**This order matters more than anything else on this page.**

Shizuku hands its authority to apps *at the moment its service starts*. An app
installed afterwards gets nothing — and it does not fail loudly, it just sits
there looking like it is broken. So: install Bulwark, then stop and start
Shizuku again.

### 4. Open Bulwark and grant it

Shizuku will ask whether Bulwark may use it. Say yes.

### You will also need a screen lock

A PIN, pattern or password. Every destructive action is confirmed by Android
itself, so with no screen lock there is nothing to confirm with. There is a
real reason for that: an app with accessibility access can read Bulwark's
screen and tap its buttons, but it cannot press a prompt that runs inside the
system process.

### After a reboot

Shizuku's pairing survives reboots. The running service does not, and wireless
debugging often switches itself off too — turn it back on, open Shizuku, tap
**Start**. Bulwark keeps its grant.

### If a screen says "Bulwark could not read…"

That amber text means Shizuku is not running. Bulwark will not pretend to know
something it could not read.

---

## Verify it instead of trusting it

Bulwark asks for shell-level authority over your phone. Scepticism is the
correct response to a request that large, so the promises are built to be
checked rather than believed.

**It has no `INTERNET` permission.** Android will not let it reach a network,
whatever its code says.

```
aapt2 dump permissions bulwark.apk | grep INTERNET     # expect nothing
```

That is enforced by the build, not by anyone remembering. A Gradle task reads
the **merged** manifest — so a dependency dragging one in still counts — and
fails the build on `INTERNET`, `ACCESS_NETWORK_STATE` or `ACCESS_WIFI_STATE`.
It is bound to `assemble`, so it cannot be skipped by building a different way.
Forty lines at the bottom of [`app/build.gradle.kts`](app/build.gradle.kts),
worth reading before you trust any of this.

**Every release is signed with one key:**

```
SHA-256  e3:cb:cb:02:2d:67:c7:af:ff:d8:6f:ee:c4:e6:c2:4e:
         73:ed:f6:11:2c:fc:32:9c:fb:cf:99:fc:2e:47:9e:7a
```

```
apksigner verify --print-certs bulwark.apk
```

If that digest does not match, what you downloaded did not come from here.

**The build is reproducible.** Two checkouts of the same commit produce a
byte-identical unsigned APK — and so does a build in a completely different
directory, because nothing about *where* you build leaks into the binary.
Signed APKs differ only inside the signature block, where the padding is
deliberately random. So the code in a release can be checked against the source
instead of taken on faith.

**It cannot remove what keeps your phone working.** Telephony, messaging and
core system components are refused inside the privileged process — below the
user interface, so no UI bug and no bad list can reach past the guard.

**Those guards keep their names in release builds**, deliberately. R8 shrinking
is on, because code that is not in the APK cannot be exploited — but
[`app/proguard-rules.pro`](app/proguard-rules.pro) keeps `ProtectedPackages`
and `CommandSafety` unrenamed, so you can decompile a release and confirm the
safety code is genuinely in there rather than taking this paragraph's word for
it.

---

## How it is built

Kotlin and Jetpack Compose, ~14,500 lines across 61 source files, minSdk 26
(Android 8.0). 364 unit tests and 96 instrumented tests.

The privileged surface is deliberately small: a single package
(`app/src/main/java/com/bulwark/app/shizuku/`) touches system APIs, and
everything else is ordinary code that cannot reach the platform even by
mistake. Policy is pure functions — what may be removed, what must be refused,
what a screen is allowed to *claim* — so the dangerous decisions are testable
without a device, and most of them are.

Two ideas run through the whole codebase:

**A rule is not its enforcement.** A firewall rule changes no system state; it
is in force while a tunnel runs and forgotten when one does not. The app keeps
those two facts apart everywhere and always says which one it actually has.

**Never report a state you have not just read back.** Not what was asked for,
not what was cached, not what worked last time. Where the platform refuses to
answer — and on Android 12+ there are questions it does refuse — the app says
"cannot tell" rather than guessing in the reassuring direction. A false sense
of protection is worse than none.

> **A note on the code comments.** Many reference design documents —
> `safety-rules.md`, `threat-model.md`, `design.md` — that are not in this
> repository. Those are the project's working notes and they stay private. The
> reasoning that matters for reading the code is in the code, which is why the
> comments are as long as they are.

## What it deliberately does not do

Saying no is most of the design:

- **No tracker or ad blocklists, and no traffic log.** Both would mean routing
  every app's traffic through Bulwark — the expensive half, the complicated
  half, and the half where Bulwark would simply be a worse NetGuard. Blocked
  apps go into a dead-end tunnel; nobody else's traffic enters it at all.
- **No bulk selection.** Forty changes at once means nobody can tell which one
  broke the phone.
- **No telemetry, no analytics, no crash reporting.** Not as a promise — as a
  build failure if it ever changes.
- **No dynamic colour theming**, so the app looks the same on every phone and a
  screenshot in a bug report means something.

---

## Standing on other people's work

Bulwark's argument for existing is integration, not invention. Nearly every
capability here already exists and is done well by somebody else:

[Shizuku](https://github.com/RikkaApps/Shizuku) — the privilege model this is
entirely built on ·
[NetGuard](https://github.com/M66B/NetGuard) ·
[RethinkDNS](https://github.com/celzero/rethink-app) ·
[TrackerControl](https://github.com/TrackerControl/tracker-control-android) ·
[Canta](https://github.com/samolego/Canta) ·
[Universal Android Debloater](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation) ·
[Island](https://github.com/oasisfeng/island) ·
[Blocker](https://github.com/lihenggui/blocker) ·
[Exodus Privacy](https://github.com/Exodus-Privacy/exodus) ·
[Echap's stalkerware indicators](https://github.com/Te-k/stalkerware-indicators)

## Contributing

Device reports are the most useful thing you can send. Android's behaviour
varies by manufacturer, and a note saying which phone you ran this on and what
it did is worth more than most patches.

Notes on unclear wording are welcome too — what the app *says* matters as much
as what it does, and several improvements have come from exactly that.

## License

[GPLv3](LICENSE). If you ship a modified version, ship the source too.
