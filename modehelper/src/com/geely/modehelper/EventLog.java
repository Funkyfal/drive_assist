package com.geely.modehelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A log that survives restarts and a full logcat buffer: every line goes to
 * logcat AND to files/events.log (rotated at 1 MB). Also records the power,
 * screen and start/shutdown signals, and tells ModeMemory about them.
 * Pull it with:
 *   adb pull /data/user/0/com.geely.modehelper/files/events.log ~/geely/
 */
final class EventLog {
    private static final String TAG = CarMode.TAG;
    private static final long MAX_BYTES = 1024 * 1024;
    private static final String SHUTDOWN_HU = "android.intent.action.ACTION_SHUTDOWN_HU";
    private static final String BOOT_HU = "android.intent.action.ACTION_BOOT_HU";
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static File file;

    private EventLog() { }

    /** Opens the file and starts recording signals. Call once per process. */
    static void start(Context ctx) {
        Context app = ctx.getApplicationContext();
        synchronized (EventLog.class) {
            if (file == null) file = new File(app.getFilesDir(), "events.log");
        }
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SHUTDOWN);
        f.addAction(Intent.ACTION_REBOOT);
        f.addAction(SHUTDOWN_HU);
        f.addAction(BOOT_HU);
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent it) {
                String a = it.getAction();
                i("signal " + a);
                if (a == null) return;
                if (a.equals(SHUTDOWN_HU) || a.equals(Intent.ACTION_SHUTDOWN)
                        || a.equals(Intent.ACTION_REBOOT)) {
                    ModeMemory.goingOff(a);
                } else if (a.equals(BOOT_HU)) {
                    ModeMemory.wake(a);
                }
            }
        }, f);
        i("event log started, pid " + android.os.Process.myPid());
    }

    static synchronized void i(String msg) {
        Log.i(TAG, msg);
        if (file == null) return;
        try {
            if (file.length() > MAX_BYTES) {
                File old = new File(file.getPath() + ".1");
                old.delete();
                file.renameTo(old);
            }
            try (FileWriter w = new FileWriter(file, true)) {
                w.write(FMT.format(new Date()) + " up=" + (SystemClock.elapsedRealtime() / 1000)
                        + "s " + msg + "\n");
            }
        } catch (IOException e) {
            Log.w(TAG, "eventlog: " + e);
        }
    }
}