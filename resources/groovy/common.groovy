// ============================================================================
// COMMON UTILITIES - Platform-agnostic functions
// ============================================================================

def platformModule = null   // Set by buildUtils after platform is loaded; used by build handlers
def knownErrors = null      // Set by buildUtils; pattern matcher for known build failures

// Set build to unstable and store the reason for Slack notifications
def setUnstable(String reason) {
    if (!env.UNSTABLE_REASONS) {
        env.UNSTABLE_REASONS = reason
    } else {
        env.UNSTABLE_REASONS = "${env.UNSTABLE_REASONS}\n${reason}"
    }
    unstable(message: reason)
}

/**
 * Fail the build with a known error — skips AI analysis and sets a custom badge.
 * Use for early failures where the cause is obvious (workspace conflict, missing env vars, tool not found).
 */
def failWithKnownError(String badgeLabel, String explanation, String fix = null) {
    def stage = env.CURRENT_STAGE ?: 'Validate'
    def errors = [[severity: 'ERROR', stage: stage, message: explanation]]
    if (fix) errors[0].fix = fix
    def analysis = [explanation: explanation, errors: errors, knownError: true]
    env.ERROR_ANALYSIS = new groovy.json.JsonBuilder(analysis).toString()
    env.SKIP_AI_ANALYSIS = 'true'
    error(explanation)
}

/**
 * Validate required environment variables — fails with known error if any are missing.
 * Use in Validate stage instead of inline checks in each jenkinsfile.
 */
def validateRequiredEnvVars(List<String> required) {
    def missing = required.findAll { !env[it]?.trim() }
    if (missing) {
        failWithKnownError(
            'Missing Config',
            "Missing required environment variables: ${missing.join(', ')}",
            "Set in: Job > Configure > Environment Variables"
        )
    }
}


def printBuildInfo(String buildType, String branch) {
    env.BUILD_NODE = env.NODE_NAME  // Capture while inside node{} - NODE_NAME may be lost in post blocks
    env.BUILD_TYPE = buildType
    echo "Build Type: ${buildType} | Branch: ${branch} | Node: ${env.NODE_NAME} | Build #${env.BUILD_NUMBER}"

    // Notify dashboard that build has started
    notifyDashboard('STARTED', [buildType: buildType, branch: branch])
}

def setupBuildPaths(Map config = [:]) {
    def projectFolder = config.projectFolder ?: 'UnityProject'

    env.UNITY_PROJECT = config.unityProject ?: "${env.WORKSPACE}/plastic/${projectFolder}"
    env.BUILD_PATH = config.buildPath ?: "${env.WORKSPACE}/build"
    env.ARTIFACT_PATH = config.artifactPath ?: "${env.WORKSPACE}/artifacts"

    if (config.platform == 'iOS') {
        env.XCODE_BASE_PATH = config.xcodePath ?: "${env.WORKSPACE}/xcode"
    }

    def paths = "Unity Project: ${env.UNITY_PROJECT} | Build: ${env.BUILD_PATH} | Artifacts: ${env.ARTIFACT_PATH}"
    if (env.XCODE_BASE_PATH) paths += " | Xcode: ${env.XCODE_BASE_PATH}"
    echo paths
}

/**
 * Extracts the Unity Accelerator cache server host from JENKINS_URL.
 * The Accelerator runs on the Jenkins master on port 10080.
 * Stores the result in env.CACHE_SERVER_ENDPOINT for use by runUnityCommand().
 */
def resolveCacheServer() {
    if (env.CACHE_SERVER_ENDPOINT) return // Already resolved

    env.CACHE_SERVER_ENDPOINT = 'odd-jenkins:10080'
    echo "[INFO] Cache server: ${env.CACHE_SERVER_ENDPOINT}"
}

// ============================================================================
// BADGE FUNCTIONS
// ============================================================================

// Pre-encoded base64 icon files in resources/icons/ for shields.io logo= parameter
// Generated from colorful 48x48 PNGs (icons8/dashboard-icons) via: base64 -i icon.png -o icon.b64
def getIconDataUri(String platform) {
    def iconFiles = [
        Android:             'icons/googleplay.b64',
        iOS:                 'icons/apple.b64',
        Amazon:              'icons/amazon.b64',
        StandaloneWindows64: 'icons/windows.b64',
        StandaloneLinux64:   'icons/linux.b64',
        StandaloneOSX:       'icons/apple.b64',
        Switch:              'icons/switch.b64',
    ]
    def iconFile = iconFiles[platform]
    if (!iconFile) return null
    try {
        def b64 = libraryResource(iconFile).trim()
        return "data:image/png;base64,${b64}"
    } catch (Exception e) {
        echo "[DEBUG] Could not load icon ${iconFile}: ${e.message}"
        return null
    }
}

def addShieldsBadge(String id, message, color, link = null, String logo = null) {
    def encodedMessage = message.replaceAll(' ', '_').replaceAll('/', '%2F').replaceAll('-', '--')
    def badgeUrl = "https://img.shields.io/badge/${encodedMessage}-${color}?style=plastic"
    if (logo) badgeUrl += "&logo=${java.net.URLEncoder.encode(logo, 'UTF-8')}"
    def badgeHtml = link ? "<a href='${link}'><img src='${badgeUrl}'/></a>" : "<img src='${badgeUrl}'/>"
    removeBadges(id: id)
    addBadge(text: badgeHtml, id: id)
}

def addShieldsDoubleBadge(String id, label, message, labelColor = null, messageColor, labelLink = null, messageLink = null, String logo = null) {
    def encodedLabel = label.replaceAll(' ', '_').replaceAll('/', '%2F').replaceAll('-', '--')
    def encodedMessage = message.replaceAll(' ', '_').replaceAll('/', '%2F').replaceAll('-', '--')
    def badgeUrl = "https://img.shields.io/badge/${encodedLabel}-${encodedMessage}-${messageColor}?style=plastic"
    if (labelColor) badgeUrl += "&labelColor=${labelColor}"
    if (logo) badgeUrl += "&logo=${java.net.URLEncoder.encode(logo, 'UTF-8')}"
    def link = messageLink ?: labelLink
    def badgeHtml = link ? "<a href='${link}'><img src='${badgeUrl}'/></a>" : "<img src='${badgeUrl}'/>"
    removeBadges(id: id)
    addBadge(text: badgeHtml, id: id)
}

private String resultBadgeColor(String result = null) {
    def status = result ?: currentBuild.result ?: currentBuild.currentResult ?: 'SUCCESS'
    return [SUCCESS: 'brightgreen', UNSTABLE: 'yellow', FAILURE: 'red', ABORTED: 'lightgrey'][status] ?: 'blue'
}

def addPlatformBadge(String color = null) {
    def platform = env.PLATFORM
    def logo = getIconDataUri(platform)
    def label = [
        Android: 'Google Play', iOS: 'App Store', Amazon: 'Amazon',
        StandaloneWindows64: 'Windows', StandaloneLinux64: 'Linux',
        StandaloneOSX: 'macOS', Switch: 'Switch'
    ][platform] ?: platform ?: 'Build'
    addShieldsBadge('platform', label, color ?: 'blue', null, logo)
}

def addBuildTypeBadge(String buildType, String color = null) {
    addShieldsBadge('env', buildType, color ?: 'blue')
}

def addBranchBadge(String color = null) {
    addShieldsDoubleBadge('branch', "${env.PLASTICSCM_BRANCH}", "cs${env.PLASTICSCM_CHANGESET_ID}", '555555', color ?: 'blue')
}

def addBuildBadges(String buildType) {
    addPlatformBadge()
    addBuildTypeBadge(buildType)
    addBranchBadge()
}

def addFailureBadge(String stageName) {
    addShieldsDoubleBadge('failure', 'failed', stageName, 'red', 'red')
}

/**
 * Re-color all badges to reflect the final build result.
 * Called from finalizeBuild() so the build list shows result at a glance.
 */
def updateBadgesForResult(String result = null) {
    def status = result ?: currentBuild.result ?: currentBuild.currentResult ?: 'SUCCESS'
    def color = resultBadgeColor(status)
    if (env.BUILD_TYPE) addBuildTypeBadge(env.BUILD_TYPE, color)
    if (env.PLASTICSCM_CHANGESET_ID) addBranchBadge(color)
    if (status == 'FAILURE' && env.FAILED_STAGE) addFailureBadge(env.FAILED_STAGE)
}

def addSidebarLink(String url, String title, String iconUrl) {
    if (!url) { echo "No URL provided for sidebar link: ${title}"; return }
    try {
        def linkActionClass = this.class.classLoader.loadClass("hudson.plugins.sidebar_link.LinkAction")
        def action = linkActionClass.newInstance(url, title, iconUrl)
        currentBuild.rawBuild.getActions().add(action)
        echo "${title}: ${url}"
    } catch (Exception e) {
        echo "Failed to add sidebar link '${title}': ${e.message}"
    }
}

def addGoogleDriveLinks(String folderLink, String fileLink, String fileType, String fileIconUrl = null) {
    addSidebarLink(folderLink, 'Google Drive Folder', 'https://cdn.jsdelivr.net/gh/homarr-labs/dashboard-icons/png/google-drive.png')
    def icon = fileIconUrl ?: 'https://img.icons8.com/fluency/48/android-os.png'
    addSidebarLink(fileLink, "Download ${fileType}", icon)
}

// ============================================================================
// PREFLIGHT - JENKINS SANDBOX PERMISSIONS
// ============================================================================

/**
 * Verify all Jenkins sandbox permissions required by the pipeline.
 * Call this in preflight to fail fast if an admin needs to approve signatures.
 */
def preflightJenkinsPermissions() {
    // All permission testing is done in @NonCPS to avoid CPS serialization of
    // non-serializable Jenkins objects (CpsFlowExecution, Jenkins, Computer, etc.).
    // This function runs inside a parallel block — CPS checkpoints from other
    // parallel branches would try to serialize THIS branch's local variables.
    // @NonCPS makes the method atomic from CPS's perspective: no intermediate
    // serialization points, so non-serializable locals are never persisted.
    def result = _testAllPermissions(currentBuild)

    if (result.failures) {
        def msg = "[ERROR] Jenkins sandbox permissions not approved (${result.passed} passed, ${result.failures.size()} failed):\n"
        msg += result.failures.collect { "  - ${it}" }.join("\n")
        msg += "\n\nFix: Go to Manage Jenkins > In-process Script Approval and approve the pending signatures."
        error msg
    }

    echo "[OK] All ${result.passed} Jenkins sandbox permissions verified"
}

@com.cloudbees.groovy.cps.NonCPS
private def _testAllPermissions(currentBuildWrapper) {
    def failures = []
    def passed = 0

    // Helper: each permission gets its own try/catch so a sandbox rejection
    // for one method doesn't prevent subsequent methods from being tested.
    // This ensures ALL pending signatures are queued at once in Jenkins'
    // Script Approval page, instead of one-at-a-time.
    def testPermission = { String name, Closure c ->
        try {
            c()
            passed++
        } catch (Exception e) {
            // Only count actual sandbox rejections as permission failures.
            // Other exceptions (NullPointerException, ClassNotFoundException, etc.) are
            // expected cascading failures when prior permissions were rejected, or runtime
            // issues unrelated to sandbox approval — reporting them as "permissions not
            // approved" hides the real error.
            def isSandboxRejection = e.getClass().getName().contains('RejectedAccessException') ||
                                      e.message?.contains('Scripts not permitted to use')
            if (isSandboxRejection) {
                failures << "${name}: ${e.message}"
            }
        }
    }

    // --- RunWrapper.getRawBuild & WorkflowRun.getExecution ---
    def rawBuild = null
    def execution = null
    testPermission('currentBuild.rawBuild') {
        rawBuild = currentBuildWrapper.rawBuild
    }
    testPermission('rawBuild.getExecution') {
        if (rawBuild) execution = rawBuild.getExecution()
    }

    // --- DepthFirstScanner + allNodes ---
    def scanner = null
    def flowNodes = null
    testPermission('DepthFirstScanner.new') {
        scanner = new org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner()
    }
    testPermission('DepthFirstScanner.allNodes') {
        if (scanner && execution) flowNodes = scanner.allNodes(execution)
    }

    // --- FlowNode methods ---
    def firstNode = null
    if (flowNodes) {
        for (def node : flowNodes) { firstNode = node; break }
    }
    testPermission('FlowNode.getId') {
        if (firstNode) firstNode.getId()
    }
    testPermission('FlowNode.getDisplayName') {
        if (firstNode) firstNode.getDisplayName()
    }
    testPermission('FlowNode.getParents') {
        if (firstNode) firstNode.getParents()
    }
    testPermission('FlowNode.getAction(LabelAction)') {
        if (firstNode) firstNode.getAction(org.jenkinsci.plugins.workflow.actions.LabelAction)
    }

    // --- LogAction.getLogText & AnnotatedLargeText.writeLogTo ---
    testPermission('LogAction.getLogText/writeLogTo') {
        if (execution) {
            def logNodes = new org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner().allNodes(execution)
            for (def node : logNodes) {
                def logAction = node.getAction(org.jenkinsci.plugins.workflow.actions.LogAction.class)
                if (logAction) {
                    def logText = logAction.getLogText()
                    def sw = new java.io.StringWriter()
                    logText.writeLogTo(0, sw)
                    break
                }
            }
        }
    }

    // --- TagsAction.getTagValue (for detecting skipped stages) ---
    testPermission('TagsAction.getTagValue') {
        if (execution) {
            def tagNodes = new org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner().allNodes(execution)
            for (def node : tagNodes) {
                def tagsAction = node.getAction(org.jenkinsci.plugins.workflow.actions.TagsAction.class)
                if (tagsAction) {
                    tagsAction.getTagValue('STAGE_STATUS')
                    break
                }
            }
        }
    }

    // --- ThreadNameAction.getThreadName ---
    testPermission('ThreadNameAction.getThreadName') {
        if (execution) {
            def threadNodes = new org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner().allNodes(execution)
            for (def node : threadNodes) {
                def threadAction = node.getAction(org.jenkinsci.plugins.workflow.actions.ThreadNameAction.class)
                if (threadAction) {
                    threadAction.getThreadName()
                    break
                }
            }
        }
    }

    // --- FlowExecution methods ---
    testPermission('FlowExecution.getCurrentHeads') {
        if (execution) execution.getCurrentHeads()
    }
    testPermission('FlowExecution.getCauseOfFailure') {
        if (execution) execution.getCauseOfFailure()
    }

    // --- Build log access ---
    testPermission('rawBuild.getLog') {
        if (rawBuild) rawBuild.getLog(1)
    }

    // --- Build causes ---
    testPermission('getBuildCauses') {
        currentBuildWrapper.getBuildCauses('hudson.model.Cause$UserIdCause')
    }

    // --- User lookup + email (for Slack) ---
    testPermission('User.getById') {
        hudson.model.User.getById("_preflight_check_", false)
    }
    testPermission('User.getProperty') {
        def causes = currentBuildWrapper.getBuildCauses('hudson.model.Cause$UserIdCause')
        if (causes && causes[0]?.userId) {
            def user = hudson.model.User.getById(causes[0].userId, false)
            if (user) user.getProperty(hudson.tasks.Mailer.UserProperty)
        }
    }
    testPermission('Mailer.UserProperty.getAddress') {
        def causes = currentBuildWrapper.getBuildCauses('hudson.model.Cause$UserIdCause')
        if (causes && causes[0]?.userId) {
            def user = hudson.model.User.getById(causes[0].userId, false)
            if (user) {
                def emailProp = user.getProperty(hudson.tasks.Mailer.UserProperty)
                emailProp?.getAddress()
            }
        }
    }

    // --- Sidebar link plugin (classLoader + loadClass + getActions + add) ---
    testPermission('classLoader.loadClass') {
        this.class.classLoader.loadClass("hudson.plugins.sidebar_link.LinkAction")
    }
    testPermission('rawBuild.getActions') {
        if (rawBuild) rawBuild.getActions()
    }
    testPermission('LinkAction.newInstance') {
        try {
            def clazz = this.class.classLoader.loadClass("hudson.plugins.sidebar_link.LinkAction")
            clazz.newInstance("http://test", "test", "")
        } catch (ClassNotFoundException e) {
            // Plugin not installed, skip
        }
    }

    // --- Jenkins.get + Label/Node/Computer/Executor API (for pickNode, isJobDisabled) ---
    def jenkins = null
    testPermission('Jenkins.get') {
        jenkins = jenkins.model.Jenkins.get()
    }
    testPermission('Jenkins.getItemByFullName') {
        if (jenkins) jenkins.getItemByFullName('_preflight_test_')
    }
    testPermission('Job.isDisabled') {
        // AbstractItem.isDisabled — used by warmup to skip archived jobs
        if (jenkins) {
            def item = jenkins.getItemByFullName(env.JOB_NAME)
            if (item) item.isDisabled()
        }
    }
    testPermission('Item.getParent') {
        // Used by warmup to check if jobs are in the same project folder
        if (jenkins) {
            def item = jenkins.getItemByFullName(env.JOB_NAME)
            if (item) item.parent
        }
    }
    testPermission('ItemGroup.getFullName') {
        if (jenkins) {
            def item = jenkins.getItemByFullName(env.JOB_NAME)
            if (item && item.parent) item.parent.fullName
        }
    }
    def labels = null
    testPermission('Jenkins.getLabels') {
        if (jenkins) labels = jenkins.getLabels()
    }
    testPermission('Jenkins.getLabel') {
        if (jenkins) jenkins.getLabel('_preflight_test_')
    }
    def testNode = null
    def testComputer = null
    testPermission('Label.getNodes') {
        if (labels) {
            for (def lbl : labels) {
                def nodes = lbl.getNodes()
                for (def n : nodes) { testNode = n; break }
                break
            }
        }
    }
    testPermission('Node.getDisplayName') {
        if (testNode) testNode.getDisplayName()
    }
    testPermission('Node.toComputer') {
        if (testNode) testComputer = testNode.toComputer()
    }
    testPermission('Computer.isOnline') {
        if (testComputer) testComputer.isOnline()
    }
    testPermission('Computer.numExecutors') {
        if (testComputer) testComputer.numExecutors
    }
    testPermission('Computer.getExecutors') {
        if (testComputer) testComputer.getExecutors()
    }
    testPermission('Executor.isBusy') {
        if (testComputer) {
            def executors = testComputer.getExecutors()
            for (def executor : executors) {
                executor.isBusy()
                break
            }
        }
    }
    testPermission('Executor.getCurrentExecutable') {
        if (testComputer) {
            def executors = testComputer.getExecutors()
            for (def executor : executors) {
                def executable = executor.getCurrentExecutable()
                if (executable) executable.getParent()?.getFullName()
                break
            }
        }
    }

    // --- Queue API (for detecting queued builds in pickNode) ---
    testPermission('Jenkins.getQueue') {
        if (jenkins) jenkins.getQueue()
    }
    testPermission('Queue.getItems') {
        if (jenkins) {
            def queue = jenkins.getQueue()
            if (queue) queue.getItems()
        }
    }
    testPermission('Queue.Item.getAssignedLabel') {
        if (jenkins) {
            def queue = jenkins.getQueue()
            if (queue) {
                def items = queue.getItems()
                for (def item : items) {
                    item.getAssignedLabel()
                    break
                }
            }
        }
    }
    testPermission('Queue.Item.task') {
        if (jenkins) {
            def queue = jenkins.getQueue()
            if (queue) {
                def items = queue.getItems()
                for (def item : items) {
                    item.task?.getFullName()
                    break
                }
            }
        }
    }

    // --- FlowInterruptedException + causes property ---
    testPermission('FlowInterruptedException class') {
        this.class.classLoader.loadClass("org.jenkinsci.plugins.workflow.steps.FlowInterruptedException")
    }
    testPermission('FlowInterruptedException.getCauses') {
        def fie = new org.jenkinsci.plugins.workflow.steps.FlowInterruptedException(hudson.model.Result.ABORTED)
        fie.getCauses()
    }

    // --- Rejection class (input step cancellation) ---
    testPermission('Rejection class') {
        this.class.classLoader.loadClass("org.jenkinsci.plugins.workflow.support.steps.input.Rejection")
    }

    // --- AbortException class ---
    testPermission('hudson.AbortException') {
        new hudson.AbortException("preflight test")
    }

    // --- Throwable methods ---
    testPermission('Throwable.printStackTrace') {
        new Exception("preflight test").printStackTrace()
    }
    testPermission('Throwable.getClass/getName') {
        def ex = new Exception("preflight test")
        ex.getClass().getName()
    }
    testPermission('Throwable.getMessage') {
        def ex = new Exception("preflight test")
        ex.getMessage()
    }

    return [passed: passed, failures: failures]
}

// ============================================================================
// USER & SCM FUNCTIONS
// ============================================================================

def captureBuildUser() {
    def causes = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')
    env.BUILD_USER = causes ? causes[0].userName : 'System'
    env.BUILD_USER_EMAIL = ''
    if (causes && causes[0].userId) {
        try {
            def user = hudson.model.User.getById(causes[0].userId, false)
            if (user) {
                def emailProperty = user.getProperty(hudson.tasks.Mailer.UserProperty)
                env.BUILD_USER_EMAIL = emailProperty?.getAddress() ?: ''
            }
        } catch (Exception e) {
            echo "[WARN] Could not get user email: ${e.message}"
        }
    }
    echo "[INFO] Build user: ${env.BUILD_USER}, email: ${env.BUILD_USER_EMAIL ?: '(none)'}"
    return [user: env.BUILD_USER, email: env.BUILD_USER_EMAIL]
}

def updateBranchDescription() {
    if (env.PLASTICSCM_BRANCH) {
        currentBuild.description = env.PLASTICSCM_BRANCH.replaceAll('^/', '')
    }
}

def printScmInfo() {
    echo """=== PlasticSCM ===
Changeset: ${env.PLASTICSCM_CHANGESET_ID} | Branch: ${env.PLASTICSCM_BRANCH} | Author: ${env.PLASTICSCM_AUTHOR}
Built by: ${env.BUILD_USER} (${env.BUILD_USER_EMAIL ?: 'no email configured'})
=================="""

    // Update branch badge with final confirmed values (checkout may have resolved differently)
    if (env.PLASTICSCM_CHANGESET_ID) addBranchBadge()
    updateBranchDescription()
}

/**
 * Add the game project's Plastic SCM change history to the Jenkins build page.
 * This compensates for Jenkins only showing changes from the @Library SCM checkout,
 * not the manually checked-out game project.
 */
def addChangeHistorySummary(String changeHistory) {
    if (!changeHistory?.trim()) return
    try {
        def branch = env.PLASTICSCM_BRANCH?.replaceAll('^/', '') ?: ''
        def cs = env.PLASTICSCM_CHANGESET_ID ?: ''
        def author = env.PLASTICSCM_AUTHOR?.split('@')?.getAt(0) ?: ''

        def html = "<b>Plastic SCM</b> &mdash; ${branch} @ cs${cs}"
        if (author) html += " by ${author}"
        html += "<br/><ul>"
        changeHistory.split('\n').each { line ->
            // Format: "- `csNNNN` author: comment"
            def cleaned = line.replaceFirst(/^-\s*/, '')
                .replaceAll('`', '')
            html += "<li>${cleaned}</li>"
        }
        html += "</ul>"
        manager.createSummary("notepad.png").appendText(html, false)
    } catch (Exception e) {
        echo "[WARN] Failed to add change history summary: ${e.message}"
    }
}

def calculateBuildVersion(int versionCodeBase) {
    def branchSuffix = "-${env.PLASTICSCM_BRANCH.replaceAll('[/\\\\]', '')}"
    env.VERSION = "${env.BASE_VERSION}.${env.PLASTICSCM_CHANGESET_ID}.${env.BUILD_NUMBER}${branchSuffix}"

    def versionCode = env.PLASTICSCM_BRANCH == '/main' ? versionCodeBase + env.BUILD_NUMBER.toInteger() : versionCodeBase
    env.ANDROID_VERSION_CODE = versionCode.toString()

    def vcDetail = env.PLASTICSCM_BRANCH == '/main' ? "${versionCodeBase} + ${env.BUILD_NUMBER} = ${env.ANDROID_VERSION_CODE} (main)" : "${env.ANDROID_VERSION_CODE} (feature)"
    echo "Version: ${env.VERSION} | versionCode: ${vcDetail}"

    return [version: env.VERSION, versionCode: env.ANDROID_VERSION_CODE]
}

// ============================================================================
// SLACK FUNCTIONS
// ============================================================================

def getSlackMention(String userName, String userEmail) {
    if (!userEmail) return userName
    try {
        def slackUserId = slackUserIdFromEmail(email: userEmail, tokenCredentialId: 'slack-token', botUser: true)
        return slackUserId ? "<@${slackUserId}>" : userName
    } catch (Exception e) {
        echo "[WARN] Could not find Slack user for ${userEmail}: ${e.message}"
        return userName
    }
}

def sendSlackMessage(Map config) {
    def channel = config.channel ?: env.SLACK_CHANNEL ?: '#builds'
    def message = config.message

    try {
        slackSend(
            channel: channel,
            tokenCredentialId: 'slack-token',
            message: message,
            botUser: true
        )
    } catch (Exception e) {
        echo "[WARN] Failed to send Slack message: ${e.message}"
    }
}

def notifyLocalCopyReady(String fileUrl) {
    try {
        if (!env.BUILD_USER_EMAIL) return
        def slackUserId = slackUserIdFromEmail(email: env.BUILD_USER_EMAIL, tokenCredentialId: 'slack-token', botUser: true)
        if (!slackUserId) return
        sendSlackMessage(
            channel: slackUserId,
            message: ":open_file_folder: Local copy ready for <${env.BUILD_URL}|${env.JOB_NAME} #${env.BUILD_NUMBER}>:\n<file:${env.LOCAL_BUILD_PATH.replace('\\', '/')}|${env.LOCAL_BUILD_PATH}>"
        )
    } catch (Exception e) {
        echo "[WARN] Failed to DM local copy notification: ${e.message}"
    }
}

def sendSlackBuildNotification(Map config) {
    def channel = config.channel ?: env.SLACK_CHANNEL ?: '#builds'
    def buildType = config.buildType
    def status = config.status
    def branch = config.branch
    def changesetId = config.changesetId
    def version = config.version
    def errorAnalysis = config.errorAnalysis ?: ""
    def buildUser = config.buildUser
    def buildUserEmail = config.buildUserEmail
    def appIcon = config.appIcon
    def platform = config.platform ?: env.PLATFORM
    def changeHistory = config.changeHistory ?: env.CHANGE_HISTORY
    def uploadStatus = config.uploadStatus ?: []
    def timestamp = config.timestamp              // If set, update existing message instead of sending new one

    def colorMap = [success: 'good', failure: 'danger', aborted: 'warning', unstable: 'warning']
    def emojiMap = [success: ':white_check_mark:', failure: ':x:', aborted: ':no_entry:', unstable: ':warning:']
    def statusTextMap = [success: 'succeeded', failure: 'failed', aborted: 'aborted', unstable: 'unstable']
    def storeIcons = [
        Android: 'https://img.icons8.com/fluency/48/android-os.png',
        iOS: 'https://img.icons8.com/ios-filled/50/FFFFFF/mac-os.png',
        Amazon: 'https://img.icons8.com/color/48/amazon.png',
        StandaloneWindows64: 'https://cdn.jsdelivr.net/gh/homarr-labs/dashboard-icons/png/steam.png',
        StandaloneLinux64: 'https://cdn.jsdelivr.net/gh/homarr-labs/dashboard-icons/png/steam.png',
        Switch: 'https://cdn.jsdelivr.net/gh/homarr-labs/dashboard-icons/png/nintendo-switch.png'
    ]
    // OS icons for platforms where store icon differs from OS (e.g. Steam builds)
    def osIcons = [
        StandaloneWindows64: 'https://img.icons8.com/fluency/48/windows-11.png',
        StandaloneLinux64: 'https://cdn.jsdelivr.net/gh/homarr-labs/dashboard-icons/png/linux.png'
    ]
    def storeIcon = storeIcons[platform] ?: storeIcons['Android']
    def osIcon = osIcons[platform]
    def buildTypeIcon = [Debug: ':wrench:', Alpha: ':test_tube:', Release: ':rocket:', EditorTest: ':test_tube:', PlayTest: ':test_tube:'][buildType] ?: ''
    def color = colorMap[status] ?: 'warning'
    def emoji = emojiMap[status] ?: ''
    def statusText = statusTextMap[status] ?: ''

    def message = ""
    if (status == 'failure' && env.FAILED_STAGE) {
        if (env.FAILED_NODE_ID) {
            message += "Failed Stage: <${env.BUILD_URL}pipeline-overview/?selected-node=${env.FAILED_NODE_ID}|*${env.FAILED_STAGE}*>\n"
        } else {
            message += "Failed Stage: *${env.FAILED_STAGE}*\n"
        }
    }
    message += "Branch: `${branch}` | Changeset: `${changesetId}`"
    if (env.PLASTICSCM_AUTHOR) message += " by ${env.PLASTICSCM_AUTHOR.split('@')[0]}"
    message += "\n"
    if (buildUser) message += "Built by: ${getSlackMention(buildUser, buildUserEmail)}"
    if (env.BUILD_NODE) message += " on `${env.BUILD_NODE}`"
    if (env.BUILDSCRIPT_CHANGESET) message += " | BuildScript: `${env.BUILDSCRIPT_CHANGESET}`"
    if (buildUser || env.BUILD_NODE) message += "\n"
    if (version && (status == 'success' || status == 'unstable')) message += "Version: `${version}`"
    if (status == 'unstable' && env.UNSTABLE_REASONS) {
        message += "\n:warning: ${env.UNSTABLE_REASONS}"
    }

    def links = "<${env.BUILD_URL}|Build> | <${env.BUILD_URL}pipeline/|Pipeline> | <${env.BUILD_URL}console|Console>"
    if (env.GDRIVE_FILE_LINK) {
        def fileType = buildType == 'Debug' ? 'APK' : 'AAB'
        if (platform == 'iOS') fileType = 'IPA'
        else if (platform == 'Switch') fileType = 'NSP'
        else if (platform == 'Amazon') fileType = 'APK'
        def sizeLabel = env.ARTIFACT_SIZE ? " (${env.ARTIFACT_SIZE})" : ''
        links += " | <${env.GDRIVE_FILE_LINK}|Download ${fileType}${sizeLabel}>"
    } else if (env.GDRIVE_FOLDER_LINK) {
        links += " | <${env.GDRIVE_FOLDER_LINK}|:file_folder: Google Drive>"
    }
    if (platform == 'Amazon' && env.AMAZON_APP_ID) {
        def latUrl = "https://developer.amazon.com/apps-and-games/console/app/${env.AMAZON_APP_ID}/live-app-testing"
        links += " | <${latUrl}|:fire: Live App Testing>"
    }
    if (env.LOCAL_BUILD_PATH) {
        def fileUrl = "file:${env.LOCAL_BUILD_PATH.replace('\\', '/')}"
        links += " | <${fileUrl}|:open_file_folder: Local>"
    }
    if (status == 'failure' || status == 'unstable') {
        def dashUrl = env.DASHBOARD_URL ?: null
        if (dashUrl) {
            links += " | <${dashUrl}/#analyzer?job=${env.JOB_NAME}&build=${env.BUILD_NUMBER}|:mag: Analyze>"
        }
    }

    // Build upload status line from env vars (set by sendUploadNotification / updateUploadStatus)
    def uploadStatusLine = buildUploadStatusLine()

    // Override header emoji if uploads are in progress
    if (env.UPLOAD_SLACK_TS && status == 'success') {
        def hasAnyPending = [env.UPLOAD_GDRIVE_STATUS, env.UPLOAD_LOCAL_STATUS, env.UPLOAD_STORE_STATUS].any { it == 'pending' }
        def hasAnyFailed = [env.UPLOAD_GDRIVE_STATUS, env.UPLOAD_LOCAL_STATUS, env.UPLOAD_STORE_STATUS].any { it == 'failed' }
        if (hasAnyPending) {
            emoji = ':hourglass_flowing_sand:'
        } else if (hasAnyFailed) {
            emoji = ':warning:'
        }
    }

    def headerLine = "${buildTypeIcon}${emoji}  <${env.BUILD_URL}|${env.JOB_NAME}> <${env.BUILD_URL}|#${env.BUILD_NUMBER}> / ${buildType}"

    def headerElements = []
    if (appIcon) {
        headerElements << [type: 'image', image_url: appIcon, alt_text: 'App Icon']
    }
    if (osIcon) {
        headerElements << [type: 'image', image_url: osIcon, alt_text: platform]
    }
    headerElements << [type: 'image', image_url: storeIcon, alt_text: platform]
    headerElements << [type: 'mrkdwn', text: headerLine]

    def blocks = [
        [type: 'context', elements: headerElements],
        [type: 'section', text: [type: 'mrkdwn', text: message]]
    ]

    if (changeHistory) {
        blocks << [type: 'context', elements: [[type: 'mrkdwn', text: "${changeHistory}"]]]
    }

    if (errorAnalysis) {
        try {
            def parsed = new groovy.json.JsonSlurper().parseText(errorAnalysis)
            def explanation = parsed.explanation ?: ''
            def errors = parsed.errors ?: []

            // Block 1: Error explanation
            if (explanation) {
                // Slack section text limit is 3000 chars - leave room for prefix
                def isKnownError = parsed.knownError ?: false
                def truncatedExplanation = explanation.take(2970)
                def header = isKnownError ? ":warning: *Known Error*" : ":warning: *Error Analysis*"
                blocks << [type: 'section', text: [type: 'mrkdwn', text: "${header}\n${truncatedExplanation}"]]
            }

            // Block 2: Raw detected errors (separate from analysis)
            if (errors) {
                def errorLines = []
                def severityOrder = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN']
                def grouped = errors.groupBy { it.severity ?: 'UNKNOWN' }
                severityOrder.each { sev ->
                    if (grouped[sev]) {
                        grouped[sev].each { err ->
                            def stagePrefix = err.stage ? "(${err.stage}) " : ""
                            errorLines << "[${sev}] ${stagePrefix}${err.message}"
                            if (err.fix) errorLines << "  FIX: ${err.fix}"
                        }
                    }
                }
                if (errorLines) {
                    def errorText = errorLines.join('\n').take(2900)
                    blocks << [type: 'divider']
                    blocks << [type: 'section', text: [type: 'mrkdwn', text: ":mag: *Detected Errors*\n```${errorText}```"]]
                }
            }
        } catch (Exception parseEx) {
            // Fallback for non-JSON analysis (e.g. old format or AI failure message)
            blocks << [type: 'section', text: [type: 'mrkdwn', text: ":warning: ${errorAnalysis}"]]
        }
    }

    // Build report warnings (oversized shaders, textures, duplicates)
    if (status == 'success' && env.BUILD_REPORT_WARNINGS) {
        def reportLines = env.BUILD_REPORT_WARNINGS.split('\n').collect { ":warning: ${it}" }.join('\n')
        def dbLink = "<${env.BUILD_URL}artifact/artifacts/assetBundles.db|assetBundles.db>"
        def reportText = reportLines.take(2800)
        blocks << [type: 'context', elements: [[type: 'mrkdwn', text: "${reportText}\n${dbLink}"]]]
    }

    if (uploadStatusLine) {
        blocks << [type: 'context', elements: [[type: 'mrkdwn', text: uploadStatusLine]]]
    }

    // Amazon LAT instructions - manual upload required (API doesn't support large APKs)
    if (platform == 'Amazon' && status == 'success' && env.AMAZON_APP_ID) {
        def apkLink = env.GDRIVE_FILE_LINK ? "<${env.GDRIVE_FILE_LINK}|Download APK>" : (env.GDRIVE_FOLDER_LINK ? "<${env.GDRIVE_FOLDER_LINK}|Google Drive>" : 'Google Drive (see links below)')
        def latSteps = ":fire: *Live App Testing - Manual Upload Required*\n" +
            "1. ${apkLink} from Google Drive\n" +
            "2. Open <https://developer.amazon.com/apps-and-games/console/app/${env.AMAZON_APP_ID}/submission/app-files|App Files> in the Amazon Developer Console\n" +
            "3. Click *Replace* on the existing APK and upload the new build\n" +
            "4. Go to <https://developer.amazon.com/apps-and-games/console/app/${env.AMAZON_APP_ID}/live-app-testing|Live App Testing> and activate the build"
        blocks << [type: 'section', text: [type: 'mrkdwn', text: latSteps]]
    }

    blocks << [type: 'context', elements: [[type: 'mrkdwn', text: links]]]

    try {
        def slackResponse
        if (timestamp) {
            slackResponse = slackSend(channel: channel, timestamp: timestamp, tokenCredentialId: 'slack-token', blocks: blocks, color: color, botUser: true)
        } else {
            slackResponse = slackSend(channel: channel, tokenCredentialId: 'slack-token', blocks: blocks, color: color, botUser: true)
        }
        return slackResponse
    } catch (Exception e) {
        echo "[ERROR] Failed to send Slack notification: ${e.message}"
        echo "[DEBUG] Channel: ${channel}, AppIcon: ${appIcon}, Platform: ${platform}"
        return null
    }
}

// ============================================================================
// BUILD WARNINGS
// ============================================================================

/**
 * Add a warning that will appear in the Slack notification.
 * Can be called from any stage to surface issues to the team.
 *
 * @param warning The warning message (e.g. "Texture: bg_4k - 16.2 MB (4096x4096 RGBA32)")
 */
def addBuildWarning(String warning) {
    def current = env.BUILD_REPORT_WARNINGS ?: ''
    env.BUILD_REPORT_WARNINGS = current ? "${current}\n${warning}" : warning
}

// ============================================================================
// INTERNAL HELPER FUNCTIONS
// ============================================================================

def getFailedNodeId(String stageName) {
    try {
        def execution = currentBuild.rawBuild.getExecution()
        for (def node : execution.getCurrentHeads()) {
            while (node != null) {
                if (node.getDisplayName() == stageName) {
                    return node.getId()
                }
                node = node.getParents()?.find { true }
            }
        }
        for (def node : new org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner().allNodes(execution)) {
            if (node.getDisplayName() == stageName) {
                return node.getId()
            }
        }
    } catch (Exception e) {
        echo "Could not get node ID: ${e.message}"
    }
    return null
}

/**
 * Extract per-stage log text from FlowNodes.
 * Returns a string with stage headers and their log content,
 * limited to the last `linesPerStage` lines per stage.
 * Returns null if FlowNode API fails (caller should fall back to getLog).
 */
/**
 * Extract per-stage logs from the pipeline execution.
 * @param linesPerStage Max lines to keep per stage
 * @param onlyStage If set, only return logs for this specific stage (e.g. the failed stage)
 */
def getPerStageLogs(int linesPerStage = 1000, String onlyStage = null) {
    try {
        def execution = currentBuild.rawBuild.getExecution()
        if (!execution) {
            echo "[getPerStageLogs] No execution found"
            return null
        }

        def allNodes = new org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner().allNodes(execution)

        // Collect log text per node, keyed by the enclosing stage name.
        def stageLogMap = [:]   // LinkedHashMap preserves insertion order
        def stageOrder = []     // Track order stages appear

        // DepthFirstScanner returns newest first - reverse for chronological order
        def nodeList = allNodes.toList().reverse()

        for (def node : nodeList) {
            def logAction = node.getAction(org.jenkinsci.plugins.workflow.actions.LogAction.class)
            if (!logAction) continue

            def stageName = findEnclosingStage(node)
            if (!stageName) stageName = '(pipeline)'

            // Skip stages we don't care about when filtering
            if (onlyStage && stageName != onlyStage) continue

            if (!stageLogMap.containsKey(stageName)) {
                stageLogMap[stageName] = []
                stageOrder << stageName
            }

            try {
                def logText = logAction.getLogText()
                def sw = new StringWriter()
                logText.writeLogTo(0, sw)
                def text = sw.toString()
                if (text?.trim()) {
                    stageLogMap[stageName].addAll(text.readLines())
                }
            } catch (Exception logEx) {
                // Individual node log extraction failed, continue
            }
        }

        if (stageLogMap.isEmpty()) {
            if (onlyStage) {
                echo "[getPerStageLogs] No logs found for stage '${onlyStage}'"
            } else {
                echo "[getPerStageLogs] No stage logs found"
            }
            return null
        }

        // Identify skipped stages (only when returning all stages)
        def skippedStages = [] as Set
        if (!onlyStage) {
            for (def node : nodeList) {
                try {
                    def tagsAction = node.getAction(org.jenkinsci.plugins.workflow.actions.TagsAction.class)
                    if (tagsAction?.getTagValue('STAGE_STATUS') == 'SKIPPED_FOR_CONDITIONAL') {
                        def name = findEnclosingStage(node)
                        if (name) skippedStages << name
                    }
                } catch (Exception e) { /* ignore */ }
            }
            if (skippedStages) {
                echo "[getPerStageLogs] Skipped stages excluded: ${skippedStages.join(', ')}"
            }
        }

        def result = new StringBuilder()
        for (def stageName : stageOrder) {
            if (skippedStages.contains(stageName)) continue
            def lines = stageLogMap[stageName]
            if (lines.isEmpty()) continue

            def trimmedLines = lines
            if (lines.size() > linesPerStage) {
                def skipped = lines.size() - linesPerStage
                trimmedLines = ["... (${skipped} lines omitted)"] + lines[-linesPerStage..-1]
            }

            result.append("=== STAGE: ${stageName} ===\n")
            result.append(trimmedLines.join('\n'))
            result.append("=== END STAGE: ${stageName} ===\n\n")
        }

        echo "[getPerStageLogs] Extracted logs from ${stageLogMap.size()} stage(s)${onlyStage ? " (filtered to '${onlyStage}')" : ''}"
        return result.toString()

    } catch (Exception e) {
        echo "[getPerStageLogs] Failed: ${e.message}"
        return null
    }
}

/**
 * Extract a stage's logs from the raw console log by parsing [Pipeline] markers.
 * More data than the flow graph API (includes Jenkins step output, bat/sh headers, etc).
 * Used as primary method when a specific stage is requested, fallback otherwise.
 * @param stageName The stage name to extract (e.g. 'Unity Build')
 * @param maxLines Max lines to return
 */
def getStageLogsFromRawLog(String stageName, int maxLines = 5000) {
    try {
        def logLines = currentBuild.rawBuild.getLog(50000)
        def stageStart = -1
        def stageEnd = -1
        def depth = 0
        def stagePattern = "[Pipeline] { (${stageName})"

        for (int i = 0; i < logLines.size(); i++) {
            def line = logLines[i]
            if (stageStart == -1) {
                if (line.contains(stagePattern)) {
                    stageStart = i
                    depth = 1
                }
            } else {
                // Track nesting depth of [Pipeline] { / }
                if (line.startsWith('[Pipeline] {')) {
                    depth++
                } else if (line.startsWith('[Pipeline] }')) {
                    depth--
                    if (depth <= 0) {
                        stageEnd = i
                        break
                    }
                }
            }
        }

        if (stageStart == -1) {
            echo "[ERROR] getStageLogsFromRawLog: Stage '${stageName}' not found in raw log — FAILED_STAGE name doesn't match any [Pipeline] { (StageName) marker. This needs fixing."
            return null
        }

        if (stageEnd == -1) stageEnd = logLines.size() - 1

        def stageLines = logLines.subList(stageStart, Math.min(stageEnd + 1, logLines.size()))
        if (stageLines.size() > maxLines) {
            def skipped = stageLines.size() - maxLines
            stageLines = stageLines.subList(stageLines.size() - maxLines, stageLines.size())
            stageLines.add(0, "... (${skipped} lines omitted)")
        }

        echo "[getStageLogsFromRawLog] Extracted ${stageLines.size()} lines for stage '${stageName}' (raw log lines ${stageStart + 1}-${stageEnd + 1})"
        return "=== STAGE: ${stageName} [FAILED] (lines ${stageStart + 1}-${stageEnd + 1}) ===\n${stageLines.join('\n')}\n=== END STAGE: ${stageName} ===\n"

    } catch (Exception e) {
        echo "[getStageLogsFromRawLog] Failed: ${e.message}"
        return null
    }
}

/**
 * Walk up parent nodes to find the enclosing stage name for a FlowNode.
 * Checks LabelAction (regular stages) and ThreadNameAction (parallel branches).
 */
def findEnclosingStage(def node) {
    def current = node
    def maxDepth = 50  // safety limit
    while (current != null && maxDepth-- > 0) {
        // LabelAction is on regular stage nodes
        def labelAction = current.getAction(org.jenkinsci.plugins.workflow.actions.LabelAction.class)
        if (labelAction) {
            def name = current.getDisplayName()
            if (name && name != 'Pipeline Start') return name
        }
        // ThreadNameAction is on parallel branch nodes (e.g. "Network", "Java")
        try {
            def threadAction = current.getAction(org.jenkinsci.plugins.workflow.actions.ThreadNameAction.class)
            if (threadAction) {
                def name = threadAction.getThreadName()
                if (name) return name
            }
        } catch (Exception ignored) {}
        current = current.getParents()?.find { true }
    }
    return null
}

// ============================================================================
// SELECTOR PARSING
// ============================================================================

/**
 * Parse a unified Plastic SCM selector string
 * Formats:
 *   /main                                      -> branch only
 *   /main/feature@repo@org@server              -> branch only (extracts /main/feature)
 *   9613                                       -> changeset only (requires branch query from PlasticSCM)
 *   9613@branch@repo@server                    -> changeset only (extracts 9613)
 *
 * Note: repSpec always comes from job's PLASTIC_REPSPEC env variable
 *
 * Returns: [changeset: String|null, branch: String|null]
 */
def parseSelector(String input) {
    if (!input?.trim()) {
        return [changeset: null, branch: '/main']
    }

    input = input.trim()

    // Check if first part is a changeset (e.g. "9613" or "9613@branch@repo@server")
    def parts = input.split('@')
    if (parts[0].isNumber()) {
        return [changeset: parts[0], branch: null]
    } else {
        // Branch: /main or /main/feature@repo@server - extract just the branch part
        def branchPart = parts[0]  // First part before any @
        def branch = branchPart.startsWith('/') ? branchPart : "/${branchPart}"
        return [changeset: null, branch: branch]
    }
}

// ============================================================================
// BUILD NOTIFICATION HELPERS
// ============================================================================

/** Returns a human-readable file size string (e.g. "12.3 MB", "1.45 GB"). Sandbox-safe. */
def formatFileSize(long bytes) {
    if (bytes >= 1073741824L) return "${Math.round(bytes / 1073741824d * 100) / 100.0d} GB"
    if (bytes >= 1048576L)    return "${Math.round(bytes / 1048576d * 10) / 10.0d} MB"
    return "${Math.round(bytes / 1024d * 10) / 10.0d} KB"
}

/**
 * Write error analysis JSON to error_analysis.txt and add a sidebar link.
 * Returns the parsed analysis object (or null on parse failure) so callers can reuse it.
 */
private def saveAnalysisFile(String errorAnalysis, String headerText) {
    def analysisFile = "${env.ARTIFACT_PATH}/error_analysis.txt"
    def analysisText = headerText
    def parsed = null
    try {
        parsed = new groovy.json.JsonSlurper().parseText(errorAnalysis)
        def analysisLabel = parsed.knownError ? 'KNOWN ERROR' : 'ERROR ANALYSIS'
        analysisText += "\n${analysisLabel}:\n${parsed.explanation}\n"
        if (parsed.errors) {
            analysisText += "\n${'=' * 50}\nDETECTED ERRORS:\n${'=' * 50}\n"
            parsed.errors.each { err ->
                def stagePrefix = err.stage ? "(${err.stage}) " : ""
                analysisText += "\n[${err.severity}] ${stagePrefix}${err.message}"
                if (err.fix) analysisText += "\n  FIX: ${err.fix}"
            }
        }
    } catch (Exception ex) {
        analysisText += "\n${errorAnalysis}"
    }
    writeFile file: analysisFile, text: analysisText
    def sidebarLabel = parsed?.knownError ? 'Known Error' : 'Error Analysis'
    def sidebarIcon = parsed?.knownError ? 'warning.png' : 'symbol-warning.png'
    addSidebarLink("${env.BUILD_URL}artifact/error_analysis.txt", sidebarLabel, sidebarIcon)
    return parsed
}

// ============================================================================
// UPLOAD PROGRESS NOTIFICATIONS
// ============================================================================

/**
 * Build the upload status line from env vars for the Slack notification.
 * Returns null if no uploads are being tracked.
 */
def buildUploadStatusLine() {
    def statusEmoji = [pending: ':hourglass_flowing_sand:', done: ':white_check_mark:', failed: ':x:']
    def parts = []

    if (env.UPLOAD_GDRIVE_STATUS) {
        def icon = statusEmoji[env.UPLOAD_GDRIVE_STATUS] ?: ':hourglass_flowing_sand:'
        def driveUrl = env.GDRIVE_FOLDER_LINK ?: env.GDRIVE_FILE_LINK
        def label = driveUrl ? "<${driveUrl}|GDrive>" : 'GDrive'
        parts << "${icon} ${label}"
    }
    if (env.UPLOAD_LOCAL_STATUS) {
        def icon = statusEmoji[env.UPLOAD_LOCAL_STATUS] ?: ':hourglass_flowing_sand:'
        def label = env.LOCAL_BUILD_PATH ? "<file:${env.LOCAL_BUILD_PATH.replace('\\', '/')}|Local>" : 'Local'
        parts << "${icon} ${label}"
    }
    if (env.UPLOAD_STORE_STATUS) {
        def icon = statusEmoji[env.UPLOAD_STORE_STATUS] ?: ':hourglass_flowing_sand:'
        def storeName = env.UPLOAD_STORE_NAME ?: 'Store'
        parts << "${icon} ${storeName}"
    }

    if (!parts) return null

    // Determine artifact type label
    def artifactType = env.UPLOAD_ARTIFACT_TYPE ?: 'Build'
    return "${artifactType}  \u2014  ${parts.join('  ')}"
}

/**
 * Send initial "uploading" notification before parallel uploads begin.
 * Shows hourglasses for each pending upload destination.
 */
/**
 * Build common Slack notification params from config + env vars.
 * Used by sendUploadNotification, updateUploadStatus, and handleBuildSuccess.
 */
private Map buildSlackParams(Map config = [:]) {
    if (!env.BUILD_USER) captureBuildUser()
    return [
        channel: config.channel ?: env.UPLOAD_SLACK_CHANNEL ?: env.SLACK_CHANNEL ?: '#builds',
        buildType: config.buildType ?: env.BUILD_TYPE ?: 'Release',
        status: 'success',
        branch: config.branch ?: env.PLASTICSCM_BRANCH ?: '-',
        changesetId: config.changesetId ?: env.PLASTICSCM_CHANGESET_ID ?: '-',
        version: config.version ?: env.VERSION,
        buildUser: env.BUILD_USER,
        buildUserEmail: env.BUILD_USER_EMAIL,
        appIcon: config.appIcon ?: env.APP_ICON,
        platform: config.platform ?: env.PLATFORM ?: 'Unknown'
    ]
}

def sendUploadNotification(Map config) {
    def params = buildSlackParams(config)
    def uploads = config.uploads ?: []

    // Determine artifact type label for display
    def platform = params.platform
    def buildType = params.buildType
    def artifactType = 'Build'
    if (platform == 'iOS') artifactType = 'IPA'
    else if (platform == 'Switch') artifactType = 'NSP'
    else if (platform == 'StandaloneWindows64' || platform == 'StandaloneLinux64') artifactType = 'Folder'
    else if (platform == 'Amazon') artifactType = 'APK'
    else if (buildType == 'Debug') artifactType = 'APK'
    else artifactType = 'AAB'
    env.UPLOAD_ARTIFACT_TYPE = artifactType

    // Set pending status for each tracked upload
    if (uploads.contains('gdrive')) env.UPLOAD_GDRIVE_STATUS = 'pending'
    if (uploads.contains('local')) env.UPLOAD_LOCAL_STATUS = 'pending'
    if (uploads.contains('store')) env.UPLOAD_STORE_STATUS = 'pending'

    // Set store display name
    def storeNames = [Android: 'Google Play', iOS: 'TestFlight', Amazon: 'Amazon',
                      StandaloneWindows64: 'Steam', StandaloneLinux64: 'Steam', Switch: 'GDrive']
    if (uploads.contains('store')) {
        env.UPLOAD_STORE_NAME = config.storeName ?: storeNames[platform] ?: 'Store'
    }

    def slackResponse = sendSlackBuildNotification(params)

    if (slackResponse) {
        env.UPLOAD_SLACK_TS = slackResponse.ts
        env.UPLOAD_SLACK_CHANNEL = slackResponse.channelId
    }
}

/**
 * Update a specific upload stage's status and refresh the Slack notification.
 * Called by upload functions on completion.
 *
 * @param stage One of: 'gdrive', 'local', 'store'
 * @param result One of: 'done', 'failed'
 */
def updateUploadStatus(String stage, String result) {
    if (stage == 'gdrive') env.UPLOAD_GDRIVE_STATUS = result
    else if (stage == 'local') env.UPLOAD_LOCAL_STATUS = result
    else if (stage == 'store') env.UPLOAD_STORE_STATUS = result

    if (!env.UPLOAD_SLACK_TS) return

    try {
        sendSlackBuildNotification(buildSlackParams() + [timestamp: env.UPLOAD_SLACK_TS])
    } catch (Exception e) {
        echo "[WARN] Failed to update upload status: ${e.message}"
    }
}

// ============================================================================
// DASHBOARD WEBHOOK - Real-time build status to Release Dashboard
// ============================================================================

/**
 * Send a generic webhook notification for build status changes.
 * Called automatically from printBuildInfo() (STARTED) and build handlers.
 * Configure target URL via WEBHOOK_URL env var (default: http://localhost:3000/api/jenkins-webhook).
 * Silently fails if endpoint is unreachable — never affects the build.
 *
 * @param status  STARTED, SUCCESS, FAILURE, UNSTABLE, ABORTED
 * @param config  Map with buildType, branch, changesetId (same as handler config)
 */
def notifyDashboard(String status, Map config = [:]) {
    try {
        def webhookUrl = env.WEBHOOK_URL ?: 'http://localhost:3000/api/jenkins-webhook'

        def payload = [
            jobName: env.JOB_NAME ?: '',
            buildNumber: env.BUILD_NUMBER?.toInteger() ?: 0,
            status: status,
            platform: env.PLATFORM ?: '',
            buildType: config.buildType ?: env.BUILD_TYPE ?: '',
            branch: (config.branch ?: env.PLASTICSCM_BRANCH ?: '').replaceAll('^/', ''),
            version: env.VERSION ?: '',
            changeset: config.changesetId ?: env.PLASTICSCM_CHANGESET_ID ?: '',
            timestamp: System.currentTimeMillis(),
            buildUrl: env.BUILD_URL ?: '',
            node: env.BUILD_NODE ?: env.NODE_NAME ?: ''
        ]

        if (status == 'STARTED') {
            def prevDuration = currentBuild.previousSuccessfulBuild?.duration
            if (prevDuration) payload.estimatedDuration = prevDuration
        } else {
            payload.duration = currentBuild.duration
            if (env.GDRIVE_FILE_LINK) payload.downloadUrl = env.GDRIVE_FILE_LINK
            if (env.ERROR_ANALYSIS) payload.errorAnalysis = env.ERROR_ANALYSIS
            if (env.FAILED_STAGE) payload.failedStage = env.FAILED_STAGE
        }

        def json = new groovy.json.JsonBuilder(payload).toString()

        def url = new URL(webhookUrl)
        def conn = url.openConnection()
        conn.setRequestMethod('POST')
        conn.setRequestProperty('Content-Type', 'application/json')
        conn.setConnectTimeout(3000)
        conn.setReadTimeout(3000)
        conn.setDoOutput(true)
        conn.getOutputStream().write(json.getBytes('UTF-8'))

        def responseCode = conn.getResponseCode()
        echo "[Webhook] ${status} -> ${responseCode}"
        conn.disconnect()
    } catch (Exception e) {
        echo "[Webhook] Skipped: ${e.message}"
    }
}

// ============================================================================
// BUILD NOTIFICATION HANDLERS
// (platform-agnostic; platform-specific helpers called via platformModule)
// ============================================================================

def handleBuildFailure(Map config) {
    notifyDashboard('FAILURE', config)

    // Capture build user if not already set
    if (!env.BUILD_USER) captureBuildUser()

    def buildType = config.buildType
    def branch = config.branch ?: env.PLASTICSCM_BRANCH
    def changeset = config.changesetId ?: env.PLASTICSCM_CHANGESET_ID ?: '-'

    env.FAILED_STAGE = env.CURRENT_STAGE ?: 'Unknown'
    env.FAILED_NODE_ID = getFailedNodeId(env.FAILED_STAGE)

    def summary = "<b>Failed in:</b> ${env.FAILED_STAGE}<br/>"
    summary += "<b>Branch:</b> ${branch} | <b>Changeset:</b> ${changeset}<br/>"
    manager.createSummary("error.png").appendText(summary, false)
}

/**
 * Save build metadata as a JSON artifact for API consumers (web dashboards, mobile apps).
 * Written to WORKSPACE/artifacts/build_info.json, archived automatically by the always block.
 */
def saveBuildMetadata(Map config) {
    def artifactDir = "${env.WORKSPACE}/artifacts"

    def metadata = [
        job: env.JOB_NAME ?: '',
        build: env.BUILD_NUMBER ?: '',
        result: currentBuild.result ?: currentBuild.currentResult ?: 'UNKNOWN',
        timestamp: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'"),
        duration: currentBuild.duration,
        url: env.BUILD_URL ?: '',

        app: env.APP_NAME ?: '',
        icon: config?.appIcon ?: env.APP_ICON ?: '',
        bundleId: env.BUNDLE_IDENTIFIER ?: '',
        version: env.VERSION ?: '',
        buildType: config?.buildType ?: env.BUILD_TYPE ?: '',
        platform: env.PLATFORM ?: '',

        branch: env.PLASTICSCM_BRANCH ?: env.BRANCH ?: '',
        changeset: env.PLASTICSCM_CHANGESET_ID ?: env.CHANGESET_ID ?: '',
        author: env.PLASTICSCM_AUTHOR ?: '',

        downloadUrl: env.GDRIVE_FILE_LINK ?: '',
        fileSize: env.ARTIFACT_SIZE ?: '',
        driveFolderUrl: env.GDRIVE_FOLDER_LINK ?: ''
    ]

    def json = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(metadata))
    writeFile file: "${artifactDir}/build_info.json", text: json
    echo "[OK] Build metadata saved to artifacts/build_info.json"
}

/**
 * Run AI failure analysis and send Slack notification - called from 'always' block.
 * For failures: runs AI analysis, extracts error cause, sends comprehensive Slack notification.
 * For unstable: runs AI analysis and updates the existing Slack message from handleBuildUnstable.
 * Skips success and aborted builds.
 */
def finalizeBuild(Map config) {
    // Save build metadata JSON for API consumers (web dashboards, etc.)
    try { saveBuildMetadata(config) } catch (Exception e) { echo "[WARN] Failed to save build metadata: ${e.message}" }

    // Re-color all shields badges to reflect final build result
    try { updateBadgesForResult() } catch (Exception e) { echo "[WARN] Failed to update badges: ${e.message}" }

    def status = currentBuild.result?.toLowerCase() ?: 'failure'

    // Skip on success or abort - those have their own handlers
    if (status == 'success' || status == 'aborted') return

    // Mark any remaining pending uploads as failed
    if (env.UPLOAD_GDRIVE_STATUS == 'pending') env.UPLOAD_GDRIVE_STATUS = 'failed'
    if (env.UPLOAD_LOCAL_STATUS == 'pending') env.UPLOAD_LOCAL_STATUS = 'failed'
    if (env.UPLOAD_STORE_STATUS == 'pending') env.UPLOAD_STORE_STATUS = 'failed'

    // For unstable builds, only analyze if there were actual errors
    if (status == 'unstable' && !env.UNSTABLE_REASONS) return

    // Safe defaults - every field has a fallback so nothing NPEs
    def buildType = config?.buildType ?: env.BUILD_TYPE ?: 'Unknown'
    def branch = config?.branch ?: env.PLASTICSCM_BRANCH ?: '-'
    def changeset = config?.changesetId ?: env.PLASTICSCM_CHANGESET_ID ?: '-'
    def platform = config?.platform ?: env.PLATFORM ?: 'Unknown'
    def channel = config?.channel ?: env.SLACK_CHANNEL ?: '#builds'
    def appIcon = config?.appIcon

    // Step 1: Collect and archive filtered console log (always, for dashboard analyzer)
    try {
        platformModule?.collectFilteredConsoleLog()
    } catch (Exception e) {
        echo "[WARN] Console log collection failed: ${e.message}"
    }

    // Add Build Analyzer sidebar link for failed/unstable builds
    try {
        def dashUrl = env.DASHBOARD_URL ?: null
        if (dashUrl) {
            def analyzerUrl = "${dashUrl}/#analyzer?job=${env.JOB_NAME}&build=${env.BUILD_NUMBER}"
            addSidebarLink(analyzerUrl, 'Build Analyzer', 'symbol-search-regular')
        }
    } catch (Exception e) {
        // Non-critical — ignore
    }

    // Step 2: Error analysis — known patterns first, then raw exception fallback
    def errorAnalysis = env.ERROR_ANALYSIS ?: null
    if (!errorAnalysis) {
        // Check known error patterns first (no API cost)
        try {
            def failMsg = currentBuild.rawBuild.getExecution()?.getCauseOfFailure()?.getMessage() ?: ''
            def matched = knownErrors?.match(failMsg)
            if (matched) {
                def failedStage = env.FAILED_STAGE ?: env.CURRENT_STAGE ?: 'Unknown'
                def errors = [[severity: 'ERROR', stage: failedStage, message: matched.explanation]]
                if (matched.fix) errors[0].fix = matched.fix
                def analysis = [
                    explanation: matched.explanation,
                    errors: errors,
                    knownError: true
                ]
                errorAnalysis = new groovy.json.JsonBuilder(analysis).toString()
                echo "[INFO] Known error pattern detected"
            }
        } catch (Exception e) {
            // Ignore — will fall through
        }

    }

    // Step 2: Fallback - extract raw exception if AI didn't produce results
    if (!errorAnalysis && status == 'failure') {
        try {
            def execution = currentBuild.rawBuild.getExecution()
            def cause = execution?.getCauseOfFailure()
            if (cause) {
                def failedStage = env.FAILED_STAGE ?: env.CURRENT_STAGE ?: 'Unknown'
                def fallback = [
                    explanation: "Build failed in ${failedStage}: ${cause.getMessage() ?: cause.getClass().getName()}",
                    errors: []
                ]
                errorAnalysis = new groovy.json.JsonBuilder(fallback).toString()
            }
        } catch (Exception e) {
            echo "[DEBUG] Could not extract failure cause: ${e.message}"
        }
    }

    // Step 3: Save analysis artifact (optional - failure here must not prevent Slack)
    try {
        if (errorAnalysis) {
            env.ERROR_ANALYSIS = errorAnalysis
            def parsedCheck = new groovy.json.JsonSlurper().parseText(errorAnalysis)
            def isKnown = parsedCheck.knownError ?: false
            def headerType = isKnown ? 'Known Error' : (status == 'unstable' ? 'Build Unstable' : 'Build Failure')
            def failedInfo = status == 'unstable'
                ? "Unstable Reasons: ${env.UNSTABLE_REASONS}"
                : "Failed Stage: ${env.FAILED_STAGE ?: env.CURRENT_STAGE ?: 'Unknown'}"
            def header = "${headerType}\n${'=' * 50}\n\n${failedInfo}\nBranch: ${branch}\nChangeset: ${changeset}\n\n${'=' * 50}\n"
            def parsedAnalysis = saveAnalysisFile(errorAnalysis, header)
            if (parsedAnalysis) {
                def isKnownError = parsedAnalysis.knownError ?: false
                def summaryLabel = isKnownError ? "Known Error" : "Error Analysis"
                def aiSummary = "<br/><b>${summaryLabel}:</b> ${parsedAnalysis.explanation}<br/>"
                if (parsedAnalysis.errors) {
                    aiSummary += "<br/><b>Detected Errors:</b><pre>"
                    parsedAnalysis.errors.each { err ->
                        def stagePrefix = err.stage ? "(${err.stage}) " : ""
                        aiSummary += "\n[${err.severity}] ${stagePrefix}${err.message}"
                        if (err.fix) aiSummary += "\n  FIX: ${err.fix}"
                    }
                    aiSummary += "</pre>"
                }
                manager.createSummary("symbol-star.png").appendText(aiSummary, false)

                // Set build description so AI analysis appears in the Jenkins build list hover tooltip
                def desc = currentBuild.description ?: ''
                def explanation = parsedAnalysis.explanation?.take(200) ?: ''
                if (explanation) {
                    currentBuild.description = desc ? "${desc}\n${explanation}" : explanation
                }
            }
        }
    } catch (Exception e) {
        echo "[WARNING] Could not save analysis artifact: ${e.message}"
    }

    // Step 4: Send Slack notification - THE critical step, must always execute
    try {
        if (status == 'failure') {
            def existingTs = env.UPLOAD_SLACK_TS
            sendSlackBuildNotification(
                channel: existingTs ? (env.UPLOAD_SLACK_CHANNEL ?: channel) : channel,
                buildType: buildType,
                status: 'failure',
                branch: branch,
                changesetId: changeset,
                errorAnalysis: errorAnalysis,
                buildUser: env.BUILD_USER,
                buildUserEmail: env.BUILD_USER_EMAIL,
                appIcon: appIcon,
                platform: platform,
                timestamp: existingTs
            )
        } else if (status == 'unstable' && errorAnalysis) {
            def ts = env.FAILURE_SLACK_TS ?: env.UPLOAD_SLACK_TS
            def slackChannel = env.FAILURE_SLACK_CHANNEL ?: env.UPLOAD_SLACK_CHANNEL ?: channel
            if (ts) {
                sendSlackBuildNotification(
                    channel: slackChannel,
                    buildType: buildType,
                    status: status,
                    branch: branch,
                    changesetId: changeset,
                    errorAnalysis: errorAnalysis,
                    buildUser: env.BUILD_USER,
                    buildUserEmail: env.BUILD_USER_EMAIL,
                    appIcon: appIcon,
                    platform: platform,
                    timestamp: ts
                )
            }
        }
    } catch (Exception e) {
        echo "[ERROR] Slack notification failed: ${e.message}"
    }
}

def handleBuildSuccess(Map config) {
    echo "[INFO] handleBuildSuccess called"
    notifyDashboard('SUCCESS', config)

    // Save build info for cache integrity checks on next build
    platformModule?.saveBuildInfo()

    // Clear any remaining pending upload statuses (they completed if we got here)
    if (env.UPLOAD_GDRIVE_STATUS == 'pending') env.UPLOAD_GDRIVE_STATUS = 'done'
    if (env.UPLOAD_LOCAL_STATUS == 'pending') env.UPLOAD_LOCAL_STATUS = 'done'
    if (env.UPLOAD_STORE_STATUS == 'pending') env.UPLOAD_STORE_STATUS = 'done'

    // Update existing upload notification or send new one
    def params = buildSlackParams(config)
    def existingTs = env.UPLOAD_SLACK_TS
    echo "[INFO] Sending success notification to ${existingTs ? 'existing message' : params.channel}"
    sendSlackBuildNotification(params + [timestamp: existingTs])
}

def handleBuildUnstable(Map config) {
    echo "[INFO] handleBuildUnstable called"
    notifyDashboard('UNSTABLE', config)

    // Send Slack notification immediately (without AI analysis)
    def slackResponse = sendSlackBuildNotification(buildSlackParams(config) + [status: 'unstable'])

    // Store Slack message coordinates so finalizeBuild() can update it
    if (slackResponse) {
        env.FAILURE_SLACK_TS = slackResponse.ts
        env.FAILURE_SLACK_CHANNEL = slackResponse.channelId
    }
}

def handleBuildAborted(Map config) {
    notifyDashboard('ABORTED', config)

    def params = buildSlackParams(config)
    sendSlackBuildNotification(params + [status: 'aborted'])
}

return this
