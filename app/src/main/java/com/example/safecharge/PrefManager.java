package com.example.safecharge;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Single source of truth for all persisted settings.
 *
 * Changes in this version:
 *  - Added getDismissPin / setDismissPin for the PIN fallback in AlarmActivity.
 *  - Added getPhotoRetentionDays / setPhotoRetentionDays for the auto-cleanup policy.
 *  - Added getLastUploadDay / setLastUploadDay for Firebase daily-upload cap.
 *  - Added getUploadCountToday / setUploadCountToday for Firebase daily-upload cap.
 */
public class PrefManager {

    private static final String PREF_NAME            = "settings";
    private static final String KEY_ALERT            = "alert";
    private static final String KEY_MOTION           = "motion";
    private static final String KEY_ARMED            = "armed";
    private static final String KEY_CAMERA           = "camera";
    private static final String KEY_PHOTO_COUNT     = "photo_count";
    private static final String KEY_MOTION_THRESHOLD = "motion_threshold";
    private static final String KEY_RECIPIENT_EMAIL_1 = "recipient_email_1";
    private static final String KEY_RECIPIENT_EMAIL_2 = "recipient_email_2";

    // PIN fallback for AlarmActivity
    private static final String KEY_DISMISS_PIN      = "dismiss_pin";

    // Photo retention (days). 0 = keep forever.
    private static final String KEY_PHOTO_RETENTION_DAYS = "photo_retention_days";

    // Firebase daily upload cap
    private static final String KEY_LAST_UPLOAD_DAY    = "last_upload_day";
    private static final String KEY_UPLOAD_COUNT_TODAY  = "upload_count_today";
    private static final String KEY_ALERT_HISTORY       = "alert_history";

    /** Default: 12 m/s² — above gravity (9.8), below a strong shake */
    public static final float DEFAULT_MOTION_THRESHOLD   = 12.0f;
    /** Default: keep photos for 30 days, then auto-delete. */
    public static final int   DEFAULT_PHOTO_RETENTION_DAYS = 30;
    /** Default: allow at most 10 Firebase upload sessions per day. */
    public static final int   DEFAULT_MAX_UPLOADS_PER_DAY  = 10;

    private final SharedPreferences pref;

    public PrefManager(Context context) {
        pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // ── Alerts ────────────────────────────────────────────────────────────────
    public void setAlertEnabled(boolean value) { pref.edit().putBoolean(KEY_ALERT, value).apply(); }
    public boolean isAlertEnabled()            { return pref.getBoolean(KEY_ALERT, false); }

    // ── Motion ────────────────────────────────────────────────────────────────
    public void setMotionEnabled(boolean value) { pref.edit().putBoolean(KEY_MOTION, value).apply(); }
    public boolean isMotionEnabled()            { return pref.getBoolean(KEY_MOTION, false); }

    // ── Motion sensitivity (m/s²) ─────────────────────────────────────────────
    public void setMotionThreshold(float value) { pref.edit().putFloat(KEY_MOTION_THRESHOLD, value).apply(); }
    public float getMotionThreshold()           { return pref.getFloat(KEY_MOTION_THRESHOLD, DEFAULT_MOTION_THRESHOLD); }

    // ── Armed state ───────────────────────────────────────────────────────────
    public void setArmed(boolean value) { pref.edit().putBoolean(KEY_ARMED, value).apply(); }
    public boolean isArmed()            { return pref.getBoolean(KEY_ARMED, false); }

    // ── Camera capture ────────────────────────────────────────────────────────
    public void setCameraEnabled(boolean value) { pref.edit().putBoolean(KEY_CAMERA, value).apply(); }
    public boolean isCameraEnabled()            { return pref.getBoolean(KEY_CAMERA, false); }

    public void setPhotoCountPerTrigger(int value) { pref.edit().putInt(KEY_PHOTO_COUNT, value).apply(); }
    public int  getPhotoCountPerTrigger()          { return pref.getInt(KEY_PHOTO_COUNT, 5); }

    // ── Alert recipient emails ────────────────────────────────────────────────
    public void setRecipientEmail1(String value) { pref.edit().putString(KEY_RECIPIENT_EMAIL_1, value.trim()).apply(); }
    public String getRecipientEmail1()           { return pref.getString(KEY_RECIPIENT_EMAIL_1, ""); }

    public void setRecipientEmail2(String value) { pref.edit().putString(KEY_RECIPIENT_EMAIL_2, value.trim()).apply(); }
    public String getRecipientEmail2()           { return pref.getString(KEY_RECIPIENT_EMAIL_2, ""); }

    // ── Dismiss PIN (for AlarmActivity PIN fallback) ──────────────────────────
    /** Returns the stored dismiss PIN, or "" if none has been set. */
    public String getDismissPin()             { return pref.getString(KEY_DISMISS_PIN, ""); }
    public void   setDismissPin(String value) { pref.edit().putString(KEY_DISMISS_PIN, value.trim()).apply(); }

    // ── Photo retention policy ────────────────────────────────────────────────
    /** Number of days to keep captured photos. 0 = keep forever. */
    public int  getPhotoRetentionDays()          { return pref.getInt(KEY_PHOTO_RETENTION_DAYS, DEFAULT_PHOTO_RETENTION_DAYS); }
    public void setPhotoRetentionDays(int value) { pref.edit().putInt(KEY_PHOTO_RETENTION_DAYS, value).apply(); }

    // ── Firebase daily upload cap ─────────────────────────────────────────────
    /** Calendar day string (yyyyMMdd) of the last upload session. */
    public String getLastUploadDay()            { return pref.getString(KEY_LAST_UPLOAD_DAY, ""); }
    public void   setLastUploadDay(String day)  { pref.edit().putString(KEY_LAST_UPLOAD_DAY, day).apply(); }

    /** Number of upload sessions started today. */
    public int  getUploadCountToday()          { return pref.getInt(KEY_UPLOAD_COUNT_TODAY, 0); }
    public void setUploadCountToday(int count) { pref.edit().putInt(KEY_UPLOAD_COUNT_TODAY, count).apply(); }

    // ── Alert History ─────────────────────────────────────────────────────────

    public String getAlertHistoryJson() {
        return pref.getString(KEY_ALERT_HISTORY, "[]");
    }

    public void addAlertEvent(String type, double lat, double lon) {
        try {
            String json = getAlertHistoryJson();
            org.json.JSONArray array = new org.json.JSONArray(json);
            
            org.json.JSONObject event = new org.json.JSONObject();
            event.put("id", System.currentTimeMillis()); // unique ID for photo association
            event.put("type", type);
            event.put("time", System.currentTimeMillis());
            event.put("lat", lat);
            event.put("lon", lon);
            event.put("photos", new org.json.JSONArray());
            
            // Keep only last 50 events to prevent bloat
            if (array.length() >= 50) {
                org.json.JSONArray newArray = new org.json.JSONArray();
                for (int i = 1; i < array.length(); i++) newArray.put(array.get(i));
                array = newArray;
            }
            
            array.put(event);
            pref.edit().putString(KEY_ALERT_HISTORY, array.toString()).apply();
        } catch (Exception ignored) {}
    }

    public void updateLatestAlertLocation(double lat, double lon) {
        try {
            String json = getAlertHistoryJson();
            org.json.JSONArray array = new org.json.JSONArray(json);
            if (array.length() == 0) return;

            org.json.JSONObject latest = array.getJSONObject(array.length() - 1);
            latest.put("lat", lat);
            latest.put("lon", lon);

            pref.edit().putString(KEY_ALERT_HISTORY, array.toString()).apply();
        } catch (Exception ignored) {}
    }

    public void addPhotoToLatestAlert(String photoName) {
        try {
            String json = getAlertHistoryJson();
            org.json.JSONArray array = new org.json.JSONArray(json);
            if (array.length() == 0) return;

            // Add to the very last alert (the most recent one)
            org.json.JSONObject latest = array.getJSONObject(array.length() - 1);
            org.json.JSONArray photos = latest.optJSONArray("photos");
            if (photos == null) photos = new org.json.JSONArray();
            photos.put(photoName);
            latest.put("photos", photos);

            pref.edit().putString(KEY_ALERT_HISTORY, array.toString()).apply();
        } catch (Exception ignored) {}
    }

    public void removeAlertEvent(long id) {
        try {
            String json = getAlertHistoryJson();
            org.json.JSONArray array = new org.json.JSONArray(json);
            org.json.JSONArray newArray = new org.json.JSONArray();
            
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject obj = array.getJSONObject(i);
                // Use 'time' as fallback if 'id' is missing (matches AlertsFragment logic)
                long alertId = obj.optLong("id", obj.optLong("time", -1));
                if (alertId != id) {
                    newArray.put(obj);
                }
            }
            
            pref.edit().putString(KEY_ALERT_HISTORY, newArray.toString()).apply();
        } catch (Exception ignored) {}
    }

    public void removeAlertEvents(java.util.Set<Long> ids) {
        try {
            String json = getAlertHistoryJson();
            org.json.JSONArray array = new org.json.JSONArray(json);
            org.json.JSONArray newArray = new org.json.JSONArray();

            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject obj = array.getJSONObject(i);
                // Use 'time' as fallback if 'id' is missing (matches AlertsFragment logic)
                long alertId = obj.optLong("id", obj.optLong("time", -1));
                if (!ids.contains(alertId)) {
                    newArray.put(obj);
                }
            }

            pref.edit().putString(KEY_ALERT_HISTORY, newArray.toString()).apply();
        } catch (Exception ignored) {}
    }
}