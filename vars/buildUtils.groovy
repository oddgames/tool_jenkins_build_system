// ============================================================================
// JENKINS BUILD UTILS - Shared Library Entry Point
// ============================================================================
// Usage in Jenkinsfile:
//   @Library('tool_jenkins_build_system') _
//
//   pipeline {
//       stages {
//           stage('Setup') {
//               steps {
//                   script {
//                       buildUtils.init()
//                       buildUtils.startup(params.BUILD_TYPE, env.BRANCH)
//                   }
//               }
//           }
//       }
//   }
// ============================================================================

import groovy.transform.Field

@Field def common = null
@Field def platform = null
@Field def cachedIsWindows = null

// ============================================================================
// NODE SELECTION - Runs before init(), no node context needed
// ============================================================================

/**
 * Pick the build node via manual override or idle-node detection.
 * Runs on the controller before agent allocation - does NOT require init().
 *
 * Uses a global lock to serialize node decisions across ALL jobs. Inside the lock:
 *   1. Sleep briefly so the previous build's agent shows as busy
 *   2. Query Jenkins API for an idle node matching the label
 *   3. Return a specific node name (never the label — avoids affinity collisions)
 *
 * If no idle node is found, the build fails immediately (no queuing).
 *
 *   BUILD1, BUILD2 → PC1 (64GB)
 *   BUILD3          → PC2 (16GB)
 *   BUILD4          → PC3 (16GB)
 *   macOS agents: OSX label
 *
 * @param nodeOverride Manual override (from params.NODE). If set, used as-is.
 * @param defaultLabel Label to match ('Windows' or 'OSX')
 * @return Specific node name for agent { label ... }
 */
def pickNode(String nodeOverride, String defaultLabel) {
    if (nodeOverride?.trim()) {
        echo "[Node] Override: ${nodeOverride}"
        return nodeOverride
    }

    def selected = defaultLabel  // Fallback if lock/API unavailable

    try {
        lock(resource: 'jenkins-node-selection') {
            sleep(time: 3, unit: 'SECONDS')
            selected = _findIdleNode(defaultLabel)
        }
    } catch (Exception e) {
        // Lock or API completely unavailable (permissions not yet approved)
        echo "[Node] Lock-based selection failed: ${e.message}"
        echo "[Node] Falling back to label: ${defaultLabel}"
    }

    return selected
}

/**
 * Derive the workspace directory name Jenkins would create for a job.
 * Jenkins replaces '/' with '_' in the job's full name to form the workspace dir.
 * e.g. "folder/game_monster_truck_destruction_amazon" → "folder_game_monster_truck_destruction_amazon"
 */
@NonCPS
private def _workspaceName(String jobName) {
    return jobName?.replaceAll('/', '_') ?: ''
}

/**
 * Query Jenkins API for a node matching the given label that can run this job.
 * A node is available if it has a free executor AND no running job whose workspace
 * directory name would collide with this job's (preventing @2 suffix conflicts).
 * Returns the node name if found, or fails the build if no node is available.
 */
private def _findIdleNode(String label) {
    def result = _queryNodes(label, env.JOB_NAME)

    if (result.log) {
        echo result.log
    }

    if (result.error) {
        error result.error
    }

    return result.node
}

/**
 * Pure Jenkins API query — no pipeline steps (echo/error).
 * Returns a map with: node (selected name or fallback label), log (status text), error (failure message or null).
 */
@NonCPS
private def _queryNodes(String label, String currentJob) {
    try {
        def jenkins = jenkins.model.Jenkins.get()
        def labelObj = jenkins.getLabel(label)

        if (labelObj == null) {
            return [node: label, log: null, error: "[Node] Label '${label}' not found in Jenkins"]
        }

        def nodes = labelObj.getNodes()
        if (!nodes) {
            return [node: label, log: null, error: "[Node] No agents configured for label '${label}'"]
        }

        // Build a map of queued job counts per node so we account for builds
        // that have been assigned a node but haven't started executing yet
        def queuedPerNode = _getQueuedJobsPerNode(jenkins)

        def currentWsName = _workspaceName(currentJob)
        def nodeStatuses = []
        def bestNode = null
        def bestBusyCount = 999

        for (def node : nodes) {
            def computer = node.toComputer()
            if (computer == null) {
                nodeStatuses << "${node.displayName}: no computer"
                continue
            }
            if (!computer.isOnline()) {
                nodeStatuses << "${node.displayName}: offline"
                continue
            }

            def hasFreeExecutor = false
            def hasWorkspaceConflict = false
            def runningJobs = []

            for (def executor : computer.getExecutors()) {
                if (!executor.isBusy()) {
                    hasFreeExecutor = true
                    continue
                }
                try {
                    def executable = executor.getCurrentExecutable()
                    def jobName = executable?.getParent()?.getFullName() ?: 'unknown'
                    runningJobs << jobName
                    if (_workspaceName(jobName) == currentWsName) {
                        hasWorkspaceConflict = true
                    }
                } catch (Exception e) {
                    runningJobs << '?'
                }
            }

            // Check queued items targeting this node for workspace conflicts
            def queuedJobs = queuedPerNode[node.displayName] ?: []
            for (def queuedJob : queuedJobs) {
                if (_workspaceName(queuedJob) == currentWsName) {
                    hasWorkspaceConflict = true
                }
            }

            def busyCount = runningJobs.size() + queuedJobs.size()
            def freeCount = computer.numExecutors - runningJobs.size()
            // A node only has free executors if there are more free slots than queued items waiting
            if (freeCount <= queuedJobs.size()) {
                hasFreeExecutor = false
            }

            def status = "${node.displayName}: ${runningJobs.size()}/${computer.numExecutors} busy"
            if (runningJobs) status += " (${runningJobs.join(', ')})"
            if (queuedJobs) status += " +${queuedJobs.size()} queued"
            if (hasWorkspaceConflict) status += " [ws conflict]"
            nodeStatuses << status

            if (hasFreeExecutor && !hasWorkspaceConflict && busyCount < bestBusyCount) {
                bestNode = node.displayName
                bestBusyCount = busyCount
            }
        }

        def log = "[Node] ${currentJob} (ws: ${currentWsName}) — ${nodeStatuses.join(' | ')}"

        if (bestNode) {
            log += "\n[Node] Selected: ${bestNode}${bestBusyCount == 0 ? ' (empty)' : ''}"
            return [node: bestNode, log: log, error: null]
        }

        return [node: label, log: log, error: "[Node] No available agent for '${currentJob}'. All '${label}' nodes either full or have workspace conflicts.\n${nodeStatuses.join(' | ')}"]

    } catch (Exception e) {
        return [node: label, log: "[Node] Jenkins API query failed: ${e.message}", error: null]
    }
}

/**
 * Scan the Jenkins build queue and return a map of node name -> list of queued job names.
 * Catches builds that are assigned to a node but haven't claimed an executor yet.
 */
@NonCPS
private def _getQueuedJobsPerNode(def jenkins) {
    def queuedPerNode = [:]
    try {
        def queue = jenkins.getQueue()
        for (def item : queue.getItems()) {
            def jobName = item.task?.getFullName() ?: null
            if (!jobName) continue

            // Try to determine which node this queued item targets
            def nodeName = null
            try {
                def assignedLabel = item.getAssignedLabel()
                if (assignedLabel) {
                    // If the label matches a specific node name, use it directly
                    def labelNodes = assignedLabel.getNodes()
                    if (labelNodes?.size() == 1) {
                        nodeName = labelNodes[0].displayName
                    }
                }
            } catch (Exception e) {
                // Ignore — can't determine target node for this queued item
            }

            if (nodeName) {
                if (!queuedPerNode[nodeName]) queuedPerNode[nodeName] = []
                queuedPerNode[nodeName] << jobName
            }
        }
    } catch (Exception e) {
        // Queue API unavailable — return empty map, fall back to executor-only check
    }
    return queuedPerNode
}

// ============================================================================
// INITIALIZATION
// ============================================================================

// Initialize utilities - must be called from within a node context
// Optional params: buildType (e.g. 'Release'), branch (e.g. '/main' or '9613')
// When provided, badges are shown immediately before checkout.
def init(Map config = [:]) {
    if (common != null && platform != null) return this

    try {
        // Load common utilities
        common = evaluate(libraryResource('groovy/common.groovy'))

        // Detect platform using isUnix() - requires node context
        // Cache it so ensureInitialized can reuse if called outside node
        if (cachedIsWindows == null) {
            cachedIsWindows = (isUnix() == false)
        }

        def platformScript = cachedIsWindows ? 'groovy/windows.groovy' : 'groovy/macos.groovy'
        def platformContent = libraryResource(platformScript)
        platform = evaluate(platformContent)
        platform.init(common)
        // Auto-version preflight cache from library content — any code change invalidates.
        // Hash platform + common so preflight reruns when any library file changes.
        def commonContent = libraryResource('groovy/common.groovy')
        def combinedHash = Math.abs((platformContent + commonContent).hashCode())
        platform.PREFLIGHT_VERSION = String.valueOf(combinedHash)
        env.PREFLIGHT_VERSION = platform.PREFLIGHT_VERSION
        common.platformModule = platform

        // Capture agent name early so it's available even if the build fails before printBuildInfo()
        if (!env.BUILD_NODE) {
            env.BUILD_NODE = env.NODE_NAME
        }

        // Detect @N workspace suffix — Jenkins created a secondary workspace
        // (e.g. customWorkspace colliding with default, or concurrent builds)
        def wsPath = env.WORKSPACE ?: ''
        if (wsPath ==~ /.*@\d+$/) {
            error "Workspace '${wsPath}' contains '@' suffix — Jenkins created a duplicate workspace. " +
                  "This causes Plastic SCM workspace conflicts and wasted disk space. " +
                  "The build cannot continue in a secondary workspace."
        }

        // Add shields badges immediately — before any shell commands
        if (config.buildType) {
            env.BUILD_TYPE = config.buildType
            common.addBuildTypeBadge(config.buildType)
        }

        // Resolve branch/changeset from Plastic SCM server before checkout
        if (config.branch && env.PLASTIC_REPSPEC) {
            try {
                def sel = common.parseSelector(config.branch)
                def resolved = platform.resolveTargetChangeset(sel.branch, sel.changeset)
                if (resolved.branch) env.PLASTICSCM_BRANCH = resolved.branch
                if (resolved.changeset) {
                    env.PLASTICSCM_CHANGESET_ID = resolved.changeset
                    common.addBranchBadge()
                }
                common.updateBranchDescription()
            } catch (Exception ex) {
                echo "[DEBUG] Could not resolve target changeset early: ${ex.message}"
            }
        }

        // Non-critical: library changeset for Slack, knownErrors — after badges are visible
        try {
            def libsPath = "${env.WORKSPACE}@libs"
            def statusCmd = cachedIsWindows
                ? """@if exist "${libsPath}" (cd /d "${libsPath}" && cm status --header --machinereadable) else (echo NO_LIBS)"""
                : """[ -d '${libsPath}' ] && cd '${libsPath}' && cm status --header --machinereadable || echo NO_LIBS"""
            def statusOut = cachedIsWindows
                ? bat(script: statusCmd, returnStdout: true).trim()
                : sh(script: statusCmd, returnStdout: true).trim()
            if (!statusOut.contains('NO_LIBS')) {
                def csId = statusOut.split('\\s+')[1]
                if (csId) env.BUILDSCRIPT_CHANGESET = "cs${csId}"
            }
        } catch (Exception ex) {
            echo "[DEBUG] Could not get library changeset: ${ex.message}"
        }
        common.knownErrors = evaluate(libraryResource('groovy/knownErrors.groovy'))
    } catch (org.jenkinsci.plugins.workflow.steps.MissingContextVariableException e) {
        // isUnix() requires node context - let ensureInitialized() handle fallback
        throw e
    } catch (Exception e) {
        def sw = new StringWriter()
        e.printStackTrace(new PrintWriter(sw))
        echo "[ERROR] Stack trace:\n${sw.toString()}"
        error "[ERROR] Failed to initialize build utilities: ${e.message}"
    }
    return this
}

def copyPipelineScripts() {
    def unityEditorPath = "${env.WORKSPACE}/plastic/${env.UNITY_PROJECT_NAME}/Assets/Editor/Pipeline"

    // Copy base scripts + only the platform-specific script needed for this build
    // Avoids compilation errors from scripts referencing unavailable platform APIs
    def platformScripts = [
        'Android': 'PipelineGooglePlay.cs',
        'Amazon': 'PipelineAmazon.cs',
        'iOS': 'PipelineApple.cs',
        'StandaloneWindows64': 'PipelineSteam.cs',
        'StandaloneLinux64': 'PipelineSteam.cs',
        'StandaloneOSX': 'PipelineSteamMac.cs',
        'Switch': 'PipelineSwitch.cs'
    ]
    def csFiles = ['Pipeline.cs', 'PipelineArtifactCopy.cs']
    def platformScript = platformScripts[env.PLATFORM]
    if (platformScript) {
        csFiles << platformScript
    }

    // Pre-load all resources on controller, then write to agent
    // (writeFile auto-creates parent dirs, so no separate mkdir needed)
    def loaded = [:]
    csFiles.each { fileName ->
        try {
            loaded[fileName] = libraryResource("Editor/Pipeline/${fileName}")
        } catch (Exception e) {
            // Skip files not in library resources
        }
    }
    loaded.each { fileName, content ->
        writeFile file: "${unityEditorPath}/${fileName}", text: content
    }
    echo "[Setup] Copied ${loaded.keySet().join(', ')} to Editor/Pipeline/"
}

// ============================================================================
// DELEGATE TO COMMON
// ============================================================================

def parseSelector(String input) { common.parseSelector(input) }
def validateRequiredEnvVars(List<String> required) { common.validateRequiredEnvVars(required) }
def failWithKnownError(String badgeLabel, String explanation, String fix = null) { common.failWithKnownError(badgeLabel, explanation, fix) }
def printBuildInfo(String buildType, String branch) { common.printBuildInfo(buildType, branch) }
def setupBuildPaths(Map config = [:]) { common.setupBuildPaths(config) }
def addShieldsBadge(String id, message, color, link = null, String logo = null) { common.addShieldsBadge(id, message, color, link, logo) }
def addShieldsDoubleBadge(String id, label, message, labelColor = null, messageColor, labelLink = null, messageLink = null, String logo = null) { common.addShieldsDoubleBadge(id, label, message, labelColor, messageColor, labelLink, messageLink, logo) }
def addPlatformBadge(String color = null) { common.addPlatformBadge(color) }
def addBuildTypeBadge(String buildType) { common.addBuildTypeBadge(buildType) }
def addBranchBadge() { common.addBranchBadge() }
def addBuildBadges(String buildType) { common.addBuildBadges(buildType) }
def addFailureBadge(String stageName) { common.addFailureBadge(stageName) }
def updateBadgesForResult(String result = null) { common.updateBadgesForResult(result) }
def preflightJenkinsPermissions() { common.preflightJenkinsPermissions() }
def addSidebarLink(String url, String title, String iconUrl) { common.addSidebarLink(url, title, iconUrl) }
def addGoogleDriveLinks(String folderLink, String fileLink, String fileType) { common.addGoogleDriveLinks(folderLink, fileLink, fileType) }
def captureBuildUser() { common.captureBuildUser() }
def printScmInfo() { common.printScmInfo() }
def calculateBuildVersion(int versionCodeBase) { common.calculateBuildVersion(versionCodeBase) }

def startup(String buildType, String paramsBranch) {
    platform.configureGitAuth()
    common.printBuildInfo(buildType, paramsBranch)
    common.captureBuildUser()
    common.printScmInfo()
    common.resolveCacheServer()
    env.CHANGE_HISTORY = platform.getPlasticChangeHistory(env.PLASTICSCM_BRANCH, 3, env.PLASTICSCM_CHANGESET_ID)
    common.addChangeHistorySummary(env.CHANGE_HISTORY)
}

def getSlackMention(String userName, String userEmail) { common.getSlackMention(userName, userEmail) }
def sendSlackBuildNotification(Map config) { common.sendSlackBuildNotification(config) }
def getFailedNodeId(String stageName) { common.getFailedNodeId(stageName) }

// ============================================================================
// DELEGATE TO PLATFORM
// ============================================================================

def configureGitAuth() { platform.configureGitAuth() }
def cleanupGitAuth() { platform.cleanupGitAuth() }
def preflightWinget() { platform.preflightWinget() }
def preflightNetwork() { platform.preflightNetwork() }
def preflightGitHubToken() { platform.preflightGitHubToken() }
def preflightRclone() { platform.preflightRclone() }
def preflightPlasticSCM() { platform.preflightPlasticSCM() }
def preflightFastlane() { platform.preflightFastlane() }
def preflightJava() { platform.preflightJava() }
def preflightNodeJS() { platform.preflightNodeJS() }
def preflightPython() { platform.preflightPython() }
def preflightCocoaPods() { platform.preflightCocoaPods() }
def preflightRuby() { platform.preflightRuby() }
def preflightVisualStudio() { platform.preflightVisualStudio() }

// Prerequisite detection and installation
def checkWinget(boolean autoInstall = false) { platform.checkWinget(autoInstall) }
def installWinget() { platform.installWinget() }
def checkGit(boolean autoInstall = false) { platform.checkGit(autoInstall) }
def installGit() { platform.installGit() }
def checkNodeJS(boolean autoInstall = false) { platform.checkNodeJS(autoInstall) }
def installNodeJS() { platform.installNodeJS() }
def checkUnityHub(boolean autoInstall = false) { platform.checkUnityHub(autoInstall) }
def checkUnity(String version, boolean autoInstall = false, List modules = []) { platform.checkUnity(version, autoInstall, modules) }
def installUnity(String version, List modules = []) { platform.installUnity(version, modules) }
def getPlaybackEnginesPath(String version) { platform.getPlaybackEnginesPath(version) }
def checkUnityModules(String version, List modules, boolean autoInstall = false) { platform.checkUnityModules(version, modules, autoInstall) }
def installUnityModules(String version, List modules) { platform.installUnityModules(version, modules) }
def acceptAndroidSdkLicenses() { platform.acceptAndroidSdkLicenses() }
def checkRuby(boolean autoInstall = false) { platform.checkRuby(autoInstall) }
def checkFastlane(boolean autoInstall = false) { platform.checkFastlane(autoInstall) }
def checkPlasticSCM(boolean autoInstall = false) { platform.checkPlasticSCM(autoInstall) }
def checkFirebaseCLI(boolean autoInstall = false) { platform.checkFirebaseCLI(autoInstall) }

// Downloadable tools (auto-install to user home directory)
def getToolsDir() { platform.getToolsDir() }
def checkRclone(boolean autoInstall = false) { platform.checkRclone(autoInstall) }
def installRclone() { platform.installRclone() }
def checkSteamCMD(boolean autoInstall = false) { platform.checkSteamCMD(autoInstall) }
def installSteamCMD() { platform.installSteamCMD() }
def checkUnityDataTool(boolean autoInstall = false) { platform.checkUnityDataTool(autoInstall) }
def installUnityDataTool() { platform.installUnityDataTool() }

def shouldSkipPreflight() { platform.shouldSkipPreflight() }
def markPreflightPassed() { platform.markPreflightPassed() }
def preflightSteamCMD() { platform.preflightSteamCMD() }
def preflightSteamStaging() { platform.preflightSteamStaging() }
def logBuildOutputs(String buildPath) { platform.logBuildOutputs(buildPath) }
def capTextures(Map config = [:]) { platform.capTextures(config) }
def restoreTextures(Map config = [:]) { platform.restoreTextures(config) }
def setupSteamStaging(String sourcePath) { platform.setupSteamStaging(sourcePath) }
def cleanupSteamStaging() { platform.cleanupSteamStaging() }

/**
 * Delete the @libs, @script, @tmp, and @N workspace directories that Jenkins
 * creates alongside the main workspace for shared-library checkouts,
 * Jenkinsfile checkouts, and workspace collisions. These accumulate across
 * builds and are never cleaned up automatically.
 */
def cleanupAtWorkspaces() {
    platform.cleanupGitAuth()

    def ws = env.WORKSPACE
    if (!ws) return

    // Strip any @N suffix from WORKSPACE so we clean relative to the base path
    def baseWs = ws.replaceAll(/@\d+$/, '')

    // Skip @tmp — Jenkins uses it for the current build's temp batch/shell scripts.
    // Deleting it mid-build causes subsequent bat/sh steps to hang or fail.
    def suffixes = ['@libs', '@script']
    for (suffix in suffixes) {
        def target = "${baseWs}${suffix}"
        try {
            if (cachedIsWindows) {
                bat script: "@if exist \"${target}\" (rmdir /s /q \"${target}\" && echo [Cleanup] Removed ${suffix}) else (echo [Cleanup] ${suffix} not present)", returnStatus: true
            } else {
                sh script: "if [ -d '${target}' ]; then rm -rf '${target}' && echo '[Cleanup] Removed ${suffix}'; else echo '[Cleanup] ${suffix} not present'; fi", returnStatus: true
            }
        } catch (Exception e) {
            echo "[Cleanup] Could not remove ${suffix}: ${e.message}"
        }
    }

    // Clean up @N collision directories (e.g. @2, @3) from customWorkspace or concurrent builds
    for (int i = 2; i <= 9; i++) {
        def atDir = "${baseWs}@${i}"
        try {
            if (cachedIsWindows) {
                bat script: "@if exist \"${atDir}\" (rmdir /s /q \"${atDir}\" && echo [Cleanup] Removed @${i}) else (echo [Cleanup] @${i} not present)", returnStatus: true
            } else {
                sh script: "if [ -d '${atDir}' ]; then rm -rf '${atDir}' && echo '[Cleanup] Removed @${i}'; else echo '[Cleanup] @${i} not present'; fi", returnStatus: true
            }
        } catch (Exception e) {
            echo "[Cleanup] Could not remove @${i}: ${e.message}"
        }
    }
}
def steamUpload(Map config) { platform.steamUpload(config) }
def querySteamManifest(Map config) { platform.querySteamManifest(config) }
def seedSteamCache(Map config = [:]) { platform.seedSteamCache(config) }
def syncSteamCache(Map config = [:]) { platform.syncSteamCache(config) }
def getSteamStagingPath() { platform.getSteamStagingPath() }
def preflightUnityDataTool() { platform.preflightUnityDataTool() }
def preflightNintendoSDK() { platform.preflightNintendoSDK() }
def preflightDotNetSDK(String requiredVersion = '8.0') { platform.preflightDotNetSDK(requiredVersion) }
def preflightLongPaths() { platform.preflightLongPaths() }
def preflightFirebaseCLI() { platform.preflightFirebaseCLI() }
def preflightUnityLicense() { platform.preflightUnityLicense() }
def extractUnityVersion(String unityProjectPath) { platform.extractUnityVersion(unityProjectPath) }
def validateUnityInstallation() { platform.validateUnityInstallation() }
def validateLinuxBuildSupport() { platform.validateLinuxBuildSupport() }
def validateWindowsIl2CppSupport() { platform.validateWindowsIl2CppSupport() }
def validateMacOSBuildSupport() { platform.validateMacOSBuildSupport() }
def validateNintendoSwitchSupport() { platform.validateNintendoSwitchSupport() }
def checkCacheValidity(String unityProjectPath) { platform.checkCacheValidity(unityProjectPath) }
def saveBuildInfo() { platform.saveBuildInfo() }
def purgeWorkspace(String cleanCache) { platform.purgeWorkspace(cleanCache) }
def cleanUnityCache(String unityProjectPath, String cleanCache) { platform.cleanUnityCache(unityProjectPath, cleanCache) }
def runUnityCommand(Map config) { platform.runUnityCommand(config) }
def collectUnityErrors(List stageNames = ['Unity Prepare', 'Unity Build']) { platform.collectUnityErrors(stageNames) }
def runUnityTests(Map config) { platform.runUnityTests(config) }
def getBuildJobWorkspace(String platformSuffix) { platform.getBuildJobWorkspace(platformSuffix) }
def runUnityDataTool(Map config = [:]) { platform.runUnityDataTool(config) }
def analyzeBuildReport(Map config = [:]) { platform.analyzeBuildReport(config) }
def resolveTargetChangeset(String branch, String changeset) { platform.resolveTargetChangeset(branch, changeset) }
def getBranchFromChangeset(String changeset, String workspacePath = null) { platform.getBranchFromChangeset(changeset, workspacePath) }
def getPlasticChangeHistory(String branch, int count = 3, String maxChangeset = null) { platform.getPlasticChangeHistory(branch, count, maxChangeset) }
def cleanPlasticWorkspace(String cleanCache = null, String workspacePath = null) { platform.cleanPlasticWorkspace(cleanCache, workspacePath) }
def plasticCheckout(Map config) { platform.plasticCheckout(config) }
def convertAabToApk(Map config) { platform.convertAabToApk(config) }
def uploadToGoogleDrive(Map config) { platform.uploadToGoogleDrive(config) }
def uploadToLocalShare(Map config) { platform.uploadToLocalShare(config) }
def uploadToGCS(Map config) { platform.uploadToGCS(config) }
def amazonUpload(Map config) { platform.amazonUpload(config) }
def uploadToGooglePlay(Map config) { platform.uploadToGooglePlay(config) }
def uploadCrashlyticsSymbols(Map config) { platform.uploadCrashlyticsSymbols(config) }
def collectFilteredConsoleLog() { platform.collectFilteredConsoleLog() }
def sendUploadNotification(Map config) { ensureInitialized(); common.sendUploadNotification(config) }
def updateUploadStatus(String stage, String result) { ensureInitialized(); common.updateUploadStatus(stage, result) }
def handleBuildFailure(Map config) { ensureInitialized(); common.handleBuildFailure(config) }
def handleBuildSuccess(Map config) { ensureInitialized(); common.handleBuildSuccess(config) }
def handleBuildUnstable(Map config) { ensureInitialized(); common.handleBuildUnstable(config) }
def finalizeBuild(Map config) { ensureInitialized(); common.finalizeBuild(config) }
def runFailureAnalysis(Map config) { finalizeBuild(config) }  // backwards compat
def handleBuildAborted(Map config) { ensureInitialized(); common.handleBuildAborted(config) }
def setUnstable(String reason) { common.setUnstable(reason) }
def addBuildWarning(String warning) { common.addBuildWarning(warning) }

// Ensure initialized for handlers (fallback if init() wasn't called)
private def ensureInitialized() {
    if (platform != null) return

    try {
        init()
    } catch (Exception e) {
        // init() may fail if called outside node context (isUnix() unavailable)
        echo "[WARN] Could not initialize build utilities: ${e.message}"
    }

    // Load common even if platform init failed - needed for Slack notifications
    if (common == null) {
        try {
            common = evaluate(libraryResource('groovy/common.groovy'))
        } catch (Exception e) {
            echo "[WARN] Could not load common utilities: ${e.message}"
        }
    }

    // Wire platform and known errors into common if available (platform may be null if init failed)
    if (common != null) {
        if (platform != null) common.platformModule = platform
        if (common.knownErrors == null) {
            try { common.knownErrors = evaluate(libraryResource('groovy/knownErrors.groovy')) } catch (Exception e) { }
        }
    }
}

// macOS-specific (will error on Windows if called)
def preflightXcode() { platform.preflightXcode() }
def preflightKeychain(String keychainPassword) { platform.preflightKeychain(keychainPassword) }
def unlockKeychain(String keychainPassword) { platform.unlockKeychain(keychainPassword) }
def runCocoaPods(String xcodePath) { platform.runCocoaPods(xcodePath) }
def archiveXcodeProject(Map config) { platform.archiveXcodeProject(config) }
def exportXcodeArchive(Map config) { platform.exportXcodeArchive(config) }
def generateExportOptionsPlist(Map config) { platform.generateExportOptionsPlist(config) }
def uploadToTestFlight(Map config) { platform.uploadToTestFlight(config) }
