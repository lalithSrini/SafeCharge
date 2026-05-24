package com.example.safecharge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ChargingService extends Service implements LifecycleOwner {

    private static final String TAG              = "ChargingService";
    private static final String CHANNEL_ID       = "charge_service";
    private static final String ALARM_CHANNEL_ID = "alarm_channel";
    private static final int    NOTIFICATION_ID  = 1;
    private static final int    ALARM_NOTIF_ID   = 2;

    private static final String FOLDER           = "SafeCharge";

    private LifecycleRegistry lifecycleRegistry;

    private BroadcastReceiver   batteryReceiver;
    private BroadcastReceiver   stopAlarmReceiver;
    private PrefManager         pref;
    private VoiceManager        voice;
    private SirenManager        siren;
    private SensorManager       sensorManager;
    private SensorEventListener motionListener;
    private CameraManager       cameraManager;
    private EmailManager        emailManager;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Guards against sending the initial alert email twice if both
     * charger-unplug and motion fire within the same alarm session.
     */
    private volatile boolean alarmEmailSent = false;

    /**
     * Guards against sending the follow-up email twice for the same "full" completion.
     * We now allow one "early" follow-up (after 1st photo) and one "final" follow-up.
     */
    private volatile boolean followUpEmailSent = false;

    /**
     * Guards against sending the early evidence email twice.
     */
    private int photosCapturedThisSession = 0;

    /**
     * Tracks whether the camera is still capturing photos.
     */
    private volatile boolean cameraCapturing = false;

    /**
     * Set to true when stopAlarmReceiver fires while camera is still running.
     */
    private volatile boolean pendingDestroy = false;

    /**
     * Set to true when the alarm is stopped by the user (PIN entered).
     * Tells sendFollowUpEmailWithPhotos() to use the 'alarm stopped' subject
     * and message so the email clearly states the alarm was stopped and shows
     * however many photos were captured before it was stopped — even if only 1.
     */
    private volatile boolean alarmWasStopped = false;

    /**
     * Tracks the number of photos sent in the last email to avoid sending
     * a final email if no new photos were taken since the 'early' email.
     */
    private int lastPhotoCountSent = 0;

    @Override
    public void onCreate() {
        super.onCreate();

        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
        lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);

        pref          = new PrefManager(this);
        voice         = new VoiceManager(this);
        siren         = new SirenManager(this);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        cameraManager = new CameraManager(this);
        emailManager  = new EmailManager(this);

        startForegroundNow();
        registerBatteryReceiver();
        registerStopAlarmReceiver();
        updateMotionDetection();

        // Run photo cleanup on every service start (cheap — just checks file ages)
        cleanupOldPhotos();
    }

    // ── Motion detection ──────────────────────────────────────────────────────

    public void updateMotionDetection() {
        if (pref.isMotionEnabled()) startMotionDetection();
        else                        stopMotionDetection();
    }

    private void startMotionDetection() {
        if (motionListener != null) stopMotionDetection();

        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer == null) {
            Log.w(TAG, "No accelerometer found on this device — motion detection unavailable.");
            return;
        }

        motionListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (!pref.isMotionEnabled()) return;
                if (siren.isAlarmPlaying())  return;

                float x = event.values[0], y = event.values[1], z = event.values[2];
                double magnitude = Math.sqrt(x * x + y * y + z * z);

                if (magnitude > pref.getMotionThreshold()) {
                    triggerTheftAlarm("Motion detected! Theft alert!", "Suspicious Movement");
                }
            }
            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };

        sensorManager.registerListener(motionListener, accelerometer,
                SensorManager.SENSOR_DELAY_NORMAL);
    }

    private void stopMotionDetection() {
        if (motionListener != null) {
            sensorManager.unregisterListener(motionListener);
            motionListener = null;
        }
    }

    // ── Broadcast receivers ───────────────────────────────────────────────────

    private void registerBatteryReceiver() {
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;

                if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                    if (pref.isAlertEnabled()) voice.speak("Charger connected");

                } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                    if (pref.isArmed()) {
                        triggerTheftAlarm("Warning! Theft detected!", "Charger Disconnected");
                    } else if (pref.isAlertEnabled()) {
                        voice.speak("Charging cable removed");
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(batteryReceiver, filter);
        }
    }

    private void registerStopAlarmReceiver() {
        stopAlarmReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "STOP_ALARM received.");

                if (siren != null) siren.stopAlarm();

                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.cancel(ALARM_NOTIF_ID);

                pref.setArmed(false);
                pref.setAlertEnabled(false);
                pref.setMotionEnabled(false);
                stopMotionDetection();

                // Mark alarm as user-stopped so the follow-up email uses
                // the 'alarm stopped' subject/message variant.
                alarmWasStopped   = true;

                // Reset guards so the stop event always sends an email
                // with whatever photos were captured so far — even just 1 photo.
                photosCapturedThisSession = 0;
                followUpEmailSent  = false;
                lastPhotoCountSent = 0;

                if (cameraCapturing) {
                    // Camera is mid-sequence. stopCapturing() posts onAllDone to the
                    // main handler. onAllDone (in triggerTheftAlarm's callback) will:
                    //   1. set cameraCapturing = false
                    //   2. call sendFollowUpEmailWithPhotos(true) ← sends with partial photos
                    //   3. check pendingDestroy → call stopSelf()
                    // We must NOT call stopSelf() here — that would trigger onDestroy()
                    // which shuts down emailManager before the follow-up email can send.
                    pendingDestroy = true;
                    cameraManager.stopCapturing();
                    // stopSelf() is intentionally omitted — the onAllDone callback handles it.
                } else {
                    // No camera session — send follow-up immediately and stop.
                    sendFollowUpEmailWithPhotos(true);
                    stopSelf();
                }
            }
        };

        IntentFilter filter = new IntentFilter("STOP_ALARM_BROADCAST");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopAlarmReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stopAlarmReceiver, filter);
        }
    }

    // ── Alarm trigger ─────────────────────────────────────────────────────────

    private void triggerTheftAlarm(String voiceMessage, String eventType) {
        voice.speak(voiceMessage);
        siren.startAlarm();
        launchAlarmScreen();

        // 1. Create alert entry immediately so it's the "latest" for incoming photos
        pref.addAlertEvent(eventType, 0, 0);

        // 2. Fetch location and update the entry / send email
        LocationHelper.fetch(this, (lat, lon) -> {
            pref.updateLatestAlertLocation(lat, lon);
            
            final EmailManager em = emailManager;
            if (em != null) {
                em.sendAlertEmail(lat, lon, false);
            }
        });

        photosCapturedThisSession = 0;
        followUpEmailSent = false;

        if (pref.isCameraEnabled()) {
            // CameraManager.captureThiefPhotos() is now idempotent — a second call
            // while a session is active is silently ignored, so both charger-unplug
            // and motion firing in the same session will not start parallel captures.
            cameraCapturing = true;
            cameraManager.captureThiefPhotos(ChargingService.this,
                (photoName) -> {
                    // ON EACH PHOTO SAVED
                    photosCapturedThisSession++;
                    Log.d(TAG, "Photo captured: " + photoName);
                    
                    // Link photo to the alert in history
                    pref.addPhotoToLatestAlert(photoName);

                    // Send email for every 2 photos taken
                    if (photosCapturedThisSession % 2 == 0) {
                        Log.d(TAG, "Batch of 2 photos reached — sending evidence email.");
                        sendFollowUpEmailWithPhotos(false);
                    }
                },
                () -> {
                    // ON ALL DONE: Send final evidence
                    cameraCapturing = false;
                    Log.d(TAG, "Camera capture finished — sending final follow-up email.");
                    sendFollowUpEmailWithPhotos(true);

                    if (pendingDestroy) {
                        Log.d(TAG, "pendingDestroy=true — stopping service after camera.");
                        pendingDestroy = false;
                        stopSelf();
                    }
                }
            );
        }
    }

    private void sendAlertEmailNow() {
        if (alarmEmailSent) {
            Log.d(TAG, "Alert email already sent this session — skipping.");
            return;
        }

        final EmailManager em = emailManager;
        if (em == null) {
            Log.w(TAG, "emailManager is null — cannot send initial alert email.");
            return;
        }

        alarmEmailSent = true;
        Log.d(TAG, "Sending immediate alert email...");
        LocationHelper.fetch(this, (latitude, longitude) ->
                em.sendAlertEmail(latitude, longitude, false));
    }

    private void sendFollowUpEmailWithPhotos(boolean isFinal) {
        if (followUpEmailSent) {
            Log.d(TAG, "Follow-up email already sent — skipping duplicate.");
            return;
        }

        if (emailManager == null) {
            Log.w(TAG, "emailManager is null — skipping follow-up email.");
            return;
        }

        // Check if we have new photos since the last email to avoid spamming the same pics
        int currentPhotoCount = getPhotoCountOnDisk();
        if (currentPhotoCount == 0 && !alarmWasStopped) {
            Log.d(TAG, "No photos on disk and alarm not stopped — skipping follow-up.");
            return;
        }
        
        if (!isFinal && currentPhotoCount == lastPhotoCountSent) {
            Log.d(TAG, "No new photos since last email — skipping early follow-up.");
            return;
        }

        if (isFinal) followUpEmailSent = true;
        lastPhotoCountSent = currentPhotoCount;

        // NOTE: canUploadToday() check intentionally removed for alarm-stopped case.
        // When the alarm is stopped mid-capture, we MUST send whatever photos were
        // taken — even just 1 — so the owner always gets evidence. The daily cap
        // only matters for repeated false-alarm spam, not for a real stop event.
        recordUploadSession();

        boolean stopped = alarmWasStopped;
        alarmWasStopped = false; // reset for next alarm session

        if (stopped) {
            Log.d(TAG, "Alarm was stopped — sending email with whatever photos are on disk (" + currentPhotoCount + ").");
        } else {
            Log.d(TAG, "Sending " + (isFinal ? "final" : "early") + " follow-up email with " + currentPhotoCount + " photo(s)...");
        }

        final EmailManager em = emailManager;
        if (em != null) {
            LocationHelper.fetch(this, (latitude, longitude) ->
                    em.sendAlertEmail(latitude, longitude, true, stopped));
        } else {
            Log.w(TAG, "emailManager was null at time of location result — follow-up email dropped.");
        }
    }

    private int getPhotoCountOnDisk() {
        File dir = new File(getFilesDir(), FOLDER);
        if (!dir.exists()) return 0;
        File[] files = dir.listFiles(f -> f.getName().endsWith(".jpg"));
        return (files != null) ? files.length : 0;
    }

    // ── Firebase daily upload cap ─────────────────────────────────────────────

    private boolean canUploadToday() {
        String today = todayString();
        if (!today.equals(pref.getLastUploadDay())) return true; // new day — reset
        return pref.getUploadCountToday() < PrefManager.DEFAULT_MAX_UPLOADS_PER_DAY;
    }

    private void recordUploadSession() {
        String today = todayString();
        if (!today.equals(pref.getLastUploadDay())) {
            pref.setLastUploadDay(today);
            pref.setUploadCountToday(1);
        } else {
            pref.setUploadCountToday(pref.getUploadCountToday() + 1);
        }
    }

    private String todayString() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    // ── Photo cleanup ─────────────────────────────────────────────────────────

    /**
     * Deletes photos from private storage that are older than the configured
     * retention period. Runs on the main thread but is fast (only checks file
     * modification timestamps — no I/O on photo content).
     *
     * A retention value of 0 means keep forever — no files are deleted.
     */
    private void cleanupOldPhotos() {
        int retentionDays = pref.getPhotoRetentionDays();
        if (retentionDays <= 0) return;

        File dir = new File(getFilesDir(), FOLDER);
        if (!dir.exists()) return;

        long cutoffMs = System.currentTimeMillis() - (long) retentionDays * 24 * 60 * 60 * 1_000L;
        File[] files  = dir.listFiles(f -> f.getName().endsWith(".jpg"));
        if (files == null) return;

        int deleted = 0;
        for (File f : files) {
            if (f.lastModified() < cutoffMs) {
                if (f.delete()) deleted++;
            }
        }

        if (deleted > 0) {
            Log.d(TAG, "Cleaned up " + deleted + " photo(s) older than "
                    + retentionDays + " day(s).");
        }
    }

    // ── Foreground notification & alarm screen ────────────────────────────────

    private void launchAlarmScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    ALARM_CHANNEL_ID, "Theft Alarm", NotificationManager.IMPORTANCE_HIGH);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setBypassDnd(true);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Intent alarmIntent = new Intent(this, AlarmActivity.class);
        alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0);

        PendingIntent fullScreenPI =
                PendingIntent.getActivity(this, 0, alarmIntent, piFlags);

        Notification notification = new NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("\uD83D\uDEA8 THEFT ALERT!")
                .setContentText("Your phone is being stolen!")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullScreenPI, true)
                .setContentIntent(fullScreenPI)
                .setOngoing(true)
                .setAutoCancel(false)
                .build();

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(ALARM_NOTIF_ID, notification);
    }

    private void startForegroundNow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Charging Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SafeCharge Running")
                .setContentText("Monitoring charging status\u2026")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override public Lifecycle getLifecycle() { return lifecycleRegistry; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra("updateMotion", false)) {
            updateMotionDetection();
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();

        stopMotionDetection();
        safeUnregister(batteryReceiver);
        safeUnregister(stopAlarmReceiver);

        // cameraManager.shutdown() no longer cancels the main handler, so any
        // pending onAllDone callback (which sends the follow-up email) is safe.
        if (cameraManager != null) cameraManager.shutdown();

        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);

        if (voice  != null) voice.release();
        if (siren  != null) siren.stopAlarm();

        // Shut down emailManager LAST and with a delay so any follow-up email
        // that was just submitted to its executor thread has time to complete.
        // The executor's shutdown() is graceful — it lets queued tasks finish
        // before the thread terminates. We post the shutdown on the main handler
        // after a 90-second window (the EmailJS timeout) to ensure the send
        // completes before the executor is torn down.
        if (emailManager != null) {
            final EmailManager em = emailManager;
            emailManager = null;
            mainHandler.postDelayed(em::shutdown, 90_000);
        }
    }

    private void safeUnregister(BroadcastReceiver receiver) {
        if (receiver == null) return;
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
    }
}