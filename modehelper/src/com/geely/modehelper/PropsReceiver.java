package com.geely.modehelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Controls PropWatch (a timeline of all car properties for finding what changes):
 *   adb shell am broadcast -a com.geely.modehelper.PROPS -n com.geely.modehelper/.PropsReceiver
 *   ... --ei minutes 15      start for this many minutes (default 10, max 30)
 *   ... --es mark text       add a MARK line (no spaces in the text)
 *   ... --ez stop true       stop now
 * Guarded by the signature permission in the manifest.
 */
public class PropsReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent i) {
        String msg;
        if (i.getBooleanExtra("stop", false)) msg = PropWatch.stop();
        else if (i.hasExtra("mark")) msg = PropWatch.mark(String.valueOf(i.getStringExtra("mark")));
        else msg = PropWatch.start(ctx, i.getIntExtra("minutes", 10));
        EventLog.i(msg);
    }
}