using System.IO;
using UnityEditor;
using UnityEditor.Build;
using UnityEditor.Build.Profile;
using UnityEngine;

namespace ODDFramework
{
    public static class PipelineSteam
    {
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

            PlayerSettings.SetScriptingBackend(NamedBuildTarget.Standalone, ScriptingImplementation.Mono2x);
            PlayerSettings.SetManagedStrippingLevel(NamedBuildTarget.Standalone, ManagedStrippingLevel.Medium);
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

            PlayerSettings.SetScriptingBackend(NamedBuildTarget.Standalone, ScriptingImplementation.Mono2x);
            PlayerSettings.SetManagedStrippingLevel(NamedBuildTarget.Standalone, ManagedStrippingLevel.High);
        }

        public static void PrepareDebug()
        {
            Pipeline.Prepare("DEVELOPMENT_BUILD,STEAM_BUILD");
        }

        public static void Debug()
        {
            if (!TryActivateBuildProfile("Assets/Editor/Pipeline/BuildProfiles/SteamDebug.buildprofile"))
                ApplyDebugSettings();
            Pipeline.Build(GetOutputPath("Debug"), BuildOptions.CompressWithLz4HC | BuildOptions.Development);
        }

        public static void PrepareRelease()
        {
            Pipeline.Prepare("STEAM_BUILD");
        }

        public static void Release()
        {
            if (!TryActivateBuildProfile("Assets/Editor/Pipeline/BuildProfiles/SteamRelease.buildprofile"))
                ApplyReleaseSettings();
            Pipeline.Build(GetOutputPath("Release"), BuildOptions.CompressWithLz4HC);
        }

        private static bool TryActivateBuildProfile(string assetPath)
        {
            var profile = AssetDatabase.LoadAssetAtPath<BuildProfile>(assetPath);
            if (profile == null) return false;
            BuildProfile.SetActiveBuildProfile(profile);
            UnityEngine.Debug.Log($"[Pipeline] Activated build profile: {assetPath}");
            return true;
        }

        private static string GetOutputPath(string buildType)
        {
            string productName = PlayerSettings.productName.Replace(" ", "");
            string extension = EditorUserBuildSettings.activeBuildTarget == BuildTarget.StandaloneLinux64 ? ".x86_64" : ".exe";
            string filename = $"output/{productName}{extension}";
            return Path.Combine(Pipeline.BuildPath, filename);
        }
    }
}
