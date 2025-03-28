package com.capstone.signme

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment : Fragment(), TFLiteModelHelper.DetectorListener {

    private lateinit var switchModelButton: Button
    private var isModelA = true // Tracks the active model

    private lateinit var previewView: PreviewView
    private lateinit var detector: TFLiteModelHelper
    private lateinit var overlayView: OverlayView
    private lateinit var cameraExecutor: ExecutorService

    private val wordBuffer = StringBuilder() // Stores letters to form words
    private var lastUpdateTime = 0L // Tracks last detected letter time

    private var lastDetectedLabel: String? = null

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private lateinit var camera: Camera
    private var cameraProvider: ProcessCameraProvider? = null

    private val CAMERA_PERMISSION_CODE = 100
    private lateinit var flashButton: ImageButton
    private lateinit var switchCameraButton: ImageButton
    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var isFlashEnabled = false
    private lateinit var resultTextView: TextView

    private val handler = Handler(Looper.getMainLooper())


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_camera, container, false)

        previewView = view.findViewById(R.id.preview_view)
        overlayView = view.findViewById(R.id.overlay)
        resultTextView = view.findViewById(R.id.result_text)
        switchModelButton = view.findViewById(R.id.switch_model_button)

        // Initialize detector with the fragment as the DetectorListener

        initializeDetector("modelFSL.tflite", "labelsFSL.txt")

        switchModelButton.setOnClickListener {
            switchModel()
        }


        flashButton = view.findViewById(R.id.flash_button)
        switchCameraButton = view.findViewById(R.id.switch_camera_button)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Request camera permission
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            setupCameraControls()
            startCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
            startCamera()
        }

        return view
    }

    private fun initializeDetector(modelName: String, labelsName: String) {
        detector = TFLiteModelHelper(
            context = requireContext(),
            modelName = modelName,
            labelsName = labelsName,
            detectorListener = this

        )
        switchModelButton.text = if (isModelA) "Switch to ASL" else "Switch to FSL"
    }

    private fun switchModel() {
        isModelA = !isModelA // Toggle model
        val newModel = if (isModelA) "modelFSL.tflite" else "modelASL.tflite"
        val newLabels = if (isModelA) "labelsFSL.txt" else "labelsASL.txt"

        initializeDetector(newModel, newLabels) // Reload model and labels

        Toast.makeText(requireContext(), "Changing Language", Toast.LENGTH_SHORT).show()
        Log.d("ModelSwitch", "Switched to $newModel with labels: $newLabels")
    }

    private fun setupCameraControls() {
        // Flash button toggle
        flashButton.setOnClickListener {
            toggleFlash()
        }

        // Camera switch button
        switchCameraButton.setOnClickListener {
            switchCamera()
        }
    }
    private fun toggleFlash() {
        if (::camera.isInitialized) {
            isFlashEnabled = !isFlashEnabled
            camera.cameraInfo.hasFlashUnit().let { hasFlash ->
                if (hasFlash) {
                    camera.cameraControl.enableTorch(isFlashEnabled)
                    // Update button appearance or icon based on flash state
                    flashButton.setImageResource(
                        if (isFlashEnabled) R.drawable.ic_flash_on
                        else R.drawable.ic_flash_off
                    )
                } else {
                    Toast.makeText(requireContext(), "Flash not available", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun switchCamera() {
        currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        startCamera()
    }


    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector = currentCameraSelector

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                    postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            detector.detect(rotatedBitmap)
        }

        try {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                viewLifecycleOwner, // Use viewLifecycleOwner to manage lifecycle correctly
                cameraSelector,
                preview,
                imageAnalyzer
            )
            preview?.setSurfaceProvider(previewView.surfaceProvider)
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed", e)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        detector.close()
    }

    // DetectorListener implementation
    override fun onEmptyDetect() {
        overlayView.invalidate()
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        activity?.runOnUiThread {
            overlayView.apply {
                setResults(boundingBoxes)
                invalidate()
            }

            if (boundingBoxes.isNotEmpty()) {
                val detectedLabel = boundingBoxes.first().clsName // Get the first detected label
                val currentTime = System.currentTimeMillis()

                // Prevent repeated detections of the same label
                if (detectedLabel == lastDetectedLabel) {
                    return@runOnUiThread
                }

                lastDetectedLabel = detectedLabel // Store last detected label

                if (detectedLabel.length == 1) { // If it's a single letter (A-Z)
                    if (currentTime - lastUpdateTime > 2000) {
                        wordBuffer.append(" ") // Add space after 2s of inactivity
                    }
                    wordBuffer.append(detectedLabel) // Add detected letter
                } else { // If it's a word/phrase (e.g., "Good morning")
                    wordBuffer.append(" ") // Add a space instead of appending the phrase
                    wordBuffer.append(detectedLabel)
                    wordBuffer.append(" ")
                }

                lastUpdateTime = currentTime

                // **Update text view immediately**
                resultTextView.text = wordBuffer.toString().trim()

                // Remove the full word after 6 seconds of inactivity
                handler.removeCallbacks(clearTextRunnable)
                handler.postDelayed(clearTextRunnable, 6000)
            }
        }
    }

    // Runnable to clear the word after 6 seconds
    private val clearTextRunnable = Runnable {
        activity?.runOnUiThread {
            wordBuffer.clear() // Clear entire buffer
            resultTextView.text = "" // Clear UI
            Log.d("WordFormation", "Full word cleared after 6 seconds")
        }
    }






    companion object {
        private const val TAG = "CameraFragment"
    }

}