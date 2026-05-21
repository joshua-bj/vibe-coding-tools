package com.suzhouxpower.andriod.vibrationdemo;

import android.content.Context;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;

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

import java.util.ArrayList;
import java.util.Locale;

/**
 * Elevator velocity & direction fragment.
 *
 * Signal chain (Z-axis only):
 *   TYPE_ACCELEROMETER → HP 1Hz (remove gravity + DC) → integrate → drift HP 0.1Hz → LP 5Hz (smooth)
 *
 * Direction is determined from the smoothed velocity; the chart shows the display velocity.
 */
public class ElevatorFragment extends Fragment implements SensorEventListener {

    private static final int MAX_DATA_POINTS = 200;
    private static final float HP_CUTOFF_HZ = 1.0f;        // remove gravity + sensor zero-offset
    private static final float DRIFT_CUTOFF_HZ = 0.1f;     // remove velocity integration drift
    private static final float LP_CUTOFF_HZ = 5.0f;        // smooth elevator vibration for direction
    private static final float NOISE_THRESHOLD = 0.05f;     // m/s — below this = stationary
    private static final long STATIONARY_SETTLE_NS = 500_000_000L; // 0.5s below threshold → stationary
    private static final int WARMUP_SAMPLES = 50;           // ~1 second at 50 Hz — let HP filter settle

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

    private boolean driftEnabled = true;
    private boolean smoothEnabled = true;

    // Filters
    private final SignalFilter.HighPassFilter hpFilter = new SignalFilter.HighPassFilter(HP_CUTOFF_HZ);
    private final SignalFilter.HighPassFilter driftFilter = new SignalFilter.HighPassFilter(DRIFT_CUTOFF_HZ);
    private final SignalFilter.LowPassFilter lpFilter = new SignalFilter.LowPassFilter(LP_CUTOFF_HZ);

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

        cbDriftFilter.setText(String.format(Locale.US, "Drift HP %.1f Hz", DRIFT_CUTOFF_HZ));
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

        sensorManager = (SensorManager) requireContext().getSystemService(Context.SENSOR_SERVICE);
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
        driftFilter.reset();
        hpFilter.reset();
        lpFilter.reset();
        rawVelZ = 0;
        displayVelZ = 0;
        smoothVelZ = 0;
        prevTimeNs = 0;
        warmupCount = 0;
        belowThresholdStartNs = 0;
        direction = DIR_STATIONARY;
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
        hpFilter.reset();
        driftFilter.reset();
        lpFilter.reset();
        rawVelZ = 0;
        displayVelZ = 0;
        smoothVelZ = 0;
        prevTimeNs = 0;
        warmupCount = 0;
        belowThresholdStartNs = 0;
        direction = DIR_STATIONARY;
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
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

        // Step 1: High-pass filter — removes gravity (DC) + sensor zero-offset
        hpFilter.update(dtSec);
        float[] hpOut = hpFilter.apply(0, 0, rawZ);
        float az = hpOut[2]; // Z-axis only

        // Warmup: feed HP filter so it learns the DC level (gravity),
        // but don't integrate yet — avoids the startup transient
        if (warmupCount < WARMUP_SAMPLES) {
            warmupCount++;
            return;
        }

        // Step 2: Pure integration
        rawVelZ += az * dtSec;

        // Step 3: Drift filter (high-pass on velocity) for display
        if (driftEnabled) {
            driftFilter.update(dtSec);
            float[] driftOut = driftFilter.apply(0, 0, rawVelZ);
            displayVelZ = driftOut[2];
        } else {
            displayVelZ = rawVelZ;
        }

        // Step 4: Low-pass filter on display velocity for direction judgment
        if (smoothEnabled) {
            lpFilter.update(dtSec);
            float[] lpOut = lpFilter.apply(0, 0, displayVelZ);
            smoothVelZ = lpOut[2];
        } else {
            smoothVelZ = displayVelZ;
        }

        // Step 5: Direction state machine
        float absSmooth = Math.abs(smoothVelZ);

        if (absSmooth < NOISE_THRESHOLD) {
            if (direction != DIR_STATIONARY) {
                if (belowThresholdStartNs == 0) {
                    belowThresholdStartNs = now;
                } else if (now - belowThresholdStartNs >= STATIONARY_SETTLE_NS) {
                    direction = DIR_STATIONARY;
                    // Reset velocity state but NOT hpFilter — it already knows the DC level
                    rawVelZ = 0;
                    displayVelZ = 0;
                    smoothVelZ = 0;
                    driftFilter.reset();
                    lpFilter.reset();
                }
            }
        } else {
            belowThresholdStartNs = 0;
            if (smoothVelZ > 0) {
                direction = DIR_UP;
            } else {
                direction = DIR_DOWN;
            }
        }

        // Step 6: Update UI — direction and speed text
        float speedMmS = Math.abs(displayVelZ) * 1000f; // m/s → mm/s
        updateDirectionUI(speedMmS);

        // Step 7: Update chart
        addToChart(displayVelZ * 1000f, smoothVelZ * 1000f);
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
