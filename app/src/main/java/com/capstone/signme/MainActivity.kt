package com.capstone.signme

import TextInputFragment
import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {
    private var cameraFragment: CameraFragment? = null
    private var textInputFragment: TextInputFragment? = null
    private lateinit var resultTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        resultTextView = findViewById(R.id.result_text_main)

        val bottomNavBar = findViewById<BottomNavigationView>(R.id.bottom_nav_bar)
        val darkColor = ContextCompat.getColorStateList(this, android.R.color.black)
        bottomNavBar.itemIconTintList = darkColor
        bottomNavBar.itemTextColor = darkColor

        if (cameraFragment == null) {
            cameraFragment = CameraFragment()  // Remove the lambda parameter
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

}
