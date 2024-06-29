package com.example.hearsee

import ApiService
import ImageRequest
import ImageResponse
import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.SystemClock.sleep
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import com.example.hearsee.databinding.ActivityMainBinding
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.io.encoding.ExperimentalEncodingApi

typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity(), GestureDetector.OnDoubleTapListener  {
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var gestureDetector: GestureDetectorCompat

    private var imageCapture: ImageCapture? = null

    private val captureTaken = R.raw.capture_taken
    private val end = R.raw.end

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions())
        { permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(baseContext,
                    "Permission request denied",
                    Toast.LENGTH_SHORT).show()
            } else {
                startCamera()
            }
        }

    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        // Set up gesture detector
        gestureDetector = GestureDetectorCompat(this, GestureListener())
        gestureDetector.setOnDoubleTapListener(this)

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun takePhoto() {
        playSound(captureTaken)
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time stamped name and MediaStore entry.
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
            .build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = output.savedUri
                    uri?.let {
                        val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                        val byteArrayOutputStream = java.io.ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)
                        val byteArray = byteArrayOutputStream.toByteArray()
                        val imageString =  Base64.encodeToString(byteArray, Base64.NO_WRAP)

                        // Send HTTP POST request with the image
                        sendImage(imageString)
                    }
                }
            }
        )
    }

    private fun sendImage(imageData: String) {
        val apiService = Retrofit.Builder()
            .baseUrl("https://server-upnmifaofa-ew.a.run.app/") // Replace <IP> with your server's IP address
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)

        val requestBody = ImageRequest(imageData)
        apiService.uploadImage(requestBody).enqueue(object : Callback<ImageResponse> {
            override fun onResponse(call: Call<ImageResponse>, response: Response<ImageResponse>) {
                if (response.isSuccessful) {
                    response.body()?.let {
                        val base64Audio = it.audio
                        val audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)
                        playMp3(audioBytes)
                    }
                } else {
                    Log.e(TAG, "Failed to get response: ${response.errorBody()?.string()}")
                }
            }

            override fun onFailure(call: Call<ImageResponse>, t: Throwable) {
                Log.e(TAG, "HTTP request failed: ${t.message}", t)
            }
        })
    }

    private fun playMp3(audioBytes: ByteArray) {
        try {
            val tempFile = File.createTempFile("audio", "mp3", cacheDir)
            tempFile.deleteOnExit()
            val fos = FileOutputStream(tempFile)
            fos.write(audioBytes)
            fos.close()
            val mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                start()
            }
            while(mediaPlayer.isPlaying){
                sleep(100)
            }
            sleep(200)
            playSound(end)
        } catch (e: IOException) {
            Log.e(TAG, "Error playing MP3: ${e.message}", e)
        }
    }

    private fun playSound(resourceId: Int){
        val afd: AssetFileDescriptor = resources.openRawResourceFd(resourceId)
        val mediaPlayer = MediaPlayer().apply {
            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            prepare()
            start()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .build()

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }


    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event?.let {
            gestureDetector.onTouchEvent(it)
        }
        return super.onTouchEvent(event)
    }

    companion object {
        private const val TAG = "HearSee"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            takePhoto()
            return true
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        return false
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        takePhoto()
        return true
    }

    override fun onDoubleTapEvent(e: MotionEvent): Boolean {
        return false
    }
}