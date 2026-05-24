package com.example.safecharge;

import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.bumptech.glide.Glide;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

public class FullScreenPhotoActivity extends AppCompatActivity {

    private static final String TAG = "FullScreenPhotoActivity";
    private File photoFile;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingDeletion;
    private View coordinatorLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_full_screen_photo);

        coordinatorLayout = findViewById(R.id.coordinatorLayout);
        ImageView imageView = findViewById(R.id.fullScreenImageView);
        ImageButton btnClose = findViewById(R.id.btnClose);
        ImageButton btnShare = findViewById(R.id.btnShare);
        ImageButton btnDownload = findViewById(R.id.btnDownload);
        ImageButton btnDelete = findViewById(R.id.btnDelete);

        String photoPath = getIntent().getStringExtra("photo_path");
        if (photoPath != null) {
            photoFile = new File(photoPath);
            if (photoFile.exists()) {
                Glide.with(this).load(photoFile).into(imageView);
            }
        }

        btnClose.setOnClickListener(v -> finishWithCleanup());
        btnShare.setOnClickListener(v -> sharePhoto());
        btnDownload.setOnClickListener(v -> downloadPhoto());
        btnDelete.setOnClickListener(v -> confirmDeletion());
    }

    private void sharePhoto() {
        if (photoFile == null || !photoFile.exists()) return;

        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/jpeg");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "Share Evidence"));
    }

    private void downloadPhoto() {
        if (photoFile == null || !photoFile.exists()) return;

        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, photoFile.getName());
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SafeCharge");

            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (FileInputStream in = new FileInputStream(photoFile);
                     OutputStream out = getContentResolver().openOutputStream(uri)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                }
                Toast.makeText(this, "Photo saved to Gallery", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Download failed: " + e.getMessage());
            Toast.makeText(this, "Failed to download", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDeletion() {
        new AlertDialog.Builder(this)
                .setTitle("Delete Photo")
                .setMessage("Are you sure you want to delete this evidence?")
                .setPositiveButton("Delete", (dialog, which) -> startDeleteWithUndo())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startDeleteWithUndo() {
        // Hide UI elements to show the deletion is pending
        findViewById(R.id.fullScreenImageView).setVisibility(View.INVISIBLE);
        findViewById(R.id.topBar).setVisibility(View.GONE);
        findViewById(R.id.bottomBar).setVisibility(View.GONE);

        pendingDeletion = () -> {
            if (photoFile != null && photoFile.exists()) {
                photoFile.delete();
                Log.d(TAG, "Photo deleted permanently");
            }
            finish();
        };

        Snackbar snackbar = Snackbar.make(coordinatorLayout, "Photo deleted", 3000);
        snackbar.setAction("UNDO", v -> {
            handler.removeCallbacks(pendingDeletion);
            pendingDeletion = null;
            findViewById(R.id.fullScreenImageView).setVisibility(View.VISIBLE);
            findViewById(R.id.topBar).setVisibility(View.VISIBLE);
            findViewById(R.id.bottomBar).setVisibility(View.VISIBLE);
            Toast.makeText(this, "Deletion undone", Toast.LENGTH_SHORT).show();
        });
        
        snackbar.addCallback(new Snackbar.Callback() {
            @Override
            public void onDismissed(Snackbar transientBottomBar, int event) {
                if (event != DISMISS_EVENT_ACTION && pendingDeletion != null) {
                    handler.post(pendingDeletion);
                }
            }
        });
        
        snackbar.show();
    }

    private void finishWithCleanup() {
        if (pendingDeletion != null) {
            handler.removeCallbacks(pendingDeletion);
            pendingDeletion.run(); // Execute pending delete immediately
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        finishWithCleanup();
    }
}