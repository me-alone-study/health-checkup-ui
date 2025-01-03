package com.example.tjy;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.AnimationDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import android.content.res.Configuration;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class CameraActivity extends Activity {
    private static final String TAG = "CameraActivity";

    private TextureView mTextureView;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    private Size mPreviewSize;
    private ImageReader mImageReader;
    private int MAX_PREVIEW_WIDTH = 1920;
    private int MAX_PREVIEW_HEIGHT = 1080;
    private CaptureRequest.Builder mCaptureRequestBuilder;
    private Handler mHandler;
    private ProgressDialog mProgressDialog;

    // 健康数据显示的TextViews
    private TextView tvSpo2;
    private TextView tvAge;
    private TextView tvGender;
    private TextView tvBloodPressure;
    private TextView tvRespiratory;
    private TextView tvHeartRate;
    private View mRecordingIndicator;
    private View mRecordingDot;
    private AnimationDrawable mRecordingAnimation;
    // 添加到类成员变量中

    //录制视频
    private static final int VIDEO_DURATION = 20000; // 20秒
    private MediaRecorder mMediaRecorder;
    private boolean isRecording = false;
    private String mVideoFilePath;

    private final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        }
    };

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = reader.acquireLatestImage();
            if (image != null) {
                // 在这里处理图像数据,进行健康数据分析
                // ...
                image.close();
            }
        }
    };

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            cameraDevice.close();
            mCameraDevice = null;
            finish();
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);
        mTextureView = findViewById(R.id.texture_view);
//        adjustViewsForScreenDensity();
        initViews();
        findViewById(R.id.btn_start).setOnClickListener(v -> startRecording());
    }


//    // 适应屏幕
//    private void adjustViewsForScreenDensity() {
//        float density = getResources().getDisplayMetrics().density;
//        if (density >= 3.0) { // xxhdpi
//            MAX_PREVIEW_WIDTH = 2560;
//            MAX_PREVIEW_HEIGHT = 1440;
//        } else if (density >= 2.0) { // xhdpi
//            MAX_PREVIEW_WIDTH = 1920;
//            MAX_PREVIEW_HEIGHT = 1080;
//        }
//    }

    // 屏幕旋转处理
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        adjustLayoutForOrientation(newConfig.orientation);
    }

    private void adjustLayoutForOrientation(int orientation) {
        ConstraintLayout rootLayout = findViewById(R.id.root_layout);
        ConstraintSet constraintSet = new ConstraintSet();
        constraintSet.clone(rootLayout);

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            constraintSet.setGuidelinePercent(R.id.guideline, 0.7f);
        } else {
            constraintSet.setGuidelinePercent(R.id.guideline, 0.6f);
        }

        constraintSet.applyTo(rootLayout);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseMediaRecorder();
    }

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (mBackgroundThread != null) {
            mBackgroundThread.quitSafely();
            try {
                mBackgroundThread.join();
                mBackgroundThread = null;
                mBackgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while shutting down background thread", e);
            }
        }
    }

    private void openCamera(int width, int height) {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = getBackCameraId(manager);
            if (cameraId == null) {
                Log.d(TAG, "没有找到后置摄像头");
                finish();
                return;
            }

            // 获取相机特性
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                throw new RuntimeException("Cannot get available preview/video sizes");
            }

            // 选择最佳预览尺寸
            mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                    width, height, MAX_PREVIEW_WIDTH, MAX_PREVIEW_HEIGHT);

            // 创建 ImageReader
            mImageReader = ImageReader.newInstance(mPreviewSize.getWidth(), mPreviewSize.getHeight(),
                    ImageFormat.YUV_420_888, 2);
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);

            manager.openCamera(cameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            //Toast.makeText(this, "无法访问相机", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "无法访问相机");
            finish();
        } catch (SecurityException e) {
            //Toast.makeText(this, "没有相机权限", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "没有相机权限");
            finish();
        }
    }

    private Size chooseOptimalSize(Size[] choices, int textureViewWidth, int textureViewHeight,
                                   int maxWidth, int maxHeight) {
        // 收集满足宽高要求的分辨率
        List<Size> bigEnough = new ArrayList<>();
        List<Size> notBigEnough = new ArrayList<>();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight) {
                if (option.getWidth() >= textureViewWidth &&
                        option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        // 选择最小的满足要求的尺寸
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }



    private String getBackCameraId(CameraManager manager) throws CameraAccessException {
        for (String cameraId : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return cameraId;
            }
        }
        return null;
    }


    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            Surface surface = new Surface(texture);

            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);
            mPreviewRequestBuilder.addTarget(mImageReader.getSurface());

            // 设置自动对焦模式
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            // 设置自动曝光模式
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

            mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            if (null == mCameraDevice) {
                                return;
                            }
                            mCaptureSession = cameraCaptureSession;
                            try {
                                mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                                        null, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Failed to start camera preview", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                            //Toast.makeText(CameraActivity.this, "配置失败",
                            //Toast.LENGTH_SHORT).show();
                            Log.d(TAG, "配置失败");

                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to preview camera", e);
        }
    }

    private void closeCamera() {
        if (null != mCaptureSession) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
        if (null != mCameraDevice) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
    }

    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    private void initViews() {
        // 相机预览
        mTextureView = findViewById(R.id.texture_view);

        // 录制指示器
        mRecordingIndicator = findViewById(R.id.recording_indicator);
        mRecordingDot = findViewById(R.id.recording_dot);
        mRecordingAnimation = (AnimationDrawable) mRecordingDot.getBackground();

        // 初始化健康数据显示
        tvSpo2 = findViewById(R.id.tv_spo2);
        tvBloodPressure = findViewById(R.id.tv_blood_pressure);
        tvHeartRate = findViewById(R.id.tv_heart_rate);
        tvRespiratory = findViewById(R.id.tv_respiratory);

        // 基本信息显示
        tvAge = findViewById(R.id.tv_age);
        tvGender = findViewById(R.id.tv_gender);

        // 设置按钮点击事件
        findViewById(R.id.btn_start).setOnClickListener(v -> startRecording());
        findViewById(R.id.btn_stop).setOnClickListener(v -> stopRecording());
    }

    // 更新健康数据的方法示例
    private void updateHealthData(String spo2, String age, String gender,
                                  String bloodPressure, String respiratory, String heartRate) {
        tvSpo2.setText(spo2);
        tvAge.setText(age);
        tvGender.setText(gender);
        tvBloodPressure.setText(bloodPressure);
        tvRespiratory.setText(respiratory);
        tvHeartRate.setText(heartRate);
    }

    private void startRecording() {
        if (!isRecording) {
            try {
                mVideoFilePath = getExternalFilesDir(null) + "/health_video.mp4";
                mMediaRecorder = new MediaRecorder();

                // 移除音频源设置，只设置视频源
                mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mMediaRecorder.setOutputFile(mVideoFilePath);
                mMediaRecorder.setVideoEncodingBitRate(10000000);
                mMediaRecorder.setVideoFrameRate(30);
                mMediaRecorder.setVideoSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
                mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);


                // 获取相机传感器方向
                CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(mCameraDevice.getId());
                int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

                //设置视频方向
                mMediaRecorder.setOrientationHint(90);


                try {
                    mMediaRecorder.prepare();
                } catch (IOException e) {
                    Log.e(TAG, "MediaRecorder prepare failed", e);
                    //Toast.makeText(this, "录制准备失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "录制准备失败: " + e.getMessage());
                    mMediaRecorder.release();
                    mMediaRecorder = null;
                    return;
                }

                SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
                if (surfaceTexture == null) {
                    Log.e(TAG, "Camera preview not ready");
                    //Toast.makeText(this, "相机预览未就绪", Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "相机预览未就绪");
                    mMediaRecorder.release();
                    mMediaRecorder = null;
                    return;
                }

                Surface previewSurface = new Surface(surfaceTexture);
                Surface recordSurface = mMediaRecorder.getSurface();

                try {
                    mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    mCaptureRequestBuilder.addTarget(previewSurface);
                    mCaptureRequestBuilder.addTarget(recordSurface);

                    mCameraDevice.createCaptureSession(
                            Arrays.asList(previewSurface, recordSurface, mImageReader.getSurface()),
                            new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(@NonNull CameraCaptureSession session) {
                                    mCaptureSession = session;
                                    try {
                                        session.setRepeatingRequest(
                                                mCaptureRequestBuilder.build(), null, null);
                                        mMediaRecorder.start();
                                        isRecording = true;
                                        showRecordingStatus();  // 添加这行

                                        new Handler().postDelayed(() -> {
                                            if (isRecording) {
                                                stopRecording();
                                            }
                                        }, VIDEO_DURATION);

                                    } catch (CameraAccessException e) {
                                        Log.e(TAG, "Failed to start camera preview", e);
                                        //Toast.makeText(CameraActivity.this,
                                        //"开启预览失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                        Log.e(TAG, "开启预览失败: " + e.getMessage());
                                        releaseMediaRecorder();
                                    }
                                }

                                @Override
                                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                    //Toast.makeText(CameraActivity.this,
                                    //"配置失败", Toast.LENGTH_SHORT).show();
                                    Log.d(TAG, "配置失败");
                                    releaseMediaRecorder();
                                }
                            }, null);
                } catch (CameraAccessException e) {
                    Log.e(TAG, "Failed to start recording", e);
                    //Toast.makeText(this, "录制设置失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "录制设置失败: " + e.getMessage());
                    releaseMediaRecorder();
                }
            } catch (Exception e) {
                Log.e(TAG, "Recording failed", e);
                //Toast.makeText(this, "录制失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                Log.e(TAG, "录制失败: " + e.getMessage());
                releaseMediaRecorder();
            }
        }
    }

    private void showRecordingStatus() {
        mRecordingIndicator.setVisibility(View.VISIBLE);
        mRecordingAnimation.start();
    }

    private void releaseMediaRecorder() {
        if (mMediaRecorder != null) {
            if (isRecording) {  // 只有在录制状态才调用 stop
                try {
                    mMediaRecorder.stop();
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Error stopping MediaRecorder", e);
                }
            }
            try {
                mMediaRecorder.reset();
                mMediaRecorder.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaRecorder", e);
            }
            mMediaRecorder = null;
        }
        isRecording = false;
    }

    private void stopRecording() {
        if (isRecording) {
            mMediaRecorder.stop();
            mMediaRecorder.reset();
            isRecording = false;
            hideRecordingStatus();  // 添加这行
            startAnalyze();
        }
    }

    private void hideRecordingStatus() {
        mRecordingAnimation.stop();
        mRecordingIndicator.setVisibility(View.GONE);
    }

    private void showProgressDialog() {
        if (mProgressDialog == null) {
            mProgressDialog = new ProgressDialog(this);
            mProgressDialog.setMessage("正在分析,请稍候...");
            mProgressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            mProgressDialog.setCancelable(false);
        }
        mProgressDialog.show();
    }

    private void dismissProgressDialog() {
        if (mProgressDialog != null && mProgressDialog.isShowing()) {
            mProgressDialog.dismiss();
        }
    }

    // 定义分析结果的数据类
    private static class AnalysisResult {
        String spo2;
        String age;
        String gender;
        String bloodPressure;
        String respiratory;
        String heartRate;

        AnalysisResult(String spo2, String age, String gender, String bloodPressure,
                       String respiratory, String heartRate) {  // 修改构造函数
            this.spo2 = spo2;
            this.age = age;
            this.gender = gender;
            this.bloodPressure = bloodPressure;
            this.respiratory = respiratory;
            this.heartRate = heartRate;  // 初始化心率
        }
    }

    private AnalysisResult analyzeVideoWithRKNN(String videoPath) {
        try {
            // TODO: 这里需要替换为实际的 RKNN 模型调用
            // 现在仅返回模拟数据用于测试
            Thread.sleep(2000); // 模拟分析过程
            return new AnalysisResult(
                    "98%",
                    "25岁",
                    "男",
                    "120/80 mmHg",
                    "18次/分钟",
                    "75次/分钟"
            );
        } catch (InterruptedException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void startAnalyze() {
        runOnUiThread(() -> {
            showProgressDialog();
            uploadVideo(mVideoFilePath);
        });

//        // 在后台线程中调用 RKNN 模块进行视频分析
//        new Thread(() -> {
//            // 模拟调用 RKNN 模块的接口，获取分析结果
//            // TODO: 实际项目中，这里需要替换为真实的 RKNN 接口调用
//            AnalysisResult result = analyzeVideoWithRKNN(mVideoFilePath);
//
//            // 在 UI 线程更新结果
//            runOnUiThread(() -> {
//                if (result != null) {
//                    updateHealthData(
//                            result.spo2,
//                            result.age,
//                            result.gender,
//                            result.bloodPressure,
//                            result.respiratory,
//                            result.heartRate
//                    );
//                } else {
//                    // 分析失败的处理
//                    updateHealthData(
//                            "分析失败",
//                            "分析失败",
//                            "分析失败",
//                            "分析失败",
//                            "分析失败",
//                            "分析失败"
//                    );
//                }
//                dismissProgressDialog();
//            });
//        }).start();
    }

    private void uploadVideo(String filePath) {
        File file = new File(filePath);
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("video", file.getName(),
                        RequestBody.create(MediaType.parse("video/mp4"), file))
                .build();

        Request request = new Request.Builder()
                .url("http://183.238.1.242:50000/upload")
                .post(requestBody)
                .build();

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(300, TimeUnit.SECONDS)    // 连接超时
                .writeTimeout(300, TimeUnit.SECONDS)      // 写入超时
                .readTimeout(300, TimeUnit.SECONDS)       // 读取超时
                .retryOnConnectionFailure(true)          // 启用失败重试
                .build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    //Toast.makeText(CameraActivity.this,
                    //"上传失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "上传失败: " + e.getMessage());
                    dismissProgressDialog();
                });
            }

            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    String responseData = response.body().string();
                    try {
                        handleUploadResponse(responseData);
                    } catch (JSONException e) {
                        e.printStackTrace();
                        runOnUiThread(() -> {
                            //Toast.makeText(CameraActivity.this,
                            //"解析结果失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            Log.e(TAG, "解析结果失败: " + e.getMessage());
                            dismissProgressDialog();
                        });
                    }
                } else {
                    runOnUiThread(() -> {
                        //Toast.makeText(CameraActivity.this,
                        //"上传失败: " + response.message(), Toast.LENGTH_SHORT).show();
                        Log.e(TAG, "上传失败: " + response.message());
                        dismissProgressDialog();
                    });
                }
            }
        });
    }

    private void handleUploadResponse(String responseData) throws JSONException {
        Log.d(TAG, "收到服务器响应数据: " + responseData);
        JSONObject jsonObject = new JSONObject(responseData);
        if (jsonObject.getString("status").equals("success")) {
            JSONObject data = jsonObject.getJSONObject("data");

            // 解析血氧饱和度
            String spo2 = String.format("%.1f%%", data.getDouble("spo2"));

            // 解析年龄和年龄组
            String age = data.getInt("age") + "岁 (" + data.getString("age_group") + ")";

            // 解析性别
            String gender = data.getString("gender").equals("Male") ? "男" : "女";

            // 解析血压
            JSONObject bp = data.getJSONObject("blood_pressure");
            String bloodPressure = String.format("%d/%d mmHg",
                    bp.getInt("sbp"), bp.getInt("dbp"));

            // 解析呼吸率
            JSONObject respirationObj = data.getJSONObject("respiration");
            String respiratory = String.format("%.0f 次/分钟", respirationObj.getDouble("rate"));

            // 解析心率
            JSONObject heartRate = data.getJSONObject("heart_rate");
            String heartRateStr = String.format("%.0f 次/分钟", heartRate.getDouble("rate"));

            runOnUiThread(() -> {
                updateHealthData(spo2, age, gender, bloodPressure, respiratory, heartRateStr);
                dismissProgressDialog();
            });
        } else {
            Log.d(TAG, "GetResponseDATAAAAAAAA: " + responseData);

            String errorMessage = jsonObject.getString("message");
            runOnUiThread(() -> {
                Log.e(TAG, "上传失败: " + errorMessage);
                dismissProgressDialog();
                updateHealthData("分析失败", "分析失败", "分析失败",
                        "分析失败", "分析失败", "分析失败");
            });
        }
    }
}