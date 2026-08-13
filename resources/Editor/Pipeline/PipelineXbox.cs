using System;
using System.IO;
using UnityEditor;
using UnityEditor.Build;
using UnityEditor.Build.Profile;
using UnityEngine;

namespace ODDFramework
{
    /// <summary>
    /// Xbox (Microsoft GDK / GameCore) builds.
    ///
    /// Which console gets built comes from the -buildTarget the pipeline passes
    /// (GameCoreXboxSeries or GameCoreXboxOne). This script deliberately never names a
    /// GameCore-only enum member or editor API: those live in the licensed module's editor
    /// extension assembly, so referencing one would stop these scripts compiling on any agent
    /// that doesn't have that exact module. Settings that can only be set through the module's
    /// own API belong in a Build Profile asset instead (see TryActivateBuildProfile).
    /// </summary>
    public static class PipelineXbox
    {
        public static void PrepareDebug()
        {
            Pipeline.Prepare("DEVELOPMENT_BUILD,XBOX_BUILD");
        }

        public static void Debug()
        {
            if (!TryActivateBuildProfile("Assets/Editor/Pipeline/BuildProfiles/XboxDebug.buildprofile"))
                ApplyDebugSettings();

            // No CompressWithLz4HC: GameCore packaging (XVC/MSIXVC) compresses the package itself,
            // and engine-level compression conflicts with the console packagers the same way it
            // does with Switch ROM creation.
            Pipeline.Build(GetOutputPath(), BuildOptions.Development);
        }

        public static void PrepareRelease()
        {
            Pipeline.Prepare("XBOX_BUILD");
        }

        public static void Release()
        {
            if (!TryActivateBuildProfile("Assets/Editor/Pipeline/BuildProfiles/XboxRelease.buildprofile"))
                ApplyReleaseSettings();

            Pipeline.Build(GetOutputPath(), BuildOptions.None);
        }

        private static void ApplyDebugSettings()
        {
            EditorUserBuildSettings.allowDebugging = true;
            EditorUserBuildSettings.connectProfiler = true;
            PlayerSettings.enableInternalProfiler = true;

            PlayerSettings.stripEngineCode = false;

            PlayerSettings.SetStackTraceLogType(LogType.Log, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Warning, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Error, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Assert, StackTraceLogType.Full);
            PlayerSettings.SetStackTraceLogType(LogType.Exception, StackTraceLogType.Full);

            ApplyScriptingSettings(Il2CppCompilerConfiguration.Debug, ManagedStrippingLevel.Minimal, false);
        }

        private static void ApplyReleaseSettings()
        {
            EditorUserBuildSettings.allowDebugging = false;
            EditorUserBuildSettings.connectProfiler = false;
            PlayerSettings.enableInternalProfiler = false;

            PlayerSettings.stripEngineCode = true;

            PlayerSettings.SetStackTraceLogType(LogType.Log, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Warning, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Error, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Assert, StackTraceLogType.ScriptOnly);
            PlayerSettings.SetStackTraceLogType(LogType.Exception, StackTraceLogType.ScriptOnly);

            ApplyScriptingSettings(Il2CppCompilerConfiguration.Release, ManagedStrippingLevel.Minimal, true);
        }

        /// <summary>
        /// Apply IL2CPP settings to whatever console target -buildTarget selected. GameCore has no
        /// NamedBuildTarget.* field to reference by name, so it is derived from the active target
        /// group at runtime; if that ever fails, the project's own player settings are left alone
        /// rather than failing the build.
        /// </summary>
        private static void ApplyScriptingSettings(Il2CppCompilerConfiguration compilerConfig, ManagedStrippingLevel stripping, bool optimizeSpeed)
        {
            NamedBuildTarget target;
            try
            {
                target = NamedBuildTarget.FromBuildTargetGroup(EditorUserBuildSettings.selectedBuildTargetGroup);
            }
            catch (Exception ex)
            {
                Pipeline.LogWarning($"[Xbox] Could not resolve a NamedBuildTarget for {EditorUserBuildSettings.selectedBuildTargetGroup}: {ex.Message}. Keeping the project's existing player settings.");
                return;
            }

            // GameCore is IL2CPP-only, but set it explicitly so a mis-set project can't silently
            // build with a different backend.
            PlayerSettings.SetScriptingBackend(target, ScriptingImplementation.IL2CPP);
            PlayerSettings.SetManagedStrippingLevel(target, stripping);
            PlayerSettings.SetIl2CppCompilerConfiguration(target, compilerConfig);
            PlayerSettings.SetIl2CppStacktraceInformation(target, Il2CppStacktraceInformation.MethodFileLineNumber);
            if (optimizeSpeed)
                PlayerSettings.SetIl2CppCodeGeneration(target, Il2CppCodeGeneration.OptimizeSpeed);

            Pipeline.Log($"[Xbox] Player settings applied for {target.TargetName}: IL2CPP, stripping={stripping}, il2cpp={compilerConfig}");
        }

        private static bool TryActivateBuildProfile(string assetPath)
        {
            var profile = AssetDatabase.LoadAssetAtPath<BuildProfile>(assetPath);
            if (profile == null) return false;
            BuildProfile.SetActiveBuildProfile(profile);
            Pipeline.Log($"[Pipeline] Activated build profile: {assetPath}");
            return true;
        }

        /// <summary>
        /// GameCore builds write a loose layout (executable + MicrosoftGame.config + data) into a
        /// folder rather than a single file, so the output path is a directory.
        /// </summary>
        private static string GetOutputPath()
        {
            string productName = PlayerSettings.productName.Replace(" ", "");
            return Path.Combine(Pipeline.BuildPath, "output", productName);
        }
    }
}
