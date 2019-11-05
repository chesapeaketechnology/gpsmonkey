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
import android.os.Build;
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

import com.android.gpstest.GpsTestListener;
import com.android.gpstest.R;
import com.chesapeaketechnology.gnssmonkey.service.GpsMonkeyService;

import java.util.ArrayList;

public abstract class AbstractGpsMonkeyActivity extends AppCompatActivity {
    protected static final int REQUEST_DISABLE_BATTERY_OPTIMIZATION = 401;
    protected final static String TAG = "GPSMonkey.monitor";
    private final static String PREF_BATTERY_OPT_IGNORE = "nvroptbat";
    private final static int PERM_REQUEST_CODE = 1;
    protected boolean serviceBound = false;
    protected GpsMonkeyService gpsMonkeyService = null;
    protected boolean permissionsPassed = false;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
//        permissionsPassed = checkPermissions();
//        if (permissionsPassed)
        startService();
//        openBatteryOptimizationDialogIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (this instanceof GpsTestListener) {
            if (serviceBound && (gpsMonkeyService != null)) {
                gpsMonkeyService.setListener((GpsTestListener) this);
            }
        }
    }

    @Override
    protected void onPause() {
        if (gpsMonkeyService != null) {
            gpsMonkeyService.setListener(null);
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (serviceBound && (gpsMonkeyService != null)) {
            try {
                unbindService(mConnection);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    protected ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(TAG, "GNSS Service bound to this activity");
            GpsMonkeyService.GpsMonkeyBinder binder = (GpsMonkeyService.GpsMonkeyBinder) service;
            gpsMonkeyService = binder.getService();
            serviceBound = true;
            onGPSMonkeyServiceConnected();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            serviceBound = false;
        }
    };

    protected void onGPSMonkeyServiceConnected() {
        if (this instanceof GpsTestListener) {
            gpsMonkeyService.setListener((GpsTestListener) this);
        }
    }

    private void startService() {
        if (serviceBound) {
            gpsMonkeyService.start();
        } else {
            startService(new Intent(this, GpsMonkeyService.class));
            Intent intent = new Intent(this, GpsMonkeyService.class);
            bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
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

    protected boolean isOptimizingBattery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            final PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName());
        } else {
            return false;
        }
    }

    private boolean isAllowAskAboutBattery() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return !prefs.getBoolean(PREF_BATTERY_OPT_IGNORE, false);
    }

    private void setNeverAskBatteryOptimize() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        SharedPreferences.Editor edit = prefs.edit();
        edit.putBoolean(PREF_BATTERY_OPT_IGNORE, true);
        edit.apply();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_DISABLE_BATTERY_OPTIMIZATION:
                setNeverAskBatteryOptimize();
                break;
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
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.quit_GPSMonkey)
                .setMessage(R.string.quit_GPSMonkey_narrative)
                .setNegativeButton(R.string.quit_yes, (dialog, which) -> {
                    if (serviceBound && (gpsMonkeyService != null)) {
                        gpsMonkeyService.shutdown();
                    }
                    AbstractGpsMonkeyActivity.this.finish();
                })
                .setPositiveButton(R.string.quit_run_in_background,
                        (arg0, arg1) -> AbstractGpsMonkeyActivity.this.finish()).create().show();
    }
}
