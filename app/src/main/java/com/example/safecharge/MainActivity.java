package com.example.safecharge;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_NOTIFICATION_PERMISSION = 101;
    private static final int REQ_FULL_SCREEN_INTENT      = 102;
    private static final int REQ_CAMERA_PERMISSION       = 103;
    private static final int REQ_LOCATION_PERMISSION     = 104;

    private PrefManager pref;
    private final Map<Integer, Fragment> fragments = new HashMap<>();
    private Fragment activeFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        pref = new PrefManager(this);

        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        
        // Initialize fragments
        fragments.put(R.id.nav_dashboard, new DashboardFragment());
        fragments.put(R.id.nav_alerts, new AlertsFragment());
        fragments.put(R.id.nav_photos, new PhotosFragment());
        fragments.put(R.id.nav_settings, new SettingsFragment());

        FragmentManager fm = getSupportFragmentManager();
        
        // Default fragment
        activeFragment = fragments.get(R.id.nav_dashboard);
        fm.beginTransaction().add(R.id.fragment_container, fragments.get(R.id.nav_settings), "settings").hide(fragments.get(R.id.nav_settings)).commit();
        fm.beginTransaction().add(R.id.fragment_container, fragments.get(R.id.nav_photos), "photos").hide(fragments.get(R.id.nav_photos)).commit();
        fm.beginTransaction().add(R.id.fragment_container, fragments.get(R.id.nav_alerts), "alerts").hide(fragments.get(R.id.nav_alerts)).commit();
        fm.beginTransaction().add(R.id.fragment_container, activeFragment, "dashboard").commit();

        bottomNav.setOnNavigationItemSelectedListener(item -> {
            Fragment selected = fragments.get(item.getItemId());
            if (selected != null && selected != activeFragment) {
                fm.beginTransaction().hide(activeFragment).show(selected).commit();
                activeFragment = selected;
                return true;
            }
            return false;
        });

        requestLocationPermission();
    }

    // ── Public API for Fragments ──────────────────────────────────────────────

    public void syncService() {
        Intent i = new Intent(this, ChargingService.class);
        if (pref.isAlertEnabled() || pref.isMotionEnabled()) {
            i.putExtra("updateMotion", true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        } else {
            stopService(i);
        }
    }

    public boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void requestCameraPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
    }

    public void showSetPinDialog() {
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("4–8 digit PIN");

        String currentPin = pref.getDismissPin();
        String title   = currentPin.isEmpty() ? "Set Dismiss PIN" : "Change Dismiss PIN";
        String message = currentPin.isEmpty()
                ? "This PIN lets you stop the alarm if fingerprint fails."
                : "Current PIN is set. Enter a new PIN to replace it, or leave blank to clear.";

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String pin = input.getText().toString().trim();
                    if (pin.isEmpty()) {
                        pref.setDismissPin("");
                        Toast.makeText(this, "PIN cleared.", Toast.LENGTH_SHORT).show();
                    } else if (pin.length() < 4) {
                        Toast.makeText(this, "PIN must be at least 4 digits.",
                                Toast.LENGTH_SHORT).show();
                        showSetPinDialog(); // re-show
                        return;
                    } else {
                        pref.setDismissPin(pin);
                        Toast.makeText(this, "✅ Dismiss PIN saved.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public void sendTestEmail() {
        String r1 = pref.getRecipientEmail1().trim();
        if (r1.isEmpty()) {
            Toast.makeText(this, "Enter at least one recipient email first",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "📤 Sending test email…", Toast.LENGTH_SHORT).show();

        EmailManager emailManager = new EmailManager(this);
        LocationHelper.fetch(this, (latitude, longitude) ->
                emailManager.sendAlertEmail(latitude, longitude, false,
                        new EmailManager.SendCallback() {
                            @Override
                            public void onSuccess() {
                                emailManager.shutdown();
                                runOnUiThread(() -> Toast.makeText(
                                        MainActivity.this, "✅ Test email sent!",
                                        Toast.LENGTH_LONG).show());
                            }

                            @Override
                            public void onFailure(String errorMessage) {
                                emailManager.shutdown();
                                runOnUiThread(() -> Toast.makeText(
                                        MainActivity.this, "❌ Failed: " + errorMessage,
                                        Toast.LENGTH_LONG).show());
                            }
                        }));
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private void requestLocationPermission() {
        boolean hasLocation = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!hasLocation) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    }, REQ_LOCATION_PERMISSION);
        } else {
            requestFullScreenIntentPermission();
        }
    }

    private void requestFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && !nm.canUseFullScreenIntent()) {
                Toast.makeText(this,
                        "Please allow 'Alarms & Reminders' so the alarm screen can open",
                        Toast.LENGTH_LONG).show();
                startActivityForResult(
                        new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                Uri.parse("package:" + getPackageName())),
                        REQ_FULL_SCREEN_INTENT);
                return;
            }
        }
        checkNotificationPermissionAndStart();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FULL_SCREEN_INTENT) checkNotificationPermissionAndStart();
    }

    private void checkNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFICATION_PERMISSION);
                return;
            }
        }
        startChargingService();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case REQ_NOTIFICATION_PERMISSION:
                startChargingService();
                break;
            case REQ_CAMERA_PERMISSION: {
                boolean granted = grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED;
                pref.setCameraEnabled(granted);
                Toast.makeText(this,
                        granted ? "📷 Camera Capture ON" : "Camera permission denied",
                        Toast.LENGTH_SHORT).show();
                
                Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
                if (current instanceof DashboardFragment) {
                    // Update UI in fragment
                }
                break;
            }
            case REQ_LOCATION_PERMISSION:
                requestFullScreenIntentPermission();
                break;
        }
    }

    private void startChargingService() {
        if (pref.isAlertEnabled() || pref.isMotionEnabled()) {
            Intent i = new Intent(this, ChargingService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        }
    }

    public static class SimpleWatcher implements android.text.TextWatcher {
        public interface StringConsumer { void accept(String s); }
        private final StringConsumer consumer;
        public SimpleWatcher(StringConsumer c) { this.consumer = c; }
        @Override public void beforeTextChanged(CharSequence s, int i, int c, int a) {}
        @Override public void onTextChanged(CharSequence s, int i, int b, int c)     {}
        @Override public void afterTextChanged(android.text.Editable s) { consumer.accept(s.toString()); }
    }
}