# AdBlocker

**English** | [日本語](README.ja.md)

An ad blocker for Android. It runs a local VPN on the device and stops DNS lookups for ad and tracker domains. No root required, and nothing you do is sent anywhere.

> **The code, documentation and icon in this repository were made by [Claude Code](https://claude.com/claude-code) (Anthropic's AI coding tool).**

<p>
  <img src="docs/screenshots/en/home-dark.png" width="240" alt="Home (protected, dark)">
  <img src="docs/screenshots/en/stats.png" width="240" alt="Statistics">
  <img src="docs/screenshots/en/log.png" width="240" alt="Query log">
</p>

## Download

Download `AdBlocker-vX.Y.apk` from [Releases](https://github.com/tonbo2339/AdBlocker/releases/latest) and install it (you need to allow installing unknown apps).
After that, the app checks for new versions and updates itself.

- Requires Android 8.0 or later
- Still in development, so the version is 0.x

## Features

- **Ad blocking**: blocks connections to about 240,000 ad and tracker domains
- **Excluded apps**: chosen apps bypass ad blocking and connect as usual (for apps that break with ad blocking)
- **My rules**: block or allow domains yourself (a rule covers the domain and its subdomains; allow rules win)
- **Pause**: let everything through for 5 minutes, 15 minutes or 1 hour without turning the VPN off
- **Query log**: the last 500 lookups and whether each was blocked. Tap one to block or allow it. Kept only in memory, and can be turned off
- **Statistics**: today's counts, a 7-day chart, all-time totals, and the most-blocked domains. Only counts are saved, not which sites you visited
- **Private DNS warning**: tells you when "Private DNS" is set to a hostname, which stops blocking from working
- **Automatic blocklist updates**: fetches the latest lists once a day
- **Automatic app updates**: checks GitHub Releases once a day and installs new versions automatically (or just notifies you, if you prefer)
- **Wi-Fi only**: keep automatic updates (blocklist and app) off mobile data
- **Quick Settings tile**: turn blocking on / off from the notification shade (tap it while paused to resume)
- **Notification settings**: app updates, blocklist updates and the running indicator can each be turned on / off
- Resumes automatically after a reboot or an app update; works with Always-on VPN
- Light / dark mode
- English / Japanese (follows the device language; on Android 13+ you can pick a language per app in Settings)

## Limitations

- Android allows only one VPN at a time. Starting another VPN app turns AdBlocker off
- Blocking doesn't work while "Private DNS" in Settings is set to a hostname (use "Automatic" or "Off"). The app shows a warning when this happens
- **The blank space where an ad was can't be removed** (DNS blocking only stops the connection; it can't change page layouts). For browsers, use something like Firefox + uBlock Origin as well
- Ads served from the same domain as the content (such as YouTube video ads), hard-coded IP addresses, and apps' own DNS-over-HTTPS can't be blocked
- DNS lookups that fall back to TCP for large responses (rare) aren't supported

## Blocklists

| Source | License |
|---|---|
| [StevenBlack/hosts](https://github.com/StevenBlack/hosts) | MIT |
| [AdGuard DNS filter](https://github.com/AdguardTeam/AdGuardSDNSFilter) | GPL-3.0 |

- Fetched once a day (and with "Update Now" in the app). Nothing is downloaded if the ETag hasn't changed
- Each source is stored separately; if a source can't be fetched, its previous copy keeps being used. A list with fewer than 1,000 rules is treated as broken and ignored
- Allow rules (`@@||domain^`) are honored
- Until the first download, the StevenBlack list bundled with the app (`app/src/main/assets/blocklist.txt`) is used

To regenerate the bundled list:

```
curl -sSL https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts \
  | tr -d '\r' | awk '$1=="0.0.0.0" && $2!="0.0.0.0" {print tolower($2)}' | sort -u \
  > app/src/main/assets/blocklist.txt
```

## Building

Open this folder in Android Studio and run it. From the command line:

```
gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
gradlew assembleRelease        # needs a signing key (see "Signing" below)
gradlew testDebugUnitTest      # unit tests (packets / DNS / rule parsing / version comparison / my rules)
gradlew lintDebug
```

### Releases and automatic updates

The app reads `https://api.github.com/repos/tonbo2339/AdBlocker/releases/latest`, and if the tag (such as `v0.1`) is newer than its own `versionName`, it downloads and installs the attached `.apk`.

1. Bump `versionCode` and set `versionName` to the new number in `app/build.gradle.kts`
2. Build, and attach the APK to a release tagged `v<versionName>`

Before installing, the app checks the package name, that the version is higher, and that the signature matches the installed app.
On Android 12 and later, updates after the first one install without asking (the first one shows a confirmation).

### Signing

APKs in Releases are signed with the author's release key (the key and its password are not in this repository). An app can only be updated in place by an APK signed with the same key, so if you install an APK you built yourself, updates from Releases won't work (you'd need to uninstall first).

To sign your own release build, create a `keystore.properties` (`storeFile` / `storePassword` / `keyAlias` / `keyPassword`), add `signing.properties=<path to that file>` to `local.properties`, and run `gradlew assembleRelease`.

## Project layout

| File | Contents |
|---|---|
| `AdBlockVpnService.kt` | The VPN itself (reads / writes the tun, decides what to block, forwards to upstream DNS) |
| `Packets.kt` / `Dns.kt` | Parsing and building IPv4 / IPv6 + UDP packets and DNS messages |
| `BlockList.kt` / `DomainSet.kt` | Loading and matching the blocklist (parent domains and allow rules) |
| `RuleParser.kt` | Parses one line in hosts / domain / Adblock format |
| `BlockListUpdater.kt` / `BlockListWorker.kt` | Downloading blocklists and the daily automatic update |
| `UpstreamDns.kt` | Choosing upstream DNS servers (the network's DNS first, public DNS as backup) |
| `AppUpdater.kt` / `AppUpdateWorker.kt` / `InstallResultReceiver.kt` | Automatic updates from GitHub Releases |
| `Filter.kt` / `UserRules.kt` / `Pause.kt` | Deciding each lookup (pause, my rules, blocklist) |
| `QueryLog.kt` / `StatsStore.kt` | Query log (in memory) / daily counts |
| `PrivateDnsMonitor.kt` | Detecting "Private DNS" set to a hostname |
| `MainActivity.kt` | Home / Statistics / Log tabs |
| `SettingsActivity.kt` / `RulesActivity.kt` / `AppListActivity.kt` | Settings / my rules / excluded apps |
| `DomainActions.kt` | The block / allow menu for a domain |
| `CardLayout.kt` / `BarChartView.kt` | Rounded cards / the 7-day chart |
| `AdBlockTileService.kt` | Quick Settings tile |
| `BootReceiver.kt` | Resuming after a reboot or an update |
| `Notifications.kt` / `Prefs.kt` | Notifications / saved settings |

## License

[MIT License](LICENSE). Each blocklist is under its own license (see [NOTICE](NOTICE)).
