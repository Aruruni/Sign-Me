import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.cc17.signme.R

class TextInputFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_text_input, container, false)

        val editText = view.findViewById<EditText>(R.id.input_text)
        val clearButton = view.findViewById<Button>(R.id.submit_button)
        val seekBar = view.findViewById<SeekBar>(R.id.font_size_seekbar)
        val fontSizeLabel = view.findViewById<TextView>(R.id.font_size_label)

        // Set initial font size
        val initialFontSize = 20f
        seekBar.progress = initialFontSize.toInt()
        editText.textSize = initialFontSize
        fontSizeLabel.text = "Font Size: ${initialFontSize.toInt()} sp"

        // Update font size based on SeekBar progress
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val newFontSize = progress.toFloat()
                editText.textSize = newFontSize
                fontSizeLabel.text = "Font Size: $progress sp"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        clearButton.setOnClickListener {
            val inputText = editText.text.toString()
            if (inputText.isNotEmpty()) {
                editText.text.clear()
                Toast.makeText(requireContext(), "Text Cleared", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Please enter some text.", Toast.LENGTH_SHORT).show()
            }
        }

        return view
    }
}