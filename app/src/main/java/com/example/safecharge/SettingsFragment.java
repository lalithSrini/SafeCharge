package com.example.safecharge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import java.util.Locale;

public class SettingsFragment extends Fragment {

    private PrefManager pref;
    private SeekBar seekSensitivity;
    private TextView tvSensitivityValue;
    private TextView tvBatteryStatus;
    private TextView tvPhotoCountValue;
    private TextView tvGpsStatus;

    private double currentLat = 0, currentLon = 0;

    private static final float THRESHOLD_MIN = 8f;
    private static final float THRESHOLD_MAX = 18f;
    private static final int SEEKBAR_MAX = 10;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level != -1 && scale != -1) {
                int batteryPct = (int) (level * 100 / (float) scale);
                updateBatteryUI(batteryPct);
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);
        pref = new PrefManager(requireContext());

        seekSensitivity = view.findViewById(R.id.seekSensitivity);
        tvSensitivityValue = view.findViewById(R.id.tvSensitivityValue);
        tvBatteryStatus = view.findViewById(R.id.tvBatteryStatus);
        tvPhotoCountValue = view.findViewById(R.id.tvPhotoCountValue);
        tvGpsStatus = view.findViewById(R.id.tvGpsStatus);

        view.findViewById(R.id.btnSetPin).setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).showSetPinDialog();
            }
        });

        view.findViewById(R.id.btnSendTestMail).setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).sendTestEmail();
            }
        });

        view.findViewById(R.id.btnRecipients).setOnClickListener(v -> showRecipientsDialog());
        view.findViewById(R.id.btnPhotoCount).setOnClickListener(v -> showPhotoCountDialog());

        // Handle GPS button click
        View btnGps = (View) tvGpsStatus.getParent();
        btnGps.setOnClickListener(v -> {
            if (currentLat != 0 || currentLon != 0) {
                String uri = String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(My Location)", 
                        currentLat, currentLon, currentLat, currentLon);
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                startActivity(intent);
            } else {
                Toast.makeText(requireContext(), "Location not available yet", Toast.LENGTH_SHORT).show();
            }
        });

        restoreSensitivitySeekBar();
        updatePhotoCountUI();

        seekSensitivity.setMax(SEEKBAR_MAX);
        seekSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                updateSensitivityLabel(p);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                pref.setMotionThreshold(progressToThreshold(bar.getProgress()));
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).syncService();
                }
            }
        });

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        requireContext().registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        refreshLocation();
    }

    private void refreshLocation() {
        if (tvGpsStatus != null) tvGpsStatus.setText("Fetching...");
        LocationHelper.fetch(requireContext(), (lat, lon) -> {
            if (isAdded()) {
                currentLat = lat;
                currentLon = lon;
                if (lat != 0 || lon != 0) {
                    tvGpsStatus.setText(String.format(Locale.US, "%.4f, %.4f", lat, lon));
                } else {
                    tvGpsStatus.setText("GPS Error");
                }
            }
        });
    }

    @Override
    public void onStop() {
        super.onStop();
        requireContext().unregisterReceiver(batteryReceiver);
    }

    private void updateBatteryUI(int percentage) {
        if (tvBatteryStatus != null) {
            tvBatteryStatus.setText(getString(R.string.battery_status_format, percentage));
        }
    }

    private void updatePhotoCountUI() {
        if (tvPhotoCountValue != null) {
            int count = pref.getPhotoCountPerTrigger();
            tvPhotoCountValue.setText(getString(R.string.photo_count_format, count, count));
        }
    }

    private void showPhotoCountDialog() {
        String[] options = {"1 + 1", "3 + 3", "5 + 5", "10 + 10"};
        int[] values = {1, 3, 5, 10};
        
        int current = pref.getPhotoCountPerTrigger();
        int initialSelection = 2; // default 5
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                initialSelection = i;
                break;
            }
        }

        new AlertDialog.Builder(requireContext(), R.style.Theme_SafeCharge_Dialog)
                .setTitle("Photos Per Trigger")
                .setSingleChoiceItems(options, initialSelection, (dialog, which) -> {
                    pref.setPhotoCountPerTrigger(values[which]);
                    updatePhotoCountUI();
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showRecipientsDialog() {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_recipients, null);
        EditText et1 = dialogView.findViewById(R.id.etRecipient1);
        EditText et2 = dialogView.findViewById(R.id.etRecipient2);

        et1.setText(pref.getRecipientEmail1());
        et2.setText(pref.getRecipientEmail2());

        new AlertDialog.Builder(requireContext(), R.style.Theme_SafeCharge_Dialog)
                .setTitle("Alert Recipients")
                .setView(dialogView)
                .setPositiveButton("Save", (dialog, which) -> {
                    pref.setRecipientEmail1(et1.getText().toString().trim());
                    pref.setRecipientEmail2(et2.getText().toString().trim());
                    Toast.makeText(requireContext(), "Recipients updated", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void restoreSensitivitySeekBar() {
        int progress = thresholdToProgress(pref.getMotionThreshold());
        seekSensitivity.setProgress(progress);
        updateSensitivityLabel(progress);
    }

    private void updateSensitivityLabel(int progress) {
        float threshold = progressToThreshold(progress);
        tvSensitivityValue.setText(String.format(Locale.US, "%.1f m/s²", threshold));
    }

    private float progressToThreshold(int progress) {
        return THRESHOLD_MIN + (THRESHOLD_MAX - THRESHOLD_MIN) * progress / SEEKBAR_MAX;
    }

    private int thresholdToProgress(float threshold) {
        return Math.round((threshold - THRESHOLD_MIN) / (THRESHOLD_MAX - THRESHOLD_MIN) * SEEKBAR_MAX);
    }
}