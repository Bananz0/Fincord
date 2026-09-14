package uk.akane.accord.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.widget.EditText
import com.google.android.material.textfield.TextInputLayout
import uk.akane.accord.R

/** Adds a one-tap clipboard action to connection fields without duplicating clipboard handling. */
fun TextInputLayout.enablePasteInto(field: EditText) {
    endIconMode = TextInputLayout.END_ICON_CUSTOM
    setEndIconDrawable(R.drawable.ic_paste)
    endIconContentDescription = context.getString(R.string.paste_from_clipboard)
    setEndIconOnClickListener {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val value = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (value.isNotEmpty()) {
            field.setText(value)
            field.setSelection(value.length)
        }
    }
}
