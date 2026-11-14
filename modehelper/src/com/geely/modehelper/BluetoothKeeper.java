package com.geely.modehelper;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

/**
 * Turns Bluetooth back on when the car wakes up or starts.
 *
 * The head unit suspends instead of rebooting, so "waking up" is detected two
 * ways: this process just started, or the loop did not tick for a while (the
 * unit was asleep: SystemClock.elapsedRealtime() keeps counting through suspend).
 * For WINDOW_MS after a wake it re-enables Bluetooth whenever it is found off,
 * then leaves it alone, so a switch-off made by the driver later stays off.
 * It never touches Bluetooth while the unit sleeps, and holds no wakelock.
 */
final class BluetoothKeeper {
    static final String TAG = CarMode.TAG;

    private static final String PREFS = "bt";
    private static final String KEY_KEEP = "keep_on";
    private static final long TICK_MS = 4_000L;
    private static final long WAKE_GAP_MS = 20_000L;
    private static final long WINDOW_MS = 90_000L;
    private static final long RETRY_MS = 8_000L;

    private static BluetoothKeeper instance;

    /** Starts the keeper once per process; later calls return the same one. */
    static synchronized BluetoothKeeper start(Context ctx) {
        if (instance == null) {
            instance = new BluetoothKeeper(ctx.getApplicationContext());
            instance.thread.start();
        }
        return instance;
    }

    private final SharedPreferences prefs;
    private final Thread thread;
    private volatile boolean running = true;
    private volatile int lastState = -1;
    private volatile long enforceUntil;     // elapsedRealtime, 0 = not watching

    private BluetoothKeeper(Context app) {
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.thread = new Thread(this::loop, "bt-keeper");
        this.thread.setDaemon(true);
    }

    boolean isKeepOn() { return prefs.getBoolean(KEY_KEEP, true); }

    void setKeepOn(boolean on) { prefs.edit().putBoolean(KEY_KEEP, on).apply(); }

    /** Starts a watching window now, as if the car had just woken up (for testing). */
    void enforceNow() { enforceUntil = SystemClock.elapsedRealtime() + WINDOW_MS; }

    String describe() {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        boolean watching = SystemClock.elapsedRealtime() < enforceUntil;
        return "bt: keep=" + isKeepOn() + " state=" + name(ad == null ? -1 : ad.getState())
                + " watching=" + watching;
    }

    private void loop() {
        long lastTick = -1L;
        long lastTry = 0L;
        while (running) {
            long now = SystemClock.elapsedRealtime();
            try {
                if (lastTick < 0 || now - lastTick > WAKE_GAP_MS) {
                    String why = lastTick < 0 ? "process start"
                            : "resumed after " + ((now - lastTick) / 1000) + " s";
                    enforceUntil = now + WINDOW_MS;
                    Log.i(TAG, "bt: " + why + (isKeepOn()
                            ? " -> watching Bluetooth for " + (WINDOW_MS / 1000) + " s"
                            : " (keep-on is off)"));
                }
                lastTick = now;

                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                int st = ad == null ? -1 : ad.getState();
                if (st != lastState) {
                    Log.i(TAG, "bt: state " + name(lastState) + " -> " + name(st));
                    lastState = st;
                }
                if (ad != null && isKeepOn() && now < enforceUntil
                        && st == BluetoothAdapter.STATE_OFF && now - lastTry >= RETRY_MS) {
                    lastTry = now;
                    Log.i(TAG, "bt: found off after start -> enable() = " + ad.enable());
                }
            } catch (Throwable t) { Log.w(TAG, "bt: " + t); }
            try { Thread.sleep(TICK_MS); } catch (InterruptedException e) { return; }
        }
    }

    private static String name(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_OFF: return "OFF";
            case BluetoothAdapter.STATE_TURNING_ON: return "TURNING_ON";
            case BluetoothAdapter.STATE_ON: return "ON";
            case BluetoothAdapter.STATE_TURNING_OFF: return "TURNING_OFF";
            case -1: return "none";
            default: return "?(" + state + ")";
        }
    }
}