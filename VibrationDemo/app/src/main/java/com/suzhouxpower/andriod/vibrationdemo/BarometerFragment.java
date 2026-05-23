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
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Barometer fragment — displays atmospheric pressure and derived altitude.
 *
 * Left Y-axis: pressure in hPa (blue line)
 * Right Y-axis: altitude in meters (orange line)
 * X-axis: time in seconds since fragment start
 *
 * Altitude is derived via SensorManager.getAltitude() using the standard
 * atmosphere reference pressure.
 */
public class BarometerFragment extends Fragment implements SensorEventListener {

    private static final int MAX_DATA_POINTS = 200;

    private SensorManager sensorManager;
    private Sensor pressureSensor;

    private LineChart chart;
    private TextView tvPressure;
    private TextView tvAltitude;

    private int dataIndex = 0;
    private long startTimeNs = 0;
    private boolean running = false;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_barometer, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        tvPressure = view.findViewById(R.id.tvPressure);
        tvAltitude = view.findViewById(R.id.tvAltitude);
        chart = view.findViewById(R.id.baroChart);

        setupChart();
        initChartData();

        sensorManager = (SensorManager) requireContext().getSystemService(Context.SENSOR_SERVICE);
    }

    private void setupChart() {
        chart.getDescription().setText("Pressure (hPa) & Altitude (m) vs Time");
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
        xl.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format(Locale.US, "%.1fs", value);
            }
        });

        // Left Y-axis: pressure in hPa
        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setTextColor(Color.parseColor("#2196F3"));
        leftAxis.setTextSize(11f);
        leftAxis.setAxisMinimum(950f);
        leftAxis.setAxisMaximum(1060f);
        leftAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format(Locale.US, "%.1f", value);
            }
        });

        // Right Y-axis: altitude in meters
        YAxis rightAxis = chart.getAxisRight();
        rightAxis.setDrawGridLines(false);
        rightAxis.setTextColor(Color.parseColor("#FF9800"));
        rightAxis.setTextSize(11f);
        rightAxis.setAxisMinimum(-200f);
        rightAxis.setAxisMaximum(500f);
        rightAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format(Locale.US, "%.0f", value);
            }
        });

        chart.getLegend().setEnabled(true);
        chart.getLegend().setTextColor(Color.WHITE);
    }

    private void initChartData() {
        LineDataSet setPressure = createDataSet("Pressure (hPa)", Color.parseColor("#2196F3"));
        LineDataSet setAltitude = createDataSet("Altitude (m)", Color.parseColor("#FF9800"));

        // Pressure on left axis (index 0), Altitude on right axis (index 1)
        setAltitude.setAxisDependency(YAxis.AxisDependency.RIGHT);

        chart.setData(new LineData(setPressure, setAltitude));
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
        running = true;
        startTimeNs = 0;
        dataIndex = 0;
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        if (pressureSensor != null) {
            sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_UI);
        } else {
            tvPressure.setText("No barometer sensor");
            tvAltitude.setText("N/A");
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        running = false;
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_PRESSURE || !running) return;

        long now = event.timestamp;
        if (startTimeNs == 0) startTimeNs = now;

        float pressure = event.values[0]; // hPa
        float altitude = SensorManager.getAltitude(
                SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure); // meters
        float timeSec = (now - startTimeNs) / 1_000_000_000f;

        // Update text displays
        tvPressure.setText(String.format(Locale.US, "%.2f hPa", pressure));
        tvAltitude.setText(String.format(Locale.US, "%.1f m", altitude));

        // Update chart
        LineData data = chart.getData();
        if (data == null) return;

        LineDataSet setPressure = (LineDataSet) data.getDataSetByIndex(0);
        LineDataSet setAltitude = (LineDataSet) data.getDataSetByIndex(1);

        data.addEntry(new Entry(timeSec, pressure), 0);
        data.addEntry(new Entry(timeSec, altitude), 1);
        dataIndex++;

        while (setPressure.getEntryCount() > MAX_DATA_POINTS) {
            setPressure.removeFirst();
            setAltitude.removeFirst();
        }

        data.notifyDataChanged();
        chart.notifyDataSetChanged();
        chart.setVisibleXRangeMaximum(MAX_DATA_POINTS * 0.02f); // ~4 seconds window
        chart.moveViewToX(timeSec - chart.getVisibleXRange());
        chart.invalidate();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
