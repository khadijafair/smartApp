package com.abdoune.SmartApp_khadija;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.image.ops.Rot90Op;
import org.tensorflow.lite.support.label.TensorLabel;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "TFLiteCameraX";
    private static final int PERMISSION_REQUEST_CAMERA = 1001;
    private static final float CONFIDENCE_THRESHOLD = 0.65f; 

    private PreviewView previewView;
    private TextView resultTextView;
    private ExecutorService cameraExecutor;

    private Interpreter tflite;
    private List<String> labels;
    private int imageSizeX;
    private int imageSizeY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        resultTextView = findViewById(R.id.resultTextView);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERMISSION_REQUEST_CAMERA);
        } else {
            initApp();
        }
    }

    private void initApp() {
        try {
            MappedByteBuffer tfliteModel = FileUtil.loadMappedFile(this, "model_unquant.tflite");
            
            // On force le mode CPU (plus stable pour PC et émulateur)
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(4); 
            
            tflite = new Interpreter(tfliteModel, options);
            labels = FileUtil.loadLabels(this, "labels.txt");

            int[] inputShape = tflite.getInputTensor(0).shape();
            imageSizeY = inputShape[1];
            imageSizeX = inputShape[2];

            cameraExecutor = Executors.newSingleThreadExecutor();
            startCamera();

        } catch (IOException e) {
            Log.e(TAG, "Erreur model: " + e.getMessage());
            Toast.makeText(this, "Fichier modèle manquant dans assets", Toast.LENGTH_LONG).show();
        }
    }

    private void startCamera() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = ProcessCameraProvider.getInstance(this).get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);

            } catch (Exception e) {
                Log.e(TAG, "Erreur CameraX: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeImage(@NonNull ImageProxy image) {
        try {
            // 1. Conversion ImageProxy -> Bitmap
            Bitmap bitmap = image.toBitmap();
            if (bitmap == null) return;

            // 2. Correction Rotation + Recadrage Carré + Normalisation
            int rotationDegrees = image.getImageInfo().getRotationDegrees();
            
            // Recadrage centre pour éviter la déformation
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int size = Math.min(width, height);
            int x = (width - size) / 2;
            int y = (height - size) / 2;
            Bitmap squareBitmap = Bitmap.createBitmap(bitmap, x, y, size, size);

            ImageProcessor imageProcessor = new ImageProcessor.Builder()
                    .add(new Rot90Op(rotationDegrees / 90)) // Remet l'image droite
                    .add(new ResizeOp(imageSizeY, imageSizeX, ResizeOp.ResizeMethod.BILINEAR))
                    .add(new NormalizeOp(127.5f, 127.5f)) // Indispensable pour Teachable Machine
                    .build();

            TensorImage tensorImage = new TensorImage(DataType.FLOAT32);
            tensorImage.load(squareBitmap);
            tensorImage = imageProcessor.process(tensorImage);

            // 3. Inférence
            TensorBuffer probabilityBuffer = TensorBuffer.createFixedSize(tflite.getOutputTensor(0).shape(), DataType.FLOAT32);
            tflite.run(tensorImage.getBuffer(), probabilityBuffer.getBuffer());

            // 4. Extraction du meilleur résultat
            Map<String, Float> results = new TensorLabel(labels, probabilityBuffer).getMapWithFloatValue();
            Map.Entry<String, Float> bestEntry = null;

            for (Map.Entry<String, Float> entry : results.entrySet()) {
                if (bestEntry == null || entry.getValue() > bestEntry.getValue()) {
                    bestEntry = entry;
                }
            }

            if (bestEntry != null && bestEntry.getValue() > CONFIDENCE_THRESHOLD) {
                final String text = String.format(Locale.US, "%s: %.1f%%", bestEntry.getKey(), bestEntry.getValue() * 100);
                runOnUiThread(() -> resultTextView.setText(text));
            } else {
                runOnUiThread(() -> resultTextView.setText("Analyse en cours..."));
            }

        } catch (Exception e) {
            Log.e(TAG, "Erreur analyse: " + e.getMessage());
        } finally {
            image.close();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initApp();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (tflite != null) tflite.close();
    }
}
