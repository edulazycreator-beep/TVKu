package com.tvku.app

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * End-to-end smoke test on a real Android runtime with real network:
 * launch -> onboarding -> catalog on Home -> open an official YouTube live channel.
 * Screenshots are written to the app's external files dir and collected by CI.
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val shots = File(context.getExternalFilesDir(null), "smoke").apply { mkdirs() }

    private fun shot(name: String) {
        device.takeScreenshot(File(shots, "$name.png"))
    }

    private fun visibleTexts(): List<String> =
        device.findObjects(By.text(Pattern.compile("(?s).+"))).mapNotNull { runCatching { it.text }.getOrNull() }

    private fun clickText(text: String, timeoutMs: Long = 10_000): Boolean {
        val obj = device.wait(Until.findObject(By.text(text)), timeoutMs) ?: return false
        obj.click()
        return true
    }

    @Test
    fun onboardingCatalogAndPlayer() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 20_000)
        SystemClock.sleep(1_500)
        shot("01-onboarding")

        repeat(4) {
            if (!clickText("Continue")) fail("onboarding 'Continue' not found. Visible: ${visibleTexts()}")
            SystemClock.sleep(700)
        }
        shot("02-privacy")
        if (!clickText("Start watching")) fail("'Start watching' not found. Visible: ${visibleTexts()}")

        val homeLoaded = device.wait(Until.hasObject(By.text("KompasTV")), 45_000)
        SystemClock.sleep(1_000)
        shot("03-home")
        Log.i(TAG, "home texts: ${visibleTexts()}")
        if (!homeLoaded) fail("Home did not show KompasTV. Visible: ${visibleTexts()}")

        clickText("KompasTV")
        SystemClock.sleep(8_000)
        shot("04-player-8s")
        SystemClock.sleep(30_000)
        shot("05-player-38s")
        val playerTexts = visibleTexts()
        Log.i(TAG, "player texts: $playerTexts")
        if (playerTexts.any { it.contains("Channel not available") }) fail("player could not find the channel: $playerTexts")
    }

    private companion object {
        const val TAG = "TVKuSmoke"
    }
}
