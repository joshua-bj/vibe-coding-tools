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
 *   TYPE_ACCELEROMETER → subtract gravity baseline (warmup) → dead zone gate →
 *   confirmation buffer → integrate → LP 5Hz (smooth)
 *
 * Acceleration processing (Step 2):
 *   1. Dead zone: |az| < ACCEL_DRIFT_GATE (0.06) → suppress as vibration/drift.
 *      Suppressed values are buffered; recovered on exit if |az| > 0.05 and streak <= 8.
 *   2. Confirmation gate: |az| >= ACCEL_DRIFT_GATE → buffer until >= 3 consecutive
 *      above-threshold samples confirm real acceleration. Short streaks are discarded as vibration.
 *   3. Once confirmed (accelConfirmed=true), above-threshold samples integrate directly
 *      until az drops below ACCEL_DRIFT_GATE again.
 *
 * Direction state machine (Step 5):
 *   STATIONARY → UP/DOWN: requires sustained accel above ACCEL_MOVE_START for MOVE_CONFIRM_NS.
 *   Direction is locked once UP/DOWN — never flips mid-ride.
 *
 * Stop detection — three paths to STATIONARY:
 *   A) While STATIONARY: velocity near zero for SETTLE_SHORT_NS → force reset
 *   B) While locked: velocity drops below threshold for settle time
 *   C) While locked: velocity sign reversal during deceleration (integration overshoot)
 */
public class ElevatorFragment extends Fragment implements SensorEventListener {

    private static final int MAX_DATA_POINTS = 200;
    private static final float LP_CUTOFF_HZ = 5.0f;        // smooth elevator vibration for direction
    private static final float ACCEL_DRIFT_GATE = 0.06f;    // m/s² — dead zone threshold; |az| below this is vibration/drift
    private static final float NOISE_THRESHOLD  = 0.15f;    // m/s — above max drift, below real movement
    private static final long SETTLE_SHORT_NS  = 500_000_000L;  // 0.5s for low-speed movements
    private static final long SETTLE_LONG_NS   = 1_000_000_000L; // 1.0s after high-speed movement
    private static final float HIGH_SPEED_MM_S = 200f;      // mm/s — above this, use long settle
    private static final int WARMUP_SAMPLES = 100;          // ~2 seconds at 50 Hz — measure gravity baseline
    private static final float ACCEL_MOVE_START = 0.10f;    // m/s² — min accel to confirm real movement
    private static final long MOVE_CONFIRM_NS  = 200_000_000L;  // 200ms sustained accel to confirm movement
    private static final float SIGN_FLIP_STOP_MM_S = 200f;  // mm/s — minimum peak speed for sign-flip stop
    private static final int DEAD_ZONE_BUFFER_MAX = 8;      // max consecutive dead-zone samples eligible for recovery
    private static final float DEAD_ZONE_RESUME_THRESHOLD = 0.05f; // m/s² — above this, suppressed value is real accel
    private static final int ACCEL_CONFIRM_COUNT = 3;       // need >= 3 consecutive above-threshold samples to confirm

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
    private CheckBox cbSmoothFilter;
    private MaterialButton btnRecord;
    private TextView tvRecordStatus;

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
    private float displayVelZ = 0f;    // integrated velocity for chart
    private float smoothVelZ = 0f;     // after LP filter — for direction judgment

    private int dataIndex = 0;
    private long prevTimeNs = 0;
    private int warmupCount = 0;

    // Direction state machine
    private int direction = DIR_STATIONARY;
    private long belowThresholdStartNs = 0;         // timestamp when velocity first dropped below threshold
    private float peakAbsSpeedMmS = 0f;              // peak |speed| during current movement

    // Dead zone recovery buffer: stores suppressed below-threshold az values
    // that may be recovered if the dead zone exit has significant values
    private final float[] deadZoneAz = new float[DEAD_ZONE_BUFFER_MAX];
    private final float[] deadZoneDt = new float[DEAD_ZONE_BUFFER_MAX];
    private int deadZoneCount = 0;

    // Above-threshold confirmation buffer: stores above-threshold az values
    // until >= ACCEL_CONFIRM_COUNT consecutive samples confirm real acceleration
    private final float[] confirmAz = new float[ACCEL_CONFIRM_COUNT + 1];
    private final float[] confirmDt = new float[ACCEL_CONFIRM_COUNT + 1];
    private int confirmCount = 0;
    private boolean accelConfirmed = false;           // true once initial confirmation is done; az integrates directly
    private int confirmingDirection = DIR_STATIONARY; // direction being confirmed for state machine (UP/DOWN)
    private long confirmStartNs = 0;                  // timestamp when direction confirmation started

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
        cbSmoothFilter = view.findViewById(R.id.cbSmoothFilter);
        btnRecord = view.findViewById(R.id.btnRecord);
        tvRecordStatus = view.findViewById(R.id.tvRecordStatus);

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
        csvBuffer.append("time_ms,original_az,accel_z_mps2,velocity_mm_s,smooth_mm_s,direction\n");
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
        confirmStartNs = 0;
        confirmingDirection = DIR_STATIONARY;
        deadZoneCount = 0;
        confirmCount = 0;
        accelConfirmed = false;
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

    private void transitionToStationary() {
        direction = DIR_STATIONARY;
        peakAbsSpeedMmS = 0;
        belowThresholdStartNs = 0;
        confirmStartNs = 0;
        confirmingDirection = DIR_STATIONARY;
        confirmCount = 0;
        accelConfirmed = false;
        rawVelZ = 0;
        displayVelZ = 0;
        smoothVelZ = 0;
        lpFilter.reset();
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
        confirmStartNs = 0;
        confirmingDirection = DIR_STATIONARY;
        deadZoneCount = 0;
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

        // Step 2: Gravity subtraction + dead zone + confirmation gate
        float az = rawZ - gravityBaseline;
        float originalAz = az; // preserve for CSV before dead zone modifies az

        // --- Dead zone: |az| < ACCEL_DRIFT_GATE (0.06) → suppress as vibration/drift ---
        if (Math.abs(az) < ACCEL_DRIFT_GATE) {
            // Reset confirmation state — streak is broken
            confirmCount = 0;
            accelConfirmed = false;

            // Buffer the suppressed value; may be recovered on dead zone exit
            if (deadZoneCount < DEAD_ZONE_BUFFER_MAX) {
                deadZoneAz[deadZoneCount] = az;
                deadZoneDt[deadZoneCount] = dtSec;
            }
            deadZoneCount++;
            az = 0f;

        // --- Above threshold: |az| >= ACCEL_DRIFT_GATE → confirmation gate ---
        } else {
            // Flush dead-zone recovery buffer on exit:
            // If streak was <= 8 AND any |az| > 0.05, those values were real acceleration
            // near the threshold boundary — integrate them back.
            if (deadZoneCount > 0) {
                if (deadZoneCount <= DEAD_ZONE_BUFFER_MAX) {
                    boolean hasSignificant = false;
                    for (int i = 0; i < deadZoneCount; i++) {
                        if (Math.abs(deadZoneAz[i]) > DEAD_ZONE_RESUME_THRESHOLD) {
                            hasSignificant = true;
                            break;
                        }
                    }
                    if (hasSignificant) {
                        for (int i = 0; i < deadZoneCount; i++) {
                            rawVelZ += deadZoneAz[i] * deadZoneDt[i];
                        }
                    }
                }
                deadZoneCount = 0;
            }

            if (accelConfirmed) {
                // Already confirmed — az integrates directly (passes through to Step 3)
            } else {
                // Buffer until >= 3 consecutive above-threshold samples confirm real acceleration.
                // Short streaks are discarded as vibration when az drops below threshold.
                confirmAz[confirmCount] = az;
                confirmDt[confirmCount] = dtSec;
                confirmCount++;
                if (confirmCount >= ACCEL_CONFIRM_COUNT) {
                    // Confirmed: flush entire buffer into velocity
                    for (int i = 0; i < confirmCount; i++) {
                        rawVelZ += confirmAz[i] * confirmDt[i];
                    }
                    confirmCount = 0;
                    accelConfirmed = true;
                }
                az = 0f; // suppress: either not yet confirmed, or already flushed above
            }
        }

        // Step 3: Pure integration
        rawVelZ += az * dtSec;
        displayVelZ = rawVelZ;

        // Step 4: Low-pass filter on display velocity for direction judgment
        if (smoothEnabled) {
            lpFilter.update(dtSec);
            float[] lpOut = lpFilter.apply(0, 0, displayVelZ);
            smoothVelZ = lpOut[2];
        } else {
            smoothVelZ = displayVelZ;
        }

        // Step 5: Direction state machine
        // Direction is LOCKED once UP or DOWN — never flips mid-ride.
        // STATIONARY → UP/DOWN: requires sustained accel above ACCEL_MOVE_START for MOVE_CONFIRM_NS.
        // UP/DOWN → STATIONARY: three paths —
        //   A) While STATIONARY: velocity near zero for settle time → force reset
        //   B) While locked: velocity drops below threshold for settle time
        //   C) While locked: velocity sign reversal during deceleration
        float absSmooth = Math.abs(smoothVelZ);

        if (direction == DIR_STATIONARY) {
            // --- Direction confirmation: require sustained accel to distinguish from noise ---
            if (Math.abs(az) > ACCEL_MOVE_START) {
                int moveDir = (az > 0) ? DIR_UP : DIR_DOWN;
                if (confirmStartNs == 0 || confirmingDirection != moveDir) {
                    confirmStartNs = now;
                    confirmingDirection = moveDir;
                } else if (now - confirmStartNs >= MOVE_CONFIRM_NS) {
                    // Direction confirmed: sustained acceleration in one direction
                    direction = moveDir;
                    peakAbsSpeedMmS = absSmooth * 1000f;
                    confirmStartNs = 0;
                    confirmingDirection = DIR_STATIONARY;
                    belowThresholdStartNs = 0;
                }
            } else {
                confirmStartNs = 0;
            }

            // --- Path A: force reset if velocity lingers near zero during STATIONARY ---
            if (absSmooth < NOISE_THRESHOLD) {
                if (belowThresholdStartNs == 0) {
                    belowThresholdStartNs = now;
                } else if (now - belowThresholdStartNs >= SETTLE_SHORT_NS) {
                    transitionToStationary();
                }
            } else {
                belowThresholdStartNs = 0;
            }
        } else {
            // Direction is locked — track peak speed
            peakAbsSpeedMmS = Math.max(peakAbsSpeedMmS, absSmooth * 1000f);
            confirmStartNs = 0;

            // --- Path B: velocity dropped below threshold while locked — settle timer ---
            if (absSmooth < NOISE_THRESHOLD) {
                if (belowThresholdStartNs == 0) {
                    belowThresholdStartNs = now;
                } else {
                    long settleNs = (peakAbsSpeedMmS > HIGH_SPEED_MM_S) ? SETTLE_LONG_NS : SETTLE_SHORT_NS;
                    if (now - belowThresholdStartNs >= settleNs) {
                        transitionToStationary();
                    }
                }
            } else {
                belowThresholdStartNs = 0;
            }


            // --- Path C: velocity sign reversal — deceleration overshoot, elevator stopped ---
            if (peakAbsSpeedMmS > SIGN_FLIP_STOP_MM_S) {
                if ((direction == DIR_DOWN && smoothVelZ > NOISE_THRESHOLD) ||
                    (direction == DIR_UP   && smoothVelZ < -NOISE_THRESHOLD)) {
                    transitionToStationary();
                }
            }

            // Direction stays locked regardless of smoothVelZ sign
        }

        // Step 6: Update UI — use smooth velocity for stable speed display
        float speedMmS = Math.abs(smoothVelZ) * 1000f; // m/s → mm/s
        updateDirectionUI(speedMmS);

        // Step 7: Update chart
        addToChart(displayVelZ * 1000f, smoothVelZ * 1000f);

        // Step 8: Record sample if recording
        if (isRecording && csvBuffer != null) {
            if (recordStartNs == 0) recordStartNs = now;
            long elapsedMs = (now - recordStartNs) / 1_000_000L;
            csvBuffer.append(elapsedMs).append(',')
                     .append(String.format(Locale.US, "%.4f", originalAz)).append(',')
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
