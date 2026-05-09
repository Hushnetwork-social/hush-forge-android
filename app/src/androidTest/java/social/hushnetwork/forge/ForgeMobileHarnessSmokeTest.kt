package social.hushnetwork.forge

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

@RunWith(AndroidJUnit4::class)
class ForgeMobileHarnessSmokeTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        assumeTrue(
            "FORGE_MOBILE_WALLET_HARNESS_PAIR_URL is required for harness E2E.",
            BuildConfig.MOBILE_WALLET_HARNESS_PAIR_URL.isNotBlank()
        )
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) {
            scenario.close()
        }
    }

    @Test
    fun connectsThroughHarnessAndLoadsPrivateNetTokens() {
        assertHarnessReachable()

        waitForText("FORGE")
        waitForText("NEO3 PRIVATE")

        checkNotNull(device.wait(Until.findObject(By.text("Connect").enabled(true)), 120_000)) {
            "Timed out waiting for WalletConnect to be ready."
        }.click()

        assertNotNull(
            "Expected WalletConnect harness session to expose the private-net account.",
            device.wait(Until.findObject(By.textStartsWith("NV1Q1")), 90_000)
        )

        waitForText("Tokens").click()

        assertNotNull(
            "Expected the connected private-net account to load native GAS balance.",
            device.wait(Until.findObject(By.text("GAS")), 90_000)
        )
    }

    private fun waitForText(text: String) =
        checkNotNull(device.wait(Until.findObject(By.text(text)), 30_000)) {
            "Timed out waiting for text: $text"
        }

    private fun assertHarnessReachable() {
        val healthUrl = harnessHealthUrl()
        val connection = (URL(healthUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
        }

        try {
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.readBytes()
                ?.toString(StandardCharsets.UTF_8)
                .orEmpty()

            assertTrue(
                "Expected Android emulator to reach wallet harness at $healthUrl, got HTTP $status $body",
                status in 200..299
            )
            assertTrue(
                "Expected harness health response to expose the private-net account, got: $body",
                body.contains("NV1Q1")
            )
        } catch (error: Throwable) {
            fail("Expected Android emulator to reach wallet harness at $healthUrl: ${error.message}")
        } finally {
            connection.disconnect()
        }
    }

    private fun harnessHealthUrl(): String {
        val pairUrl = BuildConfig.MOBILE_WALLET_HARNESS_PAIR_URL
        return if (pairUrl.endsWith("/pair")) {
            pairUrl.removeSuffix("/pair") + "/health"
        } else {
            pairUrl.trimEnd('/') + "/health"
        }
    }
}
