# Dependency reviews

One row per coordinate in `gradle/libs.versions.toml`, written before the catalog entry. Matrix classes are from the
dependency-guard decision matrix. Every `block_pending_human_review` or `block` row carries the human disposition
that let it in; no row is accepted by default.

Controls on every row: repositories restricted to Google Maven groups `com.android.*`, `com.google.*`, `androidx.*`
plus Maven Central (no Gradle Plugin Portal), `FAIL_ON_PROJECT_REPOS`, every configuration locked in
`LockMode.STRICT` (`*.lockfile`), and SHA-256 verification metadata (`gradle/verification-metadata.xml`, trust on
first use from the Gradle cache that `paddock-android` filled).

## Sources

- **Carried over, not re-run:** the same coordinates and versions were reviewed for `paddock-android` and approved
  by Hung Vo on 2026-10-01 (`paddock-android/docs/dependency-reviews.md`, which in turn cites
  `steamos-companion-android`'s 2026-09-25 and 2026-09-28 Socket results). Station AR adopts the same toolchain by
  decision D5 (2026-10-22, "Gradle, Paddock pins").
- **The Socket CLI isn't installed on this machine** (2026-10-22, `command -v socket`), so no fresh score was taken.
  Any coordinate not in the table below is `block_pending_human_review` until it has its own row and Hung's decision.

## Reviews

| Coordinate | Scope | Socket result | Matrix | Disposition |
| --- | --- | --- | --- | --- |
| `org.jetbrains.kotlin:kotlin-stdlib` 2.4.20 (+ `org.jetbrains:annotations` 13.0) | Ships (the only runtime library; R8 shrinks it) | Carried over: package 100; deep 89 overall; medium `networkAccess` and `usesEval` are standard-library APIs | block_pending_human_review | **Approved** (Paddock, 2026-10-01; D5 2026-10-22). Inseparable from Kotlin |
| `org.jetbrains.kotlin:kotlin-gradle-plugin` 2.4.20 (`kotlin.jvm`; AGP's built-in Kotlin uses it for `:app`) | Build only | Carried over: package 100; deep over 516 transitives: overall 27, supply chain 27; high `cve` in npm `serialize-javascript` 6.0.2 and `obfuscatedFile` in `commons-io` 2.16.1, mostly the Kotlin/JS npm tree | block | **Approved as build-only tooling** (Paddock, 2026-10-01; D5). Nothing of it reaches the APK |
| `com.android.tools.build:gradle` 9.3.3 (AGP) | Build only | Carried over: no package result (404 / rate limit); its POM depends on `kotlin-gradle-plugin` and `commons-io`, inheriting the alerts above | block_pending_human_review | **Approved as build-only tooling** (Paddock, 2026-10-01; D5) |
| `junit:junit` 4.13.2 (+ `org.hamcrest:hamcrest-core` 1.3) and `org.jetbrains.kotlin:kotlin-test-junit` 2.4.20 | `:core` tests only | Carried over: package 100; deep 36 (hamcrest); high `obfuscatedFile` (`MaxHistory.class`); EPL-1.0 licence flags | block | **Approved, test only** (Paddock, 2026-10-01; D5). Neither the flagged class nor the licence reaches the APK |

`app/gradle.lockfile`'s `releaseRuntimeClasspath` holds exactly `kotlin-stdlib` 2.4.20 and `annotations` 13.0.

## Not dependencies

- **Laptop tools for the sender** (`ffmpeg`, `wf-recorder`, `intel-media-driver`) are system packages on the
  laptop, installed by the person, never by the build. The sender is Python 3 standard library only.
- **The Phase 00 probe** (`tools/feasibility/`) is built from the Android SDK alone (`javac`, `d8`).
