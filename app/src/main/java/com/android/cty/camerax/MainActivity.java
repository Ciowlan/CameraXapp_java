package com.android.cty.camerax;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;

import android.content.res.AssetManager;
import android.graphics.PointF;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Surface;
import android.widget.CompoundButton;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.android.cty.camerax.databinding.ActivityMainBinding;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.PoseLandmark;
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.android.cty.camerax.classification.PoseClassifierProcessor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
    private OverlayView overlayView;
    private ActivityMainBinding viewBinding;

    private ImageCapture imageCapture = null;

    private VideoCapture videoCapture = null;
    private Recording recording = null;

    private ExecutorService cameraExecutor;

    CameraSelector cameraSelector;

    private PoseDetector poseDetector;

    private GraphicOverlay graphicOverlay;
    private boolean isFrontCamera = false;

    private boolean camerastart = false;

    // 在您的 Activity 或 Fragment 中的成员变量中创建一个 PoseClassifierProcessor 实例
    private PoseClassifierProcessor poseClassifierProcessor;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewBinding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(viewBinding.getRoot());

        // 请求相机权限
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, Configuration.REQUIRED_PERMISSIONS,
                    Configuration.REQUEST_CODE_PERMISSIONS);
        }

        // 设置拍照按钮监听
        //viewBinding.imageCaptureButton.setOnClickListener(v -> takePhoto());
        viewBinding.videoCaptureButton.setOnClickListener(v -> captureVideo());

        cameraExecutor = Executors.newSingleThreadExecutor();

        ToggleButton facingSwitch = viewBinding.facingSwitch;
        facingSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                } else {
                    cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
                }
                // 重新绑定相机预览
                startCamera();
                CamerUseCase();

            }
        });

        // 初始化姿势估计器
        poseDetector = getPoseDetector();

        // 初始化 PoseClassifierProcessor，第二个参数表示是否为流模式
        poseClassifierProcessor = null;


    }


    @SuppressLint("CheckResult")
    private void captureVideo() {
        // 确保videoCapture 已经被实例化，否则程序可能崩溃
        if (videoCapture != null) {
            // 禁用UI，直到CameraX 完成请求
            viewBinding.videoCaptureButton.setEnabled(false);

            Recording curRecording = recording;
            if (curRecording != null) {
                // 如果正在录制，需要先停止当前的 recording session（录制会话）
                curRecording.stop();
                recording = null;
                return;
            }

            // 创建一个新的 recording session
            // 首先，创建MediaStore VideoContent对象，用以设置录像通过MediaStore的方式保存
            String name = new SimpleDateFormat(Configuration.FILENAME_FORMAT, Locale.SIMPLIFIED_CHINESE)
                    .format(System.currentTimeMillis());
            ContentValues contentValues = new ContentValues();
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video");
            }

            MediaStoreOutputOptions mediaStoreOutputOptions = new MediaStoreOutputOptions
                    .Builder(getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    .setContentValues(contentValues)
                    .build();
            // 申请音频权限
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        Configuration.REQUEST_CODE_PERMISSIONS);
            }
            Recorder recorder = (Recorder) videoCapture.getOutput();
            recording = recorder.prepareRecording(this, mediaStoreOutputOptions)
                    .withAudioEnabled() // 开启音频录制
                    // 开始新录制
                    .start(ContextCompat.getMainExecutor(this), videoRecordEvent -> {
                        if (videoRecordEvent instanceof VideoRecordEvent.Start) {
                            viewBinding.videoCaptureButton.setText(getString(R.string.stop_capture));
                            viewBinding.videoCaptureButton.setEnabled(true);// 启动录制时，切换按钮显示文本
                        } else if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {//录制完成后，使用Toast通知
                            if (!((VideoRecordEvent.Finalize) videoRecordEvent).hasError()) {
                                String msg = "Video capture succeeded: " +
                                        ((VideoRecordEvent.Finalize) videoRecordEvent).getOutputResults()
                                                .getOutputUri();
                                Toast.makeText(getBaseContext(), msg, Toast.LENGTH_SHORT).show();
                                Log.d(Configuration.TAG, msg);
                            } else {
                                if (recording != null) {
                                    recording.close();
                                    recording = null;
                                    Log.e(Configuration.TAG, "Video capture end with error: " +
                                            ((VideoRecordEvent.Finalize) videoRecordEvent).getError());
                                }
                            }
                            viewBinding.videoCaptureButton.setText(getString(R.string.start_capture));
                            viewBinding.videoCaptureButton.setEnabled(true);
                        }
                    });
        }
    }


//    private void takePhoto() {
//        // 确保imageCapture 已经被实例化, 否则程序将可能崩溃
//        if (imageCapture != null) {
//            // 创建一个 "MediaStore Content" 以保存图片，带时间戳是为了保证文件名唯一
//            String name = new SimpleDateFormat(Configuration.FILENAME_FORMAT,
//                    Locale.SIMPLIFIED_CHINESE).format(System.currentTimeMillis());
//            ContentValues contentValues = new ContentValues();
//            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
//            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
//            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
//                contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image");
//            }
//
//            // 创建 output option 对象，用以指定照片的输出方式。
//            // 在这个对象中指定有关我们希望输出如何的方式。我们希望将输出保存在 MediaStore 中，以便其他应用可以显示它
//            ImageCapture.OutputFileOptions outputFileOptions = new ImageCapture.OutputFileOptions
//                    .Builder(getContentResolver(),
//                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
//                    contentValues)
//                    .build();
//
//            // 设置拍照监听，用以在照片拍摄后执行takePicture（拍照）方法
//            imageCapture.takePicture(outputFileOptions,
//                    ContextCompat.getMainExecutor(this),
//                    new ImageCapture.OnImageSavedCallback() {// 保存照片时的回调
//                        @Override
//                        public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
//                            String msg = "照片捕获成功! " + outputFileResults.getSavedUri();
//                            Toast.makeText(getBaseContext(), msg, Toast.LENGTH_SHORT).show();
//                            Log.d(Configuration.TAG, msg);
//                        }
//
//                        @Override
//                        public void onError(@NonNull ImageCaptureException exception) {
//                            Log.e(Configuration.TAG, "Photo capture failed: " + exception.getMessage());// 拍摄或保存失败时
//                        }
//                    });
//        }
//    }

    private void startCamera() {
        // 将Camera的生命周期和Activity绑定在一起（设定生命周期所有者），这样就不用手动控制相机的启动和关闭。
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                // 将你的相机绑定到APP进程的lifecycleOwner中
                ProcessCameraProvider processCameraProvider = cameraProviderFuture.get();

                // 创建一个Preview 实例，并设置该实例的 surface 提供者（provider）。
                PreviewView viewFinder = (PreviewView)findViewById(R.id.viewFinder);
                Preview preview = new Preview.Builder()
                        .build();
                preview.setSurfaceProvider(viewBinding.viewFinder.getSurfaceProvider());

                // 创建录像所需实例
                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);


                // 选择摄像头
                cameraSelector = isFrontCamera ? CameraSelector.DEFAULT_FRONT_CAMERA
                        : CameraSelector.DEFAULT_BACK_CAMERA;


                // 创建拍照所需的实例
                imageCapture = new ImageCapture.Builder().build();

                // 創建並設置graphicOverlay
                graphicOverlay = new GraphicOverlay(this, null);
                viewFinder.getOverlay().add(graphicOverlay);


                // 设置预览帧分析
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .build();
                imageAnalysis.setAnalyzer(cameraExecutor, new MyAnalyzer());

                // 设置图像分析回调
                imageAnalysis.setAnalyzer(cameraExecutor, new PoseAnalyzer());

                // 重新绑定用例前先解绑
                processCameraProvider.unbindAll();

                // 绑定用例至相机
                processCameraProvider.bindToLifecycle(MainActivity.this, cameraSelector,
                        preview,
                        imageCapture/*,
                        imageAnalysis*/,
                        videoCapture);
                camerastart = true;

            } catch (Exception e) {
                Log.e(Configuration.TAG, "用例绑定失败！" + e);
                camerastart = false;
            }
        }, /*在主线程运行*/ContextCompat.getMainExecutor(this));
    }

    private boolean allPermissionsGranted() {
        for (String permission : Configuration.REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }

    //存檔
    static class Configuration {
        public static final String TAG = "CameraxBasic";
        public static final String FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS";
        public static final int REQUEST_CODE_PERMISSIONS = 10;
        public static final int REQUEST_AUDIO_CODE_PERMISSIONS = 12;
        public static final String[] REQUIRED_PERMISSIONS =
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P ?
                        new String[]{Manifest.permission.CAMERA,
                                Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE} :
                        new String[]{Manifest.permission.CAMERA,
                                Manifest.permission.RECORD_AUDIO};
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == Configuration.REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {// 申请权限通过
                startCamera();
            } else {// 申请权限失败
                Toast.makeText(this, "用户拒绝授予权限！", Toast.LENGTH_LONG).show();
                finish();
            }
        } else if (requestCode == Configuration.REQUEST_AUDIO_CODE_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this,
                    "Manifest.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "未授权录制音频权限！", Toast.LENGTH_LONG).show();
            }
        }
    }

    private static class MyAnalyzer implements ImageAnalysis.Analyzer{
        @SuppressLint("UnsafeOptInUsageError")
        @Override
        public void analyze(@NonNull ImageProxy image) {
            Log.d(Configuration.TAG, "Image's stamp is " + Objects.requireNonNull(image.getImage()).getTimestamp());
            image.close();
        }
    }

    private PoseDetector getPoseDetector(){

        PoseDetectorOptions options =
                new PoseDetectorOptions.Builder()
                        .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                        .build();
        return PoseDetection.getClient(options);
    }


        private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
        static {
            ORIENTATIONS.append(Surface.ROTATION_0, 0);
            ORIENTATIONS.append(Surface.ROTATION_90, 90);
            ORIENTATIONS.append(Surface.ROTATION_180, 180);
            ORIENTATIONS.append(Surface.ROTATION_270, 270);
        }

//        /**
//         * Get the angle by which an image must be rotated given the device's current
//         * orientation.
//         */
//        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
//        private int getRotationCompensation(String cameraId, Activity activity, boolean isFrontFacing)
//                throws CameraAccessException {
//            // Get the device's current rotation relative to its "native" orientation.
//            // Then, from the ORIENTATIONS table, look up the angle the image must be
//            // rotated to compensate for the device's rotation.
//            int deviceRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
//            int rotationCompensation = ORIENTATIONS.get(deviceRotation);
//
//            // Get the device's sensor orientation.
//            CameraManager cameraManager = (CameraManager) activity.getSystemService(CAMERA_SERVICE);
//            int sensorOrientation = cameraManager
//                    .getCameraCharacteristics(cameraId)
//                    .get(CameraCharacteristics.SENSOR_ORIENTATION);
//
//            if (isFrontFacing) {
//                rotationCompensation = (sensorOrientation + rotationCompensation) % 360;
//            } else { // back-facing
//                rotationCompensation = (sensorOrientation - rotationCompensation + 360) % 360;
//            }
//            return rotationCompensation;
//        }

//        private class Toggle implements CompoundButton.OnCheckedChangeListener{
//            @Override
//            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
//                if(isChecked)
//                {
//                    cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
//                }
//                else {
//                cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
//                    }
//            }
//        }
    private class PoseAnalyzer implements ImageAnalysis.Analyzer {

        @Override
        public void analyze(@NonNull ImageProxy imageProxy) {

            // 获取图像数据
            ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int width = imageProxy.getWidth();
            int height = imageProxy.getHeight();
            int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();

            // 创建InputImage对象
            InputImage image = InputImage.fromByteBuffer(buffer, width, height, rotationDegrees,
                    InputImage.IMAGE_FORMAT_NV21);

            // 进行姿势估计
            poseDetector.process(image)
                    .addOnSuccessListener(pose -> {
                        // 创建一个 JSON 对象来存储坐标数据
                        JSONObject jsonObject = new JSONObject();

                        // 获取所有关节的坐标信息
                        List<PoseLandmark> landmarks = pose.getAllPoseLandmarks();


                        try {
                            for (PoseLandmark landmark : landmarks) {
                                int landmarkType = landmark.getLandmarkType();
                                PointF landmarkPosition = landmark.getPosition();

                                // 将 XYZ 坐标数据添加到 JSON 对象
                                JSONArray coordinatesArray = new JSONArray();
                                coordinatesArray.put(landmarkPosition.x);
                                coordinatesArray.put(landmarkPosition.y);
                                //coordinatesArray.put(landmarkPosition.z);

                                jsonObject.put(Integer.toString(landmarkType), coordinatesArray);
                            }

                            // 将 JSON 对象保存为 JSON 文件
                            saveJsonToFile(jsonObject.toString());

                        } catch (JSONException e) {
                            e.printStackTrace();
                        }

                        // TODO: 使用 poseResults 进行相关处理
                    })
                    .addOnFailureListener(e -> {
                        // 处理姿势估计失败的情况
                        Log.e(Configuration.TAG, "Pose detection failed: " + e.getMessage());
                    });

            imageProxy.close();
        }
    private void saveJsonToFile(String jsonData) {
        try {
            // 创建一个 JSON 文件输出流
            FileWriter fileWriter = new FileWriter("pose_data.json");

            // 将 JSON 数据写入文件
            fileWriter.write(jsonData);

            // 关闭文件输出流
            fileWriter.close();

            // 文件保存成功
        } catch (IOException e) {
            e.printStackTrace();
            // 处理文件保存失败
        }
    }

    }

    private void CamerUseCase(){
            if(camerastart){

                // 創建overlayView並將其添加到graphicOverlay

                overlayView = new OverlayView(
                        graphicOverlay,
                        null,
                        false,
                        false,
                        false,
                        null
                );
                graphicOverlay.add(overlayView);

            }
    }
    private void readCsvDataFromAssets() {
        // 使用 AssetManager 打開 CSV 檔案
        AssetManager assetManager = getAssets();
        try {
            // 指定 CSV 文件的路徑，這個路徑應該與 assets 目錄中的相對路徑一致
            String csvFilePath = "pose/fitness_pose_samples.csv";

            // 打開 CSV 文件的輸入流
            InputStream inputStream = assetManager.open(csvFilePath);

            // 使用 inputStream 來讀取 CSV 文件的內容
            InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

            // 逐行讀取 CSV 數據
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                // 在這裡處理每一行的數據，您可以將數據解析為適當的數據結構
                // 例如，您可以使用 String 的 split() 方法將每一行拆分為字段
                String[] fields = line.split(",");
                // 處理字段...
            }

            // 關閉流
            bufferedReader.close();
            inputStreamReader.close();
            inputStream.close();
        } catch (IOException e) {
            // 處理異常
            e.printStackTrace();
        }
    }

}

