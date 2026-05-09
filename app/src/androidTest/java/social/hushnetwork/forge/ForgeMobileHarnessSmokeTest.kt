package social.hushnetwork.forge

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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
}
