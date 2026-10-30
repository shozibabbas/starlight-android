package dev.starlight.charge

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs

/**
 * Gesture navigation for third-party launchers on HyperOS, which only runs its own
 * gestures with the Xiaomi launcher. Thin invisible strips along the screen edges turn
 * swipes into system actions, with visual feedback drawn on a separate layer:
 *  - swipe up from the bottom edge: Home
 *  - swipe up and hold, or a slow swipe released past mid-screen: Recents
 *  - swipe sideways along the bottom edge: previous (right) / next (left) app
 *  - swipe inwards from the left or right edge: Back
 * The only thing read from the screen is the package name of the app in front, which
 * keeps the most-recent-apps order for switching.
 */
class GestureService : AccessibilityService() {

    private lateinit var windows: WindowManager
    private lateinit var feedback: GestureFeedbackView
    private val strips = mutableListOf<View>()
    private val handler = Handler(Looper.getMainLooper())
    private val density by lazy { resources.displayMetrics.density }
    private var touchable = true

    // Most recently used launchable apps, newest first.
    private val recent = ArrayDeque<String>()
    private var onLauncher = true
    private val launchable = HashMap<String, Boolean>()
    private var switchList: List<String> = emptyList()
    private var switchIndex = 0
    private var lastSwitchAt = 0L

    // The strips only take touches while unlocked and in gesture navigation mode, so they
    // never get in the way of the lock screen or the 3-button bar.
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateTouchable()
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> waitForLock()
                // Fallback only: HyperOS sends this after its own unlock animation.
                Intent.ACTION_USER_PRESENT -> onUnlocked("user_present")
            }
        }
    }

    // ---- unlock reveal ---------------------------------------------------------------
    // HyperOS unlocks by fingerprint from the always-on display and only turns the screen
    // fully on ~175 ms afterwards, so screen-on is too late to start watching. Instead:
    //   screen off -> wait until the phone is actually locked (it may lock after a delay)
    //   locked     -> pre-arm the overlay and check the lock state every ~33 ms; these
    //                 checks pause whenever the CPU sleeps, so they cost almost nothing
    //   unlocked   -> play the reveal (if unlocking to the home screen) and go idle.

    private var reveal: UnlockRevealView? = null
    private var revealPlaying = false
    private var lockedAt = 0L
    // True until the phone is actually locked, so going home never triggers the reveal.
    private var playedThisUnlock = true
    @Volatile private var sawDeviceLocked = false

    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }

    private val lockPoll = object : Runnable {
        override fun run() {
            if (keyguard.isKeyguardLocked) startWatchingUnlock()
            else if (!isScreenOn()) handler.postDelayed(this, 250)
            // Screen back on and still unlocked: nothing to reveal.
        }
    }

    // isDeviceLocked turns false the moment the fingerprint/face/PIN is accepted, before
    // HyperOS starts its unlock animation; isKeyguardLocked only turns false once that
    // animation has finished (200-400 ms later), which is too late.
    // The checks run on their own high-priority thread: the main thread is busy with
    // window events at exactly the moment of unlocking, which delayed them.
    private val pollThread = android.os.HandlerThread("starlight-unlock",
        android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val pollHandler = Handler(pollThread.looper)

    private val unlockPoll = object : Runnable {
        override fun run() {
            val deviceLocked = keyguard.isDeviceLocked
            if (deviceLocked && !sawDeviceLocked) {
                sawDeviceLocked = true
                releaseLockWake()
            }
            val source = when {
                // Only after seeing it locked: Smart Lock can leave the device "unlocked"
                // while the lock screen is still showing.
                sawDeviceLocked && !deviceLocked -> "authenticated"
                !keyguard.isKeyguardLocked -> "keyguard_gone"
                else -> null
            }
            if (source == null) {
                pollHandler.postDelayed(this, UNLOCK_POLL_MS)
            } else {
                // Late if HyperOS has already finished its unlock (lock screen gone): the
                // home screen has been showing for a while, so play the softer burst.
                val late = !keyguard.isKeyguardLocked
                handler.postAtFrontOfQueue { onUnlocked(source, late) }
            }
        }
    }

    private fun isScreenOn() =
        getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)?.state == android.view.Display.STATE_ON

    // HyperOS marks the device locked ~0.2-0.5 s after the screen goes off, often after the
    // CPU has already gone to sleep, so the checks could miss it and fall back to the slow
    // signal. Stay awake briefly after screen-off until the locked state has been seen.
    private val lockWake by lazy {
        getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "starlight:see-lock")
            .apply { setReferenceCounted(false) }
    }

    private fun releaseLockWake() {
        if (lockWake.isHeld) runCatching { lockWake.release() }
    }

    private fun waitForLock() {
        if (!Prefs.unlockReveal(this)) return
        lockWake.acquire(LOCK_WAKE_MS)
        handler.removeCallbacks(lockPoll)
        pollHandler.removeCallbacks(unlockPoll)
        handler.post(lockPoll)
    }

    private fun startWatchingUnlock() {
        if (!Prefs.unlockReveal(this)) return
        playedThisUnlock = false
        sawDeviceLocked = false
        lockedAt = SystemClock.uptimeMillis()
        armReveal()
        pollHandler.removeCallbacks(unlockPoll)
        pollHandler.post(unlockPoll)
    }

    private fun onUnlocked(source: String, late: Boolean = true) {
        pollHandler.removeCallbacks(unlockPoll)
        releaseLockWake()
        if (playedThisUnlock) return
        playedThisUnlock = true
        Log.d(TAG, "unlock via $source, late=$late, screen ${if (isScreenOn()) "on" else "not yet on"}, home=$onLauncher")
        // Only when unlocking straight to the home screen.
        if (onLauncher && Prefs.unlockReveal(this)) showReveal(soft = late) else disarmReveal()
    }

    /** Adds the overlay window now (drawing nothing), so starting the reveal costs no time. */
    private fun armReveal() {
        if (reveal != null || !Prefs.unlockReveal(this)) return
        // Visible but transparent until it plays: an invisible window has no surface, and
        // creating one at unlock is too slow.
        val view = UnlockRevealView(this)
        val params = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            Gravity.TOP or Gravity.START, touch = false)
        runCatching { windows.addView(view, params) }.onSuccess { reveal = view }
    }

    private fun disarmReveal() {
        if (revealPlaying) return
        reveal?.let { runCatching { windows.removeView(it) } }
        reveal = null
    }

    /** Plays the unlock reveal over everything; it never takes touches. */
    fun showReveal(soft: Boolean = false) {
        if (revealPlaying) return
        armReveal()
        val view = reveal ?: return
        revealPlaying = true
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        view.play(soft) {
            revealPlaying = false
            disarmReveal()
        }
    }

    private val navObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            updateTouchable()
            handler.removeCallbacks(keepGestures)
            handler.postDelayed(keepGestures, KEEP_DELAY_MS)
        }
    }

    // HyperOS switches back to the 3-button bar whenever it re-checks the launcher and finds a
    // third-party one. If the user chose "keep gestures" (and Starlight may write settings),
    // switch straight back.
    private val keepGestures = Runnable {
        if (!Prefs.keepGestures(this)) return@Runnable
        if (Settings.Global.getInt(contentResolver, MIUI_GESTURES, 0) == 1) return@Runnable
        runCatching { Settings.Global.putInt(contentResolver, MIUI_GESTURES, 1) }
            .onSuccess { Log.d(TAG, "restored gesture navigation") }
            .onFailure { Log.d(TAG, "cannot restore gestures: ${it.message}") }
    }

    override fun onServiceConnected() {
        instance = this
        windows = getSystemService(WindowManager::class.java)
        addFeedbackLayer()
        addStrips()
        registerReceiver(stateReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        contentResolver.registerContentObserver(Settings.Secure.getUriFor(NAV_MODE), false, navObserver)
        contentResolver.registerContentObserver(Settings.Global.getUriFor(MIUI_GESTURES), false, navObserver)
        updateTouchable()
        handler.postDelayed(keepGestures, KEEP_DELAY_MS)
        if (keyguard.isKeyguardLocked) startWatchingUnlock()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        removeStrips()
        addStrips()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        pollHandler.removeCallbacks(unlockPoll)
        releaseLockWake()
        pollThread.quitSafely()
        handler.removeCallbacks(lockPoll)
        reveal?.let { runCatching { windows.removeView(it) } }
        runCatching { unregisterReceiver(stateReceiver) }
        contentResolver.unregisterContentObserver(navObserver)
        removeStrips()
        runCatching { windows.removeView(feedback) }
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in launchers()) {
            onLauncher = true
            // The home screen taking focus right after unlocking is another early signal.
            if (!playedThisUnlock && !keyguard.isKeyguardLocked) onUnlocked("window")
            return
        }
        if (!isLaunchable(pkg)) return
        onLauncher = false
        foreground = pkg
        // While switching, keep the order fixed so repeated swipes walk through it; the app
        // you land on moves to the front once the switching pause ends (see settleOrder).
        if (SystemClock.uptimeMillis() - lastSwitchAt < SWITCH_SESSION_MS) return
        bringToFront(pkg)
    }

    private var foreground: String? = null

    private fun bringToFront(pkg: String) {
        recent.remove(pkg)
        recent.addFirst(pkg)
        while (recent.size > 12) recent.removeLast()
    }

    /** After a switching session, the app in front becomes the newest again. */
    private fun settleOrder() {
        if (!onLauncher) foreground?.let { bringToFront(it) }
    }

    // ---- touch strips -------------------------------------------------------------

    private fun updateTouchable() {
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val gestures = Settings.Secure.getInt(contentResolver, NAV_MODE, 0) == 2
        val want = gestures && !locked
        if (want == touchable) return
        touchable = want
        for (view in strips) {
            val params = view.layoutParams as WindowManager.LayoutParams
            params.flags = if (want) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { windows.updateViewLayout(view, params) }
        }
    }

    private fun addFeedbackLayer() {
        feedback = GestureFeedbackView(this).apply { visibility = View.GONE }
        val params = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            Gravity.TOP or Gravity.START, touch = false)
        windows.addView(feedback, params)
    }

    private fun addStrips() {
        val edgeHeight = (resources.displayMetrics.heightPixels * 0.62f).toInt()
        addStrip(bottomStrip(), WindowManager.LayoutParams.MATCH_PARENT, px(BOTTOM_DP), Gravity.BOTTOM)
        addStrip(sideStrip(fromLeft = true), px(EDGE_DP), edgeHeight, Gravity.START or Gravity.CENTER_VERTICAL)
        addStrip(sideStrip(fromLeft = false), px(EDGE_DP), edgeHeight, Gravity.END or Gravity.CENTER_VERTICAL)
    }

    private fun addStrip(view: View, width: Int, height: Int, gravity: Int) {
        windows.addView(view, overlayParams(width, height, gravity, touch = touchable))
        strips += view
    }

    private fun overlayParams(width: Int, height: Int, gravity: Int, touch: Boolean) =
        WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (touch) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0 // cover the navigation area itself
            title = "Starlight gestures"
        }

    private fun removeStrips() {
        strips.forEach { runCatching { windows.removeView(it) } }
        strips.clear()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bottomStrip() = View(this).apply {
        var startX = 0f
        var startY = 0f
        var peak = 0f
        var handled = false
        var armedRecents = false
        var switchDir = 0
        var switchIcon: Drawable? = null
        var velocity: VelocityTracker? = null
        val screenH = resources.displayMetrics.heightPixels

        // Fires when the finger has paused after travelling up: open recents right away.
        val hold = Runnable {
            if (!handled && peak >= dp(RECENTS_HOLD_DP)) {
                handled = true
                armedRecents = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                feedback.hide()
                performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
        }

        setOnTouchListener { v, e ->
            val up = startY - e.rawY
            val dx = e.rawX - startX
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY; peak = 0f
                    handled = false; armedRecents = false; switchDir = 0; switchIcon = null
                    velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                }
                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(e)
                    if (handled) return@setOnTouchListener true
                    if (abs(dx) > dp(SWITCH_DP) / 2 && abs(dx) > abs(up) * 1.5f) {
                        // Sideways along the bottom: app switch preview.
                        val dir = if (dx > 0) 1 else -1
                        if (dir != switchDir) {
                            switchDir = dir
                            switchIcon = switchTarget(dir)?.let { iconOf(it) }
                            if (switchIcon != null) v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        handler.removeCallbacks(hold)
                        if (switchIcon != null) feedback.showSwitch(dir, abs(dx) / dp(SWITCH_DP * 2), switchIcon)
                        else feedback.hide()
                    } else {
                        switchDir = 0
                        if (up > peak + dp(3)) {
                            peak = up
                            handler.removeCallbacks(hold)
                            handler.postDelayed(hold, HOLD_MS)
                        }
                        if (up > dp(8)) feedback.showHome(e.rawX, e.rawY, up / dp(SWIPE_DP * 2), false)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(hold)
                    velocity?.addMovement(e)
                    velocity?.computeCurrentVelocity(1000)
                    val vy = -(velocity?.yVelocity ?: 0f)
                    velocity?.recycle(); velocity = null
                    feedback.hide()
                    if (handled || e.actionMasked == MotionEvent.ACTION_CANCEL) return@setOnTouchListener true
                    // A quick flick may lift before any sideways move was seen; decide from the end point.
                    if (switchDir == 0 && abs(dx) >= dp(SWITCH_DP) && abs(dx) > abs(up) * 1.5f) {
                        switchDir = if (dx > 0) 1 else -1
                    }
                    when {
                        switchDir != 0 && abs(dx) >= dp(SWITCH_DP) -> {
                            if (quickSwitch(switchDir)) v.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                        }
                        up >= dp(SWIPE_DP) && up > abs(dx) -> {
                            v.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                            // A slow swipe released past mid-screen opens recents, like HyperOS.
                            val slow = vy < dp(SLOW_FLING_DP)
                            performGlobalAction(
                                if (slow && up > screenH * 0.33f) GLOBAL_ACTION_RECENTS else GLOBAL_ACTION_HOME)
                        }
                    }
                }
            }
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun sideStrip(fromLeft: Boolean) = View(this).apply {
        var startX = 0f
        var startY = 0f
        var armed = false
        setOnTouchListener { v, e ->
            val inward = if (fromLeft) e.rawX - startX else startX - e.rawX
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = e.rawX; startY = e.rawY; armed = false }
                MotionEvent.ACTION_MOVE -> {
                    val nowArmed = inward >= dp(SWIPE_DP)
                    if (nowArmed && !armed) v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    armed = nowArmed
                    if (inward > dp(4)) feedback.showBack(fromLeft, e.rawY, inward / dp(SWIPE_DP), armed)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    feedback.hide()
                    if (e.actionMasked == MotionEvent.ACTION_UP &&
                        inward >= dp(SWIPE_DP) && inward > abs(e.rawY - startY)) {
                        v.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                }
            }
            true
        }
    }

    // ---- quick switch ---------------------------------------------------------------

    /** The app a switch in [dir] would open: +1 = previous (swipe right), -1 = next. */
    private fun switchTarget(dir: Int): String? {
        val session = SystemClock.uptimeMillis() - lastSwitchAt < SWITCH_SESSION_MS
        val (list, index) = if (session) switchList to switchIndex else freshOrder()
        return list.getOrNull(index + dir)
    }

    private fun quickSwitch(dir: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastSwitchAt >= SWITCH_SESSION_MS) {
            val (list, index) = freshOrder()
            switchList = list
            switchIndex = index
        }
        val target = switchIndex + dir
        Log.d(TAG, "switch dir=$dir index=$switchIndex onLauncher=$onLauncher list=$switchList")
        val pkg = switchList.getOrNull(target) ?: return false
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val options = if (dir > 0) ActivityOptions.makeCustomAnimation(this, R.anim.in_from_left, R.anim.out_to_right)
        else ActivityOptions.makeCustomAnimation(this, R.anim.in_from_right, R.anim.out_to_left)
        return runCatching { startActivity(intent, options.toBundle()) }.isSuccess.also { ok ->
            if (ok) { switchIndex = target; lastSwitchAt = now }
        }
    }

    /**
     * Recent apps newest first, plus the index of the app in front (-1 on the home screen).
     * Comes from Android's usage history when Starlight has usage access, so it survives
     * service restarts; otherwise from the window changes seen since the service started.
     */
    private fun freshOrder(): Pair<List<String>, Int> {
        val usage = usageOrder()
        if (usage != null) {
            val (apps, homeInFront) = usage
            return apps to if (homeInFront) -1 else 0
        }
        settleOrder()
        return recent.toList() to if (onLauncher) -1 else 0
    }

    private fun usageOrder(): Pair<List<String>, Boolean>? {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = runCatching { usm.queryEvents(now - 6 * 3_600_000L, now) }.getOrNull() ?: return null
        val lastResumed = LinkedHashMap<String, Long>()
        val event = UsageEvents.Event()
        var newest: String? = null
        var newestAt = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            lastResumed[event.packageName] = event.timeStamp
            if (event.timeStamp >= newestAt) { newestAt = event.timeStamp; newest = event.packageName }
        }
        if (lastResumed.isEmpty()) return null // no usage access, or no history yet
        val apps = lastResumed.entries.sortedByDescending { it.value }.map { it.key }
            .filter { it !in launchers() && isLaunchable(it) }.take(12)
        return apps to (newest in launchers())
    }

    private fun isLaunchable(pkg: String) = launchable.getOrPut(pkg) {
        pkg != packageName && pkg != "com.android.systemui" &&
            pkg !in inputMethods() && packageManager.getLaunchIntentForPackage(pkg) != null
    }

    private fun iconOf(pkg: String): Drawable? = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()

    // Settings registers a fallback home screen; it is an app, not a launcher.
    private fun launchers(): Set<String> = cachedLaunchers ?: packageManager
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        .map { it.activityInfo.packageName }.filter { it != "com.android.settings" }.toSet()
        .also { cachedLaunchers = it }
    private var cachedLaunchers: Set<String>? = null

    private fun inputMethods(): Set<String> = cachedInputs ?: getSystemService(InputMethodManager::class.java)
        .inputMethodList.map { it.packageName }.toSet().also { cachedInputs = it }
    private var cachedInputs: Set<String>? = null

    private fun dp(value: Int) = value * density
    private fun px(value: Int) = (value * density).toInt()

    companion object {
        /** The running service, so the setup screen can preview the unlock reveal. */
        var instance: GestureService? = null
            private set

        private const val TAG = "StarlightGestures"
        private const val NAV_MODE = "navigation_mode" // 0 = 3-button, 2 = gestures
        private const val MIUI_GESTURES = "force_fsg_nav_bar" // HyperOS's own gesture switch (global)
        private const val KEEP_DELAY_MS = 1500L
        private const val UNLOCK_POLL_MS = 16L
        private const val LOCK_WAKE_MS = 3000L
        private const val BOTTOM_DP = 18
        private const val EDGE_DP = 14
        private const val SWIPE_DP = 48
        private const val SWITCH_DP = 70
        private const val RECENTS_HOLD_DP = 90
        private const val SLOW_FLING_DP = 900 // dp per second
        private const val HOLD_MS = 260L
        private const val SWITCH_SESSION_MS = 3500L
    }
}
