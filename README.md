# ODD Games Jenkins Build System

Jenkins Shared Library for building, testing, and distributing Unity games to 5 storefronts (Steam, Google Play, Amazon, Apple App Store, Nintendo Switch).

## Setup

### 1. Add as Jenkins Global Pipeline Library

In **Manage Jenkins > System > Global Pipeline Libraries**:
- **Name:** `tool_jenkins_build_system`
- **Default version:** `main`
- **Retrieval method:** Modern SCM > Git
- **Repository URL:** `https://github.com/oddgames/tool_jenkins_build_system.git`

### 2. Configure Jenkins Job

Each jenkinsfile declares the required environment variables and credentials at the top. At minimum:

| Variable | Description | Example |
|----------|-------------|---------|
| `PLASTIC_REPSPEC` | PlasticSCM repository spec | `UnityProj_TGW@cloud` |
| `UNITY_PROJECT_NAME` | Unity project folder name | `UnityProj_TGW` |
| `APP_NAME` | App name for build artifacts | `trucksoffroad` |
| `APP_ICON` | App icon URL for notifications | `https://...` |

### 3. Run

All jenkinsfiles load the library automatically:
```groovy
@Library('tool_jenkins_build_system@main') _
```

---

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
  steam.jenkinsfile          # StandaloneWindows64 -> Steam (SteamCMD)
  googleplay.jenkinsfile     # Android -> Google Play (AAB via play_upload.rb)
  amazon.jenkinsfile         # Android -> Amazon Appstore (APK via catalog API)
  apple.jenkinsfile          # iOS -> App Store (TestFlight via Fastlane)
  switch.jenkinsfile         # Nintendo Switch -> Google Drive
  test_editor.jenkinsfile    # EditMode/PlayMode tests across platforms
```

---

## Jenkins Credentials

| Credential ID | Type | Used By |
|---------------|------|---------|
| `unity-credentials` | Username/Password | All platforms |
| `steam-credentials` | Username/Password | Steam |
| `plastic-token` | Secret Text | All platforms (SCM auth) |
| `rclone` | Secret File | Google Drive uploads |
| `gemini-api-key` | Secret Text | AI failure analysis |
| `slack-token` | Secret Text | Build notifications |
| `google-play-json` | Secret File | Google Play upload |
| `amazon-client-id` | Secret Text | Amazon Appstore |
| `amazon-client-secret` | Secret Text | Amazon Appstore |
| `apple-keychain-pass` | Secret Text | iOS builds |
| `apple-api-key` | Secret File | App Store Connect |
| `android-keystore-pass` | Secret Text | Android builds |
| `android-keyalias-pass` | Secret Text | Android builds |
