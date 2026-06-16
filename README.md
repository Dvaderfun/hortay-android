# Hortay

*Pronounced **hor-TYE** /ɦorˈtaj/ — Ukrainian for "scroll!", the imperative of гортати (to leaf through)*

A Twitter-style reader for Telegram channels. Read your subscribed channels as one chronological feed instead of a chat list.

No Firebase, no Crashlytics, no analytics, no third-party push. INTERNET permission is used only for Telegram itself and, in guest mode, anonymous `t.me/s/` previews.

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=dev.lyo.hortay">
    <img alt="Get it on Google Play" src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" height="60">
  </a>
</p>

<p align="center">
  <img src="playstore/screens/photos/01-hero.jpg" alt="Hortay feed" width="280">
</p>

## Highlights

- **Two modes** — full MTProto via TDLib, or guest mode reading public `t.me/s/<channel>` previews without an account
- **Reader-first UX** — OldestUnreadFirst boundary, dwell-based read tracking, snap scroll, folder tabs, scroll-to-bottom with unread badge
- **Full post features** — polls (vote, quiz reveal, multi-answer), reactions, custom emoji, animated stickers (TGS/WebM/WEBP), albums, inline videos, round video messages
- **Comments overlay** with predictive back, reply chains, and a user-profile sheet
- **Material 3 Expressive** — dynamic color, motion scheme, reduced-motion aware
- **English and Ukrainian** with full plurals

## Building

Requirements: JDK 17, Docker (for the TDLib build), Android SDK

1. Get `api_id` / `api_hash` at <https://my.telegram.org> → API development tools
2. Copy `local.properties.example` to `local.properties` and fill in the credentials
3. Build TDLib (Docker; ~30 min first run, ~10 min after):

   ```bash
   ./scripts/update-tdlib.sh                  # upstream master (last build is recorded in scripts/tdlib-version.txt)
   ./scripts/update-tdlib.sh 8fc2344f         # specific commit SHA
   ```

4. Install a debug build:

   ```bash
   ./gradlew :app:installDebug
   ```

The Gradle wrapper is checked in — no separate `gradle wrapper` step

## Stack

AGP 9.2.1 · Gradle 9.5.1 · Kotlin 2.4.0 (K2) · Compose Multiplatform 1.12.0-alpha01 · Material 3 1.5.0-alpha19 · Koin 4.2.1 · minSdk 26 / targetSdk 36 · TDLib pinned in `scripts/tdlib-version.txt` · Coroutines 1.11.0 · Coil 3.5.0-beta01 · SQLDelight 2.3.2 (guest mode only) · Ktor 3.5.0 · DataStore 1.2.1

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for module layout, load-bearing decisions, and TDLib usage rules.

Short version: KMP/CMP, single-Activity, Compose-only — `:androidApp` (shell), `:shared` (KMP library with all UI + data), `:libtdlib` (vendored TDLib JNI), `:iosApp` (Xcode shell), `:baselineprofile`. DI via Koin 4.x. No Hilt, no Retrofit, no Firebase.

## Contributing

Issues and PRs welcome. Read [ARCHITECTURE.md](ARCHITECTURE.md) before non-trivial changes — many decisions are load-bearing and documented inline. Conventional Commits with package scopes: `feat(timeline):`, `fix(media):`, `build(beta):`, etc.

## Security

See [SECURITY.md](SECURITY.md) for how to report vulnerabilities.

## License

[GPL-3.0-or-later](LICENSE)

---

Made in Ukraine 🇺🇦 · [Українською](README.uk.md)
