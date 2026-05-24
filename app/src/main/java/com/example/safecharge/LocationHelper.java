package com.example.safecharge;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * One-shot location fetcher.
 *
 * <p>Tries GPS first, falls back to Network provider. Calls {@link Callback}
 * as soon as a fix arrives, or with (0, 0) after {@link #TIMEOUT_MS} if no
 * fix is obtained. Either way the location listener is unregistered so the
 * helper never leaks sensors.</p>
 *
 * <p>FIX: getLastKnownLocation() is now validated for staleness. Cached
 * locations older than {@link #MAX_CACHE_AGE_MS} (5 minutes) are discarded
 * and a fresh fix is requested instead, preventing misleading coordinates
 * in the alert email.</p>
 */
public class LocationHelper {

    private static final String TAG            = "LocationHelper";
    private static final long   TIMEOUT_MS     = 10_000L;  // 10 s max wait for fresh fix
    /** Cached locations older than this are ignored and a fresh fix is requested. */
    private static final long   MAX_CACHE_AGE_MS = 5 * 60 * 1_000L; // 5 minutes

    public interface Callback {
        /** Called on the main thread. lat/lon == 0 if no fix was obtained. */
        void onResult(double latitude, double longitude);
    }

    /** Entry point — safe to call from any thread. */
    public static void fetch(Context context, Callback callback) {
        boolean hasFine   = ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "No location permission — skipping GPS.");
            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(0, 0));
            return;
        }

        new Handler(Looper.getMainLooper()).post(() ->
                new OneShotFetcher(context.getApplicationContext(), hasFine, callback).start());
    }

    // ── Internal one-shot fetcher ─────────────────────────────────────────────

    private static class OneShotFetcher implements LocationListener {

        private final LocationManager locationManager;
        private final Callback        callback;
        private final Handler         handler = new Handler(Looper.getMainLooper());
        private boolean               delivered = false;

        OneShotFetcher(Context context, boolean hasFine, Callback callback) {
            this.locationManager = (LocationManager)
                    context.getSystemService(Context.LOCATION_SERVICE);
            this.callback = callback;
        }

        @SuppressWarnings("MissingPermission")
        void start() {
            // 1. Return a cached "last known" location only if it is recent enough.
            //    FIX: previously any cached location was returned immediately, even
            //    if it was hours old or from a different city. Now we validate age
            //    before trusting it.
            Location cached = getBestLastKnown();
            if (cached != null && isRecent(cached)) {
                Log.d(TAG, "Returning recent cached location (age "
                        + locationAgeMs(cached) / 1000 + "s).");
                deliver(cached.getLatitude(), cached.getLongitude());
                return;
            }

            if (cached != null) {
                Log.d(TAG, "Cached location is stale (" + locationAgeMs(cached) / 1000
                        + "s old) — requesting fresh fix.");
            }

            // 2. Register for a fresh fix on both providers
            boolean gpsAvailable = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean netAvailable = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

            if (gpsAvailable) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 0, 0, this, Looper.getMainLooper());
            }
            if (netAvailable) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 0, 0, this, Looper.getMainLooper());
            }

            if (!gpsAvailable && !netAvailable) {
                Log.w(TAG, "No location provider enabled.");
                // Fall back to stale cache rather than nothing, if available
                if (cached != null) {
                    Log.w(TAG, "Using stale cache as last resort.");
                    deliver(cached.getLatitude(), cached.getLongitude());
                } else {
                    deliver(0, 0);
                }
                return;
            }

            // 3. Timeout fallback — use stale cache if we have it, otherwise (0,0)
            handler.postDelayed(() -> {
                if (!delivered) {
                    Log.w(TAG, "Location timeout.");
                    stop();
                    if (cached != null) {
                        Log.w(TAG, "Timeout: falling back to stale cache.");
                        deliver(cached.getLatitude(), cached.getLongitude());
                    } else {
                        deliver(0, 0);
                    }
                }
            }, TIMEOUT_MS);
        }

        @SuppressWarnings("MissingPermission")
        private Location getBestLastKnown() {
            Location best = null;
            for (String provider : locationManager.getProviders(true)) {
                Location loc = locationManager.getLastKnownLocation(provider);
                if (loc != null && (best == null || loc.getAccuracy() < best.getAccuracy())) {
                    best = loc;
                }
            }
            return best;
        }

        /**
         * Returns true if the location fix is younger than {@link #MAX_CACHE_AGE_MS}.
         * Uses elapsedRealtimeNanos for API 17+ (immune to clock adjustments),
         * falls back to wall-clock time comparison.
         */
        private boolean isRecent(Location location) {
            long ageMs = locationAgeMs(location);
            return ageMs < MAX_CACHE_AGE_MS;
        }

        private long locationAgeMs(Location location) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                return (SystemClock.elapsedRealtimeNanos()
                        - location.getElapsedRealtimeNanos()) / 1_000_000L;
            } else {
                return System.currentTimeMillis() - location.getTime();
            }
        }

        private void stop() {
            try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
            handler.removeCallbacksAndMessages(null);
        }

        private void deliver(double lat, double lon) {
            if (delivered) return;
            delivered = true;
            stop();
            callback.onResult(lat, lon);
        }

        @Override
        public void onLocationChanged(Location location) {
            deliver(location.getLatitude(), location.getLongitude());
        }

        @Override public void onStatusChanged(String p, int s, Bundle e) {}
        @Override public void onProviderEnabled(String p)  {}
        @Override public void onProviderDisabled(String p) {}
    }
}