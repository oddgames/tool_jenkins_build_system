using System;
using System.IO;
using UnityEngine;
using UnityEditor;
using UnityEditor.Build;
using UnityEditor.Build.Reporting;

#if UNITY_ANDROID
using UnityEditor.Android;
#endif

namespace ODDFramework
{
    public class PipelineArtifactCopy :
#if UNITY_ANDROID
        IPostGenerateGradleAndroidProject,
#endif
        IPostprocessBuildWithReport
    {
        public int callbackOrder => 10000;

        private static readonly string[] AndroidPatterns = { "*.gradle", "*.properties", "*.xml", "*.json", "*.txt" };
        private static readonly string[] iOSPatterns = { "*.plist", "*.pbxproj", "*.entitlements", "*.xcconfig" };

#if UNITY_ANDROID
        public void OnPostGenerateGradleAndroidProject(string gradleProjectPath)
        {
            try
            {
                string artifactPath = Pipeline.ArtifactPath;
                if (string.IsNullOrEmpty(artifactPath))
                {
                    Debug.LogWarning("[PipelineArtifactCopy] ARTIFACT_PATH not set, skipping artifact copy");
                    return;
                }

                Pipeline.Log("Copying Android Gradle artifacts to artifact path");

                string androidArtifactPath = Path.Combine(artifactPath, "android");

                var parentDir = Directory.GetParent(gradleProjectPath);
                if (parentDir != null)
                {
                    CopyMatchingFiles(parentDir.FullName, androidArtifactPath, AndroidPatterns);
                    Pipeline.Log($"✓ Android artifacts copied to: {androidArtifactPath}");
                }
            }
            catch (Exception ex)
            {
                Pipeline.LogError($"Failed to copy Android artifacts: {ex.Message}");
            }
        }
#endif

        public void OnPostprocessBuild(BuildReport report)
        {
#if UNITY_IOS
            if (report.summary.platformGroup == BuildTargetGroup.iOS && report.summary.result == BuildResult.Succeeded)
            {
                try
                {
                    string artifactPath = Pipeline.ArtifactPath;
                    if (string.IsNullOrEmpty(artifactPath))
                    {
                        Debug.LogWarning("[PipelineArtifactCopy] ARTIFACT_PATH not set, skipping artifact copy");
                        return;
                    }

                    Pipeline.Log("Copying iOS Xcode artifacts to artifact path");

                    string iosArtifactPath = Path.Combine(artifactPath, "ios");

                    CopyMatchingFiles(report.summary.outputPath, iosArtifactPath, iOSPatterns);

                    Pipeline.Log($"✓ iOS artifacts copied to: {iosArtifactPath}");
                }
                catch (Exception ex)
                {
                    Pipeline.LogError($"Failed to copy iOS artifacts: {ex.Message}");
                }
            }
#endif
        }

        private void CopyMatchingFiles(string sourceRoot, string destRoot, string[] patterns)
        {
            if (!Directory.Exists(sourceRoot))
            {
                Pipeline.LogWarning($"Source directory does not exist: {sourceRoot}");
                return;
            }

            int copiedCount = 0;

            foreach (var pattern in patterns)
            {
                var matchingFiles = Directory.GetFiles(sourceRoot, pattern, SearchOption.AllDirectories);

                foreach (var sourceFile in matchingFiles)
                {
                    string relativePath = GetRelativePath(sourceRoot, sourceFile);
                    string destFile = Path.Combine(destRoot, relativePath);

                    string destDir = Path.GetDirectoryName(destFile);
                    if (!Directory.Exists(destDir))
                    {
                        Directory.CreateDirectory(destDir);
                    }

                    File.Copy(sourceFile, destFile, true);
                    copiedCount++;
                }
            }

            Pipeline.Log($"  Copied {copiedCount} file(s) matching patterns: {string.Join(", ", patterns)}");
        }

        private string GetRelativePath(string fromPath, string toPath)
        {
            var fromUri = new Uri(AppendDirectorySeparator(fromPath));
            var toUri = new Uri(toPath);

            var relativeUri = fromUri.MakeRelativeUri(toUri);
            string relativePath = Uri.UnescapeDataString(relativeUri.ToString());

            return relativePath.Replace('/', Path.DirectorySeparatorChar);
        }

        private string AppendDirectorySeparator(string path)
        {
            if (!path.EndsWith(Path.DirectorySeparatorChar.ToString()))
            {
                return path + Path.DirectorySeparatorChar;
            }
            return path;
        }
    }
}
