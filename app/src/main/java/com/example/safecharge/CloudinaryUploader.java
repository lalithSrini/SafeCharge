package com.example.safecharge;

import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;

/**
 * Uploads captured thief photos to Cloudinary (free tier) and returns
 * their permanent public download URLs used by EmailManager to embed
 * clickable links in the theft alert email.
 *
 * WHY CLOUDINARY INSTEAD OF FIREBASE STORAGE:
 *   Firebase Storage requires the Blaze (pay-as-you-go) billing plan.
 *   If the billing account becomes delinquent (unpaid/expired card),
 *   Firebase returns HTTP 402 on every download URL and photos cannot
 *   be viewed even though they were uploaded successfully. Cloudinary's
 *   free tier (25 GB storage, 25 GB bandwidth/month) has no credit card
 *   requirement and never enters a delinquent state.
 *
 * HOW IT WORKS:
 *   Cloudinary's unsigned upload endpoint accepts a standard HTTP
 *   multipart/form-data POST — no SDK, no authentication token, no
 *   Firebase dependency. We open a FileInputStream (works fine from
 *   private app storage, unlike Uri.fromFile()), stream the bytes,
 *   and parse the "secure_url" from the JSON response.
 *
 * SETUP (one-time):
 *   1. Create a free account at https://cloudinary.com/users/register/free
 *   2. Dashboard → Settings → Upload → Upload presets → Add upload preset
 *      • Signing mode: Unsigned
 *      • Folder: thief-photos   (optional, keeps uploads organised)
 *      • Save → note the preset name (e.g. "thief_preset")
 *   3. Dashboard → note your Cloud Name (e.g. "dxxxxxxxx")
 *   4. Add to local.properties (never commit this file):
 *        CLOUDINARY_CLOUD_NAME=dxxxxxxxx
 *        CLOUDINARY_UPLOAD_PRESET=thief_preset
 *   5. build.gradle.kts already injects them via BuildConfig —
 *      add the two buildConfigField lines shown in the comment below.
 *
 * build.gradle.kts additions (inside defaultConfig { ... }):
 *   buildConfigField("String", "CLOUDINARY_CLOUD_NAME",    "\"${localProp("CLOUDINARY_CLOUD_NAME")}\"")
 *   buildConfigField("String", "CLOUDINARY_UPLOAD_PRESET", "\"${localProp("CLOUDINARY_UPLOAD_PRESET")}\"")
 *
 * You can also REMOVE the Firebase dependencies from build.gradle.kts:
 *   // implementation(platform("com.google.firebase:firebase-bom:34.12.0"))
 *   // implementation("com.google.firebase:firebase-storage")
 *   // implementation("com.google.firebase:firebase-auth")
 * And remove  id("com.google.gms.google-services")  from the plugins block.
 */
public class CloudinaryUploader {

    private static final String TAG = "CloudinaryUploader";

    // Injected at compile time from local.properties via BuildConfig
    private static final String CLOUD_NAME    = BuildConfig.CLOUDINARY_CLOUD_NAME;
    private static final String UPLOAD_PRESET = BuildConfig.CLOUDINARY_UPLOAD_PRESET;

    // Cloudinary unsigned upload endpoint
    // Full URL: https://api.cloudinary.com/v1_1/{cloud_name}/image/upload
    private static final String UPLOAD_URL =
            "https://api.cloudinary.com/v1_1/" + CLOUD_NAME + "/image/upload";

    /** Timeout per photo upload in seconds. */
    private static final long TIMEOUT_PER_PHOTO_S = 60L;

    /** Total timeout for all uploads combined. */
    private static final long TOTAL_TIMEOUT_S = 120L;

    /** Multipart boundary string. */
    private static final String BOUNDARY = "----CloudinaryBoundary7MA4YWxk";

    /** Thread pool for concurrent uploads — speeds up 10-photo batches. */
    private static final ExecutorService UPLOAD_EXECUTOR =
            Executors.newFixedThreadPool(3); // 3 concurrent uploads max

    public interface UploadCallback {
        /** Called on a background thread with all successfully uploaded URLs. */
        void onComplete(List<String> downloadUrls);
    }

    /**
     * Uploads all photos to Cloudinary concurrently (max 3 at a time)
     * and returns their permanent public HTTPS URLs.
     *
     * Blocking — call from a background thread. Returns when all uploads
     * complete or the total timeout expires.
     *
     * @param photos   list of .jpg files from context.getFilesDir()/ChargeProtect/
     * @param callback receives the list of public HTTPS download URLs
     */
    public static void uploadPhotos(List<File> photos, UploadCallback callback) {
        if (photos == null || photos.isEmpty()) {
            callback.onComplete(new ArrayList<>());
            return;
        }

        Log.d(TAG, "Starting Cloudinary upload for " + photos.size() + " photo(s)...");

        List<String>   urls  = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(photos.size());

        for (File photo : photos) {
            UPLOAD_EXECUTOR.execute(() -> {
                try {
                    String url = uploadOne(photo);
                    if (url != null) {
                        urls.add(url);
                        Log.d(TAG, "Uploaded: " + photo.getName() + " → " + url);
                    } else {
                        Log.w(TAG, "No URL returned for: " + photo.getName());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Upload failed for " + photo.getName()
                            + " — " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            boolean done = latch.await(TOTAL_TIMEOUT_S, TimeUnit.SECONDS);
            if (!done) {
                Log.w(TAG, "Upload timed out — partial results: "
                        + urls.size() + "/" + photos.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Upload interrupted.");
        }

        Log.d(TAG, "Upload complete: " + urls.size() + "/" + photos.size() + " URLs.");
        callback.onComplete(new ArrayList<>(urls));
    }

    /**
     * Uploads a single photo to Cloudinary via multipart/form-data POST.
     *
     * @param photo the .jpg file to upload (from private app storage)
     * @return the permanent public HTTPS URL, or null if upload failed
     */
    private static String uploadOne(File photo) throws Exception {
        // ── Build multipart body ──────────────────────────────────────────────
        // Cloudinary unsigned upload requires two fields:
        //   upload_preset  — the unsigned preset name from your dashboard
        //   file           — the image bytes
        // Optionally:
        //   folder         — organises uploads in the Cloudinary media library
        //   public_id      — sets the filename in Cloudinary

        String lineEnd   = "\r\n";
        String twoHyphens = "--";

        URL url = new URL(UPLOAD_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setUseCaches(false);
        conn.setDoOutput(true);
        conn.setDoInput(true);
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout((int) (TIMEOUT_PER_PHOTO_S * 1000));
        conn.setRequestProperty("Connection",   "Keep-Alive");
        conn.setRequestProperty("Cache-Control","no-cache");
        conn.setRequestProperty("Content-Type",
                "multipart/form-data; boundary=" + BOUNDARY);

        try (DataOutputStream dos = new DataOutputStream(conn.getOutputStream())) {

            // Field: upload_preset
            dos.writeBytes(twoHyphens + BOUNDARY + lineEnd);
            dos.writeBytes("Content-Disposition: form-data; name=\"upload_preset\""
                    + lineEnd + lineEnd);
            dos.writeBytes(UPLOAD_PRESET + lineEnd);

            // Field: folder (keeps thief photos organised in Cloudinary)
            dos.writeBytes(twoHyphens + BOUNDARY + lineEnd);
            dos.writeBytes("Content-Disposition: form-data; name=\"folder\""
                    + lineEnd + lineEnd);
            dos.writeBytes("thief-photos" + lineEnd);

            // Field: public_id (use filename without extension as the Cloudinary ID)
            String publicId = photo.getName().replace(".jpg", "");
            dos.writeBytes(twoHyphens + BOUNDARY + lineEnd);
            dos.writeBytes("Content-Disposition: form-data; name=\"public_id\""
                    + lineEnd + lineEnd);
            dos.writeBytes(publicId + lineEnd);

            // Field: file (the actual image bytes)
            dos.writeBytes(twoHyphens + BOUNDARY + lineEnd);
            dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\""
                    + photo.getName() + "\"" + lineEnd);
            dos.writeBytes("Content-Type: image/jpeg" + lineEnd + lineEnd);

            // Stream the file bytes directly from private storage —
            // no Uri.fromFile() needed, no permission issues on Android 7+
            try (FileInputStream fis = new FileInputStream(photo)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    dos.write(buffer, 0, bytesRead);
                }
            }

            dos.writeBytes(lineEnd);
            dos.writeBytes(twoHyphens + BOUNDARY + twoHyphens + lineEnd);
            dos.flush();
        }

        // ── Read response ─────────────────────────────────────────────────────
        int responseCode = conn.getResponseCode();
        StringBuilder response = new StringBuilder();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(
                        responseCode == 200
                                ? conn.getInputStream()
                                : conn.getErrorStream(),
                        StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) response.append(line);
        }

        conn.disconnect();

        if (responseCode != 200) {
            Log.e(TAG, "Cloudinary HTTP " + responseCode + " for "
                    + photo.getName() + ": " + response);
            return null;
        }

        // ── Parse JSON → extract secure_url ──────────────────────────────────
        // Cloudinary response:
        // {
        //   "public_id": "thief-photos/thief_front_20260510_102521_0",
        //   "secure_url": "https://res.cloudinary.com/{cloud}/image/upload/v.../thief-photos/xxx.jpg",
        //   "url": "http://res.cloudinary.com/...",
        //   ...
        // }
        // We always use "secure_url" (HTTPS) so the link works in all email clients.
        JSONObject json = new JSONObject(response.toString());
        if (json.has("secure_url")) {
            return json.getString("secure_url");
        }

        Log.e(TAG, "No secure_url in Cloudinary response: " + response);
        return null;
    }
}