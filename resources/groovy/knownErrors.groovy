// ============================================================================
// KNOWN ERRORS — Pattern matching for common build failures
// ============================================================================
// When a failure message matches a known pattern, AI analysis is skipped and
// a human-written explanation + fix is shown instead. Add new entries to
// KNOWN_ERROR_PATTERNS as they are discovered.
//
// Each entry has:
//   pattern  — substring matched against the failure message (case-sensitive)
//   explain  — (optional) closure that receives the failure message and returns
//              a user-friendly explanation. Falls back to the raw message.
//   fix      — (optional) closure or string with remediation steps.
// ============================================================================

/**
 * Registry of known error patterns.
 * Add new entries here as common failures are identified.
 */
def getPatterns() {
    return [
        [
            pattern: "contains '@' suffix",
            explain: { msg -> "Workspace contains an '@' suffix — Jenkins created a duplicate workspace due to a workspace path collision (e.g. customWorkspace matching the default) or a concurrent build." },
            fix: "Remove customWorkspace from the agent block if it matches the default path. If concurrent builds are the cause, enable 'Do not allow concurrent builds' in the job config."
        ],
        [
            pattern: 'Missing env vars',
            explain: { msg -> msg },
            fix: 'Add the missing environment variables in the Jenkins job configuration.'
        ],
        [
            pattern: 'Missing required',
            explain: { msg -> msg },
            fix: 'Check the job configuration for missing required parameters.'
        ],
        [
            pattern: 'not found',
        ],
        [
            pattern: 'not available',
        ],
        [
            pattern: 'not installed',
            fix: 'Install the missing tool on the build agent, or check that it is on the PATH.'
        ],
    ]
}

/**
 * Check a failure message against all known error patterns.
 *
 * @param failMsg  The exception/failure message from the build
 * @return Map with [matched: true/false, explanation: String, fix: String or null]
 *         or null if no pattern matched
 */
def match(String failMsg) {
    if (!failMsg) return null

    for (entry in getPatterns()) {
        if (failMsg.contains(entry.pattern)) {
            def explanation = entry.explain ? entry.explain(failMsg) : failMsg
            def fix = entry.fix instanceof Closure ? entry.fix(failMsg) : entry.fix
            return [matched: true, explanation: explanation, fix: fix]
        }
    }
    return null
}
