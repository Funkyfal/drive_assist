package com.geely.modehelper;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import com.geely.assist.core.RangeEstimator;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

/**
 * Feeds RangeEstimator from the car every few seconds, keeps its window across
 * restarts and records raw driving samples to a CSV for later tuning.
 * Read-only towards the car: it never writes a property.
 */
final class RangeTracker {
    static final String TAG = CarMode.TAG;

    static final int PROP_SOC = 557885165;        // HV battery SoC, %, float
    static final int PROP_ODO = 289407492;        // odometer, km, float
    static final int PROP_OEM_RANGE = 289407752;  // the car's own range estimate, km, float

    static final double DEFAULT_CAPACITY_KWH = 39.4;
    static final double DEFAULT_WINDOW_KM = 50.0;

    private static final long TICK_MS = 5_000L;
    private static final long RETRY_MS = 30_000L;
    private static final long SAVE_MS = 60_000L;
    private static final long LOG_MS = 60_000L;
    private static final long MAX_CSV_BYTES = 3L * 1024 * 1024;
    private static final String PREFS = "range";

    private static RangeTracker instance;

    /** Starts the tracker once per process; later calls return the same one. */
    static synchronized RangeTracker start(Context ctx) {
        if (instance == null) {
            instance = new RangeTracker(ctx.getApplicationContext());
            instance.thread.start();
        }
        return instance;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final CarMode car = new CarMode();
    private final Thread thread;
    private volatile boolean running = true;

    private RangeEstimator est;                       // guarded by this
    private float lastSoc = Float.NaN, lastOdo = Float.NaN, lastOem = Float.NaN;
    private long lastSaveMs, lastLogMs;
    private int nullStreak;

    private RangeTracker(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.est = build();
        String saved = prefs.getString("state", null);
        if (saved != null && est.load(saved)) Log.i(TAG, "range: restored the saved window");
        this.thread = new Thread(this::loop, "range-tracker");
        this.thread.setDaemon(true);
    }

    private RangeEstimator build() {
        double cap = prefs.getFloat("capacity_kwh", (float) DEFAULT_CAPACITY_KWH);
        double win = prefs.getFloat("window_km", (float) DEFAULT_WINDOW_KM);
        try {
            return new RangeEstimator(cap, win);
        } catch (IllegalArgumentException e) {
            return new RangeEstimator(DEFAULT_CAPACITY_KWH, DEFAULT_WINDOW_KM);
        }
    }

    private void loop() {
        while (running) {
            long sleep = TICK_MS;
            try {
                if (!car.isReady() && !car.connect(app)) sleep = RETRY_MS;
                else tick();
            } catch (Throwable t) { Log.w(TAG, "range: " + t); }
            try { Thread.sleep(sleep); } catch (InterruptedException e) { return; }
        }
    }

    private void tick() {
        Float soc = car.readFloatProp(PROP_SOC, 0);
        Float odo = car.readFloatProp(PROP_ODO, 0);
        if (soc == null || odo == null) {
            // The binder may be dead after a suspend; reconnect after a few misses.
            if (++nullStreak >= 6) {
                Log.i(TAG, "range: no readings, reconnecting");
                car.disconnect();
                nullStreak = 0;
            }
            return;
        }
        nullStreak = 0;
        Float speed = car.readSpeed();
        Float oem = car.readFloatProp(PROP_OEM_RANGE, 0);
        Integer gear = car.readGear();
        float sp = speed == null ? 0f : speed;
        long now = SystemClock.elapsedRealtime();
        boolean driving = RangeEstimator.isDriving(gear, sp);

        synchronized (this) {
            lastSoc = soc;
            lastOdo = odo;
            lastOem = oem == null ? Float.NaN : oem;
            est.accept(now, odo, soc, gear, sp);
            if (now - lastSaveMs >= SAVE_MS) {
                save();
                lastSaveMs = now;
            }
        }
        if (driving) {
            logSample(odo, soc, gear, sp, oem == null ? Float.NaN : oem);
            if (now - lastLogMs >= LOG_MS) {
                lastLogMs = now;
                Log.i(TAG, describe());
            }
        }
    }

    private void save() {
        prefs.edit().putString("state", est.save()).apply();
    }

    /** One line with the latest readings and both ranges. */
    synchronized String describe() {
        double cap = prefs.getFloat("capacity_kwh", (float) DEFAULT_CAPACITY_KWH);
        double win = prefs.getFloat("window_km", (float) DEFAULT_WINDOW_KM);
        if (Float.isNaN(lastSoc)) return "range: no readings yet";
        RangeEstimator.Estimate e = est.estimate(lastSoc);
        if (e == null) return "range: SoC out of range: " + lastSoc;
        return String.format(Locale.US,
                "range: soc=%.1f%% odo=%.1f oem=%s ours=%.0f km cons=%.1f kWh/100km "
                        + "window=%.1f km recent=%.0f%% (capacity %.1f kWh, window %.0f km)",
                lastSoc, lastOdo, Float.isNaN(lastOem) ? "?" : String.format(Locale.US, "%.0f", lastOem),
                e.rangeKm, e.consumptionKwh100, e.windowKm, e.recentWeight * 100.0, cap, win);
    }

    /** Changes capacity and/or window size, optionally clearing the window. */
    synchronized String configure(Double capacity, Double window, boolean reset) {
        SharedPreferences.Editor ed = prefs.edit();
        if (capacity != null && capacity >= 20 && capacity <= 100)
            ed.putFloat("capacity_kwh", capacity.floatValue());
        if (window != null && window >= 5 && window <= 500)
            ed.putFloat("window_km", window.floatValue());
        ed.apply();
        String state = est.save();
        est = build();
        if (!reset) est.load(state);
        save();
        return describe();
    }

    // Raw driving samples for offline tuning. Pull with:
    //   adb pull /data/user/0/com.geely.modehelper/files/range-samples.csv
    private void logSample(float odo, float soc, Integer gear, float speed, float oem) {
        try {
            File f = new File(app.getFilesDir(), "range-samples.csv");
            if (f.length() > MAX_CSV_BYTES) {
                File old = new File(f.getPath() + ".1");
                old.delete();
                f.renameTo(old);
            }
            boolean fresh = !f.exists() || f.length() == 0;
            try (FileWriter w = new FileWriter(f, true)) {
                if (fresh) w.write("wall_ms,odo_km,soc_pct,gear,speed_kmh,oem_km\n");
                w.write(String.format(Locale.US, "%d,%.1f,%.1f,%s,%.0f,%s\n",
                        System.currentTimeMillis(), odo, soc,
                        gear == null ? "" : String.valueOf(gear), speed,
                        Float.isNaN(oem) ? "" : String.format(Locale.US, "%.0f", oem)));
            }
        } catch (IOException e) { Log.w(TAG, "range: csv: " + e); }
    }
}