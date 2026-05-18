package com.suzhouxpower.andriod.vibrationdemo;

import android.content.Context;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private static final int MAX_DATA_POINTS = 200;

    private SensorManager sensorManager;
    private Sensor accelerometer;

    private LineChart chart;
    private TextView tvX, tvY, tvZ, tvFreq;

    private final List<Entry> xEntries = new ArrayList<>();
    private final List<Entry> yEntries = new ArrayList<>();
    private final List<Entry> zEntries = new ArrayList<>();
    private int dataIndex = 0;
    private static final long FREQ_WINDOW_NS = 60_000_000_000L; // 1 minute
    private final Deque<Long> freqTimestamps = new ArrayDeque<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        tvX = findViewById(R.id.tvX);
        tvY = findViewById(R.id.tvY);
        tvZ = findViewById(R.id.tvZ);
        tvFreq = findViewById(R.id.tvFreq);
        chart = findViewById(R.id.chart);

        setupChart();
        initChartData();

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        }
    }

    private void setupChart() {
        chart.getDescription().setText("Accelerometer (m/s\u00B2)");
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
        LineDataSet setX = createDataSet("X", Color.RED);
        LineDataSet setY = createDataSet("Y", Color.GREEN);
        LineDataSet setZ = createDataSet("Z", Color.BLUE);

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
    protected void onResume() {
        super.onResume();
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_LINEAR_ACCELERATION) return;

        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        tvX.setText(String.format(Locale.US, "X: %.2f", x));
        tvY.setText(String.format(Locale.US, "Y: %.2f", y));
        tvZ.setText(String.format(Locale.US, "Z: %.2f", z));

        long now = System.nanoTime();
        freqTimestamps.addLast(now);
        while (!freqTimestamps.isEmpty() && now - freqTimestamps.peekFirst() > FREQ_WINDOW_NS) {
            freqTimestamps.removeFirst();
        }
        if (freqTimestamps.size() >= 2) {
            float spanSec = (now - freqTimestamps.peekFirst()) / 1_000_000_000f;
            if (spanSec > 0) {
                tvFreq.setText(String.format(Locale.US, "%.0f Hz", freqTimestamps.size() / spanSec));
            }
        }

        LineData data = chart.getData();
        if (data == null) return;

        LineDataSet setX = (LineDataSet) data.getDataSetByIndex(0);
        LineDataSet setY = (LineDataSet) data.getDataSetByIndex(1);
        LineDataSet setZ = (LineDataSet) data.getDataSetByIndex(2);

        data.addEntry(new Entry(dataIndex, x), 0);
        data.addEntry(new Entry(dataIndex, y), 1);
        data.addEntry(new Entry(dataIndex, z), 2);
        dataIndex++;

        // Trim oldest points to keep chart readable
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
