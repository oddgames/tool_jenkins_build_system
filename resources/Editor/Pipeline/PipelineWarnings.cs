using System;
using System.Collections.Generic;
using System.IO;
using System.Text.RegularExpressions;
using UnityEditor;
using UnityEngine;

namespace ODDFramework
{
    /// <summary>
    /// Build warnings that make the Jenkins build UNSTABLE (yellow) without failing it.
    ///
    /// Raise one from anywhere in the project - runtime or editor code, any assembly:
    ///   Debug.LogWarning("[BUILD-WARN] 3 localisation keys missing");   // marker in any log message
    ///   Pipeline.Warn("3 localisation keys missing");                   // editor code that can see Pipeline
    /// Both land in ARTIFACT_PATH/build_warnings.txt (one per line, de-duplicated). The Jenkins
    /// "Build Warnings" stage reads that file, flags the build UNSTABLE, and lists the lines in the
    /// Slack post, on a badge, and in a sidebar link. The player still builds and uploads.
    ///
    /// Optional: UNSTABLE_WARNING_PATTERNS (Jenkins job env, ';'-separated regexes, case-insensitive)
    /// promotes matching native Unity warnings/errors too, e.g. "Shader error in;Missing script".
    ///
    /// The log hook is installed on every editor load in batch mode, so warnings raised during asset
    /// import, Prepare, and the player build are all caught. Cost: one string search per log line,
    /// one file append per NEW warning. No Unity API is touched from the (possibly worker-thread)
    /// callback. Outside batch mode / Jenkins this whole file is inert.
    /// </summary>
    public static partial class Pipeline
    {
        public const string WarningMarker = "[BUILD-WARN]";
        private const string WarningsFileName = "build_warnings.txt";
        private const int MaxWarningLength = 300;

        private static readonly object warnLock = new object();
        private static HashSet<string> recordedWarnings;   // lazily seeded from the file (survives domain reloads)
        private static Regex[] warningPatterns;

        [InitializeOnLoadMethod]
        private static void InstallWarningHook()
        {
            if (!Application.isBatchMode || string.IsNullOrEmpty(ArtifactPath))
                return;

            warningPatterns = ParseWarningPatterns(GetVariable("UNSTABLE_WARNING_PATTERNS", string.Empty));

            // Re-registered after every domain reload; the previous delegate died with the old domain.
            Application.logMessageReceivedThreaded -= OnLogMessageForWarnings;
            Application.logMessageReceivedThreaded += OnLogMessageForWarnings;
        }

        /// <summary>Raise a build warning: recorded for Jenkins and echoed to the log with the marker.</summary>
        public static void Warn(string message)
        {
            if (string.IsNullOrWhiteSpace(message)) return;
            RecordWarning(message);
            Debug.LogWarning($"{WarningMarker} {message}");
        }

        private static void OnLogMessageForWarnings(string condition, string stackTrace, LogType type)
        {
            try
            {
                if (string.IsNullOrEmpty(condition)) return;

                int idx = condition.IndexOf(WarningMarker, StringComparison.Ordinal);
                if (idx >= 0)
                {
                    RecordWarning(condition.Substring(idx + WarningMarker.Length));
                    return;
                }

                if (warningPatterns == null || warningPatterns.Length == 0 || type == LogType.Log)
                    return;

                foreach (Regex pattern in warningPatterns)
                {
                    if (pattern.IsMatch(condition))
                    {
                        RecordWarning(condition);
                        return;
                    }
                }
            }
            catch
            {
                // A log handler must never throw - it would take the build down with it.
            }
        }

        /// <summary>First line only, trimmed and capped, so one warning is one line in the report.</summary>
        private static string NormalizeWarning(string raw)
        {
            string line = raw.Trim();
            int nl = line.IndexOfAny(new[] { '\r', '\n' });
            if (nl >= 0) line = line.Substring(0, nl).Trim();
            if (line.Length > MaxWarningLength) line = line.Substring(0, MaxWarningLength) + "…";
            return line;
        }

        private static void RecordWarning(string raw)
        {
            string line = NormalizeWarning(raw);
            if (line.Length == 0) return;

            string artifacts = ArtifactPath;
            if (string.IsNullOrEmpty(artifacts)) return;
            string path = Path.Combine(artifacts, WarningsFileName);

            lock (warnLock)
            {
                if (recordedWarnings == null)
                {
                    recordedWarnings = new HashSet<string>(StringComparer.Ordinal);
                    try
                    {
                        if (File.Exists(path))
                            foreach (string existing in File.ReadAllLines(path))
                                recordedWarnings.Add(existing.Trim());
                    }
                    catch { /* unreadable file: start fresh, duplicates are removed on the Jenkins side too */ }
                }

                if (!recordedWarnings.Add(line)) return;

                try
                {
                    Directory.CreateDirectory(artifacts);
                    File.AppendAllText(path, line + System.Environment.NewLine);
                }
                catch (Exception ex)
                {
                    // Can't reach the artifact dir - the log line still carries the marker for humans.
                    Console.Error.WriteLine($"[Pipeline] Could not record build warning to {path}: {ex.Message}");
                }
            }
        }

        private static Regex[] ParseWarningPatterns(string spec)
        {
            if (string.IsNullOrWhiteSpace(spec)) return Array.Empty<Regex>();
            var list = new List<Regex>();
            foreach (string part in spec.Split(';'))
            {
                string p = part.Trim();
                if (p.Length == 0) continue;
                try
                {
                    list.Add(new Regex(p, RegexOptions.IgnoreCase | RegexOptions.CultureInvariant));
                }
                catch (ArgumentException ex)
                {
                    Debug.LogWarning($"[Pipeline] Ignoring invalid UNSTABLE_WARNING_PATTERNS entry '{p}': {ex.Message}");
                }
            }
            return list.ToArray();
        }
    }
}
