/* Tested on P60Q (86G lark-eink-perf1_86B, Allwinner sun8iw10p1, Android 4.4).
 *
 * Unlike Moaan W7 (ViewRootImpl.setRefreshMode), this firmware exposes a global
 * refresh policy through a static method on android.view.View:
 *
 *   View.setEinkUpdateStrategy(int strategy, int start, int middle, int end)
 *
 * It reaches SurfaceFlinger through Binder and applies globally. The strategy
 * and mode values were verified against the stock InkReader app:
 *   - strategy 1 (MORE_FRAME_WITHOUT_FLIP) with modes 132 (GC16_LOCAL/GU16)
 *     is the clean state InkReader leaves behind after a full refresh.
 *   - dirty state observed as strategy 3 with modes containing 4 (GC16).
 */

package org.koreader.launcher.device.epd

import android.annotation.SuppressLint
import android.util.Log
import android.view.View
import java.lang.reflect.Method
import org.koreader.launcher.device.EPDInterface

class LarkEPDController internal constructor(
    private val writeStrategy: (Int, Int) -> Boolean
) : EPDInterface {

    constructor() : this(::setEinkUpdateStrategy)

    // JNI refresh requests and View callbacks can run on different threads.
    private val strategyLock = Any()
    private var paused = false
    private var resetRunnable: Runnable? = null
    private var resetView: View? = null

    companion object {
        private const val TAG = "EPD"

        // Modes exposed by the P60Q firmware. W7's GLR16=0x40 is unverified here.
        private const val MODE_GC16 = 0x04
        private const val MODE_A2 = 0x10
        private const val MODE_GU16 = 0x84 // == GC16_LOCAL(132) on this firmware

        // Clean global state: strategy MORE_FRAME_WITHOUT_FLIP + GU16 everywhere.
        // The stock InkReader fullRefresh leaves exactly (1, 132, 132, 132) behind.
        private const val CLEAN_STRATEGY = 1
        private const val CLEAN_MODE = MODE_GU16

        private const val RESET_DELAY_MS = 150L

        @Volatile private var reflectionReady = false
        private var setStrategyFn: Method? = null

        @SuppressLint("BlockedPrivateApi")
        @Synchronized
        private fun initReflection() {
            if (reflectionReady) return
            try {
                val method = View::class.java.getDeclaredMethod(
                    "setEinkUpdateStrategy",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType)
                method.isAccessible = true
                setStrategyFn = method
            } catch (e: Exception) {
                Log.w(TAG, "Lark EPD: reflection path unavailable: $e")
            }
            reflectionReady = true
        }

        private fun setEinkUpdateStrategy(strategy: Int, mode: Int): Boolean {
            initReflection()
            val fn = setStrategyFn ?: return false
            return try {
                fn.invoke(null, strategy, mode, mode, mode)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Lark EPD: setEinkUpdateStrategy failed: $e")
                false
            }
        }
    }

    private fun setCleanState(): Boolean = writeStrategy(CLEAN_STRATEGY, CLEAN_MODE)

    // Call only while holding strategyLock. Invalidate before removing from the
    // View queue, since a callback may already be waiting to acquire the lock.
    private fun cancelResetLocked() {
        val runnable = resetRunnable
        val view = resetView
        resetRunnable = null
        resetView = null
        if (runnable != null) view?.removeCallbacks(runnable)
    }

    override fun getPlatform(): String = "sunxi"
    override fun getMode(): String = "all"

    override fun getWaveformFull(): Int = MODE_GC16
    override fun getWaveformPartial(): Int = MODE_GU16
    // First UI candidate: use the known GC16 mode; still requires device testing.
    override fun getWaveformFullUi(): Int = MODE_GC16
    override fun getWaveformPartialUi(): Int = MODE_GU16
    override fun getWaveformFast(): Int = MODE_A2

    override fun getWaveformDelay(): Int = 0
    override fun getWaveformDelayUi(): Int = 0
    override fun getWaveformDelayFast(): Int = 0

    override fun needsView(): Boolean = false

    override fun setEpdMode(
        targetView: View,
        mode: Int, delay: Long,
        x: Int, y: Int, width: Int, height: Int, epdMode: String?
    ) {
        val rawMode = when {
            epdMode == "EPD_FULL" || mode == MODE_GC16 -> MODE_GC16 // full / fullUi
            mode == MODE_A2 -> MODE_A2 // fast
            else -> MODE_GU16
        }

        synchronized(strategyLock) {
            // Do not take global policy ownership back after another app resumes.
            if (paused) return
            cancelResetLocked()
            if (!writeStrategy(CLEAN_STRATEGY, rawMode) || rawMode == CLEAN_MODE) return

            // setEinkUpdateStrategy writes a persistent global policy, so reset
            // temporary GC16/A2 modes after 150 ms. Callback identity and the
            // same lock prevent an old reset from overwriting a newer request.
            val runnable = object : Runnable {
                override fun run() {
                    synchronized(strategyLock) {
                        if (paused || resetRunnable !== this) return
                        resetRunnable = null
                        resetView = null
                        setCleanState()
                    }
                }
            }
            resetView = targetView
            resetRunnable = runnable
            if (!targetView.postDelayed(runnable, RESET_DELAY_MS)) {
                // A rejected callback must not leave a temporary global mode set.
                cancelResetLocked()
                setCleanState()
            }
        }
    }

    override fun resume() {
        // Wipe the dirty state left behind by sleep/wake or other apps.
        synchronized(strategyLock) {
            cancelResetLocked()
            paused = false
            setCleanState()
        }
    }

    override fun pause() {
        synchronized(strategyLock) {
            paused = true
            cancelResetLocked()
        }
    }
}
