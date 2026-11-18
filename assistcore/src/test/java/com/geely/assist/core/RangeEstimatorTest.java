package com.geely.assist.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RangeEstimatorTest {
    private static final double CAP = 39.4;

    // "True" car state; what the estimator sees is quantized like the real
    // sensors: SoC to 0.1 %, odometer to 0.1 km.
    private long ts = 1_000_000L;
    private double odo = 1000.0;
    private double soc = 80.0;

    private static double q(double v) { return Math.round(v * 10.0) / 10.0; }

    private RangeEstimator estimator(double windowKm) {
        RangeEstimator e = new RangeEstimator(CAP, windowKm);
        e.setPriorKwh100(14.0);
        return e;
    }

    /** Drive `km` at a constant consumption (negative = net regeneration), a reading every 5 s. */
    private void drive(RangeEstimator e, double km, double kwhPer100, double speedKmh) {
        double stepKm = speedKmh * 5.0 / 3600.0;
        int steps = (int) Math.round(km / stepKm);
        for (int i = 0; i < steps; i++) {
            ts += 5_000;
            odo += stepKm;
            soc -= stepKm * kwhPer100 / CAP;
            e.accept(ts, q(odo), q(soc), 8, speedKmh);
        }
    }

    /** Park for `minutes` (a reading a minute) while SoC drops by `socDropPct`. */
    private void park(RangeEstimator e, int minutes, double socDropPct) {
        for (int i = 0; i < minutes; i++) {
            ts += 60_000;
            soc -= socDropPct / minutes;
            e.accept(ts, q(odo), q(soc), 4, 0);
        }
    }

    @Test public void steadyDriveGivesTheRealConsumption() {
        RangeEstimator e = estimator(50);
        drive(e, 80, 15.0, 72);
        RangeEstimator.Estimate r = e.estimate(50);
        assertNotNull(r);
        assertEquals(15.0, r.consumptionKwh100, 0.4);
        assertEquals(CAP * 0.5 / 0.15, r.rangeKm, 4.0);   // 131 km
        assertEquals(1.0, r.recentWeight, 1e-9);
    }

    @Test public void parkedTimeDoesNotCount() {
        RangeEstimator e = estimator(50);
        drive(e, 10, 15.0, 72);
        park(e, 60, 3.0);          // an hour of standby drain
        drive(e, 10, 15.0, 72);
        assertEquals(15.0, e.estimate(50).consumptionKwh100, 0.5);
    }

    @Test public void windowForgetsOldDriving() {
        RangeEstimator e = estimator(50);
        drive(e, 60, 25.0, 72);
        drive(e, 60, 10.0, 72);
        assertEquals(10.0, e.estimate(50).consumptionKwh100, 0.4);
    }

    @Test public void windowMixesWhatIsInsideIt() {
        RangeEstimator e = estimator(50);
        drive(e, 60, 25.0, 72);
        drive(e, 30, 10.0, 72);
        // last ~50 km: 30 km at 10 and ~20 km at 25
        assertEquals(16.0, e.estimate(50).consumptionKwh100, 0.7);
    }

    @Test public void fewKilometresUseThePrior() {
        RangeEstimator e = estimator(50);
        drive(e, 1.0, 15.0, 72);
        RangeEstimator.Estimate r = e.estimate(50);
        assertEquals(14.0, r.consumptionKwh100, 1e-9);
        assertEquals(0.0, r.recentWeight, 1e-9);
    }

    @Test public void socRecalibrationJumpIsIgnored() {
        RangeEstimator e = estimator(50);
        drive(e, 20, 15.0, 72);
        soc += 5.0;                 // the BMS suddenly re-estimates upward
        drive(e, 20, 15.0, 72);
        assertEquals(15.0, e.estimate(50).consumptionKwh100, 0.5);
    }

    @Test public void netRegenerationIsClampedToTheFloor() {
        RangeEstimator e = estimator(50);
        drive(e, 10, -5.0, 72);     // a long descent
        RangeEstimator.Estimate r = e.estimate(50);
        assertEquals(6.0, r.consumptionKwh100, 1e-9);
        assertTrue(r.rangeKm > 0 && r.rangeKm < 1000);
    }

    @Test public void invalidSocGivesNoEstimate() {
        RangeEstimator e = estimator(50);
        assertNull(e.estimate(Double.NaN));
        assertNull(e.estimate(101));
        assertNull(e.estimate(-1));
    }

    @Test public void reserveShortensTheRange() {
        RangeEstimator e = estimator(50);
        drive(e, 30, 15.0, 72);
        double plain = e.estimate(50).rangeKm;
        e.setReservePct(5);
        assertTrue(e.estimate(50).rangeKm < plain);
    }

    @Test public void saveAndLoadKeepTheWindow() {
        RangeEstimator a = estimator(50);
        drive(a, 30, 15.0, 72);
        String text = a.save();

        RangeEstimator b = estimator(50);
        assertTrue(b.load(text));
        assertEquals(a.estimate(50).consumptionKwh100, b.estimate(50).consumptionKwh100, 1e-9);
        assertEquals(a.estimate(50).windowKm, b.estimate(50).windowKm, 1e-9);
    }

    @Test public void badSavedTextStartsEmpty() {
        RangeEstimator e = estimator(50);
        drive(e, 30, 15.0, 72);
        assertFalse(e.load("garbage"));
        assertFalse(e.load("v1|1,2|oops"));
        assertEquals(14.0, e.estimate(50).consumptionKwh100, 1e-9);
    }

    @Test public void missingGearFallsBackToSpeed() {
        assertTrue(RangeEstimator.isDriving(null, 10));
        assertFalse(RangeEstimator.isDriving(null, 0));
        assertFalse(RangeEstimator.isDriving(4, 30));   // Park wins
        assertTrue(RangeEstimator.isDriving(8, 0));     // Drive, stopped at a light
    }
}