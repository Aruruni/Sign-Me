package com.cc17.signme

import CameraFragment
import TFLiteModelHelper
import TextInputFragment
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cc17.signme.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private var cameraFragment: CameraFragment? = null
    private var textInputFragment: TextInputFragment? = null
    private lateinit var modelHelper: TFLiteModelHelper
    private lateinit var resultTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        resultTextView = findViewById(R.id.result_text_main)
        modelHelper = TFLiteModelHelper(this, "model.tflite")

        val bottomNavBar = findViewById<BottomNavigationView>(R.id.bottom_nav_bar)
        val darkColor = ContextCompat.getColorStateList(this, android.R.color.black)
        bottomNavBar.itemIconTintList = darkColor
        bottomNavBar.itemTextColor = darkColor

        if (cameraFragment == null) {
            cameraFragment = CameraFragment { imageProxy ->
                processFrame(imageProxy)
            }
            supportFragmentManager.beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                .replace(R.id.map_fragment, cameraFragment!!, "CAMERA_FRAGMENT")
                .commit()
        }

        bottomNavBar.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_camera -> showCameraFragment()
                R.id.nav_text_input -> showTextInputFragment()
            }
            true
        }
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val bitmap = ImageUtils.imageProxyToBitmap(imageProxy) ?: run {
            imageProxy.close()
            return
        }

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val prediction = modelHelper.detectObjects(bitmap)
                withContext(Dispatchers.Main) {
                    resultTextView.text = prediction
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error processing frame: ${e.message}", e)
            } finally {
                imageProxy.close()
            }
        }
    }

    private fun showCameraFragment() {
        supportFragmentManager.beginTransaction().apply {
            cameraFragment?.let { show(it) }
            textInputFragment?.let { hide(it) }
            commit()
        }
    }

    private fun showTextInputFragment() {
        if (textInputFragment == null) {
            textInputFragment = TextInputFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.map_fragment, textInputFragment!!, "TEXT_INPUT_FRAGMENT")
                .commit()
        }
        supportFragmentManager.beginTransaction().apply {
            textInputFragment?.let { show(it) }
            cameraFragment?.let { hide(it) }
            commit()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        modelHelper.close()


    }
}
