package com.serenegiant.audiovideosample

import android.content.Context
import android.hardware.Camera
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.WindowManager

import java.io.IOException
import java.lang.ref.WeakReference

/**
 * Thread for asynchronous operation of camera preview
 */
class CameraThread(cameraView: CameraGLView) : Thread("Camera thread") {

    companion object {
        private val TAG = "CameraThread"
        private val CAMERA_ID = 0
    }

    private val readyFence = java.lang.Object()
    private val cameraViewRef: WeakReference<CameraGLView> = WeakReference(cameraView)

    @JvmField
    @Volatile
    var isRunning = false
    private var camera: Camera? = null
    private var isFrontFace: Boolean = false

    var handler: CameraHandler? = null
        get() {
            synchronized(readyFence) {
                try {
                    readyFence.wait()
                } catch (e: InterruptedException) {
                }
            }
            return field
        }

    /**
     * message loop
     * prepare Looper and create Handler for this thread
     */
    override fun run() {
        Log.d(TAG, "Camera thread start")
        Looper.prepare()
        synchronized(readyFence) {
            handler = CameraHandler(this)
            isRunning = true
            readyFence.notify()
        }
        Looper.loop()
        Log.d(TAG, "Camera thread finish")
        synchronized(readyFence) {
            handler = null
            isRunning = false
        }
    }

    /**
     * start camera preview
     *
     * @param width
     * @param height
     */
    fun startPreview(width: Int, height: Int) {
        Log.v(TAG, "startPreview:")
        val parent = cameraViewRef.get()
        if (parent != null && camera == null) {
            // This is a sample project so just use 0 as camera ID.
            // it is better to selecting camera is available
            try {
                camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT)
                val params = camera!!.parameters

                val focusModes = params.supportedFocusModes
                if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                    params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    params.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
                } else {
                    Log.i(TAG, "Camera does not support autofocus")
                }
                // let's try fastest frame rate. You will get near 60fps, but your device become hot.
                val supportedFpsRange = params.supportedPreviewFpsRange
                //					final int n = supportedFpsRange != null ? supportedFpsRange.size() : 0;
                //					int[] range;
                //					for (int i = 0; i < n; i++) {
                //						range = supportedFpsRange.get(i);
                //						Log.i(TAG, String.format("supportedFpsRange(%d)=(%d,%d)", i, range[0], range[1]));
                //					}
                val max_fps = supportedFpsRange[supportedFpsRange.size - 1]
                Log.i(TAG, String.format("fps:%d-%d", max_fps[0], max_fps[1]))
                params.setPreviewFpsRange(max_fps[0], max_fps[1])
                params.setRecordingHint(true)
                // request closest supported preview size
                val closestSize = CameraUtils.getClosestSupportedSize(
                    params.supportedPreviewSizes, width, height)
                params.setPreviewSize(closestSize.width, closestSize.height)
                // request closest picture size for an aspect ratio issue on Nexus7
                val pictureSize = CameraUtils.getClosestSupportedSize(
                    params.supportedPictureSizes, width, height)
                params.setPictureSize(pictureSize.width, pictureSize.height)
                // rotate camera preview according to the device orientation
                setRotation(params)
                camera!!.parameters = params
                // get the actual preview size
                val previewSize = camera!!.parameters.previewSize
                Log.i(TAG, String.format("previewSize(%d, %d)", previewSize.width, previewSize.height))
                // adjust view size with keeping the aspect ration of camera preview.
                // here is not a UI thread and we should request parent view to execute.
                parent.post { parent.setVideoSize(previewSize.width, previewSize.height) }
                val st = parent.surfaceTexture
                st!!.setDefaultBufferSize(previewSize.width, previewSize.height)
                camera!!.setPreviewTexture(st)
            } catch (e: IOException) {
                Log.e(TAG, "startPreview:", e)
                if (camera != null) {
                    camera!!.release()
                    camera = null
                }
            } catch (e: RuntimeException) {
                Log.e(TAG, "startPreview:", e)
                if (camera != null) {
                    camera!!.release()
                    camera = null
                }
            }

            if (camera != null) {
                // start camera preview display
                camera!!.startPreview()
            }
        }
    }

    /**
     * stop camera preview
     */
    fun stopPreview() {
        Log.v(TAG, "stopPreview:")
        if (camera != null) {
            camera!!.stopPreview()
            camera!!.release()
            camera = null
        }
        val parent = cameraViewRef.get() ?: return
        parent.mCameraHandler = null
    }

    /**
     * rotate preview screen according to the device orientation
     *
     * @param params
     */
    private fun setRotation(params: Camera.Parameters) {
        Log.v(TAG, "setRotation:")
        val parent = cameraViewRef.get() ?: return

        val display = (parent.context
            .getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        val rotation = display.rotation
        var degrees = 0
        when (rotation) {
            Surface.ROTATION_0 -> degrees = 0
            Surface.ROTATION_90 -> degrees = 90
            Surface.ROTATION_180 -> degrees = 180
            Surface.ROTATION_270 -> degrees = 270
        }
        // get whether the camera is front camera or back camera
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(CAMERA_ID, info)

        isFrontFace = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
        if (isFrontFace) {    // front camera
            Log.d(TAG, "Back Camera")
            degrees = (info.orientation + degrees) % 360
            degrees = (360 - degrees) % 360  // reverse
        } else {  // back camera
            Log.d(TAG, "Front Camera")
            degrees = (info.orientation + degrees + 180) % 360
        }
        // apply rotation setting
        camera!!.setDisplayOrientation(degrees)
        parent.setRotation(degrees)
        // XXX This method fails to call and camera stops working on some devices.
        //			params.setRotation(degrees);
    }
}
