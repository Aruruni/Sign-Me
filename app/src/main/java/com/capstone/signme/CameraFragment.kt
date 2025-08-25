package com.capstone.signme

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.appcompat.widget.PopupMenu
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment : Fragment(), TFLiteModelHelper.DetectorListener {

    private var currentModelIndex = 0

    // List of models and labels
    private val models = listOf(
        Pair("alphabets.tflite", "alphabetslabel.txt"),
        Pair("filipino.tflite", "filipinolabel.txt"),
        Pair("english.tflite", "englishlabel.txt")
    )

    private val modelNames = listOf("Alphabets", "Filipino", "English")

    private lateinit var previewView: PreviewView
    private lateinit var detector: TFLiteModelHelper
    private lateinit var overlayView: OverlayView
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var flashButton: ImageButton
    private lateinit var switchCameraButton: ImageButton
    private lateinit var listPopupButton: Button
    private lateinit var resultTextView: TextView

    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var isFlashEnabled = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private lateinit var camera: Camera
    private var cameraProvider: ProcessCameraProvider? = null

    private val handler = Handler(Looper.getMainLooper())

    private val wordBuffer = StringBuilder()
    private var lastUpdateTime = 0L
    private var lastDetectedLabel: String? = null
    private var isSingleLabelMode = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_camera, container, false)

        previewView = view.findViewById(R.id.preview_view)
        overlayView = view.findViewById(R.id.overlay)
        resultTextView = view.findViewById(R.id.result_text)
        flashButton = view.findViewById(R.id.flash_button)
        switchCameraButton = view.findViewById(R.id.switch_camera_button)
        listPopupButton = view.findViewById(R.id.list_popup_button)

        // 🔹 Set initial button text to current model name
        listPopupButton.text = modelNames[currentModelIndex]

        // Initialize with default model
        initializeDetector(models[currentModelIndex].first, models[currentModelIndex].second)

        // Setup popup menu for model switching
        listPopupButton.setOnClickListener { showModelPopupMenu(it) }

        // Setup camera controls
        setupCameraControls()
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        }

        return view
    }

    private fun showModelPopupMenu(anchor: View) {
        val popupMenu = PopupMenu(requireContext(), anchor)
        modelNames.forEachIndexed { index, name ->
            popupMenu.menu.add(Menu.NONE, index, index, name)
        }

        popupMenu.setOnMenuItemClickListener { item ->
            val position = item.itemId
            if (position != currentModelIndex) {
                currentModelIndex = position
                val (newModel, newLabels) = models[currentModelIndex]
                initializeDetector(newModel, newLabels)

                // 🔹 Update button text to reflect mode
                listPopupButton.text = modelNames[currentModelIndex]

                Toast.makeText(requireContext(), "Switched to ${modelNames[currentModelIndex]}", Toast.LENGTH_SHORT).show()
                Log.d("ModelSwitch", "Switched to $newModel with labels: $newLabels")
            }
            true
        }
        popupMenu.show()
    }

    private fun initializeDetector(modelName: String, labelsName: String) {
        detector = TFLiteModelHelper(
            context = requireContext(),
            modelName = modelName,
            labelsName = labelsName,
            detectorListener = this
        )
    }

    private fun setupCameraControls() {
        flashButton.setOnClickListener { toggleFlash() }
        switchCameraButton.setOnClickListener { switchCamera() }
    }

    private fun toggleFlash() {
        if (::camera.isInitialized) {
            isFlashEnabled = !isFlashEnabled
            if (camera.cameraInfo.hasFlashUnit()) {
                camera.cameraControl.enableTorch(isFlashEnabled)
                flashButton.setImageResource(
                    if (isFlashEnabled) R.drawable.ic_flash_on else R.drawable.ic_flash_off
                )
            } else {
                Toast.makeText(requireContext(), "Flash not available", Toast.LENGTH_SHORT).show()
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
                viewLifecycleOwner,
                currentCameraSelector,
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

    override fun onEmptyDetect() {
        overlayView.invalidate()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        resultTextView.setOnClickListener {
            isSingleLabelMode = !isSingleLabelMode
            val modeText = if (isSingleLabelMode) "Single Label Mode" else "Continuous Text Mode"
            Toast.makeText(requireContext(), "Switched to $modeText", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        activity?.runOnUiThread {
            overlayView.apply {
                setResults(boundingBoxes)
                invalidate()
            }

            if (boundingBoxes.isNotEmpty()) {
                val detectedLabel = boundingBoxes.first().clsName
                val currentTime = System.currentTimeMillis()

                if (detectedLabel == lastDetectedLabel) return@runOnUiThread
                lastDetectedLabel = detectedLabel

                if (isSingleLabelMode) {
                    resultTextView.text = detectedLabel
                } else {
                    if (detectedLabel.length == 1) {
                        if (currentTime - lastUpdateTime > 2000) wordBuffer.append(" ")
                        wordBuffer.append(detectedLabel)
                    } else {
                        wordBuffer.append(" $detectedLabel ")
                    }

                    lastUpdateTime = currentTime

                    val wordText = wordBuffer.toString().trim()
                    val maxLines = 2
                    val maxCharactersPerLine = 20
                    val lines = wordText.chunked(maxCharactersPerLine)
                    val displayedLines = if (lines.size > maxLines) lines.takeLast(maxLines) else lines

                    resultTextView.text = displayedLines.joinToString("\n")

                    handler.removeCallbacks(clearTextRunnable)
                    handler.postDelayed(clearTextRunnable, 6000)
                }
            }
        }
    }

    private val clearTextRunnable = Runnable {
        activity?.runOnUiThread {
            wordBuffer.clear()
            resultTextView.text = ""
            Log.d("WordFormation", "Full word cleared after 6 seconds")
        }
    }

    companion object {
        private const val TAG = "CameraFragment"
        private const val CAMERA_PERMISSION_CODE = 100
    }
}
