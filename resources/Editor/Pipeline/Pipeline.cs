using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using UnityEditor;
using UnityEditor.AddressableAssets;
using UnityEditor.AddressableAssets.Build;
using UnityEditor.AddressableAssets.Settings;
using UnityEditor.AddressableAssets.Settings.GroupSchemas;
using UnityEditor.Build.Reporting;
using UnityEngine;
using UnityEngine.AddressableAssets;
using UnityEngine.AddressableAssets.ResourceLocators;
using UnityEngine.ResourceManagement.AsyncOperations;
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
            // Always force Unity to use its bundled JDK, SDK, NDK ("Installed with Unity").
            // Setting to null = embedded. If the module isn't installed, verifyAndroidJdk()
            // in the preflight stage will have already failed the build before we get here.
            // See: https://docs.unity3d.com/6000.0/Documentation/ScriptReference/Android.AndroidExternalToolsSettings-jdkRootPath.html
            AndroidExternalToolsSettings.jdkRootPath = null;
            AndroidExternalToolsSettings.sdkRootPath = null;
            AndroidExternalToolsSettings.ndkRootPath = null;
            Log("✓ Android external tools set to embedded (JDK, SDK, NDK)");
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
                // Ensure embedded Android tools are set for every Unity invocation
                // (Prepare and Build run as separate processes — EditorPrefs may not persist in batchmode)
                UseEmbeddedAndroidTools();

                // Update version data before building so it's included in the build
                UpdateVersionData();

                // Addressables content is built by the player build itself when the project's
                // "Build Addressables on Player Build" setting is on; only build it up-front when
                // that setting is off (otherwise nothing would build it). Self-skips if unused.
                EnsureAddressablesContentBuilt(clean: CleanAddressablesRequested());

                report = RunPlayerBuild(outputPath, options);

                // Verify the shipped catalog indexes every entry it should. A stale incremental
                // cache can emit up-to-date bundles under a catalog that omits recently-added
                // entries ("No Location found for Key=<guid>" at runtime, e.g. blank preview icons);
                // if so, purge + rebuild the player once, and fail if a clean rebuild can't fix it.
                report = ValidateCatalogWithRebuild(report, outputPath, options);

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
            catch (Exception ex)
            {
                LogError($"Build failed: {ex.Message}");
                exitCode = 1;
            }
            finally
            {
                Log($"Exiting Unity with code: {exitCode}");
                EditorApplication.Exit(exitCode);
            }

        }

        /// <summary>
        /// Build the Addressables catalog + bundles up-front, but ONLY when the player build won't do
        /// it itself (project setting "Build Addressables on Player Build" = off). When that setting
        /// is on, the player build owns the content build and this is a no-op — the pipeline validates
        /// that build's output afterwards instead of producing a second, redundant copy. Skips
        /// entirely when BUILD_ADDRESSABLES=false or no Addressables settings exist.
        /// </summary>
        private static void EnsureAddressablesContentBuilt(bool clean)
        {
            if (!AddressablesEnabledForBuild())
                return;

            if (SettingBuildsAddressablesWithPlayer())
            {
                Log("Addressables builds with the player build — deferring to it (its catalog is validated afterwards)");
                return;
            }

            var sw = System.Diagnostics.Stopwatch.StartNew();

            if (clean)
            {
                Log("Cleaning previous Addressables content (forces a full rebuild)");
                AddressableAssetSettings.CleanPlayerContent();
            }

            Log($"Building Addressables content for {EditorUserBuildSettings.activeBuildTarget} ({(clean ? "clean/full" : "incremental")})...");

            AddressableAssetSettings.BuildPlayerContent(out AddressablesPlayerBuildResult result);
            sw.Stop();

            if (result != null && !string.IsNullOrEmpty(result.Error))
            {
                LogError($"Addressables content build FAILED after {sw.Elapsed.TotalSeconds:F1}s: {result.Error}");
                throw new Exception($"Addressables content build failed: {result.Error}");
            }

            Log($"✓ Addressables content build complete in {sw.Elapsed.TotalSeconds:F1}s");
        }

        /// <summary>
        /// Build the player. Extracted so the catalog validator can re-run it after a clean
        /// Addressables rebuild. Errors logged during the build are captured for replay.
        /// </summary>
        private static BuildReport RunPlayerBuild(string outputPath, BuildOptions options)
        {
            // Capture errors/exceptions logged during the build so we can replay them at the end —
            // Unity's BuildReport often has empty error messages.
            capturedErrors.Clear();
            Application.logMessageReceived += CaptureLogErrors;

            string[] scenes = EditorBuildSettings.scenes.Where(s => s.enabled).Select(s => s.path).ToArray();

            Log($"Building with defines: {string.Join(",", ScriptingDefines.GetList(EditorUserBuildSettings.selectedBuildTargetGroup))}");

            BuildReport playerReport = null;
            try
            {
                playerReport = BuildPipeline.BuildPlayer
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

            return playerReport;
        }

        /// <summary>
        /// Verify every shipped Addressable entry actually has a catalog location, and if not, purge
        /// the incremental build cache and rebuild the player once. An incremental Addressables build
        /// can emit up-to-date bundles while shipping a STALE catalog that omits recently-added
        /// entries: the bundle is present but AssetReference loads throw "No Location found for
        /// Key=&lt;guid&gt;" at runtime (blank preview icons, missing content). A catalog rebuilt
        /// against a purged cache matches the bundles again; if entries are still absent after a clean
        /// rebuild the build is failed rather than shipping blank content. Only runs on a succeeded
        /// player build that ships Addressables. Project-agnostic: keys off the Addressables settings.
        /// </summary>
        private static BuildReport ValidateCatalogWithRebuild(BuildReport playerReport, string outputPath, BuildOptions options)
        {
            if (playerReport == null || playerReport.summary.result != BuildResult.Succeeded)
                return playerReport; // build failed for other reasons — leave the normal error path to report it
            if (!AddressablesEnabledForBuild())
                return playerReport;

            var missing = FindGuidsMissingFromCatalog(out int checkedCount);
            if (missing == null)
                return playerReport; // catalog couldn't be loaded — already warned

            if (missing.Count == 0)
            {
                Log($"✓ Catalog validation passed — all {checkedCount} indexed entries present");
                return playerReport;
            }

            LogWarning($"Catalog validation FAILED: {missing.Count} of {checkedCount} entries have no catalog location " +
                       $"(stale incremental cache). First: '{missing[0]}'. Purging cache and rebuilding the player once...");

            AddressableAssetSettings.CleanPlayerContent();
            PurgeAddressablesBuildCache();
            // When the pipeline owns the content build (setting off), rebuild it now so the re-run
            // player build has fresh content to package. No-op when the player build owns it.
            EnsureAddressablesContentBuilt(clean: true);

            playerReport = RunPlayerBuild(outputPath, options);
            if (playerReport == null || playerReport.summary.result != BuildResult.Succeeded)
                return playerReport; // rebuild failed — surfaced by the normal error path

            var stillMissing = FindGuidsMissingFromCatalog(out checkedCount);
            if (stillMissing != null && stillMissing.Count > 0)
            {
                throw new Exception(
                    $"Addressables catalog is STILL missing {stillMissing.Count} of {checkedCount} entries after a clean rebuild " +
                    $"(first: '{stillMissing[0]}'). An asset is likely unbuildable — failing the build rather than shipping blank content.");
            }

            Log("✓ Catalog validation passed after clean rebuild");
            return playerReport;
        }

        /// <summary>
        /// Returns the addresses of shipped entries whose GUID has no location in the freshly-built
        /// catalog, or null if the catalog couldn't be loaded (validation skipped). Loads the built
        /// catalog with the same runtime API that resolves AssetReferences (LoadContentCatalogAsync →
        /// IResourceLocator.Locate), so the check matches exactly how the game fails at runtime — no
        /// binary parsing. Only groups that both ship (IncludeInBuild) and index GUIDs
        /// (IncludeGUIDInCatalog) are checked — that is exactly the set an AssetReference resolves by
        /// GUID.
        /// </summary>
        private static List<string> FindGuidsMissingFromCatalog(out int checkedCount)
        {
            checkedCount = 0;

            string catalogPath = LocateBuiltCatalog();
            if (catalogPath == null)
            {
                LogWarning("Could not locate the built Addressables catalog — skipping catalog validation.");
                return null;
            }

            AsyncOperationHandle<IResourceLocator> handle = default;
            IResourceLocator locator;
            try
            {
                handle = Addressables.LoadContentCatalogAsync(catalogPath, false);
                locator = handle.WaitForCompletion();
            }
            catch (Exception ex)
            {
                if (handle.IsValid())
                    Addressables.Release(handle);
                LogWarning($"Could not load the built catalog at '{catalogPath}' ({ex.Message}) — skipping catalog validation.");
                return null;
            }

            if (locator == null)
            {
                if (handle.IsValid())
                    Addressables.Release(handle);
                LogWarning($"Built catalog at '{catalogPath}' produced no locator — skipping catalog validation.");
                return null;
            }

            try
            {
                var settings = AddressableAssetSettingsDefaultObject.Settings;
                var missing = new List<string>();
                var gathered = new List<AddressableAssetEntry>();

                foreach (var group in settings.groups)
                {
                    var schema = group != null ? group.GetSchema<BundledAssetGroupSchema>() : null;
                    if (schema == null || !schema.IncludeInBuild || !schema.IncludeGUIDInCatalog)
                        continue;

                    gathered.Clear();
                    foreach (var entry in group.entries)
                        entry.GatherAllAssets(gathered, includeSelf: true, recurseAll: true, includeSubObjects: false);

                    foreach (var e in gathered)
                    {
                        if (e == null || e.IsFolder || string.IsNullOrEmpty(e.guid) || string.IsNullOrEmpty(e.AssetPath))
                            continue;
                        if (!File.Exists(e.AssetPath))
                            continue; // no on-disk asset — legitimately not shipped

                        checkedCount++;
                        // type: null → pure key-existence check, mirroring how AssetReference resolves.
                        if (!locator.Locate(e.guid, null, out _))
                            missing.Add(e.address);
                    }
                }

                return missing;
            }
            finally
            {
                Addressables.Release(handle);
            }
        }

        private static string LocateBuiltCatalog()
        {
            string buildPath = Addressables.BuildPath;
            if (Directory.Exists(buildPath))
            {
                var found = Directory.GetFiles(buildPath, "catalog*.*", SearchOption.AllDirectories)
                                     .FirstOrDefault(IsCatalogFile);
                if (found != null)
                    return found;
            }

            return null;
        }

        private static bool IsCatalogFile(string path)
        {
            string f = Path.GetFileName(path);
            return f.StartsWith("catalog", StringComparison.OrdinalIgnoreCase)
                   && (f.EndsWith(".bin", StringComparison.OrdinalIgnoreCase) || f.EndsWith(".json", StringComparison.OrdinalIgnoreCase));
        }

        /// <summary>
        /// Purge the ScriptableBuildPipeline incremental cache so the next content build is fully
        /// from-scratch. It is this cache's staleness that produces a catalog omitting new entries.
        /// </summary>
        private static void PurgeAddressablesBuildCache()
        {
            try
            {
                UnityEditor.Build.Pipeline.Utilities.BuildCache.PurgeCache(false);
                Log("✓ Purged ScriptableBuildPipeline BuildCache");
            }
            catch (Exception ex)
            {
                LogWarning($"Could not purge SBP BuildCache (continuing with CleanPlayerContent only): {ex.Message}");
            }
        }

        private static bool CleanAddressablesRequested()
        {
            return string.Equals(GetVariable("CLEAN_ADDRESSABLES", "false"), "true", StringComparison.OrdinalIgnoreCase);
        }

        /// <summary>
        /// True when Addressables should participate in this build — BUILD_ADDRESSABLES (default
        /// "true") is on and the project actually has Addressables settings.
        /// </summary>
        private static bool AddressablesEnabledForBuild()
        {
            if (!string.Equals(GetVariable("BUILD_ADDRESSABLES", "true"), "true", StringComparison.OrdinalIgnoreCase))
            {
                Log("BUILD_ADDRESSABLES=false — skipping Addressables content build/validation");
                return false;
            }

            if (!AddressableAssetSettingsDefaultObject.SettingsExists)
            {
                Log("No AddressableAssetSettings configured — skipping Addressables content build/validation");
                return false;
            }

            return true;
        }

        /// <summary>
        /// Whether the player build will build Addressables content itself (mirrors the Addressables
        /// package's own ShouldBuildAddressablesForPlayerBuild). When true the pipeline must not build
        /// a second copy; when false the pipeline owns the content build.
        /// </summary>
        private static bool SettingBuildsAddressablesWithPlayer()
        {
            var settings = AddressableAssetSettingsDefaultObject.Settings;
            switch (settings.BuildAddressablesWithPlayerBuild)
            {
                case AddressableAssetSettings.PlayerBuildOption.DoNotBuildWithPlayer:
                    return false;
                case AddressableAssetSettings.PlayerBuildOption.BuildWithPlayer:
                    return true;
                default: // PreferencesValue — mirror the package's EditorPref lookup
                    return EditorPrefs.GetBool("Addressables.BuildAddressablesWithPlayerBuild", true);
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
                        // Gradle errors arrive as the entire gradle stdout in one message,
                        // where the real cause ("Manifest merger failed", etc.) is buried far
                        // below hundreds of harmless "Configure project" warnings. Surface the
                        // "FAILURE: Build failed" → "BUILD FAILED" block up front when present.
                        string content = ExtractGradleFailure(msg.content) ?? msg.content;
                        LogError($"[{step.name}] [{msg.type}] {content}");
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

        /// <summary>
        /// If the message contains a Gradle failure banner, return just the failure block
        /// ("FAILURE: Build failed with an exception." through the "BUILD FAILED in <n>s"
        /// line) with a header. This is where the real cause is printed. Returns null when
        /// no banner is present so the caller falls back to the original content.
        /// </summary>
        private static string ExtractGradleFailure(string content)
        {
            if (string.IsNullOrEmpty(content)) return null;

            int start = content.IndexOf("FAILURE: Build failed with an exception", StringComparison.Ordinal);
            if (start < 0) return null;

            int end = content.IndexOf("BUILD FAILED", start, StringComparison.Ordinal);
            // Include the "BUILD FAILED in <n>s" line itself if we found it.
            if (end > start)
            {
                int eol = content.IndexOf('\n', end);
                end = eol > end ? eol : content.Length;
            }
            else
            {
                end = content.Length;
            }

            string block = content.Substring(start, end - start).Trim();
            return $"Gradle build failed — extracted cause (full output above/in console):\n{block}";
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
