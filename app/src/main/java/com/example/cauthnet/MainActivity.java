package com.example.cauthnet;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.pytorch.IValue;
import org.pytorch.Module;
import org.pytorch.Tensor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private static final String TAG = "SensorStats";
    private static final String PREFS_NAME = "UserPreferences";
    private static final String ENROLLED_EMBEDDING_KEY = "EnrolledEmbedding";

    private SensorManager sensorManager;
    private Sensor accelerometer, gyroscope, magnetometer;

    private Deque<float[]> accelData = new ArrayDeque<>(10);
    private Deque<float[]> gyroData = new ArrayDeque<>(10);
    private Deque<float[]> magData = new ArrayDeque<>(10);
    private Deque<float[]> accelDataPostClick = new ArrayDeque<>(10);
    private Deque<float[]> gyroDataPostClick = new ArrayDeque<>(10);
    private Deque<float[]> magDataPostClick = new ArrayDeque<>(10);

    private boolean isButtonClicked = false;
    private boolean isEnrolling = false;
    private int enrollmentCount = 0;
    private float[] enrolledEmbedding;
    private Module module;  // PyTorch Module

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize SensorManager
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        // Initialize sensors
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        Button btnEnroll = findViewById(R.id.btnEnroll);
        Button btnAuthenticate = findViewById(R.id.btnAuthenticate);
        Button btnShowStats = findViewById(R.id.btnShowStats);

        List<float[]> allStatistics = new ArrayList<>(); // Store all statistics arrays

        // Load the PyTorch model
        try {
            module = Module.load(assetFilePath(this, "model_scripted.pt"));
        } catch (Exception e) {
            Log.e(TAG, "Error loading model", e);
        }

        btnEnroll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startEnrollment(allStatistics);
            }
        });

        btnAuthenticate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startAuthentication();
            }
        });

        btnShowStats.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isEnrolling) {
                    handleEnrollment(allStatistics);
                } else {
                    handleAuthentication(allStatistics);
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL);
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        Deque<float[]> queue = null;
        Deque<float[]> queuePostClick = null;

        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                queue = accelData;
                queuePostClick = accelDataPostClick;
                break;
            case Sensor.TYPE_GYROSCOPE:
                queue = gyroData;
                queuePostClick = gyroDataPostClick;
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                queue = magData;
                queuePostClick = magDataPostClick;
                break;
        }

        if (queue != null) {
            float[] valuesWithMagnitude = new float[4];
            System.arraycopy(event.values, 0, valuesWithMagnitude, 0, 3);
            valuesWithMagnitude[3] = calculateMagnitude(event.values);

            if (!isButtonClicked) {
                if (queue.size() == 10) {
                    queue.pollFirst(); // Remove the oldest entry
                }
                queue.offerLast(valuesWithMagnitude); // Add the latest entry
            } else {
                if (queuePostClick.size() == 10) {
                    queuePostClick.pollFirst(); // Remove the oldest entry
                }
                queuePostClick.offerLast(valuesWithMagnitude); // Add the latest entry
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Handle changes in sensor accuracy if needed
    }

//    private float calculateMagnitude(float[] values) {
//        return (float) Math.sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2]);
//    }


    private void startEnrollment(List<float[]> allStatistics) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enrollment")
                .setMessage("Press the Show Stats button 20 times to complete enrollment.")
                .setPositiveButton("OK", (dialog, which) -> {
                    isEnrolling = true;
                    enrollmentCount = 0;
                    allStatistics.clear();
                })
                .setCancelable(false)
                .show();
    }

    float[] averageStatistics = null;
    private void handleEnrollment(List<float[]> allStatistics) {
        if (enrollmentCount < 20) {
            isButtonClicked = true;

            calculateAndPrintStatistics();
            float[] newStatistics = createStatisticsArray();
            allStatistics.add(newStatistics);  // Add the new statistics array
            averageStatistics = calculateAverageStatistics(allStatistics);
            Log.d(TAG, "Average Statistics: " + Arrays.toString(averageStatistics));


            enrollmentCount++;
            isButtonClicked = false;
            resetDataQueues();
            Toast.makeText(this, "Enrollment step " + enrollmentCount + " completed", Toast.LENGTH_SHORT).show();
        } else {
            isEnrolling = false;
            Log.d(TAG, "Average Statistics after enrolment: " + Arrays.toString(averageStatistics));

            float [] enrolledEmbedding = runModel(averageStatistics);
            saveEnrolledEmbedding(enrolledEmbedding);
            Toast.makeText(this, "Enrollment completed", Toast.LENGTH_SHORT).show();
        }
    }

    private void startAuthentication() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Authentication")
                .setMessage("Press the Show Stats button to authenticate.")
                .setPositiveButton("OK", (dialog, which) -> {
                    isEnrolling = false;
                })
                .setCancelable(false)
                .show();
    }

    private void handleAuthentication(List<float[]> allStatistics) {
        isButtonClicked = true;
        float[] newStatistics = createStatisticsArray();
        if (allStatistics.size() == 20) {
            allStatistics.remove(0); // Remove the oldest entry
        }
        allStatistics.add(newStatistics);  // Add the new statistics array
        averageStatistics = calculateAverageStatistics(allStatistics);
//        Log.d(TAG, "Average Statistics in Authentication: " + Arrays.toString(averageStatistics));


        float [] authenEmbedding = runModel(averageStatistics);
        enrolledEmbedding = loadEnrolledEmbedding();
//        Log.d(TAG, "authenEmbedding: " + Arrays.toString(authenEmbedding));



        float distance = calculateEuclideanDistance(enrolledEmbedding, authenEmbedding);
//        Log.d(TAG, "Distance: " + distance);
        isButtonClicked = false;
        resetDataQueues();

        Toast.makeText(this, "Distance: " + distance, Toast.LENGTH_SHORT).show();


//        if (distance < 0.7) {
//            Toast.makeText(this, "Genuine user detected", Toast.LENGTH_SHORT).show();
//        } else {
//            Toast.makeText(this, "Adversary detected", Toast.LENGTH_SHORT).show();
//        }
    }

    private void saveEnrolledEmbedding(float[] embedding) {
        SharedPreferences sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(ENROLLED_EMBEDDING_KEY, Arrays.toString(embedding));
        editor.apply();
    }

    private float[] loadEnrolledEmbedding() {
        SharedPreferences sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String embeddingString = sharedPreferences.getString(ENROLLED_EMBEDDING_KEY, null);
        if (embeddingString != null) {
            String[] stringArray = embeddingString.replace("[", "").replace("]", "").split(", ");
            float[] embedding = new float[stringArray.length];
            for (int i = 0; i < stringArray.length; i++) {
                embedding[i] = Float.parseFloat(stringArray[i]);
            }
            return embedding;
        }
        return null;
    }


    private float calculateMagnitude(float[] values) {
        return (float) Math.sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2]);
    }

    private void calculateAndPrintStatistics() {
        printStatistics("Accelerometer", accelData, accelDataPostClick);
        printStatistics("Gyroscope", gyroData, gyroDataPostClick);
        printStatistics("Magnetometer", magData, magDataPostClick);
    }

    private void printStatistics(String sensorType, Deque<float[]> dataBefore, Deque<float[]> dataAfter) {
        float[] meanBefore = calculateMean(dataBefore);
        float[] meanAfter = calculateMean(dataAfter);
        float[] stdDevBefore = calculateStdDev(dataBefore, meanBefore);
        float[] stdDevAfter = calculateStdDev(dataAfter, meanAfter);
        float[] minBefore = calculateMin(dataBefore);
        float[] minAfter = calculateMin(dataAfter);
        float[] maxBefore = calculateMax(dataBefore);
        float[] maxAfter = calculateMax(dataAfter);

        Log.d(TAG, sensorType + " Statistics Before Button Click:");
        printValues("Mean", meanBefore);
        printValues("StdDev", stdDevBefore);
        printValues("Min", minBefore);
        printValues("Max", maxBefore);

        Log.d(TAG, sensorType + " Statistics After Button Click:");
        printValues("Mean", meanAfter);
        printValues("StdDev", stdDevAfter);
        printValues("Min", minAfter);
        printValues("Max", maxAfter);

        Log.d(TAG, sensorType + " Statistics Differences:");
        printDifferences("Mean", meanBefore, meanAfter);
        printDifferences("StdDev", stdDevBefore, stdDevAfter);
        printDifferences("Min", minBefore, minAfter);
        printDifferences("Max", maxBefore, maxAfter);
    }

    private void printValues(String label, float[] values) {
        Log.d(TAG, label + ": X=" + values[0] + ", Y=" + values[1] + ", Z=" + values[2] + ", M=" + values[3]);
    }

    private void printDifferences(String label, float[] before, float[] after) {
        Log.d(TAG, label + " Difference: X=" + (after[0] - before[0]) + ", Y=" + (after[1] - before[1]) + ", Z=" + (after[2] - before[2]) + ", M=" + (after[3] - before[3]));
    }


    private float calculateEuclideanDistance(float[] a, float[] b) {
        float sum = 0;
        for (int i = 0; i < a.length; i++) {
            float diff = a[i] - b[i];
            sum += diff * diff;
        }
        return (float) Math.sqrt(sum);
    }

    private void resetDataQueues() {
        accelData.clear();
        gyroData.clear();
        magData.clear();
        accelDataPostClick.clear();
        gyroDataPostClick.clear();
        magDataPostClick.clear();
    }

    private float[] createStatisticsArray() {
        float[] statistics = new float[144];
        int index = 0;

        // Accelerometer
        index = fillSensorStats(accelData, accelDataPostClick, statistics, index);

        // Gyroscope
        index = fillSensorStats(gyroData, gyroDataPostClick, statistics, index);

        // Magnetometer
        index = fillSensorStats(magData, magDataPostClick, statistics, index);

        Log.d(TAG, "Statistics Array: " + Arrays.toString(statistics));

        return statistics;
    }

    // Helper method to fill statistics for a specific sensor type
    private int fillSensorStats(Deque<float[]> dataBefore, Deque<float[]> dataAfter, float[] statistics, int index) {
        float[] meanBefore = calculateMean(dataBefore);
        float[] stdDevBefore = calculateStdDev(dataBefore, meanBefore);
        float[] minBefore = calculateMin(dataBefore);
        float[] maxBefore = calculateMax(dataBefore);

        float[] meanAfter = calculateMean(dataAfter);
        float[] stdDevAfter = calculateStdDev(dataAfter, meanAfter);
        float[] minAfter = calculateMin(dataAfter);
        float[] maxAfter = calculateMax(dataAfter);

        float[] diffMean = calculateDiff(meanBefore, meanAfter);
        float[] diffStdDev = calculateDiff(stdDevBefore, stdDevAfter);
        float[] diffMin = calculateDiff(minBefore, minAfter);
        float[] diffMax = calculateDiff(maxBefore, maxAfter);

        // Manually fill in the statistics in the desired order
        for (int i = 0; i < 4; i++) { // Iterate over X, Y, Z, M
            statistics[index++] = meanBefore[i];
            statistics[index++] = stdDevBefore[i];
            statistics[index++] = minBefore[i];
            statistics[index++] = maxBefore[i];
        }

        for (int i = 0; i < 4; i++) { // Iterate over X, Y, Z, M
            statistics[index++] = meanAfter[i];
            statistics[index++] = stdDevAfter[i];
            statistics[index++] = minAfter[i];
            statistics[index++] = maxAfter[i];
        }
        for (int i = 0; i < 4; i++) { // Iterate over X, Y, Z, M
            statistics[index++] = diffMean[i];
            statistics[index++] = diffStdDev[i];
            statistics[index++] = diffMin[i];
            statistics[index++] = diffMax[i];
        }

        return index;
    }

    // Helper method to calculate differences between two arrays
    private float[] calculateDiff(float[] arr1, float[] arr2) {
        float[] diff = new float[arr1.length];
        for (int i = 0; i < arr1.length; i++) {
            diff[i] = arr2[i] - arr1[i];
        }
        return diff;
    }

    private float[] calculateAverageStatistics(List<float[]> allStatistics) {
        float[] average = new float[144];
        for (float[] stats : allStatistics) {
            for (int i = 0; i < 144; i++) {
                average[i] += stats[i];
            }
        }
        for (int i = 0; i < 144; i++) {
            average[i] /= allStatistics.size();
        }
        return average;
    }

    private float[] runModel(float[] averageStatistics) {

        for (int i = 0; i < averageStatistics.length; i++) {
            averageStatistics[i] = Math.max(-10, Math.min(averageStatistics[i], 10));
        }
        Log.d(TAG, "Average Statistics in run model: " + Arrays.toString(averageStatistics));

        // Convert the averageStatistics array to a tensor
        Tensor inputTensor = Tensor.fromBlob(averageStatistics, new long[]{1, 144});

        // Log tensor shape and type
        Log.d("TensorInfo", "Input Tensor Shape: " + Arrays.toString(inputTensor.shape()));

        // Create an array of IValue objects, one for each tensor
        IValue[] inputs = new IValue[]{IValue.from(inputTensor), IValue.from(inputTensor)};

        // Pass the array of IValue objects to the model's forward method
        IValue output = module.forward(inputs);

        // Handle the output
        Tensor outputTensor = output.toTuple()[0].toTensor();  // Adjust if your output structure is different
        long[] outputShape = outputTensor.shape();
        Log.d("TensorInfo", "Output Tensor Shape: " + Arrays.toString(outputShape));

        float[] outputData = outputTensor.getDataAsFloatArray();
        // Print the 32 values in the tensor
        Log.d("TensorInfo", "Output Tensor Values: " + Arrays.toString(outputData));

//        return outputData.getDataAsFloatArray();
        // Return the output array
        return outputData;
    }

    private String assetFilePath(Context context, String assetName) throws IOException {
        File file = new File(context.getFilesDir(), assetName);
        if (file.exists() && file.length() > 0) {
            return file.getAbsolutePath();
        }
        try (InputStream is = context.getAssets().open(assetName);
             FileOutputStream fos = new FileOutputStream(file)) {
            byte[] buffer = new byte[4 * 1024];
            int read;
            while ((read = is.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
            fos.flush();
        }
        return file.getAbsolutePath();
    }
//}



//package com.example.cauthnet;
//
//import android.content.Context;
//import android.hardware.Sensor;
//import android.hardware.SensorEvent;
//import android.hardware.SensorEventListener;
//import android.hardware.SensorManager;
//import android.os.Bundle;
//import android.util.Log;
//import android.view.View;
//import android.widget.Button;
//import android.util.Log;
//
//import androidx.appcompat.app.AppCompatActivity;
//
//import org.pytorch.IValue;
//import org.pytorch.Module;
//import org.pytorch.Tensor;
//import org.pytorch.torchvision.TensorImageUtils;
//
//import java.io.File;
//import java.io.FileOutputStream;
//import java.io.IOException;
//import java.io.InputStream;
//import java.util.ArrayDeque;
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.Deque;
//import java.util.List;
//
//public class MainActivity extends AppCompatActivity implements SensorEventListener {
//
//    private static final String TAG = "SensorStats";
//
//    private SensorManager sensorManager;
//    private Sensor accelerometer, gyroscope, magnetometer;
//
//    private Deque<float[]> accelData = new ArrayDeque<>(10);
//    private Deque<float[]> gyroData = new ArrayDeque<>(10);
//    private Deque<float[]> magData = new ArrayDeque<>(10);
//    private Deque<float[]> accelDataPostClick = new ArrayDeque<>(10);
//    private Deque<float[]> gyroDataPostClick = new ArrayDeque<>(10);
//    private Deque<float[]> magDataPostClick = new ArrayDeque<>(10);
//
//    private boolean isButtonClicked = false;
//    private Module module;  // PyTorch Module
//
//    @Override
//    protected void onCreate(Bundle savedInstanceState) {
//        super.onCreate(savedInstanceState);
//        setContentView(R.layout.activity_main);
//
//        // Initialize SensorManager
//        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
//
//        // Initialize sensors
//        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
//        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
//        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
//
//        Button btnShowStats = findViewById(R.id.btnShowStats);
//        List<float[]> allStatistics = new ArrayList<>(); // Store all statistics arrays
//
//        // Load the PyTorch model
//        try {
//            module = Module.load(assetFilePath(this, "model_scripted.pt"));
//        } catch (Exception e) {
//            Log.e(TAG, "Error loading model", e);
//        }
//
//        btnShowStats.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                calculateAndPrintStatistics();
//                float[] newStatistics = createStatisticsArray();
//                allStatistics.add(newStatistics);  // Add the new statistics array
//                float[] averageStatistics = calculateAverageStatistics(allStatistics);
//                Log.d(TAG, "Average Statistics: " + Arrays.toString(averageStatistics));
//
//                // Pass the average statistics to the model
//                float [] result = runModel(averageStatistics);
//                Log.d(TAG, "Model Output: " + result[3]);
//
//                isButtonClicked = true;
//
//                // Reset pre-click data with current post-click data for next iteration
//                copyPostClickDataToPreClickData();
//                clearPostClickData(); // Clear post-click data to start new collection
//            }
//        });
//    }
//
//    @Override
//    protected void onResume() {
//        super.onResume();
//        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
//        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL);
//        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_NORMAL);
//    }
//
//    @Override
//    protected void onPause() {
//        super.onPause();
//        sensorManager.unregisterListener(this);
//    }
//
//    @Override
//    public void onSensorChanged(SensorEvent event) {
//        Deque<float[]> queue = null;
//        Deque<float[]> queuePostClick = null;
//
//        switch (event.sensor.getType()) {
//            case Sensor.TYPE_ACCELEROMETER:
//                queue = accelData;
//                queuePostClick = accelDataPostClick;
//                break;
//            case Sensor.TYPE_GYROSCOPE:
//                queue = gyroData;
//                queuePostClick = gyroDataPostClick;
//                break;
//            case Sensor.TYPE_MAGNETIC_FIELD:
//                queue = magData;
//                queuePostClick = magDataPostClick;
//                break;
//        }
//
//        if (queue != null) {
//            float[] valuesWithMagnitude = new float[4];
//            System.arraycopy(event.values, 0, valuesWithMagnitude, 0, 3);
//            valuesWithMagnitude[3] = calculateMagnitude(event.values);
//
//            if (!isButtonClicked) {
//                if (queue.size() == 10) {
//                    queue.pollFirst(); // Remove the oldest entry
//                }
//                queue.offerLast(valuesWithMagnitude); // Add the latest entry
//            } else {
//                if (queuePostClick.size() == 10) {
//                    queuePostClick.pollFirst(); // Remove the oldest entry
//                }
//                queuePostClick.offerLast(valuesWithMagnitude); // Add the latest entry
//            }
//        }
//    }
//
//    @Override
//    public void onAccuracyChanged(Sensor sensor, int accuracy) {
//        // Handle changes in sensor accuracy if needed
//    }
//
//    private float calculateMagnitude(float[] values) {
//        return (float) Math.sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2]);
//    }
//
//    private void calculateAndPrintStatistics() {
//        printStatistics("Accelerometer", accelData, accelDataPostClick);
//        printStatistics("Gyroscope", gyroData, gyroDataPostClick);
//        printStatistics("Magnetometer", magData, magDataPostClick);
//    }
//
//    private void printStatistics(String sensorType, Deque<float[]> dataBefore, Deque<float[]> dataAfter) {
//        float[] meanBefore = calculateMean(dataBefore);
//        float[] meanAfter = calculateMean(dataAfter);
//        float[] stdDevBefore = calculateStdDev(dataBefore, meanBefore);
//        float[] stdDevAfter = calculateStdDev(dataAfter, meanAfter);
//        float[] minBefore = calculateMin(dataBefore);
//        float[] minAfter = calculateMin(dataAfter);
//        float[] maxBefore = calculateMax(dataBefore);
//        float[] maxAfter = calculateMax(dataAfter);
//
//        Log.d(TAG, sensorType + " Statistics Before Button Click:");
//        printValues("Mean", meanBefore);
//        printValues("StdDev", stdDevBefore);
//        printValues("Min", minBefore);
//        printValues("Max", maxBefore);
//
//        Log.d(TAG, sensorType + " Statistics After Button Click:");
//        printValues("Mean", meanAfter);
//        printValues("StdDev", stdDevAfter);
//        printValues("Min", minAfter);
//        printValues("Max", maxAfter);
//
//        Log.d(TAG, sensorType + " Statistics Differences:");
//        printDifferences("Mean", meanBefore, meanAfter);
//        printDifferences("StdDev", stdDevBefore, stdDevAfter);
//        printDifferences("Min", minBefore, minAfter);
//        printDifferences("Max", maxBefore, maxAfter);
//    }
//
//    private void printValues(String label, float[] values) {
//        Log.d(TAG, label + ": X=" + values[0] + ", Y=" + values[1] + ", Z=" + values[2] + ", M=" + values[3]);
//    }
//
//    private void printDifferences(String label, float[] before, float[] after) {
//        Log.d(TAG, label + " Difference: X=" + (after[0] - before[0]) + ", Y=" + (after[1] - before[1]) + ", Z=" + (after[2] - before[2]) + ", M=" + (after[3] - before[3]));
//    }
//
    private float[] calculateMean(Deque<float[]> data) {
        if (data.isEmpty()) return new float[]{0, 0, 0, 0};
        float[] mean = new float[4];
        for (float[] values : data) {
            mean[0] += values[0];
            mean[1] += values[1];
            mean[2] += values[2];
            mean[3] += values[3];
        }
        for (int i = 0; i < 4; i++) {
            mean[i] /= data.size();
        }
        return mean;
    }

    private float[] calculateStdDev(Deque<float[]> data, float[] mean) {
        if (data.isEmpty()) return new float[]{0, 0, 0, 0};
        float[] stdDev = new float[4];
        for (float[] values : data) {
            stdDev[0] += (values[0] - mean[0]) * (values[0] - mean[0]);
            stdDev[1] += (values[1] - mean[1]) * (values[1] - mean[1]);
            stdDev[2] += (values[2] - mean[2]) * (values[2] - mean[2]);
            stdDev[3] += (values[3] - mean[3]) * (values[3] - mean[3]);
        }
        for (int i = 0; i < 4; i++) {
            stdDev[i] = (float) Math.sqrt(stdDev[i] / data.size());
        }
        return stdDev;
    }

    private float[] calculateMin(Deque<float[]> data) {
        if (data.isEmpty()) return new float[]{Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] min = new float[]{Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        for (float[] values : data) {
            min[0] = Math.min(min[0], values[0]);
            min[1] = Math.min(min[1], values[1]);
            min[2] = Math.min(min[2], values[2]);
            min[3] = Math.min(min[3], values[3]);
        }
        return min;
    }

    private float[] calculateMax(Deque<float[]> data) {
        if (data.isEmpty()) return new float[]{0, 0, 0, 0};
        float[] max = new float[]{Float.MIN_VALUE, Float.MIN_VALUE, Float.MIN_VALUE, Float.MIN_VALUE};
        for (float[] values : data) {
            max[0] = Math.max(max[0], values[0]);
            max[1] = Math.max(max[1], values[1]);
            max[2] = Math.max(max[2], values[2]);
            max[3] = Math.max(max[3], values[3]);
        }
        return max;
    }
//
//    private void copyPostClickDataToPreClickData() {
//        accelData.clear();
//        accelData.addAll(accelDataPostClick);
//        gyroData.clear();
//        gyroData.addAll(gyroDataPostClick);
//        magData.clear();
//        magData.addAll(magDataPostClick);
//    }
//
//    private void clearPostClickData() {
//        accelDataPostClick.clear();
//        gyroDataPostClick.clear();
//        magDataPostClick.clear();
//    }
//
//    private float[] createStatisticsArray() {
//        float[] statistics = new float[144];
//        int index = 0;
//
//        // Accelerometer
//        index = fillSensorStats(accelData, accelDataPostClick, statistics, index);
//
//        // Gyroscope
//        index = fillSensorStats(gyroData, gyroDataPostClick, statistics, index);
//
//        // Magnetometer
//        index = fillSensorStats(magData, magDataPostClick, statistics, index);
//
//        Log.d(TAG, "Statistics Array: " + Arrays.toString(statistics));
//
//        return statistics;
//    }
//
//    // Helper method to fill statistics for a specific sensor type
//    private int fillSensorStats(Deque<float[]> dataBefore, Deque<float[]> dataAfter, float[] statistics, int index) {
//        float[] meanBefore = calculateMean(dataBefore);
//        float[] stdDevBefore = calculateStdDev(dataBefore, meanBefore);
//        float[] minBefore = calculateMin(dataBefore);
//        float[] maxBefore = calculateMax(dataBefore);
//
//        float[] meanAfter = calculateMean(dataAfter);
//        float[] stdDevAfter = calculateStdDev(dataAfter, meanAfter);
//        float[] minAfter = calculateMin(dataAfter);
//        float[] maxAfter = calculateMax(dataAfter);
//
//        float[] diffMean = calculateDiff(meanBefore, meanAfter);
//        float[] diffStdDev = calculateDiff(stdDevBefore, stdDevAfter);
//        float[] diffMin = calculateDiff(minBefore, minAfter);
//        float[] diffMax = calculateDiff(maxBefore, maxAfter);
//
//        // Manually fill in the statistics in the desired order
//        for (int i = 0; i < 4; i++) { // Iterate over X, Y, Z, M
//            statistics[index++] = meanBefore[i];
//            statistics[index++] = stdDevBefore[i];
//            statistics[index++] = minBefore[i];
//            statistics[index++] = maxBefore[i];
//        }
//
//        for (int i = 0; i < 4; i++) { // Iterate over X, Y, Z, M
//            statistics[index++] = meanAfter[i];
//            statistics[index++] = stdDevAfter[i];
//            statistics[index++] = minAfter[i];
//            statistics[index++] = maxAfter[i];
//        }
//        for (int i = 0; i < 4; i++) { // Iterate over X, Y, Z, M
//            statistics[index++] = diffMean[i];
//            statistics[index++] = diffStdDev[i];
//            statistics[index++] = diffMin[i];
//            statistics[index++] = diffMax[i];
//        }
//
//        return index;
//    }
//
//    // Helper method to calculate differences between two arrays
//    private float[] calculateDiff(float[] arr1, float[] arr2) {
//        float[] diff = new float[arr1.length];
//        for (int i = 0; i < arr1.length; i++) {
//            diff[i] = arr2[i] - arr1[i];
//        }
//        return diff;
//    }
//
//    private float[] calculateAverageStatistics(List<float[]> allStatistics) {
//        float[] average = new float[144];
//        for (float[] stats : allStatistics) {
//            for (int i = 0; i < 144; i++) {
//                average[i] += stats[i];
//            }
//        }
//        for (int i = 0; i < 144; i++) {
//            average[i] /= allStatistics.size();
//        }
//        return average;
//    }
//
//    // Helper method to load the model file from assets
//    public static String assetFilePath(Context context, String assetName) throws IOException {
//        File file = new File(context.getFilesDir(), assetName);
//        try (InputStream is = context.getAssets().open(assetName);
//             FileOutputStream fos = new FileOutputStream(file)) {
//            byte[] buffer = new byte[4 * 1024];
//            int read;
//            while ((read = is.read(buffer)) != -1) {
//                fos.write(buffer, 0, read);
//            }
//            fos.flush();
//        }
//        return file.getAbsolutePath();
//    }
//
//
//    // Run model and get result
//    private float[] runModel(float[] averageStatistics) {
//
//        for (int i = 0; i < averageStatistics.length; i++) {
//            averageStatistics[i] = Math.max(-10, Math.min(averageStatistics[i], 10));
//        }
//
//
//        // Convert the averageStatistics array to a tensor
//        Tensor inputTensor = Tensor.fromBlob(averageStatistics, new long[]{1, 144});
//
//        // Log tensor shape and type
//        Log.d("TensorInfo", "Input Tensor Shape: " + java.util.Arrays.toString(inputTensor.shape()));
//
//        // Create an array of IValue objects, one for each tensor
//        IValue[] inputs = new IValue[]{IValue.from(inputTensor), IValue.from(inputTensor)};
//
//        // Pass the array of IValue objects to the model's forward method
//        IValue output = module.forward(inputs);
//
//        // Handle the output
//        Tensor outputTensor = output.toTuple()[0].toTensor();  // Adjust if your output structure is different
//        long[] outputShape = outputTensor.shape();
//        Log.d("TensorInfo", "Output Tensor Shape: " + java.util.Arrays.toString(outputShape));
//
//        float[] outputData = outputTensor.getDataAsFloatArray();
//        // Print the 32 values in the tensor
//        Log.d("TensorInfo", "Output Tensor Values: " + java.util.Arrays.toString(outputData));
//
//
//        // Return the output array
//        return outputData;
//    }
}
