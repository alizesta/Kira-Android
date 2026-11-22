package ai.kira.mobile.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import ai.kira.mobile.MainActivity

class AiAssistantOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            isRunning = false
            hideQuickPanel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForegroundService()
        showBubbleIfAllowed()
        isRunning = bubble != null
        return START_STICKY
    }

    private fun startAsForegroundService() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    "Kira floating assistant",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps the user-enabled Kira floating assistant available."
                    setShowBadge(false)
                }
            )
        }

        val openIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, AiAssistantOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Kira is active")
            .setContentText("Floating assistant is available. Tap to open Kira.")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(0, "خاموش کردن", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showBubbleIfAllowed() {
        if (!Settings.canDrawOverlays(this) || bubble != null) return

        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = TextView(this).apply {
            text = "K"
            textSize = 21f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            elevation = 12f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF7661D8.toInt())
                setStroke(2, 0xFFB9A5FF.toInt())
            }
            contentDescription = "باز کردن پنل سریع Kira"
            isClickable = true
            isFocusable = false
        }

        val params = WindowManager.LayoutParams(
            dp(58),
            dp(58),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(18)
            y = dp(220)
        }

        attachTouchBehavior(view, params, manager)
        manager.addView(view, params)
        windowManager = manager
        bubble = view
        bubbleParams = params
    }

    private fun attachTouchBehavior(
        view: View,
        params: WindowManager.LayoutParams,
        manager: WindowManager
    ) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + downX - event.rawX).toInt().coerceAtLeast(0)
                    params.y = (startY + event.rawY - downY).toInt().coerceAtLeast(0)
                    manager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.hypot(event.rawX - downX, event.rawY - downY)
                    if (moved < dp(10)) toggleQuickPanel()
                    true
                }
                else -> true
            }
        }
    }

    private fun toggleQuickPanel() {
        if (panel != null) {
            hideQuickPanel()
            return
        }
        val manager = windowManager ?: return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(0xF2161D2B.toInt())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0xFF62568E.toInt())
            }
            elevation = dp(18).toFloat()
            isFocusable = true
            isFocusableInTouchMode = true
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Kira · پنل سریع"
            textSize = 16f
            setTextColor(0xFFF4F2FA.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(42), 1f))
        val close = TextView(this).apply {
            text = "×"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(0xFFB9A5FF.toInt())
            contentDescription = "بستن پنل"
        }
        header.addView(close, LinearLayout.LayoutParams(dp(36), dp(42)))
        close.setOnClickListener { hideQuickPanel() }
        root.addView(header)

        val description = TextView(this).apply {
            text = "مستقیم به قابلیت موردنظرت برو."
            textSize = 11f
            setTextColor(0xFF9BA5B8.toInt())
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(description)
        addActionRow(root, listOf("چت" to ROUTE_CHAT, "جستجو" to ROUTE_SEARCH))
        addActionRow(root, listOf("ساخت تصویر" to ROUTE_IMAGE, "تماس صوتی" to ROUTE_VOICE))

        val prompt = EditText(this).apply {
            hint = "پیام سریع برای Kira…"
            setHintTextColor(0xFF9BA5B8.toInt())
            setTextColor(0xFFF4F2FA.toInt())
            textSize = 14f
            minLines = 1
            maxLines = 3
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(0xFF20283A.toInt())
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), 0xFF49536A.toInt())
            }
        }
        val promptParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        root.addView(prompt, promptParams)

        val send = Button(this).apply {
            text = "ارسال به چت"
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            background = actionBackground()
        }
        val sendParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ).apply { topMargin = dp(6) }
        root.addView(send, sendParams)
        send.setOnClickListener {
            val text = prompt.text?.toString()?.trim().orEmpty()
            hideQuickPanel()
            openMainActivity(ROUTE_CHAT, text)
        }

        val params = WindowManager.LayoutParams(
            dp(288),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(10)
            y = (bubbleParams?.y ?: dp(220)).coerceAtLeast(dp(12))
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        manager.addView(root, params)
        panel = root
        root.requestFocus()
    }

    private fun addActionRow(root: LinearLayout, actions: List<Pair<String, String>>) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.forEach { (label, route) ->
            val button = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 12f
                setTextColor(0xFFFFFFFF.toInt())
                background = actionBackground()
                setOnClickListener {
                    hideQuickPanel()
                    openMainActivity(route)
                }
            }
            val params = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
            row.addView(button, params)
        }
        val rowParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) }
        root.addView(row, rowParams)
    }

    private fun actionBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(0xFF493D79.toInt())
        cornerRadius = dp(12).toFloat()
    }

    private fun hideQuickPanel() {
        val current = panel ?: return
        runCatching { windowManager?.removeView(current) }
        panel = null
    }

    private fun openMainActivity(route: String, text: String = "") {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, route)
            putExtra(MainActivity.EXTRA_CHAT_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        hideQuickPanel()
        bubble?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubble = null
        bubbleParams = null
        windowManager = null
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        const val ACTION_START = "ai.kira.mobile.action.START_ASSISTANT_OVERLAY"
        const val ACTION_STOP = "ai.kira.mobile.action.STOP_ASSISTANT_OVERLAY"
        const val ROUTE_CHAT = "chat"
        const val ROUTE_SEARCH = "search"
        const val ROUTE_IMAGE = "image"
        const val ROUTE_VOICE = "voice"
        private const val NOTIFICATION_CHANNEL = "kira_overlay"
        private const val NOTIFICATION_ID = 8301
        private const val REQUEST_OPEN = 8302
        private const val REQUEST_STOP = 8303
    }
}
