package com.ioxxy.numduo

import android.content.*
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.view.SurfaceHolder
import androidx.preference.PreferenceManager
import java.lang.NumberFormatException

import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.TimeZone
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Updates rate in milliseconds for interactive mode. We update once a second to advance the
 * second hand.
 */
private const val INTERACTIVE_UPDATE_RATE_MS = 20000

/**
 * Handler message id for updating the time periodically in interactive mode.
 */
private const val MSG_UPDATE_TIME = 0

class MyWatchFace : CanvasWatchFaceService(){
    enum class AppColor(val hex: Int) {
        RED(0xF44336),
        GREEN(0x69F0AE),
        BLUE(0x2196F3),
        YELLOW(0xFFEB3B),
    }

    override fun onCreateEngine(): Engine {
        return Engine()
    }

    private class EngineHandler(reference: MyWatchFace.Engine) : Handler() {
        private val mWeakReference: WeakReference<MyWatchFace.Engine> = WeakReference(reference)

        override fun handleMessage(msg: Message) {
            val engine = mWeakReference.get()
            if (engine != null) {
                when (msg.what) {
                    MSG_UPDATE_TIME -> engine.handleUpdateTimeMessage()
                }
            }
        }
    }

    inner class Engine : CanvasWatchFaceService.Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private lateinit var mCalendar: Calendar
        private lateinit var preferences: SharedPreferences;

        private var mRegisteredTimeZoneReceiver = false
        private var mMuteMode: Boolean = false
        private var mCenterX: Float = 0F
        private var mCenterY: Float = 0F
        private var mWidth: Float = 0F
        private var mHeight: Float = 0F

        private lateinit var mHoursPaint: Paint
        private lateinit var mMinutesPaint: Paint
        private var mHoursPaintStyle: Paint.Style = Paint.Style.STROKE
        private var mMinutesPaintStyle: Paint.Style = Paint.Style.STROKE
        private var mHoursPaintColor: Int = AppColor.YELLOW.hex
        private var mMinutesPaintColor: Int = Color.WHITE

        private var mAmbient: Boolean = false
        private var mLowBitAmbient: Boolean = false
        private var mBurnInProtection: Boolean = false

        /* Handler to update the time once a second in interactive mode. */
        private val mUpdateTimeHandler = EngineHandler(this)

        private val mTimeZoneReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                mCalendar.timeZone = TimeZone.getDefault()
                invalidate()
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)

            setWatchFaceStyle(WatchFaceStyle.Builder(this@MyWatchFace)
                    .setAcceptsTapEvents(true)
                    .build())

            mCalendar = Calendar.getInstance()
            preferences = PreferenceManager.getDefaultSharedPreferences(baseContext);
            preferences.registerOnSharedPreferenceChangeListener(this);

            initializeWatchFace()
        }

        private fun initializeWatchFace() {
            /* Set defaults for colors */
            mHoursPaintStyle = if (preferences.getBoolean("hfill", false)) Paint.Style.FILL else Paint.Style.STROKE
            mMinutesPaintStyle = if (preferences.getBoolean("mfill", false)) Paint.Style.FILL else Paint.Style.STROKE
            mHoursPaintColor = Color.parseColor(preferences.getString("hcolor", "0xFFEB3B")?.replace("0x", "#"))
            mMinutesPaintColor = Color.parseColor(preferences.getString("mcolor", "0xFFFFFF")?.replace("0x", "#"))

            mHoursPaint = Paint().apply {
                color = mHoursPaintColor
                strokeWidth = 5f
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
                textSize = 190f
                typeface = resources.getFont(R.font.myfont)
                textAlign = Paint.Align.RIGHT
                style = mHoursPaintStyle
            }

            mMinutesPaint = Paint().apply {
                color = mMinutesPaintColor
                strokeWidth = 5f
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
                textSize = 190f
                typeface = resources.getFont(R.font.myfont)
                textAlign = Paint.Align.RIGHT
                style = mMinutesPaintStyle
            }
        }

        override fun onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            super.onDestroy()
        }

        override fun onPropertiesChanged(properties: Bundle) {
            super.onPropertiesChanged(properties)
            mLowBitAmbient = properties.getBoolean(
                    WatchFaceService.PROPERTY_LOW_BIT_AMBIENT, false)
            mBurnInProtection = properties.getBoolean(
                    WatchFaceService.PROPERTY_BURN_IN_PROTECTION, false)
        }

        override fun onTimeTick() {
            super.onTimeTick()
            invalidate()
        }

        override fun onAmbientModeChanged(inAmbientMode: Boolean) {
            super.onAmbientModeChanged(inAmbientMode)
            mAmbient = inAmbientMode

            updateWatchHandStyle()

            // Check and trigger whether or not timer should be running (only
            // in active mode).
            updateTimer()
        }

        private fun updateWatchHandStyle() {
            if (mAmbient) {
                mHoursPaint.style = Paint.Style.STROKE
                mMinutesPaint.style = Paint.Style.STROKE
            } else {
                mHoursPaint.style = mHoursPaintStyle
                mMinutesPaint.style = mMinutesPaintStyle
            }
        }

        override fun onInterruptionFilterChanged(interruptionFilter: Int) {
            super.onInterruptionFilterChanged(interruptionFilter)
            val inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE

            /* Dim display in mute mode. */
            if (mMuteMode != inMuteMode) {
                mMuteMode = inMuteMode
                mHoursPaint.alpha = if (inMuteMode) 100 else 255
                mMinutesPaint.alpha = if (inMuteMode) 100 else 255
                invalidate()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)

            /*
             * Find the coordinates of the center point on the screen, and ignore the window
             * insets, so that, on round watches with a "chin", the watch face is centered on the
             * entire screen, not just the usable portion.
             */
            mCenterX = width / 2f
            mCenterY = height / 2f
            mWidth = width.toFloat()
            mHeight = height.toFloat()
        }

        /**
         * Captures tap event (and tap type). The [WatchFaceService.TAP_TYPE_TAP] case can be
         * used for implementing specific logic to handle the gesture.
         */
        override fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {
            when (tapType) {
                WatchFaceService.TAP_TYPE_TOUCH -> {
                    // The user has started touching the screen.
                }
                WatchFaceService.TAP_TYPE_TOUCH_CANCEL -> {
                    // The user has started a different gesture or otherwise cancelled the tap.
                }
                WatchFaceService.TAP_TYPE_TAP -> {
                    // The user has completed the tap gesture.
                }
            }
            invalidate()
        }


        override fun onDraw(canvas: Canvas, bounds: Rect) {
            val now = System.currentTimeMillis()
            mCalendar.timeInMillis = now

            drawBackground(canvas)
            drawWatchFace(canvas)
        }

        private fun drawBackground(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
        }

        private fun drawWatchFace(canvas: Canvas) {
            var hours: String = SimpleDateFormat("HH").format(mCalendar.time)
            var minutes = SimpleDateFormat("mm").format(mCalendar.time)
            var bounds: Rect = Rect()
            mHoursPaint.getTextBounds(hours, 0, hours.length, bounds);
            canvas.drawText(hours, mWidth - 50, mCenterY - 10, mHoursPaint)

            canvas.drawText(minutes, mWidth - 50, mCenterY + 140, mMinutesPaint)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            if (visible) {
                registerReceiver()
                /* Update time zone in case it changed while we weren't visible. */
                mCalendar.timeZone = TimeZone.getDefault()
                invalidate()
            } else {
                unregisterReceiver()
            }

            /* Check and trigger whether or not timer should be running (only in active mode). */
            updateTimer()
        }

        private fun registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return
            }
            mRegisteredTimeZoneReceiver = true
            val filter = IntentFilter(Intent.ACTION_TIMEZONE_CHANGED)
            this@MyWatchFace.registerReceiver(mTimeZoneReceiver, filter)
        }

        private fun unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return
            }
            mRegisteredTimeZoneReceiver = false
            this@MyWatchFace.unregisterReceiver(mTimeZoneReceiver)
        }

        /**
         * Starts/stops the [.mUpdateTimeHandler] timer based on the state of the watch face.
         */
        private fun updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME)
            }
        }

        /**
         * Returns whether the [.mUpdateTimeHandler] timer should be running. The timer
         * should only run in active mode.
         */
        private fun shouldTimerBeRunning(): Boolean {
            return isVisible && !mAmbient
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        fun handleUpdateTimeMessage() {
            invalidate()
            if (shouldTimerBeRunning()) {
                val timeMs = System.currentTimeMillis()
                val delayMs = INTERACTIVE_UPDATE_RATE_MS - timeMs % INTERACTIVE_UPDATE_RATE_MS
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs)
            }
        }

        override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
            if(preferences != null) {
                mHoursPaintStyle = if (preferences.getBoolean("hfill", false)) Paint.Style.FILL else Paint.Style.STROKE
                mMinutesPaintStyle = if (preferences.getBoolean("mfill", false)) Paint.Style.FILL else Paint.Style.STROKE
                mHoursPaintColor = Color.parseColor(preferences.getString("hcolor", "0xFFEB3B")?.replace("0x", "#"))
                mMinutesPaintColor = Color.parseColor(preferences.getString("mcolor", "0xFFFFFF")?.replace("0x", "#"))

                mHoursPaint.style = mHoursPaintStyle
                mMinutesPaint.style = mMinutesPaintStyle
                mHoursPaint.color = mHoursPaintColor
                mMinutesPaint.color = mMinutesPaintColor

                invalidate()
            }
        }
    }


}


