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
import android.widget.Button;
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

public class VelocityFragment extends Fragment implements SensorEventListener {

    private static final int MAX_DATA_POINTS = 200;
    private static final long RMS_WINDOW_NS = 1_000_000_000L; // 1 second
    private static final float HP_CUTOFF_HZ = 1.0f; // high-pass filter cutoff frequency

    private SensorManager sensorManager;
    private Sensor accelerometer;

    private LineChart chart;
    private TextView tvVelX, tvVelY, tvVelZ;
    private Button btnToggleHp;

    private boolean hpEnabled = true;

    private int dataIndex = 0;
    private long prevTimeNs = 0;

    private final SignalFilter.HighPassFilter highPassFilter = new SignalFilter.HighPassFilter(HP_CUTOFF_HZ);

    // Integrated velocity per axis (m/s)
    private float velX, velY, velZ;

    // RMS 1-second window: accumulate squared velocity samples
    private long windowStartNs = 0;
    private float sumVelX2, sumVelY2, sumVelZ2;
    private int sampleCount;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_velocity, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        tvVelX = view.findViewById(R.id.tvVelX);
        tvVelY = view.findViewById(R.id.tvVelY);
        tvVelZ = view.findViewById(R.id.tvVelZ);
        chart = view.findViewById(R.id.velChart);
        btnToggleHp = view.findViewById(R.id.btnToggleHp);

        updateHpButtonText();
        btnToggleHp.setOnClickListener(v -> {
            hpEnabled = !hpEnabled;
            updateHpButtonText();
            highPassFilter.reset();
            velX = velY = velZ = 0;
            prevTimeNs = 0;
            windowStartNs = 0;
            sumVelX2 = sumVelY2 = sumVelZ2 = 0;
            sampleCount = 0;
        });

        setupChart();
        initChartData();

        sensorManager = (SensorManager) requireContext().getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        }
    }

    private void setupChart() {
        chart.getDescription().setText("Velocity RMS (mm/s) - 1s window");
        chart.getDescription().setTextSize(12f);
        chart.setDrawGridBackground(true);
        chart.setPinchZoom(true);

        XAxis xl = chart.getXAxis();
        xl.setPosition(XAxis.XAxisPosition.BOTTOM);
        xl.setDrawGridLines(false);
        xl.setGranularity(1f);

        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setDrawGridLines(true);

        chart.getAxisRight().setEnabled(false);
        chart.getLegend().setEnabled(true);
    }

    private void initChartData() {
        LineDataSet setX = createDataSet("Vel X", Color.RED);
        LineDataSet setY = createDataSet("Vel Y", Color.GREEN);
        LineDataSet setZ = createDataSet("Vel Z", Color.BLUE);

        chart.setData(new LineData(setX, setY, setZ));
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

    @Override
    public void onResume() {
        super.onResume();
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
        prevTimeNs = 0;
        highPassFilter.reset();
        velX = velY = velZ = 0;
        windowStartNs = 0;
        sumVelX2 = sumVelY2 = sumVelZ2 = 0;
        sampleCount = 0;
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
        if (event.sensor.getType() != Sensor.TYPE_LINEAR_ACCELERATION) return;

        long now = System.nanoTime();

        float rawX = event.values[0];
        float rawY = event.values[1];
        float rawZ = event.values[2];

        if (prevTimeNs == 0) {
            // First sample: initialize, no integration yet
            prevTimeNs = now;
            windowStartNs = now;
            return;
        }

        float dtSec = (now - prevTimeNs) / 1_000_000_000f;
        prevTimeNs = now;

        // Step 1: High-pass filter (if enabled)
        float ax, ay, az;
        if (hpEnabled) {
            highPassFilter.update(dtSec);
            float[] filtered = highPassFilter.apply(rawX, rawY, rawZ);
            ax = filtered[0];
            ay = filtered[1];
            az = filtered[2];
        } else {
            ax = rawX;
            ay = rawY;
            az = rawZ;
        }

        // Step 2: Integrate acceleration -> velocity (m/s)
        velX += ax * dtSec;
        velY += ay * dtSec;
        velZ += az * dtSec;

        // Step 3: Accumulate squared velocity for 1-second RMS window
        sumVelX2 += velX * velX;
        sumVelY2 += velY * velY;
        sumVelZ2 += velZ * velZ;
        sampleCount++;

        if (now - windowStartNs >= RMS_WINDOW_NS && sampleCount > 0) {
            float rmsVelX = (float) Math.sqrt(sumVelX2 / sampleCount);
            float rmsVelY = (float) Math.sqrt(sumVelY2 / sampleCount);
            float rmsVelZ = (float) Math.sqrt(sumVelZ2 / sampleCount);

            // Convert m/s -> mm/s
            float mmX = rmsVelX * 1000f;
            float mmY = rmsVelY * 1000f;
            float mmZ = rmsVelZ * 1000f;

            tvVelX.setText(String.format(Locale.US, "Vel X: %.2f", mmX));
            tvVelY.setText(String.format(Locale.US, "Vel Y: %.2f", mmY));
            tvVelZ.setText(String.format(Locale.US, "Vel Z: %.2f", mmZ));

            LineData data = chart.getData();
            if (data != null) {
                LineDataSet setX = (LineDataSet) data.getDataSetByIndex(0);
                LineDataSet setY = (LineDataSet) data.getDataSetByIndex(1);
                LineDataSet setZ = (LineDataSet) data.getDataSetByIndex(2);

                data.addEntry(new Entry(dataIndex, mmX), 0);
                data.addEntry(new Entry(dataIndex, mmY), 1);
                data.addEntry(new Entry(dataIndex, mmZ), 2);
                dataIndex++;

                while (setX.getEntryCount() > MAX_DATA_POINTS) {
                    setX.removeFirst();
                    setY.removeFirst();
                    setZ.removeFirst();
                }

                data.notifyDataChanged();
                chart.notifyDataSetChanged();
                updateMinMaxLines(setX, setY, setZ);
                chart.setVisibleXRangeMaximum(MAX_DATA_POINTS);
                chart.moveViewToX(dataIndex - MAX_DATA_POINTS);
            }

            // Reset for next 1-second window
            windowStartNs = now;
            sumVelX2 = sumVelY2 = sumVelZ2 = 0;
            sampleCount = 0;
        }
    }

    private void updateHpButtonText() {
        btnToggleHp.setText(hpEnabled
                ? String.format(Locale.US, "High-Pass Filter: ON (%.1f Hz)", HP_CUTOFF_HZ)
                : "High-Pass Filter: OFF");
    }

    private void updateMinMaxLines(LineDataSet setX, LineDataSet setY, LineDataSet setZ) {
        float max = Float.MIN_VALUE;
        float min = Float.MAX_VALUE;

        for (Entry e : setX.getValues()) {
            if (e.getY() > max) max = e.getY();
            if (e.getY() < min) min = e.getY();
        }
        for (Entry e : setY.getValues()) {
            if (e.getY() > max) max = e.getY();
            if (e.getY() < min) min = e.getY();
        }
        for (Entry e : setZ.getValues()) {
            if (e.getY() > max) max = e.getY();
            if (e.getY() < min) min = e.getY();
        }

        if (max == Float.MIN_VALUE || min == Float.MAX_VALUE) return;

        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.removeAllLimitLines();

        LimitLine zeroLine = new LimitLine(0f, "X Axis");
        zeroLine.setLineColor(Color.GRAY);
        zeroLine.setTextColor(Color.GRAY);
        zeroLine.setLineWidth(2f);
        zeroLine.setTextSize(10f);
        leftAxis.addLimitLine(zeroLine);

        LimitLine maxLine = new LimitLine(max, String.format(Locale.US, "Max: %.2f", max));
        maxLine.enableDashedLine(10f, 10f, 0f);
        maxLine.setLineColor(Color.parseColor("#FF6600"));
        maxLine.setTextColor(Color.parseColor("#FF6600"));
        maxLine.setLineWidth(1f);
        maxLine.setTextSize(10f);
        leftAxis.addLimitLine(maxLine);

        LimitLine minLine = new LimitLine(min, String.format(Locale.US, "Min: %.2f", min));
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
