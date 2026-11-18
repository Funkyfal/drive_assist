package com.geely.assist.core;

import java.util.ArrayDeque;

/**
 * Real-world range from recent consumption: the last N km of driving.
 *
 * Plain Java (no Android), so it is unit-tested on a desktop JVM. Feed it one
 * reading per telemetry tick with {@link #accept}; ask for {@link #estimate}
 * whenever the UI needs a number. Thread-safe.
 *
 * Energy of an interval is capacity * (SoC before - SoC after), so regeneration
 * nets out by itself. Only driving intervals count: parking, charging and gaps
 * in the data break the chain instead of being bridged.
 */
public final class RangeEstimator {
    /** CURRENT_GEAR value for Park. */
    public static final int GEAR_PARK = 4;

    /** With fewer km than this in the window only the prior is used. */
    static final double MIN_KM = 2.0;
    /** Full trust in the window after this many km (or the window size, if smaller). */
    static final double FULL_KM = 20.0;
    /** The window is stored as segments of about this length. */
    static final double SEGMENT_KM = 1.0;
    /** Readings further apart than this are not bridged. */
    static final long MAX_GAP_MS = 60_000L;
    /** Not moving for this long in a driving gear stops counting energy. */
    static final long STALL_MS = 10 * 60_000L;
    /** A SoC step implying more power than this is a recalibration jump, not driving. */
    static final double MAX_POWER_KW = 150.0;
    /** More odometer progress than this between two readings is not believable. */
    static final double MAX_STEP_KM = 3.0;
    static final double MIN_KWH_100 = 6.0;
    static final double MAX_KWH_100 = 60.0;

    /** The result of {@link #estimate}. */
    public static final class Estimate {
        /** Remaining range, km. */
        public final double rangeKm;
        /** Consumption used for the range, kWh per 100 km. */
        public final double consumptionKwh100;
        /** Driving distance currently in the window, km. */
        public final double windowKm;
        /** Share of the recent window in the consumption, 0..1 (rest is the prior). */
        public final double recentWeight;

        Estimate(double rangeKm, double consumptionKwh100, double windowKm, double recentWeight) {
            this.rangeKm = rangeKm;
            this.consumptionKwh100 = consumptionKwh100;
            this.windowKm = windowKm;
            this.recentWeight = recentWeight;
        }
    }

    private static final class Segment {
        final double km, kwh;
        Segment(double km, double kwh) { this.km = km; this.kwh = kwh; }
    }

    private final double capacityKwh;
    private final double windowKm;
    private double priorKwh100 = 14.0;
    private double reservePct = 0.0;

    private final ArrayDeque<Segment> segments = new ArrayDeque<>();
    private double pendingKm, pendingKwh;

    private boolean hasAnchor;
    private long anchorTs;
    private double anchorOdo, anchorSoc;
    private long stillSinceMs = -1L;

    /**
     * @param capacityKwh battery capacity, kWh (39.4 for this car)
     * @param windowKm    how many km of recent driving to average (for example 50 or 100)
     */
    public RangeEstimator(double capacityKwh, double windowKm) {
        if (!(capacityKwh > 0)) throw new IllegalArgumentException("capacity must be positive");
        if (!(windowKm >= 5)) throw new IllegalArgumentException("window must be at least 5 km");
        this.capacityKwh = capacityKwh;
        this.windowKm = windowKm;
    }

    /** Consumption assumed before there is enough recent data, kWh per 100 km. */
    public synchronized void setPriorKwh100(double v) {
        if (v >= MIN_KWH_100 && v <= MAX_KWH_100) priorKwh100 = v;
    }

    /** SoC percentage treated as unusable (a safety margin), 0..30. */
    public synchronized void setReservePct(double v) {
        if (v >= 0 && v <= 30) reservePct = v;
    }

    /** True when the gear says the car is driving; with no gear, speed decides. */
    public static boolean isDriving(Integer gear, double speedKmh) {
        if (gear != null) return gear != GEAR_PARK;
        return speedKmh > 0;
    }

    /** One telemetry reading. gear may be null when unknown. */
    public synchronized void accept(long tsMs, double odoKm, double socPct, Integer gear, double speedKmh) {
        boolean valid = isFinite(odoKm) && odoKm > 0 && isFinite(socPct) && socPct >= 0 && socPct <= 100;
        if (!valid || !isDriving(gear, speedKmh)) {
            hasAnchor = false;
            stillSinceMs = -1L;
            return;
        }
        if (!hasAnchor) {
            setAnchor(tsMs, odoKm, socPct);
            stillSinceMs = -1L;
            return;
        }
        long dt = tsMs - anchorTs;
        double dKm = odoKm - anchorOdo;
        if (dt <= 0 || dt > MAX_GAP_MS || dKm < 0 || dKm > MAX_STEP_KM) {
            setAnchor(tsMs, odoKm, socPct);
            stillSinceMs = -1L;
            return;
        }
        double dKwh = capacityKwh * (anchorSoc - socPct) / 100.0;
        double kw = Math.abs(dKwh) / (dt / 3_600_000.0);
        boolean stalled = false;
        if (dKm > 0) {
            stillSinceMs = -1L;
        } else {
            if (stillSinceMs < 0) stillSinceMs = anchorTs;
            stalled = tsMs - stillSinceMs > STALL_MS;
        }
        if (kw <= MAX_POWER_KW && !stalled) add(dKm, dKwh);
        setAnchor(tsMs, odoKm, socPct);
    }

    /** Range for the given SoC, or null when the SoC is not a valid percentage. */
    public synchronized Estimate estimate(double socPct) {
        if (!(socPct >= 0 && socPct <= 100)) return null;   // also rejects NaN
        double km = pendingKm, kwh = pendingKwh;
        for (Segment s : segments) { km += s.km; kwh += s.kwh; }
        double fullKm = Math.min(windowKm, FULL_KM);
        double w = km <= MIN_KM ? 0.0 : Math.min(1.0, (km - MIN_KM) / (fullKm - MIN_KM));
        double recent = km > 0 ? kwh / km * 100.0 : priorKwh100;
        double cons = w * recent + (1.0 - w) * priorKwh100;
        cons = Math.max(MIN_KWH_100, Math.min(MAX_KWH_100, cons));
        double energyLeft = capacityKwh * Math.max(0.0, socPct - reservePct) / 100.0;
        return new Estimate(energyLeft / cons * 100.0, cons, km, w);
    }

    /** Forgets everything, including the anchor. */
    public synchronized void reset() {
        segments.clear();
        pendingKm = 0;
        pendingKwh = 0;
        hasAnchor = false;
        stillSinceMs = -1L;
    }

    /** The window as text, to keep across restarts. */
    public synchronized String save() {
        StringBuilder b = new StringBuilder("v1|");
        b.append(pendingKm).append(',').append(pendingKwh);
        for (Segment s : segments) b.append('|').append(s.km).append(',').append(s.kwh);
        return b.toString();
    }

    /** Restores text from {@link #save}. Returns false (and starts empty) when it is not valid. */
    public synchronized boolean load(String text) {
        reset();
        if (text == null || !text.startsWith("v1|")) return false;
        String[] parts = text.substring(3).split("\\|");
        try {
            double[] p = pair(parts[0]);
            pendingKm = p[0];
            pendingKwh = p[1];
            for (int i = 1; i < parts.length; i++) {
                double[] q = pair(parts[i]);
                segments.addLast(new Segment(q[0], q[1]));
            }
        } catch (RuntimeException e) {
            reset();
            return false;
        }
        trim();
        return true;
    }

    private static double[] pair(String s) {
        String[] a = s.split(",");
        if (a.length != 2) throw new IllegalArgumentException("bad pair");
        double km = Double.parseDouble(a[0]), kwh = Double.parseDouble(a[1]);
        if (!isFinite(km) || !isFinite(kwh) || km < 0) throw new IllegalArgumentException("bad value");
        return new double[] {km, kwh};
    }

    private void setAnchor(long ts, double odo, double soc) {
        hasAnchor = true;
        anchorTs = ts;
        anchorOdo = odo;
        anchorSoc = soc;
    }

    private void add(double dKm, double dKwh) {
        pendingKm += dKm;
        pendingKwh += dKwh;
        if (pendingKm >= SEGMENT_KM) {
            segments.addLast(new Segment(pendingKm, pendingKwh));
            pendingKm = 0;
            pendingKwh = 0;
        }
        trim();
    }

    /** Drops the oldest segments while the rest still covers the window. */
    private void trim() {
        double total = pendingKm;
        for (Segment s : segments) total += s.km;
        while (!segments.isEmpty() && total - segments.peekFirst().km >= windowKm) {
            total -= segments.removeFirst().km;
        }
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
}