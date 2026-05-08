package social.hushnetwork.forge

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.reown.android.CoreClient
import com.reown.appkit.client.AppKit
import com.reown.appkit.client.Modal
import com.reown.appkit.client.models.Session
import com.reown.appkit.client.models.request.Request
import com.reown.appkit.client.models.request.SentRequestResult
import com.reown.sign.client.Sign
import com.reown.sign.client.SignClient
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var connectButton: Button
    private lateinit var walletInfoButton: Button
    private lateinit var networkButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var refreshButton: Button
    private lateinit var sessionText: TextView
    private lateinit var networkText: TextView
    private lateinit var readAccountText: TextView
    private lateinit var statusText: TextView
    private lateinit var tokenList: LinearLayout
    private lateinit var logText: TextView
    private var connectedWalletAddress: String? = null
    private var connectedChainId: String? = null
    private var readAddress: String? = null
    private var balanceRefreshInFlight: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())

        ForgeWalletConnectDelegate.onEvent = { message ->
            appendLog(message)
            if (message.contains(" response on ")) {
                val method = message.substringBefore(" response on ")
                setStatus("$method response received from Neon Mobile.", false)
            }
            refreshSessionUi()
        }
        ForgeWalletConnectDelegate.onSessionChanged = {
            refreshSessionUi()
        }

        handleDeepLink(intent)
        refreshSessionUi()
        if (readAddress == null) {
            renderConnectWalletPrompt()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
        refreshSessionUi()
    }

    override fun onResume() {
        super.onResume()
        refreshSessionUi()
    }

    override fun onDestroy() {
        if (isFinishing) {
            ForgeWalletConnectDelegate.onEvent = null
            ForgeWalletConnectDelegate.onSessionChanged = null
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(0x151515))
            layoutParams = matchParent()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            setBackgroundColor(color(0x202020))
        }
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brandRow.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.forge_logo)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                rightMargin = dp(8)
            }
        )
        brandRow.addView(text("FORGE", 22, color(0xff5a2f), Typeface.BOLD))
        header.addView(brandRow)
        header.addView(text("Native Android WalletConnect dApp", 13, color(0xb4bbc6), Typeface.NORMAL).apply {
            setPadding(0, dp(2), 0, dp(12))
        })

        sessionText = text("WalletConnect: checking session", 13, color(0xb4bbc6), Typeface.NORMAL)
        sessionText.setPadding(0, 0, 0, dp(10))
        header.addView(sessionText)

        networkText = text("Wallet chain: not connected", 13, color(0xb4bbc6), Typeface.NORMAL).apply {
            setPadding(0, 0, 0, dp(8))
        }
        header.addView(networkText)

        readAccountText = text("Wallet address: not connected", 13, color(0xb4bbc6), Typeface.NORMAL).apply {
            setPadding(0, 0, 0, dp(2))
        }
        header.addView(readAccountText)

        val firstRow = actionRow()
        connectButton = button("Connect Neon").apply { setOnClickListener { connectNeon() } }
        firstRow.addView(connectButton, rowButtonParams())
        walletInfoButton = button("Wallet Info").apply { setOnClickListener { requestWallet("getWalletInfo") } }
        firstRow.addView(walletInfoButton, rowButtonParams(leftMargin = 10))
        header.addView(firstRow)

        val secondRow = actionRow()
        networkButton = button("Network").apply { setOnClickListener { requestWallet("getNetworkVersion") } }
        secondRow.addView(networkButton, rowButtonParams())
        disconnectButton = button("Disconnect All").apply { setOnClickListener { disconnectWallet() } }
        secondRow.addView(disconnectButton, rowButtonParams(leftMargin = 10))
        header.addView(secondRow)

        val thirdRow = actionRow()
        refreshButton = button("Refresh Tokens").apply { setOnClickListener { refreshBalances() } }
        thirdRow.addView(refreshButton, rowButtonParams())
        val openNeonButton = button("Open Neon").apply { setOnClickListener { openNeonWallet() } }
        thirdRow.addView(openNeonButton, rowButtonParams(leftMargin = 10))
        header.addView(thirdRow)

        statusText = text("Ready", 13, color(0xb4bbc6), Typeface.NORMAL).apply {
            setPadding(dp(16), dp(10), dp(16), dp(8))
        }
        root.addView(statusText)

        val scrollView = ScrollView(this)
        tokenList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(2), dp(16), dp(8))
        }
        scrollView.addView(tokenList)
        root.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        logText = text("Log", 12, color(0x8b95a1), Typeface.NORMAL).apply {
            setPadding(dp(16), dp(10), dp(16), dp(14))
            maxLines = 8
            setBackgroundColor(color(0x101010))
        }
        root.addView(logText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        return root
    }

    private fun connectNeon() {
        if (!ForgeWalletConnect.isReady) {
            setStatus("WalletConnect is still initializing.", true)
            return
        }

        ForgeWalletConnect.activeSession()?.let { session ->
            ForgeWalletConnectDelegate.selectSession(session.topic)
            setStatus("Neon Mobile is already connected. Disconnect before creating another pairing.", false)
            refreshSessionUi()
            return
        }

        setStatus("Creating WalletConnect proposal for Neon Mobile...", false)
        connectButton.isEnabled = false

        val pairing = CoreClient.Pairing.create { error ->
            runOnUiThread {
                connectButton.isEnabled = true
                setStatus("Pairing failed: ${error.throwable.message}", true)
            }
        }
        if (pairing == null) {
            connectButton.isEnabled = true
            return
        }

        val connectParams = Modal.Params.ConnectParams(
            sessionNamespaces = ForgeWalletConnect.proposalNamespaces(),
            properties = mapOf("forgeClient" to "android-poc"),
            pairing = pairing
        )

        AppKit.connect(
            connectParams,
            onSuccess = { uri ->
                runOnUiThread {
                    connectButton.isEnabled = true
                    appendLog("WalletConnect URI created: ${shortWalletConnectUri(uri)}")
                    openWalletConnectUri(uri)
                }
            },
            onError = { error ->
                runOnUiThread {
                    connectButton.isEnabled = true
                    setStatus("Connect failed: ${error.throwable.message}", true)
                }
            }
        )
    }

    private fun requestWallet(method: String) {
        val signSession = ForgeWalletConnect.activeSession()
        if (signSession == null && runCatching { AppKit.getSession() }.getOrNull() !is Session.WalletConnectSession) {
            setStatus("Connect Neon Mobile before sending wallet requests.", true)
            return
        }

        setStatus("Sending $method to Neon Mobile...", false)

        if (signSession != null) {
            SignClient.request(
                Sign.Params.Request(
                    sessionTopic = signSession.topic,
                    method = method,
                    params = "[]",
                    chainId = BuildConfig.WALLETCONNECT_CHAIN_ID
                ),
                onSuccess = {
                    runOnUiThread {
                        appendLog("$method sent to Neon Mobile.")
                        setStatus("Waiting for Neon Mobile response...", false)
                        openWalletForSession(signSession)
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        setStatus("$method failed: ${error.throwable.message ?: error.throwable.javaClass.simpleName}", true)
                    }
                }
            )
            return
        }

        AppKit.request(
            Request(method = method, params = "[]", chainId = BuildConfig.WALLETCONNECT_CHAIN_ID),
            onSuccess = { _: SentRequestResult ->
                runOnUiThread {
                    appendLog("$method sent. Approve or review it in Neon Mobile.")
                    setStatus("Waiting for Neon Mobile response...", false)
                    openNeonWallet()
                }
            },
            onError = { error: Throwable ->
                runOnUiThread {
                    setStatus("$method failed: ${error.message ?: error.javaClass.simpleName}", true)
                }
            }
        )
    }

    private fun disconnectWallet() {
        val signSessions = ForgeWalletConnect.activeSessions()
        if (signSessions.isNotEmpty()) {
            setStatus("Disconnecting ${signSessions.size} WalletConnect session(s)...", false)
            disconnectButton.isEnabled = false

            val remaining = AtomicInteger(signSessions.size)
            var failures = 0
            signSessions.forEach { session ->
                SignClient.disconnect(
                    Sign.Params.Disconnect(session.topic),
                    onSuccess = {
                        runOnUiThread {
                            appendLog("Disconnected session for ${ForgeWalletConnect.accountAddress(session) ?: session.topic.take(12)}")
                            if (remaining.decrementAndGet() == 0) {
                                finishDisconnectAll(failures)
                            }
                        }
                    },
                    onError = { error ->
                        runOnUiThread {
                            failures += 1
                            appendLog("Disconnect failed: ${error.throwable.message ?: error.throwable.javaClass.simpleName}")
                            if (remaining.decrementAndGet() == 0) {
                                finishDisconnectAll(failures)
                            }
                        }
                    }
                )
            }
            return
        }

        AppKit.disconnect(
            onSuccess = {
                runOnUiThread {
                    ForgeWalletConnectDelegate.selectSession(null)
                    appendLog("WalletConnect session disconnected.")
                    refreshSessionUi()
                }
            },
            onError = { error ->
                runOnUiThread {
                    setStatus("Disconnect failed: ${error.message ?: error.javaClass.simpleName}", true)
                }
            }
        )
    }

    private fun finishDisconnectAll(failures: Int) {
        ForgeWalletConnectDelegate.selectSession(null)
        connectedWalletAddress = null
        connectedChainId = null
        readAddress = null
        updateWalletSessionLabels()
        disconnectButton.isEnabled = true
        if (failures == 0) {
            setStatus("All FORGE WalletConnect sessions disconnected. Connect again with the funded Neon account.", false)
        } else {
            setStatus("Disconnected with $failures failure(s). Check Neon Connections and remove FORGE manually if needed.", true)
        }
        refreshSessionUi()
        renderConnectWalletPrompt()
    }

    private fun refreshBalances() {
        val rpcUrl = BuildConfig.DEFAULT_RPC_URL
        val address = readAddress?.trim().orEmpty()

        if (address.isEmpty()) {
            renderConnectWalletPrompt()
            setStatus("Connect Neon Mobile to load wallet tokens.", false)
            setButtonEnabled(refreshButton, false)
            return
        }

        if (rpcUrl.isEmpty()) {
            setStatus("Missing private-net RPC URL.", true)
            return
        }

        balanceRefreshInFlight = true
        setButtonEnabled(refreshButton, false)
        setStatus("Reading Neo3 private network...", false)

        executor.submit {
            try {
                val version = rpc(rpcUrl, "getversion", JSONArray())
                val network = version
                    .getJSONObject("result")
                    .getJSONObject("protocol")
                    .getInt("network")

                if (network != BuildConfig.EXPECTED_NETWORK_MAGIC) {
                    throw IllegalStateException(
                        "Wrong Neo network magic. Expected ${BuildConfig.EXPECTED_NETWORK_MAGIC}, got $network"
                    )
                }

                val params = JSONArray().put(address)
                val balances = rpc(rpcUrl, "getnep17balances", params)
                val balanceItems = balances
                    .getJSONObject("result")
                    .optJSONArray("balance")

                val tokens = mutableListOf<TokenBalance>()
                if (balanceItems != null) {
                    for (index in 0 until balanceItems.length()) {
                        val item = balanceItems.getJSONObject(index)
                        tokens += TokenBalance(
                            symbol = item.optString("symbol", "UNKNOWN"),
                            name = item.optString("name", ""),
                            hash = item.optString("assethash", ""),
                            rawAmount = item.optString("amount", "0"),
                            decimals = item.optString("decimals", "0").toIntOrNull() ?: 0
                        )
                    }
                }

                runOnUiThread {
                    renderTokens(tokens)
                    setStatus("Loaded ${shortAddress(address)} on ${connectedChainId ?: "wallet chain"} - RPC magic $network", false)
                    balanceRefreshInFlight = false
                    setButtonEnabled(refreshButton, readAddress != null)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    tokenList.removeAllViews()
                    setStatus(error.message ?: error.javaClass.simpleName, true)
                    balanceRefreshInFlight = false
                    setButtonEnabled(refreshButton, readAddress != null)
                }
            }
        }
    }

    private fun rpc(rpcUrl: String, method: String, params: JSONArray): JSONObject {
        val request = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", method)
            .put("params", params)

        val body = request.toString().toByteArray(StandardCharsets.UTF_8)
        val connection = (URL(rpcUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Length", body.size.toString())
        }

        connection.outputStream.use { output: OutputStream ->
            output.write(body)
        }

        val status = connection.responseCode
        val response = readAll(if (status in 200..299) connection.inputStream else connection.errorStream)
        if (status !in 200..299) {
            throw IllegalStateException("RPC HTTP $status: $response")
        }

        val json = JSONObject(response)
        if (json.has("error")) {
            throw IllegalStateException(json.getJSONObject("error").toString())
        }

        return json
    }

    private fun handleDeepLink(intent: Intent?) {
        val url = intent?.dataString ?: return
        AppKit.handleDeepLink(url) { error ->
            runOnUiThread {
                setStatus("Deep link error: ${error.throwable.message}", true)
            }
        }
        appendLog("Handled WalletConnect deep link.")
    }

    private fun openWalletConnectUri(uri: String) {
        copyToClipboard("WalletConnect URI", uri)

        val opened = openUriInNeon(uri) ||
            openUriInNeon(normalizeWalletConnectUri(uri)) ||
            openUri(uri)

        if (opened) {
            setStatus("Neon opened. WalletConnect URI is also copied for manual paste if the approval screen is not shown.", false)
        } else {
            openNeonWallet()
            setStatus("WalletConnect URI copied. Open Neon and paste/connect manually.", true)
        }
    }

    private fun openUriInNeon(uri: String): Boolean =
        openUri(uri) { intent ->
            intent.setPackage(BuildConfig.NEON_PACKAGE)
        }

    private fun openUri(uri: String, configure: (Intent) -> Unit = {}): Boolean =
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    configure(this)
                }
            )
            true
        } catch (_: ActivityNotFoundException) {
            false
        }

    private fun normalizeWalletConnectUri(uri: String): String =
        if (uri.startsWith("wc:") && !uri.startsWith("wc://")) {
            uri.replaceFirst("wc:", "wc://")
        } else {
            uri
        }

    private fun shortWalletConnectUri(uri: String): String {
        val topic = uri.removePrefix("wc:").removePrefix("//").substringBefore("@")
        return "wc:${topic.take(10)}..."
    }

    private fun openNeonWallet() {
        val launchIntent = packageManager.getLaunchIntentForPackage(BuildConfig.NEON_PACKAGE)
        if (launchIntent != null) {
            startActivity(launchIntent)
            return
        }

        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${BuildConfig.NEON_PACKAGE}")))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Neon Wallet is not installed.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openWalletForSession(session: Sign.Model.Session) {
        val redirect = session.redirect
        if (!redirect.isNullOrBlank() && openUri(redirect)) {
            return
        }

        openNeonWallet()
    }

    private fun refreshSessionUi() {
        val signSession = ForgeWalletConnect.activeSession()
        val activeSessions = ForgeWalletConnect.activeSessions()
        val session = runCatching { AppKit.getSession() }.getOrNull()
        val account = runCatching { AppKit.getAccount() }.getOrNull()
        if (signSession != null && ForgeWalletConnectDelegate.selectedSessionTopic != signSession.topic) {
            ForgeWalletConnectDelegate.selectSession(signSession.topic)
        }

        val signAddress = signSession?.let { ForgeWalletConnect.accountAddress(it) }
        val signChainId = signSession?.let { ForgeWalletConnect.chainId(it) }
        val appKitSessionAddress = if (session is Session.WalletConnectSession) {
            session.namespaces.values.firstNotNullOfOrNull { namespace ->
                namespace.accounts.firstOrNull()
            }?.substringAfterLast(":")
        } else {
            null
        }
        val appKitSessionChainId = if (session is Session.WalletConnectSession) {
            session.namespaces.values.firstNotNullOfOrNull { namespace ->
                namespace.accounts.firstOrNull()?.split(":")?.takeIf { it.size >= 3 }?.let { "${it[0]}:${it[1]}" }
                    ?: namespace.chains?.firstOrNull()
            }
        } else {
            null
        }
        val detectedWalletAddress = signAddress ?: account?.address ?: appKitSessionAddress
        val detectedChainId = signChainId ?: account?.chain?.id ?: appKitSessionChainId
        syncWalletSession(detectedWalletAddress, detectedChainId)

        val sessionLabel = when {
            signSession != null -> {
                val address = signAddress ?: signSession.topic.take(12)
                val suffix = if (activeSessions.size > 1) " (${activeSessions.size} sessions)" else ""
                "FORGE session account: $address$suffix"
            }
            account != null -> "FORGE session account: ${account.address}"
            session is Session.WalletConnectSession -> {
                "FORGE session account: ${appKitSessionAddress ?: session.topic.take(12)}"
            }
            else -> "FORGE session account: not connected"
        }

        sessionText.text = sessionLabel
        val connected = signSession != null || session is Session.WalletConnectSession || account != null
        connectButton.text = if (connected) "Connected" else "Connect Neon"
        setButtonEnabled(connectButton, !connected)
        setButtonEnabled(walletInfoButton, connected)
        setButtonEnabled(networkButton, connected)
        setButtonEnabled(disconnectButton, connected)
        setButtonEnabled(refreshButton, connected && !balanceRefreshInFlight)
    }

    private fun syncWalletSession(walletAddress: String?, chainId: String?) {
        if (walletAddress.isNullOrBlank()) {
            if (connectedWalletAddress != null || connectedChainId != null || readAddress != null) {
                connectedWalletAddress = null
                connectedChainId = null
                readAddress = null
                updateWalletSessionLabels()
                renderConnectWalletPrompt()
            }
            return
        }

        val changed = connectedWalletAddress != walletAddress || connectedChainId != chainId
        connectedWalletAddress = walletAddress
        connectedChainId = chainId
        readAddress = walletAddress
        updateWalletSessionLabels()
        if (changed) {
            refreshBalances()
        }
    }

    private fun updateWalletSessionLabels() {
        networkText.text = "Wallet chain: ${connectedChainId ?: "not connected"}"
        readAccountText.text = connectedWalletAddress?.let { address ->
            "Wallet address: ${shortAddress(address)}"
        } ?: "Wallet address: not connected"
    }

    private fun renderConnectWalletPrompt() {
        tokenList.removeAllViews()
        tokenList.addView(text("Connect Neon Mobile to load wallet tokens.", 14, color(0xb4bbc6), Typeface.NORMAL))
    }

    private fun renderTokens(tokens: List<TokenBalance>) {
        tokenList.removeAllViews()

        if (tokens.isEmpty()) {
            tokenList.addView(text("No balances returned for this wallet.", 14, color(0xb4bbc6), Typeface.NORMAL))
            return
        }

        tokens.forEach { token ->
            tokenList.addView(tokenCard(token))
        }
    }

    private fun tokenCard(token: TokenBalance): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(color(0x242424), dp(8), color(0x3a3a3a), 1)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(10))
            }
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        card.addView(top)

        top.addView(text(token.symbol, 19, color(0xf5f5f5), Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(text(formatAmount(token.rawAmount, token.decimals), 17, color(0xf5f5f5), Typeface.BOLD).apply {
            gravity = Gravity.END
        })

        card.addView(text(token.name, 13, color(0xb4bbc6), Typeface.NORMAL).apply {
            setPadding(0, dp(4), 0, dp(6))
        })
        card.addView(text(shortHash(token.hash), 12, color(0x8b95a1), Typeface.NORMAL))

        return card
    }

    private fun readAll(stream: InputStream?): String {
        if (stream == null) return ""

        return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            buildString {
                var line = reader.readLine()
                while (line != null) {
                    append(line)
                    line = reader.readLine()
                }
            }
        }
    }

    private fun formatAmount(rawAmount: String, decimals: Int): String =
        runCatching {
            BigDecimal(BigInteger(rawAmount), decimals)
                .stripTrailingZeros()
                .toPlainString()
        }.getOrElse { rawAmount }

    private fun shortHash(hash: String): String =
        if (hash.length <= 18) hash else hash.take(8) + "..." + hash.takeLast(6)

    private fun shortAddress(address: String): String =
        if (address.length <= 16) address else address.take(8) + "..." + address.takeLast(6)

    private fun setStatus(message: String, error: Boolean) {
        statusText.text = message
        statusText.setTextColor(if (error) color(0xff6d4d) else color(0xb4bbc6))
        if (error) appendLog(message)
    }

    private fun appendLog(message: String) {
        val previous = logText.text.toString().takeIf { it != "Log" }.orEmpty()
        logText.text = listOf(message, previous)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .lineSequence()
            .take(8)
            .joinToString("\n")
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    private fun actionRow(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }

    private fun rowButtonParams(leftMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            if (leftMargin > 0) setMargins(dp(leftMargin), 0, 0, 0)
        }

    private fun button(label: String): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(color(0xff5a2f), dp(8), color(0xff5a2f), 0)
        }

    private fun setButtonEnabled(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1.0f else 0.45f
    }

    private fun text(value: String, sizeSp: Int, color: Int, style: Int): TextView =
        TextView(this).apply {
            text = value
            textSize = sizeSp.toFloat()
            setTextColor(color)
            setTypeface(Typeface.DEFAULT, style)
        }

    private fun rounded(fill: Int, radius: Int, stroke: Int, strokeWidth: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            if (strokeWidth > 0) setStroke(strokeWidth, stroke)
        }

    private fun matchParent(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun color(rgb: Int): Int =
        Color.rgb((rgb shr 16) and 0xff, (rgb shr 8) and 0xff, rgb and 0xff)

    private data class TokenBalance(
        val symbol: String,
        val name: String,
        val hash: String,
        val rawAmount: String,
        val decimals: Int
    )
}
