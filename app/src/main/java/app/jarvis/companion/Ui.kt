package app.jarvis.companion

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object Theme {
    val bg = Color.parseColor("#02060C")
    val panel = Color.parseColor("#0A1624")
    val text = Color.parseColor("#BFE7FF")
    val dim = Color.parseColor("#6B8CA6")
    val accent = Color.parseColor("#38BDF8")
    val warn = Color.parseColor("#FBBF24")
    val bad = Color.parseColor("#F87171")
    val ok = Color.parseColor("#34D399")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.label(text: String, size: Float = 15f, color: Int = Theme.text, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

fun Context.button(text: String, primary: Boolean = false, onClick: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(if (primary) Color.parseColor("#02131F") else Theme.accent)
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (primary) Theme.accent else Theme.panel)
            setStroke(dp(1), Theme.accent)
        }
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { onClick() }
    }

fun Context.field(hint: String, multiline: Boolean = false, password: Boolean = false): EditText =
    EditText(this).apply {
        this.hint = hint
        setHintTextColor(Theme.dim)
        setTextColor(Theme.text)
        textSize = 16f
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Theme.panel)
            setStroke(dp(1), Color.parseColor("#1E3A52"))
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (!multiline) maxLines = 1
    }

fun Context.vertical(pad: Int = 12): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

fun Context.horizontal(): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

fun LinearLayout.add(v: View, top: Int = 8, weight: Float = 0f, width: Int = ViewGroup.LayoutParams.MATCH_PARENT): View {
    val lp = LinearLayout.LayoutParams(if (weight > 0f) 0 else width, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
    lp.topMargin = context.dp(top)
    addView(v, lp)
    return v
}

fun Context.card(): LinearLayout =
    vertical(12).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Theme.panel)
        }
    }
