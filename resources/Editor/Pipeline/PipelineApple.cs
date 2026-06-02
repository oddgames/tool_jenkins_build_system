using System.IO;
using UnityEditor;
using UnityEditor.Build;
using UnityEditor.Build.Reporting;
using UnityEngine;
using UnityEditor.iOS.Xcode;
using UnityEditor.iOS.Xcode.Extensions;

namespace ODDFramework
{
    public static class PipelineApple
    {
        private static void ApplyReleaseSettings()
        {
            PlayerSettings.stripEngineCode = true;

            PlayerSettings.SetStackTraceLogType(LogType.Log, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Warning, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Error, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Assert, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Exception, StackTraceLogType.ScriptOnly);

            EditorUserBuildSettings.allowDebugging = false;
            EditorUserBuildSettings.connectProfiler = false;
            PlayerSettings.enableInternalProfiler = false;

            PlayerSettings.SetManagedStrippingLevel(NamedBuildTarget.iOS, ManagedStrippingLevel.Medium);
            PlayerSettings.SetIl2CppCompilerConfiguration(NamedBuildTarget.iOS, Il2CppCompilerConfiguration.Release);
            PlayerSettings.SetIl2CppCodeGeneration(NamedBuildTarget.iOS, Il2CppCodeGeneration.OptimizeSpeed);
            PlayerSettings.SetIl2CppStacktraceInformation(NamedBuildTarget.iOS, Il2CppStacktraceInformation.MethodFileLineNumber);

            PlayerSettings.iOS.scriptCallOptimization = ScriptCallOptimizationLevel.SlowAndSafe;
        }

        public static void PrepareDebug()
        {
            string versionString = Pipeline.GetVariable("VERSION");
            var version = new ODDFramework.Version(versionString);

            PlayerSettings.iOS.buildNumber = version.Build.ToString();
            Pipeline.Log($"✓ CFBundleVersion: {PlayerSettings.iOS.buildNumber}");

            Pipeline.Prepare("DEVELOPMENT_BUILD,ODDGAMES_FORCE_ENABLE_ODD_LOGS");
        }

        public static void Debug()
        {
            ApplyDebugSettings();

            string xcodeBasePath = System.Environment.GetEnvironmentVariable("XCODE_BASE_PATH");
            if (string.IsNullOrEmpty(xcodeBasePath))
                throw new System.Exception("XCODE_BASE_PATH environment variable is not set");

            Pipeline.Build(xcodeBasePath, BuildOptions.CompressWithLz4HC | BuildOptions.Development);
        }

        private static void ApplyDebugSettings()
        {
            PlayerSettings.stripEngineCode = false;

            PlayerSettings.SetStackTraceLogType(LogType.Log, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Warning, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Error, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Assert, StackTraceLogType.Full);
            PlayerSettings.SetStackTraceLogType(LogType.Exception, StackTraceLogType.Full);

            EditorUserBuildSettings.allowDebugging = false;
            EditorUserBuildSettings.connectProfiler = false;
            PlayerSettings.enableInternalProfiler = false;

            PlayerSettings.SetManagedStrippingLevel(NamedBuildTarget.iOS, ManagedStrippingLevel.Medium);
            PlayerSettings.SetIl2CppCompilerConfiguration(NamedBuildTarget.iOS, Il2CppCompilerConfiguration.Debug);
            PlayerSettings.SetIl2CppCodeGeneration(NamedBuildTarget.iOS, Il2CppCodeGeneration.OptimizeSize);
            PlayerSettings.SetIl2CppStacktraceInformation(NamedBuildTarget.iOS, Il2CppStacktraceInformation.MethodFileLineNumber);

            PlayerSettings.iOS.scriptCallOptimization = ScriptCallOptimizationLevel.SlowAndSafe;
        }

        public static void PrepareRelease()
        {
            string versionString = Pipeline.GetVariable("VERSION");
            var version = new ODDFramework.Version(versionString);

            PlayerSettings.iOS.buildNumber = version.Build.ToString();

            Pipeline.Log($"✓ CFBundleVersion: {PlayerSettings.iOS.buildNumber}");

            Pipeline.Prepare("ODDGAMES_FORCE_ENABLE_ODD_LOGS");
        }

        public static void Release()
        {
            ApplyReleaseSettings();

            string xcodeBasePath = System.Environment.GetEnvironmentVariable("XCODE_BASE_PATH");
            if (string.IsNullOrEmpty(xcodeBasePath))
                throw new System.Exception("XCODE_BASE_PATH environment variable is not set");

            Pipeline.Build(xcodeBasePath, BuildOptions.CompressWithLz4HC);
        }

        public static void PrepareQA()
        {
            string versionString = Pipeline.GetVariable("VERSION");
            var version = new ODDFramework.Version(versionString);

            PlayerSettings.iOS.buildNumber = version.Build.ToString();
            Pipeline.Log($"✓ CFBundleVersion: {PlayerSettings.iOS.buildNumber}");

            Pipeline.Prepare("ODDGAMES_FORCE_ENABLE_ODD_LOGS");
        }

        public static void QA()
        {
            ApplyQASettings();

            string xcodeBasePath = System.Environment.GetEnvironmentVariable("XCODE_BASE_PATH");
            if (string.IsNullOrEmpty(xcodeBasePath))
                throw new System.Exception("XCODE_BASE_PATH environment variable is not set");

            // LZ4 (not LZ4HC) — much faster to compress than the high-compression variant
            Pipeline.Build(xcodeBasePath, BuildOptions.CompressWithLz4);
        }

        /// <summary>
        /// QA build settings — fastest possible iOS build for internal testing.
        /// NOTE: iOS has NO Mono option — Apple only allows AOT/IL2CPP, so unlike Android
        /// the QA build still uses IL2CPP. Speed comes from the Debug IL2CPP compiler config
        /// (far faster C++ compile than Release/Master), low managed stripping, no engine
        /// stripping, and LZ4 data compression. The Xcode archive also uses the Debug
        /// configuration (see archiveXcodeProject in macos.groovy). QA is never store-uploaded.
        /// </summary>
        private static void ApplyQASettings()
        {
            PlayerSettings.stripEngineCode = false;

            PlayerSettings.SetStackTraceLogType(LogType.Log, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Warning, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Error, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Assert, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Exception, StackTraceLogType.ScriptOnly);

            EditorUserBuildSettings.allowDebugging = false;
            EditorUserBuildSettings.connectProfiler = false;
            PlayerSettings.enableInternalProfiler = false;

            // Low (not Medium) stripping — less link analysis, faster build. IL2CPP can't go Disabled.
            PlayerSettings.SetManagedStrippingLevel(NamedBuildTarget.iOS, ManagedStrippingLevel.Low);
            // Debug compiler config — dramatically faster IL2CPP C++ compile than Release
            PlayerSettings.SetIl2CppCompilerConfiguration(NamedBuildTarget.iOS, Il2CppCompilerConfiguration.Debug);
            PlayerSettings.SetIl2CppCodeGeneration(NamedBuildTarget.iOS, Il2CppCodeGeneration.OptimizeSize);
            PlayerSettings.SetIl2CppStacktraceInformation(NamedBuildTarget.iOS, Il2CppStacktraceInformation.MethodFileLineNumber);

            PlayerSettings.iOS.scriptCallOptimization = ScriptCallOptimizationLevel.SlowAndSafe;
        }

        [UnityEditor.Callbacks.PostProcessBuild(int.MaxValue)]
        public static void OnPostprocessBuild(BuildTarget target, string pathToBuiltProject)
        {
            UnityEngine.Debug.Log($"[PipelineApple.PostProcessBuild] Called - Target: {target}, Path: {pathToBuiltProject}");

            if (target != BuildTarget.iOS)
            {
                UnityEngine.Debug.LogWarning($"[PipelineApple.PostProcessBuild] Skipping - Target={target} (expected iOS)");
                return;
            }

            string buildPath = pathToBuiltProject;
            string plistPath = Path.Combine(buildPath, "Info.plist");
            string projectPath = Path.Combine(buildPath, "Unity-iPhone.xcodeproj/project.pbxproj");

            // Get configurable values from environment
            string entitlementsFilename = Pipeline.GetVariable("ENTITLEMENTS_FILENAME", "App.entitlements");
            string bundleIdentifier = Pipeline.GetVariable("BUNDLE_IDENTIFIER", PlayerSettings.applicationIdentifier);
            string entitlementsPath = Path.Combine(buildPath, $"Unity-iPhone/{entitlementsFilename}");

            UpdateXcodeProject(projectPath);
            UpdatePlist(plistPath);
            AddCapabilities(buildPath, projectPath, entitlementsPath, entitlementsFilename, bundleIdentifier);
        }

        private static void UpdateXcodeProject(string projectPath)
        {
            PBXProject project = new PBXProject();
            project.ReadFromFile(projectPath);

            project.SetBuildProperty(project.GetUnityFrameworkTargetGuid(), "ENABLE_BITCODE", "NO");
            project.SetBuildProperty(project.GetUnityMainTargetGuid(), "ENABLE_BITCODE", "NO");
            project.SetBuildProperty(project.ProjectGuid(), "ENABLE_BITCODE", "NO");

            project.SetBuildProperty(project.ProjectGuid(), "DEBUG_INFORMATION_FORMAT", "dwarf-with-dsym");
            project.SetBuildProperty(project.GetUnityMainTargetGuid(), "DEBUG_INFORMATION_FORMAT", "dwarf-with-dsym");
            project.SetBuildProperty(project.GetUnityFrameworkTargetGuid(), "DEBUG_INFORMATION_FORMAT", "dwarf-with-dsym");

            project.SetBuildProperty(project.GetUnityFrameworkTargetGuid(), "CODE_SIGN_IDENTITY", "iPhone Developer");
            project.SetBuildProperty(project.GetUnityFrameworkTargetGuid(), "CODE_SIGN_IDENTITY[sdk=iphoneos*]", "iPhone Developer");

            project.SetBuildProperty(project.ProjectGuid(), "MTL_TREAT_WARNINGS_AS_ERRORS", "YES");
            project.SetBuildProperty(project.GetUnityMainTargetGuid(), "MTL_TREAT_WARNINGS_AS_ERRORS", "YES");
            project.SetBuildProperty(project.GetUnityFrameworkTargetGuid(), "MTL_TREAT_WARNINGS_AS_ERRORS", "YES");

            project.WriteToFile(projectPath);

            Pipeline.Log($"✓ Configured Xcode build settings (bitcode, debug symbols, code signing, strict Metal)");
        }

        private static void UpdatePlist(string plistPath)
        {
            var plist = new PlistDocument();
            plist.ReadFromFile(plistPath);

            string bundleVersion = plist.root["CFBundleShortVersionString"].AsString();
            var version = new Version(bundleVersion);

            plist.root.SetString("CFBundleShortVersionString", version.MajorMinorRevision);
            plist.root.SetBoolean("ITSAppUsesNonExemptEncryption", false);
            plist.root.SetString("NSLocalNetworkUsageDescription", "Used to optimize ad delivery and analytics.");

#if DEVELOPMENT_BUILD
            plist.root.SetBoolean("UIFileSharingEnabled", true);
#endif

            plist.WriteToFile(plistPath);

            Pipeline.Log($"✓ CFBundleShortVersionString: {version.MajorMinorRevision}");
            Pipeline.Log($"✓ ITSAppUsesNonExemptEncryption: false");
        }

        private static void AddCapabilities(string buildPath, string projectPath, string entitlementsPath, string entitlementsFilename, string bundleIdentifier)
        {
#if DEVELOPMENT_BUILD
            bool debug = true;
#else
            bool debug = false;
#endif

            var project = new PBXProject();
            project.ReadFromFile(projectPath);
            project.SetBuildProperty(project.GetUnityMainTargetGuid(), "CODE_SIGN_ENTITLEMENTS", $"Unity-iPhone/{entitlementsFilename}");
            project.SetBuildProperty(project.GetUnityMainTargetGuid(), "PRODUCT_BUNDLE_IDENTIFIER", bundleIdentifier);
            project.WriteToFile(projectPath);

            var capabilityManager = new ProjectCapabilityManager(
                projectPath,
                Path.GetFullPath(entitlementsPath),
                "Unity-iPhone"
            );

            // Make sure capability manager is the last thing to write to the project
            capabilityManager.AddGameCenter();
            capabilityManager.AddPushNotifications(debug);
            capabilityManager.AddiCloud(true, true, true, true, new string[0] {  });
            capabilityManager.WriteToFile();

            Pipeline.Log($"✓ Added iCloud, GameCenter, and Push Notification capabilities");
        }
    }
}
