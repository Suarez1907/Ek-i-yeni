package com.example.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Collects a startup baseline profile. Run on a device or emulator:
 * ./gradlew :app:generateBaselineProfile
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupBaselineProfile {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE)), 5_000)
    }

    private companion object {
        const val TARGET_PACKAGE = "com.aistudio.eksinoled.trkxqp"
    }
}
