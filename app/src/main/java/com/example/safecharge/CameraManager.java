package com.example.safecharge;

import android.content.ContentValues;
import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class CameraManager {

    private static final String TAG = "CameraManager";
    private static final String FOLDER = "SafeCharge";

    /**
     * Gap between consecutive captures in milliseconds.
     */
    private static final long CAPTURE_INTERVAL_MS = 2_000L;

    private final Context context;
    private final PrefManager pref;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final AtomicBoolean isCapturing = new AtomicBoolean(false);

    /**
     * FIX: guards against a second captureThiefPhotos() call arriving while a
     * capture sequence is already running (e.g., charger-unplug and motion both
     * fire in the same alarm session). Without this the sequence would start twice,
     * doubling Firebase uploads and potentially binding CameraX twice.
     */
    private final AtomicBoolean sessionActive = new AtomicBoolean(false);

    /**
     * The runnable currently sitting in the handler queue waiting to fire the
     * next capture (the inter-shot postDelayed). Stored so stopCapturing() can
     * remove it immediately instead of waiting for the 2-second delay to expire.
     */
    private Runnable pendingCapture = null;

    /**
     * Always the real completion callback passed by ChargingService.
     * Set once in captureThiefPhotos() and NEVER touched by scheduleCameraSequence.
     */
    private Runnable realOnAllDone = null;

    public CameraManager(Context context) {
        this.context = context.getApplicationContext();
        this.pref = new PrefManager(context);
    }

    /**
     * Stops any further captures immediately.
     * <p>
     * Cancels the pending inter-shot delay (if any) and calls onAllDone right
     * away so the service is notified instantly and can send the follow-up email
     * with whatever photos are already saved, without waiting up to 2 seconds.
     */
    public void stopCapturing() {
        if (!isCapturing.getAndSet(false)) return;

        if (pendingCapture != null) {
            mainHandler.removeCallbacks(pendingCapture);
            pendingCapture = null;
        }

        if (realOnAllDone != null) {
            Runnable done = realOnAllDone;
            realOnAllDone = null;
            mainHandler.post(done);
        }
    }

    /**
     * Captures photos from the FRONT camera then
     * photos from the BACK camera sequentially.
     * <p>
     * The number of photos is defined in {@link PrefManager#getPhotoCountPerTrigger()}.
     * <p>
     * FIX: idempotent — if a session is already running (e.g., motion fires
     * immediately after charger-unplug in the same alarm) this call is a no-op,
     * preventing a second parallel capture sequence from starting.
     *
     * @param lifecycleOwner owner to bind CameraX to
     * @param onFirstPhoto   called on the main thread after the VERY FIRST photo is saved
     * @param onAllDone      called on the main thread when ALL captures finish
     *                       or when stopCapturing() is called
     */
    public void captureThiefPhotos(LifecycleOwner lifecycleOwner, java.util.function.Consumer<String> onPhotoSaved, Runnable onAllDone) {
        // Guard: do not start a second session while one is already running.
        if (!sessionActive.compareAndSet(false, true)) {
            Log.d(TAG, "captureThiefPhotos() called while session already active — ignoring.");
            return;
        }

        isCapturing.set(true);

        realOnAllDone = () -> {
            sessionActive.set(false); // allow future sessions after this one completes
            if (onAllDone != null) onAllDone.run();
        };

        scheduleCameraSequence(lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA, "front", 0, onPhotoSaved,
                () -> scheduleCameraSequence(lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA, "back", 0, onPhotoSaved,
                        realOnAllDone));
    }

    // ── Sequential capture chain for one camera ───────────────────────────────

    private void scheduleCameraSequence(LifecycleOwner lifecycleOwner,
                                        CameraSelector cameraSelector,
                                        String label,
                                        int index,
                                        java.util.function.Consumer<String> onPhotoSaved,
                                        Runnable onAllDone) {
        int photoCount = pref.getPhotoCountPerTrigger();
        if (!isCapturing.get() || index >= photoCount) {
            pendingCapture = null;
            if (onAllDone != null) mainHandler.post(onAllDone);
            return;
        }

        long delay = index == 0 ? 0L : CAPTURE_INTERVAL_MS;

        Runnable captureRunnable = () -> {
            pendingCapture = null;
            if (!isCapturing.get()) return;
            captureOne(lifecycleOwner, cameraSelector, label, index, (filename) -> {
                if (onPhotoSaved != null) onPhotoSaved.accept(filename);
                scheduleCameraSequence(lifecycleOwner, cameraSelector,
                        label, index + 1, onPhotoSaved, onAllDone);
            });
        };

        pendingCapture = captureRunnable;
        mainHandler.postDelayed(captureRunnable, delay);
    }

    // ── Single capture ────────────────────────────────────────────────────────

    private void captureOne(LifecycleOwner lifecycleOwner,
                            CameraSelector cameraSelector,
                            String label,
                            int index,
                            java.util.function.Consumer<String> onComplete) {

        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(context);

        future.addListener(() -> {
            ProcessCameraProvider provider = null;
            try {
                provider = future.get();
                provider.unbindAll();

                ImageCapture imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                provider.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture);

                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        .format(new Date());
                String filename = "thief_" + label + "_" + timestamp + "_" + index + ".jpg";

                File dir = new File(context.getFilesDir(), FOLDER);
                if (!dir.exists()) dir.mkdirs();
                File outFile = new File(dir, filename);

                ImageCapture.OutputFileOptions options =
                        new ImageCapture.OutputFileOptions.Builder(outFile).build();

                final ProcessCameraProvider bound = provider;

                imageCapture.takePicture(options, executor,
                        new ImageCapture.OnImageSavedCallback() {
                            @Override
                            public void onImageSaved(ImageCapture.OutputFileResults r) {
                                Log.d(TAG, "Saved: " + outFile.getName());
                                // Automatic gallery save removed to keep photos private within the app
                                // saveToGallery(outFile, filename);
                                mainHandler.post(() -> {
                                    unbindSafely(bound);
                                    onComplete.accept(filename);
                                });
                            }

                            @Override
                            public void onError(ImageCaptureException e) {
                                Log.e(TAG, label + " capture " + index
                                        + " failed: " + e.getMessage());
                                mainHandler.post(() -> {
                                    unbindSafely(bound);
                                    onComplete.accept(null);
                                });
                            }
                        });

            } catch (Exception e) {
                Log.e(TAG, label + " bind failed (index " + index + "): " + e.getMessage());
                final ProcessCameraProvider p = provider;
                mainHandler.post(() -> {
                    if (p != null) unbindSafely(p);
                    onComplete.accept(null);
                });
            }
        }, mainHandler::post);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void unbindSafely(ProcessCameraProvider provider) {
        try {
            provider.unbindAll();
        } catch (Exception ignored) {
        }
    }

    private void saveToGallery(File sourceFile, String filename) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + FOLDER);

            android.net.Uri uri = context.getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            if (uri != null) {
                try (FileInputStream in = new FileInputStream(sourceFile);
                     OutputStream out = context.getContentResolver()
                             .openOutputStream(uri)) {
                    if (out != null) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                    }
                }
            }
            Log.d(TAG, "Gallery saved: " + filename);
        } catch (Exception e) {
            Log.e(TAG, "Gallery save failed: " + e.getMessage());
        }
    }

    public void shutdown() {
        isCapturing.set(false);
        sessionActive.set(false);
        // Do NOT call mainHandler.removeCallbacksAndMessages(null) here.
        // stopCapturing() posts the onAllDone callback to the main handler
        // just before ChargingService calls stopSelf() → onDestroy() → shutdown().
        // Removing all main-handler callbacks here races with that pending post
        // and silently drops the follow-up email. The executor shutdown alone is
        // sufficient to stop any further capture work.
        executor.shutdown();
    }
}

