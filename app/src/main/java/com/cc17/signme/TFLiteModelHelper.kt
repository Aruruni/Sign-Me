import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.LinkedList

class TFLiteModelHelper(context: Context, modelName: String) {
    private var interpreter: Interpreter
    private val labels: List<String>
    private val recentDetections = LinkedList<Pair<String, Float>>()

    init {
        // Load the model file into a ByteBuffer
        val modelBuffer = loadModelFile(context, modelName)
        // Initialize the TensorFlow Lite Interpreter
        interpreter = Interpreter(modelBuffer)
        // Load labels from the labels.txt file in assets
        labels = loadLabels(context, "labels.txt")
    }

    // Load the TensorFlow Lite model file from the assets folder
    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        try {
            val assetFileDescriptor = context.assets.openFd(modelName)
            val fileInputStream = assetFileDescriptor.createInputStream()
            val fileChannel = fileInputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        } catch (e: Exception) {
            throw IllegalArgumentException("Error loading model file: $modelName", e)
        }
    }

    // Load labels from a file in the assets folder
    private fun loadLabels(context: Context, fileName: String): List<String> {
        return context.assets.open(fileName).bufferedReader().useLines { it.toList() }
    }

    // Method to detect objects from a Bitmap
    fun detectObjects(bitmap: Bitmap): String {
        // Resize the input Bitmap to 224x224 as required by the model
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
        // Convert the resized Bitmap to a ByteBuffer
        val byteBuffer = convertBitmapToByteBuffer(resizedBitmap)

        // Prepare the output buffer
        val output = Array(1) { FloatArray(labels.size) }
        // Run inference
        interpreter.run(byteBuffer, output)

        // Get the index of the highest confidence score
        val maxIndex = output[0].indices.maxByOrNull { output[0][it] } ?: -1
        val confidence = if (maxIndex != -1) output[0][maxIndex] else 0.0f

        // Maintain a rolling average for smooth predictions
        if (recentDetections.size > 10) recentDetections.poll()
        recentDetections.add(Pair(labels[maxIndex], confidence))
        val averagedConfidence = recentDetections.map { it.second }.average()

        // Return the detected label or prompt for adjustment
        return if (maxIndex != -1 && averagedConfidence > 0.87) {
            labels[maxIndex]
        } else {
            "(Adjust Camera)"
        }
    }

    // Helper method to convert a Bitmap to ByteBuffer
    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * 224 * 224 * 3) // RGB image
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(224 * 224)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (pixelValue in intValues) {
            // Normalize RGB values to [0, 1] by dividing by 255.0f
            byteBuffer.putFloat(((pixelValue shr 16) and 0xFF) / 255.0f) // Red
            byteBuffer.putFloat(((pixelValue shr 8) and 0xFF) / 255.0f)  // Green
            byteBuffer.putFloat((pixelValue and 0xFF) / 255.0f)          // Blue
        }
        return byteBuffer
    }

    // Close the interpreter to release resources
    fun close() {
        interpreter.close()
    }
}
