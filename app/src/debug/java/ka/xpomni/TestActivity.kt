package ka.xpomni

import android.app.Activity
import android.graphics.Paint
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class TestActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var textView: TextView
    private lateinit var surfaceView: SurfaceView
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00FFFF.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFF00.toInt())
        }
        setContentView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        textView = TextView(this)
        content.addView(
            textView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            ),
        )

        surfaceView = SurfaceView(this).apply {
            setZOrderOnTop(true)
            setSecure(true)
            holder.addCallback(this@TestActivity)
        }
        content.addView(
            surfaceView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            ),
        )
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val canvas = holder.lockCanvas()
        canvas.drawRect(
            0f,
            0f,
            surfaceView.measuredWidth.toFloat(),
            surfaceView.measuredHeight.toFloat(),
            paint,
        )
        textView.text = "SurfaceView"
        textView.draw(canvas)
        textView.text = "TextView"
        holder.unlockCanvasAndPost(canvas)
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
}
