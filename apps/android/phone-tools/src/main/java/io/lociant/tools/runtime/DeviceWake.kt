package io.lociant.tools.runtime

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import io.lociant.tools.LociantAccessibilityService
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A short, verified wake request. The wake lock always has a system-enforced timeout. */
object DeviceWake {
    private const val WAKE_TIMEOUT_MS = 3_000L
    private const val DISMISS_TIMEOUT_MS = 5_000L
    private const val GESTURE_TIMEOUT_MS = 2_000L
    private const val REQUEST_ID = "io.lociant.tools.DEVICE_WAKE_REQUEST_ID"
    private val pendingDismissals = ConcurrentHashMap<String, CompletableFuture<String>>()
    private val requestLock = Any()
    private var heldWakeLock: PowerManager.WakeLock? = null

    fun wake(context: Context, dismissKeyguard: Boolean, holdMs: Long): JSONObject {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return failure("main_thread_unavailable", "device_wake must run on a device tool worker thread.", context)
        }
        synchronized(requestLock) {
            val power = context.getSystemService(PowerManager::class.java)
                ?: return failure("power_service_unavailable", "Android power service is unavailable.", context)
            val keyguard = context.getSystemService(KeyguardManager::class.java)
                ?: return failure("keyguard_service_unavailable", "Android keyguard service is unavailable.", context)
            val before = DeviceInteraction.snapshot(context)
            val wasScreenOn = before.optBoolean("screenOn", false)
            try {
                holdScreenOn(power, wasScreenOn, holdMs)
            } catch (error: SecurityException) {
                return failure("wake_permission_denied", "Android denied the WAKE_LOCK permission.", context, before)
            } catch (error: RuntimeException) {
                return failure("wake_lock_rejected", error.message ?: "Android rejected the wake lock.", context, before)
            }
            if (!waitFor(WAKE_TIMEOUT_MS) {
                    power.isInteractive && DeviceInteraction.snapshot(context).optBoolean("screenOn", false)
                }) {
                return failure("screen_wake_timeout", "Android did not report a lit, interactive screen after the wake request.", context, before)
            }

            var unlockMethod = "not_needed"
            var activityResult = "not_attempted"
            var gestureAccepted = false
            if (dismissKeyguard && keyguard.isKeyguardLocked) {
                if (keyguard.isDeviceSecure) {
                    return failure("secure_keyguard", "A PIN, password, or pattern must be entered by the user.", context, before)
                }
                activityResult = requestKeyguardDismissal(context)
                if (waitFor(DISMISS_TIMEOUT_MS) { !keyguard.isKeyguardLocked }) {
                    unlockMethod = "keyguard_manager"
                } else {
                    // An OEM can block a background Activity even when startActivity returned.
                    // Only try gestures after the display is visibly awake, and verify the lock state.
                    val accessibility = LociantAccessibilityService.instance
                    if (accessibility != null) {
                        val metrics = context.resources.displayMetrics
                        val x = metrics.widthPixels / 2
                        val startY = metrics.heightPixels * 4 / 5
                        val endY = metrics.heightPixels / 4
                        gestureAccepted = accessibility.gestureSwipe(x, startY, x, endY, 350L)
                            .optBoolean("accepted", false)
                        if (gestureAccepted && waitFor(GESTURE_TIMEOUT_MS) { !keyguard.isKeyguardLocked }) {
                            unlockMethod = "accessibility_swipe"
                        } else if (keyguard.isKeyguardLocked) {
                            gestureAccepted = accessibility.gestureTwoFingerSwipe(
                                metrics.widthPixels * 2 / 5, metrics.widthPixels * 3 / 5,
                                startY, endY, 450L,
                            ).optBoolean("accepted", false)
                            if (gestureAccepted && waitFor(GESTURE_TIMEOUT_MS) { !keyguard.isKeyguardLocked }) {
                                unlockMethod = "accessibility_two_finger_swipe"
                            }
                        }
                    }
                }
            }

            val after = DeviceInteraction.snapshot(context)
            val unlocked = !after.optBoolean("keyguardLocked", true)
            val success = after.optBoolean("screenOn", false) && (!dismissKeyguard || unlocked)
            val code = when {
                success -> "verified"
                !after.optBoolean("screenOn", false) -> "screen_off_after_request"
                activityResult == "activity_not_resumed_or_timed_out" && !gestureAccepted -> "background_activity_blocked"
                else -> "keyguard_still_locked"
            }
            if (!success) releaseWakeLock()
            return JSONObject()
                .put("ok", success)
                .put("action", "device_wake")
                .put("code", code)
                .put("message", if (success) "Screen state verified after wake request." else "Android did not leave the screen on and keyguard dismissed; inspect the observed states and activity result.")
                .put("before", before)
                .put("after", after)
                .put("wakeMethod", if (wasScreenOn) "already_on" else "timed_wake_lock")
                .put("unlockMethod", unlockMethod)
                .put("activityResult", activityResult)
                .put("gestureAccepted", gestureAccepted)
                .put("holdMs", holdMs)
        }
    }

    private fun requestKeyguardDismissal(context: Context): String {
        val id = UUID.randomUUID().toString()
        val future = CompletableFuture<String>()
        pendingDismissals[id] = future
        return try {
            context.startActivity(Intent(context, DeviceWakeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                putExtra(REQUEST_ID, id)
            })
            try {
                future.get(DISMISS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                "activity_not_resumed_or_timed_out"
            }
        } catch (error: SecurityException) {
            "activity_start_denied"
        } catch (error: RuntimeException) {
            "activity_start_failed:${error.javaClass.simpleName}"
        } finally {
            pendingDismissals.remove(id)
        }
    }

    internal fun requestId(intent: Intent?): String = intent?.getStringExtra(REQUEST_ID).orEmpty()

    internal fun complete(id: String, result: String) {
        pendingDismissals[id]?.complete(result)
    }

    @Suppress("DEPRECATION")
    private fun holdScreenOn(power: PowerManager, alreadyOn: Boolean, holdMs: Long) {
        releaseWakeLock()
        val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            (if (alreadyOn) 0 else PowerManager.ACQUIRE_CAUSES_WAKEUP)
        heldWakeLock = power.newWakeLock(flags, "Lociant:device_wake").apply {
            setReferenceCounted(false)
            acquire(holdMs)
        }
    }

    private fun releaseWakeLock() {
        heldWakeLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
        heldWakeLock = null
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(100L)
        }
        return condition()
    }

    private fun failure(code: String, message: String, context: Context, before: JSONObject? = null): JSONObject {
        releaseWakeLock()
        return JSONObject()
            .put("ok", false)
            .put("action", "device_wake")
            .put("code", code)
            .put("message", message)
            .put("before", before ?: DeviceInteraction.snapshot(context))
            .put("after", DeviceInteraction.snapshot(context))
    }
}

/** Only visible while asking Android to dismiss an ordinary, non-secure keyguard. */
class DeviceWakeActivity : Activity() {
    private var requestId = ""
    private var requestedDismissal = false
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = DeviceWake.requestId(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        if (requestedDismissal) return
        requestedDismissal = true
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null) {
            complete("keyguard_service_unavailable")
        } else if (!keyguard.isKeyguardLocked) {
            complete("already_unlocked")
        } else {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = complete("dismissed")
                override fun onDismissCancelled() = complete("dismiss_cancelled")
                override fun onDismissError() = complete("dismiss_error")
            })
        }
    }

    private fun complete(result: String) {
        if (completed) return
        completed = true
        DeviceWake.complete(requestId, result)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        if (!completed) DeviceWake.complete(requestId, "activity_destroyed")
        super.onDestroy()
    }
}
