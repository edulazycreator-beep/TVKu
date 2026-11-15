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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
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

    /** Screenshots go to /data/local/tmp (shell-owned) so they survive the app being uninstalled after the run. */
    private fun shot(name: String) {
        device.executeShellCommand("mkdir -p $SHOTS")
        device.executeShellCommand("screencap -p $SHOTS/$name.png")
    }

    @get:Rule
    val watcher = object : TestWatcher() {
        override fun failed(e: Throwable, description: Description) {
            Log.e(TAG, "SMOKE FAILED: ${e.message}", e)
            runCatching { shot("99-failure") }
        }
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

        // Home: a verified broadcaster stream (if the catalog has any) and an official YouTube channel.
        val homeLoaded = device.wait(Until.hasObject(By.res("channel-stream")), 45_000) ||
            device.wait(Until.hasObject(By.res("channel-youtube")), 5_000)
        SystemClock.sleep(1_000)
        shot("03-home")
        Log.i(TAG, "home texts: ${visibleTexts()}")
        if (!homeLoaded) fail("Home shows no playable channel. Visible: ${visibleTexts()}")

        val stream = device.findObject(By.res("channel-stream"))
        if (stream != null) {
            stream.click()
            // Media3 player: LIVE is shown only once frames are actually rendered.
            val live = device.wait(Until.hasObject(By.text("LIVE")), 40_000)
            shot("04-stream-player")
            val texts = visibleTexts()
            Log.i(TAG, "stream player live=$live texts: $texts")
            if (texts.any { it.contains("Channel not available") }) fail("player could not find the channel: $texts")
            if (!live) fail("verified stream did not reach PLAYING within 40 s: $texts")
            device.pressBack()
            SystemClock.sleep(1_500)
        } else {
            Log.i(TAG, "catalog has no verified streams yet")
        }

        // Official YouTube channel (KompasTV), scrolled into view.
        var swipes = 0
        while (device.findObject(By.text("KompasTV")) == null && swipes < 80) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 40)
            SystemClock.sleep(250)
            swipes++
        }
        Log.i(TAG, "scrolled $swipes times to reach KompasTV")
        SystemClock.sleep(1_500) // let the list settle: a tap during a fling only stops the fling
        if (!clickText("KompasTV")) fail("KompasTV not found. Visible: ${visibleTexts()}")
        SystemClock.sleep(20_000)
        shot("05-youtube-player")
        val playerTexts = visibleTexts()
        Log.i(TAG, "youtube player texts: $playerTexts")
        if (playerTexts.any { it.contains("Channel not available") }) fail("player could not find the channel: $playerTexts")
        if (playerTexts.none { it == "Watch on YouTube" }) fail("YouTube fallback action missing: $playerTexts")
    }

    private companion object {
        const val TAG = "TVKuSmoke"
        const val SHOTS = "/data/local/tmp/tvku-smoke"
    }
}
