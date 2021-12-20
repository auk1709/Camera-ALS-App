package com.example.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.icu.text.SimpleDateFormat
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
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
    private lateinit var videoCapture: VideoCapture<Recorder>
    private var activeRecording: ActiveRecording? = null
    private lateinit var recordingState: VideoRecordEvent

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    private var imageWidth = 480
    private var imageHeight = 480

    private var exposureTime: Long = 20400000
    private var frameDuration: Long = 16666666
    private var sensitivity = 100
    private var focusDistance = 0F
    private var minFocusDistance = 0F

    private var timerTime = 5000L
    private var intervalTime = 5000L

    private lateinit var sensorManager: SensorManager
    private var als: Sensor? = null

    private lateinit var luxValueList: MutableMap<Long, Int>
    private var isRecordingLux = false

    private var useTimer = false
    private lateinit var timer: CountDownTimer
    private var setInterval = false
    private var intervalTimer: Timer = Timer()
    private val handler = Handler()

    private lateinit var cameraControl: CameraControl

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
        timerTime = sharedPreferences.getString("timer_time", "5000")?.toLongOrNull() ?: 5000L
        intervalTime = sharedPreferences.getString("interval_time", "5000")?.toLongOrNull()
            ?.times(1000)
            ?: 5000L

        if (allPermissionGranted()) {
            startCamera()
        } else {
            requestPermission.launch(REQUIRED_PERMISSIONS)
        }

        binding.focusText.text =
            getString(R.string.focus_text, String.format("%05.3f", focusDistance))
        binding.cameraCaptureButton.setOnClickListener { takePhoto() }
        binding.videoCaptureButton.setOnClickListener { takeVideo() }

        binding.recordLuxButton.setOnClickListener { switchLuxRecord() }

        binding.timerToggleButton.setOnClickListener { toggleTimer() }
        binding.timerTimeText.text = (timerTime / 1000).toString()
        if (useTimer) {
            binding.timerToggleButton.setImageResource(R.drawable.ic_baseline_timer_24)
            binding.timerTimeText.visibility = View.VISIBLE
        } else {
            binding.timerToggleButton.setImageResource(R.drawable.ic_baseline_timer_off_24)
            binding.timerTimeText.visibility = View.GONE
        }


        outputDirectory = getOutputDirectory()

        cameraExecutor = Executors.newSingleThreadExecutor()


        val isPointerVisible = sharedPreferences.getBoolean("pointer", false)
        binding.pointer.visibility = if (isPointerVisible) View.VISIBLE else View.GONE

        binding.focusBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                focusDistance = progress * minFocusDistance / 100

                val cameraCaptureRequestOptions = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                    .build()

                Camera2CameraControl.from(cameraControl)
                    .addCaptureRequestOptions(cameraCaptureRequestOptions)

                binding.focusText.text =
                    getString(R.string.focus_text, String.format("%02.3f", focusDistance))
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        })

        timer = object : CountDownTimer(timerTime, 1000) {
            override fun onFinish() {
            }

            override fun onTick(time: Long) {
            }
        }

        binding.cameraSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (allPermissionGranted()) {
                    startCamera()
                } else {
                    requestPermission.launch(REQUIRED_PERMISSIONS)
                }
            } else {
                pauseCamera()
            }
        }

        binding.intervalSwitch.setOnCheckedChangeListener { _, isChecked ->
            setInterval = isChecked
        }

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
        if (isRecordingLux) luxValueList[System.nanoTime()] = lux
    }

    private fun toggleTimer() {
        useTimer = !useTimer
        if (useTimer) {
            binding.timerToggleButton.setImageResource(R.drawable.ic_baseline_timer_24)
            binding.timerTimeText.visibility = View.VISIBLE
        } else {
            binding.timerToggleButton.setImageResource(R.drawable.ic_baseline_timer_off_24)
            binding.timerTimeText.visibility = View.GONE
        }
        Log.d(TAG, "toggle timer")
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
//                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            }
        )
    }

    private fun takeVideo() {
        val name = SimpleDateFormat(
            FILENAME_FORMAT,
            Locale.JAPAN
        ).format(System.currentTimeMillis()) + ".mp4"
        val videoFile = File(outputDirectory, name)
        val outputOption = FileOutputOptions.Builder(videoFile).build()

        activeRecording = videoCapture.output.prepareRecording(requireContext(), outputOption)
            .withEventListener(ContextCompat.getMainExecutor(requireContext()), captureListener)
            .start()

        if (useTimer) {
            timer = object : CountDownTimer(timerTime, 1000) {
                override fun onFinish() {
                    val recording = activeRecording
                    if (recording != null) {
                        recording.stop()
                        activeRecording = null
                    }
                }

                override fun onTick(time: Long) {
                    binding.timerTimeText.text = (time / 1000).toString()
                }
            }
            timer.start()
        }
    }

    private val captureListener = Consumer<VideoRecordEvent> { event ->
        if (event !is VideoRecordEvent.Status)
            recordingState = event

        when (event) {
            is VideoRecordEvent.Start -> {
                binding.videoCaptureButton.apply {
                    setOnClickListener {
                        val recording = activeRecording
                        if (recording != null) {
                            recording.stop()
                            activeRecording = null
                        }
                    }
                    setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.red))
                }
            }
            is VideoRecordEvent.Finalize -> {
                binding.videoCaptureButton.apply {
                    setOnClickListener { takeVideo() }
                    setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.purple_500))
                }
                timer.cancel()
                binding.timerTimeText.text = (timerTime / 1000).toString()
            }
        }
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

            val qualitySelector = QualitySelector
                .firstTry(QualitySelector.QUALITY_UHD)
//                .firstTry(QualitySelector.QUALITY_FHD)
                .thenTry(QualitySelector.QUALITY_FHD)
                .thenTry(QualitySelector.QUALITY_HD)
                .finallyTry(
                    QualitySelector.QUALITY_SD,
                    QualitySelector.FALLBACK_STRATEGY_LOWER
                )
            val recorder = Recorder.Builder()
                .setExecutor(cameraExecutor).setQualitySelector(qualitySelector)
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, videoCapture
                )
                cameraControl = camera.cameraControl
                val camChars = Camera2CameraInfo.extractCameraCharacteristics(camera.cameraInfo)
                minFocusDistance =
                    camChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)!!
                binding.cameraCaptureButton.isEnabled = true
                binding.videoCaptureButton.isEnabled = true
                binding.cameraOffText.visibility = View.GONE
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun pauseCamera() {
        ProcessCameraProvider.getInstance(requireContext()).get().unbindAll()
        binding.cameraCaptureButton.isEnabled = false
        binding.videoCaptureButton.isEnabled = false
        binding.cameraOffText.visibility = View.VISIBLE
    }

    private fun startLuxRecord() {
        isRecordingLux = true
        luxValueList = mutableMapOf()
        binding.recordLuxButton.setBackgroundColor(
            ContextCompat.getColor(
                requireContext(),
                R.color.red
            )
        )
        if (useTimer) {
            timer = object : CountDownTimer(timerTime, 1000) {
                override fun onFinish() {
                    finishLuxRecord()
                    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    val ringtone = RingtoneManager.getRingtone(context, uri)
                    ringtone.play()
                    handler.postDelayed({
                        ringtone.stop()
                    }, 3000)
                }

                override fun onTick(time: Long) {
                    binding.timerTimeText.text = (time / 1000).toString()
                }
            }
            timer.start()
        }
        if (setInterval) {
            binding.cameraSwitch.isChecked = false
            intervalTimer = Timer()
            intervalTimer.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    handler.post {
                        binding.cameraSwitch.isChecked = true
                        handler.postDelayed(Runnable {
                            takePhoto()
                        }, 1000)
                        handler.postDelayed(Runnable {
                            binding.cameraSwitch.isChecked = false
                        }, 2000)
                    }
                }
            }, intervalTime - 1000, intervalTime)
        }
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
            Toast.makeText(context, "Save Lux Data, ${outputFile.absolutePath}", Toast.LENGTH_SHORT)
                .show()
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to save lux data", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Failed to save lux data", e)
        }
        binding.recordLuxButton.setBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.purple_500)
        )
        timer.cancel()
        intervalTimer.cancel()
        binding.timerTimeText.text = (timerTime / 1000).toString()
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