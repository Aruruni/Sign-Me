import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.LinkedList

class TFLiteModelHelper(context: Context, modelName: String) {
    private var interpreter: Interpreter

    private val labels: List<String>

    init {
        val modelBuffer = loadModelFile(context, modelName)
        interpreter = Interpreter(modelBuffer)

        // Load labels from the assets folder
        labels = loadLabels(context, "labels.txt")
    }

    // Load labels from the assets folder
    private fun loadLabels(context: Context, fileName: String): List<String> {
        return context.assets.open(fileName).bufferedReader().useLines { it.toList() }
    }

    // Method to detect objects from a Bitmap
    private val recentDetections = LinkedList<Pair<String, Float>>()

    fun detectObjects(bitmap: Bitmap): String {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
        val byteBuffer = convertBitmapToByteBuffer(resizedBitmap)

        val output = Array(1) { FloatArray(labels.size) }
        interpreter.run(byteBuffer, output)

        val maxIndex = output[0].indices.maxByOrNull { output[0][it] } ?: -1
        val confidence = output[0][maxIndex]

        // Maintain a rolling average for smoothness
        if (recentDetections.size > 10) recentDetections.poll()
        recentDetections.add(Pair(labels[maxIndex], confidence))
        val averagedConfidence = recentDetections.map { it.second }.average()

        val detectedLabel = if (maxIndex != -1 && averagedConfidence > 0.87) {
            labels[maxIndex]
        } else {
            "(Adjust Camera)"
        }

        return "$detectedLabel"
    }





    // Helper method to convert Bitmap to ByteBuffer
    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * 224 * 224 * 3) // For RGB 224x224
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(224 * 224)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in intValues.indices) {
            val value = intValues[i]
            byteBuffer.putFloat(((value shr 16) and 0xFF) / 255.0f)  // Red
            byteBuffer.putFloat(((value shr 8) and 0xFF) / 255.0f)   // Green
            byteBuffer.putFloat((value and 0xFF) / 255.0f)           // Blue
        }
        return byteBuffer
    }
    fun close() {
        interpreter.close()

    }



    // Load the model file from assets
    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(modelName)
        val fileInputStream = assetFileDescriptor.createInputStream()
        val fileChannel = fileInputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
}
