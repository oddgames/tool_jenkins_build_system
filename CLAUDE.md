# Jenkins Build System for Unity Games

Multi-platform Jenkins Shared Library for building, testing, and distributing Unity games to 5 storefronts. Loaded via `@Library('tool_jenkins_build_system@main') _`.

## Project Structure

```
vars/
  buildUtils.groovy          # API surface, delegates to platform modules

resources/
  groovy/
    common.groovy            # Platform-agnostic: Slack, badges, Gemini AI, versioning, SCM
    windows.groovy           # Windows agent: prereqs, Unity builds, uploads, Steam staging
    macos.groovy             # macOS agent: prereqs, Xcode, TestFlight, keychain
  Editor/Pipeline/           # Unity C# scripts copied into projects at build time
    Pipeline.cs              # Base: env vars, build options, version, artifact paths
    PipelineGooglePlay.cs    # Android AAB: keystore, ASTC, IL2CPP, debug symbols
    PipelineAmazon.cs        # Android APK: single monolithic APK for Amazon
    PipelineApple.cs         # iOS: Xcode post-build, plist, capabilities
    PipelineSteam.cs         # Windows: IL2CPP standalone build
    PipelineSwitch.cs        # Nintendo Switch build
    PipelineArtifactCopy.cs  # Copy build outputs to artifact directory
  scripts/
    play_upload.rb           # Google Play AAB upload (JWT auth, resumable, symbol upload)

jenkinsfiles/                # Declarative pipelines per platform
  steam.jenkinsfile          # StandaloneWindows64 → Steam (SteamCMD)
  googleplay.jenkinsfile     # Android → Google Play (AAB via play_upload.rb)
  amazon.jenkinsfile         # Android → Amazon Appstore (APK via catalog API)
  apple.jenkinsfile          # iOS → App Store (TestFlight via Fastlane)
  switch.jenkinsfile         # Nintendo Switch → Google Drive (manual portal upload)
  test_editor.jenkinsfile    # EditMode/PlayMode tests across platforms
```

## Architecture

`buildUtils.groovy` calls `init()` to detect agent OS via `isUnix()`, then loads `common.groovy` + platform module (`windows.groovy` or `macos.groovy`). Jenkinsfile stages call `buildUtils.*` which delegates to loaded modules.

Unity C# pipeline scripts are copied from `resources/Editor/Pipeline/` into the project's `Assets/Editor/Pipeline/` by `copyPipelineScripts()` during Startup, before Unity opens. These configure platform-specific build settings and are invoked via Unity's `-executeMethod` CLI.

### Adding New Features

1. Add functions to `resources/groovy/windows.groovy` (and `macos.groovy` if cross-platform)
2. Add delegation methods to `vars/buildUtils.groovy`
3. **Update the Jenkinsfiles** — library functions alone won't run without pipeline stages

### Library Loading

- `@Library('tool_jenkins_build_system@branch')` — branch override only affects `@script` workspace (Jenkinsfile checkout), NOT `@libs` workspace (library code)
- `@libs` always resolves from **Jenkins Global Pipeline Libraries** Plastic SCM selector: `smartbranch "main"`
- **All library code changes MUST be merged to `main`** to take effect in builds

## Source Control — Plastic SCM

This project uses **Plastic SCM**, not Git.

**NEVER add a `cm` command to code unless verified** via docs or `cm <command> --help`. GUI-only features do NOT necessarily have CLI equivalents.

### Verified `cm` Commands
- `cm status`, `cm history <file>`, `cm cat <file>`, `cm undo . -r`, `cm update --forced`, `cm find changeset`

### Known Non-Existent Commands
- `cm diff` — visual/UI tool only, won't work on CLI
- `cm workspace checkcontent` — **does not exist**; "Check content (hash)" is GUI-only

## Jenkins Constraints

- `isUnix()` requires node context — **DO NOT** use `System.getProperty('os.name')` (returns controller OS, not agent)
- `System.getenv('HOME')` / `System.getenv('USERPROFILE')` — **returns controller's env**, not agent's. Use `env.HOME` or shell commands
- Post actions in declarative pipelines run after node release if the build fails early
- Windows 260-char path limit — Steam uses `C:\Temp\Steam\` staging to work around this
- Script approvals are verified at build time by `preflightJenkinsPermissions()` in `common.groovy`
- **When adding sandbox-restricted API calls** (anything under `jenkins.model.*`, `hudson.*`, `org.jenkinsci.*`, `rawBuild.*`, `classLoader.*`, `.newInstance()`), **add a matching `testPermission()` block** to `preflightJenkinsPermissions()`. Each permission MUST have its own try/catch so all pending signatures are queued at once in Jenkins' Script Approval page.
- Node selection API (`pickNode()`) requires: `Jenkins.get`, `getLabel`, `getLabels`, `Node.toComputer`, `Node.getDisplayName`, `LabelAtom.getNodes`, `Computer.isOnline`, `Computer.numExecutors`, `Computer.getExecutors`, `Executor.isBusy`, `Executor.getCurrentExecutable`, `Run.getParent`, `Job.getFullName`

### Sandbox Restrictions

`Integer.toHexString()` and `Integer.toString(n, 16)` are **BLOCKED** — use `Math.abs()` with decimal instead. Check Jenkins script approval page if methods are blocked.

### Winget Package IDs

Use **specific version IDs**, not bare prefix names:
- `Python.Python.3.13` (not `Python.Python.3` — doesn't resolve on all machines)
- `RubyInstallerTeam.RubyWithDevKit.3.2`
- When auto-installing, try multiple version IDs in descending order with `returnStatus: true` fallback

### Windows Batch Gotchas

- `forfiles` returns **exit code 1** when no files match — suppress with `2>nul || echo ...` to prevent false failures
- `for %%f in (*.apk *.aab)` can match multiple files — use `goto :eof` after first match to get single filename

### Gemini AI Analysis

Gemini error analysis uses **Ruby** (not Python). Any jenkinsfile with `gemini-api-key` needs `preflightRuby()` in Preflight Checks, or AI analysis silently fails on build errors.

### Unity Hub CLI

- Syntax: `"Unity Hub.exe" -- --headless <command>`
- Takes ~20 seconds to respond — must wait, any input cancels the operation
- Cannot test from Git Bash on Windows — bash mangles `--` arg separator. Use `cmd.exe` or Jenkins `bat` steps
- Build agents: `C:\UnityEditors\` — Dev PC: `C:\Program Files\Unity\Hub\Editor\`

## Key Fixes Reference

- **Switch NSP not building**: `BuildOptions.CompressWithLz4HC` conflicts with Switch ROM creation — removed from `PipelineSwitch.cs`. Switch uses its own compression via `switchEnableRomCompression`/`switchRomCompressionType`
- **Steam Linux building Windows exe**: Missing `linux-il2cpp` module causes Unity to silently fall back to Windows — added `validateLinuxBuildSupport()` preflight to `steam-linux.jenkinsfile`

## Firebase Crashlytics Symbol Upload

1. Set `UPLOAD_CRASHLYTICS_SYMBOLS=true` in Jenkins job config
2. Add Firebase Crashlytics Admin role to `google-play-json` service account
3. Ensure `google-services.json` exists in Unity project (`Assets/StreamingAssets/` or `Assets/Plugins/Android/`)
4. Runs in parallel with other post-build tasks, skips silently if not configured
