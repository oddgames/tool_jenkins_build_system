#!/usr/bin/env node
//
// Amazon App Submission API — Upload APK (no commit)
//
// Usage:
//   node amazon_upload.js [apk-path]
//
// Ref: https://developer.amazon.com/api/appstore/v1
//      https://amzndevresources.com/appsubapi/swagger-en/index.html
//
// This script creates an edit, uploads the APK, and stops WITHOUT committing.
// Check the Amazon Developer Console to inspect the result.
//

// ─── Amazon Credentials & Config ─────────────────────────────────────────────
// Environment variables (set by Jenkins withCredentials or export manually):
//   AMAZON_CLIENT_ID     - OAuth2 client ID
//   AMAZON_CLIENT_SECRET - OAuth2 client secret
//   AMAZON_APP_ID        - App ID from Amazon Developer Console
// ──────────────────────────────────────────────────────────────────────────────

const https = require("https");
const fs = require("fs");
const path = require("path");

const CLIENT_ID = process.env.AMAZON_CLIENT_ID;
const CLIENT_SECRET = process.env.AMAZON_CLIENT_SECRET;
const APP_ID = process.env.AMAZON_APP_ID;
const APK_PATH = process.argv[2];

if (!CLIENT_ID || !CLIENT_SECRET || !APP_ID) {
  console.error("[ERROR] Missing required environment variables: AMAZON_CLIENT_ID, AMAZON_CLIENT_SECRET, AMAZON_APP_ID");
  process.exit(1);
}
if (!APK_PATH && process.argv[2] !== "--attach") {
  console.error("[ERROR] Usage: node amazon_upload.js <apk-path>");
  process.exit(1);
}

const API_VERSION = "v1";

// ─── Helpers ──────────────────────────────────────────────────────────────────

function request(url, options, body) {
  return new Promise((resolve, reject) => {
    const parsed = new URL(url);
    const opts = {
      hostname: parsed.hostname,
      path: parsed.pathname + parsed.search,
      method: options.method || "GET",
      headers: options.headers || {},
    };

    const req = https.request(opts, (res) => {
      const chunks = [];
      res.on("data", (c) => chunks.push(c));
      res.on("end", () => {
        const rawBody = Buffer.concat(chunks).toString();
        const etag = res.headers["etag"] || null;
        let json = null;
        try {
          json = JSON.parse(rawBody);
        } catch {}
        resolve({ status: res.statusCode, body: rawBody, json, etag, headers: res.headers });
      });
    });

    req.on("error", reject);

    if (body instanceof fs.ReadStream) {
      // Track upload progress
      const totalBytes = options.headers?.["Content-Length"];
      let uploaded = 0;
      let lastPct = -1;
      body.on("data", (chunk) => {
        uploaded += chunk.length;
        if (totalBytes) {
          const pct = Math.floor((uploaded / totalBytes) * 100);
          if (pct !== lastPct && pct % 5 === 0) {
            const uploadedMB = (uploaded / (1024 * 1024)).toFixed(1);
            const totalMB = (totalBytes / (1024 * 1024)).toFixed(1);
            process.stdout.write(`\r[UPLOAD] ${uploadedMB} / ${totalMB} MB (${pct}%)`);
            lastPct = pct;
          }
        }
      });
      body.on("end", () => {
        if (totalBytes) process.stdout.write("\n");
      });
      body.pipe(req);
    } else if (body) {
      req.write(typeof body === "string" ? body : JSON.stringify(body));
      req.end();
    } else {
      req.end();
    }
  });
}

function apiUrl(path) {
  return `https://developer.amazon.com/api/appstore/${API_VERSION}/applications/${APP_ID}${path}`;
}

function log(label, msg) {
  console.log(`[${label}] ${msg}`);
}

function die(msg) {
  console.error(`\n[ERROR] ${msg}`);
  process.exit(1);
}

// ─── Steps ────────────────────────────────────────────────────────────────────

async function getToken() {
  log("AUTH", "Requesting OAuth token from api.amazon.com...");

  const res = await request("https://api.amazon.com/auth/O2/token", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
  }, JSON.stringify({
    grant_type: "client_credentials",
    client_id: CLIENT_ID,
    client_secret: CLIENT_SECRET,
    scope: "appstore::apps:readwrite",
  }));

  if (res.status !== 200 || !res.json?.access_token) {
    die(`Auth failed (HTTP ${res.status}): ${res.body}`);
  }

  log("AUTH", `Token acquired (expires in ${res.json.expires_in}s)`);
  return res.json.access_token;
}

async function getExistingEdit(token) {
  log("EDIT", "Checking for existing edit...");

  const res = await request(apiUrl("/edits"), {
    headers: { Authorization: `Bearer ${token}` },
  });

  if (res.status === 200 && res.json?.id) {
    log("EDIT", `Found existing edit: ${res.json.id} (status: ${res.json.status})`);
    return { id: res.json.id, status: res.json.status, etag: res.etag };
  }

  log("EDIT", "No existing edit found");
  return null;
}

async function deleteEdit(token, editId, etag) {
  log("EDIT", `Deleting existing edit ${editId}...`);

  const res = await request(apiUrl(`/edits/${editId}`), {
    method: "DELETE",
    headers: {
      Authorization: `Bearer ${token}`,
      "If-Match": etag,
    },
  });

  if (res.status !== 204 && res.status !== 200) {
    die(`Failed to delete edit (HTTP ${res.status}): ${res.body}`);
  }

  log("EDIT", "Deleted");
}

async function createEdit(token) {
  log("EDIT", "Creating new edit...");

  const res = await request(apiUrl("/edits"), {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
  });

  if (res.status !== 200 || !res.json?.id) {
    die(`Failed to create edit (HTTP ${res.status}): ${res.body}`);
  }

  log("EDIT", `Created edit: ${res.json.id} (status: ${res.json.status})`);
  return { id: res.json.id, etag: res.etag };
}

async function listApks(token, editId) {
  const res = await request(apiUrl(`/edits/${editId}/apks`), {
    headers: { Authorization: `Bearer ${token}` },
  });

  return res.json || [];
}

async function replaceApk(token, editId, apkPath) {
  const fileName = path.basename(apkPath);
  const fileSize = fs.statSync(apkPath).size;
  const sizeMB = (fileSize / (1024 * 1024)).toFixed(1);

  // Step 1: List existing APKs to get the ID to replace
  const apks = await listApks(token, editId);
  if (apks.length === 0) die("No existing APKs in edit to replace");

  const targetApk = apks[0]; // Replace the first (usually only) APK
  log("REPLACE", `Will replace ${targetApk.name} (${targetApk.id}, versionCode: ${targetApk.versionCode})`);

  // Step 2: Get ETag for the specific APK
  const apkRes = await request(apiUrl(`/edits/${editId}/apks/${targetApk.id}`), {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (apkRes.status !== 200) die(`Failed to get APK ETag (HTTP ${apkRes.status}): ${apkRes.body}`);
  const etag = apkRes.etag;
  log("REPLACE", `ETag: ${etag}`);

  // Step 3: Replace APK via PUT (same approach as fastlane-plugin-amazon_appstore)
  log("REPLACE", `Uploading ${fileName} (${sizeMB} MB) via PUT /apks/${targetApk.id}/replace...`);

  const stream = fs.createReadStream(apkPath);

  const res = await request(apiUrl(`/edits/${editId}/apks/${targetApk.id}/replace`), {
    method: "PUT",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/vnd.android.package-archive",
      "Content-Length": fileSize.toString(),
      "If-Match": etag,
    },
  }, stream);

  if (res.status !== 200) {
    die(`APK replace failed (HTTP ${res.status}): ${res.body}`);
  }

  log("REPLACE", `Response (HTTP ${res.status}): ${res.body}`);
  log("REPLACE", `Success — APK ID: ${res.json?.id}, versionCode: ${res.json?.versionCode}, name: ${res.json?.name}`);
  return res.json;
}

// ─── Attach-only mode ─────────────────────────────────────────────────────────
// Usage: node amazon_upload.js --attach <editId> <fileId>

async function attachOnly(editIdArg, fileId) {
  const token = await getToken();

  // If no edit ID given, find the current active edit
  let editId = editIdArg;
  if (!editId) {
    log("EDIT", "Looking up active edit...");
    const existing = await getExistingEdit(token);
    if (!existing) die("No active edit found. Run the full upload first.");
    editId = existing.id;
  }

  console.log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
  console.log("  Amazon App Submission API — Attach APK to existing edit");
  console.log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
  console.log(`  Edit ID: ${editId}`);
  console.log(`  File ID: ${fileId}`);
  console.log("");

  const maxAttempts = 30;
  const delaySeconds = 10;
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    log("ATTACH", `Attempt ${attempt}/${maxAttempts}...`);
    const res = await request(apiUrl(`/edits/${editId}/apks/attach`), {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
      },
    }, JSON.stringify({ fileId }));

    if (res.status === 200) {
      log("ATTACH", `Response: ${res.body}`);
      log("ATTACH", `Success — APK ID: ${res.json?.id}, versionCode: ${res.json?.versionCode}, name: ${res.json?.name}`);

      // List APKs after attach
      const apks = await listApks(token, editId);
      log("APKs", "APKs in edit after attach:");
      apks.forEach((a) => log("APKs", `  ${a.id} — versionCode: ${a.versionCode}, name: ${a.name}`));
      return;
    }

    log("ATTACH", `Failed (HTTP ${res.status}): ${res.body}`);
    const notReady = res.json?.errors?.some((e) => e.errorCode === "asset_not_ready");
    if (!notReady) {
      die(`APK attach failed — see response above`);
    }

    log("ATTACH", `Asset still processing, retrying in ${delaySeconds}s...`);
    await new Promise((r) => setTimeout(r, delaySeconds * 1000));
  }
  die(`Timed out after ${maxAttempts * delaySeconds}s`);
}

// ─── Main ─────────────────────────────────────────────────────────────────────

async function main() {
  // --attach mode: just attach an already-uploaded file to an existing edit
  if (process.argv[2] === "--attach") {
    const editId = process.argv[3] || null;  // null = auto-detect active edit
    const fileId = process.argv[4] || "amzn1.devportal.assetupload.1112b857bea04514849a219dc7f07d9e";
    return attachOnly(editId, fileId);
  }

  // Validate APK path
  if (!fs.existsSync(APK_PATH)) die(`File not found: ${APK_PATH}`);

  console.log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
  console.log("  Amazon App Submission API — Upload APK (no commit)");
  console.log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
  console.log(`  App ID:  ${APP_ID}`);
  console.log(`  APK:     ${APK_PATH}`);
  console.log("");

  // Step 1: Auth
  const token = await getToken();

  // Step 2: Handle existing edit (only one allowed at a time)
  const existing = await getExistingEdit(token);
  if (existing) {
    await deleteEdit(token, existing.id, existing.etag);
  }

  // Step 3: Create fresh edit
  const edit = await createEdit(token);

  // Step 4: Show current APKs in edit (inherited from live version)
  const apksBefore = await listApks(token, edit.id);
  if (apksBefore.length > 0) {
    log("APKs", `Current APKs in edit (from live version):`);
    apksBefore.forEach((a) => log("APKs", `  ${a.id} — versionCode: ${a.versionCode}, name: ${a.name}`));
  }

  // Step 5: Replace existing APK (PUT /apks/{apkId}/replace — same as fastlane plugin)
  const uploaded = await replaceApk(token, edit.id, APK_PATH);

  // Step 6: Show APKs after upload
  const apksAfter = await listApks(token, edit.id);
  log("APKs", `APKs in edit after upload:`);
  apksAfter.forEach((a) => log("APKs", `  ${a.id} — versionCode: ${a.versionCode}, name: ${a.name}`));

  // Summary
  console.log("");
  console.log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
  console.log("  DONE — Edit created and APK uploaded, NOT committed.");
  console.log("");
  console.log("  Check the Amazon Developer Console:");
  console.log(`  https://developer.amazon.com/apps-and-games/console/app/${APP_ID}`);
  console.log("");
  console.log(`  Edit ID: ${edit.id}`);
  console.log("");
  console.log("  Next steps you can try:");
  console.log("    - Inspect the edit on the portal");
  console.log("    - To commit: POST /edits/{editId}/commit (with If-Match ETag)");
  console.log("    - To discard: DELETE /edits/{editId} (with If-Match ETag)");
  console.log("    - To submit for LAT: commit the edit, then enable Live App Testing in console");
  console.log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
}

main().catch((err) => {
  console.error(`\n[FATAL] ${err.message}`);
  process.exit(1);
});
