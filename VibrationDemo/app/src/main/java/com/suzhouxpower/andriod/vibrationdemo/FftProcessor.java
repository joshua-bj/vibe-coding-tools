package com.suzhouxpower.andriod.vibrationdemo;

/**
 * FFT utilities: Cooley-Tukey radix-2 FFT, windowing, and magnitude calculation.
 */
public class FftProcessor {

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
     * Find the index of the peak magnitude.
     */
    public static int peakIndex(float[] magnitude) {
        int peak = 0;
        float max = magnitude[0];
        for (int i = 1; i < magnitude.length; i++) {
            if (magnitude[i] > max) {
                max = magnitude[i];
                peak = i;
            }
        }
        return peak;
    }
}
