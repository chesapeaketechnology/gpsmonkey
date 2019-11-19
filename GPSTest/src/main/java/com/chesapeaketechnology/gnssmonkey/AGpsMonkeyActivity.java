package com.chesapeaketechnology.gnssmonkey;

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

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.android.gpstest.Application;
import com.android.gpstest.GpsTestListener;
import com.android.gpstest.R;
import com.chesapeaketechnology.gnssmonkey.service.GpsMonkeyService;

import java.util.ArrayList;

/**
 * Abstract activity to manage the GPS Monkey service (and isolate code changes for GPS Monkey from
 * existing GPS Test code to simplify merging in changes from forked app).
 */
public abstract class AGpsMonkeyActivity extends AppCompatActivity {
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
            serviceBound = false;
            gpsMonkeyService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
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
        // TODO KMB: I don't think this case can happen. Even when Android was forced to kill the
        //  process (following instructions here: https://stackoverflow.com/a/18695974), when
        //  onCreate() was called, serviceBound was false and gpsMonkeyService was null.
        if (serviceBound) {
            gpsMonkeyService.start();
        } else {
            startService(serviceIntent);
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        }
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
        // TODO KMB: If we are unbinding in stop, shouldn't we bind in start?
        super.onStart();
    }

    /**
     * Called when the activity is brought to the foreground and gains focus. This method should
     * initialize components that are released in {@link #onPause()}.
     *
     * {@inheritDoc}
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (this instanceof GpsTestListener) {
            if (serviceBound && (gpsMonkeyService != null)) {
                gpsMonkeyService.setListener((GpsTestListener) this);
            }
        }
    }

    /**
     * Called when the activity is losing focus (but may still be visible). This method should
     * release components that were initialized in {@link #onResume()}.
     *
     * {@inheritDoc}
     */
    @Override
    protected void onPause() {
        if (gpsMonkeyService != null) {
            gpsMonkeyService.setListener(null);
        }
        super.onPause();
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
        if (serviceBound && (gpsMonkeyService != null)) {
            try {
                unbindService(serviceConnection);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * According to the Android documentation, there are situations where the system will kill the
     * host process without calling {@link #onDestroy()}, so that should not be used as a place to
     * save data. It recommends using either {@link #onSaveInstanceState(Bundle)} or
     * {@link #onPause()}. Unfortunately, both can happen in multiple cases where we would want to
     * keep running the service and recording data (such as switching to the settings activity
     * within the GPS Monkey app). The main advantage of using this method is that it will not be
     * called in multi-window mode when the focus is switched, whereas {@link #onPause()} will be
     * called whenever this activity loses focus.
     *
     * Note: This method will be called before {@link #onStop()} prior to
     * {@link android.os.Build.VERSION_CODES.P} and after in later versions.
     *
     * @see <a href="https://developer.android.com/reference/android/app/Activity.html#onDestroy%28%29">
     * Activity#onDestroy() reference documentation</a>
     */
    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);

        // TODO KMB: This is too aggressive; it stops the service if we change to another activity
        //  in the app (such as the preferences activity). However, it looks like GpsTestActivity
        //  has the same issue.

        // Check if the user has chosen to stop GNSS whenever app is in background
        if (!isChangingConfigurations() && Application.getPrefs().getBoolean(getString(R.string.pref_key_stop_gnss_in_background), false)) {

            if (serviceBound) {
                stopService(serviceIntent);
            }
        }
    }

    /**
     * Per the Android documentation, there are situations where the system will kill the host
     * process without calling this method, so it should not be used as a place to save data.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.quit_GPSMonkey)
                .setMessage(R.string.quit_GPSMonkey_narrative)
                .setNegativeButton(R.string.quit_yes, (dialog, which) -> {
                    if (serviceBound) {
                        stopService(serviceIntent);
                    }
                    finish();
                })
                .setPositiveButton(R.string.quit_run_in_background, (arg0, arg1) -> finish())
                .create().show();
    }

    protected void onGPSMonkeyServiceConnected() {
        if (this instanceof GpsTestListener) {
            gpsMonkeyService.setListener((GpsTestListener) this);
        }
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
