package com.suzhouxpower.andriod.vibrationdemo;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.util.ArrayList;
import java.util.Locale;

public class VoiceprintFragment extends Fragment {

    private static final int SAMPLE_RATE = 44100;
    private static final int MAX_DISPLAY_FREQ = 8000;
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int UPDATE_INTERVAL_MS = 100;

    private static final int[] FFT_SIZES = {512, 1024, 2048, 4096, 8192};
    private static final String[] FFT_LABELS = {"512", "1024", "2048", "4096", "8192"};

    private BarChart chart;
    private TextView tvPeakFreq;
    private Spinner spinnerFftSize;

    private AudioRecord audioRecord;
    private Thread recordingThread;
    private volatile boolean isRecording = false;
    private volatile int fftSize = 2048;

    private long lastUpdateTime = 0;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_voiceprint, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        tvPeakFreq = view.findViewById(R.id.tvPeakFreq);
        chart = view.findViewById(R.id.fftChart);
        spinnerFftSize = view.findViewById(R.id.spinnerFftSize);

        setupChart();
        setupSpinner();
    }

    private void setupChart() {
        chart.getDescription().setText("Frequency Spectrum (Hz)");
        chart.getDescription().setTextSize(12f);
        chart.getDescription().setTextColor(Color.WHITE);
        chart.setDrawGridBackground(false);
        chart.setPinchZoom(true);
        chart.setFitBars(true);

        XAxis xl = chart.getXAxis();
        xl.setPosition(XAxis.XAxisPosition.BOTTOM);
        xl.setDrawGridLines(false);
        xl.setGranularity(50f);
        xl.setLabelCount(9);
        xl.setAxisMinimum(0f);
        xl.setAxisMaximum(MAX_DISPLAY_FREQ);
        xl.setTextColor(Color.WHITE);
        xl.setTextSize(11f);

        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setTextColor(Color.WHITE);
        leftAxis.setTextSize(11f);
        leftAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format(Locale.US, "%.1f", value);
            }
        });

        YAxis rightAxis = chart.getAxisRight();
        rightAxis.setEnabled(true);
        rightAxis.setDrawGridLines(false);
        rightAxis.setTextColor(Color.WHITE);
        rightAxis.setTextSize(11f);
        rightAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                if (value <= 0) return "-∞ dB";
                float db = 20f * (float) Math.log10(value);
                return String.format(Locale.US, "%.0f dB", db);
            }
        });
        chart.getLegend().setEnabled(false);

        BarDataSet set = new BarDataSet(new ArrayList<>(), "Magnitude");
        set.setColor(Color.parseColor("#2196F3"));
        chart.setData(new BarData(set));
        chart.invalidate();
    }

    private void setupSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, FFT_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerFftSize.setAdapter(adapter);
        // Default: 2048 = index 2
        spinnerFftSize.setSelection(2);

        spinnerFftSize.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int newSize = FFT_SIZES[position];
                if (newSize != fftSize) {
                    fftSize = newSize;
                    restartRecording();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void restartRecording() {
        boolean wasRecording = isRecording;
        stopRecording();
        if (wasRecording) {
            startRecording();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startRecording();
        } else {
            ActivityCompat.requestPermissions(requireActivity(),
                    new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        stopRecording();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording();
            }
        }
    }

    private void startRecording() {
        if (isRecording) return;

        int size = fftSize;
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            return;
        }

        bufferSize = Math.max(bufferSize, size * 2);

        audioRecord = new AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release();
            audioRecord = null;
            return;
        }

        isRecording = true;
        audioRecord.startRecording();

        recordingThread = new Thread(this::recordingLoop);
        recordingThread.start();
    }

    private void stopRecording() {
        isRecording = false;
        if (recordingThread != null) {
            try {
                recordingThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            recordingThread = null;
        }
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (IllegalStateException ignored) {
            }
            audioRecord.release();
            audioRecord = null;
        }
    }

    private void recordingLoop() {
        while (isRecording) {
            int size = fftSize;
            float freqResolution = (float) SAMPLE_RATE / size;
            int displayBins = MAX_DISPLAY_FREQ * size / SAMPLE_RATE;

            short[] buffer = new short[size];
            float[] real = new float[size];
            float[] imag = new float[size];

            int read = audioRecord.read(buffer, 0, size);
            if (read != size || !isRecording) continue;

            for (int i = 0; i < size; i++) {
                real[i] = buffer[i] / 32768f;
                imag[i] = 0f;
            }
            FftProcessor.hammingWindow(real);

            FftProcessor.fft(real, imag);
            float[] mag = FftProcessor.magnitude(real, imag);

            long now = System.currentTimeMillis();
            if (now - lastUpdateTime < UPDATE_INTERVAL_MS) continue;
            lastUpdateTime = now;

            int peakIdx = FftProcessor.peakIndex(mag);
            float peakFreq = peakIdx * freqResolution;

            ArrayList<BarEntry> entries = new ArrayList<>(displayBins);
            for (int i = 0; i < displayBins; i++) {
                entries.add(new BarEntry(i * freqResolution, mag[i]));
            }

            float barWidth = freqResolution * 0.8f;

            if (isAdded() && getActivity() != null) {
                requireActivity().runOnUiThread(() -> updateChart(entries, peakFreq, barWidth));
            }
        }
    }

    private void updateChart(ArrayList<BarEntry> entries, float peakFreq, float barWidth) {
        tvPeakFreq.setText(String.format(Locale.US, "Peak: %.1f Hz", peakFreq));

        BarDataSet set = new BarDataSet(entries, "Magnitude");
        set.setColor(Color.parseColor("#2196F3"));
        set.setDrawValues(false);

        BarData data = new BarData(set);
        data.setBarWidth(barWidth);

        chart.setData(data);
        chart.notifyDataSetChanged();
        chart.invalidate();
    }
}
