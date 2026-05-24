package com.example.safecharge;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

public class DashboardFragment extends Fragment {

    private SwitchCompat switchMotion, switchCamera, switchEmail, switchSiren;
    private View btnArmed;
    private TextView tvArmedStatus, tvStatusBadge, tvDisarmHint;
    private View viewStatusDot, layoutStatusBadge;
    private ImageView iconArmed;
    private LinearLayout layoutActivityLog;
    private TextView tvLogEmpty;
    private PrefManager pref;
    
    private ObjectAnimator pulseAnimator;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_dashboard, container, false);
        
        pref = new PrefManager(requireContext());

        btnArmed = view.findViewById(R.id.viewArmedGlow);
        tvArmedStatus = view.findViewById(R.id.tvArmedStatus);
        tvStatusBadge = view.findViewById(R.id.tvStatusBadge);
        tvDisarmHint = view.findViewById(R.id.tvDisarmHint);
        viewStatusDot = view.findViewById(R.id.viewStatusDot);
        layoutStatusBadge = view.findViewById(R.id.layoutStatusBadge);
        iconArmed = view.findViewById(R.id.iconArmed);

        switchMotion = view.findViewById(R.id.switchMotion);
        switchCamera = view.findViewById(R.id.switchCamera);
        switchEmail  = view.findViewById(R.id.switchEmail);
        switchSiren  = view.findViewById(R.id.switchSiren);

        setupAnimations();
        
        // Initial states
        updateArmedUI();
        switchMotion.setChecked(pref.isMotionEnabled());
        switchCamera.setChecked(pref.isCameraEnabled());
        switchEmail.setChecked(true); // Placeholder
        switchSiren.setChecked(true); // Placeholder

        btnArmed.setOnClickListener(v -> {
            boolean newState = !pref.isAlertEnabled();
            pref.setAlertEnabled(newState);
            pref.setArmed(newState);
            updateArmedUI();
            
            Toast.makeText(requireContext(), newState ? "🔒 System Armed" : "🔓 System Disarmed",
                    Toast.LENGTH_SHORT).show();
            
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).syncService();
            }
        });

        switchMotion.setOnCheckedChangeListener((b, isOn) -> {
            pref.setMotionEnabled(isOn);
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).syncService();
            }
        });

        switchCamera.setOnCheckedChangeListener((b, isOn) -> {
            if (isOn && !((MainActivity) getActivity()).hasCameraPermission()) {
                ((MainActivity) getActivity()).requestCameraPermission();
                switchCamera.setChecked(false);
                return;
            }
            pref.setCameraEnabled(isOn);
        });

        return view;
    }

    private void setupAnimations() {
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(btnArmed,
                PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.15f),
                PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.15f),
                PropertyValuesHolder.ofFloat("alpha", 1.0f, 0.5f)
        );
        pulseAnimator.setDuration(1200);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
    }

    private void updateArmedUI() {
        boolean armed = pref.isAlertEnabled();
        
        if (armed) {
            tvArmedStatus.setText(R.string.status_armed);
            tvArmedStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.neon_cyan));
            iconArmed.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.neon_cyan)));
            btnArmed.setAlpha(1.0f);
            tvDisarmHint.setText(R.string.status_disarm_hint);
            tvDisarmHint.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));

            tvStatusBadge.setText(R.string.status_secure);
            tvStatusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.neon_green));
            viewStatusDot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.neon_green)));
            layoutStatusBadge.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.neon_green_dim)));
            
            if (!pulseAnimator.isRunning()) pulseAnimator.start();
        } else {
            tvArmedStatus.setText(R.string.status_disarmed);
            tvArmedStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_dim));
            iconArmed.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.text_dim)));
            btnArmed.setAlpha(0.2f);
            tvDisarmHint.setText(R.string.status_arm_hint);
            tvDisarmHint.setTextColor(ContextCompat.getColor(requireContext(), R.color.neon_cyan));

            tvStatusBadge.setText(R.string.status_unprotected);
            tvStatusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.neon_red));
            viewStatusDot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.neon_red)));
            layoutStatusBadge.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.neon_red_glow)));

            if (pulseAnimator.isRunning()) pulseAnimator.cancel();
            btnArmed.setScaleX(1.0f);
            btnArmed.setScaleY(1.0f);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateArmedUI();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (pulseAnimator != null) pulseAnimator.cancel();
    }
}