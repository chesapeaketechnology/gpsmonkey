package com.chesapeaketechnology.gnssmonkey.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.android.gpstest.Application;
import com.android.gpstest.GpsTestListener;
import com.android.gpstest.R;
import com.chesapeaketechnology.gnssmonkey.FailureActivity;

import java.io.File;

import static com.chesapeaketechnology.gnssmonkey.service.GpsMonkeyService.InputSourceType.LOCAL;
import static com.chesapeaketechnology.gnssmonkey.service.GpsMonkeyService.InputSourceType.LOCAL_FILE;

/**
 * GPSMonkey service listens for updates from the GPS receiver and stores in TORGI compliant GeoPackage format."
 */
public class GpsMonkeyService extends Service {

    public enum InputSourceType {LOCAL, LOCAL_FILE}

    private final static String TAG = "GPSMonkeySvc";
    private final static int GPSMonkey_NOTIFICATION_ID = 1;

    private final static String NOTIFICATION_CHANNEL = "GPSMonkey_report";
    public final static String ACTION_STOP = "STOP";
    public final static String PREFS_CURRENT_INPUT_MODE = "inputmode";
    private GeoPackageRecorder geoPackageRecorder = null;
    private Location currentLocation = null;
    private InputSourceType inputSourceType = LOCAL;

    private SharedPreferences.OnSharedPreferenceChangeListener prefChangeListener;

    private long firstGpsAcqTime = Long.MIN_VALUE;
    private final static long TIME_TO_WAIT_FOR_GNSS_RAW_BEFORE_FAILURE = 1000L * 15L; //time to wait between first location measurement received and considering this device does not likely support raw GNSS collection
    private boolean gnssRawSupportKnown = false;

    public void setListener(GpsTestListener listener) {
        this.listener = listener;
    }

    private LocationManager locMgr = null;
    private GpsTestListener listener = null;

    private final IBinder mBinder = new GpsMonkeyBinder();

    public GeoPackageRecorder getGeoPackageRecorder() {
        return geoPackageRecorder;
    }

    public void start() {
        start(inputSourceType);
    }

    public void start(InputSourceType inputSourceType) {
        if (this.inputSourceType != inputSourceType) {
            this.inputSourceType = inputSourceType;
            Log.d(TAG, "GPSMonkey mode changed to " + inputSourceType.name());
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            prefs.edit().putInt(PREFS_CURRENT_INPUT_MODE, inputSourceType.ordinal()).commit();
        }
        boolean permissionsPassed = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        permissionsPassed = permissionsPassed && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;

        if (permissionsPassed) {
            currentLocation = null;
            if (locMgr == null) {
                locMgr = getSystemService(LocationManager.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    locMgr.registerGnssMeasurementsCallback(measurementListener);
                    locMgr.registerGnssStatusCallback(statusListener);
                }

                locMgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locListener);
                if (inputSourceType == LOCAL) {
                    currentLocation = locMgr.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                }
            } else {
                if (inputSourceType == LOCAL) {
                    currentLocation = locMgr.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    if (listener != null) {
                        listener.onLocationChanged(currentLocation);
                    }
                }
            }
            if (inputSourceType == LOCAL_FILE) {
                if (geoPackageRecorder != null) {
                    geoPackageRecorder.shutdown();
                }
            } else {
                if (geoPackageRecorder == null) {
                    geoPackageRecorder = new GeoPackageRecorder(this);
                    geoPackageRecorder.start();
                }
            }
            setForeground();
        }
    }

    private final GnssStatus.Callback statusListener = new GnssStatus.Callback() {
        public void onSatelliteStatusChanged(final GnssStatus status) {
            if (inputSourceType == LOCAL) {
                if (geoPackageRecorder != null) {
                    geoPackageRecorder.onSatelliteStatusChanged(status);
                }

                if (listener != null) {
                    listener.onSatelliteStatusChanged(status);
                }
            }
        }
    };

    private final GnssMeasurementsEvent.Callback measurementListener = new GnssMeasurementsEvent.Callback() {
        public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
            gnssRawSupportKnown = true;
            if (inputSourceType == LOCAL) {
                if (geoPackageRecorder != null) {
                    geoPackageRecorder.onGnssMeasurementsReceived(event);
                }
            }
        }
    };

    public void updateLocation(final Location loc) {

        currentLocation = loc;
        if (geoPackageRecorder != null) {
            geoPackageRecorder.onLocationChanged(loc);
        }

        if (listener != null) {
            listener.onLocationChanged(loc);
        }
    }

    private LocationListener locListener = new LocationListener() {
        public void onProviderEnabled(String provider) {
//            if ((listener != null) && (inputSourceType == LOCAL))
//                listener.onProviderChanged(provider, true);
        }

        public void onProviderDisabled(String provider) {
//            if ((listener != null) && (inputSourceType == LOCAL))
//                listener.onProviderChanged(provider, false);
        }

        public void onStatusChanged(final String provider, int status, Bundle extras) {
        }

        private boolean hasGnssRawFailureNagLaunched = false;

        public void onLocationChanged(final Location loc) {
            if (inputSourceType == LOCAL) {
                updateLocation(loc);
                if (!gnssRawSupportKnown && !hasGnssRawFailureNagLaunched) {
                    if (firstGpsAcqTime < 0L) {
                        firstGpsAcqTime = System.currentTimeMillis();
                    } else if (System.currentTimeMillis() > firstGpsAcqTime + TIME_TO_WAIT_FOR_GNSS_RAW_BEFORE_FAILURE) {
                        hasGnssRawFailureNagLaunched = true;
                        startActivity(new Intent(GpsMonkeyService.this, FailureActivity.class));
                    }
                }
            }
        }
    };

    /**
     * Clean-up GPSMonkey and then stop this service
     */
    public void shutdown() {
        stopSelf();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        start(inputSourceType);
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        int inputModeIndex = prefs.getInt(PREFS_CURRENT_INPUT_MODE, 0);
        InputSourceType[] sources = InputSourceType.values();
        if ((inputModeIndex >= 0) && (inputModeIndex < sources.length)) {
            inputSourceType = sources[inputModeIndex];
        }
    }

    @Override
    public void onDestroy() {
        if (prefChangeListener != null) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener);
        }
        if (locMgr != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                locMgr.unregisterGnssMeasurementsCallback(measurementListener);
                locMgr.unregisterGnssStatusCallback(statusListener);
            }
            if (locListener != null) {
                locMgr.removeUpdates(locListener);
            }
            locMgr = null;
        }

        shareFile();

        super.onDestroy();
    }

    public void shareFile() {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.cancelAll();

        String dbFile = null;
        if (geoPackageRecorder != null) {
            dbFile = geoPackageRecorder.shutdown();
        }
        if ((dbFile != null) && Application.getPrefs().getBoolean(getString(R.string.auto_share), true)) {
            File file = new File(dbFile);
            if (file.exists()) {
                Intent intentShareFile = new Intent(Intent.ACTION_SEND);
                intentShareFile.setType("application/octet-stream");
                intentShareFile.putExtra(Intent.EXTRA_STREAM,
                        FileProvider.getUriForFile(this, this.getApplicationContext().getPackageName() + ".geopackage.provider", file));
                intentShareFile.putExtra(Intent.EXTRA_SUBJECT, file.getName());
                intentShareFile.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(intentShareFile);
                } catch (ActivityNotFoundException ignore) {
                }
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (this) {
            if (intent != null) {
                String action = intent.getAction();
                if (ACTION_STOP.equalsIgnoreCase(action)) {
                    Log.d(TAG, "Shutting down GpsMonkeyService");
                    stopSelf();
                    return START_NOT_STICKY;
                }
            }
            return START_STICKY;
        }
    }

    public class GpsMonkeyBinder extends Binder {
        public GpsMonkeyService getService() {
            return GpsMonkeyService.this;
        }
    }

    /**
     * Running GPSMonkey as a foreground service allows GPSMonkey to stay active on API level 26+ devices. Depending
     * on desired collection rates, could also consider migrating to a JobScheduler
     */
    private void setForeground() {
        PendingIntent pendingIntent = null;
        try {
            Intent notificationIntent = new Intent(this, Class.forName("com.android.gpstest.GpsTestActivity"));
            pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        } catch (ClassNotFoundException ignore) {
        }

        Notification.Builder builder;
        builder = new Notification.Builder(this);
        builder.setContentIntent(pendingIntent);
        //TODO
        builder.setSmallIcon(R.mipmap.ic_launcher);
        builder.setContentTitle(getResources().getString(R.string.app_name));
        switch (inputSourceType) {
            case LOCAL_FILE:
                builder.setTicker(getResources().getString(R.string.notification_historical));
                builder.setContentText(getResources().getString(R.string.notification_historical));
                break;

            default:
                builder.setTicker(getResources().getString(R.string.notification));
                builder.setContentText(getResources().getString(R.string.notification));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(NOTIFICATION_CHANNEL);
        }

        Intent intentStop = new Intent(this, GpsMonkeyService.class);
        intentStop.setAction(ACTION_STOP);
        PendingIntent pIntentShutdown = PendingIntent.getService(this, 0, intentStop, PendingIntent.FLAG_UPDATE_CURRENT);
        builder.addAction(android.R.drawable.ic_lock_power_off, "Stop GPSMonkey", pIntentShutdown);

        startForeground(GPSMonkey_NOTIFICATION_ID, builder.build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "GPS Monkey";
            String description = "GNSS Recording data";
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL, name, importance);
            channel.setDescription(description);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    public Location getCurrentLocation() {
        return currentLocation;
    }
}
