package com.geely.modehelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Status and manual control of the remembered modes:
 *   adb shell am broadcast -a com.geely.modehelper.SET_MODE -n com.geely.modehelper/.ModeReceiver
 *   ... --ei drive 570491137      save and apply a drive mode (Eco ...137, Comfort ...138, Sport ...139)
 *   ... --ei regen 537003265      save and apply regeneration (Low ...265, Mid ...266, High ...267)
 *   ... --ez restore true         act as if the car had just woken up
 *   ... --ez learn true           learn from what the driver picks (default false)
 *   ... --ez forget true          forget the saved modes
 * Guarded by the signature permission in the manifest.
 */
public class ModeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent i) {
        ModeMemory m = ModeMemory.start(ctx);
        StringBuilder b = new StringBuilder();
        if (i.getBooleanExtra("forget", false)) {
            m.forget();
            b.append("forgotten; ");
        }
        if (i.hasExtra("learn")) {
            m.setLearning(i.getBooleanExtra("learn", false));
            b.append("learn changed; ");
        }
        if (i.hasExtra("drive")) {
            int v = i.getIntExtra("drive", 0);
            b.append(m.setDrive(v) ? "drive set; " : "drive " + v + " REJECTED; ");
        }
        if (i.hasExtra("regen")) {
            int v = i.getIntExtra("regen", 0);
            b.append(m.setRegen(v) ? "regen set; " : "regen " + v + " REJECTED; ");
        }
        if (i.getBooleanExtra("restore", false)) m.onWake("manual");
        Log.i(CarMode.TAG, b + m.describe());
    }
}