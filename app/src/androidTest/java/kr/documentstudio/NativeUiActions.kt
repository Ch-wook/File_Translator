package kr.documentstudio

import android.graphics.Rect
import android.os.SystemClock
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import org.junit.Assert.assertTrue

/** Re-query accessibility after native/WebView layout changes; inject one real touch. */
internal object NativeUiActions {
    fun refresh() {if(Build.VERSION.SDK_INT>=33) InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()}

    fun tap(device: UiDevice, packageName: String, label: String, timeout: Long=20000) {
        tapStable(device,label,timeout) {
            device.findObject(By.pkg(packageName).text(label).enabled(true))
                ?: device.findObject(By.pkg(packageName).desc(label).enabled(true))
        }
    }
    fun tap(device: UiDevice, selector: BySelector, timeout: Long=20000) {
        tapStable(device,selector.toString(),timeout) {device.findObject(selector.enabled(true))}
    }
    private fun tapStable(device: UiDevice,label: String,timeout: Long,find: ()->UiObject2?) {
        device.waitForIdle(1000)
        val deadline=SystemClock.uptimeMillis()+timeout
        var previous: Rect?=null
        var stableSince=SystemClock.uptimeMillis()
        while(SystemClock.uptimeMillis()<deadline) {
            refresh()
            val bounds=try {find()?.visibleBounds?.takeIf {it.width()>=24 && it.height()>=24}}
                catch(_: StaleObjectException){null}
            if(bounds==null || bounds!=previous) {
                previous=bounds?.let {Rect(it)}
                stableSince=SystemClock.uptimeMillis()
            } else if(SystemClock.uptimeMillis()-stableSince>=300) {
                assertTrue("Inject the visible native control: $label",device.click(bounds.centerX(),bounds.centerY()))
                return
            }
            SystemClock.sleep(60)
        }
        throw AssertionError("Native control must be enabled and stop moving before touch: $label")
    }
}
