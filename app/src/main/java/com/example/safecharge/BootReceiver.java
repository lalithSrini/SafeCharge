package com.example.safecharge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Restarts {@link ChargingService} after a device reboot if the user
 * had alerts or motion detection enabled before the reboot.
 *
 * FIX: must be android:exported="true" in the manifest so the system
 * can deliver BOOT_COMPLETED on API 33+. The receiver is safe because
 * it ignores every action except BOOT_COMPLETED.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        PrefManager pref = new PrefManager(context);
        if (!pref.isAlertEnabled() && !pref.isMotionEnabled()) {
            Log.d(TAG, "Boot: service not needed (alarm was disarmed before reboot).");
            return;
        }

        Log.d(TAG, "Boot: restarting ChargingService.");
        Intent serviceIntent = new Intent(context, ChargingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}