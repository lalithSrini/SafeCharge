package com.example.safecharge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Legacy standalone receiver — kept for reference only.
 *
 * <p>All charger-connect/disconnect logic is handled inside
 * {@link ChargingService#registerBatteryReceiver()}, which also has access to
 * {@link PrefManager}, {@link VoiceManager}, and the alarm trigger. This class
 * is NOT registered in {@code AndroidManifest.xml} and is never used at runtime.</p>
 */

public class BatteryReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        // No-op: logic lives in ChargingService
    }
}