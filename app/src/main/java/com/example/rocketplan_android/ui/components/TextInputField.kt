package com.example.rocketplan_android.ui.components

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.widget.addTextChangedListener
import com.example.rocketplan_android.R
import com.example.rocketplan_android.databinding.ComponentTextInputFieldBinding

/**
 * Custom input field component matching iOS InputField design
 */
class TextInputField @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding: ComponentTextInputFieldBinding =
        ComponentTextInputFieldBinding.inflate(LayoutInflater.from(context), this, true)

    var title: String = ""
        set(value) {
            field = value
            binding.inputTitle.text = value
        }

    var hint: String = ""
        set(value) {
            field = value
            binding.inputEditText.hint = value
        }

    var text: String
        get() = binding.inputEditText.text?.toString() ?: ""
        set(value) {
            if (binding.inputEditText.text?.toString() != value) {
                binding.inputEditText.setText(value)
            }
        }

    var errorMessage: String? = null
        set(value) {
            field = value
            if (value.isNullOrEmpty()) {
                binding.inputError.visibility = View.GONE
                binding.inputDivider.setBackgroundColor(
                    context.getColor(R.color.light_border)
                )
            } else {
                binding.inputError.text = value
                binding.inputError.visibility = View.VISIBLE
                binding.inputDivider.setBackgroundColor(
                    context.getColor(R.color.warning_red)
                )
            }
        }

    var inputType: Int
        get() = binding.inputEditText.inputType
        set(value) {
            binding.inputEditText.inputType = value
        }

    var onTextChanged: ((String) -> Unit)? = null

    init {
        orientation = VERTICAL

        // Handle text changes
        binding.inputEditText.addTextChangedListener { editable ->
            val newText = editable?.toString() ?: ""
            onTextChanged?.invoke(newText)
            // Clear error when user starts typing
            if (errorMessage != null) {
                errorMessage = null
            }
        }

        // Handle focus changes for divider color
        binding.inputEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && errorMessage == null) {
                binding.inputDivider.setBackgroundColor(
                    context.getColor(R.color.main_purple)
                )
            } else if (!hasFocus && errorMessage == null) {
                binding.inputDivider.setBackgroundColor(
                    context.getColor(R.color.light_border)
                )
            }
        }

        // Read custom attributes if provided
        attrs?.let {
            val typedArray = context.obtainStyledAttributes(it, R.styleable.TextInputField)
            try {
                title = typedArray.getString(R.styleable.TextInputField_title) ?: ""
                hint = typedArray.getString(R.styleable.TextInputField_hint) ?: ""
            } finally {
                typedArray.recycle()
            }
        }
    }

    fun setInputTypeEmail() {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
    }

    fun setInputTypePassword() {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    fun requestInputFocus() {
        binding.inputEditText.requestFocus()
    }
}
