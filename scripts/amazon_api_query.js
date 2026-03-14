#!/usr/bin/env node
// Query all Amazon App Submission API methods and dump responses to a file

const https = require("https");
const fs = require("fs");

const CLIENT_ID = process.env.AMAZON_CLIENT_ID;
const CLIENT_SECRET = process.env.AMAZON_CLIENT_SECRET;
const APP_ID = process.env.AMAZON_APP_ID;

if (!CLIENT_ID || !CLIENT_SECRET || !APP_ID) {
  console.error("[ERROR] Missing required environment variables: AMAZON_CLIENT_ID, AMAZON_CLIENT_SECRET, AMAZON_APP_ID");
  process.exit(1);
}

function req(url, opts, body) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const r = https.request(
      {
        hostname: u.hostname,
        path: u.pathname + u.search,
        method: opts.method || "GET",
        headers: opts.headers || {},
      },
      (res) => {
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () =>
          resolve({
            status: res.statusCode,
            body: Buffer.concat(chunks).toString(),
            etag: res.headers["etag"],
            headers: res.headers,
          })
        );
      }
    );
    r.on("error", reject);
    if (body) {
      r.write(body);
      r.end();
    } else {
      r.end();
    }
  });
}

function api(path) {
  return `https://developer.amazon.com/api/appstore/v1/applications/${APP_ID}${path}`;
}

(async () => {
  const out = [];
  function log(s) {
    console.log(s);
    out.push(s);
  }

  // Auth
  const auth = await req(
    "https://api.amazon.com/auth/O2/token",
    { method: "POST", headers: { "Content-Type": "application/json" } },
    JSON.stringify({
      grant_type: "client_credentials",
      client_id: CLIENT_ID,
      client_secret: CLIENT_SECRET,
      scope: "appstore::apps:readwrite",
    })
  );
  const authJson = JSON.parse(auth.body);
  const token = authJson.access_token;
  log("=== AUTH ===");
  log(`HTTP ${auth.status}`);
  log(`Token: ${token.substring(0, 20)}...`);
  log(`Expires: ${authJson.expires_in}s`);
  log("");

  const authH = { Authorization: `Bearer ${token}` };

  // GET /edits
  log("=== GET /edits ===");
  const edit = await req(api("/edits"), { headers: authH });
  log(`HTTP ${edit.status} | ETag: ${edit.etag}`);
  log(edit.body);
  log("");

  if (edit.status !== 200) {
    log("No edit found, stopping.");
    fs.writeFileSync(
      "c:/Workspaces/tool_jenkins_build_system/scripts/amazon_api_dump.txt",
      out.join("\n")
    );
    return;
  }

  const editData = JSON.parse(edit.body);
  const editId = editData.id;

  // GET /edits/{editId}/apks
  log("=== GET /edits/{editId}/apks (listApks) ===");
  const apks = await req(api(`/edits/${editId}/apks`), { headers: authH });
  log(`HTTP ${apks.status} | ETag: ${apks.etag}`);
  log(apks.body);
  log("");

  // GET each individual APK + targeting
  const apkList = JSON.parse(apks.body);
  for (const apk of apkList) {
    log(`=== GET /edits/{editId}/apks/${apk.id} (getApk: ${apk.name}) ===`);
    const single = await req(api(`/edits/${editId}/apks/${apk.id}`), {
      headers: authH,
    });
    log(`HTTP ${single.status} | ETag: ${single.etag}`);
    log(single.body);
    log(`Headers: ${JSON.stringify(single.headers)}`);
    log("");

    log(
      `=== GET /edits/{editId}/apks/${apk.id}/targeting (${apk.name}) ===`
    );
    const tgt = await req(
      api(`/edits/${editId}/apks/${apk.id}/targeting`),
      { headers: authH }
    );
    log(`HTTP ${tgt.status} | ETag: ${tgt.etag}`);
    log(tgt.body);
    log("");
  }

  // GET /edits/{editId}/listings
  log("=== GET /edits/{editId}/listings ===");
  const listings = await req(api(`/edits/${editId}/listings`), {
    headers: authH,
  });
  log(`HTTP ${listings.status}`);
  const listBody = listings.body;
  log(listBody.length > 3000 ? listBody.substring(0, 3000) + "...[truncated]" : listBody);
  log("");

  // GET /edits/{editId}/availability
  log("=== GET /edits/{editId}/availability ===");
  const avail = await req(api(`/edits/${editId}/availability`), {
    headers: authH,
  });
  log(`HTTP ${avail.status} | ETag: ${avail.etag}`);
  const availBody = avail.body;
  log(availBody.length > 3000 ? availBody.substring(0, 3000) + "...[truncated]" : availBody);
  log("");

  const outPath =
    "c:/Workspaces/tool_jenkins_build_system/scripts/amazon_api_dump.txt";
  fs.writeFileSync(outPath, out.join("\n"));
  log(`--- Saved to ${outPath} ---`);
})();
