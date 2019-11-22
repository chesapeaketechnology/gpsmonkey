package com.chesapeaketechnology.gnssmonkey.service;

import com.android.gpstest.R;
import com.android.gpstest.util.Config;

import android.content.Context;
import android.hardware.SensorEvent;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.Location;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Saves GNSS data into a GeoPackage
 */
public class GeoPackageRecorder extends HandlerThread {
    private static final String TAG = "GPSMonkey.GpkgRec";
    private static final String FILENAME_PREFIX = "GNSS-MONKEY";

    @SuppressWarnings("SpellCheckingInspection")
    private static final SimpleDateFormat FILENAME_FRIENDLY_TIME_FORMAT = new SimpleDateFormat("YYYYMMdd-HHmmss", Locale.US);

    private final Context context;
    private final Config config;
    private Handler handler;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    private GeoPackageDatabase gpkgDatabase;
    private String gpkgFilePath;
    private String gpkgFolderPath;

    protected GeoPackageRecorder(Context context) {
        super("GeoPkgRcdr");
        this.context = context;
        config = Config.getInstance(context);
    }

    @Override
    protected void onLooperPrepared() {
        handler = new Handler();

        gpkgFolderPath = config.getSaveDirectoryPath();

        if (gpkgFolderPath == null) {
            Log.e(TAG, "Unable to find GPSMonkey storage location; using Download directory");
            gpkgFolderPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
        }

        try {
            gpkgFilePath = gpkgFolderPath + "/" + createGpkgFilename();

            gpkgDatabase = new GeoPackageDatabase(context);
            gpkgDatabase.start(gpkgFilePath);
            ready.set(true);
        } catch (SQLException e) {
            Log.e(TAG, "Error setting up GeoPackage", e);
        }
    }

    /**
     * Creates a new GeoPackage filename using the filename prefix and the current time.
     *
     * @return The filename
     */
    private String createGpkgFilename() {
        String timestamp = FILENAME_FRIENDLY_TIME_FORMAT.format(System.currentTimeMillis());
        return FILENAME_PREFIX + "-" + timestamp + ".gpkg";
    }

    public void onGnssMeasurementsReceived(final GnssMeasurementsEvent event) {
        if (ready.get() && (handler != null) && (event != null)) {
            handler.post(() -> gpkgDatabase.writeGnssMeasurements(event));
        }
    }

    public void onLocationChanged(final Location location) {
        if (ready.get() && (handler != null)) {
            handler.post(() -> gpkgDatabase.writeLocation(location));
        }
    }

    public void onSatelliteStatusChanged(final GnssStatus status) {
        if (ready.get() && (handler != null)) {
            handler.post(() -> gpkgDatabase.writeSatelliteStatus(status));
        }
    }

    // TODO KMB: Need to check with Steve to see if this is still needed. Currently, there is no
    //  logic to setup the motion table, and nothing was calling this method.
    public void onSensorUpdated(final SensorEvent event) {
        if (ready.get() && (handler != null) && (event != null)) {
            handler.post(() -> gpkgDatabase.writeSensorStatus(event));
        }
    }

    /**
     * Shuts down the recorder and provides the file path for the database
     *
     * @return The gpkg filename.
     */
    public String shutdown() {
        Log.d(TAG, "GeoPackageRecorder.shutdown()");
        ready.set(false);

        getLooper().quit();

        gpkgDatabase.shutdown();

        removeTempFiles();

        Toast.makeText(context, context.getString(R.string.data_saved_location) + gpkgFolderPath, Toast.LENGTH_LONG).show();

        return gpkgFilePath;
    }

    /**
     * Deletes any temporary journal files in the save directory.
     */
    private void removeTempFiles() {
        try {
            if (gpkgFolderPath != null) {
                File dir = new File(gpkgFolderPath);
                if (dir.exists()) {
                    // If the file is not a directory, null will be returned
                    File[] files = dir.listFiles();

                    if ((files != null) && (files.length > 0)) {
                        for (File file : files) {
                            String fileName = file.getName();
                            if ((fileName != null) && fileName.endsWith("-journal")) {
                                //noinspection ResultOfMethodCallIgnored
                                file.delete();
                            }
                        }
                    }
                }
            }
        } catch (Exception ignore) {
        }
    }
}
