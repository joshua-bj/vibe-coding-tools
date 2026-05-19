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

public class RmsFragment extends Fragment implements SensorEventListener {

    private static final int MAX_DATA_POINTS = 200;
    private static final long RMS_WINDOW_NS = 1_000_000_000L; // 1 second
    private static final float HP_CUTOFF_HZ = 1.0f;

    private SensorManager sensorManager;
    private Sensor accelerometer;

    private LineChart chart;
    private TextView tvRmsX, tvRmsY, tvRmsZ;
    private CheckBox cbHighPass;
    private CheckBox cbLinearAccel;

    private int dataIndex = 0;
    private long windowStartNs = 0;
    private float sumX2, sumY2, sumZ2;
    private int sampleCount;
    private final SignalFilter.HighPassFilter highPassFilter = new SignalFilter.HighPassFilter(HP_CUTOFF_HZ);
    private boolean hpEnabled = false;
    private boolean useLinearAccel = true;
    private long prevTimeNs = 0;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_rms, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        tvRmsX = view.findViewById(R.id.tvRmsX);
        tvRmsY = view.findViewById(R.id.tvRmsY);
        tvRmsZ = view.findViewById(R.id.tvRmsZ);
        chart = view.findViewById(R.id.rmsChart);
        cbHighPass = view.findViewById(R.id.cbHighPass);
        cbLinearAccel = view.findViewById(R.id.cbLinearAccel);

        cbHighPass.setText(String.format(Locale.US, "HP Filter (%.1f Hz)", HP_CUTOFF_HZ));
        cbHighPass.setChecked(hpEnabled);
        cbHighPass.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
            hpEnabled = isChecked;
            highPassFilter.reset();
            prevTimeNs = 0;
            windowStartNs = 0;
            sumX2 = sumY2 = sumZ2 = 0;
            sampleCount = 0;
        });

        cbLinearAccel.setText("Linear Accel");
        cbLinearAccel.setChecked(useLinearAccel);
        cbLinearAccel.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
            useLinearAccel = isChecked;
            updateSensor();
            prevTimeNs = 0;
            windowStartNs = 0;
            sumX2 = sumY2 = sumZ2 = 0;
            sampleCount = 0;
            clearChart();
        });

        setupChart();
        initChartData();

        sensorManager = (SensorManager) requireContext().getSystemService(Context.SENSOR_SERVICE);
        updateSensor();
    }

    private void setupChart() {
        chart.getDescription().setText("RMS Accelerometer (m/s\u00B2) - 1s window");
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
        LineDataSet setX = createDataSet("RMS X", Color.RED);
        LineDataSet setY = createDataSet("RMS Y", Color.GREEN);
        LineDataSet setZ = createDataSet("RMS Z", Color.BLUE);

        chart.setData(new LineData(setX, setY, setZ));
        chart.invalidate();
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

    private void updateSensor() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
            int sensorType = useLinearAccel ? Sensor.TYPE_LINEAR_ACCELERATION : Sensor.TYPE_ACCELEROMETER;
            accelerometer = sensorManager.getDefaultSensor(sensorType);
            if (accelerometer != null) {
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
            }
        }
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
        prevTimeNs = 0;
        highPassFilter.reset();
        windowStartNs = 0;
        sumX2 = sumY2 = sumZ2 = 0;
        sampleCount = 0;
        updateSensor();
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
        int expectedType = useLinearAccel ? Sensor.TYPE_LINEAR_ACCELERATION : Sensor.TYPE_ACCELEROMETER;
        if (event.sensor.getType() != expectedType) return;

        long now = System.nanoTime();

        if (windowStartNs == 0) {
            windowStartNs = now;
        }

        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        // Apply high-pass filter if enabled
        if (hpEnabled) {
            if (prevTimeNs == 0) {
                prevTimeNs = now;
                return;
            }
            float dtSec = (now - prevTimeNs) / 1_000_000_000f;
            prevTimeNs = now;
            highPassFilter.update(dtSec);
            float[] filtered = highPassFilter.apply(x, y, z);
            x = filtered[0];
            y = filtered[1];
            z = filtered[2];
        }

        sumX2 += x * x;
        sumY2 += y * y;
        sumZ2 += z * z;
        sampleCount++;

        if (now - windowStartNs >= RMS_WINDOW_NS && sampleCount > 0) {
            float rmsX = (float) Math.sqrt(sumX2 / sampleCount);
            float rmsY = (float) Math.sqrt(sumY2 / sampleCount);
            float rmsZ = (float) Math.sqrt(sumZ2 / sampleCount);

            tvRmsX.setText(String.format(Locale.US, "RMS X: %.2f", rmsX));
            tvRmsY.setText(String.format(Locale.US, "RMS Y: %.2f", rmsY));
            tvRmsZ.setText(String.format(Locale.US, "RMS Z: %.2f", rmsZ));

            LineData data = chart.getData();
            if (data != null) {
                LineDataSet setX = (LineDataSet) data.getDataSetByIndex(0);
                LineDataSet setY = (LineDataSet) data.getDataSetByIndex(1);
                LineDataSet setZ = (LineDataSet) data.getDataSetByIndex(2);

                data.addEntry(new Entry(dataIndex, rmsX), 0);
                data.addEntry(new Entry(dataIndex, rmsY), 1);
                data.addEntry(new Entry(dataIndex, rmsZ), 2);
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
            sumX2 = sumY2 = sumZ2 = 0;
            sampleCount = 0;
        }
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
