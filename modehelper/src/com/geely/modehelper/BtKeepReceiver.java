package com.geely.modehelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Status and switches for BluetoothKeeper:
 *   adb shell am broadcast -a com.geely.modehelper.BT_KEEP -n com.geely.modehelper/.BtKeepReceiver
 *   ... --ez on false     stop turning Bluetooth on at start (true = turn it on again)
 *   ... --ez now true     start a watching window now, as if the car had just woken up
 * Guarded by the signature permission in the manifest.
 */
public class BtKeepReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent i) {
        BluetoothKeeper k = BluetoothKeeper.start(ctx);
        if (i.hasExtra("on")) k.setKeepOn(i.getBooleanExtra("on", true));
        if (i.getBooleanExtra("now", false)) k.enforceNow();
        Log.i(CarMode.TAG, k.describe());
    }
}