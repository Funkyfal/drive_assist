package com.geely.modehelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Prints the current range estimate to the log, or changes its settings:
 *   adb shell am broadcast -a com.geely.modehelper.RANGE -n com.geely.modehelper/.RangeReceiver
 *   ... --ef window 100        window size, km (5..500)
 *   ... --ef capacity 39.4     battery capacity, kWh (20..100)
 *   ... --ez reset true        forget the recent window
 * Guarded by the signature permission in the manifest.
 */
public class RangeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent i) {
        RangeTracker t = RangeTracker.start(ctx);
        Double capacity = null, window = null;
        if (i.hasExtra("capacity")) capacity = (double) i.getFloatExtra("capacity", 0f);
        if (i.hasExtra("window")) window = (double) i.getFloatExtra("window", 0f);
        boolean reset = i.getBooleanExtra("reset", false);
        boolean change = capacity != null || window != null || reset;
        Log.i(CarMode.TAG, change ? t.configure(capacity, window, reset) : t.describe());
    }
}