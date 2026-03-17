#
# Amazon App Submission API -- Upload APK (does not commit or go live)
#
# Usage: powershell -File amazon_upload.ps1 <apk-path>
#
# Environment variables (set by Jenkins withCredentials):
#   AMAZON_CLIENT_ID     - OAuth2 client ID
#   AMAZON_CLIENT_SECRET - OAuth2 client secret
#   AMAZON_APP_ID        - App ID from Amazon Developer Console
#
# Ref: https://developer.amazon.com/api/appstore/v1
#

param(
    [Parameter(Mandatory=$true)]
    [string]$ApkPath
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$CLIENT_ID     = $env:AMAZON_CLIENT_ID
$CLIENT_SECRET = $env:AMAZON_CLIENT_SECRET
$APP_ID        = $env:AMAZON_APP_ID

if (-not $CLIENT_ID -or -not $CLIENT_SECRET -or -not $APP_ID) {
    Write-Error "[ERROR] Missing required environment variables: AMAZON_CLIENT_ID, AMAZON_CLIENT_SECRET, AMAZON_APP_ID"
    exit 1
}
if (-not (Test-Path $ApkPath)) {
    Write-Error "[ERROR] File not found: $ApkPath"
    exit 1
}

$API_BASE = "https://developer.amazon.com/api/appstore/v1/applications/$APP_ID"

function Log($label, $msg) { Write-Host "[$label] $msg" }

function Amazon-Request {
    param(
        [string]$Url,
        [string]$Method = "GET",
        [string]$Token,
        [string]$Body,
        [string]$ContentType,
        [string]$IfMatch
    )

    $headers = @{}
    if ($Token)   { $headers["Authorization"] = "Bearer $Token" }
    if ($IfMatch) { $headers["If-Match"] = $IfMatch }

    $params = @{
        Uri             = $Url
        Method          = $Method
        Headers         = $headers
        UseBasicParsing = $true
        TimeoutSec      = 60
    }
    if ($ContentType) { $params["ContentType"] = $ContentType }
    if ($Body)        { $params["Body"] = $Body }

    try {
        $resp = Invoke-WebRequest @params
        $etag = $resp.Headers["ETag"]
        if ($etag -is [array]) { $etag = $etag[0] }
        $json = $null
        if ($resp.Content) {
            try { $json = $resp.Content | ConvertFrom-Json } catch {}
        }
        return @{ Status = $resp.StatusCode; Body = $resp.Content; Json = $json; ETag = $etag }
    } catch {
        $ex = $_.Exception
        $status = 0
        $body = ""
        if ($ex.Response) {
            $status = [int]$ex.Response.StatusCode
            $stream = $ex.Response.GetResponseStream()
            $reader = New-Object System.IO.StreamReader($stream)
            $body = $reader.ReadToEnd()
            $reader.Close()
        }
        $json = $null
        if ($body) { try { $json = $body | ConvertFrom-Json } catch {} }
        return @{ Status = $status; Body = $body; Json = $json; ETag = $null }
    }
}

# --- Step 1: OAuth2 Token ---
Log "AUTH" "Requesting OAuth token from api.amazon.com..."
$tokenBody = @{
    grant_type    = "client_credentials"
    client_id     = $CLIENT_ID
    client_secret = $CLIENT_SECRET
    scope         = "appstore::apps:readwrite"
} | ConvertTo-Json

$tokenResp = Amazon-Request -Url "https://api.amazon.com/auth/O2/token" -Method "POST" -Body $tokenBody -ContentType "application/json"
if ($tokenResp.Status -ne 200 -or -not $tokenResp.Json.access_token) {
    Write-Error "[ERROR] Auth failed (HTTP $($tokenResp.Status)): $($tokenResp.Body)"
    exit 1
}
$token = $tokenResp.Json.access_token
Log "AUTH" "Token acquired (expires in $($tokenResp.Json.expires_in)s)"

# --- Step 2: Check/Delete Existing Edit ---
Log "EDIT" "Checking for existing edit..."
$editResp = Amazon-Request -Url "$API_BASE/edits" -Method "GET" -Token $token
if ($editResp.Status -eq 200 -and $editResp.Json.id) {
    Log "EDIT" "Found existing edit: $($editResp.Json.id) (status: $($editResp.Json.status)) -- deleting..."
    $delResp = Amazon-Request -Url "$API_BASE/edits/$($editResp.Json.id)" -Method "DELETE" -Token $token -IfMatch $editResp.ETag
    if ($delResp.Status -ne 204 -and $delResp.Status -ne 200) {
        Write-Error "[ERROR] Failed to delete existing edit (HTTP $($delResp.Status)): $($delResp.Body)"
        exit 1
    }
    Log "EDIT" "Deleted"
}

# --- Step 3: Create Fresh Edit ---
Log "EDIT" "Creating new edit..."
$newEditResp = Amazon-Request -Url "$API_BASE/edits" -Method "POST" -Token $token
if ($newEditResp.Status -ne 200 -or -not $newEditResp.Json.id) {
    Write-Error "[ERROR] Failed to create edit (HTTP $($newEditResp.Status)): $($newEditResp.Body)"
    exit 1
}
$editId = $newEditResp.Json.id
Log "EDIT" "Created edit: $editId"

# --- Step 4: List Existing APKs ---
$apksResp = Amazon-Request -Url "$API_BASE/edits/$editId/apks" -Method "GET" -Token $token
$apks = $apksResp.Json
if (-not $apks -or $apks.Count -eq 0) {
    Write-Error "[ERROR] No existing APKs in edit to replace"
    exit 1
}
$targetApk = $apks[0]
Log "REPLACE" "Will replace $($targetApk.name) ($($targetApk.id), versionCode: $($targetApk.versionCode))"

# --- Step 5: Get ETag for Target APK ---
$apkInfoResp = Amazon-Request -Url "$API_BASE/edits/$editId/apks/$($targetApk.id)" -Method "GET" -Token $token
if ($apkInfoResp.Status -ne 200) {
    Write-Error "[ERROR] Failed to get APK ETag (HTTP $($apkInfoResp.Status)): $($apkInfoResp.Body)"
    exit 1
}
$apkETag = $apkInfoResp.ETag
Log "REPLACE" "ETag: $apkETag"

# --- Step 6: Upload APK via PUT ---
$fileInfo = Get-Item $ApkPath
$sizeMB = "{0:N1}" -f ($fileInfo.Length / 1MB)
Log "REPLACE" "Uploading $($fileInfo.Name) ($sizeMB MB) via PUT /apks/$($targetApk.id)/replace..."

$fileBytes = [System.IO.File]::ReadAllBytes($ApkPath)
$headers = @{
    "Authorization" = "Bearer $token"
    "If-Match"      = $apkETag
}
try {
    $uploadResp = Invoke-WebRequest `
        -Uri "$API_BASE/edits/$editId/apks/$($targetApk.id)/replace" `
        -Method "PUT" `
        -Headers $headers `
        -ContentType "application/vnd.android.package-archive" `
        -Body $fileBytes `
        -UseBasicParsing `
        -TimeoutSec 600
} catch {
    $ex = $_.Exception
    $status = 0; $body = ""
    if ($ex.Response) {
        $status = [int]$ex.Response.StatusCode
        $stream = $ex.Response.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream)
        $body = $reader.ReadToEnd()
        $reader.Close()
    }
    Write-Error "[ERROR] APK replace failed (HTTP $status): $body"
    exit 1
}

$uploadJson = $null
try { $uploadJson = $uploadResp.Content | ConvertFrom-Json } catch {}
Log "REPLACE" "Success -- versionCode: $($uploadJson.versionCode), name: $($uploadJson.name)"

# Edit is NOT committed — committing submits for production review.
# Instead, submit to Live App Testing (default_group) from the Developer Console:
#   https://developer.amazon.com/apps-and-games/console/app/$APP_ID
# LAT has no REST API so this step must be done manually.

Log "DONE" "APK uploaded to edit $editId -- submit to LAT from https://developer.amazon.com/apps-and-games/console/app/$APP_ID"
