package com.chesapeaketechnology.gnssmonkey;

import com.android.gpstest.Application;
import com.android.gpstest.GpsTestActivity;
import com.android.gpstest.R;
import com.chesapeaketechnology.gnssmonkey.service.GpsMonkeyService;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Extension of {@link GpsTestActivity} to manage the GPS Monkey service (and isolate code changes
 * for GPS Monkey from existing GPS Test code to simplify merging in changes from forked app).
 */
public class GpsMonkeyActivity extends GpsTestActivity {
    protected static final int REQUEST_DISABLE_BATTERY_OPTIMIZATION = 401;
    protected static final String TAG = "GPSMonkey.Activity";
    private static final String PREF_BATTERY_OPT_IGNORE = "nvroptbat";
    private static final int PERM_REQUEST_CODE = 1;
    private Intent serviceIntent;
    protected boolean serviceBound = false;
    protected GpsMonkeyService gpsMonkeyService = null;
    protected boolean permissionsPassed = false;

    protected ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(TAG, "GPS Monkey Service bound to this activity");
            GpsMonkeyService.GpsMonkeyBinder binder = (GpsMonkeyService.GpsMonkeyBinder) service;
            gpsMonkeyService = binder.getService();
            serviceBound = true;
            onGPSMonkeyServiceConnected();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            // Note: this method will only be called if the connection is dropped; it will *not* be
            // called when we unbind the service. That means, we need to make sure to update the
            // serviceBound variable when we call unbind().
            serviceBound = false;
            gpsMonkeyService = null;
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

//        permissionsPassed = checkPermissions();
//        if (permissionsPassed)
        startService();
//        openBatteryOptimizationDialogIfNeeded();
    }

    /**
     * Starts the GPS Monkey service which handles logging the GNSS data to a GeoPackage file.
     */
    private void startService() {
        if (serviceIntent == null) serviceIntent = new Intent(this, GpsMonkeyService.class);

        startService(serviceIntent);
    }

    /**
     * @return true once all required permissions are granted, otherwise returns false and requests the
     * required permission(s).
     */
    private boolean checkPermissions() {
        ArrayList<String> needed = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        if (needed.isEmpty()) {
            return true;
        } else {
            String[] perms = new String[needed.size()];
            perms = new String[perms.length];
            for (int i = 0; i < perms.length; i++) {
                perms[i] = needed.get(i);
            }

            ActivityCompat.requestPermissions(this, perms, PERM_REQUEST_CODE);
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERM_REQUEST_CODE) {
            if (checkPermissions()) {
                permissionsPassed = true;
                startService();
            } else {
                Toast.makeText(this, "Both Location and Storage permissions are needed", Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * Called immediately before the activity is made visible. This method should initialize
     * components that are released in {@link #onStop()}.
     *
     * {@inheritDoc}
     */
    @Override
    protected void onStart() {
        super.onStart();

        if (!serviceBound) {
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onGpsSwitchStateChange(boolean gpsOn) {
        if (serviceBound && (gpsMonkeyService != null)) {
            if (gpsOn) {
                gpsMonkeyService.startGps();
            } else {
                gpsMonkeyService.stopGps();
            }
        }
    }

    /**
     * Called when the activity is no longer visible. This method should release components that
     * were initialized in {@link #onStart()}.
     *
     * {@inheritDoc}
     */
    @Override
    protected void onStop() {
        super.onStop();
        if (serviceBound) {
            try {
                unbindService(serviceConnection);
                serviceBound = false;
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding service", e);
            }
        }
    }

    /**
     * According to the Android documentation, there are situations where the system will kill the
     * host process without calling {@link #onDestroy()}, so that should not be used as a place to
     * save data. It recommends using either {@link #onSaveInstanceState(Bundle)} or
     * {@link #onPause()}. Unfortunately, both can happen in multiple cases where we would want to
     * keep running the service and recording data (such as switching to the settings activity
     * within the GPS Monkey app).
     *
     * The documentation does not describe the situations where this method would not be called, but
     * it is likely something like the battery dying, in which case there isn't much we could do
     * anyway.
     *
     * @see <a href="https://developer.android.com/reference/android/app/Activity.html#onDestroy%28%29">
     * Activity#onDestroy() reference documentation</a>
     */
    @Override
    protected void onDestroy() {
        // If the user has chosen to stop GNSS whenever the app is in the background, stop the GPS
        // Monkey service when the app is destroyed. Ultimately, we may want to create a separate
        // setting for this, since the user may want to allow GNSS to run in the background while
        // the app is running, but not have a service running once they kill the app.
        if (isFinishing() && Application.getPrefs().getBoolean(getString(R.string.pref_key_stop_gnss_in_background), false)) {

            if (serviceBound) {
                stopService(serviceIntent);
            }
        }

        super.onDestroy();
    }

    /**
     * Called when the activity successfully binds to the service.
     */
    protected void onGPSMonkeyServiceConnected() {
        // It's possible to miss the initial setting of the GPS switch state if our service isn't
        // bound yet, so check as soon as we bind to see if GPS should be started.
        onGpsSwitchStateChange(mStarted);
    }

    /**
     * Request battery optimization exception so that the system doesn't throttle back our app
     */
    private void openBatteryOptimizationDialogIfNeeded() {
        if (isOptimizingBattery() && isAllowAskAboutBattery()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(R.string.enable_battery_optimization);
            builder.setMessage(R.string.battery_optimizations_narrative);
            builder.setPositiveButton(R.string.battery_optimize_yes, (dialog, which) -> {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                Uri uri = Uri.parse("package:" + getPackageName());
                intent.setData(uri);
                try {
                    startActivityForResult(intent, REQUEST_DISABLE_BATTERY_OPTIMIZATION);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(this, R.string.does_not_support_battery_optimization, Toast.LENGTH_SHORT).show();
                }
            });
            builder.setOnDismissListener(dialog -> setNeverAskBatteryOptimize());
            final AlertDialog dialog = builder.create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DISABLE_BATTERY_OPTIMIZATION) {
            setNeverAskBatteryOptimize();
        }
    }

    private void setNeverAskBatteryOptimize() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        SharedPreferences.Editor edit = prefs.edit();
        edit.putBoolean(PREF_BATTERY_OPT_IGNORE, true);
        edit.apply();
    }

    protected boolean isOptimizingBattery() {
        final PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private boolean isAllowAskAboutBattery() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return !prefs.getBoolean(PREF_BATTERY_OPT_IGNORE, false);
    }
}
