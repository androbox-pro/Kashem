package com.andrognito.patternlockview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.andrognito.patternlockview.listener.PatternLockViewListener
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class PatternLockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Dot(val row: Int, val column: Int, val id: Int)

    enum class PatternViewMode { CORRECT, WRONG, NORMAL }

    var normalStateColor: Int = 0xFFFFFFFF.toInt()
        set(value) { field = value; invalidate() }
    var correctStateColor: Int = 0xFF4CAF50.toInt()
        set(value) { field = value; invalidate() }
    var wrongStateColor: Int = 0xFFF44336.toInt()
        set(value) { field = value; invalidate() }
    var isInputEnabled: Boolean = true
        set(value) { field = value; if (!value) invalidate() }

    private val dots = Array(3) { row -> Array(3) { col -> Dot(row, col, row * 3 + col) } }
    private val selected = ArrayList<Dot>()
    private val listeners = LinkedHashSet<PatternLockViewListener>()
    private var mode = PatternViewMode.NORMAL
    private var drawing = false
    private var touchX = 0f
    private var touchY = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(4f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        val step = side / 4f
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        val radius = dp(8f)

        pathPaint.color = when (mode) {
            PatternViewMode.CORRECT -> correctStateColor
            PatternViewMode.WRONG -> wrongStateColor
            PatternViewMode.NORMAL -> normalStateColor
        }
        if (selected.size > 1) {
            val path = Path()
            selected.forEachIndexed { index, dot ->
                val x = left + step * (dot.column + 1)
                val y = top + step * (dot.row + 1)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (drawing) path.lineTo(touchX, touchY)
            canvas.drawPath(path, pathPaint)
        }

        for (row in 0..2) for (col in 0..2) {
            val dot = dots[row][col]
            val x = left + step * (col + 1)
            val y = top + step * (row + 1)
            val isSelected = selected.contains(dot)
            paint.color = if (isSelected) pathPaint.color else normalStateColor
            canvas.drawCircle(x, y, if (isSelected) radius * 1.45f else radius, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isInputEnabled) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = PatternViewMode.NORMAL
                selected.clear()
                drawing = true
                touchX = event.x
                touchY = event.y
                dotAt(event.x, event.y)?.let {
                    selected.add(it)
                    listeners.forEach { listener -> listener.onStarted() }
                    listeners.forEach { listener -> listener.onProgress(ArrayList(selected)) }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (drawing) {
                    touchX = event.x
                    touchY = event.y
                    dotAt(event.x, event.y)?.let { dot ->
                        if (!selected.contains(dot)) {
                            selected.add(dot)
                            listeners.forEach { listener -> listener.onProgress(ArrayList(selected)) }
                        }
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drawing) {
                    touchX = event.x
                    touchY = event.y
                    drawing = false
                    if (selected.isNotEmpty()) {
                        listeners.forEach { listener -> listener.onComplete(ArrayList(selected)) }
                    }
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dotAt(x: Float, y: Float): Dot? {
        val side = min(width, height).toFloat()
        val step = side / 4f
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        var best: Dot? = null
        var bestDistance = Float.MAX_VALUE
        for (row in 0..2) for (col in 0..2) {
            val cx = left + step * (col + 1)
            val cy = top + step * (row + 1)
            val d = sqrt((x - cx).pow(2) + (y - cy).pow(2))
            if (d < step * 0.42f && d < bestDistance) {
                bestDistance = d
                best = dots[row][col]
            }
        }
        return best
    }

    fun addPatternLockListener(listener: PatternLockViewListener) { listeners.add(listener) }
    fun removePatternLockListener(listener: PatternLockViewListener) { listeners.remove(listener) }

    fun clearPattern() {
        selected.clear()
        drawing = false
        mode = PatternViewMode.NORMAL
        invalidate()
        listeners.forEach { it.onCleared() }
    }

    fun setViewMode(viewMode: PatternViewMode) { mode = viewMode; invalidate() }
    fun setInputEnabled(enabled: Boolean) { isInputEnabled = enabled }
    fun setInStealthMode(@Suppress("UNUSED_PARAMETER") enabled: Boolean) {}
    fun setTactileFeedbackEnabled(@Suppress("UNUSED_PARAMETER") enabled: Boolean) {}

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
