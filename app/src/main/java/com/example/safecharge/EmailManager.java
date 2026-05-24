package com.example.safecharge;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class EmailManager {

    private static final String TAG = "EmailManager";

    private static final String SERVICE_ID  = BuildConfig.EMAILJS_SERVICE_ID;
    private static final String TEMPLATE_ID = BuildConfig.EMAILJS_TEMPLATE_ID;
    private static final String PUBLIC_KEY  = BuildConfig.EMAILJS_PUBLIC_KEY;

    private static final String API_URL = "https://api.emailjs.com/api/v1.0/email/send";
    private static final String FOLDER  = "SafeCharge";

    private static final int MAX_PHOTOS      = 10;
    private static final int CONNECT_TIMEOUT = 20_000;
    private static final int READ_TIMEOUT    = 30_000;

    public interface SendCallback {
        void onSuccess();
        void onFailure(String errorMessage);
    }

    private final Context         context;
    private final PrefManager     pref;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public EmailManager(Context context) {
        this.context = context.getApplicationContext();
        this.pref    = new PrefManager(context);
    }

    public void sendAlertEmail(double latitude, double longitude, boolean isFollowUp) {
        sendAlertEmail(latitude, longitude, isFollowUp, false, null);
    }

    public void sendAlertEmail(double latitude, double longitude,
                               boolean isFollowUp, boolean alarmStopped) {
        sendAlertEmail(latitude, longitude, isFollowUp, alarmStopped, null);
    }

    public void sendAlertEmail(double latitude, double longitude,
                               boolean isFollowUp, SendCallback callback) {
        sendAlertEmail(latitude, longitude, isFollowUp, false, callback);
    }

    public void sendAlertEmail(double latitude, double longitude,
                               boolean isFollowUp, boolean alarmStopped,
                               SendCallback callback) {
        executor.execute(() -> {
            try {
                doSend(latitude, longitude, isFollowUp, alarmStopped);
                if (callback != null) callback.onSuccess();
            } catch (Exception e) {
                Log.e(TAG, "Exception during send: " + e.getClass().getSimpleName()
                        + " — " + e.getMessage(), e);
                if (callback != null) callback.onFailure(e.getMessage());
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
    }

    private void doSend(double latitude, double longitude, boolean isFollowUp, boolean alarmStopped) throws Exception {

        // 1. Recipients
        List<String> recipients = new ArrayList<>();
        String r1 = pref.getRecipientEmail1().trim();
        String r2 = pref.getRecipientEmail2().trim();
        if (!r1.isEmpty()) recipients.add(r1);
        if (!r2.isEmpty()) recipients.add(r2);
        if (recipients.isEmpty()) {
            throw new Exception("No recipients set — open the app and fill in Recipient 1.");
        }

        // 2. Location strings
        String locationText, mapsUrl;
        if (latitude == 0 && longitude == 0) {
            locationText = "GPS unavailable at time of alert.";
            mapsUrl      = "N/A";
        } else {
            mapsUrl      = "https://maps.google.com/?q=" + latitude + "," + longitude;
            locationText = latitude + ", " + longitude;
        }
        Log.d(TAG, "Location: " + locationText);

        // 3. Upload photos to Firebase → collect plain clickable links
        String photoLinks = "";
        int    photoCount = 0;

        if (isFollowUp) {
            List<File> photos = getRecentPhotos(MAX_PHOTOS);
            photoCount = photos.size();

            if (photoCount > 0) {
                Log.d(TAG, "Uploading " + photoCount + " photo(s) to Firebase...");

                // FIX: The original code used a bare array element (urlHolder[0])
                // written on FirebaseUploader's CALLBACK_EXECUTOR thread and read
                // on this executor thread immediately after uploadPhotos() returned.
                // Java Memory Model gives NO visibility guarantee across threads
                // without a synchronisation action, so urlHolder[0] could still
                // hold the empty ArrayList and photoLinks would be "" — causing
                // the email to arrive with no image links even though photos were
                // uploaded successfully.
                //
                // Fix: AtomicReference guarantees visibility of the set() across
                // threads. The CountDownLatch's countDown() → await() pair adds the
                // required happens-before edge so the get() below always sees the
                // fully-populated list that the callback wrote.
                final AtomicReference<List<String>> urlRef =
                        new AtomicReference<>(new ArrayList<>());
                final CountDownLatch uploadLatch = new CountDownLatch(1);

                CloudinaryUploader.uploadPhotos(photos, downloadUrls -> {
                    urlRef.set(downloadUrls);   // visible to all threads via AtomicReference
                    uploadLatch.countDown();     // establishes happens-before with await() below
                });

                try {
                    boolean completed = uploadLatch.await(120, TimeUnit.SECONDS);
                    if (!completed) {
                        Log.w(TAG, "uploadPhotos() did not signal within 120s — "
                                + "photo links may be incomplete.");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.w(TAG, "Interrupted while waiting for Firebase uploads.");
                }

                List<String> downloadUrls = urlRef.get(); // guaranteed visible after await()
                if (!downloadUrls.isEmpty()) {
                    photoLinks = buildPhotoLinksText(downloadUrls);
                    photoCount = downloadUrls.size();
                    Log.d(TAG, "Built links for " + photoCount + " photo(s).");
                } else {
                    Log.w(TAG, "No Firebase URLs — sending without photo links.");
                }
            } else {
                Log.d(TAG, "Follow-up email — no photos found on disk.");
            }
        } else {
            Log.d(TAG, "Immediate/test email — skipping photos.");
        }

        // 4. Message body
        String message;
        if (!isFollowUp) {
            message = "\u26A0\uFE0F Theft alarm was triggered on your phone!";
        } else if (alarmStopped) {
            // Alarm was manually stopped — tell owner exactly how many photos
            // were captured before the alarm was stopped, even if just 1.
            if (photoCount > 0) {
                message = "\uD83D\uDED1 Alarm was stopped. " + photoCount
                        + " photo(s) captured before alarm was stopped. Tap links below to view.";
            } else {
                message = "\uD83D\uDED1 Alarm was stopped. No photos were captured before stopping.";
            }
        } else {
            message = "\uD83D\uDCF7 " + photoCount + " photo(s) captured. Tap links below to view.";
        }

        // 5. Send to each recipient
        for (String recipient : recipients) {
            sendOne(recipient, locationText, mapsUrl, message, photoCount, photoLinks);
        }
    }

    private void sendOne(String recipient,
                         String locationText,
                         String mapsUrl,
                         String message,
                         int photoCount,
                         String photoLinks) throws Exception {

        JSONObject params = new JSONObject();
        params.put("to_email",    recipient);
        params.put("from_name",   "SafeCharge"); // Professional sender name
        params.put("reply_to",    "noreply@safecharge.security"); 
        params.put("time",        new java.text.SimpleDateFormat(
                "dd MMM yyyy, hh:mm:ss a", java.util.Locale.getDefault())
                .format(new java.util.Date()));
        params.put("message",     message);
        params.put("location",    locationText);
        params.put("maps_url",    mapsUrl);
        params.put("photo_count", photoCount + " photo(s)");
        params.put("photo_links", photoLinks);

        JSONObject body = new JSONObject();
        body.put("service_id",      SERVICE_ID);
        body.put("template_id",     TEMPLATE_ID);
        body.put("user_id",         PUBLIC_KEY);
        // Note: EmailJS sometimes expects 'accessToken' or just 'user_id' depending on the API settings.
        // We include both to be safe.
        body.put("accessToken",     PUBLIC_KEY);
        body.put("template_params", params);

        String payloadStr  = body.toString();
        long   payloadSize = payloadStr.getBytes(StandardCharsets.UTF_8).length;
        Log.d(TAG, "Payload size: " + payloadSize + " bytes");

        HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept",       "application/json, text/plain, */*");
        conn.setRequestProperty("Origin",       "http://localhost");
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setDoOutput(true);

        byte[] payload = payloadStr.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(payload.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
            os.flush();
        }

        int    code     = conn.getResponseCode();
        String respBody = readStream(code < 400 ? conn.getInputStream()
                : conn.getErrorStream());
        conn.disconnect();

        Log.d(TAG, "HTTP " + code + " | " + respBody);
        if (code != 200) throw new Exception("HTTP " + code + " — " + respBody);
        Log.d(TAG, "Email sent to " + recipient);
    }

    /**
     * Builds an HTML list of clickable Firebase photo links.
     *
     * Output format (HTML):
     *   <a href="https://...">Photo 1 (Front) - View</a><br>
     *   <a href="https://...">Photo 2 (Back) - View</a><br>
     *
     * IMPORTANT — EmailJS template requirement:
     *   Use TRIPLE braces  {{{photo_links}}}  in your EmailJS template so
     *   EmailJS renders the HTML as-is instead of escaping the angle brackets.
     *   Double braces {{photo_links}} will escape < > and show raw HTML text
     *   instead of clickable links.
     */
    private String buildPhotoLinksText(List<String> urls) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < urls.size(); i++) {
            String url    = urls.get(i);
            String camera = url.contains("front") ? "Front" : "Back";
            sb.append("<a href=\"").append(url).append("\" target=\"_blank\">")
                    .append("Photo ").append(i + 1)
                    .append(" (").append(camera).append(") - View")
                    .append("</a><br>");
        }
        return sb.toString();
    }

    private String readStream(InputStream stream) {
        if (stream == null) return "(empty)";
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) {
            return "(read error: " + e.getMessage() + ")";
        }
    }

    private List<File> getRecentPhotos(int max) {
        List<File> result = new ArrayList<>();
        File dir = new File(context.getFilesDir(), FOLDER);
        if (!dir.exists()) return result;
        File[] files = dir.listFiles(f -> f.getName().endsWith(".jpg"));
        if (files == null) return result;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = 0; i < Math.min(max, files.length); i++) result.add(files[i]);
        return result;
    }
}