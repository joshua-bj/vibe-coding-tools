package com.suzhouxpower.andriod.vibrationdemo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * FFT utilities: Cooley-Tukey radix-2 FFT, windowing, and magnitude calculation.
 */
public class FftProcessor {

    /**
     * Represents a detected spectral peak with interpolated frequency.
     */
    public static class FftPeak implements Comparable<FftPeak> {
        public final int binIndex;
        public final float interpolatedBin;
        public final float frequencyHz;
        public final float magnitude;

        public FftPeak(int binIndex, float interpolatedBin, float frequencyHz, float magnitude) {
            this.binIndex = binIndex;
            this.interpolatedBin = interpolatedBin;
            this.frequencyHz = frequencyHz;
            this.magnitude = magnitude;
        }

        @Override
        public int compareTo(FftPeak other) {
            return Float.compare(other.magnitude, this.magnitude);
        }
    }

    /**
     * In-place Cooley-Tukey radix-2 FFT.
     * Arrays must be power-of-two length.
     */
    public static void fft(float[] real, float[] imag) {
        int n = real.length;
        if ((n & (n - 1)) != 0) {
            throw new IllegalArgumentException("Length must be power of two");
        }

        // Bit-reversal permutation
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) {
                j ^= bit;
                bit >>= 1;
            }
            j ^= bit;
            if (i < j) {
                float tmpR = real[i]; real[i] = real[j]; real[j] = tmpR;
                float tmpI = imag[i]; imag[i] = imag[j]; imag[j] = tmpI;
            }
        }

        // FFT butterfly
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            float wReal = (float) Math.cos(angle);
            float wImag = (float) Math.sin(angle);

            for (int i = 0; i < n; i += len) {
                float curReal = 1, curImag = 0;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j;
                    int v = i + j + len / 2;
                    float tReal = curReal * real[v] - curImag * imag[v];
                    float tImag = curReal * imag[v] + curImag * real[v];
                    real[v] = real[u] - tReal;
                    imag[v] = imag[u] - tImag;
                    real[u] += tReal;
                    imag[u] += tImag;
                    float newCurReal = curReal * wReal - curImag * wImag;
                    curImag = curReal * wImag + curImag * wReal;
                    curReal = newCurReal;
                }
            }
        }
    }

    /**
     * Compute magnitudes from real/imag FFT output.
     * Returns array of length n/2 (only positive frequencies).
     */
    public static float[] magnitude(float[] real, float[] imag) {
        int n = real.length / 2;
        float[] mag = new float[n];
        for (int i = 0; i < n; i++) {
            mag[i] = (float) Math.sqrt(real[i] * real[i] + imag[i] * imag[i]);
        }
        return mag;
    }

    /**
     * Apply a Hamming window in-place.
     */
    public static void hammingWindow(float[] data) {
        int n = data.length;
        for (int i = 0; i < n; i++) {
            data[i] *= (float) (0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / (n - 1)));
        }
    }

    /**
     * Find the index of the peak magnitude (skips DC bin 0).
     */
    public static int peakIndex(float[] magnitude) {
        int peak = 1;
        float max = magnitude[1];
        for (int i = 2; i < magnitude.length; i++) {
            if (magnitude[i] > max) {
                max = magnitude[i];
                peak = i;
            }
        }
        return peak;
    }

    /**
     * Parabolic interpolation around the peak bin for sub-bin accuracy.
     * Returns the fractional peak index (can be between bins).
     */
    public static float interpolatedPeakIndex(float[] magnitude, int peakIdx) {
        // Not enough neighbors for interpolation
        if (peakIdx <= 0 || peakIdx >= magnitude.length - 1) {
            return peakIdx;
        }
        float yPrev = magnitude[peakIdx - 1];
        float yPeak = magnitude[peakIdx];
        float yNext = magnitude[peakIdx + 1];
        float denom = yPrev - 2f * yPeak + yNext;
        if (denom == 0f) {
            return peakIdx;
        }
        float delta = 0.5f * (yPrev - yNext) / denom;
        // Clamp to ±0.5 bin to avoid overshoot
        delta = Math.max(-0.5f, Math.min(0.5f, delta));
        return peakIdx + delta;
    }

    /**
     * Detect multiple dominant frequency peaks using a 4-stage pipeline:
     *   1. Find local maxima above a relative threshold (10% of global max)
     *   2. Minimum distance filter (50 Hz between peaks)
     *   3. Prominence filter (must stand out from baseline by 5% of global max)
     *   4. Parabolic interpolation for sub-bin accuracy
     *
     * @param magnitude  FFT magnitude spectrum (length fftSize/2)
     * @param sampleRate audio sample rate in Hz
     * @param maxBins    limit search to this many bins (visible frequency range)
     * @param fftSize    the FFT size used to produce the magnitude array
     * @return list of detected peaks sorted by magnitude descending (max 8)
     */
    public static List<FftPeak> findDominantPeaks(float[] magnitude, float sampleRate,
                                                   int maxBins, int fftSize) {
        final float THRESHOLD_FRACTION = 0.10f;
        final float MIN_DISTANCE_HZ = 50.0f;
        final float PROMINENCE_FRACTION = 0.05f;
        final int MAX_PEAKS = 8;

        int len = Math.min(maxBins, magnitude.length);
        float freqRes = sampleRate / (float) fftSize;

        // Find global maximum (skip DC bin 0)
        float maxMag = 0f;
        for (int i = 1; i < len; i++) {
            if (magnitude[i] > maxMag) maxMag = magnitude[i];
        }

        // If signal is essentially silence, return empty
        if (maxMag < 1e-10f) {
            return Collections.emptyList();
        }

        float threshold = THRESHOLD_FRACTION * maxMag;
        float prominenceThreshold = PROMINENCE_FRACTION * maxMag;
        int minDistBins = Math.max(1, (int) Math.ceil(MIN_DISTANCE_HZ / freqRes));

        // Stage 1+2: local maxima above relative threshold
        ArrayList<Integer> candidates = new ArrayList<>();
        for (int i = 1; i < len - 1; i++) {
            if (magnitude[i] > magnitude[i - 1] && magnitude[i] > magnitude[i + 1]
                    && magnitude[i] >= threshold) {
                candidates.add(i);
            }
        }
        // Right edge
        if (len > 2 && magnitude[len - 1] > magnitude[len - 2] && magnitude[len - 1] >= threshold) {
            candidates.add(len - 1);
        }

        // Sort candidates by magnitude descending
        candidates.sort((a, b) -> Float.compare(magnitude[b], magnitude[a]));

        // Stage 3: minimum distance filter (greedy acceptance)
        ArrayList<Integer> accepted = new ArrayList<>();
        for (int idx : candidates) {
            boolean tooClose = false;
            for (int accIdx : accepted) {
                if (Math.abs(idx - accIdx) < minDistBins) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) {
                accepted.add(idx);
            }
        }

        // Stage 4: prominence filter
        ArrayList<FftPeak> results = new ArrayList<>();
        for (int peakIdx : accepted) {
            float prominence = computeProminence(magnitude, peakIdx, len);
            if (prominence < prominenceThreshold) continue;

            float interpIdx = interpolatedPeakIndex(magnitude, peakIdx);
            float freq = interpIdx * freqRes;
            results.add(new FftPeak(peakIdx, interpIdx, freq, magnitude[peakIdx]));
        }

        // Sort descending by magnitude, cap at MAX_PEAKS
        Collections.sort(results);
        if (results.size() > MAX_PEAKS) {
            results = new ArrayList<>(results.subList(0, MAX_PEAKS));
        }

        return results;
    }

    /**
     * Compute the prominence of a peak: how much it stands above the higher of
     * the two surrounding valleys. Each direction scans until hitting a higher bin.
     */
    private static float computeProminence(float[] magnitude, int peakIdx, int len) {
        float peakMag = magnitude[peakIdx];

        // Scan left
        float leftMin = peakMag;
        for (int i = peakIdx - 1; i >= 0; i--) {
            if (magnitude[i] > peakMag) break;
            if (magnitude[i] < leftMin) leftMin = magnitude[i];
        }

        // Scan right
        float rightMin = peakMag;
        for (int i = peakIdx + 1; i < len; i++) {
            if (magnitude[i] > peakMag) break;
            if (magnitude[i] < rightMin) rightMin = magnitude[i];
        }

        return peakMag - Math.max(leftMin, rightMin);
    }
}
