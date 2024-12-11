import android.graphics.Bitmap
import android.os.Bundle
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
import com.cc17.signme.ImageUtils
import com.cc17.signme.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment(private val onImageProxyReceived: (ImageProxy) -> Unit) : Fragment() {

    private lateinit var previewView: PreviewView
    private lateinit var tfliteModelHelper: TFLiteModelHelper
    private lateinit var resultTextView: TextView
    private lateinit var cameraExecutor: ExecutorService

    // New buttons for flash and camera switch
    private lateinit var flashButton: ImageButton
    private lateinit var switchCameraButton: ImageButton

    // Camera-related variables
    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var isFlashEnabled = false
    private lateinit var camera: Camera
    private lateinit var cameraProvider: ProcessCameraProvider

    fun onImageReceived(imageProxy: ImageProxy) {
        onImageProxyReceived(imageProxy)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_camera, container, false)
        previewView = view.findViewById(R.id.preview_view)
        resultTextView = view.findViewById(R.id.result_text)

        // Find new buttons
        flashButton = view.findViewById(R.id.flash_button)
        switchCameraButton = view.findViewById(R.id.switch_camera_button)

        tfliteModelHelper = TFLiteModelHelper(requireContext(), "model.tflite")

        setupCameraControls()
        startCamera()

        cameraExecutor = Executors.newSingleThreadExecutor()

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
        // Switch between front and back cameras
        currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // Restart the camera with the new selector
        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()

                camera = cameraProvider.bindToLifecycle(
                    this,
                    currentCameraSelector,
                    preview,
                    imageAnalyzer
                )

                // Reset flash state when switching cameras
                isFlashEnabled = false
                flashButton.setImageResource(R.drawable.ic_flash_off)
            } catch (e: Exception) {
                Log.e("CameraFragment", "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun processImage(imageProxy: ImageProxy) {
        val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
        bitmap?.let {
            try {
                val result = tfliteModelHelper.detectObjects(it)
                requireActivity().runOnUiThread {
                    resultTextView.text = result
                }
            } catch (e: Exception) {
                Log.e("CameraFragment", "Error in object detection", e)
                requireActivity().runOnUiThread {
                    resultTextView.text = "Error in Detection"
                }
            }
        }
        imageProxy.close()
    }


    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        tfliteModelHelper.close()
    }
}