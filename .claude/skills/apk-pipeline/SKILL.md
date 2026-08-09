---
name: apk-pipeline
description: Conventions and invariants for the personal Android CI/CD pipeline (apk-ci, apk-drops, AppBlocker, FacebookGuard, BugCatcher). Use when touching any GitHub Actions workflow, build.gradle.kts signing/version/build-type wiring, the in-app updater, the drops release feed, or the OpenCode agent workflows for these apps.
---

# APK pipeline conventions

Prompt from a phone → OpenCode edits → CI builds and signs → the app on the
phone offers a one-tap update. Four repos:

| Repo | Visibility | Role |
|---|---|---|
| `apk-ci` | public | Reusable workflow, canonical updater sources, templates |
| `apk-drops` | public | Signed APKs + JSON feed, published as releases |
| `AppBlocker`, `FacebookGuard`, `BugCatcher` | private | The apps |

`apk-ci` is public deliberately: it holds no secrets, and public means the
reusable workflow resolves without an access setting and the drift check can
clone it without a token.

## Invariants — breaking any of these breaks updates on a real device

1. **One keystore signs debug, preview and release, in all three apps.**
   Fingerprint `29:D0:2D:19:90:5B:66:F8:...:D4:79:E9:E5`. A different signature
   means `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, unfixable without an uninstall
   that loses app data.

2. **`versionCode` comes from CI only** — `version-code-offset + run_number`,
   read via `System.getenv("APP_VERSION_CODE")`. Never hand-edit it.
   `run_number` is scoped per workflow *file*, so **exactly one caller workflow
   per app repo**; renaming that file resets the counter and silently kills
   updates forever. The publish step's monotonic guard is what catches it.

3. **Preview and stable are different `applicationId`s** (`.preview` suffix) and
   install side by side. This is the safety property that lets an unreviewed
   agent branch ship straight to a phone. An agent branch can never publish to
   stable — only `main` does.

4. **`OPENROUTER_API_KEY` must never reach the build job.** Secrets are declared
   explicitly in the caller, never `secrets: inherit`. The build job runs
   agent-authored Gradle and tests; do not hand it the agent's own credential.

5. **You cannot roll back by downgrading.** Android refuses a lower
   `versionCode`. Rollback is `git revert` + rebuild.

## Gotchas that have already bitten, or would have

- **`gradlew` is committed mode `100644`** in these repos. Every CI job needs
  `chmod +x ./gradlew`. Fix permanently with `git update-index --chmod=+x gradlew`.
- **`releases/latest/download/` cannot serve six streams.** "Latest" is one
  pointer per repo. Each `<app>-<channel>` stream gets its own fixed pointer tag,
  clobbered per build, plus an immutable `-v<code>` release for history.
- **`resValue("string", "app_name", …)` collides** with the `app_name` already in
  `src/main/res/values/strings.xml` and fails the resource merge. Override via
  the build-type source set `app/src/preview/res/values/strings.xml` instead —
  which also fixes the accessibility-service label, where telling stable from
  preview matters most.
- **`testBuildType` defaults to `debug`**, so `connectedPreviewAndroidTest` does
  not exist until `testBuildType` is made configurable via a Gradle property.
- **Lint with no `lint-baseline.xml` creates one and fails that run.** Generate
  and commit the baseline before CI ever runs on a repo.
- **`sendBroadcast` + `setPackage` to a package not in `<queries>` is a silent
  no-op.** No exception, no log. This is how the BugCatcher bridge breaks.
- **`am start -n` with an `applicationIdSuffix`** needs
  `com.example.app.preview/com.example.app.MainActivity` — package suffixed,
  class not. Use `monkey -p` instead and sidestep it.
- **Comments posted with `GITHUB_TOKEN` do not trigger workflow runs.** Any
  design that relies on a bot comment retriggering CI silently does nothing.
- **`[ test ] && cmd` as the last line of a `run:` block** fails the step under
  `set -e` when the test is false. Use `if`.
- **Never interpolate `${{ github.event.* }}` into a `run:` body.** Pass through
  `env:` — a crafted comment or commit message otherwise executes on the runner.
  Applies to comment bodies, commit messages, PR titles and branch names.

## Where things live

```
apk-ci/
  .github/workflows/android-build-ship.yml   reusable workflow_call pipeline
  shared/android/{AppUpdater,UpdateInstallReceiver,UpdateManifest}.kt
  templates/{AGENTS.md,build.yml,opencode.yml,autofix.yml}
  canonical/<app>-gradle-region.sha256       hash of the CI-OWNED Gradle region
```

The updater is **vendored** into each app, not published as an AAR or a
submodule — the agent has to be able to read and reason about it in the repo it
is editing. CI diffs each vendored copy against `apk-ci` ignoring line 1 (the
`package` declaration). After changing `shared/android/*.kt`, re-vendor all
three apps in the same pass or CI will fail on drift.

## Agent-proofing

Three CI guards, in `android-build-ship.yml`:

1. `.github/` unchanged vs `origin/main` — enforced on `opencode/*` branches only,
   so humans can still edit workflows.
2. SHA-256 of the `// --- CI-OWNED-BEGIN ---` … `END` region of
   `app/build.gradle.kts` matches `apk-ci/canonical/`. Skips with a warning when
   no canonical hash is committed yet, so a repo can be bootstrapped first.
3. Vendored updater matches canonical.

After changing a CI-owned Gradle region deliberately, **regenerate the canonical
hash**:

```bash
awk '/CI-OWNED-BEGIN/,/CI-OWNED-END/' app/build.gradle.kts | sha256sum | cut -d' ' -f1
```

Autofix is capped at 3 attempts, counted by `<!-- autofix:attempt=N -->` marker
comments, gated on the `AUTOFIX_ENABLED` repo variable, and restricted to
`opencode/*` branches on `run_attempt == 1`.

## What CI proves

It turns *"it compiles"* into *"it launches"*. It does **not** test the
accessibility service — which is what AppBlocker and FacebookGuard essentially
are — nor overlays, foreground services, the boot receiver, MediaProjection, the
cross-app bridge, persistence, or anything behind a tap. A blank white screen
passes.

Do not describe a green build as "verified working". The real gate is the
preview app installed next to the stable one, and the user looking at it.

## Local verification (Windows)

A full Android toolchain is present, so verify before pushing:

```
keytool  C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe
SDK      C:\Users\count\AppData\Local\Android\Sdk   (platforms 34, 36.1)
keystore C:\Users\count\.apk-ci-signing\apk-ci-release.jks
```

```bash
./gradlew :app:assemblePreview
aapt2 dump packagename app/build/outputs/apk/preview/app-preview.apk   # expect .preview
apksigner verify --print-certs app/build/outputs/apk/preview/app-preview.apk
```
