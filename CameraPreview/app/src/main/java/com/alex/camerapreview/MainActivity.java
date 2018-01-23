package com.alex.camerapreview;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.alex.camerapreview.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    TextureView.SurfaceTextureListener surfaceTextureListener;
    CameraManager cameraManager;
    int cameraFacing;
    Size previewSize;
    String cameraId;
    final int CAMERA_REQUEST_CODE = 12;
    Handler backgroundHandler;
    CameraDevice cameraDevice;
    CameraDevice.StateCallback stateCallback;
    HandlerThread backgroundThread;
    TextureView textureView;
    android.hardware.camera2.CaptureRequest.Builder captureRequestBuilder;
    CaptureRequest captureRequest;
    CameraCaptureSession cameraCaptureSession;
    ImageView imgFlashLight, imgChangeCamera;
    OrientationEventListener mOrientationListener;
    RelativeLayout frameButton;
    File galleryFolder;
    public enum Rotation {
        PORTRAIT, LANDSCAPE, REVERSE_LANDSCAPE
    }
    Rotation currentRotation = Rotation.PORTRAIT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        //ask for permission for camera and storage in order to save pics
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE}, CAMERA_REQUEST_CODE);

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        //start with the back camera
        cameraFacing = CameraCharacteristics.LENS_FACING_BACK;
        textureView = (TextureView)findViewById(R.id.textureView);
        imgFlashLight = (ImageView)findViewById(R.id.imgFlashlight);
        imgChangeCamera = (ImageView)findViewById(R.id.imgChangeCamera);
        frameButton = (RelativeLayout)findViewById(R.id.frameLayout);

        //orientation listener used to rotate the icons when device is in landscape mode
        mOrientationListener = new OrientationEventListener(this,
                SensorManager.SENSOR_DELAY_NORMAL) {

            @Override
            public void onOrientationChanged(int orientation) {
                //maybe on a flat surface, nothing to do
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN)
                    return;

                if (orientation < 45 || orientation > 315) {
                    //0 degrees
                    if(currentRotation != Rotation.PORTRAIT){
                        currentRotation = Rotation.PORTRAIT;
                        imgFlashLight.animate().rotation(0).start();
                        imgChangeCamera.animate().rotation(0).start();
                    }

                }
                else if (orientation < 135) {
                    //90 degrees
                    if(currentRotation != Rotation.LANDSCAPE){
                        currentRotation = Rotation.LANDSCAPE;
                        imgFlashLight.animate().rotation(-90).start();
                        imgChangeCamera.animate().rotation(-90).start();
                    }
                }
                else if (orientation < 225) {
                    //180 degrees

                }
                else
                {
                    if(currentRotation != Rotation.REVERSE_LANDSCAPE){
                        currentRotation = Rotation.REVERSE_LANDSCAPE;
                        imgFlashLight.animate().rotation(90).start();
                        imgChangeCamera.animate().rotation(90).start();
                    }
                }



            }
        };
        if (mOrientationListener.canDetectOrientation() == true) {
            mOrientationListener.enable();
        } else {
            mOrientationListener.disable();
        }


        frameButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ///stop shooting while taking the pic
                lock();
                FileOutputStream outputPhoto = null;
                try {
                    //create the folder for the image if it's not already existing
                    createImageGallery();
                    outputPhoto = new FileOutputStream(createImageFile(galleryFolder));
                    textureView.getBitmap()
                            .compress(Bitmap.CompressFormat.PNG, 100, outputPhoto);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    //start shooting again
                    unlock();
                    try {
                        if (outputPhoto != null) {
                            outputPhoto.close();

                            Toast.makeText(MainActivity.this, "Image taken", Toast.LENGTH_LONG).show();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });

        imgChangeCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                closeCamera();
                if(cameraFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraFacing = CameraCharacteristics.LENS_FACING_FRONT;
                    imgChangeCamera.setImageResource(R.drawable.ic_camera_rear_white_24dp);
                }
                else {
                    cameraFacing = CameraCharacteristics.LENS_FACING_BACK;
                    imgChangeCamera.setImageResource(R.drawable.ic_camera_front_white_24dp);
                }
                setUpCamera();
                openCamera();
            }
        });

        surfaceTextureListener = new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                setUpCamera();
                openCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {

            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

            }
        };

        stateCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice cameraDevice) {
                MainActivity.this.cameraDevice = cameraDevice;
                createPreviewSession();
            }

            @Override
            public void onDisconnected(CameraDevice cameraDevice) {
                cameraDevice.close();
                MainActivity.this.cameraDevice = null;
            }

            @Override
            public void onError(CameraDevice cameraDevice, int error) {
                cameraDevice.close();
                MainActivity.this.cameraDevice = null;
            }
        };


    }


    private void lock() {
        try {
            //submit a request for an image to be captured by the camera device
            cameraCaptureSession.capture(captureRequestBuilder.build(),
                    null, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void unlock() {
        try {
            cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(),
                    null, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }




    private void createPreviewSession() {
        try {
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(surfaceTexture);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(previewSurface);

            cameraDevice.createCaptureSession(Collections.singletonList(previewSurface),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                            if (cameraDevice == null)  return;


                            try {
                                captureRequest = captureRequestBuilder.build();
                                MainActivity.this.cameraCaptureSession = cameraCaptureSession;
                                MainActivity.this.cameraCaptureSession.setRepeatingRequest(captureRequest,
                                        null, backgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {

                        }
                    }, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        //open the background thread that handles the camera
        openBackgroundThread();
        //if the textureview is already available just set it up
        if (textureView.isAvailable()) {
            setUpCamera();
            openCamera();
        } else {
            //attach the listener to the textureview
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        closeCamera();
        closeBackgroundThread();
    }

    private void closeCamera() {
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }

        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    private void closeBackgroundThread() {
        if (backgroundHandler != null) {
            backgroundThread.quitSafely();
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    private void setUpCamera() {
        try {
            //look for every camera available in the device and take the one with
            //lens facing characteristics
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics cameraCharacteristics =
                        cameraManager.getCameraCharacteristics(cameraId);
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) ==
                        cameraFacing) {
                    StreamConfigurationMap streamConfigurationMap = cameraCharacteristics.get(
                            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    //take the highest resolution preview available
                    previewSize = streamConfigurationMap.getOutputSizes(SurfaceTexture.class)[0];
                    this.cameraId = cameraId;
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void createImageGallery() {
        File storageDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        galleryFolder = new File(storageDirectory, getResources().getString(R.string.app_name));
        if (!galleryFolder.exists()) {
            boolean wasCreated = galleryFolder.mkdirs();
            if (!wasCreated) {
                //manage the error

                Toast.makeText(this, "Error while creating folder", Toast.LENGTH_LONG).show();
            }
        }
    }

    private File createImageFile(File galleryFolder) throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String imageFileName = "image_" + timeStamp + "_";
        return File.createTempFile(imageFileName, ".jpg", galleryFolder);
    }


    private void openCamera() {
        try {
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                //stateCallBack tells when the camera is opened/closed
                //all this work is done in a background thread in order not to block the UI
                cameraManager.openCamera(cameraId, stateCallback, backgroundHandler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mOrientationListener.disable();
    }



    private void turnFlashLightOn() {

    }

    private void openBackgroundThread() {
        backgroundThread = new HandlerThread("camera_background_thread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }
}
