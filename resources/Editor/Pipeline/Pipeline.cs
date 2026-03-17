using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEngine;
#if UNITY_ANDROID
using UnityEditor.Android;
#endif

namespace ODDFramework
{
    public static partial class Pipeline
    {
        public static string Branch { get; private set; }
        public static string BranchSanitized { get; private set; }
        public static int BuildNumber { get; private set; }
        public static int Changeset { get; private set; }
        public static bool IsMain { get; private set; }
        public static bool IsBatchMode { get; private set; }
        public static BuildTargetGroup CurrentBuildTargetGroup => EditorUserBuildSettings.selectedBuildTargetGroup;

        private const string ENV_KEY_BRANCH = "BRANCH";
        private const string ENV_KEY_BUILDNUMBER = "BUILD_NUMBER";
        private const string ENV_KEY_CHANGESET = "CHANGESET_ID";
        private const string ENV_KEY_BUILDPATH = "BUILD_PATH";
        private const string ENV_KEY_ARTIFACTPATH = "ARTIFACT_PATH";

        private static BuildReport report;
        private static readonly List<string> capturedErrors = new();

        [InitializeOnLoadMethod]
        private static void Initialize()
        {
            IsBatchMode = Application.isBatchMode;

            Branch = GetVariable(ENV_KEY_BRANCH, string.Empty);
            BranchSanitized = string.IsNullOrEmpty(Branch) ? string.Empty : Branch.Replace('/', '_').Replace('\\', '_');

            if (!int.TryParse(GetVariable(ENV_KEY_BUILDNUMBER, "-1"), out int buildNum))
                buildNum = -1;
            BuildNumber = buildNum;

            if (!int.TryParse(GetVariable(ENV_KEY_CHANGESET, "-1"), out int changeset))
                changeset = -1;
            Changeset = changeset;

            IsMain = !string.IsNullOrEmpty(Branch) && (Branch == "/main" || Branch.EndsWith("/main") || Branch.Contains("/main/"));
        }

        public static string GetVariable(string key, string @default = null)
        {
            string value = System.Environment.GetEnvironmentVariable(key);
            if (string.IsNullOrEmpty(value))
            {
                if (@default == null)
                    throw new Exception($"Required environment variable '{key}' is not set");
                return @default;
            }
            return value;
        }

        public static string BuildPath => GetVariable(ENV_KEY_BUILDPATH);
        public static string ArtifactPath => GetVariable(ENV_KEY_ARTIFACTPATH, string.Empty);
        public static string OutputPrefix => GetVariable("APP_NAME", "BUILD");

public static BuildOptions GetBuildOptions()
        {
            BuildOptions options = BuildOptions.CompressWithLz4HC;

            if (EditorUserBuildSettings.development)
                options |= BuildOptions.Development;

            return options;
        }

        public static void SetupBuildVersion()
        {
            string versionString = GetVariable("VERSION");
            if (string.IsNullOrEmpty(versionString))
                return;

            var version = new ODDFramework.Version(versionString);
            // Set bundle version without feature suffix to comply with iOS requirements (max 18 chars, numbers and dots only)
            PlayerSettings.bundleVersion = version.MajorMinorRevision;

            // Disable Unity splash screen logo
            PlayerSettings.SplashScreen.showUnityLogo = false;

            Log($"✓ bundleVersion: {PlayerSettings.bundleVersion}");
            Log($"✓ Unity splash logo disabled");
        }

        public static void Prepare(string additionalDefines = null, Action platformSetup = null)
        {
            Log("Starting build preparation");

            UseEmbeddedAndroidTools();

            Type.GetType("ODDFramework.PlatformSpecificBundlesConfig")?.GetMethod("Setup")?.Invoke(null, null);
            Type.GetType("ODDFramework.PipelineSetup")?.GetMethod("OnPrepare")?.Invoke(null, null);

            platformSetup?.Invoke();
            SetupBuildVersion();

            if (!string.IsNullOrEmpty(additionalDefines))
            {
                ApplyScriptingDefines(additionalDefines);
            }

            Log("Build preparation complete");
        }

        /// <summary>
        /// Force Unity to use its bundled JDK, SDK, NDK, and Gradle instead of external installations.
        /// Without this, batchmode builds on fresh agents fail with "JDK not found" because Unity's
        /// editor preferences haven't been configured via the GUI.
        /// </summary>
        private static void UseEmbeddedAndroidTools()
        {
#if UNITY_ANDROID
            // Point Unity at its bundled JDK/SDK/NDK ("Installed with Unity").
            // Only override if the bundled tools exist — otherwise leave the editor
            // preferences untouched so any manually-configured paths are preserved.
            // See: https://docs.unity3d.com/6000.0/Documentation/ScriptReference/Android.AndroidExternalToolsSettings-jdkRootPath.html
            string unityEditorPath = Path.GetDirectoryName(UnityEditor.EditorApplication.applicationPath);
            string playbackEngines = Path.Combine(unityEditorPath, "Data", "PlaybackEngines", "AndroidPlayer");

            string bundledJdkPath = Path.Combine(playbackEngines, "OpenJDK");
            if (Directory.Exists(bundledJdkPath))
            {
                AndroidExternalToolsSettings.jdkRootPath = null;
                Log($"✓ JDK set to embedded ({bundledJdkPath})");
            }
            else
            {
                Log($"⚠ Bundled OpenJDK not found at {bundledJdkPath} — keeping existing JDK preference: '{AndroidExternalToolsSettings.jdkRootPath}'");
            }

            string bundledSdkPath = Path.Combine(playbackEngines, "SDK");
            if (Directory.Exists(bundledSdkPath))
            {
                AndroidExternalToolsSettings.sdkRootPath = null;
                Log($"✓ SDK set to embedded ({bundledSdkPath})");
            }
            else
            {
                Log($"⚠ Bundled SDK not found — keeping existing SDK preference: '{AndroidExternalToolsSettings.sdkRootPath}'");
            }

            string bundledNdkPath = Path.Combine(playbackEngines, "NDK");
            if (Directory.Exists(bundledNdkPath))
            {
                AndroidExternalToolsSettings.ndkRootPath = null;
                Log($"✓ NDK set to embedded ({bundledNdkPath})");
            }
            else
            {
                Log($"⚠ Bundled NDK not found — keeping existing NDK preference: '{AndroidExternalToolsSettings.ndkRootPath}'");
            }
#endif
        }

        private static void ApplyScriptingDefines(string scriptingDefines)
        {
            Log($"Applying scripting defines: '{scriptingDefines}'");

            var targetGroup = EditorUserBuildSettings.selectedBuildTargetGroup;
            var defines = scriptingDefines.Split(new[] { ',' }, StringSplitOptions.RemoveEmptyEntries);

            foreach (var define in defines)
            {
                var trimmedDefine = define.Trim();
                if (!string.IsNullOrEmpty(trimmedDefine))
                {
                    ScriptingDefines.Append(targetGroup, trimmedDefine);
                }
            }

            Log($"Applied defines: {string.Join(", ", ScriptingDefines.GetList(targetGroup))}");
        }

        private static void UpdateVersionData()
        {
            const string VERSION_DATA_PATH = "Assets/Resources/VersionData.asset";

            // Ensure Resources folder exists
            string resourcesPath = "Assets/Resources";
            if (!Directory.Exists(resourcesPath))
            {
                Directory.CreateDirectory(resourcesPath);
            }

            // Load or create VersionData asset
            VersionScriptableObject versionData = AssetDatabase.LoadAssetAtPath<VersionScriptableObject>(VERSION_DATA_PATH);

            if (versionData == null)
            {
                versionData = ScriptableObject.CreateInstance<VersionScriptableObject>();
                AssetDatabase.CreateAsset(versionData, VERSION_DATA_PATH);
            }

            // Populate from Pipeline values
            versionData.bundleVersionString = PlayerSettings.bundleVersion;
            versionData.bundleIdentifier = PlayerSettings.applicationIdentifier;
            versionData.buildNumber = BuildNumber;
            versionData.revisionNumber = Changeset;
            versionData.changesetId = Changeset.ToString();
            versionData.branchName = Branch;
            versionData.buildDate = System.DateTime.UtcNow.ToString("yyyy-MM-dd HH:mm:ss UTC");

            // Extract feature name from version string
            string version = PlayerSettings.bundleVersion;
            int dashIndex = version.IndexOf('-');
            versionData.featureName = dashIndex > 0 ? version.Substring(dashIndex + 1) : "";

            // Save changes
            EditorUtility.SetDirty(versionData);
            AssetDatabase.SaveAssets();

            Log($"✓ VersionData updated: {versionData.GetFullVersion()} (Branch: {versionData.branchName}, Build: {versionData.buildNumber})");
        }

public static void Build(string outputPath, BuildOptions options)
        {
            int exitCode = 1;

            try
            {
                // Update version data before building so it's included in the build
                UpdateVersionData();

                // Capture errors/exceptions logged during the build so we can replay them
                // at the end — Unity's BuildReport often has empty error messages
                capturedErrors.Clear();
                Application.logMessageReceived += CaptureLogErrors;

                string[] scenes = EditorBuildSettings.scenes.Where(s => s.enabled).Select(s => s.path).ToArray();

                Log($"Building with defines: {string.Join(",", ScriptingDefines.GetList(EditorUserBuildSettings.selectedBuildTargetGroup))}");

                try
                {
                    report = BuildPipeline.BuildPlayer
                    (
                        new BuildPlayerOptions()
                        {
                            scenes = scenes,
                            target = EditorUserBuildSettings.activeBuildTarget,
                            locationPathName = outputPath,
                            options = options,
                            targetGroup = EditorUserBuildSettings.selectedBuildTargetGroup
                        }
                    );
                }
                catch (Exception ex)
                {
                    Debug.LogException(ex);
                }
                finally
                {
                    Application.logMessageReceived -= CaptureLogErrors;
                }

                bool buildSuccess = report != null && report.summary.result == BuildResult.Succeeded;
                exitCode = buildSuccess ? 0 : 1;

                if (!buildSuccess && report != null)
                {
                    ReportBuildErrors(report);
                }
                else
                {
                    ListBuildOutput(outputPath);
                }
            }
            finally
            {
                Log($"Exiting Unity with code: {exitCode}");
                EditorApplication.Exit(exitCode);
            }

        }

        private static void ListBuildOutput(string outputPath)
        {
            Log("========== BUILD OUTPUT ==========");

            // Get the directory containing the output
            string outputDir = Path.GetDirectoryName(outputPath);
            if (string.IsNullOrEmpty(outputDir) || !Directory.Exists(outputDir))
            {
                Log($"Output directory not found: {outputDir}");
                return;
            }

            // List all files
            var files = Directory.GetFiles(outputDir, "*", SearchOption.TopDirectoryOnly);
            foreach (var file in files)
            {
                var info = new FileInfo(file);
                Log($"  FILE: {info.Name} ({info.Length / 1024.0 / 1024.0:F2} MB)");
            }

            // List all directories
            var dirs = Directory.GetDirectories(outputDir, "*", SearchOption.TopDirectoryOnly);
            foreach (var dir in dirs)
            {
                var dirInfo = new DirectoryInfo(dir);
                long size = GetDirectorySize(dirInfo);
                Log($"  DIR:  {dirInfo.Name}/ ({size / 1024.0 / 1024.0:F2} MB)");
            }

            Log("==================================");
        }

        private static long GetDirectorySize(DirectoryInfo dir)
        {
            long size = 0;
            try
            {
                foreach (var file in dir.GetFiles("*", SearchOption.AllDirectories))
                {
                    size += file.Length;
                }
            }
            catch { }
            return size;
        }

        private static void ReportBuildErrors(BuildReport report)
        {
            int errorCount = 0;
            int exceptionCount = 0;

            foreach (var step in report.steps)
            {
                foreach (var msg in step.messages)
                {
                    if (msg.type == LogType.Exception)
                        exceptionCount++;
                    else if (msg.type == LogType.Error)
                        errorCount++;
                }
            }

            LogError("========== BUILD FAILED ==========");
            LogError($"Result: {report.summary.result} | Platform: {report.summary.platform} | Errors: {errorCount} | Exceptions: {exceptionCount}");

            foreach (var step in report.steps)
            {
                foreach (var msg in step.messages)
                {
                    if (msg.type == LogType.Exception || msg.type == LogType.Error)
                    {
                        LogError($"[{step.name}] [{msg.type}] {msg.content}");
                    }
                }
            }

            // Replay captured errors that aren't already in the build report
            var reportedMessages = new System.Collections.Generic.HashSet<string>();
            foreach (var step in report.steps)
            {
                foreach (var msg in step.messages)
                {
                    if (msg.type == LogType.Exception || msg.type == LogType.Error)
                    {
                        reportedMessages.Add(msg.content);
                    }
                }
            }

            var extraErrors = capturedErrors.Where(e => {
                // Strip the "[Error] " or "[Exception] " prefix for comparison
                int idx = e.IndexOf("] ");
                string content = idx >= 0 ? e.Substring(idx + 2) : e;
                return !reportedMessages.Contains(content);
            }).ToList();

            if (extraErrors.Count > 0)
            {
                LogError("---------- Captured Errors ----------");
                foreach (var error in extraErrors)
                {
                    LogError(error);
                }
            }

            if (errorCount == 0 && exceptionCount == 0 && extraErrors.Count == 0)
            {
                LogError("No error details found — check the console log above for the actual error.");
            }

            LogError("==================================");
        }

        private static void CaptureLogErrors(string message, string stackTrace, LogType type)
        {
            if (type == LogType.Error || type == LogType.Exception)
            {
                var entry = $"[{type}] {message}";
                if (!string.IsNullOrEmpty(stackTrace))
                    entry += $"\n{stackTrace}";
                capturedErrors.Add(entry);
            }
        }

        #region Logging

        private static string GetCallerPrefix()
        {
            var stackTrace = new System.Diagnostics.StackTrace();
            var frame = stackTrace.GetFrame(2);
            var method = frame?.GetMethod();

            if (method != null)
            {
                var className = method.DeclaringType?.Name ?? "Unknown";
                var methodName = method.Name;
                return $"[{className}.{methodName}]";
            }

            return "[Pipeline]";
        }

        public static void Log(string message)
        {
            Debug.LogFormat(LogType.Log, LogOption.NoStacktrace, null, "{0}", $"{GetCallerPrefix()} {message}");
        }

        public static void LogError(string message)
        {
            Debug.LogFormat(LogType.Error, LogOption.NoStacktrace, null, "{0}", $"{GetCallerPrefix()} {message}");
        }

        public static void LogWarning(string message)
        {
            string formattedMessage = $"{GetCallerPrefix()} {message}";
            Debug.LogWarning(formattedMessage);
        }

        #endregion
    }

}
