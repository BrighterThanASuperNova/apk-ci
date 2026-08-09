# apk-ci

Shared CI/CD for personal Android apps. Build → sign → test → publish a signed APK
that the app itself offers to install, so a change prompted from a phone ends up
running on that phone a few minutes later.

Consumed by [`AppBlocker`](https://github.com/BrighterThanASuperNova/AppBlocker),
`FacebookGuard` and `BugCatcher`. APKs are published to the public
[`apk-drops`](https://github.com/BrighterThanASuperNova/apk-drops) repo.

This repo is public on purpose: it contains no secrets, and public means the
reusable workflow resolves without an access setting and the vendored-file drift
check can clone it without a token.

## What's here

| Path | Purpose |
|---|---|
| `.github/workflows/android-build-ship.yml` | The reusable `workflow_call` pipeline |
| `shared/android/` | Canonical in-app updater sources, vendored into each app |
| `templates/` | Per-app files: `AGENTS.md`, caller `build.yml`, `opencode.yml`, `autofix.yml` |
| `canonical/` | SHA-256 of each app's CI-owned `build.gradle.kts` region |

## Channels

Every branch publishes to **preview**; only `main` publishes to **stable**.

| | Stable | Preview |
|---|---|---|
| Trigger | push to `main` | push to any other branch |
| applicationId | `com.example.<app>` | `com.example.<app>.preview` |
| Launcher label | `AppBlocker` | `AppBlocker (preview)` |
| Minified | yes (R8) | no |
| Installs over stable? | — | **no, it installs alongside** |

The separate `applicationId` is the safety property: **an agent branch can never
overwrite your working app**, and it can never publish to stable.

## Feed URLs

Six permanent pointer tags, clobbered on every build. `releases/latest` is a single
per-repo pointer and cannot serve six streams, so each stream gets its own fixed tag:

```
https://github.com/BrighterThanASuperNova/apk-drops/releases/download/<app>-<channel>/<app>-<channel>.json
https://github.com/BrighterThanASuperNova/apk-drops/releases/download/<app>-<channel>/<app>-<channel>.apk
```

Each build also publishes an immutable `<app>-<channel>-v<versionCode>` release —
that's the history, and where you grab a known-good APK from.

## Signing

One keystore signs **debug, preview and release** across all three apps, so builds
from any source are update-compatible. Fingerprint:

```
SHA256: 29:D0:2D:19:90:5B:66:F8:D4:04:57:61:13:4F:24:9D:6A:EE:87:50:B8:AE:A9:0C:FF:DC:BB:90:D4:79:E9:E5
```

Verify any APK matches with:

```bash
apksigner verify --print-certs app-preview.apk
```

If that fingerprint ever differs, **do not install it** — a mismatched signature is
either a misconfigured build or a tampered artifact, and installing it is impossible
anyway (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).

### Required secrets, per app repo

| Secret | Notes |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Single-line base64 of the `.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Store password |
| `ANDROID_KEY_ALIAS` | `apkci` |
| `ANDROID_KEY_PASSWORD` | Same value as the store password |
| `DROPS_REPO_TOKEN` | Fine-grained PAT, `apk-drops` only, Contents: RW |
| `OPENROUTER_API_KEY` | Only used by the OpenCode workflow, never passed to the build job |

Secrets are declared **explicitly** in the caller, never `secrets: inherit` — that
would hand `OPENROUTER_API_KEY` to the build job, where an agent-authored Gradle
script could read it. Don't give the agent's own credentials to code the agent wrote.

## Runbook

### Rollback

**You cannot roll back by downgrading.** Android refuses a lower `versionCode`, and
the updater only offers `remote > local`, so re-uploading old bytes does nothing.

Rollback is roll-forward:

```bash
git revert <bad-sha> && git push
```

CI builds the old code under a new, higher `versionCode` (~6 min). Or trigger it from
your phone: Actions → build → *Run workflow* → set `ref`.

If the pipeline *and* the app are both broken, the only escape is uninstall + manual
sideload of an older APK from an immutable release — which **loses app data**, since
all three apps set `allowBackup="false"` deliberately.

### Keystore rotation

Only if the key leaks. There is no graceful path: a new key means every device must
uninstall and reinstall, losing data. Generate a new keystore, replace the four
secrets in all three repos, update the fingerprint above, and tell yourself off for
leaking it.

### PAT expiry

`DROPS_REPO_TOKEN` is a fine-grained PAT with a **maximum 366-day expiry**, and it
fails *silently* — publishes just stop. Calendar the renewal. The durable fix is a
GitHub App with `actions/create-github-app-token@v1`.

### `versionCode` went backwards

`github.run_number` is scoped per workflow *file*, so renaming or recreating a
caller `build.yml` resets it to 1 — which would silently kill updates forever. The
publish step refuses to move a pointer backwards and fails loudly. Fix by bumping
`version-code-offset` in the caller past the highest previously published value.

**Rule: exactly one caller workflow file per app repo.**

### What CI does and does not prove

The gates turn *"it compiles"* into *"it launches"*. That is one notch up, not
"it works".

**Caught:** crash on launch, missing/renamed resources, resource merge conflicts,
`ClassNotFoundException` from a bad refactor, theme and manifest breakage, and (via
the tier-2 check against the shipped artifact) a class R8 stripped.

**Not caught:** anything behind a tap; the **entire accessibility-service behaviour**,
which is what AppBlocker and FacebookGuard actually are; overlay paths; foreground
service lifecycle; the boot receiver; MediaProjection; the cross-app bridge;
persistence; ANRs; and every wrong-but-not-crashing change. A screen that renders as
a blank white box passes.

**The real gate is the preview app sitting next to the stable one, and you looking
at it.** CI is a pre-filter that stops obviously-broken builds from wasting your
attention.
