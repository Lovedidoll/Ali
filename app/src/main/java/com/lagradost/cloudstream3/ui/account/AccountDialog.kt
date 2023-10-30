package com.lagradost.cloudstream3.ui.account

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.LockPinDialogBinding
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe

object AccountDialog {
    // TODO add account creation dialog to allow creating accounts directly from AccountSelectActivity

    fun showPinInputDialog(
        context: Context,
        currentPin: String?,
        editAccount: Boolean,
        callback: (String?) -> Unit
    ) {
        fun TextView.visibleWithText(@StringRes textRes: Int) {
            visibility = View.VISIBLE
            setText(textRes)
        }

        fun View.isVisible() = visibility == View.VISIBLE

        val binding = LockPinDialogBinding.inflate(LayoutInflater.from(context))

        val isPinSet = currentPin != null
        val isNewPin = editAccount && !isPinSet
        val isEditPin = editAccount && isPinSet

        val titleRes = if (isEditPin) R.string.enter_current_pin else R.string.enter_pin

        val dialog = AlertDialog.Builder(context, R.style.AlertDialogCustom)
            .setView(binding.root)
            .setTitle(titleRes)
            .setNegativeButton(R.string.cancel) { _, _ ->
                callback.invoke(null)
            }
            .setOnCancelListener {
                callback.invoke(null)
            }
            .setOnDismissListener {
                if (binding.pinEditTextError.isVisible()) {
                    callback.invoke(null)
                }
            }
            .create()

        var isPinValid = false

        binding.pinEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val enteredPin = s.toString()
                val isEnteredPinValid = enteredPin.length == 4

                if (isEnteredPinValid) {
                    if (isPinSet) {
                        if (enteredPin != currentPin) {
                            binding.pinEditTextError.visibleWithText(R.string.pin_error_incorrect)
                            binding.pinEditText.text = null
                            isPinValid = false
                        } else {
                            binding.pinEditTextError.visibility = View.GONE
                            isPinValid = true

                            callback.invoke(enteredPin)
                            dialog.dismissSafe()
                        }
                    } else {
                        binding.pinEditTextError.visibility = View.GONE
                        isPinValid = true
                    }
                } else if (isNewPin) {
                    binding.pinEditTextError.visibleWithText(R.string.pin_error_length)
                    isPinValid = false
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        // Detect IME_ACTION_DONE
        binding.pinEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && isPinValid) {
                val enteredPin = binding.pinEditText.text.toString()
                callback.invoke(enteredPin)
                dialog.dismissSafe()
            }
            true
        }

        // We don't want to accidentally have the dialog dismiss when clicking outside of it.
        // That is what the cancel button is for.
        dialog.setCanceledOnTouchOutside(false)

        dialog.show()

        // Auto focus on PIN input and show keyboard
        binding.pinEditText.requestFocus()
        binding.pinEditText.postDelayed({
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.pinEditText, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }
}