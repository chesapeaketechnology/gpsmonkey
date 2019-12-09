package com.android.gpstest;

import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.GpsStatus;
import android.location.LocationListener;

/**
 * Interface used by GpsTestActivity to communicate with Gps*Fragments
 */
public interface GpsTestListener extends LocationListener {

    default void gpsStart() {
    }

    default void gpsStop() {
    }

    @Deprecated
    default void onGpsStatusChanged(int event, GpsStatus status) {
    }

    default void onGnssFirstFix(int ttffMillis) {
    }

    default void onSatelliteStatusChanged(GnssStatus status) {
    }

    default void onGnssStarted() {
    }

    default void onGnssStopped() {
    }

    default void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
    }

    default void onOrientationChanged(double orientation, double tilt) {
    }

    default void onNmeaMessage(String message, long timestamp) {
    }
}