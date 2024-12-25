package com.example.tjy;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;

import java.io.IOException;

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
    private static final int MAX_PREVIEW_WIDTH = 1920;
    private static final int MAX_PREVIEW_HEIGHT = 1080;
    private CaptureRequest.Builder mCaptureRequestBuilder;
    private Handler mHandler;
    private ProgressDialog mProgressDialog;

    // 健康数据显示的TextViews
    private TextView tvSpo2;
    private TextView tvAge;
    private TextView tvGender;
    private TextView tvBloodPressure;
    private TextView tvRespiratory;

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
        setContentView(R.layout.activity_main);
        mTextureView = findViewById(R.id.texture_view);
        initViews();
        findViewById(R.id.btn_start).setOnClickListener(v -> startRecording());
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
                Toast.makeText(this, "没有找到后置摄像头", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "无法访问相机", Toast.LENGTH_SHORT).show();
            finish();
        } catch (SecurityException e) {
            Toast.makeText(this, "没有相机权限", Toast.LENGTH_SHORT).show();
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
                            Toast.makeText(CameraActivity.this, "配置失败",
                                    Toast.LENGTH_SHORT).show();
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

        // 初始化健康数据显示
        tvSpo2 = findViewById(R.id.tv_spo2);
        tvAge = findViewById(R.id.tv_age);
        tvGender = findViewById(R.id.tv_gender);
        tvBloodPressure = findViewById(R.id.tv_blood_pressure);
        tvRespiratory = findViewById(R.id.tv_respiratory);
    }

    // 更新健康数据的方法示例
    private void updateHealthData(String spo2, String age, String gender, String bloodPressure, String respiratory) {
        tvSpo2.setText(spo2);
        tvAge.setText(age);
        tvGender.setText(gender);
        tvBloodPressure.setText(bloodPressure);
        tvRespiratory.setText(respiratory);
    }

    private void startRecording() {
        if (!isRecording) {
            try {
                mVideoFilePath = getExternalFilesDir(null) + "/health_video.mp4";
                String path = getExternalFilesDir(null).getAbsolutePath();
                Log.d("CameraActivity", "storagePathFile: " + path);
                mMediaRecorder = new MediaRecorder();

                // 移除音频源设置，只设置视频源
                mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mMediaRecorder.setOutputFile(mVideoFilePath);
                mMediaRecorder.setVideoEncodingBitRate(10000000);
                mMediaRecorder.setVideoFrameRate(30);
                mMediaRecorder.setVideoSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
                mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                // 移除音频编码器设置

                try {
                    mMediaRecorder.prepare();
                } catch (IOException e) {
                    Log.e(TAG, "MediaRecorder prepare failed", e);
                    Toast.makeText(this, "录制准备失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    mMediaRecorder.release();
                    mMediaRecorder = null;
                    return;
                }

                SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
                if (surfaceTexture == null) {
                    Log.e(TAG, "Camera preview not ready");
                    Toast.makeText(this, "相机预览未就绪", Toast.LENGTH_SHORT).show();
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

                                        new Handler().postDelayed(() -> {
                                            if (isRecording) {
                                                stopRecording();
                                            }
                                        }, VIDEO_DURATION);

                                    } catch (CameraAccessException e) {
                                        Log.e(TAG, "Failed to start camera preview", e);
                                        Toast.makeText(CameraActivity.this,
                                                "开启预览失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                        releaseMediaRecorder();
                                    }
                                }

                                @Override
                                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                    Toast.makeText(CameraActivity.this,
                                            "配置失败", Toast.LENGTH_SHORT).show();
                                    releaseMediaRecorder();
                                }
                            }, null);
                } catch (CameraAccessException e) {
                    Log.e(TAG, "Failed to start recording", e);
                    Toast.makeText(this, "录制设置失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    releaseMediaRecorder();
                }
            } catch (Exception e) {
                Log.e(TAG, "Recording failed", e);
                Toast.makeText(this, "录制失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                releaseMediaRecorder();
            }
        }
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
            startAnalyze();
        }
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

        AnalysisResult(String spo2, String age, String gender, String bloodPressure, String respiratory) {
            this.spo2 = spo2;
            this.age = age;
            this.gender = gender;
            this.bloodPressure = bloodPressure;
            this.respiratory = respiratory;
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
                    "18次/分钟"
            );
        } catch (InterruptedException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void startAnalyze() {
        runOnUiThread(() -> {
            showProgressDialog();
        });

        // 在后台线程中调用 RKNN 模块进行视频分析
        new Thread(() -> {
            // 模拟调用 RKNN 模块的接口，获取分析结果
            // TODO: 实际项目中，这里需要替换为真实的 RKNN 接口调用
            AnalysisResult result = analyzeVideoWithRKNN(mVideoFilePath);

            // 在 UI 线程更新结果
            runOnUiThread(() -> {
                if (result != null) {
                    updateHealthData(
                            result.spo2,
                            result.age,
                            result.gender,
                            result.bloodPressure,
                            result.respiratory
                    );
                } else {
                    // 分析失败的处理
                    updateHealthData(
                            "分析失败",
                            "分析失败",
                            "分析失败",
                            "分析失败",
                            "分析失败"
                    );
                }
                dismissProgressDialog();
            });
        }).start();
    }
}