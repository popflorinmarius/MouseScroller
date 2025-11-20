package com.example.mousetotouch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import kotlin.math.abs

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val btn = Button(this)
        btn.text = "Enable Service & Overlay"
        btn.setOnClickListener { checkPermissions() }
        setContentView(btn)
    }

    private fun checkPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, 
                Uri.parse("package:$packageName"))
            startActivity(intent)
            Toast.makeText(this, "Allow 'Display over other apps'", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "Turn ON 'Mouse Scroller'", Toast.LENGTH_LONG).show()
    }
}

class MouseAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var toggleButton: View? = null
    private var touchOverlay: View? = null
    private var isScrollModeActive = false
    
    private var screenWidth = 0
    private var screenHeight = 0

    override fun onServiceConnected() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        createToggleButton()
    }

    // --- SAFETY FEATURE: VOLUME DOWN KILLS OVERLAY ---
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (isScrollModeActive && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            disableScrollMode()
            Toast.makeText(this, "Emergency Stop Activated", Toast.LENGTH_SHORT).show()
            return true
        }
        return super.onKeyEvent(event)
    }

    private fun createToggleButton() {
        if (toggleButton != null) return
        
        toggleButton = Button(this).apply {
            text = "🖱 Mode"
            setBackgroundColor(0xFF4444AA.toInt()) // Blue color
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                if (isScrollModeActive) disableScrollMode() else enableScrollMode()
            }
        }

        val params = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                LayoutParams.TYPE_APPLICATION_OVERLAY 
            else LayoutParams.TYPE_PHONE,
            LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        windowManager.addView(toggleButton, params)
    }

    private fun enableScrollMode() {
        if (isScrollModeActive) return
        
        // 1. Remove button first (so we can add it back ON TOP later)
        if (toggleButton != null) windowManager.removeView(toggleButton)

        // 2. Create the Trap Overlay
        touchOverlay = FrameLayout(this).apply {
            // Slight RED tint so you know it's blocking touch
            setBackgroundColor(0x20FF0000) 
            setOnTouchListener { _, event ->
                handleMouseInput(event)
                true // We Consume the touch. Normal touch WILL NOT work here.
            }
        }

        val overlayParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                LayoutParams.TYPE_APPLICATION_OVERLAY 
            else LayoutParams.TYPE_PHONE,
            LayoutParams.FLAG_LAYOUT_IN_SCREEN, 
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(touchOverlay, overlayParams)
        isScrollModeActive = true

        // 3. Re-add Button (Now it is on TOP of the overlay)
        (toggleButton as? Button)?.text = "❌ STOP"
        (toggleButton as? Button)?.setBackgroundColor(0xFFAA0000.toInt()) // Red
        
        // Add button back
        val btnParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                LayoutParams.TYPE_APPLICATION_OVERLAY 
            else LayoutParams.TYPE_PHONE,
            LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }
        windowManager.addView(toggleButton, btnParams)
    }

    private fun disableScrollMode() {
        if (!isScrollModeActive) return
        
        // Remove everything
        if (touchOverlay != null) {
            windowManager.removeView(touchOverlay)
            touchOverlay = null
        }
        if (toggleButton != null) {
            windowManager.removeView(toggleButton)
            toggleButton = null
        }
        
        isScrollModeActive = false
        // Re-create just the button
        createToggleButton()
    }

    // --- Input Handling ---
    private var startX = 0f
    private var startY = 0f
    private var lastDispatchTime = 0L
    private val MOVEMENT_THRESHOLD = 10 // Made more sensitive

    private fun handleMouseInput(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val currentX = event.rawX
                val currentY = event.rawY
                val dx = currentX - startX
                val dy = currentY - startY

                if (System.currentTimeMillis() - lastDispatchTime > 50) {
                    val isRightClick = (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
                    
                    if (isRightClick) {
                        if (abs(dy) > MOVEMENT_THRESHOLD) {
                            dispatchZoom(zoomIn = (dy < 0))
                            startX = currentX
                            startY = currentY
                            lastDispatchTime = System.currentTimeMillis()
                        }
                    } else {
                        // Regular Scroll
                        if (abs(dx) > MOVEMENT_THRESHOLD || abs(dy) > MOVEMENT_THRESHOLD) {
                            dispatchSwipe(startX, startY, currentX, currentY)
                            startX = currentX
                            startY = currentY
                            lastDispatchTime = System.currentTimeMillis()
                        }
                    }
                }
            }
        }
    }

    private fun dispatchSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        // Clamp coordinates to be on screen (prevents crashes)
        val safeX1 = x1.coerceIn(0f, screenWidth.toFloat())
        val safeY1 = y1.coerceIn(0f, screenHeight.toFloat())
        val safeX2 = x2.coerceIn(0f, screenWidth.toFloat())
        val safeY2 = y2.coerceIn(0f, screenHeight.toFloat())

        if (abs(safeX1 - safeX2) < 5 && abs(safeY1 - safeY2) < 5) return

        val path = Path()
        path.moveTo(safeX1, safeY1)
        path.lineTo(safeX2, safeY2)
        
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        dispatchGesture(builder.build(), null, null)
    }

    private fun dispatchZoom(zoomIn: Boolean) {
        val cx = screenWidth / 2f
        val cy = screenHeight / 2f
        val gap = 200f
        val travel = 300f

        val path1 = Path()
        val path2 = Path()

        if (zoomIn) {
            path1.moveTo(cx, cy - gap); path1.lineTo(cx, cy - gap - travel)
            path2.moveTo(cx, cy + gap); path2.lineTo(cx, cy + gap + travel)
        } else {
            path1.moveTo(cx, cy - gap - travel); path1.lineTo(cx, cy - gap)
            path2.moveTo(cx, cy + gap + travel); path2.lineTo(cx, cy + gap)
        }

        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path1, 0, 200))
        builder.addStroke(GestureDescription.StrokeDescription(path2, 0, 200))
        dispatchGesture(builder.build(), null, null)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}
    
    override fun onDestroy() {
        super.onDestroy()
        if (toggleButton != null) windowManager.removeView(toggleButton)
        if (touchOverlay != null) windowManager.removeView(touchOverlay)
    }
}
