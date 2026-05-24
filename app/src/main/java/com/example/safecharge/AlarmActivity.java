package com.example.safecharge;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

public class AlarmActivity extends AppCompatActivity {

    private static final int MAX_ATTEMPTS = 3;

    private PrefManager pref;

    private BiometricPrompt           biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;

    private int      failedAttempts = 0;
    private TextView tvSubtitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Show over lock screen & turn screen on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );
        }

        setContentView(R.layout.activity_alarm);

        pref       = new PrefManager(this);
        tvSubtitle = findViewById(R.id.tvAlarmSubtitle);

        buildBiometricPrompt();
        showBiometricPrompt();

        findViewById(R.id.btnFingerprint).setOnClickListener(v -> {
            failedAttempts = 0; // Reset counter so they can try biometrics again
            showBiometricPrompt();
        });

        // Block back button — alarm cannot be dismissed without auth
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Toast.makeText(AlarmActivity.this,
                        "Use fingerprint or PIN to stop alarm", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ── Biometric setup ───────────────────────────────────────────────────────

    private void buildBiometricPrompt() {
        promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Stop Alarm")
                .setSubtitle("Place your registered finger to disarm")
                .setNegativeButtonText("Use PIN instead")
                .build();

        biometricPrompt = new BiometricPrompt(this,
                ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {

                    @Override
                    public void onAuthenticationSucceeded(
                            BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        stopAlarmAndFinish();
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();

                        failedAttempts++;
                        int remaining = MAX_ATTEMPTS - failedAttempts;

                        if (remaining > 0) {
                            updateSubtitle("Wrong fingerprint — " + remaining
                                    + " attempt" + (remaining == 1 ? "" : "s") + " left");
                            Toast.makeText(AlarmActivity.this,
                                    "❌ Wrong fingerprint — " + remaining + " left",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);

                        if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                            // User tapped "Use PIN instead"
                            showPinFallback();

                        } else if (errorCode == BiometricPrompt.ERROR_LOCKOUT ||
                                errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) {
                            updateSubtitle("Biometrics locked. Use your PIN instead.");
                            Toast.makeText(AlarmActivity.this,
                                    "Device locked — use PIN to stop the alarm.",
                                    Toast.LENGTH_LONG).show();
                            showPinFallback();

                        } else if (failedAttempts >= MAX_ATTEMPTS) {
                            updateSubtitle("Max attempts reached. Enter your PIN.");
                            showPinFallback();

                        } else {
                            showBiometricPrompt();
                        }
                    }
                });
    }

    private void showBiometricPrompt() {
        BiometricManager bm = BiometricManager.from(this);
        boolean canAuthenticate = bm.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG |
                        BiometricManager.Authenticators.BIOMETRIC_WEAK)
                == BiometricManager.BIOMETRIC_SUCCESS;

        if (!canAuthenticate) {
            // No biometrics enrolled — go straight to PIN
            updateSubtitle("No fingerprint enrolled. Enter your PIN to stop the alarm.");
            showPinFallback();
            return;
        }

        int remaining = MAX_ATTEMPTS - failedAttempts;
        if (remaining <= 0) {
            showPinFallback();
            return;
        }

        updateSubtitle("Place your finger — " + remaining + " attempt" + (remaining == 1 ? "" : "s") + " allowed");
        biometricPrompt.authenticate(promptInfo);
    }

    // ── PIN fallback ──────────────────────────────────────────────────────────

    /**
     * Shows a dialog asking for the user's dismiss PIN.
     * The PIN is stored in PrefManager and set by the user in MainActivity.
     * If no PIN has been set, any non-empty input is accepted (fail-safe).
     */
    private void showPinFallback() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("Enter dismiss PIN");

        new AlertDialog.Builder(this)
                .setTitle("Stop Alarm")
                .setMessage("Enter your dismiss PIN to disarm.")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Confirm", (dialog, which) -> {
                    String entered = input.getText().toString().trim();
                    String savedPin = pref.getDismissPin();

                    if (savedPin.isEmpty()) {
                        // No PIN configured — accept any non-empty input as fail-safe
                        if (!entered.isEmpty()) {
                            stopAlarmAndFinish();
                        } else {
                            Toast.makeText(this, "Enter a PIN to stop the alarm.",
                                    Toast.LENGTH_SHORT).show();
                            showPinFallback();
                        }
                    } else if (entered.equals(savedPin)) {
                        stopAlarmAndFinish();
                    } else {
                        Toast.makeText(this, "❌ Wrong PIN. Try again.", Toast.LENGTH_SHORT).show();
                        showPinFallback();
                    }
                })
                .setNegativeButton("Use Fingerprint", (dialog, which) -> {
                    failedAttempts = 0; // reset counter when switching back
                    buildBiometricPrompt();
                    showBiometricPrompt();
                })
                .show();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void updateSubtitle(String text) {
        if (tvSubtitle != null) tvSubtitle.setText(text);
    }

    // ── Stop alarm ────────────────────────────────────────────────────────────

    private void stopAlarmAndFinish() {
        pref.setArmed(false);
        pref.setAlertEnabled(false);
        pref.setMotionEnabled(false);

        Intent stopBroadcast = new Intent("STOP_ALARM_BROADCAST");
        stopBroadcast.setPackage(getPackageName());
        sendBroadcast(stopBroadcast);

        Toast.makeText(this, "Alarm Stopped ✅", Toast.LENGTH_SHORT).show();

        // Return to MainActivity instead of closing the app
        Intent mainIntent = new Intent(this, MainActivity.class);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(mainIntent);

        finish();
    }
}