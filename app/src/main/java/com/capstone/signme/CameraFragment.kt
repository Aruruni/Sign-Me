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

class CameraFragment : Fragment(), EnhancedTFLiteModelHelper.DetectorListener {
    private lateinit var previewView: PreviewView
    private lateinit var detector: EnhancedTFLiteModelHelper
    private lateinit var overlayView: OverlayView
    private lateinit var cameraExecutor: ExecutorService

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private lateinit var camera: Camera
    private var cameraProvider: ProcessCameraProvider? = null

    private val CAMERA_PERMISSION_CODE = 100
    private val isFrontCamera = false
    private lateinit var flashButton: ImageButton
    private lateinit var switchCameraButton: ImageButton
    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var isFlashEnabled = false
    private lateinit var resultTextView: TextView

    private val labelQueue: MutableList<String> = mutableListOf()
    private val handler = Handler(Looper.getMainLooper())


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_camera, container, false)

        previewView = view.findViewById(R.id.preview_view)
        overlayView = view.findViewById(R.id.overlay)
        resultTextView = view.findViewById(R.id.result_text)

        // Initialize detector with the fragment as the DetectorListener
        detector = EnhancedTFLiteModelHelper(
            context = requireContext(),
            modelName = "model.tflite",
            labelsName = "labels.txt",
            detectorListener = this
        )
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
        }

        return view
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

        // Optional: Update isFrontCamera for consistency
        val isFrontCamera = (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA)

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

            // Display detected labels in the TextView
            if (boundingBoxes.isNotEmpty()) {
                val detectedLabels = boundingBoxes.joinToString(separator = ", ") { it.clsName }
                resultTextView.text = "$detectedLabels"
            } else {
                resultTextView.text = "No objects detected"
            }
        }
    }
    private fun showLabelsSequentially(newLabels: List<String>) {
        for (label in newLabels) {
            if (labelQueue.size >= 5) {
                labelQueue.removeAt(0) // Remove the oldest label to maintain max 5 labels
            }
            labelQueue.add(label) // Add the new label

            // Update the TextView with the rolling window effect
            updateTextView()

            // Schedule removal of the first label after 2.5s
            handler.postDelayed({
                if (labelQueue.isNotEmpty()) {
                    labelQueue.removeAt(0) // Remove the oldest label
                    updateTextView()
                }
            }, 2500L)
        }
    }

    // Function to update the TextView without any separators
    private fun updateTextView() {
        resultTextView.text = labelQueue.joinToString(separator = " ") // No commas, just spaces
    }


    companion object {
        private const val TAG = "CameraFragment"
    }
}