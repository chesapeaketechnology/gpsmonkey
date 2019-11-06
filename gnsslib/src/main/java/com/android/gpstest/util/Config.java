package com.android.gpstest.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.preference.PreferenceManager;

import java.io.File;
import java.util.UUID;

public class Config {
    public static final String PREFS_SAVE_DIR = "savedir";
    public static final String PREFS_AUTO_SHARE = "autoshare";
    public static final String PREFS_PROCESS_EW = "processew";
    public static final String PREFS_UUID = "callsign";
    public static final String PREFS_GPS_ONLY = "gpsonly";
    public static final String PREFS_BROADCAST = "broadcast";
    public static final String PREFS_SQAN = "sqan";
    public static final String PREFS_SEND_TO_SOS = "sendtosos";

    private static boolean gpsOnly = false;
    private static Config instance = null;

    private String savedDir = null;
    private boolean processEwOnboard;
    private SharedPreferences prefs;
    private String uuid = null;
    private String remoteIP = null;

    public static boolean isGpsOnly() {
        return gpsOnly;
    }

    public static boolean isSosBroadcastEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREFS_SEND_TO_SOS, true);
    }

    public static boolean isIpcBroadcastEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREFS_BROADCAST, true);
    }

    public static boolean isSqAnBroadcastEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREFS_SQAN, true);
    }

    public static Config getInstance(Context context) {
        if (instance == null) {
            instance = new Config(context);
        }
        return instance;
    }

    private Config(Context context) {
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        processEwOnboard = prefs.getBoolean(PREFS_PROCESS_EW, false);
        if (prefs.getString(PREFS_UUID, null) == null) {
            SharedPreferences.Editor editor = prefs.edit();
            editor.apply();
        }
    }

    public void loadPrefs() {
        gpsOnly = prefs.getBoolean(PREFS_GPS_ONLY, false);
    }

    public void setProcessEwOnboard(boolean processEwOnboard) {
        this.processEwOnboard = processEwOnboard;
        SharedPreferences.Editor edit = prefs.edit();
        edit.putBoolean(PREFS_PROCESS_EW, processEwOnboard);
        edit.commit();
    }

    public boolean isAutoShareEnabled() {
        return prefs.getBoolean(PREFS_AUTO_SHARE, true);
    }

    public void setGpsOnly(boolean gpsOnly) {
        Config.gpsOnly = gpsOnly;
        prefs.edit().putBoolean(PREFS_GPS_ONLY, gpsOnly).commit();
    }

    public boolean processEWOnboard() {
        return processEwOnboard;
    }

    public String getUuid() {
        if (uuid == null) {
            uuid = prefs.getString(PREFS_UUID, null);
            if (uuid == null) {
                uuid = UUID.randomUUID().toString();
                prefs.edit().putString(PREFS_UUID, uuid).apply();
            }
        }
        return uuid;
    }

    public String getSavedDir() {
        if (savedDir == null) {
            /*savedDir = prefs.getString(PREFS_SAVE_DIR, null);
            if (savedDir != null) {
                try {
                    Uri savedDirUri = Uri.parse(savedDir);
                    if (savedDirUri != null) {
                        context.getContentResolver().takePersistableUriPermission(savedDirUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION); //Keep the permissions to access this location up to date across reboots
                    }
                } catch (NullPointerException ignore) {}
            }
            if (savedDir == null)
                savedDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();*/
            if (savedDir == null) {
                File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GPSMonkey");

                //noinspection ResultOfMethodCallIgnored
                folder.mkdirs();
                savedDir = folder.getAbsolutePath();
            }
        }
        return savedDir;
    }

    public void setSavedDir(String savedDir) {
        SharedPreferences.Editor edit = prefs.edit();
        if (savedDir == null) {
            edit.remove(PREFS_SAVE_DIR);
        } else {
            edit.putString(PREFS_SAVE_DIR, savedDir);
        }
        edit.commit();
    }
}
