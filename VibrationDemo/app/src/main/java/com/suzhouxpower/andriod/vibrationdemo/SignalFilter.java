package com.suzhouxpower.andriod.vibrationdemo;

/**
 * Digital signal filters for vibration analysis.
 * All filters operate on 3-axis (X, Y, Z) sample streams.
 */
public class SignalFilter {

    /**
     * 1st-order IIR high-pass filter.
     * Cutoff frequency is configurable; alpha is recomputed from the sample interval each call.
     */
    public static class HighPassFilter {
        private final float cutoffHz;
        private float hpX, hpY, hpZ;
        private float prevRawX, prevRawY, prevRawZ;
        private float alpha;

        public HighPassFilter(float cutoffHz) {
            this.cutoffHz = cutoffHz;
        }

        /** Update filter coefficient for the current sample interval. */
        public void update(float dtSec) {
            float rc = 1f / (2f * (float) Math.PI * cutoffHz);
            alpha = rc / (rc + dtSec);
        }

        /**
         * Apply the high-pass filter.
         * Formula: y[n] = alpha * (y[n-1] + x[n] - x[n-1])
         * Returns filtered [x, y, z].
         */
        public float[] apply(float rawX, float rawY, float rawZ) {
            hpX = alpha * (hpX + rawX - prevRawX);
            hpY = alpha * (hpY + rawY - prevRawY);
            hpZ = alpha * (hpZ + rawZ - prevRawZ);
            prevRawX = rawX;
            prevRawY = rawY;
            prevRawZ = rawZ;
            return new float[]{hpX, hpY, hpZ};
        }

        public void reset() {
            hpX = hpY = hpZ = 0;
            prevRawX = prevRawY = prevRawZ = 0;
            alpha = 0;
        }
    }

    /**
     * Band-pass filter implemented as cascade of high-pass and low-pass.
     */
    public static class BandPassFilter {
        private final HighPassFilter hp;
        private final LowPassFilter lp;

        public BandPassFilter(float lowCutoffHz, float highCutoffHz) {
            this.hp = new HighPassFilter(lowCutoffHz);
            this.lp = new LowPassFilter(highCutoffHz);
        }

        public void update(float dtSec) {
            hp.update(dtSec);
            lp.update(dtSec);
        }

        public float[] apply(float rawX, float rawY, float rawZ) {
            float[] hpOut = hp.apply(rawX, rawY, rawZ);
            return lp.apply(hpOut[0], hpOut[1], hpOut[2]);
        }

        public void reset() {
            hp.reset();
            lp.reset();
        }
    }

    /**
     * 1st-order IIR low-pass filter.
     */
    public static class LowPassFilter {
        private final float cutoffHz;
        private float lpX, lpY, lpZ;
        private float alpha;

        public LowPassFilter(float cutoffHz) {
            this.cutoffHz = cutoffHz;
        }

        public void update(float dtSec) {
            float rc = 1f / (2f * (float) Math.PI * cutoffHz);
            alpha = dtSec / (rc + dtSec);
        }

        /**
         * Apply the low-pass filter.
         * Formula: y[n] = y[n-1] + alpha * (x[n] - y[n-1])
         * Returns filtered [x, y, z].
         */
        public float[] apply(float rawX, float rawY, float rawZ) {
            lpX += alpha * (rawX - lpX);
            lpY += alpha * (rawY - lpY);
            lpZ += alpha * (rawZ - lpZ);
            return new float[]{lpX, lpY, lpZ};
        }

        public void reset() {
            lpX = lpY = lpZ = 0;
            alpha = 0;
        }
    }
}
