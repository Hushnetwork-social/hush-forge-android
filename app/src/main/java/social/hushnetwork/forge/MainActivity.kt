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
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.reown.android.CoreClient
import com.reown.appkit.client.AppKit
import com.reown.appkit.client.Modal
import com.reown.appkit.client.models.Session
import com.reown.sign.client.Sign
import com.reown.sign.client.SignClient
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val repository = ForgeChainRepository()
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val marketRefreshRunnable = object : Runnable {
        override fun run() {
            refreshVisibleMarketData()
            refreshHandler.postDelayed(this, MARKET_REFRESH_INTERVAL_MS)
        }
    }
    private val quoteUsdRefreshRunnable = object : Runnable {
        override fun run() {
            refreshQuoteUsdReferences(force = true)
            refreshHandler.postDelayed(this, QUOTE_USD_REFRESH_INTERVAL_MS)
        }
    }

    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var networkLine: View
    private lateinit var networkBadge: TextView
    private lateinit var connectButton: Button
    private lateinit var pairsTab: Button
    private lateinit var tokensTab: Button
    private lateinit var adminTab: Button

    private var selectedTab = ForgeTab.PAIRS
    private var selectedPairFilter = PairFilter.TRENDING
    private var selectedPairHash: String? = null
    private var selectedDetailTab = DetailTab.TRADE
    private val marketCandlesByToken = mutableMapOf<String, List<ForgeMarketCandle>>()
    private val loadingMarketCandles = mutableSetOf<String>()
    private val marketCandleErrors = mutableMapOf<String, String>()
    private var dashboard: ForgeDashboard? = null
    private var loading = false
    private var refreshTimerStarted = false
    private var quoteUsdRefreshTimerStarted = false
    private var selectedPriceDisplay = PriceDisplayMode.QUOTE
    private var connectedWalletAddress: String? = null
    private var connectedChainId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())

        ForgeWalletConnectDelegate.onEvent = {
            refreshSessionUi()
        }
        ForgeWalletConnectDelegate.onSessionChanged = {
            refreshSessionUi()
            loadDashboard()
        }

        handleDeepLink(intent)
        refreshSessionUi()
        loadDashboard()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
        refreshSessionUi()
        loadDashboard()
    }

    override fun onResume() {
        super.onResume()
        refreshSessionUi()
        startMarketRefreshTimer()
        startQuoteUsdRefreshTimer()
    }

    override fun onPause() {
        stopMarketRefreshTimer()
        stopQuoteUsdRefreshTimer()
        super.onPause()
    }

    override fun onDestroy() {
        stopMarketRefreshTimer()
        stopQuoteUsdRefreshTimer()
        if (isFinishing) {
            ForgeWalletConnectDelegate.onEvent = null
            ForgeWalletConnectDelegate.onSessionChanged = null
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (selectedPairHash != null) {
            selectedPairHash = null
            selectedDetailTab = DetailTab.TRADE
            render()
            return
        }
        super.onBackPressed()
    }

    private fun buildLayout(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(FORGE_BG)
            layoutParams = matchParent()
        }

        root.addView(buildHeader())

        val scroll = ScrollView(this).apply {
            isFillViewport = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(22))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        return root
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, statusBarInset() + dp(2), 0, dp(10))
            setBackgroundColor(HEADER)
        }

        header.addView(networkRail(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(16), 0)
        }

        top.addView(ImageView(this).apply {
            setImageResource(R.drawable.forge_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(8) })

        top.addView(text("FORGE", 20, ORANGE, Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        connectButton = smallButton("Connect").apply {
            setOnClickListener {
                if (connectedWalletAddress == null) connectNeon() else disconnectWallet()
            }
        }
        top.addView(connectButton, LinearLayout.LayoutParams(dp(112), dp(40)))
        header.addView(top)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), 0)
        }
        pairsTab = tabButton("Pairs", ForgeTab.PAIRS)
        tokensTab = tabButton("Tokens", ForgeTab.TOKENS)
        adminTab = tabButton("Admin", ForgeTab.ADMIN)
        tabs.addView(pairsTab, tabParams())
        tabs.addView(tokensTab, tabParams(left = 8))
        tabs.addView(adminTab, tabParams(left = 8))
        header.addView(tabs)

        return header
    }

    private fun networkRail(): View =
        FrameLayout(this).apply {
            networkLine = View(this@MainActivity).apply {
                setBackgroundColor(NETWORK_PRIVATE)
            }
            addView(networkLine, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2), Gravity.CENTER_VERTICAL))

            networkBadge = text("NEO3 PRIVATE", 10, Color.WHITE, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(3), dp(12), dp(3))
            }
            addView(networkBadge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(20), Gravity.CENTER))
        }

    private fun tabButton(label: String, tab: ForgeTab): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                selectedPairHash = null
                selectedDetailTab = DetailTab.TRADE
                selectedTab = tab
                updateTabs()
                render()
            }
        }

    private fun tabParams(left: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(78), dp(38)).apply {
            if (left > 0) leftMargin = dp(left)
        }

    private fun updateTabs() {
        styleTab(pairsTab, selectedTab == ForgeTab.PAIRS)
        styleTab(tokensTab, selectedTab == ForgeTab.TOKENS)
        styleTab(adminTab, selectedTab == ForgeTab.ADMIN)
    }

    private fun styleTab(button: Button, active: Boolean) {
        button.setTextColor(if (active) Color.WHITE else MUTED)
        button.background = rounded(
            if (active) ORANGE_MUTED else SURFACE,
            dp(10),
            if (active) ORANGE else BORDER,
            1
        )
    }

    private fun loadDashboard(
        showLoading: Boolean = true,
        onLoaded: ((ForgeDashboard) -> Unit)? = null
    ) {
        if (loading) return
        loading = true
        if (showLoading || dashboard == null) {
            renderLoading()
        }

        val address = connectedWalletAddress
        executor.submit {
            try {
                val loaded = repository.load(address)
                runOnUiThread {
                    dashboard = loaded
                    loading = false
                    updateStatus()
                    render()
                    refreshQuoteUsdReferences()
                    onLoaded?.invoke(loaded)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    loading = false
                    dashboard = null
                    renderError(error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    private fun loadMarketCandles(pair: ForgePair, force: Boolean = false) {
        val tokenHash = pair.token.contractHash.lowercase(Locale.US)
        if ((!force && marketCandlesByToken.containsKey(tokenHash)) || loadingMarketCandles.contains(tokenHash)) {
            return
        }

        loadingMarketCandles += tokenHash
        marketCandleErrors.remove(tokenHash)

        executor.submit {
            try {
                val candles = repository.loadMarketCandles(pair)
                runOnUiThread {
                    loadingMarketCandles -= tokenHash
                    marketCandlesByToken[tokenHash] = candles
                    render()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    loadingMarketCandles -= tokenHash
                    marketCandleErrors[tokenHash] = error.message ?: error.javaClass.simpleName
                    render()
                }
            }
        }
    }

    private fun refreshVisibleMarketData() {
        val activePairHash = selectedPairHash ?: return
        if (loading) return

        loadDashboard(showLoading = false) { refreshedDashboard ->
            val refreshedPair = refreshedDashboard.pairs.firstOrNull {
                it.token.contractHash.equals(activePairHash, ignoreCase = true)
            }
            if (refreshedPair != null) {
                loadMarketCandles(refreshedPair, force = true)
            }
        }
    }

    private fun refreshQuoteUsdReferences(force: Boolean = false) {
        val assets = quoteAssetsForUsd()
        if (assets.isEmpty()) return

        executor.submit {
            QuoteAssetUsdReferenceStore.refresh(assets, force)
            runOnUiThread {
                if (selectedPriceDisplay == PriceDisplayMode.USD) {
                    render()
                }
            }
        }
    }

    private fun quoteAssetsForUsd(): List<String> =
        (dashboard?.pairs?.map { it.quoteAsset } ?: emptyList())
            .map { it.uppercase(Locale.US) }
            .filter { it == "GAS" || it == "NEO" }
            .distinct()

    private fun startMarketRefreshTimer() {
        if (refreshTimerStarted) return
        refreshTimerStarted = true
        refreshHandler.postDelayed(marketRefreshRunnable, MARKET_REFRESH_INTERVAL_MS)
    }

    private fun stopMarketRefreshTimer() {
        refreshTimerStarted = false
        refreshHandler.removeCallbacks(marketRefreshRunnable)
    }

    private fun startQuoteUsdRefreshTimer() {
        if (quoteUsdRefreshTimerStarted) return
        quoteUsdRefreshTimerStarted = true
        refreshHandler.postDelayed(quoteUsdRefreshRunnable, QUOTE_USD_REFRESH_INTERVAL_MS)
    }

    private fun stopQuoteUsdRefreshTimer() {
        quoteUsdRefreshTimerStarted = false
        refreshHandler.removeCallbacks(quoteUsdRefreshRunnable)
    }

    private fun updateStatus() {
        updateNetworkBadge()
    }

    private fun updateNetworkBadge() {
        if (!::networkBadge.isInitialized) return
        val chain = connectedChainId ?: BuildConfig.WALLETCONNECT_CHAIN_ID
        val normalized = chain.lowercase(Locale.US)
        val label = when {
            "main" in normalized -> "NEO3 MAINNET"
            "test" in normalized -> "NEO3 TESTNET"
            "private" in normalized -> "NEO3 PRIVATE"
            else -> chain.uppercase(Locale.US)
        }
        val fill = when {
            "main" in normalized -> GREEN
            "test" in normalized -> GOLD
            "private" in normalized -> NETWORK_PRIVATE
            else -> color(0x4c5664)
        }
        networkBadge.text = label
        networkBadge.background = rounded(fill, dp(11), fill, 0)
        if (::networkLine.isInitialized) {
            networkLine.setBackgroundColor(fill)
        }
    }

    private fun displayMarketPrice(amount: BigInteger, quoteAsset: String, tokenDecimals: Int): String {
        if (selectedPriceDisplay == PriceDisplayMode.USD) {
            formatMarketPriceUsd(
                amount = amount,
                quoteAsset = quoteAsset,
                tokenDecimals = tokenDecimals,
                reference = QuoteAssetUsdReferenceStore.reference(quoteAsset)
            )?.let { return it }
        }

        return formatMarketPrice(amount, quoteAsset, tokenDecimals)
    }

    private fun displayMarketCap(amount: BigInteger, quoteAsset: String): String {
        if (selectedPriceDisplay == PriceDisplayMode.USD) {
            formatQuoteAmountUsd(
                amount = amount,
                quoteAsset = quoteAsset,
                reference = QuoteAssetUsdReferenceStore.reference(quoteAsset),
                compact = true
            )?.let { return it }
        }

        return formatQuoteAmount(amount, quoteAsset)
    }

    private fun render() {
        content.removeAllViews()
        updateTabs()

        val data = dashboard
        if (data == null) {
            renderLoading()
            return
        }

        selectedPairHash?.let { hash ->
            val pair = data.pairs.firstOrNull { it.token.contractHash.equals(hash, ignoreCase = true) }
            if (pair != null) {
                renderPairDetail(pair)
                return
            }
            selectedPairHash = null
        }

        when (selectedTab) {
            ForgeTab.PAIRS -> renderPairs(data)
            ForgeTab.TOKENS -> renderTokens(data)
            ForgeTab.ADMIN -> renderAdmin(data)
        }
    }

    private fun renderLoading() {
        if (!::content.isInitialized) return
        content.removeAllViews()
        repeat(4) {
            content.addView(card().apply {
                addView(text("Loading", 14, MUTED, Typeface.BOLD))
                addView(text("Reading live FORGE state from the private network.", 13, MUTED, Typeface.NORMAL).withTopPadding(6))
            })
        }
    }

    private fun renderError(message: String) {
        content.removeAllViews()
        content.addView(card().apply {
            addView(text("FORGE data unavailable", 19, TEXT, Typeface.BOLD))
            addView(text(message, 13, ERROR, Typeface.NORMAL).withTopPadding(8))
            addView(primaryButton("Retry").apply {
                setOnClickListener { loadDashboard() }
            }, fullButtonParams(top = 14))
        })
    }

    private fun renderPairs(data: ForgeDashboard) {
        sectionTitle("Pairs", "Live bonding-curve markets from TokenFactory")
        content.addView(pairFilterRow(data), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        if (data.pairs.isEmpty()) {
            emptyCard("No tradable pairs yet.", "Speculative tokens appear here after their bonding curve is configured.")
            return
        }

        val visiblePairs = when (selectedPairFilter) {
            PairFilter.TRENDING -> data.pairs.take(3)
            PairFilter.ALL -> data.pairs
            PairFilter.GAS -> data.pairs.filter { it.quoteAsset.equals("GAS", ignoreCase = true) }
            PairFilter.NEO -> data.pairs.filter { it.quoteAsset.equals("NEO", ignoreCase = true) }
            PairFilter.SOON -> data.pairs.filter { it.progressBps >= 7_500 }
        }

        if (visiblePairs.isEmpty()) {
            emptyCard("No pairs in this filter.", "Try All pairs or switch the quote asset filter.")
            return
        }

        if (selectedPairFilter == PairFilter.TRENDING) {
            val latest = visiblePairs.first()
            content.addView(pairHero(latest), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            })

            val remainingPairs = visiblePairs.drop(1)
            if (remainingPairs.isNotEmpty()) {
                sectionLabel("More tradable pairs")
                remainingPairs.forEach { pair -> content.addView(pairCompactCard(pair)) }
            }
            return
        }

        sectionLabel("${visiblePairs.size} tradable pair${if (visiblePairs.size == 1) "" else "s"}")
        visiblePairs.forEach { pair -> content.addView(pairCompactCard(pair)) }
    }

    private fun pairFilterRow(data: ForgeDashboard): View =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pairFilterButton("Trending", PairFilter.TRENDING, data.pairs.take(3).size))
                addView(pairFilterButton("All", PairFilter.ALL, data.pairs.size))
                addView(pairFilterButton("GAS", PairFilter.GAS, data.pairs.count { it.quoteAsset.equals("GAS", ignoreCase = true) }))
                addView(pairFilterButton("NEO", PairFilter.NEO, data.pairs.count { it.quoteAsset.equals("NEO", ignoreCase = true) }))
                addView(pairFilterButton("Soon", PairFilter.SOON, data.pairs.count { it.progressBps >= 7_500 }))
            })
        }

    private fun pairFilterButton(label: String, filter: PairFilter, count: Int): Button =
        Button(this).apply {
            text = "$label $count"
            isAllCaps = false
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            minWidth = 0
            minimumWidth = 0
            includeFontPadding = false
            setPadding(dp(12), 0, dp(12), 0)
            val active = selectedPairFilter == filter
            setTextColor(if (active) Color.WHITE else MUTED)
            background = rounded(
                if (active) ORANGE else SURFACE,
                dp(18),
                if (active) ORANGE else BORDER,
                1
            )
            setOnClickListener {
                selectedPairFilter = filter
                render()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                rightMargin = dp(8)
            }
        }

    private fun pairHero(pair: ForgePair): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { openPairDetail(pair) }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(SURFACE_DARK, dp(8), colorForToken(pair.token.contractHash), 1)
            addView(tokenTitleRow(pair.token, pair.pairLabel, relativeAge(pair.createdAt), "Speculative"))
            addView(metricGrid(
                "Price", displayMarketPrice(pair.currentPrice, pair.quoteAsset, pair.token.decimals),
                "Market cap", displayMarketCap(pair.marketCap, pair.quoteAsset),
                "Reserve", formatQuoteAmount(pair.realQuote, pair.quoteAsset),
                "Trades", pair.totalTrades.toString()
            ).withTopPadding(12))
            addView(progress(pair.progressBps), pairProgressParams())
        }

    private fun pairCard(pair: ForgePair): View =
        card().apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { openPairDetail(pair) }
            addView(tokenTitleRow(pair.token, pair.pairLabel, relativeAge(pair.createdAt), pair.contractStatus.ifBlank { "Active" }))
            addView(metricGrid(
                "Price", displayMarketPrice(pair.currentPrice, pair.quoteAsset, pair.token.decimals),
                "Market cap", displayMarketCap(pair.marketCap, pair.quoteAsset),
                "Reserve", formatQuoteAmount(pair.realQuote, pair.quoteAsset),
                "Graduation", "${pair.progressBps / 100}.${(pair.progressBps % 100).toString().padStart(2, '0')}%"
            ).withTopPadding(12))
            addView(progress(pair.progressBps), pairProgressParams())
        }

    private fun pairCompactCard(pair: ForgePair): View =
        card(stroke = colorForToken(pair.token.contractHash)).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { openPairDetail(pair) }
            addView(tokenTitleRow(pair.token, pair.pairLabel, relativeAge(pair.createdAt), pair.contractStatus.ifBlank { "Active" }))
            addView(metricGrid(
                "Price", displayMarketPrice(pair.currentPrice, pair.quoteAsset, pair.token.decimals),
                "Reserve", formatQuoteAmount(pair.realQuote, pair.quoteAsset),
                "Market cap", displayMarketCap(pair.marketCap, pair.quoteAsset),
                "Graduation", "${pair.progressBps / 100}.${(pair.progressBps % 100).toString().padStart(2, '0')}%"
            ).withTopPadding(12))
            addView(progress(pair.progressBps), pairProgressParams())
        }

    private fun openPairDetail(pair: ForgePair) {
        selectedPairHash = pair.token.contractHash
        selectedDetailTab = DetailTab.TRADE
        loadMarketCandles(pair)
        render()
    }

    private fun renderPairDetail(pair: ForgePair) {
        loadMarketCandles(pair)

        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
            addView(ghostButton("< Pairs").apply {
                setOnClickListener {
                    selectedPairHash = null
                    selectedDetailTab = DetailTab.TRADE
                    render()
                }
            }, LinearLayout.LayoutParams(dp(92), dp(38)))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(pill(pair.contractStatus.ifBlank { "Active" }))
        })

        content.addView(card(stroke = colorForToken(pair.token.contractHash)).apply {
            addView(tokenTitleRow(pair.token, pair.pairLabel, pair.token.name, "Speculative"))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(metric("Price", displayMarketPrice(pair.currentPrice, pair.quoteAsset, pair.token.decimals)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(metric("Market cap", displayMarketCap(pair.marketCap, pair.quoteAsset)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
                addView(priceDisplayButton(pair.quoteAsset), LinearLayout.LayoutParams(dp(38), dp(34)).apply { leftMargin = dp(10) })
            }.withTopPadding(14))
        })

        content.addView(detailActionRow(pair), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        when (selectedDetailTab) {
            DetailTab.TRADE -> renderDetailTrade(pair)
            DetailTab.DETAILS -> renderDetailInfo(pair)
            DetailTab.CURVE -> renderDetailCurve(pair)
        }
    }

    private fun detailTabRow(): View =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(detailTabButton("Trade", DetailTab.TRADE))
                addView(detailTabButton("Details", DetailTab.DETAILS))
                addView(detailTabButton("Curve", DetailTab.CURVE))
            })
        }

    private fun detailTabButton(label: String, tab: DetailTab): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            minWidth = 0
            minimumWidth = 0
            includeFontPadding = false
            setPadding(dp(14), 0, dp(14), 0)
            val active = selectedDetailTab == tab
            setTextColor(if (active) Color.WHITE else MUTED)
            background = rounded(
                if (active) ORANGE else SURFACE,
                dp(18),
                if (active) ORANGE else BORDER,
                1
            )
            setOnClickListener {
                selectedDetailTab = tab
                render()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                rightMargin = dp(8)
            }
        }

    private fun detailActionRow(pair: ForgePair): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(detailTabRow(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(refreshMarketButton(pair), LinearLayout.LayoutParams(dp(38), dp(34)).apply {
                leftMargin = dp(8)
            })
        }

    private fun refreshMarketButton(pair: ForgePair): ImageButton =
        ImageButton(this).apply {
            contentDescription = "Refresh market"
            setImageResource(R.drawable.ic_refresh)
            setColorFilter(TEXT)
            scaleType = ImageView.ScaleType.CENTER
            background = rounded(SURFACE, dp(17), BORDER, 1)
            isEnabled = !loading && !loadingMarketCandles.contains(pair.token.contractHash.lowercase(Locale.US))
            alpha = if (isEnabled) 1f else 0.45f
            setOnClickListener {
                selectedPairHash = pair.token.contractHash
                Toast.makeText(this@MainActivity, "Refreshing market...", Toast.LENGTH_SHORT).show()
                refreshVisibleMarketData()
            }
        }

    private fun priceDisplayButton(quoteAsset: String): ImageButton =
        ImageButton(this).apply {
            val showingUsd = selectedPriceDisplay == PriceDisplayMode.USD
            contentDescription = if (showingUsd) "Show $quoteAsset prices" else "Show USD prices"
            setImageResource(R.drawable.ic_swap_vertical)
            setColorFilter(if (showingUsd) ORANGE else TEXT)
            scaleType = ImageView.ScaleType.CENTER
            background = rounded(SURFACE, dp(17), if (showingUsd) ORANGE else BORDER, 1)
            setOnClickListener {
                selectedPriceDisplay = if (selectedPriceDisplay == PriceDisplayMode.USD) {
                    PriceDisplayMode.QUOTE
                } else {
                    PriceDisplayMode.USD
                }
                if (selectedPriceDisplay == PriceDisplayMode.USD) {
                    refreshQuoteUsdReferences(force = true)
                    if (QuoteAssetUsdReferenceStore.reference(quoteAsset) == null) {
                        Toast.makeText(this@MainActivity, "Loading USD reference...", Toast.LENGTH_SHORT).show()
                    }
                }
                render()
            }
        }

    private fun tradingChartCard(pair: ForgePair): View =
        card().apply {
            val tokenHash = pair.token.contractHash.lowercase(Locale.US)
            val candles = marketCandlesByToken[tokenHash] ?: pair.candles
            val latestCandle = candles.lastOrNull()
            val candleLoading = loadingMarketCandles.contains(tokenHash)
            val candleError = marketCandleErrors[tokenHash]
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text("15m market preview", 18, TEXT, Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(pill(if (candles.isEmpty()) if (candleLoading) "Loading" else "Waiting" else "On-chain"))
            })
            addView(
                TradePreviewChartView(
                    this@MainActivity,
                    candles,
                    pair.quoteAsset,
                    pair.token.decimals,
                    QuoteAssetUsdReferenceStore.reference(pair.quoteAsset),
                    selectedPriceDisplay == PriceDisplayMode.USD
                ),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)).apply {
                topMargin = dp(12)
                }
            )
            if (candles.isEmpty()) {
                addView(text(
                    candleError ?: if (candleLoading) {
                        "Loading router Trade events into 15m candles."
                    } else {
                        "15m candles appear after settled router Trade events are indexed."
                    },
                    12,
                    if (candleError == null) MUTED else ERROR,
                    Typeface.NORMAL
                ).withTopPadding(8))
            }
            addView(metricGrid(
                "Latest close", latestCandle?.let { displayMarketPrice(it.close, pair.quoteAsset, pair.token.decimals) }
                    ?: displayMarketPrice(pair.currentPrice, pair.quoteAsset, pair.token.decimals),
                "15m volume", latestCandle?.let { formatQuoteAmount(it.volume, pair.quoteAsset) } ?: "-",
                "Trades indexed", pair.totalTrades.toString(),
                "Reserve", formatQuoteAmount(pair.realQuote, pair.quoteAsset)
            ).withTopPadding(12))
        }

    private fun renderDetailTrade(pair: ForgePair) {
        content.addView(tradingChartCard(pair))
        content.addView(card().apply {
            addView(text("Buy / Sell", 18, TEXT, Typeface.BOLD))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(primaryButton("Buy"), LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(ghostButton("Sell"), LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(8) })
            }.withTopPadding(12))
            addView(text("Quote in ${pair.quoteAsset}", 12, MUTED, Typeface.BOLD).withTopPadding(14))
            addView(text("0 ${pair.quoteAsset}", 18, TEXT, Typeface.BOLD).apply {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(SURFACE_DARK, dp(8), BORDER, 1)
            }.withTopPadding(8))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf("0.1", "0.5", "1").forEachIndexed { index, preset ->
                    addView(ghostButton("$preset ${pair.quoteAsset}"), LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                        if (index > 0) leftMargin = dp(8)
                    })
                }
            }.withTopPadding(10))
            addView(metricGrid(
                "Expected out", "-",
                "Minimum", "-",
                "Impact", "-",
                "Slippage", "1%"
            ).withTopPadding(14))
            addView(primaryButton("Buy ${pair.token.symbol}").apply {
                setOnClickListener {
                    Toast.makeText(this@MainActivity, "WalletConnect market signing comes next.", Toast.LENGTH_SHORT).show()
                }
            }, fullButtonParams(top = 14))
        })
    }

    private fun renderDetailInfo(pair: ForgePair) {
        val token = dashboard?.tokens
            ?.firstOrNull { it.contractHash.equals(pair.token.contractHash, ignoreCase = true) }
            ?: pair.token

        content.addView(card().apply {
            addView(text("Contract", 18, TEXT, Typeface.BOLD))
            addView(infoRow("Hash", shortHash(pair.token.contractHash), copyIconButton("Token contract hash", pair.token.contractHash)).withTopPadding(10))
            addView(infoRow("Creator", pair.token.creatorHash?.let { shortHash(it) } ?: "-").withTopPadding(8))
            addView(infoRow("Total supply", formatTokenAmount(token.supply, token.decimals)).withTopPadding(8))
            addView(infoRow("Created", relativeAge(pair.createdAt)).withTopPadding(8))
        })

        content.addView(card().apply {
            addView(text("Holders", 18, TEXT, Typeface.BOLD))
            addView(infoRow("Connected wallet", token.walletBalance?.let { formatTokenAmount(it, token.decimals) } ?: "No balance").withTopPadding(10))
            addView(infoRow("Top holders", "Indexer endpoint needed").withTopPadding(8))
        })

        content.addView(card().apply {
            addView(text("Activity", 18, TEXT, Typeface.BOLD))
            addView(infoRow("Trades indexed", pair.totalTrades.toString()).withTopPadding(10))
            addView(infoRow("Latest transaction", "Waiting for activity indexer").withTopPadding(8))
        })

        content.addView(card().apply {
            addView(text("Project links", 18, TEXT, Typeface.BOLD))
            addView(infoRow("X profile", "Not set").withTopPadding(10))
            addView(infoRow("Website", "Not set").withTopPadding(8))
        })
    }

    private fun renderDetailCurve(pair: ForgePair) {
        content.addView(card().apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text("Live curve preview", 18, TEXT, Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(pill(formatProgressLabel(pair.progressBps)))
            })
            addView(MarketPreviewChartView(this@MainActivity, pair.progressBps), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)).apply {
                topMargin = dp(12)
            })
            addView(metricGrid(
                "Current reserve", formatQuoteAmount(pair.realQuote, pair.quoteAsset),
                "Threshold", formatQuoteAmount(pair.graduationThreshold, pair.quoteAsset),
                "Status", if (pair.graduationReady) "Ready" else "Bonding",
                "Creator", pair.token.creatorHash?.let { shortHash(it) } ?: "-"
            ).withTopPadding(16))
            addView(text("Drawn from router reserve and graduation state.", 12, MUTED, Typeface.NORMAL).withTopPadding(14))
        })
    }

    private fun renderTokens(data: ForgeDashboard) {
        sectionTitle("Tokens", "Factory-created NEP-17 assets and wallet holdings")

        content.addView(walletSummaryCard(data))

        val factoryTokens = data.tokens.filter { !it.isNative }
        if (factoryTokens.isEmpty()) {
            emptyCard("No FORGE tokens yet.", "Create a token from the mobile signing flow when that operation layer is enabled.")
        } else {
            factoryTokens.forEach { token ->
                content.addView(tokenCard(token, data.pairs.firstOrNull { it.token.contractHash.equals(token.contractHash, ignoreCase = true) }))
            }
        }

        val nativeTokens = data.tokens.filter { it.isNative || it.walletBalance != null }
        if (nativeTokens.isNotEmpty()) {
            sectionLabel("Wallet assets")
            nativeTokens.forEach { token -> content.addView(tokenCard(token, null)) }
        }
    }

    private fun walletSummaryCard(data: ForgeDashboard): View =
        card().apply {
            val address = connectedWalletAddress
            addView(text(if (address == null) "Wallet not connected" else shortAddress(address), 18, TEXT, Typeface.BOLD))
            addView(text("${data.tokens.count { !it.isNative }} FORGE tokens - ${data.pairs.size} live pair(s)", 13, MUTED, Typeface.NORMAL).withTopPadding(6))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(primaryButton("Forge Token").apply {
                    setOnClickListener {
                        Toast.makeText(this@MainActivity, "Token creation signing comes next.", Toast.LENGTH_SHORT).show()
                    }
                }, LinearLayout.LayoutParams(0, dp(44), 1f))
                addView(ghostButton("Refresh").apply {
                    setOnClickListener { loadDashboard() }
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(10) })
            }.withTopPadding(14))
        }

    private fun tokenCard(token: ForgeToken, pair: ForgePair?): View =
        card(stroke = if (token.mode == "speculative") GOLD else BORDER).apply {
            if (pair != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { openPairDetail(pair) }
            }
            val badge = when {
                token.isNative -> "Native"
                token.mode != null -> token.mode.replaceFirstChar { it.titlecase(Locale.US) }
                else -> "NEP-17"
            }
            addView(tokenTitleRow(token, token.symbol, token.name, badge))
            addView(metricGrid(
                "Supply", formatTokenAmount(token.supply, token.decimals),
                "Balance", token.walletBalance?.let { formatTokenAmount(it, token.decimals) } ?: "-",
                "Mode", token.mode ?: "-",
                "Hash", shortHash(token.contractHash)
            ).withTopPadding(12))
        }

    private fun renderAdmin(data: ForgeDashboard) {
        sectionTitle("Admin", "TokenFactory owner controls for mobile")

        val config = data.factoryConfig
        if (config == null) {
            emptyCard("TokenFactory config unavailable.", "The app can still show Pairs and Tokens, but owner controls need GetConfig.")
            return
        }

        val ownerAddress = hash160ToAddress(config.ownerHash) ?: shortHash(config.ownerHash)
        val ownerConnected = connectedWalletAddress
            ?.let { addressToHash160(it) }
            ?.equals(config.ownerHash, ignoreCase = true) == true

        content.addView(card(stroke = if (ownerConnected) GREEN else BORDER).apply {
            addView(text("Factory Summary", 20, TEXT, Typeface.BOLD))
            addView(metricGrid(
                "Contract", shortHash(data.factoryHash),
                "Owner", if (ownerConnected) "Connected" else ownerAddress,
                "Status", if (config.paused) "Paused" else "Active",
                "Wallet", if (ownerConnected) "Owner" else connectedWalletAddress?.let { shortAddress(it) } ?: "Not connected"
            ).withTopPadding(12))
            addView(metricGrid(
                "Creation fee", formatQuoteAmount(config.creationFee, "GAS"),
                "Operation fee", formatQuoteAmount(config.operationFee, "GAS"),
                "Template", "v${config.templateVersion}",
                "Stored", if (config.templateNefStored && config.templateManifestStored) "Yes" else "No"
            ).withTopPadding(10))
        })

        adminAction(
            title = "Creation Fee",
            body = "Current: ${formatQuoteAmount(config.creationFee, "GAS")}",
            enabled = ownerConnected,
            action = "Set Fee"
        )
        adminAction(
            title = "Operation Fee",
            body = "Current: ${formatQuoteAmount(config.operationFee, "GAS")}",
            enabled = ownerConnected,
            action = "Set Fee"
        )
        adminAction(
            title = if (config.paused) "Resume TokenFactory" else "Pause TokenFactory",
            body = "Pausing blocks token creation and factory-managed token changes.",
            enabled = ownerConnected,
            action = if (config.paused) "Resume" else "Pause"
        )
        adminAction(
            title = "Platform Fee Policy",
            body = "Propagate TokenFactory platform-fee policy to all eligible tokens.",
            enabled = ownerConnected,
            action = "Review"
        )
        adminAction(
            title = "Claim Factory Assets",
            body = "Claim non-zero TokenFactory balances from the owner wallet.",
            enabled = ownerConnected,
            action = "Claim"
        )
    }

    private fun adminAction(title: String, body: String, enabled: Boolean, action: String) {
        content.addView(card().apply {
            addView(text(title, 17, TEXT, Typeface.BOLD))
            addView(text(body, 13, MUTED, Typeface.NORMAL).withTopPadding(6))
            addView(primaryButton(action).apply {
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.45f
                setOnClickListener {
                    Toast.makeText(this@MainActivity, "WalletConnect admin signing comes next.", Toast.LENGTH_SHORT).show()
                }
            }, fullButtonParams(top = 12))
        })
    }

    private fun sectionTitle(title: String, subtitle: String) {
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
            addView(text(title, 26, TEXT, Typeface.BOLD))
            addView(text(subtitle, 13, MUTED, Typeface.NORMAL).withTopPadding(4))
        })
    }

    private fun sectionLabel(label: String) {
        content.addView(text(label, 13, MUTED, Typeface.BOLD).apply {
            setPadding(0, dp(12), 0, dp(10))
        })
    }

    private fun emptyCard(title: String, body: String) {
        content.addView(card().apply {
            addView(text(title, 18, TEXT, Typeface.BOLD))
            addView(text(body, 13, MUTED, Typeface.NORMAL).withTopPadding(8))
        })
    }

    private fun tokenTitleRow(token: ForgeToken, title: String, subtitle: String, badge: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(tokenIcon(token), LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 17, TEXT, Typeface.BOLD))
                addView(text(subtitle, 12, MUTED, Typeface.NORMAL).withTopPadding(2))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pill(badge))
        }

    private fun metricGrid(
        label1: String,
        value1: String,
        label2: String,
        value2: String,
        label3: String,
        value3: String,
        label4: String,
        value4: String
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(metricRow(label1, value1, label2, value2))
            addView(metricRow(label3, value3, label4, value4).withTopPadding(8))
        }

    private fun metricRow(label1: String, value1: String, label2: String, value2: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(metric(label1, value1), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(metric(label2, value2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
        }

    private fun metric(label: String, value: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(label.uppercase(Locale.US), 10, MUTED, Typeface.BOLD))
            addView(text(value, 14, TEXT, Typeface.BOLD).withTopPadding(3))
        }

    private fun infoRow(label: String, value: String, trailing: View? = null): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(label.uppercase(Locale.US), 10, MUTED, Typeface.BOLD))
                addView(text(value, 14, TEXT, Typeface.BOLD).withTopPadding(3))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (trailing != null) {
                addView(trailing, LinearLayout.LayoutParams(dp(34), dp(34)).apply { leftMargin = dp(10) })
            }
        }

    private fun copyIconButton(label: String, value: String): View =
        TextView(this).apply {
            text = "⧉"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(MUTED)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(SURFACE_DARK, dp(7), BORDER, 1)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                copyToClipboard(label, value)
                Toast.makeText(this@MainActivity, "Contract hash copied.", Toast.LENGTH_SHORT).show()
            }
        }

    private fun progress(progressBps: Int): View {
        val normalized = progressBps.coerceIn(0, 10_000)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(color(0x303030), dp(6), color(0x303030), 0)
        }
        row.addView(View(this).apply {
            background = rounded(ORANGE, dp(6), ORANGE, 0)
        }, LinearLayout.LayoutParams(0, dp(6), normalized.coerceAtLeast(1).toFloat()))
        row.addView(View(this), LinearLayout.LayoutParams(0, dp(6), (10_000 - normalized).coerceAtLeast(1).toFloat()))
        return row
    }

    private fun pairProgressParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18)
        }

    private fun tokenIcon(token: ForgeToken): View =
        TokenIdenticonView(this, token.contractHash)

    private fun pill(label: String): TextView =
        text(label, 11, TEXT, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = rounded(SURFACE_DARK, dp(6), BORDER, 1)
        }

    private fun card(fill: Int = SURFACE, stroke: Int = BORDER): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = rounded(fill, dp(8), stroke, 1)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }

    private fun primaryButton(label: String): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(ORANGE, dp(8), ORANGE, 0)
        }

    private fun smallButton(label: String): Button = primaryButton(label)

    private fun ghostButton(label: String): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(MUTED)
            background = rounded(SURFACE, dp(8), BORDER, 1)
        }

    private fun fullButtonParams(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
            topMargin = dp(top)
        }

    private fun connectNeon() {
        if (!ForgeWalletConnect.isReady) {
            Toast.makeText(this, "WalletConnect is still initializing.", Toast.LENGTH_SHORT).show()
            return
        }

        ForgeWalletConnect.activeSession()?.let { session ->
            ForgeWalletConnectDelegate.selectSession(session.topic)
            refreshSessionUi()
            loadDashboard()
            return
        }

        connectButton.isEnabled = false
        val pairing = CoreClient.Pairing.create { error ->
            runOnUiThread {
                connectButton.isEnabled = true
                Toast.makeText(this, "Pairing failed: ${error.throwable.message}", Toast.LENGTH_LONG).show()
            }
        } ?: run {
            connectButton.isEnabled = true
            return
        }

        AppKit.connect(
            Modal.Params.ConnectParams(
                sessionNamespaces = ForgeWalletConnect.proposalNamespaces(),
                properties = mapOf("forgeClient" to "android-app"),
                pairing = pairing
            ),
            onSuccess = { uri ->
                runOnUiThread {
                    connectButton.isEnabled = true
                    openWalletConnectUri(uri)
                }
            },
            onError = { error ->
                runOnUiThread {
                    connectButton.isEnabled = true
                    Toast.makeText(this, "Connect failed: ${error.throwable.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun disconnectWallet() {
        val sessions = ForgeWalletConnect.activeSessions()
        if (sessions.isEmpty()) {
            ForgeWalletConnectDelegate.selectSession(null)
            connectedWalletAddress = null
            connectedChainId = null
            refreshSessionUi()
            loadDashboard()
            return
        }

        connectButton.isEnabled = false
        val remaining = AtomicInteger(sessions.size)
        sessions.forEach { session ->
            SignClient.disconnect(
                Sign.Params.Disconnect(session.topic),
                onSuccess = {
                    runOnUiThread {
                        if (remaining.decrementAndGet() == 0) finishDisconnect()
                    }
                },
                onError = {
                    runOnUiThread {
                        if (remaining.decrementAndGet() == 0) finishDisconnect()
                    }
                }
            )
        }
    }

    private fun finishDisconnect() {
        ForgeWalletConnectDelegate.selectSession(null)
        connectedWalletAddress = null
        connectedChainId = null
        connectButton.isEnabled = true
        refreshSessionUi()
        loadDashboard()
    }

    private fun refreshSessionUi() {
        val signSession = ForgeWalletConnect.activeSession()
        val session = runCatching { AppKit.getSession() }.getOrNull()
        val account = runCatching { AppKit.getAccount() }.getOrNull()

        if (signSession != null && ForgeWalletConnectDelegate.selectedSessionTopic != signSession.topic) {
            ForgeWalletConnectDelegate.selectSession(signSession.topic)
        }

        val signAddress = signSession?.let { ForgeWalletConnect.accountAddress(it) }
        val signChainId = signSession?.let { ForgeWalletConnect.chainId(it) }
        val appKitSessionAddress = if (session is Session.WalletConnectSession) {
            session.namespaces.values.firstNotNullOfOrNull { namespace -> namespace.accounts.firstOrNull() }
                ?.substringAfterLast(":")
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

        connectedWalletAddress = signAddress ?: account?.address ?: appKitSessionAddress
        connectedChainId = signChainId ?: account?.chain?.id ?: appKitSessionChainId
        connectButton.text = connectedWalletAddress?.let { compactAddress(it) } ?: "Connect"
        updateStatus()
    }

    private fun handleDeepLink(intent: Intent?) {
        val url = intent?.dataString ?: return
        AppKit.handleDeepLink(url) { error ->
            runOnUiThread {
                Toast.makeText(this, "Deep link error: ${error.throwable.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openWalletConnectUri(uri: String) {
        copyToClipboard("WalletConnect URI", uri)
        if (MobileWalletHarness.enabled) {
            Toast.makeText(this, "Pairing with local mobile harness wallet...", Toast.LENGTH_SHORT).show()
            MobileWalletHarness.pair(uri) { result ->
                runOnUiThread {
                    result
                        .onSuccess {
                            Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                        }
                        .onFailure {
                            Toast.makeText(this, it.message ?: "Harness pairing failed.", Toast.LENGTH_LONG).show()
                        }
                }
            }
            return
        }

        val opened = openUriInNeon(uri) || openUriInNeon(normalizeWalletConnectUri(uri)) || openUri(uri)
        if (!opened) {
            openNeonWallet()
            Toast.makeText(this, "WalletConnect URI copied for manual pairing.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openUriInNeon(uri: String): Boolean =
        openUri(uri) { intent -> intent.setPackage(BuildConfig.NEON_PACKAGE) }

    private fun openUri(uri: String, configure: (Intent) -> Unit = {}): Boolean =
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                configure(this)
            })
            true
        } catch (_: ActivityNotFoundException) {
            false
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

    private fun normalizeWalletConnectUri(uri: String): String =
        if (uri.startsWith("wc:") && !uri.startsWith("wc://")) {
            uri.replaceFirst("wc:", "wc://")
        } else {
            uri
        }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    private fun addressToHash160(address: String): String? =
        runCatching {
            val decoded = base58Decode(address)
            if (decoded.size != 25) return null
            val payload = decoded.copyOfRange(0, 21)
            val checksum = decoded.copyOfRange(21, 25)
            val digest = MessageDigest.getInstance("SHA-256")
            val actual = digest.digest(digest.digest(payload)).copyOfRange(0, 4)
            if (!checksum.contentEquals(actual)) return null
            "0x" + payload.copyOfRange(1, 21).reversedArray().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }.getOrNull()

    private fun hash160ToAddress(hash: String): String? =
        runCatching {
            val normalized = hash.removePrefix("0x")
            if (!Regex("^[0-9a-fA-F]{40}$").matches(normalized)) return null
            val scriptHash = normalized.chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
                .reversedArray()
            val payload = byteArrayOf(0x35.toByte()) + scriptHash
            base58Encode(payload + checksum(payload))
        }.getOrNull()

    private fun base58Decode(value: String): ByteArray {
        var number = BigInteger.ZERO
        value.forEach { char ->
            val digit = BASE58_ALPHABET.indexOf(char)
            if (digit < 0) throw IllegalArgumentException("Invalid base58 character")
            number = number.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit.toLong()))
        }
        val bytes = number.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        val leadingZeros = value.takeWhile { it == '1' }.count()
        return ByteArray(leadingZeros) + bytes
    }

    private fun base58Encode(bytes: ByteArray): String {
        var number = BigInteger(1, bytes)
        val output = StringBuilder()
        while (number > BigInteger.ZERO) {
            val division = number.divideAndRemainder(BigInteger.valueOf(58))
            number = division[0]
            output.append(BASE58_ALPHABET[division[1].toInt()])
        }
        repeat(bytes.takeWhile { it == 0.toByte() }.count()) {
            output.append('1')
        }
        return output.reverse().toString()
    }

    private fun checksum(payload: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(digest.digest(payload)).copyOfRange(0, 4)
    }

    private fun TextView.withTopPadding(value: Int): TextView = apply {
        setPadding(paddingLeft, dp(value), paddingRight, paddingBottom)
    }

    private fun View.withTopPadding(value: Int): View = apply {
        setPadding(paddingLeft, dp(value), paddingRight, paddingBottom)
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

    private fun colorForToken(seed: String): Int {
        val palette = intArrayOf(PURPLE, GREEN, ORANGE, TEAL, GOLD)
        val index = seed.lowercase(Locale.US).fold(0) { acc, char -> acc + char.code }.mod(palette.size)
        return palette[index]
    }

    private fun color(rgb: Int): Int =
        Color.rgb((rgb shr 16) and 0xff, (rgb shr 8) and 0xff, rgb and 0xff)

    private fun formatProgressLabel(progressBps: Int): String {
        val normalized = progressBps.coerceIn(0, 10_000)
        return "${normalized / 100}.${(normalized % 100).toString().padStart(2, '0')}%"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun statusBarInset(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun matchParent(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private enum class ForgeTab {
        PAIRS,
        TOKENS,
        ADMIN
    }

    private enum class PairFilter {
        TRENDING,
        ALL,
        GAS,
        NEO,
        SOON
    }

    private enum class DetailTab {
        TRADE,
        DETAILS,
        CURVE
    }

    private enum class PriceDisplayMode {
        QUOTE,
        USD
    }

    private companion object {
        private val FORGE_BG = Color.rgb(18, 18, 18)
        private val HEADER = Color.rgb(33, 33, 33)
        private val SURFACE = Color.rgb(35, 35, 35)
        private val SURFACE_DARK = Color.rgb(25, 25, 25)
        private val TEXT = Color.rgb(245, 245, 245)
        private val MUTED = Color.rgb(166, 177, 188)
        private val BORDER = Color.rgb(73, 50, 42)
        private val ORANGE = Color.rgb(255, 90, 47)
        private val ORANGE_MUTED = Color.rgb(76, 45, 35)
        private val GREEN = Color.rgb(28, 218, 128)
        private val TEAL = Color.rgb(24, 194, 180)
        private val PURPLE = Color.rgb(132, 64, 219)
        private val GOLD = Color.rgb(167, 119, 18)
        private val NETWORK_PRIVATE = Color.rgb(158, 64, 255)
        private val ERROR = Color.rgb(255, 109, 77)
        private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        private const val MARKET_REFRESH_INTERVAL_MS = 15_000L
        private const val QUOTE_USD_REFRESH_INTERVAL_MS = 60_000L
    }
}
