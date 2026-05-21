package com.suzhouxpower.andriod.vibrationdemo;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Elevator velocity & direction fragment.
 *
 * Signal chain (Z-axis only):
 *   TYPE_ACCELEROMETER → subtract gravity baseline (measured during warmup) → integrate → LP 5Hz (smooth)
 *
 * Gravity removal: direct subtraction of the average raw Z during warmup (device stationary).
 * This preserves real constant acceleration — unlike HP filter which removes it as "DC".
 *
 * Drift correction: exponential decay toward zero only during true STATIONARY state
 * (both velocity and acceleration near zero).
 */
public class ElevatorFragment extends Fragment implements SensorEventListener {

    private static final int MAX_DATA_POINTS = 200;
    private static final float LP_CUTOFF_HZ = 5.0f;        // smooth elevator vibration for direction
    private static final float DRIFT_DECAY = 0.96f;         // per-sample decay at ~50 Hz, tau ≈ 0.5s
    private static final float BASELINE_TRACK = 0.002f;     // per-sample EMA alpha, tau ≈ 10s at 50 Hz
    private static final float ACCEL_QUIET = 0.03f;         // m/s² — below this = no significant accel
    private static final float NOISE_THRESHOLD = 0.05f;     // m/s — below this = stationary
    private static final long SETTLE_SHORT_NS  = 500_000_000L;  // 0.5s for low-speed movements
    private static final long SETTLE_LONG_NS   = 1_000_000_000L; // 1.0s after high-speed movement
    private static final float HIGH_SPEED_MM_S = 200f;      // mm/s — above this, use long settle
    private static final int WARMUP_SAMPLES = 50;           // ~1 second at 50 Hz — measure gravity baseline

    // Direction constants
    private static final int DIR_STATIONARY = 0;
    private static final int DIR_UP = 1;
    private static final int DIR_DOWN = 2;

    private SensorManager sensorManager;
    private Sensor accelerometer;

    private LineChart chart;
    private TextView tvDirection;
    private TextView tvSpeed;
    private TextView tvVelZ;
    private CheckBox cbDriftFilter;
    private CheckBox cbSmoothFilter;
    private MaterialButton btnRecord;
    private TextView tvRecordStatus;

    private boolean driftEnabled = true;
    private boolean smoothEnabled = true;

    // Recording state
    private boolean isRecording = false;
    private long recordStartNs = 0;
    private StringBuilder csvBuffer = null;

    // Filters
    private final SignalFilter.LowPassFilter lpFilter = new SignalFilter.LowPassFilter(LP_CUTOFF_HZ);

    // Gravity baseline (measured during warmup)
    private float gravityBaseline = 0f;
    private float warmupSum = 0f;
    private int warmupSamplesCollected = 0;

    // Velocity state (Z-axis only)
    private float rawVelZ = 0f;        // pure integrated velocity
    private float displayVelZ = 0f;    // after drift filter — for chart
    private float smoothVelZ = 0f;     // after LP filter — for direction judgment

    private int dataIndex = 0;
    private long prevTimeNs = 0;
    private int warmupCount = 0;

    // Direction state machine
    private int direction = DIR_STATIONARY;
    private long belowThresholdStartNs = 0;
    private float peakAbsSpeedMmS = 0f;  // peak |speed| during current movement

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_elevator, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        tvDirection = view.findViewById(R.id.tvDirection);
        tvSpeed = view.findViewById(R.id.tvSpeed);
        tvVelZ = view.findViewById(R.id.tvVelZ);
        chart = view.findViewById(R.id.elevatorChart);
        cbDriftFilter = view.findViewById(R.id.cbDriftFilter);
        cbSmoothFilter = view.findViewById(R.id.cbSmoothFilter);
        btnRecord = view.findViewById(R.id.btnRecord);
        tvRecordStatus = view.findViewById(R.id.tvRecordStatus);

        cbDriftFilter.setText("Drift Fix");
        cbDriftFilter.setChecked(driftEnabled);
        cbDriftFilter.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
            driftEnabled = isChecked;
            resetState();
        });

        cbSmoothFilter.setText(String.format(Locale.US, "Smooth LP %.0f Hz", LP_CUTOFF_HZ));
        cbSmoothFilter.setChecked(smoothEnabled);
        cbSmoothFilter.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
            smoothEnabled = isChecked;
            lpFilter.reset();
            smoothVelZ = displayVelZ;
        });

        setupChart();
        initChartData();

        btnRecord.setOnClickListener(v -> {
            if (!isRecording) {
                startRecording();
            } else {
                stopRecording();
            }
        });

        view.findViewById(R.id.btnReset).setOnClickListener(v -> resetState());

        sensorManager = (SensorManager) requireContext().getSystemService(Context.SENSOR_SERVICE);
    }

    private void startRecording() {
        isRecording = true;
        recordStartNs = 0;
        csvBuffer = new StringBuilder();
        // UTF-8 BOM for Excel compatibility
        csvBuffer.append('\uFEFF');
        csvBuffer.append("time_ms,accel_z_mps2,velocity_mm_s,smooth_mm_s,direction\n");
        btnRecord.setText("Stop");
        btnRecord.setStrokeColorResource(android.R.color.holo_red_dark);
        btnRecord.setTextColor(Color.parseColor("#F44336"));
        tvRecordStatus.setText("Recording...");
        tvRecordStatus.setTextColor(Color.parseColor("#F44336"));
    }

    private void stopRecording() {
        isRecording = false;
        btnRecord.setText("Record");
        btnRecord.setStrokeColorResource(com.google.android.material.R.color.material_on_surface_stroke);
        btnRecord.setTextColor(Color.WHITE);

        if (csvBuffer != null && csvBuffer.length() > 0) {
            saveCsvToDownloads(csvBuffer);
        }
        csvBuffer = null;
    }

    private void saveCsvToDownloads(StringBuilder buffer) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String filename = "elevator_" + timestamp + ".csv";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VibrationDemo");

        ContentResolver resolver = requireContext().getContentResolver();
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);

        if (uri == null) {
            tvRecordStatus.setText("Save failed");
            tvRecordStatus.setTextColor(Color.parseColor("#F44336"));
            return;
        }

        try (OutputStream os = resolver.openOutputStream(uri)) {
            os.write(buffer.toString().getBytes(StandardCharsets.UTF_8));
            String path = Environment.DIRECTORY_DOWNLOADS + "/VibrationDemo/" + filename;
            tvRecordStatus.setText("Saved: " + path);
            tvRecordStatus.setTextColor(Color.parseColor("#4CAF50"));
            Toast.makeText(requireContext(), "Saved to " + path, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            tvRecordStatus.setText("Save error: " + e.getMessage());
            tvRecordStatus.setTextColor(Color.parseColor("#F44336"));
        }
    }

    private String directionString(int dir) {
        switch (dir) {
            case DIR_UP: return "UP";
            case DIR_DOWN: return "DOWN";
            default: return "STATIONARY";
        }
    }

    private void setupChart() {
        chart.getDescription().setText("Elevator Velocity Z (mm/s)");
        chart.getDescription().setTextSize(12f);
        chart.setDrawGridBackground(true);
        chart.setPinchZoom(true);
        chart.setAutoScaleMinMaxEnabled(true);

        XAxis xl = chart.getXAxis();
        xl.setPosition(XAxis.XAxisPosition.BOTTOM);
        xl.setDrawGridLines(false);
        xl.setGranularity(1f);
        xl.setTextColor(Color.WHITE);
        xl.setTextSize(11f);

        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setTextColor(Color.WHITE);
        leftAxis.setTextSize(11f);

        chart.getAxisRight().setEnabled(false);
        chart.getLegend().setEnabled(true);
        chart.getLegend().setTextColor(Color.WHITE);
    }

    private void initChartData() {
        LineDataSet setVel = createDataSet("Velocity Z", Color.parseColor("#2196F3"));
        LineDataSet setSmooth = createDataSet("Smooth Z (dir)", Color.parseColor("#FF9800"));

        chart.setData(new LineData(setVel, setSmooth));
        chart.invalidate();
    }

    private LineDataSet createDataSet(String label, int color) {
        LineDataSet set = new LineDataSet(new ArrayList<>(), label);
        set.setColor(color);
        set.setLineWidth(1.5f);
        set.setDrawCircles(false);
        set.setDrawValues(false);
        set.setMode(LineDataSet.Mode.LINEAR);
        return set;
    }

    private void resetState() {
        lpFilter.reset();
        rawVelZ = 0;
        displayVelZ = 0;
        smoothVelZ = 0;
        prevTimeNs = 0;
        warmupCount = 0;
        warmupSum = 0;
        warmupSamplesCollected = 0;
        gravityBaseline = 0;
        belowThresholdStartNs = 0;
        direction = DIR_STATIONARY;
        peakAbsSpeedMmS = 0;
        clearChart();
    }

    private void clearChart() {
        dataIndex = 0;
        chart.getAxisLeft().removeAllLimitLines();
        LineData data = chart.getData();
        if (data != null) {
            for (int i = 0; i < data.getDataSetCount(); i++) {
                data.getDataSetByIndex(i).clear();
            }
            data.notifyDataChanged();
            chart.notifyDataSetChanged();
            chart.fitScreen();
            chart.invalidate();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        lpFilter.reset();
        rawVelZ = 0;
        displayVelZ = 0;
        smoothVelZ = 0;
        prevTimeNs = 0;
        warmupCount = 0;
        warmupSum = 0;
        warmupSamplesCollected = 0;
        gravityBaseline = 0;
        belowThresholdStartNs = 0;
        direction = DIR_STATIONARY;
        peakAbsSpeedMmS = 0;
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (isRecording) stopRecording();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

        long now = event.timestamp;
        float rawZ = event.values[2]; // Z-axis only

        if (prevTimeNs == 0) {
            prevTimeNs = now;
            return;
        }

        float dtSec = (now - prevTimeNs) / 1_000_000_000f;
        prevTimeNs = now;

        // Step 1: Warmup — measure gravity baseline (device is stationary)
        if (warmupSamplesCollected < WARMUP_SAMPLES) {
            warmupSum += rawZ;
            warmupSamplesCollected++;
            if (warmupSamplesCollected == WARMUP_SAMPLES) {
                gravityBaseline = warmupSum / WARMUP_SAMPLES;
            }
            return;
        }

        // Step 2: Direct gravity subtraction — preserves real constant acceleration
        float az = rawZ - gravityBaseline;

        // Step 3: Pure integration
        rawVelZ += az * dtSec;

        // Step 4: Drift correction — only during true STATIONARY (both velocity and accel near zero)
        // When acceleration is significant, the elevator is starting to move — don't fight integration.
        if (direction == DIR_STATIONARY && driftEnabled && Math.abs(az) < ACCEL_QUIET) {
            rawVelZ *= DRIFT_DECAY;
            // Auto-recalibrate gravity baseline: when truly stationary, real accel ≈ 0.
            // Any residual in az is sensor drift — slowly track it into the baseline.
            gravityBaseline += BASELINE_TRACK * (rawZ - gravityBaseline);
        }
        displayVelZ = rawVelZ;

        // Step 5: Low-pass filter on display velocity for direction judgment
        if (smoothEnabled) {
            lpFilter.update(dtSec);
            float[] lpOut = lpFilter.apply(0, 0, displayVelZ);
            smoothVelZ = lpOut[2];
        } else {
            smoothVelZ = displayVelZ;
        }

        // Step 6: Direction state machine
        // Pattern: 静止→加速→短暂减速→匀速→减速→可能短暂加速→静止
        // Key: direction is LOCKED once UP or DOWN — never flips mid-ride.
        //      Only transitions: STATIONARY→UP, STATIONARY→DOWN, UP→STATIONARY, DOWN→STATIONARY
        float absSmooth = Math.abs(smoothVelZ);

        if (direction == DIR_STATIONARY) {
            // Only leave STATIONARY when velocity clearly exceeds threshold
            if (absSmooth > NOISE_THRESHOLD) {
                direction = (smoothVelZ > 0) ? DIR_UP : DIR_DOWN;
                peakAbsSpeedMmS = absSmooth * 1000f;
                belowThresholdStartNs = 0;
            }
        } else {
            // Direction is locked — track peak speed
            peakAbsSpeedMmS = Math.max(peakAbsSpeedMmS, absSmooth * 1000f);

            if (absSmooth < NOISE_THRESHOLD) {
                // Velocity dropped below threshold — start settle timer
                if (belowThresholdStartNs == 0) {
                    belowThresholdStartNs = now;
                } else {
                    long settleNs = (peakAbsSpeedMmS > HIGH_SPEED_MM_S) ? SETTLE_LONG_NS : SETTLE_SHORT_NS;
                    if (now - belowThresholdStartNs >= settleNs) {
                        direction = DIR_STATIONARY;
                        peakAbsSpeedMmS = 0;
                        // Reset velocity state
                        rawVelZ = 0;
                        displayVelZ = 0;
                        smoothVelZ = 0;
                        lpFilter.reset();
                    }
                }
            } else {
                // Still moving — "可能的短暂加速" resets the settle timer
                belowThresholdStartNs = 0;
            }
            // Direction stays locked regardless of smoothVelZ sign
        }

        // Step 7: Update UI — use smooth velocity for stable speed display
        float speedMmS = Math.abs(smoothVelZ) * 1000f; // m/s → mm/s
        updateDirectionUI(speedMmS);

        // Step 8: Update chart
        addToChart(displayVelZ * 1000f, smoothVelZ * 1000f);

        // Step 9: Record sample if recording
        if (isRecording && csvBuffer != null) {
            if (recordStartNs == 0) recordStartNs = now;
            long elapsedMs = (now - recordStartNs) / 1_000_000L;
            csvBuffer.append(elapsedMs).append(',')
                     .append(String.format(Locale.US, "%.4f", az)).append(',')
                     .append(String.format(Locale.US, "%.2f", displayVelZ * 1000f)).append(',')
                     .append(String.format(Locale.US, "%.2f", smoothVelZ * 1000f)).append(',')
                     .append(directionString(direction)).append('\n');

            tvRecordStatus.setText(String.format(Locale.US, "Recording... %.1fs", elapsedMs / 1000f));
        }
    }

    private void updateDirectionUI(float speedMmS) {
        String dirText;
        int dirColor;

        switch (direction) {
            case DIR_UP:
                dirText = "↑ UP";
                dirColor = Color.parseColor("#4CAF50"); // green
                break;
            case DIR_DOWN:
                dirText = "↓ DOWN";
                dirColor = Color.parseColor("#F44336"); // red
                break;
            default:
                dirText = "↕ Stationary";
                dirColor = Color.parseColor("#9E9E9E"); // gray
                speedMmS = 0;
                break;
        }

        tvDirection.setText(dirText);
        tvDirection.setTextColor(dirColor);
        tvSpeed.setText(String.format(Locale.US, "%.1f mm/s", speedMmS));
        tvSpeed.setTextColor(dirColor);
        tvVelZ.setText(String.format(Locale.US, "v_z: %.2f mm/s", speedMmS));
    }

    private void addToChart(float velMmS, float smoothMmS) {
        LineData data = chart.getData();
        if (data == null) return;

        LineDataSet setVel = (LineDataSet) data.getDataSetByIndex(0);
        LineDataSet setSmooth = (LineDataSet) data.getDataSetByIndex(1);

        data.addEntry(new Entry(dataIndex, velMmS), 0);
        data.addEntry(new Entry(dataIndex, smoothMmS), 1);
        dataIndex++;

        while (setVel.getEntryCount() > MAX_DATA_POINTS) {
            setVel.removeFirst();
            setSmooth.removeFirst();
        }

        data.notifyDataChanged();
        chart.notifyDataSetChanged();
        updateMinMaxLines(setVel, setSmooth);
        chart.setVisibleXRangeMaximum(MAX_DATA_POINTS);
        chart.moveViewToX(dataIndex - MAX_DATA_POINTS);
    }

    private void updateMinMaxLines(LineDataSet setVel, LineDataSet setSmooth) {
        float max = Float.MIN_VALUE;
        float min = Float.MAX_VALUE;

        for (Entry e : setVel.getValues()) {
            if (e.getY() > max) max = e.getY();
            if (e.getY() < min) min = e.getY();
        }
        for (Entry e : setSmooth.getValues()) {
            if (e.getY() > max) max = e.getY();
            if (e.getY() < min) min = e.getY();
        }

        if (max == Float.MIN_VALUE || min == Float.MAX_VALUE) return;

        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.removeAllLimitLines();

        LimitLine zeroLine = new LimitLine(0f, "Zero");
        zeroLine.setLineColor(Color.GRAY);
        zeroLine.setTextColor(Color.GRAY);
        zeroLine.setLineWidth(2f);
        zeroLine.setTextSize(10f);
        leftAxis.addLimitLine(zeroLine);

        LimitLine maxLine = new LimitLine(max, String.format(Locale.US, "Max: %.1f", max));
        maxLine.enableDashedLine(10f, 10f, 0f);
        maxLine.setLineColor(Color.parseColor("#FF6600"));
        maxLine.setTextColor(Color.parseColor("#FF6600"));
        maxLine.setLineWidth(1f);
        maxLine.setTextSize(10f);
        leftAxis.addLimitLine(maxLine);

        LimitLine minLine = new LimitLine(min, String.format(Locale.US, "Min: %.1f", min));
        minLine.enableDashedLine(10f, 10f, 0f);
        minLine.setLineColor(Color.parseColor("#9933CC"));
        minLine.setTextColor(Color.parseColor("#9933CC"));
        minLine.setLineWidth(1f);
        minLine.setTextSize(10f);
        leftAxis.addLimitLine(minLine);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
