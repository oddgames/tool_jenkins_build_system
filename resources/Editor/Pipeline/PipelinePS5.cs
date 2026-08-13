using System;
using System.IO;
using UnityEditor;
using UnityEditor.Build;
using UnityEditor.Build.Profile;
using UnityEngine;

namespace ODDFramework
{
    /// <summary>
    /// PlayStation 5 builds.
    ///
    /// Like the Xbox script, this deliberately never names a PS5-only enum member or editor API
    /// (UnityEditor.PS5.* lives in the licensed module's extension assembly, so referencing it
    /// would stop these scripts compiling on agents without the module). PS5-specific settings
    /// that can only be set through the module's own API belong in a Build Profile asset instead
    /// (see TryActivateBuildProfile).
    /// </summary>
    public static class PipelinePS5
    {
        public static void PrepareDebug()
        {
            Pipeline.Prepare("DEVELOPMENT_BUILD,PS5_BUILD");
        }

        public static void Debug()
        {
            if (!TryActivateBuildProfile("Assets/Editor/Pipeline/BuildProfiles/PS5Debug.buildprofile"))
                ApplyDebugSettings();

            // No CompressWithLz4HC: the PS5 package format does its own compression, and engine
            // compression conflicts with console packaging the same way it does on Switch.
            Pipeline.Build(GetOutputPath(), BuildOptions.Development);
        }

        public static void PrepareRelease()
        {
            Pipeline.Prepare("PS5_BUILD");
        }

        public static void Release()
        {
            if (!TryActivateBuildProfile("Assets/Editor/Pipeline/BuildProfiles/PS5Release.buildprofile"))
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
        /// Apply IL2CPP settings to the active console target. PS5 has no NamedBuildTarget.* field
        /// to reference by name, so it is derived from the active target group at runtime; if that
        /// fails, the project's own player settings are left alone rather than failing the build.
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
                Pipeline.LogWarning($"[PS5] Could not resolve a NamedBuildTarget for {EditorUserBuildSettings.selectedBuildTargetGroup}: {ex.Message}. Keeping the project's existing player settings.");
                return;
            }

            // PS5 is IL2CPP-only, but set it explicitly so a mis-set project can't silently build
            // with a different backend.
            PlayerSettings.SetScriptingBackend(target, ScriptingImplementation.IL2CPP);
            PlayerSettings.SetManagedStrippingLevel(target, stripping);
            PlayerSettings.SetIl2CppCompilerConfiguration(target, compilerConfig);
            PlayerSettings.SetIl2CppStacktraceInformation(target, Il2CppStacktraceInformation.MethodFileLineNumber);
            if (optimizeSpeed)
                PlayerSettings.SetIl2CppCodeGeneration(target, Il2CppCodeGeneration.OptimizeSpeed);

            Pipeline.Log($"[PS5] Player settings applied for {target.TargetName}: IL2CPP, stripping={stripping}, il2cpp={compilerConfig}");
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
        /// PS5 builds write an application layout into a folder rather than a single file, so the
        /// output path is a directory.
        /// </summary>
        private static string GetOutputPath()
        {
            string productName = PlayerSettings.productName.Replace(" ", "");
            return Path.Combine(Pipeline.BuildPath, "output", productName);
        }
    }
}
