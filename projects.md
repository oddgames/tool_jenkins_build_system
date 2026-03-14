# Project Configuration

## MTD (Monster Truck Destruction)

```
PLASTIC_REPSPEC=game_monster_truck_destruction@oddgames_external@cloud
UNITY_PROJECT_NAME=UnityProj_MTD
APP_NAME=MTD

# Android
BUNDLE_IDENTIFIER_ANDROID=au.com.oddgames.monstertruckdestruction
APP_ICON_ANDROID=https://play-lh.googleusercontent.com/i20fVWXc1tX4Kqe4mQNayeZJsqmD7F32MyNhmTZBPBA_wEiowild7OAheiXwnuiCyB8=s96-rw

# iOS
BUNDLE_IDENTIFIER_IOS=com.chillingo.monstertruckdestruction
APP_ICON_IOS=https://is1-ssl.mzstatic.com/image/thumb/Purple221/v4/52/33/f3/5233f330-b5f8-6dc1-933f-d3f64b39bc98/AppIcon-0-0-1x_U007emarketing-0-8-0-85-220.png/400x400ia-75.webp
APPLE_TEAM_ID=52TRASNQ85
ANDROID_KEYSTORE_NAME=Assets/Keystore/MTD.keystore
ANDROID_KEYALIAS_NAME=au.com.oddgames.monstertruckdestruction
CREDENTIAL_KEYSTORE_PASS=mtd-keystore-pass
CREDENTIAL_KEYALIAS_PASS=mtd-keyalias-pass
VERSION_CODE_BASE=30106896
RCLONE_REMOTE=drive:Builds
STEAM_APP_ID=324760
STEAM_DEPOT_ID=324761
```

## TOR (Trucks Off Road)

```
PLASTIC_REPSPEC=game_trucks_off_road@oddgames_external@cloud
UNITY_PROJECT_NAME=UnityProj_TGW
BUNDLE_IDENTIFIER=au.com.oddgames.trucksoffroad
APP_ICON=https://play-lh.googleusercontent.com/Ca-vfAUx3vJ7H3_tu7A01mvEe8yhqmNoFVbFMR-Te94eJRVW-NyG74T7aXlol1__Ins=s96-rw
APP_NAME=TOR
ANDROID_KEYSTORE_NAME=Assets/../../Resources/tor.keystore
ANDROID_KEYALIAS_NAME=au.com.oddgames.trucksoffroad
CREDENTIAL_KEYSTORE_PASS=tor-keystore-pass
CREDENTIAL_KEYALIAS_PASS=tor-keyalias-pass
VERSION_CODE_BASE=190928076
RCLONE_REMOTE=drive:Builds
STEAM_APP_ID=2099810
STEAM_DEPOT_ID=2099811
```

---

## Auto-Downloaded Tools

Build tools are automatically downloaded on first use and stored in the user's home directory (`~/.buildtools` or `%USERPROFILE%\.buildtools`). This location persists across workspace cleanups.

| Tool | Windows Path | macOS Path | Version |
|------|-------------|------------|---------|
| rclone | `~\.buildtools\rclone\rclone.exe` | `~/.buildtools/rclone/rclone` | latest |
| SteamCMD | `~\.buildtools\steamcmd\steamcmd.exe` | `~/.buildtools/steamcmd/steamcmd.sh` | auto-updates |
| UnityDataTool | `~\.buildtools\unity_data_tool\UnityDataTool.exe` | `~/.buildtools/unity_data_tool/UnityDataTool` | latest |

### Tool Installation

Tools are installed automatically when needed, or can be explicitly installed:

```groovy
// Check if tool is available (auto-install if missing)
buildUtils.checkRclone(true)
buildUtils.checkSteamCMD(true)
buildUtils.checkUnityDataTool(true)

// Explicit installation
buildUtils.installRclone()
buildUtils.installSteamCMD()
buildUtils.installUnityDataTool()

// Get the tools directory path
def toolsDir = buildUtils.getToolsDir()
```

**Note:** rclone and UnityDataTool fetch the latest release from GitHub API. SteamCMD self-updates on first run.

---

## Pipeline Scripts

The `resources/Editor/Pipeline/` directory contains Unity C# scripts that are copied to the Unity project before each build. These scripts define the build methods called by Jenkins.

| Script | Purpose |
|--------|---------|
| `Pipeline.cs` | Base pipeline utilities |
| `PipelineGooglePlay.cs` | Android/Google Play builds |
| `PipelineApple.cs` | iOS/App Store builds |
| `PipelineSteam.cs` | Steam Windows builds |
| `PipelineAmazon.cs` | Amazon Appstore builds |

Scripts are copied by `buildUtils.copyPipelineScripts()` to `{UNITY_PROJECT}/Assets/Editor/Pipeline/`.

---

## Prerequisite Detection

The build system can detect and optionally install missing prerequisites. Use these functions in your Jenkinsfile:

### Check All Prerequisites

```groovy
// Check prerequisites for the target platform (report only)
buildUtils.checkPrerequisites('Android')
buildUtils.checkPrerequisites('iOS')
buildUtils.checkPrerequisites('StandaloneWindows64')

// Check and auto-install missing prerequisites
buildUtils.checkPrerequisites('Android', true)
```

### Individual Checks

```groovy
// Windows only: winget package manager (required for other Windows auto-installs)
buildUtils.checkWinget()                    // check only
buildUtils.checkWinget(true)                // auto-install from GitHub

// Git
buildUtils.checkGit()                       // check only
buildUtils.checkGit(true)                   // auto-install if missing

// Unity Hub
buildUtils.checkUnityHub()                  // check only
buildUtils.checkUnityHub(true)              // auto-install if missing

// Unity version (reads UNITY_VERSION from project)
buildUtils.checkUnity('2022.3.20f1')
buildUtils.checkUnity('2022.3.20f1', true)  // auto-install

// Unity modules
buildUtils.checkUnityModules('2022.3.20f1', ['android', 'android-sdk-ndk-tools'])
buildUtils.installUnityModules('2022.3.20f1', ['ios'])

// Accept Unity licenses after installation
buildUtils.acceptUnityLicenses()

// Ruby and Fastlane
buildUtils.checkRuby()                      // check only
buildUtils.checkRuby(true)                  // auto-install if missing
buildUtils.checkFastlane(true)              // auto-install if Ruby available

// PlasticSCM
buildUtils.checkPlasticSCM()                // check only
buildUtils.checkPlasticSCM(true)            // auto-install if missing
```

### Platform-Specific Prerequisites

| Prerequisite | Windows Auto-Install | macOS Auto-Install |
|--------------|---------------------|-------------------|
| winget | ✓ GitHub releases | N/A |
| Git | ✓ winget | ✓ brew/xcode-select |
| Unity Hub | ✓ winget | ✓ brew cask |
| Unity (version) | ✓ Unity Hub CLI | ✓ Unity Hub CLI |
| Unity Modules | ✓ Unity Hub CLI | ✓ Unity Hub CLI |
| PlasticSCM | ✓ winget | ✓ brew cask |
| Ruby | ✓ winget | ✓ system/brew |
| Fastlane | ✓ gem install | ✓ brew/gem |
| Android SDK | (bundled with Unity) | (bundled with Unity) |
| Java/OpenJDK | (bundled with Unity) | (bundled with Unity) |
| Xcode | N/A | (Mac App Store) |
| Xcode CLI Tools | N/A | ✓ xcode-select |
| CocoaPods | N/A | ✓ brew/gem |
| Homebrew | N/A | ✓ install script |

**Notes:**
- Independent checks run in parallel for faster prerequisite scanning.
- Windows: [winget](https://learn.microsoft.com/en-us/windows/package-manager/winget/) is auto-installed from GitHub if missing.
- macOS: [Homebrew](https://brew.sh/) is auto-installed if missing (requires user interaction).
