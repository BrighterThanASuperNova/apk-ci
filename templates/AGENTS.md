# AGENTS.md

<!-- Canonical template: BrighterThanASuperNova/apk-ci/templates/AGENTS.md
     Vendored per app. Replace <APP> / <app> / <MainClass> and append the
     app-specific section at the bottom. -->

Guidance for AI agents working in this repository. Read this before editing.

## 1. What this repo is

A single-module Android app: `:app`, Kotlin only, XML layouts with
`findViewById`. **No Compose, no view binding, no DI, no coroutines, no
Retrofit.** AGP 8.2.2, Kotlin 1.9.22, Gradle 8.5, JDK 17, minSdk 26,
target/compile 34.

**Zero third-party dependencies** — androidx and Material only. That is a
deliberate asset: fast dependency resolution, nothing to keep from R8, no
transitive surface. Keep it that way.

## 2. HANDS OFF — CI enforces these, your build *will* fail

- **Do not edit `versionCode` or `versionName`.** They come from the
  `APP_VERSION_CODE` / `APP_VERSION_NAME` environment variables set by CI.
  Hand-editing them breaks the update feed for every installed copy.
- **Do not edit anything between `// --- CI-OWNED-BEGIN ---` and
  `// --- CI-OWNED-END ---`** in `app/build.gradle.kts`. That region holds the
  signing config, version wiring and the `preview` build type, and CI compares
  its hash against a canonical value.
- **Do not edit `app/src/main/java/**/update/*.kt`.** Those are vendored from
  `apk-ci` and CI fails on any drift. If one needs changing, say so in the PR
  body and a human will update `apk-ci`.
- **Do not change** `applicationId`, `namespace`, `minSdk`, `targetSdk`,
  `compileSdk`, or the AGP / Kotlin versions.
- **Do not add or edit anything under `.github/workflows/`.**
- **Do not add third-party dependencies** without justifying it in the PR body.
  Prefer platform APIs.
- **Do not delete or `@Ignore` a failing test to make CI green.** Fix the cause,
  or explain in the PR body why the test is wrong.

## 3. Build commands

```bash
chmod +x ./gradlew                  # required on Linux; it is committed mode 100644

./gradlew :app:assembleDebug        # local development
./gradlew :app:assemblePreview      # what ships to the phone from a branch
./gradlew :app:assembleRelease      # what ships from main

./gradlew :app:lintPreview
./gradlew :app:testPreviewUnitTest
./gradlew -PtestBuildType=preview :app:connectedPreviewAndroidTest   # needs a device
```

## 4. Output paths

```
app/build/outputs/apk/{debug,preview,release}/app-{debug,preview,release}.apk
app/build/outputs/mapping/release/mapping.txt
app/build/reports/lint-results-preview.html
app/build/reports/tests/testPreviewUnitTest/index.html
app/build/outputs/androidTest-results/connected/
```

## 5. Signing

One shared keystore signs debug, preview **and** release across all three apps,
so builds from any source are update-compatible. CI supplies
`ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and
`ANDROID_KEY_PASSWORD`.

Locally, without those, the default debug keystore is used — the resulting APK
will **not** install over a CI build. Uninstall first.

## 6. Channels — two copies of this app can be installed at once

| | Stable | Preview |
|---|---|---|
| Built from | `main` | every other branch |
| applicationId | `com.example.<app>` | `com.example.<app>.preview` |
| Launcher label | `<APP>` | `<APP> (preview)` |
| Minified | yes (R8) | no |

**Never assume only one copy is installed.** Preview installs *alongside*
stable, which is what makes it safe to ship your branch straight to a phone.

Because preview is unminified, **release can break in ways preview does not** —
R8 stripping a class the app needs at startup, most often.

## 7. Testing

- `app/src/test` — JVM unit tests. **New non-UI logic gets a unit test here.**
- `app/src/androidTest` — instrumented. The smoke test asserts `MainActivity`
  reaches `RESUMED`; it catches crashes on launch and **nothing else**.

Put `[skip-emulator]` in a commit message or PR title to skip the emulator on
preview builds. It is deliberately ignored on `main`.

## 8. The cross-app bridge

BugCatcher's broadcast contract is duplicated across all three repos
(`BugCatcherBridge.kt` here, `BridgeContract.kt` in BugCatcher) because separate
Gradle projects cannot share a module. If you touch it, the other repos must
change too — say so explicitly in the PR body.

Note that `sendBroadcast` with `setPackage` to a package not declared in
`<queries>` is a **silent no-op**: no exception, no log. Bridge changes need
matching `<queries>` entries or they fail invisibly.

## 9. Style

Kotlin official style, 4-space indent, `AppCompatActivity`, `findViewById`,
Material components from `com.google.android.material`. Match the file you are
editing rather than the guide.

## 10. PR conventions

Branch `opencode/<slug>`. Imperative title. The body must state: what changed,
why, how you verified it, and any HANDS-OFF file you had to touch.

## 11. When CI fails

Download the `failure-diagnostics` artifact from the run and read, in order:

1. the lint HTML report
2. the unit-test XML
3. `logcat.txt` — search for `FATAL EXCEPTION`

---

## App-specific notes

<!-- Append per app: what it does, its key classes, its permissions and any
     behaviour that is easy to break. -->
