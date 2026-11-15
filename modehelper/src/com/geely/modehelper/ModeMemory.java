package com.geely.modehelper;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import com.geely.assist.core.ModePolicy;

/**
 * Keeps the drive and regeneration modes the driver wants and puts them back
 * after the car wakes up or starts. The decisions are ModePolicy's; this class
 * reads the car, writes it, stores the saved values and logs everything to
 * EventLog. Learning from what the driver picks is OFF until switched on.
 */
final class ModeMemory {
    static final String TAG = CarMode.TAG;

    private static final String PREFS = "modes";
    private static final long TICK_MS = 2_000L;
    private static final long RETRY_MS = 15_000L;
    private static final long WAKE_GAP_MS = 20_000L;

    private static ModeMemory instance;

    /** Starts the memory once per process; later calls return the same one. */
    static synchronized ModeMemory start(Context ctx) {
        if (instance == null) {
            instance = new ModeMemory(ctx.getApplicationContext());
            instance.thread.start();
        }
        return instance;
    }

    /** The car woke up or started; no-op before start(). */
    static void wake(String why) {
        ModeMemory m = get();
        if (m != null) m.onWake(why);
    }

    /** The car is going off; no-op before start(). */
    static void goingOff(String why) {
        ModeMemory m = get();
        if (m != null) m.onGoingOff(why);
    }

    private static synchronized ModeMemory get() { return instance; }

    static String driveName(Integer v) {
        if (v == null) return "?";
        switch (v) {
            case CarMode.DRIVE_ECO: return "Eco";
            case CarMode.DRIVE_COMFORT: return "Comfort";
            case CarMode.DRIVE_SPORT: return "Sport";
            default: return String.valueOf(v);
        }
    }

    static String regenName(Integer v) {
        if (v == null) return "?";
        switch (v) {
            case CarMode.REGEN_LOW: return "Low";
            case CarMode.REGEN_MID: return "Mid";
            case CarMode.REGEN_HIGH: return "High";
            default: return String.valueOf(v);
        }
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final CarMode car = new CarMode();
    private final Thread thread;
    private volatile boolean running = true;
    private final Mode drive, regen;
    private int nullStreak;
    private boolean lastParked;
    private boolean powerWatchTried;

    private ModeMemory(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.drive = new Mode("drive", true,
                CarMode.DRIVE_ECO, CarMode.DRIVE_COMFORT, CarMode.DRIVE_SPORT);
        this.regen = new Mode("regen", false,
                CarMode.REGEN_LOW, CarMode.REGEN_MID, CarMode.REGEN_HIGH);
        this.thread = new Thread(this::loop, "mode-memory");
        this.thread.setDaemon(true);
    }

    // ---------------------------------------------------------------- public

    synchronized void onWake(String why) {
        long now = SystemClock.elapsedRealtime();
        drive.policy.onWake(now);
        regen.policy.onWake(now);
        EventLog.i("modes: wake (" + why + ")");
    }

    synchronized void onGoingOff(String why) {
        drive.policy.onGoingOff();
        regen.policy.onGoingOff();
        EventLog.i("modes: going off (" + why + ")");
    }

    /** Saves a drive mode and applies it at once (in Park). False if not a known value. */
    synchronized boolean setDrive(int v) {
        if (!drive.policy.choose(v, SystemClock.elapsedRealtime())) return false;
        drive.persist();
        EventLog.i("modes: drive chosen " + driveName(v));
        return true;
    }

    /** Saves a regeneration mode and applies it at once (in Park). False if not a known value. */
    synchronized boolean setRegen(int v) {
        if (!regen.policy.choose(v, SystemClock.elapsedRealtime())) return false;
        regen.persist();
        EventLog.i("modes: regen chosen " + regenName(v));
        return true;
    }

    /** Learning from what the driver picks in the car's own screens. */
    synchronized void setLearning(boolean on) {
        prefs.edit().putBoolean("learn", on).apply();
        drive.policy.setLearning(on);
        regen.policy.setLearning(on);
        EventLog.i("modes: learning " + (on ? "ON" : "OFF"));
    }

    synchronized void forget() {
        drive.policy.forget();
        regen.policy.forget();
        drive.persist();
        regen.persist();
        EventLog.i("modes: saved modes forgotten");
    }

    synchronized String describe() {
        return "modes: learn=" + drive.policy.isLearning()
                + " drive=" + driveName(drive.lastSeen) + " saved=" + driveName(drive.policy.saved())
                + " pending=" + drive.policy.isPending()
                + " | regen=" + regenName(regen.lastSeen) + " saved=" + regenName(regen.policy.saved())
                + " pending=" + regen.policy.isPending()
                + " | parked=" + lastParked;
    }

    // ---------------------------------------------------------------- loop

    private void loop() {
        long lastTick = -1L;
        while (running) {
            long sleep = TICK_MS;
            try {
                if (!car.isReady() && !car.connect(app)) {
                    sleep = RETRY_MS;
                } else {
                    long now = SystemClock.elapsedRealtime();
                    if (lastTick < 0) onWake("process start");
                    else if (now - lastTick > WAKE_GAP_MS)
                        onWake("resumed after " + ((now - lastTick) / 1000) + " s");
                    lastTick = now;
                    maybeWatchPower();
                    tick(now);
                }
            } catch (Throwable t) { Log.w(TAG, "modes: " + t); }
            try { Thread.sleep(sleep); } catch (InterruptedException e) { return; }
        }
    }

    // The car's own power states: one attempt per process.
    private void maybeWatchPower() {
        if (powerWatchTried || !car.isReady()) return;
        powerWatchTried = true;
        try {
            boolean ok = PowerWatch.register(car.powerManager(), state -> {
                EventLog.i("power state " + state);
                if (state == PowerWatch.SUSPEND_ENTER || state == PowerWatch.SHUTDOWN_ENTER) {
                    goingOff("power state " + state);
                } else if (state == PowerWatch.SUSPEND_EXIT || state == PowerWatch.SHUTDOWN_CANCELLED) {
                    wake("power state " + state);
                }
            });
            EventLog.i("power watch: " + (ok ? "registered" : "no power manager"));
        } catch (Throwable t) { Log.w(TAG, "power watch: " + t); }
    }

    private synchronized void tick(long now) {
        Integer d = car.readDrive(), r = car.readRegen();
        if (d == null && r == null) {
            // The binder may be dead after a suspend; reconnect after a few misses.
            if (++nullStreak >= 6) {
                EventLog.i("modes: no readings, reconnecting");
                car.disconnect();
                nullStreak = 0;
            }
            return;
        }
        nullStreak = 0;
        Integer g = car.readGear();
        boolean parked = g != null && g == CarMode.GEAR_PARK;
        if (parked != lastParked || g != lastGear) {
            EventLog.i("modes: gear " + g + " parked=" + parked);
            lastParked = parked;
            lastGear = g;
        }
        drive.observe(d, parked, now);
        regen.observe(r, parked, now);
    }

    private Integer lastGear;

    // ---------------------------------------------------------------- one mode

    private final class Mode {
        final String key;
        final boolean isDrive;
        final ModePolicy policy;
        Integer lastSeen;

        Mode(String key, boolean isDrive, int... allowed) {
            this.key = key;
            this.isDrive = isDrive;
            this.policy = new ModePolicy(allowed);
            this.policy.load(prefs.contains(key) ? prefs.getInt(key, 0) : null);
            this.policy.setLearning(prefs.getBoolean("learn", false));
        }

        String name(Integer v) { return isDrive ? driveName(v) : regenName(v); }

        void persist() {
            Integer s = policy.saved();
            if (s == null) prefs.edit().remove(key).apply();
            else prefs.edit().putInt(key, s).apply();
        }

        void observe(Integer carVal, boolean parked, long now) {
            if (carVal != null && !carVal.equals(lastSeen)) {
                EventLog.i("modes: " + key + " car value " + name(lastSeen) + " -> " + name(carVal)
                        + " (parked=" + parked + ", saved " + name(policy.saved()) + ")");
                lastSeen = carVal;
            }
            ModePolicy.Step s = policy.observe(carVal, parked, now);
            if (s.note != null) {
                EventLog.i("modes: " + key + " " + s.note + " (car " + name(carVal)
                        + ", saved " + name(policy.saved()) + ")");
            }
            if (s.learned != null) persist();
            if (s.write != null) {
                if (isDrive) car.writeDrive(s.write); else car.writeRegen(s.write);
                EventLog.i("modes: " + key + " WRITE " + name(s.write));
            }
        }
    }
}