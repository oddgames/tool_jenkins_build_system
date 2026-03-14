require "json"
require "net/http"
require "uri"
require "openssl"
require "base64"
require "time"

$progress_file = ENV["UPLOAD_PROGRESS_FILE"]

def log(level, msg)
  puts "[#{Time.now.strftime("%H:%M:%S")}] [#{level}] #{msg}"
  $stdout.flush
end

def write_progress(percent)
  return unless $progress_file
  File.write($progress_file, percent.to_i.to_s)
rescue
end

def get_access_token(json_key_path, scope)
  key_data = JSON.parse(File.read(json_key_path))
  now = Time.now.to_i
  header = { alg: "RS256", typ: "JWT" }
  claims = { iss: key_data["client_email"], scope: scope, aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600 }
  segments = [header, claims].map { |h| Base64.urlsafe_encode64(JSON.generate(h), padding: false) }
  signing_input = segments.join(".")
  key = OpenSSL::PKey::RSA.new(key_data["private_key"])
  signature = key.sign(OpenSSL::Digest::SHA256.new, signing_input)
  jwt = "#{signing_input}.#{Base64.urlsafe_encode64(signature, padding: false)}"
  uri = URI("https://oauth2.googleapis.com/token")
  res = Net::HTTP.post_form(uri, grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: jwt)
  JSON.parse(res.body)["access_token"]
end

def delete_play_edit(token, package, edit_id)
  uri = URI("https://androidpublisher.googleapis.com/androidpublisher/v3/applications/#{package}/edits/#{edit_id}")
  req = Net::HTTP::Delete.new(uri)
  req["Authorization"] = "Bearer #{token}"
  res = Net::HTTP.start(uri.host, uri.port, use_ssl: true) { |http| http.request(req) }
  log "INFO", "Deleted stale edit #{edit_id}: #{res.code}"
rescue => e
  log "WARN", "Could not delete edit #{edit_id}: #{e.message}"
end

def create_play_edit(token, package)
  uri = URI("https://androidpublisher.googleapis.com/androidpublisher/v3/applications/#{package}/edits")
  req = Net::HTTP::Post.new(uri)
  req["Authorization"] = "Bearer #{token}"
  req["Content-Type"] = "application/json"
  res = Net::HTTP.start(uri.host, uri.port, use_ssl: true) { |http| http.request(req) }
  raise "Failed to create edit: #{res.body}" unless res.is_a?(Net::HTTPSuccess)
  body = JSON.parse(res.body)
  if body["expiryTimeSeconds"]
    expiry = Time.at(body["expiryTimeSeconds"].to_i)
    remaining = body["expiryTimeSeconds"].to_i - Time.now.to_i
    log "INFO", "Edit expires at #{expiry} (#{remaining}s from now)"
  end
  body["id"]
end

def list_bundles(token, package, edit_id)
  uri = URI("https://androidpublisher.googleapis.com/androidpublisher/v3/applications/#{package}/edits/#{edit_id}/bundles")
  req = Net::HTTP::Get.new(uri)
  req["Authorization"] = "Bearer #{token}"
  res = Net::HTTP.start(uri.host, uri.port, use_ssl: true) { |http| http.request(req) }
  return [] unless res.is_a?(Net::HTTPSuccess)
  body = JSON.parse(res.body)
  (body["bundles"] || []).map { |b| b["versionCode"].to_i }
end

def check_upload_status(upload_uri, file_size, token)
  req = Net::HTTP::Put.new(upload_uri)
  req["Authorization"] = "Bearer #{token}"
  req["Content-Length"] = "0"
  req["Content-Range"] = "bytes */#{file_size}"

  http = Net::HTTP.new(upload_uri.host, upload_uri.port)
  http.use_ssl = true
  http.open_timeout = 30
  http.read_timeout = 30

  res = http.request(req)

  if res.code == "308"
    # Upload incomplete — Range header tells us how far we got
    range = res["Range"]
    if range && range =~ /bytes=0-(\d+)/
      return { status: :incomplete, uploaded: $1.to_i + 1 }
    else
      # No Range header means nothing was received
      return { status: :incomplete, uploaded: 0 }
    end
  elsif res.is_a?(Net::HTTPSuccess)
    return { status: :complete, body: JSON.parse(res.body) }
  else
    return { status: :dead, code: res.code, body: res.body }
  end
end

def send_chunk(upload_uri, token, chunk, offset, file_size)
  chunk_end = offset + chunk.bytesize - 1

  req = Net::HTTP::Put.new(upload_uri)
  req["Authorization"] = "Bearer #{token}"
  req["Content-Type"] = "application/octet-stream"
  req["Content-Length"] = chunk.bytesize.to_s
  req["Content-Range"] = "bytes #{offset}-#{chunk_end}/#{file_size}"
  req.body = chunk

  http = Net::HTTP.new(upload_uri.host, upload_uri.port)
  http.use_ssl = true
  http.open_timeout = 120
  http.read_timeout = 600
  http.write_timeout = 600

  http.request(req)
end

def upload_bundle_multipart(token, package, edit_id, aab_path)
  file_size = File.size(aab_path)
  boundary = "----PlayUpload#{Time.now.to_i}#{rand(100000)}"

  uri = URI("https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/#{package}/edits/#{edit_id}/bundles?uploadType=multipart")

  # Build multipart body: metadata part + file part
  metadata_part = "--#{boundary}\r\n" \
    "Content-Type: application/json; charset=UTF-8\r\n\r\n" \
    "{}\r\n"
  file_header = "--#{boundary}\r\n" \
    "Content-Type: application/octet-stream\r\n\r\n"
  file_footer = "\r\n--#{boundary}--\r\n"

  file_data = File.binread(aab_path)
  body = metadata_part + file_header + file_data + file_footer

  req = Net::HTTP::Post.new(uri)
  req["Authorization"] = "Bearer #{token}"
  req["Content-Type"] = "multipart/related; boundary=#{boundary}"
  req["Content-Length"] = body.bytesize.to_s
  req.body = body

  http = Net::HTTP.new(uri.host, uri.port)
  http.use_ssl = true
  http.open_timeout = 120
  http.read_timeout = 600
  http.write_timeout = 600

  log "INFO", "Uploading #{(file_size / 1024.0 / 1024.0).round(1)} MB via multipart upload..."
  start_time = Time.now

  res = http.request(req)

  total_time = Time.now - start_time
  avg_speed = file_size / total_time / 1024.0 / 1024.0

  unless res.is_a?(Net::HTTPSuccess)
    raise "Multipart upload failed: #{res.code} #{res.body}"
  end

  log "OK", "Multipart upload complete in #{total_time.round(1)}s (avg #{avg_speed.round(2)} MB/s)"
  JSON.parse(res.body)
end

def upload_bundle(token, package, edit_id, aab_path, json_key_path)
  file_size = File.size(aab_path)

  # Snapshot existing bundles BEFORE upload so we can detect new ones after failure
  bundles_before = list_bundles(token, package, edit_id)
  log "INFO", "Existing bundles before upload: #{bundles_before.length}"

  # Try multipart upload first (single atomic request, no chunking)
  # Falls back to resumable chunked upload on failure
  begin
    result = upload_bundle_multipart(token, package, edit_id, aab_path)
    return result, edit_id, token
  rescue => e
    log "WARN", "Multipart upload failed: #{e.message}"
    # If the edit died, check if bundle made it to storage, otherwise get a fresh edit
    if e.message.include?("expired") || e.message.include?("FAILED_PRECONDITION")
      delete_play_edit(token, package, edit_id)
      token = get_access_token(json_key_path, "https://www.googleapis.com/auth/androidpublisher")
      edit_id = create_play_edit(token, package)
      existing = list_bundles(token, package, edit_id)
      new_bundles = existing - bundles_before
      if new_bundles.any?
        log "OK", "New bundle detected in storage after multipart attempt: #{new_bundles.join(', ')}"
        return nil, edit_id, token
      end
      if existing.any?
        log "INFO", "Only pre-existing bundles found (#{existing.length}), upload did not complete"
      end
      log "INFO", "Falling back to resumable upload with fresh edit: #{edit_id}..."
    else
      log "INFO", "Falling back to resumable chunked upload..."
    end
  end

  # Dynamic chunk size: split into ~9 chunks, minimum 50MB, rounded to nearest MB
  chunk_size = [(file_size / 9.0).ceil / (1024 * 1024) * (1024 * 1024), 50 * 1024 * 1024].max
  max_session_retries = 3        # Max times to restart the entire upload session
  current_edit_id = edit_id
  token_created_at = Time.now.to_i

  session_retry = 0
  while session_retry <= max_session_retries
    # Initiate resumable upload session
    init_uri = URI("https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/#{package}/edits/#{current_edit_id}/bundles?uploadType=resumable")
    init_req = Net::HTTP::Post.new(init_uri)
    init_req["Authorization"] = "Bearer #{token}"
    init_req["Content-Type"] = "application/json"
    init_req["X-Upload-Content-Type"] = "application/octet-stream"
    init_req["X-Upload-Content-Length"] = file_size.to_s
    init_req.body = "{}"

    init_res = Net::HTTP.start(init_uri.host, init_uri.port, use_ssl: true) { |http| http.request(init_req) }
    raise "Failed to initiate upload: #{init_res.code} #{init_res.body}" unless init_res.is_a?(Net::HTTPSuccess)

    upload_uri = URI(init_res["Location"])
    log "OK", "Resumable upload session initiated#{session_retry > 0 ? " (retry #{session_retry})" : ""}"
    log "INFO", "Uploading #{(file_size / 1024.0 / 1024.0).round(1)} MB to Google Play..."

    uploaded = 0
    start_time = Time.now
    max_chunk_retries = 5
    token_refresh_count = 0

    begin
      File.open(aab_path, "rb") do |file|
        while uploaded < file_size
          # Proactively refresh token before it expires (refresh at 50 min of 60 min TTL)
          if Time.now.to_i - token_created_at > 3000
            token = get_access_token(json_key_path, "https://www.googleapis.com/auth/androidpublisher")
            token_created_at = Time.now.to_i
            log "INFO", "Proactively refreshed access token"
          end

          file.seek(uploaded)
          chunk = file.read([chunk_size, file_size - uploaded].min)
          chunk_retry = 0
          res = nil

          while chunk_retry <= max_chunk_retries
            begin
              res = send_chunk(upload_uri, token, chunk, uploaded, file_size)
              break  # Got a response, exit retry loop
            rescue IO::TimeoutError, Errno::ETIMEDOUT, Errno::ECONNRESET, Errno::ECONNREFUSED, SocketError, EOFError => e
              chunk_retry += 1
              if chunk_retry <= max_chunk_retries
                wait_time = 2 ** (chunk_retry - 1)  # Exponential backoff: 1s, 2s, 4s, 8s, 16s
                log "WARN", "Network error (attempt #{chunk_retry}/#{max_chunk_retries}): #{e.message}"
                log "INFO", "Checking upload status before retry..."
                sleep(wait_time)

                # Check where Google thinks we are before retrying
                status = check_upload_status(upload_uri, file_size, token)
                if status[:status] == :complete
                  log "OK", "Upload was already complete!"
                  return status[:body], current_edit_id, token
                elsif status[:status] == :incomplete
                  actual_uploaded = status[:uploaded]
                  if actual_uploaded != uploaded
                    log "INFO", "Server has #{(actual_uploaded / 1024.0 / 1024.0).round(1)} MB, adjusting position"
                    uploaded = actual_uploaded
                    file.seek(uploaded)
                    chunk = file.read([chunk_size, file_size - uploaded].min)
                  end
                elsif status[:status] == :dead
                  log "WARN", "Upload session is dead (#{status[:code]}), will restart"
                  raise "Upload session dead"
                end
              else
                raise "Network error after #{max_chunk_retries} attempts: #{e.message}"
              end
            end
          end

          # Check response
          if res.code == "308"
            uploaded += chunk.bytesize
          elsif res.is_a?(Net::HTTPSuccess)
            uploaded = file_size
            total_time = Time.now - start_time
            avg_speed = file_size / total_time / 1024.0 / 1024.0
            log "OK", "Upload complete in #{total_time.round(1)}s (avg #{avg_speed.round(2)} MB/s)"
            return JSON.parse(res.body), current_edit_id, token
          elsif res.code.to_i >= 500
            # Server error — check status and retry the chunk
            chunk_retry += 1
            wait_time = 2 ** (chunk_retry - 1)  # Exponential backoff: 1s, 2s, 4s, 8s, 16s
            log "WARN", "Server error #{res.code}, waiting #{wait_time}s then checking upload status..."
            sleep(wait_time)

            status = check_upload_status(upload_uri, file_size, token)
            if status[:status] == :complete
              return status[:body], current_edit_id, token
            elsif status[:status] == :incomplete
              uploaded = status[:uploaded]
              log "INFO", "Server has #{(uploaded / 1024.0 / 1024.0).round(1)} MB, resuming"
              next  # Re-read chunk from new position at top of while loop
            else
              raise "Upload session dead after server error"
            end
          elsif res.code.to_i == 404 || res.code.to_i == 410
            # 404/410 — session gone, need to restart entirely (per Google docs)
            raise "Upload session dead"
          elsif res.code.to_i == 401
            # Token expired mid-upload — refresh and retry the same chunk
            token_refresh_count += 1
            raise "Repeated 401 errors after #{token_refresh_count} token refreshes" if token_refresh_count > 3
            log "WARN", "Token expired (401), refreshing and retrying chunk (refresh #{token_refresh_count})..."
            token = get_access_token(json_key_path, "https://www.googleapis.com/auth/androidpublisher")
            token_created_at = Time.now.to_i
            next  # Re-enter outer loop at same offset, chunk_retry resets
          else
            # 400-level errors (including FAILED_PRECONDITION) — check session status before giving up
            log "WARN", "Chunk upload returned #{res.code}: #{res.body}"
            log "INFO", "Checking upload session status..."
            sleep(5)
            status = check_upload_status(upload_uri, file_size, token)
            if status[:status] == :complete
              log "OK", "Upload was already complete despite error response!"
              return status[:body], current_edit_id, token
            elsif status[:status] == :incomplete
              uploaded = status[:uploaded]
              log "INFO", "Server has #{(uploaded / 1024.0 / 1024.0).round(1)} MB, resuming from there"
              next
            else
              raise "Upload session dead"
            end
          end

          # Progress
          elapsed = Time.now - start_time
          speed = uploaded / elapsed / 1024.0 / 1024.0
          percent = (uploaded * 100.0 / file_size).round(1)
          write_progress(percent)
          log "INFO", "Progress: #{percent}% (#{(uploaded / 1024.0 / 1024.0).round(1)}/#{(file_size / 1024.0 / 1024.0).round(1)} MB) @ #{speed.round(2)} MB/s"
        end
      end

      raise "Upload finished but no success response received"

    rescue => e
      if e.message.include?("session dead") && session_retry < max_session_retries
        session_retry += 1
        wait_time = session_retry * 30
        log "WARN", "Upload session lost: #{e.message}"
        log "INFO", "Creating new edit and restarting upload in #{wait_time}s (attempt #{session_retry}/#{max_session_retries})..."
        delete_play_edit(token, package, current_edit_id)
        sleep(wait_time)
        # Refresh token and create a new edit for the retry
        token = get_access_token(json_key_path, "https://www.googleapis.com/auth/androidpublisher")
        token_created_at = Time.now.to_i
        current_edit_id = create_play_edit(token, package)
        log "OK", "Created new edit: #{current_edit_id}"

        # Check if the bundle already made it to Google's storage despite the error
        # (common when upload was near-complete and failed during server-side validation)
        existing = list_bundles(token, package, current_edit_id)
        new_bundles = existing - bundles_before
        if new_bundles.any?
          log "INFO", "New bundle detected in storage: #{new_bundles.join(', ')}"
          # Return nil result — caller should use the new version code
          return nil, current_edit_id, token
        end
        if existing.any?
          log "INFO", "Only pre-existing bundles (#{existing.length}), upload did not complete — re-uploading..."
        else
          log "INFO", "No bundles found, re-uploading..."
        end
        next
      else
        raise e
      end
    end
  end

  raise "Upload failed after #{max_session_retries} session retries"
end

def assign_to_track(token, package, edit_id, version_code, track, status)
  uri = URI("https://androidpublisher.googleapis.com/androidpublisher/v3/applications/#{package}/edits/#{edit_id}/tracks/#{track}")
  req = Net::HTTP::Put.new(uri)
  req["Authorization"] = "Bearer #{token}"
  req["Content-Type"] = "application/json"
  req.body = JSON.generate({ track: track, releases: [{ versionCodes: [version_code], status: status }] })
  res = Net::HTTP.start(uri.host, uri.port, use_ssl: true) { |http| http.request(req) }
  raise "Failed to assign track: #{res.body}" unless res.is_a?(Net::HTTPSuccess)
end

def commit_edit(token, package, edit_id, changes_not_sent_for_review: false)
  uri = URI("https://androidpublisher.googleapis.com/androidpublisher/v3/applications/#{package}/edits/#{edit_id}:commit")
  uri.query = "changesNotSentForReview=true" if changes_not_sent_for_review
  req = Net::HTTP::Post.new(uri)
  req["Authorization"] = "Bearer #{token}"
  res = Net::HTTP.start(uri.host, uri.port, use_ssl: true) { |http| http.request(req) }
  raise "Failed to commit edit: #{res.body}" unless res.is_a?(Net::HTTPSuccess)
end

def upload_native_symbols(token, package, edit_id, version_code, symbols_path)
  file_size = File.size(symbols_path)
  log "INFO", "Uploading native symbols: #{(file_size / 1024.0 / 1024.0).round(1)} MB"

  # Initiate resumable upload for native symbols
  init_uri = URI("https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/#{package}/edits/#{edit_id}/apks/#{version_code}/deobfuscationFiles/nativeCode?uploadType=resumable")
  init_req = Net::HTTP::Post.new(init_uri)
  init_req["Authorization"] = "Bearer #{token}"
  init_req["Content-Type"] = "application/json"
  init_req["X-Upload-Content-Type"] = "application/octet-stream"
  init_req["X-Upload-Content-Length"] = file_size.to_s
  init_req.body = "{}"

  init_res = Net::HTTP.start(init_uri.host, init_uri.port, use_ssl: true) { |http| http.request(init_req) }
  raise "Failed to initiate symbols upload: #{init_res.code} #{init_res.body}" unless init_res.is_a?(Net::HTTPSuccess)

  upload_uri = URI(init_res["Location"])

  # Upload the entire file
  File.open(symbols_path, "rb") do |file|
    req = Net::HTTP::Put.new(upload_uri)
    req["Authorization"] = "Bearer #{token}"
    req["Content-Type"] = "application/octet-stream"
    req["Content-Length"] = file_size.to_s
    req.body = file.read

    http = Net::HTTP.new(upload_uri.host, upload_uri.port)
    http.use_ssl = true
    http.read_timeout = 600
    http.write_timeout = 600

    res = http.request(req)
    raise "Failed to upload symbols: #{res.code} #{res.body}" unless res.is_a?(Net::HTTPSuccess)
  end

  log "OK", "Native symbols uploaded"
end

def find_symbols_file(build_dir)
  # Look for *.symbols.zip (Unity naming) or symbols.zip
  Dir.glob(File.join(build_dir, "*.symbols.zip")).first ||
    Dir.glob(File.join(build_dir, "symbols.zip")).first
end

begin

json_key = ENV["SUPPLY_JSON_KEY"] or raise "SUPPLY_JSON_KEY environment variable not set"
package = ARGV[0] or raise "Missing argument: package"
aab_file = ARGV[1] or raise "Missing argument: aab_file"
track = ARGV[2] || "internal"
status = ARGV[3] || "completed"

raise "AAB file not found: #{aab_file}" unless File.exist?(aab_file)
file_size = File.size(aab_file)

log "INFO", "=== Google Play Upload ==="
log "INFO", "Package: #{package}"
log "INFO", "AAB: #{aab_file}"
log "INFO", "Size: #{(file_size / 1024.0 / 1024.0).round(1)} MB"
log "INFO", "Track: #{track}"

play_token = get_access_token(json_key, "https://www.googleapis.com/auth/androidpublisher")
log "OK", "Got Google Play access token"

edit_id = create_play_edit(play_token, package)
log "OK", "Created Play edit: #{edit_id}"

begin
  result, edit_id, play_token = upload_bundle(play_token, package, edit_id, aab_file, json_key)

  if result.nil?
    # Bundle made it to storage despite the upload error (detected by upload_bundle via before/after comparison)
    # List bundles and use the highest version code (which should be our newly uploaded bundle)
    existing = list_bundles(play_token, package, edit_id)
    raise "Bundle reported as uploaded but no bundles found in edit" if existing.empty?
    version_code = existing.max
    log "OK", "Using bundle from storage, version code: #{version_code}"
  else
    version_code = result["versionCode"]
    raise "No versionCode in upload result" unless version_code
    log "OK", "Bundle uploaded, version code: #{version_code}"
  end

  # Post-upload steps: assign track, upload symbols, commit
  # If edit expires here, we can retry with a new edit without re-uploading.
  # Per Google docs: uploaded bundles go into a "storage area" and can be
  # assigned to a track in "this or a subsequent edit".
  max_post_upload_retries = 3
  post_upload_retry = 0

  build_dir = File.dirname(aab_file)
  symbols_file = find_symbols_file(build_dir)

  # Fast path: assign to track and commit (both instant, minimal edit exposure)
  while true
    begin
      assign_to_track(play_token, package, edit_id, version_code, track, status)
      log "OK", "Assigned to #{track} track"

      begin
        commit_edit(play_token, package, edit_id)
      rescue => commit_err
        if commit_err.message.include?("changesNotSentForReview") || commit_err.message.include?("Changes cannot be sent for review")
          log "WARN", "Commit rejected — retrying with changesNotSentForReview=true"
          commit_edit(play_token, package, edit_id, changes_not_sent_for_review: true)
        else
          raise commit_err
        end
      end
      log "OK", "Edit committed"
      log "SUCCESS", "Google Play upload complete!"
      break
    rescue => e
      is_edit_expired = e.message.include?("edit has expired") || e.message.include?("Edit has been deleted")
      is_review_issue = e.message.include?("changesNotSentForReview") || e.message.include?("Changes cannot be sent for review")
      post_upload_retry += 1

      if is_review_issue && post_upload_retry <= max_post_upload_retries
        log "WARN", "Review state issue (attempt #{post_upload_retry}/#{max_post_upload_retries}), retrying with changesNotSentForReview..."
        delete_play_edit(play_token, package, edit_id)
        sleep(5)
        play_token = get_access_token(json_key, "https://www.googleapis.com/auth/androidpublisher")
        edit_id = create_play_edit(play_token, package)
        log "OK", "Created new edit: #{edit_id}, reassigning version code #{version_code}"
      elsif is_edit_expired && post_upload_retry <= max_post_upload_retries
        log "WARN", "Edit expired post-upload (attempt #{post_upload_retry}/#{max_post_upload_retries}), creating new edit to reassign..."
        delete_play_edit(play_token, package, edit_id)
        sleep(5)
        play_token = get_access_token(json_key, "https://www.googleapis.com/auth/androidpublisher")
        edit_id = create_play_edit(play_token, package)
        log "OK", "Created new edit: #{edit_id}, reassigning version code #{version_code}"
      else
        raise e
      end
    end
  end

  # Upload native symbols in a separate edit (non-blocking, best-effort)
  # This runs after the release is committed so it can't block the deployment
  if symbols_file
    begin
      log "INFO", "Uploading native symbols in separate edit..."
      play_token = get_access_token(json_key, "https://www.googleapis.com/auth/androidpublisher")
      sym_edit_id = create_play_edit(play_token, package)
      upload_native_symbols(play_token, package, sym_edit_id, version_code, symbols_file)
      commit_edit(play_token, package, sym_edit_id)
      log "OK", "Native symbols committed"
    rescue => sym_err
      if sym_err.message.include?("added previously") || sym_err.message.include?("can't be modified")
        log "INFO", "Native symbols already uploaded for this version code"
      else
        log "WARN", "Native symbols upload failed (non-fatal): #{sym_err.message}"
      end
      delete_play_edit(play_token, package, sym_edit_id) rescue nil
    end
  else
    log "INFO", "No symbols.zip found, skipping native symbols upload"
  end
rescue => e
  # Clean up the stale edit so it doesn't block future uploads
  log "WARN", "Upload failed: #{e.message}"
  delete_play_edit(play_token, package, edit_id)
  raise e
end

puts "EXIT_CODE:0"

rescue => e
  log "ERROR", e.message
  puts "EXIT_CODE:1"
  exit 1
end
