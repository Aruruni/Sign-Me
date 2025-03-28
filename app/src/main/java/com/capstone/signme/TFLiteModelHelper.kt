package com.capstone.signme

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class TFLiteModelHelper(
    private val context: Context,
    private val modelName: String,
    private val labelsName: String,
    private val detectorListener: DetectorListener? = null
) {

    private var interpreter: Interpreter? = null
    private var labels = mutableListOf<String>()

    private var tensorWidth = 0
    private var tensorHeight = 0
    private var numClasses = 0
    private var isYolo = false // Flag to determine if using YOLO model or standard classification

    private val imageProcessor = ImageProcessor.Builder()
        .add(NormalizeOp(INPUT_MEAN, INPUT_STANDARD_DEVIATION))
        .add(CastOp(INPUT_IMAGE_TYPE))
        .build()

    init {
        setup()
    }

    fun setup() {
        val model = loadModelFile(context, modelName)
        val options = Interpreter.Options()
        options.numThreads = 4
        options.setUseNNAPI(true)
        interpreter = Interpreter(model, options)
        interpreter = Interpreter(model, options)

        // Get input shape
        val inputShape = interpreter?.getInputTensor(0)?.shape() ?: return
        tensorWidth = inputShape[1]
        tensorHeight = inputShape[2]

        // Determine if using YOLO model based on output shape
        val outputShape = interpreter?.getOutputTensor(0)?.shape() ?: return
        isYolo = outputShape.size >= 3

            val inputStream: InputStream = context.assets.open(labelsName)
            val reader = BufferedReader(InputStreamReader(inputStream))

            var line: String? = reader.readLine()
            while (line != null && line != "") {
                labels.add(line)
                line = reader.readLine()
            }
            reader.close()
            inputStream.close()
            numClasses = labels.size
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        return context.assets.openFd(modelName).let { fileDescriptor ->
            fileDescriptor.createInputStream().channel.map(
                FileChannel.MapMode.READ_ONLY,
                fileDescriptor.startOffset,
                fileDescriptor.declaredLength
            )
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }


    // Enhanced detection method that returns bounding boxes
    fun detect(frame: Bitmap): List<BoundingBox> {
        interpreter ?: return emptyList()
        if (tensorWidth == 0 || tensorHeight == 0) return emptyList()

        val inferenceTime = SystemClock.uptimeMillis()

        // Resize bitmap to match input tensor dimensions
        val resizedBitmap = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, false)

        // Process image
        val tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(resizedBitmap)
        val processedImage = imageProcessor.process(tensorImage)
        val imageBuffer = processedImage.buffer

        if (isYolo) {
            // YOLO model handling
            val outputShape = interpreter?.getOutputTensor(0)?.shape() ?: return emptyList()
            val numChannels = outputShape[1]
            val numElements = outputShape[2]

            val output = TensorBuffer.createFixedSize(intArrayOf(1, numChannels, numElements), OUTPUT_IMAGE_TYPE)
            interpreter?.run(imageBuffer, output.buffer)

            val boxes = processYoloOutput(output.floatArray, numChannels, numElements)
            val finalTime = SystemClock.uptimeMillis() - inferenceTime

            detectorListener?.onDetect(boxes, finalTime)
            return boxes
        } else {
            // Standard classification model handling
            val output = FloatArray(numClasses)
            interpreter?.run(imageBuffer, arrayOf(output))

            // Find best prediction
            var maxIdx = -1
            var maxConf = -1.0f
            for (i in output.indices) {
                if (output[i] > maxConf) {
                    maxConf = output[i]
                    maxIdx = i
                }
            }

            val result = mutableListOf<BoundingBox>()
            if (maxIdx >= 0 && maxConf > CONFIDENCE_THRESHOLD) {
                // Create a single bounding box for the entire image with classification result
                result.add(
                    BoundingBox(
                        x1 = 0.1f, y1 = 0.1f, x2 = 0.9f, y2 = 0.9f,
                        cx = 0.5f, cy = 0.5f, w = 0.8f, h = 0.8f,
                        cnf = maxConf, cls = maxIdx, clsName = labels[maxIdx]
                    )
                )
                detectorListener?.onDetect(result, SystemClock.uptimeMillis() - inferenceTime)
            } else {
                detectorListener?.onEmptyDetect()
            }

            return result
        }
    }

    private fun processYoloOutput(array: FloatArray, numChannels: Int, numElements: Int): List<BoundingBox> {
        val boundingBoxes = mutableListOf<BoundingBox>()

        for (c in 0 until numElements) {
            var maxConf = -1.0f
            var maxIdx = -1
            var j = 4
            var arrayIdx = c + numElements * j

            while (j < numChannels) {
                if (array[arrayIdx] > maxConf) {
                    maxConf = array[arrayIdx]
                    maxIdx = j - 4
                }
                j++
                arrayIdx += numElements
            }

            if (maxConf > CONFIDENCE_THRESHOLD) {
                val clsName = if (maxIdx < labels.size) labels[maxIdx] else "Unknown"
                val cx = array[c] // 0
                val cy = array[c + numElements] // 1
                val w = array[c + numElements * 2]
                val h = array[c + numElements * 3]
                val x1 = cx - (w/2F)
                val y1 = cy - (h/2F)
                val x2 = cx + (w/2F)
                val y2 = cy + (h/2F)

                // Validate bounding box coordinates
                if (x1 < 0F || x1 > 1F) continue
                if (y1 < 0F || y1 > 1F) continue
                if (x2 < 0F || x2 > 1F) continue
                if (y2 < 0F || y2 > 1F) continue

                boundingBoxes.add(
                    BoundingBox(
                        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                        cx = cx, cy = cy, w = w, h = h,
                        cnf = maxConf, cls = maxIdx, clsName = clsName
                    )
                )
            }
        }

        if (boundingBoxes.isEmpty()) return emptyList()

        return applyNMS(boundingBoxes)
    }

    private fun applyNMS(boxes: List<BoundingBox>): MutableList<BoundingBox> {
        val sortedBoxes = boxes.sortedByDescending { it.cnf }.toMutableList()
        val selectedBoxes = mutableListOf<BoundingBox>()

        while(sortedBoxes.isNotEmpty()) {
            val first = sortedBoxes.first()
            selectedBoxes.add(first)
            sortedBoxes.remove(first)

            val iterator = sortedBoxes.iterator()
            while (iterator.hasNext()) {
                val nextBox = iterator.next()
                val iou = calculateIoU(first, nextBox)
                if (iou >= IOU_THRESHOLD) {
                    iterator.remove()
                }
            }
        }

        return selectedBoxes
    }

    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val x1 = maxOf(box1.x1, box2.x1)
        val y1 = maxOf(box1.y1, box2.y1)
        val x2 = minOf(box1.x2, box2.x2)
        val y2 = minOf(box1.y2, box2.y2)
        val intersectionArea = maxOf(0F, x2 - x1) * maxOf(0F, y2 - y1)
        val box1Area = box1.w * box1.h
        val box2Area = box2.w * box2.h
        return intersectionArea / (box1Area + box2Area - intersectionArea)
    }

    interface DetectorListener {
        fun onEmptyDetect()
        fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long)
    }

    companion object {
        private const val INPUT_MEAN = 0f
        private const val INPUT_STANDARD_DEVIATION = 255f
        private val INPUT_IMAGE_TYPE = DataType.FLOAT32
        private val OUTPUT_IMAGE_TYPE = DataType.FLOAT32
        private const val CONFIDENCE_THRESHOLD = 0.5F
        private const val IOU_THRESHOLD = 0.5F
    }
}