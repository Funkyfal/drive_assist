package com.geely.modehelper;

import android.app.Application;
import android.util.Log;

/** Starts the background workers whenever this process starts (boot, restart, any receiver). */
public class HelperApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        try {
            EventLog.start(this);
        } catch (Throwable t) {
            Log.w(CarMode.TAG, "event log did not start: " + t);
        }
        try {
            RangeTracker.start(this);
        } catch (Throwable t) {
            Log.w(CarMode.TAG, "range tracker did not start: " + t);
        }
        try {
            BluetoothKeeper.start(this);
        } catch (Throwable t) {
            Log.w(CarMode.TAG, "bluetooth keeper did not start: " + t);
        }
        try {
            ModeMemory.start(this);
        } catch (Throwable t) {
            Log.w(CarMode.TAG, "mode memory did not start: " + t);
        }
    }
}