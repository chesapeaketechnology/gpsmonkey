package com.chesapeaketechnology.gnssmonkey.service;

import com.android.gpstest.Application;
import com.android.gpstest.R;
import com.android.gpstest.util.PreferenceUtils;
import com.chesapeaketechnology.gnssmonkey.RawGnssFailureActivity;

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

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import static com.chesapeaketechnology.gnssmonkey.service.GpsMonkeyService.InputSourceType.LOCAL;
import static com.chesapeaketechnology.gnssmonkey.service.GpsMonkeyService.InputSourceType.LOCAL_FILE;

/**
 * The GPS Monkey service listens for updates from the GPS receiver and stores them in a
 * TORGI-compliant GeoPackage format.
 */
public class GpsMonkeyService extends Service {
    public static final String ACTION_STOP = "STOP";
    public static final String PREFS_CURRENT_INPUT_MODE = "inputmode";

    private static final String TAG = "GPSMonkey.Service";
    private static final int GPS_MONKEY_NOTIFICATION_ID = 1;
    private static final String NOTIFICATION_CHANNEL = "GPSMonkey_report";

    /**
     * Time to wait between first location measurement received before considering this device does
     * not likely support raw GNSS collection.
     */
    private static final long TIME_TO_WAIT_FOR_GNSS_RAW_BEFORE_FAILURE = 1000L * 15L;

    private final IBinder gpsMonkeyBinder = new GpsMonkeyBinder();
    private final AtomicBoolean gpsStarted = new AtomicBoolean(false);

    /**
     * Callback for receiving GNSS measurements from the location manager.
     */
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

    /**
     * Callback for receiving GNSS status from the location manager.
     */
    private final GnssStatus.Callback statusListener = new GnssStatus.Callback() {
        public void onSatelliteStatusChanged(final GnssStatus status) {
            if (inputSourceType == LOCAL) {
                if (geoPackageRecorder != null) {
                    geoPackageRecorder.onSatelliteStatusChanged(status);
                }
            }
        }
    };

    /**
     * Callback for location updates from the location manager.
     */
    private final LocationListener locationListener = new LocationListener() {
        private boolean hasGnssRawFailureNagLaunched = false;

        public void onLocationChanged(final Location location) {
            if (inputSourceType == LOCAL) {
                updateLocation(location);
                if (!gnssRawSupportKnown && !hasGnssRawFailureNagLaunched) {
                    if (firstGpsAcqTime < 0L) {
                        firstGpsAcqTime = System.currentTimeMillis();
                    } else if (System.currentTimeMillis() > firstGpsAcqTime + TIME_TO_WAIT_FOR_GNSS_RAW_BEFORE_FAILURE) {
                        hasGnssRawFailureNagLaunched = true;

                        // The user may choose to continue using the app even without GNSS since
                        // they do get some satellite status on this display. If that is the case,
                        // they can choose not to be nagged about this every time they launch the app.
                        boolean ignoreRawGnssFailure = PreferenceUtils.getBoolean(Application.get().getString(R.string.pref_key_ignore_raw_gnss_failure), false);
                        if (!ignoreRawGnssFailure) {
                            startActivity(new Intent(GpsMonkeyService.this, RawGnssFailureActivity.class));
                        }
                    }
                }
            }
        }

        public void onStatusChanged(final String provider, int status, Bundle extras) {
        }

        public void onProviderEnabled(String provider) {
        }

        public void onProviderDisabled(String provider) {
        }
    };

    private GeoPackageRecorder geoPackageRecorder = null;
    private InputSourceType inputSourceType = LOCAL;
    private SharedPreferences.OnSharedPreferenceChangeListener prefChangeListener;
    private long firstGpsAcqTime = Long.MIN_VALUE;
    private boolean gnssRawSupportKnown = false;
    private LocationManager locationManager = null;

    /**
     * Performs one-time setup immediately before either {@link #onStartCommand(Intent, int, int)}
     * or {@link #onBind(Intent)} is called. If the service is already running, this method is not
     * called.
     */
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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (this) {
            if (intent != null) {
                String action = intent.getAction();

                // This stop action comes from the GPS Monkey notification
                if (ACTION_STOP.equalsIgnoreCase(action)) {
                    Log.d(TAG, "Shutting down GpsMonkeyService");
                    stopSelf();
                    return START_NOT_STICKY;
                }
            }

            return START_STICKY;
        }
    }

    public void start() {
        start(inputSourceType);
    }

    @Override
    public IBinder onBind(Intent intent) {
        start(inputSourceType);
        return gpsMonkeyBinder;
    }

    public void start(InputSourceType inputSourceType) {
        if (this.inputSourceType != inputSourceType) {
            this.inputSourceType = inputSourceType;
            Log.d(TAG, "GPSMonkey mode changed to " + inputSourceType.name());
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            prefs.edit().putInt(PREFS_CURRENT_INPUT_MODE, inputSourceType.ordinal()).apply();
        }

        if (inputSourceType == LOCAL_FILE) {
            if (geoPackageRecorder != null) {
                geoPackageRecorder.shutdown();
                geoPackageRecorder = null;
            }
        } else {
            if (geoPackageRecorder == null) {
                geoPackageRecorder = new GeoPackageRecorder(this);
                geoPackageRecorder.start();
            }
        }

        setForeground();
    }

    /**
     * Running GPSMonkey as a foreground service allows GPSMonkey to stay active on API level 26+
     * devices.
     * Depending on desired collection rates, could also consider migrating to a JobScheduler.
     */
    private void setForeground() {
        PendingIntent pendingIntent = null;
        try {
            Intent notificationIntent = new Intent(this, Class.forName("com.chesapeaketechnology.gnssmonkey.GpsMonkeyActivity"));
            pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        } catch (ClassNotFoundException ignore) {
        }

        Notification.Builder builder;
        builder = new Notification.Builder(this, NOTIFICATION_CHANNEL);
        builder.setContentIntent(pendingIntent);
        //TODO
        builder.setSmallIcon(R.mipmap.ic_launcher);
        builder.setContentTitle(getResources().getString(R.string.app_name));

        int notificationTextId = (inputSourceType == LOCAL_FILE) ? R.string.notification_historical : R.string.notification;
        String notificationText = getResources().getString(notificationTextId);
        builder.setTicker(notificationText);
        builder.setContentText(notificationText);

        // Add an action to the notification for stopping the GPS Monkey service
        Intent intentStop = new Intent(this, GpsMonkeyService.class);
        intentStop.setAction(ACTION_STOP);
        PendingIntent pIntentShutdown = PendingIntent.getService(this, 0, intentStop, PendingIntent.FLAG_UPDATE_CURRENT);
        builder.addAction(android.R.drawable.ic_lock_power_off, "Stop GPSMonkey", pIntentShutdown);

        startForeground(GPS_MONKEY_NOTIFICATION_ID, builder.build());
    }

    @Override
    public void onDestroy() {
        if (prefChangeListener != null) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener);
        }

        stopGps();

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.cancelAll();

        shareFile();

        super.onDestroy();
    }

    public void shareFile() {
        String dbFile = null;
        if (geoPackageRecorder != null) {
            dbFile = geoPackageRecorder.shutdown();
        }

        // Default auto-sharing to false until we add a user setting for it.
        if ((dbFile != null) && Application.getPrefs().getBoolean(getString(R.string.auto_share), false)) {
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

    /**
     * Opens a new GeoPackage database for recording GPS data and registers for GPS updates.
     */
    public void startGps() {
        if (gpsStarted.getAndSet(true)) return;

        if (geoPackageRecorder != null) geoPackageRecorder.openGeoPackageDatabase();

        boolean hasPermissions = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        hasPermissions = hasPermissions && ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;

        if (hasPermissions) {
            if (locationManager == null) {
                locationManager = getSystemService(LocationManager.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    locationManager.registerGnssMeasurementsCallback(measurementListener);
                    locationManager.registerGnssStatusCallback(statusListener);
                }

                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locationListener);
            }
        }
    }

    /**
     * Unregisters from GPS updates and closes the GeoPackage database recording the GPS data.
     */
    public void stopGps() {
        if (!gpsStarted.getAndSet(false)) return;

        if (locationManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                locationManager.unregisterGnssMeasurementsCallback(measurementListener);
                locationManager.unregisterGnssStatusCallback(statusListener);
            }

            locationManager.removeUpdates(locationListener);
            locationManager = null;
        }

        if (geoPackageRecorder != null) geoPackageRecorder.closeGeoPackageDatabase();
    }

    public void updateLocation(final Location location) {
        if (geoPackageRecorder != null) {
            geoPackageRecorder.onLocationChanged(location);
        }
    }

    /**
     * Enum representing the type of input source.
     */
    public enum InputSourceType {LOCAL, LOCAL_FILE}

    /**
     * The binder for the {@link GpsMonkeyService}.
     */
    public class GpsMonkeyBinder extends Binder {
        public GpsMonkeyService getService() {
            return GpsMonkeyService.this;
        }
    }
}
