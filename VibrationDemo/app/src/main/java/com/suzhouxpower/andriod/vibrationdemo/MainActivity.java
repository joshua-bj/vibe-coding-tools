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
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private static final int MAX_DATA_POINTS = 200;

    private SensorManager sensorManager;
    private Sensor accelerometer;

    private LineChart chart;
    private TextView tvX, tvY, tvZ;

    private final List<Entry> xEntries = new ArrayList<>();
    private final List<Entry> yEntries = new ArrayList<>();
    private final List<Entry> zEntries = new ArrayList<>();
    private int dataIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        tvX = findViewById(R.id.tvX);
        tvY = findViewById(R.id.tvY);
        tvZ = findViewById(R.id.tvZ);
        chart = findViewById(R.id.chart);

        setupChart();
        initChartData();

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
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
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2] - SensorManager.GRAVITY_EARTH;

        tvX.setText(String.format("X: %.2f", x));
        tvY.setText(String.format("Y: %.2f", y));
        tvZ.setText(String.format("Z: %.2f", z));

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
        chart.setVisibleXRangeMaximum(MAX_DATA_POINTS);
        chart.moveViewToX(dataIndex - MAX_DATA_POINTS);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
