using System.IO;
using UnityEditor;
using UnityEditor.Build;
using UnityEngine;

namespace ODDFramework
{
    public static class PipelineSwitch
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

            PlayerSettings.SetScriptingBackend(NamedBuildTarget.NintendoSwitch, ScriptingImplementation.IL2CPP);
            PlayerSettings.SetManagedStrippingLevel(NamedBuildTarget.NintendoSwitch, ManagedStrippingLevel.Minimal);
            PlayerSettings.SetIl2CppCompilerConfiguration(NamedBuildTarget.NintendoSwitch, Il2CppCompilerConfiguration.Debug);
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

            PlayerSettings.SetScriptingBackend(NamedBuildTarget.NintendoSwitch, ScriptingImplementation.IL2CPP);
            PlayerSettings.SetManagedStrippingLevel(NamedBuildTarget.NintendoSwitch, ManagedStrippingLevel.Minimal);
            PlayerSettings.SetIl2CppCompilerConfiguration(NamedBuildTarget.NintendoSwitch, Il2CppCompilerConfiguration.Release);
            PlayerSettings.SetIl2CppCodeGeneration(NamedBuildTarget.NintendoSwitch, Il2CppCodeGeneration.OptimizeSpeed);
        }

        public static void PrepareDebug()
        {
            Pipeline.Prepare("DEVELOPMENT_BUILD");
        }

        public static void Debug()
        {
            ApplyDebugSettings();

            // NSP output
            EditorUserBuildSettings.switchCreateRomFile = true;
            EditorUserBuildSettings.switchEnableRomCompression = false;

            // Debug tools
            EditorUserBuildSettings.switchEnableDebugPad = true;
            EditorUserBuildSettings.switchNVNGraphicsDebugger = true;

            Pipeline.Build(GetOutputPath("Debug"), BuildOptions.Development);
        }

        public static void PrepareRelease()
        {
            Pipeline.Prepare("");
        }

        public static void Release()
        {
            ApplyReleaseSettings();

            // NSP output with compression
            EditorUserBuildSettings.switchCreateRomFile = true;
            EditorUserBuildSettings.switchEnableRomCompression = true;
            EditorUserBuildSettings.switchRomCompressionType = SwitchRomCompressionType.Lz4;

            // No debug tools in release
            EditorUserBuildSettings.switchEnableDebugPad = false;
            EditorUserBuildSettings.switchNVNGraphicsDebugger = false;

            Pipeline.Build(GetOutputPath("Release"), BuildOptions.None);
        }

        private static string GetOutputPath(string buildType)
        {
            string productName = PlayerSettings.productName.Replace(" ", "");
            string filename = $"output/{productName}.nsp";
            return Path.Combine(Pipeline.BuildPath, filename);
        }
    }
}
