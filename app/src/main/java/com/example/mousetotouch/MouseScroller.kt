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

// --- 1. The Main Activity (Permission Setup) ---
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
            Toast.makeText(this, "Please allow 'Display over other apps'", Toast.LENGTH_LONG).show()
            return
        }
        
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "Find 'Mouse Scroller' and turn it ON", Toast.LENGTH_LONG).show()
    }
}

// --- 2. The Core Service Logic ---
class MouseAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var toggleButton: View? = null
    private var touchOverlay: View? = null
    private var isScrollModeActive = false
    
    // Screen dimensions for calculating center zoom
    private var screenWidth = 0
    private var screenHeight = 0

    override fun onServiceConnected() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        createToggleButton()
    }

    private fun createToggleButton() {
        toggleButton = Button(this).apply {
            text = "🖱 Mode"
            alpha = 0.8f
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
        
        touchOverlay = FrameLayout(this).apply {
            setBackgroundColor(0x05000000) // Very faint grey to indicate active
            setOnTouchListener { _, event ->
                handleMouseInput(event)
                true 
            }
        }

        val params = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                LayoutParams.TYPE_APPLICATION_OVERLAY 
            else LayoutParams.TYPE_PHONE,
            LayoutParams.FLAG_LAYOUT_IN_SCREEN, 
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(touchOverlay, params)
        isScrollModeActive = true
        (toggleButton as? Button)?.text = "❌ Stop"
    }

    private fun disableScrollMode() {
        if (!isScrollModeActive) return
        if (touchOverlay != null) {
            windowManager.removeView(touchOverlay)
            touchOverlay = null
        }
        isScrollModeActive = false
        (toggleButton as? Button)?.text = "🖱 Mode"
    }

    // --- Input Handling ---

    private var startX = 0f
    private var startY = 0f
    private var lastDispatchTime = 0L
    
    // Threshold to prevent jitter
    private val MOVEMENT_THRESHOLD = 20 

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

                // Throttle: Only process every 100ms to allow gesture to complete
                if (System.currentTimeMillis() - lastDispatchTime > 100) {
                    
                    // Check if Right Mouse Button is pressed
                    // BUTTON_SECONDARY is usually the right mouse button
                    val isRightClick = (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
                    
                    if (isRightClick) {
                        // --- ZOOM LOGIC ---
                        if (abs(dy) > MOVEMENT_THRESHOLD) {
                            // Negative dy means moving UP (Zoom In)
                            // Positive dy means moving DOWN (Zoom Out)
                            dispatchZoom(zoomIn = (dy < 0))
                            
                            // Reset origin to allow continuous zooming
                            startX = currentX
                            startY = currentY
                            lastDispatchTime = System.currentTimeMillis()
                        }
                    } else {
                        // --- SCROLL LOGIC (Left Click / Default) ---
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

    // existing scroll logic
    private fun dispatchSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val builder = GestureDescription.Builder()
        builder.addStroke(stroke)
        dispatchGesture(builder.build(), null, null)
    }

    // New Zoom Logic
    private fun dispatchZoom(zoomIn: Boolean) {
        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f
        val gap = 150f // Start distance from center
        val travel = 400f // How far the fingers move

        // Path 1: Top Finger
        val path1 = Path()
        // Path 2: Bottom Finger
        val path2 = Path()

        if (zoomIn) {
            // Pinch OPEN (Fingers start close, move out)
            // Finger 1: Starts slightly above center, moves WAY up
            path1.moveTo(centerX, centerY - gap)
            path1.lineTo(centerX, centerY - gap - travel)

            // Finger 2: Starts slightly below center, moves WAY down
            path2.moveTo(centerX, centerY + gap)
            path2.lineTo(centerX, centerY + gap + travel)
        } else {
            // Pinch CLOSED (Fingers start far apart, move to center)
            // Finger 1: Starts far up, moves to center
            path1.moveTo(centerX, centerY - gap - travel)
            path1.lineTo(centerX, centerY - gap)

            // Finger 2: Starts far down, moves to center
            path2.moveTo(centerX, centerY + gap + travel)
            path2.lineTo(centerX, centerY + gap)
        }

        val stroke1 = GestureDescription.StrokeDescription(path1, 0, 200)
        val stroke2 = GestureDescription.StrokeDescription(path2, 0, 200)

        val builder = GestureDescription.Builder()
        builder.addStroke(stroke1)
        builder.addStroke(stroke2) // Adding a second stroke makes it multi-touch

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
