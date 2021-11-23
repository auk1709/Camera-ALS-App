package com.example.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CaptureRequest
import android.icu.text.SimpleDateFormat
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.example.camera.databinding.FragmentCameraBinding
import java.io.File
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment : Fragment(), SensorEventListener {
    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!
    private var imageCapture: ImageCapture? = null

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    private var imageWidth = 480
    private var imageHeight = 480

    private var exposureTime: Long = 20400000
    private var frameDuration: Long = 16666666
    private var sensitivity = 100

    private lateinit var sensorManager: SensorManager
    private var als: Sensor? = null

    private lateinit var luxValueList: MutableMap<Long, Int>
    private var isRecordingLux = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        super.onCreateView(inflater, container, savedInstanceState)
        setHasOptionsMenu(true)

        sensorManager = requireActivity().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        als = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        val view = binding.root

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

        imageWidth = sharedPreferences.getString("image_width", "1080")?.toIntOrNull() ?: 1080
        imageHeight = sharedPreferences.getString("image_height", "1920")?.toIntOrNull() ?: 1920

        exposureTime = sharedPreferences.getString("exposure_time", "20400000")?.toLongOrNull()
            ?: 20400000
        frameDuration = sharedPreferences.getString("frame_duration", "16666666")?.toLongOrNull()
            ?: 16666666
        sensitivity = sharedPreferences.getString("sensitivity", "100")?.toIntOrNull() ?: 100

        if (allPermissionGranted()) {
            startCamera()
        } else {
            requestPermission.launch(REQUIRED_PERMISSIONS)
        }

        binding.cameraCaptureButton.setOnClickListener { takePhoto() }

        binding.recordLuxButton.setOnClickListener { switchLuxRecord() }

        outputDirectory = getOutputDirectory()

        cameraExecutor = Executors.newSingleThreadExecutor()


        val isPointerVisible = sharedPreferences.getBoolean("pointer", false)
        binding.pointer.visibility = if (isPointerVisible) View.VISIBLE else View.GONE

        return view
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_settings -> {
            val action = CameraFragmentDirections.actionCameraFragmentToSettingsFragment()
            findNavController().navigate(action)
            true
        }
        else -> {
            super.onOptionsItemSelected(item)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {

    }

    override fun onSensorChanged(event: SensorEvent) {
        val lux = event.values[0].toInt()
        binding.alsValueText.text = getString(R.string.lux_value, lux)
        if (isRecordingLux) luxValueList[System.currentTimeMillis()] = lux
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val photoFile = File(
            outputDirectory,
            SimpleDateFormat(
                FILENAME_FORMAT, Locale.US
            ).format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    val msg = "Photo capture succeeded: $savedUri"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            }
        )
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val previewBuilder = Preview.Builder()

            Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                CaptureRequest.CONTROL_MODE,
                CaptureRequest.CONTROL_MODE_OFF
            ).setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_OFF
            ).setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            ).setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_OFF
            ).setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, sensitivity)
                .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime)

            val preview = previewBuilder
                .setTargetResolution(Size(imageWidth, imageHeight))
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            val imageCaptureBuilder = ImageCapture.Builder()
                .setTargetResolution(Size(imageWidth, imageHeight))
                .setCaptureMode(CAPTURE_MODE_MINIMIZE_LATENCY)

            imageCapture = imageCaptureBuilder.build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture,
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun startLuxRecord() {
        isRecordingLux = true
        luxValueList = mutableMapOf()
    }

    private fun finishLuxRecord() {
        isRecordingLux = false
        val outputFile = File(
            getOutputDirectory(),
            SimpleDateFormat(
                FILENAME_FORMAT,
                Locale.JAPAN
            ).format(System.currentTimeMillis()) + ".csv"
        )
        try {
            luxValueList.forEach { (time, lux) ->
                outputFile.absoluteFile.appendText("$time,$lux\n")
            }
//            requireContext().openFileOutput(outputFile.absolutePath, Context.MODE_PRIVATE).use {
//                luxValueList.forEach { (time, lux) ->
//                    it.write("$time,$lux\n".toByteArray())
//                }
//            }
            Toast.makeText(context, "Save Lux Data, ${outputFile.absolutePath}", Toast.LENGTH_SHORT)
                .show()
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to save lux data", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Failed to save lux data", e)
        }
    }

    private fun switchLuxRecord() {
        if (isRecordingLux) finishLuxRecord() else startLuxRecord()
    }

    private fun allPermissionGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            requireContext(), it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = requireActivity().externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else requireContext().filesDir
    }

    override fun onResume() {
        super.onResume()
        als?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }


    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (it[Manifest.permission.CAMERA] == true) {
                startCamera()
            } else {
                Toast.makeText(
                    context,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    companion object {
        private const val TAG = "Camera-ALS"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}