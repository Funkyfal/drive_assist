package com.geely.assist.core;

/**
 * When a mode the car may reset (drive mode, regeneration) must be written back,
 * and when a value the car shows counts as the driver's choice.
 *
 * Plain Java, no Android, so it is tested on a desktop JVM. Not thread-safe:
 * the caller serialises access. All times are milliseconds on one clock.
 *
 * Restoring: after a wake (or an explicit choice) a value that differs from the
 * saved one is written back, only while parked, spaced by SETTLE_MS, at most
 * MAX_WRITES times, until it matches, the driver picks something else, or
 * RESTORE_MS passes.
 *
 * Learning (off by default): a differing value that stays unchanged for
 * STABLE_MS becomes the saved one, but never while the car is going off, never
 * during QUIET_MS after a wake, and never when it is the very value the car
 * reset to and we could not restore.
 */
public final class ModePolicy {
    public static final long RESTORE_MS = 3 * 60_000L;
    public static final long SETTLE_MS = 6_000L;
    public static final int MAX_WRITES = 6;
    public static final long STABLE_MS = 8_000L;
    public static final long QUIET_MS = 60_000L;

    /** What the caller should do after one observation. */
    public static final class Step {
        /** Value to write to the car, or null. */
        public final Integer write;
        /** Value that just became the saved one by learning, or null. */
        public final Integer learned;
        /** Short remark for the event log, or null. */
        public final String note;

        Step(Integer write, Integer learned, String note) {
            this.write = write;
            this.learned = learned;
            this.note = note;
        }
    }

    private static final Step NONE = new Step(null, null, null);

    private final int[] allowed;
    private boolean learning;
    private Integer saved;

    private boolean pending;
    private long pendingUntil;
    private Integer resetValue;
    private int writes;
    private long lastWriteMs = Long.MIN_VALUE / 2;

    private boolean goingOff;
    private long quietUntil;
    private Integer suppressed;
    private Integer candidate;
    private long candidateSince;

    public ModePolicy(int... allowed) {
        this.allowed = allowed.clone();
    }

    public boolean isAllowed(int v) {
        for (int a : allowed) if (a == v) return true;
        return false;
    }

    public void setLearning(boolean on) {
        learning = on;
        candidate = null;
    }

    public boolean isLearning() { return learning; }

    public Integer saved() { return saved; }

    public boolean isPending() { return pending; }

    /** Loads a saved value from storage without acting on it. Unknown values are dropped. */
    public void load(Integer v) {
        saved = (v != null && isAllowed(v)) ? v : null;
    }

    /** An explicit choice: saves it and starts restoring. False if it is not a known value. */
    public boolean choose(int v, long now) {
        if (!isAllowed(v)) return false;
        saved = v;
        candidate = null;
        suppressed = null;
        arm(now);
        return true;
    }

    public void forget() {
        saved = null;
        pending = false;
        candidate = null;
        suppressed = null;
        resetValue = null;
    }

    /** The car started or woke up. */
    public void onWake(long now) {
        goingOff = false;
        quietUntil = now + QUIET_MS;
        candidate = null;
        suppressed = null;
        if (saved != null) arm(now);
    }

    /** The car is shutting down or suspending: whatever it does to the mode now is not a choice. */
    public void onGoingOff() {
        goingOff = true;
        pending = false;
        candidate = null;
    }

    private void arm(long now) {
        pending = true;
        pendingUntil = now + RESTORE_MS;
        resetValue = null;
        writes = 0;
    }

    /** One reading of the car's value (null if unreadable). */
    public Step observe(Integer car, boolean parked, long now) {
        if (car == null || !isAllowed(car)) return NONE;

        if (saved == null) {
            if (!learning) return NONE;
            saved = car;
            return new Step(null, car, "first value remembered");
        }
        if (car.equals(saved)) {
            boolean wasPending = pending;
            pending = false;
            candidate = null;
            suppressed = null;
            return wasPending ? new Step(null, null, "as saved") : NONE;
        }

        if (pending) {
            if (resetValue == null) resetValue = car;
            boolean settled = now - lastWriteMs >= SETTLE_MS;
            if (!car.equals(resetValue) && settled) {
                pending = false;                    // someone picked another mode: stop fighting it
            } else if (now > pendingUntil) {
                pending = false;
                suppressed = car;
                return new Step(null, null, "restore gave up (time)");
            } else if (writes >= MAX_WRITES && settled) {
                pending = false;
                suppressed = car;
                return new Step(null, null, "restore gave up after " + writes + " writes");
            } else if (!parked || !settled) {
                return NONE;
            } else {
                writes++;
                lastWriteMs = now;
                return new Step(saved, null, "restore write " + writes);
            }
        }

        // learning
        if (suppressed != null && !car.equals(suppressed)) suppressed = null;
        if (!learning || goingOff || now < quietUntil || suppressed != null) {
            candidate = null;
            return NONE;
        }
        if (candidate == null || !candidate.equals(car)) {
            candidate = car;
            candidateSince = now;
            return NONE;
        }
        if (now - candidateSince >= STABLE_MS) {
            saved = car;
            candidate = null;
            return new Step(null, car, "learned");
        }
        return NONE;
    }
}