package app.phonehop

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

/** Palette for the dark "glass" look. */
object Glass {
    val TEXT = Color.parseColor("#ECEEF1")
    val TEXT_2 = Color.parseColor("#8B929C")
    val ACCENT = Color.parseColor("#D5D9DF")      // primary buttons, selected segment
    val ON_ACCENT = Color.parseColor("#0B0C0E")   // text on ACCENT
    val DOWN = Color.parseColor("#D5D9DF")        // graph: download (bright)
    val UP = Color.parseColor("#7C8798")          // graph: upload (dim)
    val TOGGLE_ON = Color.parseColor("#B8C0CC")
    val RED = Color.parseColor("#C8736D")
    val CARD_TOP = Color.parseColor("#17FFFFFF")
    val CARD_BOTTOM = Color.parseColor("#08FFFFFF")
    val STROKE = Color.parseColor("#1FFFFFFF")
    val FILL = Color.parseColor("#0FFFFFFF")
    val POPUP = Color.parseColor("#F0151619")
}

/** Near-black gradient with faint neutral glows; the cards' translucency picks these up like frosted glass. */
class AuroraDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        paint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(Color.parseColor("#050607"), Color.parseColor("#0A0B0D"), Color.parseColor("#0E0F12")),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(bounds, paint)
        glow(canvas, w * 0.90f, h * 0.06f, w * 0.85f, Color.parseColor("#1F8B929C"))
        glow(canvas, w * 0.02f, h * 0.55f, w * 0.75f, Color.parseColor("#12606876"))
    }

    private fun glow(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        paint.shader = RadialGradient(cx, cy, r, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Required override")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * Selectable text that never scrolls its parent to follow the text cursor. A focused, selectable
 * TextView otherwise drags the whole page to itself every time its text changes, which made the
 * screen jump to the log whenever a button was pressed.
 */
class QuietTextView(context: Context) : TextView(context) {
    init {
        // When focus lands here (the system re-focuses after a card is redrawn), do not scroll the page to us.
        revealOnFocusHint = false
    }

    override fun bringPointIntoView(offset: Int): Boolean = false
}

/**
 * A ScrollView that never scrolls by itself to reveal a view that just got focus or changed. Android does that after a
 * card is redrawn or a log line is added, which threw the page far away from what was just tapped. Finger scrolling and
 * the explicit keyboard handling in the activity are not affected.
 */
class StableScrollView(context: Context) : android.widget.ScrollView(context) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect): Int = 0
}

/** View factory for the screen's building blocks. */
class Ui(private val ctx: Context) {
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun lp(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = top }

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun glassFill(radiusDp: Int) = rounded(Glass.FILL, radiusDp, Glass.STROKE)

    // ---------------------------------------------------------------- text

    fun appTitle(title: String, subtitle: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(ctx).apply { setImageResource(R.drawable.logo) }, LinearLayout.LayoutParams(dp(56), dp(56)))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply {
                text = title; textSize = 30f; setTextColor(Glass.TEXT); typeface = bold
            })
            addView(TextView(ctx).apply {
                text = subtitle; textSize = 14f; setTextColor(Glass.TEXT_2)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
    }

    /** A small heading that separates groups of cards (for example "Advanced"). */
    fun sectionLabel(t: String) = TextView(ctx).apply {
        text = t.uppercase(); textSize = 11.5f; letterSpacing = 0.12f; setTextColor(Glass.TEXT_2); typeface = medium
        setPadding(dp(6), dp(26), 0, dp(2))
    }

    /** The headline status card: a coloured dot, a big state, one line of detail, and the technical checklist behind "Details". */
    class StatusHero(val view: View, private val dot: GradientDrawable, private val title: TextView, private val sub: TextView) {
        fun set(h: StatusSummary.Headline) {
            title.text = h.title; sub.text = h.subtitle
            sub.visibility = if (h.subtitle.isEmpty()) View.GONE else View.VISIBLE
            dot.setColor(when (h.level) {
                StatusSummary.Level.GOOD -> Color.parseColor("#7FC8A0")
                StatusSummary.Level.WARN -> Color.parseColor("#D9B26B")
                StatusSummary.Level.IDLE -> Color.parseColor("#6F7681")
            })
        }
    }

    fun statusHero(details: View): StatusHero {
        val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#6F7681")) }
        val title = TextView(ctx).apply { text = "Starting..."; textSize = 26f; setTextColor(Glass.TEXT); typeface = bold }
        val sub = TextView(ctx).apply { text = ""; textSize = 14f; setTextColor(Glass.TEXT_2); visibility = View.GONE }
        val toggle = TextView(ctx).apply { text = "Details \u25B8"; textSize = 13f; setTextColor(Glass.TEXT_2); setPadding(0, dp(10), 0, dp(2)) }
        details.visibility = View.GONE
        toggle.setOnClickListener {
            val open = details.visibility != View.VISIBLE
            details.visibility = if (open) View.VISIBLE else View.GONE
            toggle.text = if (open) "Details \u25BE" else "Details \u25B8"
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(View(ctx).apply { background = dot }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(12) })
            addView(title)
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Glass.CARD_TOP, Glass.CARD_BOTTOM))
                .apply { cornerRadius = dp(24).toFloat(); setStroke(dp(1), Glass.STROKE) }
            addView(row)
            addView(sub, lp(dp(4)))
            addView(toggle)
            addView(details, lp(dp(6)))
        }
        return StatusHero(card, dot, title, sub)
    }

    fun caption(t: String) = TextView(ctx).apply { text = t; textSize = 12.5f; setTextColor(Glass.TEXT_2) }

    fun mono(t: String, sizeSp: Float = 12f) = QuietTextView(ctx).apply {
        text = t; textSize = sizeSp; setTextColor(Glass.TEXT); typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }

    // ---------------------------------------------------------------- containers

    /** A frosted card with a title, optional subtitle, and children spaced evenly. */
    fun card(title: String, subtitle: String?, vararg children: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(18))
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Glass.CARD_TOP, Glass.CARD_BOTTOM)
        ).apply { cornerRadius = dp(22).toFloat(); setStroke(dp(1), Glass.STROKE) }
        addView(TextView(ctx).apply { text = title; textSize = 18f; setTextColor(Glass.TEXT); typeface = medium })
        if (subtitle != null) addView(caption(subtitle), lp(dp(2)))
        children.forEach { addView(it, lp(dp(12))) }
    }

    /** A card that starts folded; tapping its title opens it. For technical details that most days nobody needs. */
    fun collapsible(title: String, subtitle: String?, vararg children: View, expanded: Boolean = false): View {
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
            if (subtitle != null) addView(caption(subtitle), lp())
            children.forEach { addView(it, lp(dp(12))) }
        }
        val arrow = TextView(ctx).apply { text = if (expanded) "\u25BE" else "\u25B8"; textSize = 18f; setTextColor(Glass.TEXT_2) }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            addView(TextView(ctx).apply { text = title; textSize = 18f; setTextColor(Glass.TEXT); typeface = medium },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(arrow)
            setOnClickListener {
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
                arrow.text = if (open) "\u25BE" else "\u25B8"
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Glass.CARD_TOP, Glass.CARD_BOTTOM))
                .apply { cornerRadius = dp(22).toFloat(); setStroke(dp(1), Glass.STROKE) }
            addView(header)
            addView(body, lp(dp(12)))
        }
    }

    fun cardParams(top: Int = 16) = lp(dp(top))

    /** A visible heading above a control, with optional help text beneath it. */
    fun labeled(label: String, control: View, help: String? = null) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(ctx).apply {
            text = label; textSize = 13f; setTextColor(Glass.TEXT); typeface = medium
            setPadding(dp(4), 0, 0, dp(6))
        })
        addView(control)
        if (help != null) addView(caption(help).apply { setPadding(dp(4), dp(6), dp(4), 0) })
    }

    /** Vertical group whose children are evenly spaced. */
    fun stack(vararg children: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        children.forEachIndexed { i, v -> addView(v, lp(if (i == 0) 0 else dp(12))) }
    }

    fun watch(field: EditText, onChange: (String) -> Unit) {
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = onChange(s?.toString().orEmpty())
        })
    }

    fun row(vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i < views.size - 1) marginEnd = dp(10)
            })
        }
    }

    // ---------------------------------------------------------------- controls

    private fun pill(text: String, fill: Int, stroke: Int?, textColor: Int, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        typeface = medium
        setTextColor(textColor)
        stateListAnimator = null
        minHeight = dp(46)
        minimumHeight = dp(46)
        setPadding(dp(12), 0, dp(12), 0)
        background = RippleDrawable(
            ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
            rounded(fill, 14, stroke),
            rounded(Color.WHITE, 14)
        )
        setOnClickListener { onClick() }
    }

    fun primary(text: String, onClick: () -> Unit) = pill(text, Glass.ACCENT, null, Glass.ON_ACCENT, onClick)
    fun secondary(text: String, onClick: () -> Unit) = pill(text, Glass.FILL, Glass.STROKE, Glass.TEXT, onClick)
    fun danger(text: String, onClick: () -> Unit) = pill(text, Glass.FILL, Glass.STROKE, Glass.RED, onClick)

    fun field(hint: String, value: String = "", number: Boolean = false) = EditText(ctx).apply {
        this.hint = hint
        setText(value)
        setSingleLine()
        textSize = 16f
        setTextColor(Glass.TEXT)
        setHintTextColor(Color.parseColor("#80A3ABBA"))
        background = glassFill(14)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        if (number) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    /**
     * A text/number field whose contents are hidden until the eye button is tapped.
     * Returns the container to add to a layout and the EditText to read from.
     */
    fun secretField(hint: String, number: Boolean): Pair<View, EditText> {
        val field = EditText(ctx).apply {
            this.hint = hint
            textSize = 16f
            setTextColor(Glass.TEXT)
            setHintTextColor(Color.parseColor("#80A3ABBA"))
            background = glassFill(14)
            setPadding(dp(16), dp(14), dp(52), dp(14))
        }
        val eye = ImageView(ctx).apply {
            setColorFilter(Glass.TEXT_2)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = false
        }
        var shown = false
        fun apply() {
            val cls = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            val variation = when {
                number && shown -> InputType.TYPE_NUMBER_VARIATION_NORMAL
                number -> InputType.TYPE_NUMBER_VARIATION_PASSWORD
                shown -> InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else -> InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            // No setSingleLine() here: it swaps the password masking for a plain single-line display.
            field.inputType = cls or variation
            field.setSelection(field.text.length)
            eye.setImageResource(if (shown) R.drawable.ic_eye_off else R.drawable.ic_eye)
            eye.contentDescription = if (shown) "Hide" else "Show"
        }
        apply()
        eye.setOnClickListener { shown = !shown; apply() }
        val box = FrameLayout(ctx).apply {
            addView(field, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(eye, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
        }
        return Pair(box, field)
    }

    fun toggle(text: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(ctx).apply {
        this.text = text
        textSize = 15f
        setTextColor(Glass.TEXT)
        isChecked = checked
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Color.parseColor("#9AA1AC")))
        trackTintList = ColorStateList(states, intArrayOf(Glass.TOGGLE_ON, Color.parseColor("#33FFFFFF")))
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    /** iOS-style segmented control. */
    fun segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = glassFill(14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val tabs = mutableListOf<TextView>()
        fun paint(sel: Int) = tabs.forEachIndexed { i, t ->
            t.background = if (i == sel) rounded(Glass.ACCENT, 11) else ColorDrawable(Color.TRANSPARENT)
            t.setTextColor(if (i == sel) Glass.ON_ACCENT else Glass.TEXT_2)
        }
        labels.forEachIndexed { i, label ->
            val tab = TextView(ctx).apply {
                text = label; textSize = 14f; typeface = medium; gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { paint(i); onSelect(i) }
            }
            tabs.add(tab)
            wrap.addView(tab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        paint(selected)
        return wrap
    }

    /** Wraps a spinner in a glass box with a chevron (the stock arrow disappears with a custom background). */
    fun spinnerBox(spinner: Spinner): View {
        spinner.background = ColorDrawable(Color.TRANSPARENT)
        spinner.setPopupBackgroundDrawable(rounded(Glass.POPUP, 14, Glass.STROKE))
        return FrameLayout(ctx).apply {
            background = glassFill(14)
            addView(spinner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(TextView(ctx).apply {
                text = "▾"; textSize = 16f; setTextColor(Glass.TEXT_2)
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { marginEnd = dp(16) })
        }
    }

    fun adapter(items: List<String>) = object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getView(position, convertView, parent) as TextView).apply {
                setTextColor(Glass.TEXT); textSize = 15f
                setPadding(dp(16), dp(14), dp(40), dp(14))
            }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getDropDownView(position, convertView, parent) as TextView).apply {
                setTextColor(Glass.TEXT); textSize = 15f
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setBackgroundColor(Color.TRANSPARENT)
            }
    }
}

/** Scrolling area chart of two series (download and upload) over the last minute. */
class LiveGraphView(
    context: Context,
    private val downColor: Int,
    private val upColor: Int,
    private val floor: Float = 50_000f,
    private val format: (Double) -> String = TrafficMath::formatRate,
) : View(context) {
    private val capacity = 60
    private val down = FloatArray(capacity)
    private val up = FloatArray(capacity)
    private var count = 0
    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val grid = Paint().apply { color = Color.parseColor("#1AFFFFFF"); strokeWidth = 1f * density }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Glass.TEXT_2; textSize = 10f * density; typeface = Typeface.MONOSPACE
    }

    fun push(downBps: Double, upBps: Double) {
        if (count == capacity) {
            System.arraycopy(down, 1, down, 0, capacity - 1)
            System.arraycopy(up, 1, up, 0, capacity - 1)
        } else {
            count++
        }
        down[count - 1] = downBps.toFloat()
        up[count - 1] = upBps.toFloat()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val top = 16f * density
        val bottom = h - 4f * density
        val chartH = bottom - top
        var peak = floor // a minimum scale so an idle line doesn't look like a storm
        for (i in 0 until count) peak = maxOf(peak, down[i], up[i])
        val max = peak * 1.15f

        for (i in 0..2) {
            val y = top + chartH * i / 2f
            canvas.drawLine(0f, y, w, y, grid)
        }
        canvas.drawText(format(max.toDouble()), 2f * density, 11f * density, label)

        drawSeries(canvas, up, upColor, w, top, chartH, max)
        drawSeries(canvas, down, downColor, w, top, chartH, max)
    }

    private fun drawSeries(canvas: Canvas, data: FloatArray, color: Int, w: Float, top: Float, chartH: Float, max: Float) {
        if (count < 2) return
        fun x(i: Int) = w * (capacity - count + i) / (capacity - 1)
        fun y(v: Float) = top + chartH * (1f - v / max)
        val path = android.graphics.Path().apply {
            moveTo(x(0), y(data[0]))
            for (i in 1 until count) {
                val mx = (x(i - 1) + x(i)) / 2f
                cubicTo(mx, y(data[i - 1]), mx, y(data[i]), x(i), y(data[i]))
            }
        }
        val area = android.graphics.Path(path).apply {
            lineTo(x(count - 1), top + chartH)
            lineTo(x(0), top + chartH)
            close()
        }
        fill.shader = LinearGradient(
            0f, top, 0f, top + chartH,
            (color and 0x00FFFFFF) or 0x59000000, color and 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        canvas.drawPath(area, fill)
        line.color = color
        canvas.drawPath(path, line)
    }
}

/** Title, current rates and a live graph. */
class TrafficPanel(
    context: Context,
    title: String,
    floor: Float = 50_000f,
    private val format: (Double) -> String = TrafficMath::formatRate,
    private val downLabel: String = "\u2193",
    private val upLabel: String = "\u2191",
) : LinearLayout(context) {
    private val downColor = Glass.DOWN
    private val upColor = Glass.UP
    private val graph = LiveGraphView(context, downColor, upColor, floor, format)
    private val rates = TextView(context).apply { textSize = 12f; typeface = Typeface.MONOSPACE }
    private val density = resources.displayMetrics.density

    init {
        orientation = VERTICAL
        val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply {
            text = title; textSize = 13f; setTextColor(Glass.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(rates)
        addView(header)
        addView(graph, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (96 * density).toInt()).apply {
            topMargin = (6 * density).toInt()
        })
        show(0.0, 0.0)
    }

    fun update(downBps: Double, upBps: Double) {
        graph.push(downBps, upBps)
        show(downBps, upBps)
    }

    private fun show(downBps: Double, upBps: Double) {
        rates.text = android.text.SpannableStringBuilder().apply {
            fun part(t: String, color: Int) {
                val start = length
                append(t)
                setSpan(android.text.style.ForegroundColorSpan(color), start, length, 0)
            }
            part("$downLabel ${format(downBps)}", downColor)
            append("   ")
            part("$upLabel ${format(upBps)}", upColor)
        }
    }
}
