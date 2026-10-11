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

class LarkEPDController : EPDInterface {

    private var resetRunnable: Runnable? = null
    private var resetView: View? = null

    companion object {
        private const val TAG = "EPD"

        // EPD waveform modes (Allwinner sunxi, same table as SunxiEPDController)
        private const val MODE_GC16 = 0x04
        private const val MODE_A2 = 0x10
        private const val MODE_GLR16 = 0x40
        private const val MODE_GU16 = 0x84 // == GC16_LOCAL(132) on this firmware

        // Clean global state: strategy MORE_FRAME_WITHOUT_FLIP + GU16 everywhere.
        // The stock InkReader fullRefresh leaves exactly (1, 132, 132, 132) behind.
        private const val CLEAN_STRATEGY = 1
        private const val CLEAN_MODE = 132

        private const val RESET_DELAY_MS = 150L

        @Volatile private var reflectionReady = false
        private var setStrategyFn: Method? = null

        @SuppressLint("BlockedPrivateApi")
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

        private fun setCleanState(): Boolean =
            setEinkUpdateStrategy(CLEAN_STRATEGY, CLEAN_MODE)
    }

    override fun getPlatform(): String = "sunxi"
    override fun getMode(): String = "all"

    override fun getWaveformFull(): Int = MODE_GC16
    override fun getWaveformPartial(): Int = MODE_GU16
    override fun getWaveformFullUi(): Int = MODE_GLR16
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
        val rawMode = when (mode) {
            getWaveformFull() -> MODE_GC16
            getWaveformFullUi() -> MODE_GLR16
            getWaveformFast() -> MODE_A2
            else -> MODE_GU16
        }

        if (setEinkUpdateStrategy(CLEAN_STRATEGY, rawMode)) {
            // setEinkUpdateStrategy writes a persistent global policy, so reset
            // to the clean state shortly after the refresh to avoid leaving the
            // display in a flashing mode.
            resetRunnable?.let { runnable ->
                resetView?.removeCallbacks(runnable)
            }
            resetView = targetView
            val runnable = Runnable {
                setCleanState()
            }
            resetRunnable = runnable
            targetView.postDelayed(runnable, RESET_DELAY_MS)
        }
    }

    override fun resume() {
        // Wipe the dirty state left behind by sleep/wake or other apps.
        setCleanState()
    }

    override fun pause() {}
}
