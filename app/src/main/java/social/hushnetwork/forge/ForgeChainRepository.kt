package social.hushnetwork.forge

import android.util.Base64
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.NumberFormat
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

private data class MutableMarketCandle(
    val time: Long,
    var open: BigInteger,
    var high: BigInteger,
    var low: BigInteger,
    var close: BigInteger,
    var volume: BigInteger
)

private data class RpcBatchCall(
    val method: String,
    val params: JSONArray
)

private data class TxRef(
    val blockIndex: Int,
    val txIndex: Int,
    val txHash: String,
    val occurredAt: Long
)

private data class MarketTradeEvent(
    val blockIndex: Int,
    val txIndex: Int,
    val notificationIndex: Int,
    val occurredAt: Long,
    val txHash: String,
    val side: String,
    val traderHash: String,
    val traderAddress: String,
    val quoteAsset: String,
    val quoteAmount: BigInteger,
    val tokenAmount: BigInteger,
    val price: BigInteger
)

private data class MarketReplay(
    val indexedThroughBlock: Int,
    val tradeEvents: List<MarketTradeEvent>,
    val holderBalances: Map<String, BigInteger>
)

private data class MutableTraderStats(
    var totalTrades: Int = 0,
    var buyVolume: BigInteger = BigInteger.ZERO,
    var sellVolume: BigInteger = BigInteger.ZERO
)

class ForgeChainRepository(
    private val rpcUrl: String = BuildConfig.DEFAULT_RPC_URL,
    private val factoryHash: String = BuildConfig.FACTORY_CONTRACT_HASH,
    private val routerHash: String = BuildConfig.BONDING_CURVE_ROUTER_HASH,
    private val activityApiBaseUrl: String = BuildConfig.FORGE_ACTIVITY_API_BASE_URL
) {
    fun load(address: String?): ForgeDashboard {
        val warnings = mutableListOf<String>()
        val networkMagic = readNetworkMagic()
        val factoryConfig = runCatching { readFactoryConfig() }
            .onFailure { warnings += "Factory config unavailable: ${it.message ?: it.javaClass.simpleName}" }
            .getOrNull()

        val factoryTokens = runCatching { readFactoryTokens() }
            .onFailure { warnings += "Factory tokens unavailable: ${it.message ?: it.javaClass.simpleName}" }
            .getOrDefault(emptyList())

        val walletBalances = if (address.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { readWalletBalances(address) }
                .onFailure { warnings += "Wallet balances unavailable: ${it.message ?: it.javaClass.simpleName}" }
                .getOrDefault(emptyList())
        }

        val tokensByHash = linkedMapOf<String, ForgeToken>()
        factoryTokens.forEach { token -> tokensByHash[token.contractHash.lowercase(Locale.US)] = token }
        walletBalances.forEach { balance ->
            val key = balance.contractHash.lowercase(Locale.US)
            val existing = tokensByHash[key]
            tokensByHash[key] = if (existing == null) {
                resolveTokenMetadata(balance.contractHash).copy(walletBalance = balance.amount)
            } else {
                existing.copy(walletBalance = balance.amount)
            }
        }

        val pairs = runCatching { readMarketPairs(factoryTokens.filter { !it.isNative }) }
            .onFailure { warnings += "Pairs unavailable: ${it.message ?: it.javaClass.simpleName}" }
            .getOrDefault(emptyList())

        return ForgeDashboard(
            networkMagic = networkMagic,
            factoryHash = factoryHash,
            routerHash = routerHash,
            factoryConfig = factoryConfig,
            tokens = tokensByHash.values.sortedWith(compareBy<ForgeToken> { it.isNative }.thenBy { it.symbol }),
            pairs = pairs.sortedWith(compareByDescending<ForgePair> { it.createdAt ?: 0L }.thenBy { it.pairLabel }),
            walletBalances = walletBalances,
            warning = warnings.firstOrNull()
        )
    }

    fun loadMarketCandles(pair: ForgePair): List<ForgeMarketCandle> =
        if (activityApiBaseUrl.isNotBlank()) {
            readMarketCandlesFromApi(pair.token.contractHash)
        } else {
            readMarketCandles(pair)
        }

    fun loadMarketActivity(pair: ForgePair): ForgeMarketActivity =
        readMarketActivity(pair)

    fun getBondingCurveBuyQuote(tokenHash: String, quoteIn: BigInteger): ForgeBuyQuote {
        val stack = invokeFunctionWithAliases(
            routerHash,
            listOf("getBuyQuote", "GetBuyQuote"),
            JSONArray()
                .put(hashParam(tokenHash))
                .put(integerParam(quoteIn))
        )
        val tuple = stack.optJSONObject(0)?.optJSONArray("value")
            ?: throw IllegalStateException("GetBuyQuote returned no tuple for $tokenHash")
        if (tuple.length() < 10) throw IllegalStateException("GetBuyQuote returned ${tuple.length()} fields")

        return ForgeBuyQuote(
            tokenHash = tokenHash,
            grossQuoteIn = stackBigInteger(tuple.getJSONObject(0)),
            quoteConsumed = stackBigInteger(tuple.getJSONObject(1)),
            quoteRefund = stackBigInteger(tuple.getJSONObject(2)),
            grossTokenOut = stackBigInteger(tuple.getJSONObject(3)),
            burnAmount = stackBigInteger(tuple.getJSONObject(4)),
            netTokenOut = stackBigInteger(tuple.getJSONObject(5)),
            platformFee = stackBigInteger(tuple.getJSONObject(6)),
            creatorFee = stackBigInteger(tuple.getJSONObject(7)),
            nextPrice = stackBigInteger(tuple.getJSONObject(8)),
            capped = stackBoolean(tuple.getJSONObject(9))
        )
    }

    fun getBondingCurveSellQuote(tokenHash: String, tokenIn: BigInteger): ForgeSellQuote {
        val stack = invokeFunctionWithAliases(
            routerHash,
            listOf("getSellQuote", "GetSellQuote"),
            JSONArray()
                .put(hashParam(tokenHash))
                .put(integerParam(tokenIn))
        )
        val tuple = stack.optJSONObject(0)?.optJSONArray("value")
            ?: throw IllegalStateException("GetSellQuote returned no tuple for $tokenHash")
        if (tuple.length() < 9) throw IllegalStateException("GetSellQuote returned ${tuple.length()} fields")

        return ForgeSellQuote(
            tokenHash = tokenHash,
            grossTokenIn = stackBigInteger(tuple.getJSONObject(0)),
            burnAmount = stackBigInteger(tuple.getJSONObject(1)),
            netTokenIn = stackBigInteger(tuple.getJSONObject(2)),
            grossQuoteOut = stackBigInteger(tuple.getJSONObject(3)),
            netQuoteOut = stackBigInteger(tuple.getJSONObject(4)),
            platformFee = stackBigInteger(tuple.getJSONObject(5)),
            creatorFee = stackBigInteger(tuple.getJSONObject(6)),
            nextPrice = stackBigInteger(tuple.getJSONObject(7)),
            liquidityOkay = stackBoolean(tuple.getJSONObject(8))
        )
    }

    fun waitForTransaction(txHash: String, timeoutMs: Long = 45_000L, intervalMs: Long = 1_500L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val result = rpc("getapplicationlog", JSONArray().put(txHash)).getJSONObject("result")
                val executions = result.optJSONArray("executions")
                val execution = executions?.optJSONObject(0)
                val state = execution?.optString("vmstate").orEmpty()
                if (state.contains("FAULT", ignoreCase = true)) {
                    val exception = execution?.optString("exception").orEmpty()
                    throw IllegalStateException(exception.ifBlank { "Transaction executed with FAULT" })
                }
                if (state.contains("HALT", ignoreCase = true) || executions != null) {
                    return true
                }
            } catch (error: Exception) {
                val message = error.message.orEmpty()
                if (!message.contains("unknown", ignoreCase = true) &&
                    !message.contains("-100", ignoreCase = true)
                ) {
                    throw error
                }
            }
            Thread.sleep(intervalMs)
        }
        return false
    }

    private fun readNetworkMagic(): Int {
        val result = rpc("getversion", JSONArray()).getJSONObject("result")
        return result.getJSONObject("protocol").getInt("network")
    }

    private fun readFactoryTokens(): List<ForgeToken> =
        getAllFactoryTokenHashes().map { resolveTokenMetadata(it) }

    private fun getAllFactoryTokenHashes(): List<String> {
        if (factoryHash.isBlank() || factoryHash == "0x") return emptyList()

        val hashes = mutableListOf<String>()
        var start = 0
        while (true) {
            val result = rpc(
                "findstorage",
                JSONArray()
                    .put(factoryHash)
                    .put("Ag==")
                    .put(start)
            ).getJSONObject("result")

            val values = result.optJSONArray("results") ?: JSONArray()
            for (index in 0 until values.length()) {
                val hash = decodeHash(values.getJSONObject(index).optString("value"))
                if (hash != null) hashes += hash
            }

            if (!result.optBoolean("truncated", false)) break
            start = result.optInt("next", 0)
            if (start == 0) break
        }
        return hashes.distinctBy { it.lowercase(Locale.US) }
    }

    private fun readWalletBalances(address: String): List<ForgeWalletBalance> {
        val result = rpc("getnep17balances", JSONArray().put(address)).getJSONObject("result")
        val balances = result.optJSONArray("balance") ?: return emptyList()
        val items = mutableListOf<ForgeWalletBalance>()
        for (index in 0 until balances.length()) {
            val item = balances.getJSONObject(index)
            val hash = normalizeHash(item.optString("assethash"))
            items += ForgeWalletBalance(
                contractHash = hash,
                symbol = item.optString("symbol", ""),
                name = item.optString("name", ""),
                amount = parseBigInteger(item.optString("amount", "0")),
                decimals = item.optString("decimals", "0").toIntOrNull() ?: 0
            )
        }
        return items
    }

    private fun readFactoryConfig(): ForgeFactoryConfig {
        val stack = invokeFunction(factoryHash, "getConfig", JSONArray())
        val tuple = stack.optJSONObject(0)?.optJSONArray("value")
            ?: throw IllegalStateException("Factory GetConfig returned no tuple")
        if (tuple.length() < 8) throw IllegalStateException("Factory GetConfig returned ${tuple.length()} fields")

        return ForgeFactoryConfig(
            creationFee = stackBigInteger(tuple.getJSONObject(0)),
            operationFee = stackBigInteger(tuple.getJSONObject(1)),
            paused = tuple.getJSONObject(2).optBoolean("value", false),
            ownerHash = stackHash(tuple.getJSONObject(3)) ?: "",
            templateHash = stackHash(tuple.getJSONObject(4)) ?: "",
            templateVersion = stackBigInteger(tuple.getJSONObject(5)),
            templateNefStored = tuple.getJSONObject(6).optBoolean("value", false),
            templateManifestStored = tuple.getJSONObject(7).optBoolean("value", false)
        )
    }

    private fun resolveTokenMetadata(contractHash: String): ForgeToken {
        nativeToken(contractHash)?.let { native ->
            val supply = if (native.symbol == "GAS") {
                runCatching {
                    val stack = invokeFunction(contractHash, "totalSupply", JSONArray())
                    stackBigInteger(stack.getJSONObject(0))
                }.getOrDefault(native.supply)
            } else {
                native.supply
            }
            return native.copy(supply = supply)
        }

        val factoryToken = runCatching { readFactoryToken(contractHash) }.getOrNull()
        val directToken = runCatching { readDirectToken(contractHash) }.getOrNull()

        val symbol = factoryToken?.symbol?.takeIf { it.isNotBlank() }
            ?: directToken?.symbol?.takeIf { it.isNotBlank() }
            ?: shortHash(contractHash)

        return ForgeToken(
            contractHash = contractHash,
            symbol = symbol,
            name = directToken?.name?.takeIf { it.isNotBlank() } ?: symbol,
            supply = directToken?.supply ?: factoryToken?.supply ?: BigInteger.ZERO,
            decimals = directToken?.decimals ?: 0,
            creatorHash = factoryToken?.creatorHash,
            mode = factoryToken?.mode,
            tier = factoryToken?.tier,
            createdAt = factoryToken?.createdAt,
            isNative = false
        )
    }

    private fun readFactoryToken(contractHash: String): ForgeToken? {
        val stack = invokeFunction(
            factoryHash,
            "getToken",
            JSONArray().put(hashParam(contractHash))
        )
        val tuple = stack.optJSONObject(0)?.optJSONArray("value") ?: return null
        if (tuple.length() < 4) return null

        return ForgeToken(
            contractHash = contractHash,
            symbol = stackText(tuple.getJSONObject(0)),
            name = "",
            supply = stackBigInteger(tuple.getJSONObject(2)),
            decimals = 0,
            creatorHash = stackHash(tuple.getJSONObject(1)),
            mode = normalizeMode(stackText(tuple.getJSONObject(3))),
            tier = tuple.optJSONObject(4)?.let { stackBigInteger(it).toInt() },
            createdAt = tuple.optJSONObject(5)?.let { stackBigInteger(it).toLong() },
            isNative = false
        )
    }

    private fun readDirectToken(contractHash: String): ForgeToken {
        val symbol = runCatching { stackText(invokeFunction(contractHash, "symbol", JSONArray()).getJSONObject(0)) }
            .getOrDefault("")
        val name = runCatching { stackText(invokeFunction(contractHash, "getName", JSONArray()).getJSONObject(0)) }
            .getOrDefault(symbol)
        val decimals = runCatching { stackBigInteger(invokeFunction(contractHash, "decimals", JSONArray()).getJSONObject(0)).toInt() }
            .getOrDefault(0)
        val supply = runCatching { stackBigInteger(invokeFunction(contractHash, "totalSupply", JSONArray()).getJSONObject(0)) }
            .getOrDefault(BigInteger.ZERO)

        return ForgeToken(
            contractHash = contractHash,
            symbol = symbol,
            name = name,
            supply = supply,
            decimals = decimals,
            creatorHash = null,
            mode = null,
            tier = null,
            createdAt = null,
            isNative = false
        )
    }

    private fun readMarketPairs(tokens: List<ForgeToken>): List<ForgePair> {
        if (routerHash.isBlank() || routerHash == "0x") return emptyList()

        return tokens.mapNotNull { token ->
            runCatching {
                val stack = invokeFunctionWithAliases(
                    routerHash,
                    listOf("getCurve", "GetCurve"),
                    JSONArray().put(hashParam(token.contractHash))
                )
                val curve = parseCurve(token.contractHash, stack)
                if (curve.contractStatus.equals("NOT_FOUND", ignoreCase = true)) {
                    return@mapNotNull null
                }
                ForgePair(
                    token = token,
                    quoteAsset = curve.quoteAsset,
                    pairLabel = "${token.symbol}/${curve.quoteAsset}",
                    currentPrice = curve.currentPrice,
                    realQuote = curve.realQuote,
                    graduationThreshold = curve.graduationThreshold,
                    progressBps = if (curve.graduationThreshold > BigInteger.ZERO) {
                        curve.realQuote.multiply(BigInteger.valueOf(10_000)).divide(curve.graduationThreshold).toInt()
                    } else {
                        0
                    },
                    graduationReady = curve.graduationReady,
                    totalTrades = curve.totalTrades,
                    marketCap = curve.currentPrice.multiply(curve.totalSupply).divide(PRICE_SCALE),
                    createdAt = token.createdAt ?: curve.createdAt,
                    contractStatus = curve.contractStatus,
                    currentCurveInventory = curve.currentCurveInventory,
                    launchCurveInventory = curve.curveInventory,
                    launchRetainedInventory = curve.retainedInventory,
                    totalSupply = curve.totalSupply,
                    launchProfile = curve.launchProfile
                )
            }.getOrNull()
        }
    }

    private fun invokeFunctionWithAliases(
        contractHash: String,
        operations: List<String>,
        params: JSONArray
    ): JSONArray {
        var lastError: Throwable? = null
        for (operation in operations) {
            try {
                return invokeFunction(contractHash, operation, params)
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("Contract invocation failed")
    }

    private fun parseCurve(tokenHash: String, stack: JSONArray): ForgeCurve {
        val tuple = stack.optJSONObject(0)?.optJSONArray("value")
            ?: throw IllegalStateException("GetCurve returned no tuple for $tokenHash")
        if (tuple.length() < 14) throw IllegalStateException("GetCurve returned ${tuple.length()} fields")

        return ForgeCurve(
            tokenHash = tokenHash,
            contractStatus = stackText(tuple.getJSONObject(0)),
            quoteAsset = stackText(tuple.getJSONObject(1)).uppercase(Locale.US).ifBlank { "GAS" },
            realQuote = stackBigInteger(tuple.getJSONObject(3)),
            currentCurveInventory = stackBigInteger(tuple.getJSONObject(4)),
            graduationThreshold = stackBigInteger(tuple.getJSONObject(6)),
            graduationReady = stackBoolean(tuple.getJSONObject(7)),
            currentPrice = stackBigInteger(tuple.getJSONObject(8)),
            totalTrades = stackBigInteger(tuple.getJSONObject(9)),
            createdAt = stackBigInteger(tuple.getJSONObject(10)).toLong(),
            curveInventory = stackBigInteger(tuple.getJSONObject(11)),
            retainedInventory = stackBigInteger(tuple.getJSONObject(12)),
            totalSupply = stackBigInteger(tuple.getJSONObject(13)),
            launchProfile = tuple.optJSONObject(15)?.let { normalizeLaunchProfile(stackText(it)) }
        )
    }

    private fun readMarketCandles(pair: ForgePair): List<ForgeMarketCandle> {
        val replay = readMarketReplay(pair)
        if (replay.tradeEvents.isEmpty()) return emptyList()

        val candles = linkedMapOf<Long, MutableMarketCandle>()
        replay.tradeEvents
            .sortedWith(compareBy<MarketTradeEvent> { it.blockIndex }.thenBy { it.txIndex }.thenBy { it.notificationIndex })
            .forEach { trade ->
                applyTradeToCandle(candles, trade.occurredAt, trade.price, trade.quoteAmount)
            }

        return buildContinuousCandles(candles.values.toList(), System.currentTimeMillis())
    }

    private fun readMarketActivity(pair: ForgePair): ForgeMarketActivity {
        val replay = readMarketReplay(pair)
        val sortedTrades = replay.tradeEvents.sortedWith(
            compareBy<MarketTradeEvent> { it.blockIndex }
                .thenBy { it.txIndex }
                .thenBy { it.notificationIndex }
        )
        val traderStats = mutableMapOf<String, MutableTraderStats>()
        sortedTrades.forEach { trade ->
            val stats = traderStats.getOrPut(trade.traderHash) { MutableTraderStats() }
            stats.totalTrades += 1
            if (trade.side == "buy") {
                stats.buyVolume += trade.quoteAmount
            } else {
                stats.sellVolume += trade.quoteAmount
            }
        }

        return ForgeMarketActivity(
            tokenHash = pair.token.contractHash,
            indexedThroughBlock = replay.indexedThroughBlock,
            trades = sortedTrades
                .asReversed()
                .take(DEFAULT_ACTIVITY_LIMIT)
                .map { trade ->
                    ForgeTradeHistoryEntry(
                        id = "${trade.txHash}:${trade.notificationIndex}",
                        occurredAt = trade.occurredAt,
                        side = trade.side,
                        trader = trade.traderAddress,
                        quoteAsset = trade.quoteAsset,
                        quoteAmount = trade.quoteAmount,
                        tokenAmount = trade.tokenAmount,
                        price = trade.price,
                        txHash = trade.txHash
                    )
                },
            holders = buildHolderEntries(replay.holderBalances, pair),
            topTraders = buildTopTraderEntries(traderStats)
        )
    }

    private fun readMarketReplay(pair: ForgePair): MarketReplay {
        if (routerHash.isBlank() || routerHash == "0x") {
            return MarketReplay(-1, emptyList(), emptyMap())
        }

        val blockCount = rpc("getblockcount", JSONArray()).getInt("result")
        val startBlock = findFirstBlockAtOrAfter(
            blockCount,
            pair.createdAt?.let { normalizeTimestampToMs(it) - FIFTEEN_MINUTES_SECONDS * 1000L } ?: 0L
        )
        val tradeEvents = mutableListOf<MarketTradeEvent>()
        val holderBalances = mutableMapOf<String, BigInteger>()
        val normalizedTokenHash = normalizeHash(pair.token.contractHash)
        val normalizedRouterHash = normalizeHash(routerHash)
        var indexedThroughBlock = startBlock - 1
        var start = startBlock

        while (start < blockCount) {
            val endExclusive = minOf(blockCount, start + BLOCK_BATCH_SIZE)
            val blockCalls = (start until endExclusive).map { blockIndex ->
                RpcBatchCall("getblock", JSONArray().put(blockIndex).put(1))
            }
            val blockResponses = rpcBatch(blockCalls)
            val txRefs = mutableListOf<TxRef>()

            blockResponses.forEachIndexed { offset, response ->
                val block = response?.optJSONObject("result") ?: return@forEachIndexed
                val blockIndex = start + offset
                val occurredAt = normalizeTimestampToMs(block.optLong("time", 0L))
                val transactions = block.optJSONArray("tx") ?: JSONArray()
                for (txIndex in 0 until transactions.length()) {
                    val txHash = when (val tx = transactions.get(txIndex)) {
                        is JSONObject -> tx.optString("hash")
                        else -> tx.toString()
                    }
                    if (txHash.isNotBlank()) {
                        txRefs += TxRef(blockIndex, txIndex, txHash, occurredAt)
                    }
                }
            }

            txRefs.chunked(LOG_BATCH_SIZE).forEach { chunk ->
                val logResponses = rpcBatch(chunk.map { ref ->
                    RpcBatchCall("getapplicationlog", JSONArray().put(ref.txHash))
                })
                logResponses.forEachIndexed { index, response ->
                    val log = response?.optJSONObject("result") ?: return@forEachIndexed
                    collectMarketReplayEvents(
                        log = log,
                        ref = chunk[index],
                        normalizedRouterHash = normalizedRouterHash,
                        normalizedTokenHash = normalizedTokenHash,
                        tradeEvents = tradeEvents,
                        holderBalances = holderBalances
                    )
                }
            }

            indexedThroughBlock = endExclusive - 1
            start = endExclusive
        }

        return MarketReplay(
            indexedThroughBlock = indexedThroughBlock.coerceAtLeast(0),
            tradeEvents = tradeEvents.takeLast(MAX_MOBILE_TRADES),
            holderBalances = holderBalances
        )
    }

    private fun findFirstBlockAtOrAfter(blockCount: Int, timestampMs: Long): Int {
        if (timestampMs <= 0L || blockCount <= 0) return 0

        var low = 0
        var high = blockCount - 1
        var answer = blockCount
        while (low <= high) {
            val mid = (low + high) / 2
            val block = rpc("getblock", JSONArray().put(mid).put(1)).getJSONObject("result")
            val blockTime = normalizeTimestampToMs(block.optLong("time", 0L))
            if (blockTime >= timestampMs) {
                answer = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }

        return answer.coerceIn(0, blockCount)
    }

    private fun collectMarketReplayEvents(
        log: JSONObject,
        ref: TxRef,
        normalizedRouterHash: String,
        normalizedTokenHash: String,
        tradeEvents: MutableList<MarketTradeEvent>,
        holderBalances: MutableMap<String, BigInteger>
    ) {
        val executions = log.optJSONArray("executions") ?: JSONArray()
        for (executionIndex in 0 until executions.length()) {
            val notifications = executions
                .optJSONObject(executionIndex)
                ?.optJSONArray("notifications")
                ?: JSONArray()

            for (notificationIndex in 0 until notifications.length()) {
                val notification = notifications.optJSONObject(notificationIndex) ?: continue
                val contractHash = normalizeHash(notification.optString("contract"))
                val eventName = notification.optString("eventname")

                if (contractHash.equals(normalizedRouterHash, ignoreCase = true) &&
                    eventName.equals("Trade", ignoreCase = true)
                ) {
                    val trade = parseTradeNotification(notification) ?: continue
                    if (!trade.tokenHash.equals(normalizedTokenHash, ignoreCase = true)) {
                        continue
                    }

                    tradeEvents += MarketTradeEvent(
                        blockIndex = ref.blockIndex,
                        txIndex = ref.txIndex,
                        notificationIndex = notificationIndex,
                        occurredAt = ref.occurredAt,
                        txHash = ref.txHash,
                        side = trade.side,
                        traderHash = trade.traderHash,
                        traderAddress = hash160ToAddress(trade.traderHash) ?: trade.traderHash,
                        quoteAsset = trade.quoteAsset,
                        quoteAmount = trade.quoteAmount,
                        tokenAmount = trade.tokenAmount,
                        price = trade.price
                    )
                    continue
                }

                if (contractHash.equals(normalizedTokenHash, ignoreCase = true) &&
                    eventName.equals("Transfer", ignoreCase = true)
                ) {
                    val transfer = parseTransferNotification(notification) ?: continue
                    applyTransferBalance(holderBalances, transfer.fromHash, transfer.amount.negate())
                    applyTransferBalance(holderBalances, transfer.toHash, transfer.amount)
                }
            }
        }
    }

    private fun readMarketCandlesFromApi(tokenHash: String): List<ForgeMarketCandle> {
        val baseUrl = activityApiBaseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) return emptyList()

        val encodedTokenHash = URLEncoder.encode(tokenHash, StandardCharsets.UTF_8.name())
        val encodedRouterHash = URLEncoder.encode(routerHash, StandardCharsets.UTF_8.name())
        val endpoint = "$baseUrl/api/markets/$encodedTokenHash/activity?routerHash=$encodedRouterHash"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/json")
        }

        val status = connection.responseCode
        val response = readAll(if (status in 200..299) connection.inputStream else connection.errorStream)
        if (status !in 200..299) {
            val error = runCatching { JSONObject(response).optString("error") }.getOrNull()
            throw IllegalStateException(error?.takeIf { it.isNotBlank() } ?: "Market activity API HTTP $status")
        }

        val candles = JSONObject(response).optJSONArray("candles") ?: JSONArray()
        val result = mutableListOf<ForgeMarketCandle>()
        for (index in 0 until candles.length()) {
            val candle = candles.optJSONObject(index) ?: continue
            result += ForgeMarketCandle(
                time = candle.optLong("time"),
                open = parseBigInteger(candle.optString("open", "0")),
                high = parseBigInteger(candle.optString("high", "0")),
                low = parseBigInteger(candle.optString("low", "0")),
                close = parseBigInteger(candle.optString("close", "0")),
                volume = parseBigInteger(candle.optString("volume", "0"))
            )
        }

        return result.takeLast(MAX_MOBILE_CANDLES)
    }

    private data class ParsedMarketTrade(
        val tokenHash: String,
        val traderHash: String,
        val side: String,
        val quoteAsset: String,
        val quoteAmount: BigInteger,
        val tokenAmount: BigInteger,
        val price: BigInteger
    )

    private data class ParsedTransfer(
        val fromHash: String?,
        val toHash: String?,
        val amount: BigInteger
    )

    private fun parseTradeNotification(notification: JSONObject): ParsedMarketTrade? {
        val values = notification.optJSONObject("state")?.optJSONArray("value") ?: return null
        if (values.length() < 9) return null

        val tokenHash = stackHash160(values.optJSONObject(0)) ?: return null
        val traderHash = stackHash160(values.optJSONObject(1)) ?: return null
        val side = stackText(values.getJSONObject(2)).trim().lowercase(Locale.US)
        val quoteAsset = stackText(values.getJSONObject(3)).trim().uppercase(Locale.US)
        if (side != "buy" && side != "sell") return null
        if (quoteAsset != "GAS" && quoteAsset != "NEO") return null

        return ParsedMarketTrade(
            tokenHash = normalizeHash(tokenHash),
            traderHash = normalizeHash(traderHash),
            side = side,
            quoteAsset = quoteAsset,
            quoteAmount = stackBigInteger(values.getJSONObject(6)),
            tokenAmount = stackBigInteger(values.getJSONObject(7)),
            price = stackBigInteger(values.getJSONObject(8))
        )
    }

    private fun parseTransferNotification(notification: JSONObject): ParsedTransfer? {
        val values = notification.optJSONObject("state")?.optJSONArray("value") ?: return null
        if (values.length() < 3) return null

        return ParsedTransfer(
            fromHash = stackHash160(values.optJSONObject(0))?.let { normalizeHash(it) },
            toHash = stackHash160(values.optJSONObject(1))?.let { normalizeHash(it) },
            amount = stackBigInteger(values.getJSONObject(2))
        )
    }

    private fun applyTransferBalance(
        balances: MutableMap<String, BigInteger>,
        holderHash: String?,
        delta: BigInteger
    ) {
        val normalized = holderHash?.let { normalizeHash(it) } ?: return
        if (normalized == ZERO_HASH || delta == BigInteger.ZERO) return

        val next = (balances[normalized] ?: BigInteger.ZERO) + delta
        if (next <= BigInteger.ZERO) {
            balances.remove(normalized)
        } else {
            balances[normalized] = next
        }
    }

    private fun buildHolderEntries(
        balances: Map<String, BigInteger>,
        pair: ForgePair
    ): List<ForgeHolderEntry> {
        val normalizedRouter = normalizeHash(routerHash)
        val outstandingSupply = balances.entries.fold(BigInteger.ZERO) { sum, entry ->
            val hash = normalizeHash(entry.key)
            if (hash == normalizedRouter || hash == ZERO_HASH || entry.value <= BigInteger.ZERO) {
                sum
            } else {
                sum + entry.value
            }
        }

        return balances.entries
            .filter { entry ->
                val hash = normalizeHash(entry.key)
                hash != normalizedRouter && hash != ZERO_HASH && entry.value > BigInteger.ZERO
            }
            .sortedWith { left, right ->
                val balanceCompare = right.value.compareTo(left.value)
                if (balanceCompare != 0) balanceCompare else left.key.compareTo(right.key)
            }
            .take(DEFAULT_ACTIVITY_LIMIT)
            .mapIndexed { index, entry ->
                val hash = normalizeHash(entry.key)
                ForgeHolderEntry(
                    rank = index + 1,
                    address = hash160ToAddress(hash) ?: hash,
                    balance = entry.value,
                    shareBps = if (outstandingSupply > BigInteger.ZERO) {
                        entry.value.multiply(BigInteger.valueOf(10_000)).divide(outstandingSupply).toInt()
                    } else {
                        null
                    }
                )
            }
    }

    private fun buildTopTraderEntries(
        traderStats: Map<String, MutableTraderStats>
    ): List<ForgeTopTraderEntry> =
        traderStats.entries
            .sortedWith { left, right ->
                val tradeCompare = right.value.totalTrades.compareTo(left.value.totalTrades)
                if (tradeCompare != 0) return@sortedWith tradeCompare
                val leftVolume = left.value.buyVolume + left.value.sellVolume
                val rightVolume = right.value.buyVolume + right.value.sellVolume
                val volumeCompare = rightVolume.compareTo(leftVolume)
                if (volumeCompare != 0) volumeCompare else left.key.compareTo(right.key)
            }
            .take(DEFAULT_ACTIVITY_LIMIT)
            .mapIndexed { index, entry ->
                val hash = normalizeHash(entry.key)
                val stats = entry.value
                ForgeTopTraderEntry(
                    rank = index + 1,
                    address = hash160ToAddress(hash) ?: hash,
                    totalTrades = stats.totalTrades,
                    buyVolume = stats.buyVolume,
                    sellVolume = stats.sellVolume,
                    netQuoteVolume = stats.buyVolume - stats.sellVolume
                )
            }

    private fun applyTradeToCandle(
        candles: MutableMap<Long, MutableMarketCandle>,
        occurredAt: Long,
        price: BigInteger,
        volume: BigInteger
    ) {
        val bucketStart = toBucketStartSeconds(occurredAt)
        val existing = candles[bucketStart]
        if (existing == null) {
            candles[bucketStart] = MutableMarketCandle(
                time = bucketStart,
                open = price,
                high = price,
                low = price,
                close = price,
                volume = volume
            )
            return
        }

        if (price > existing.high) existing.high = price
        if (price < existing.low) existing.low = price
        existing.close = price
        existing.volume += volume
    }

    private fun buildContinuousCandles(
        rawCandles: List<MutableMarketCandle>,
        indexedAt: Long
    ): List<ForgeMarketCandle> {
        val sorted = rawCandles.sortedBy { it.time }
        if (sorted.isEmpty()) return emptyList()

        val candlesByTime = sorted.associateBy { it.time }
        val firstBucket = sorted.first().time
        val lastBucket = maxOf(sorted.last().time, toBucketStartSeconds(indexedAt))
        val continuous = mutableListOf<ForgeMarketCandle>()
        var previousClose: BigInteger? = null

        var bucketStart = firstBucket
        while (bucketStart <= lastBucket) {
            val raw = candlesByTime[bucketStart]
            if (raw == null) {
                val close = previousClose
                if (close != null) {
                    continuous += ForgeMarketCandle(
                        time = bucketStart,
                        open = close,
                        high = close,
                        low = close,
                        close = close,
                        volume = BigInteger.ZERO
                    )
                }
            } else {
                val open = previousClose ?: raw.open
                val high = if (raw.high > open) raw.high else open
                val low = if (raw.low < open) raw.low else open
                continuous += ForgeMarketCandle(
                    time = raw.time,
                    open = open,
                    high = high,
                    low = low,
                    close = raw.close,
                    volume = raw.volume
                )
                previousClose = raw.close
            }
            bucketStart += FIFTEEN_MINUTES_SECONDS
        }

        return continuous.takeLast(MAX_MOBILE_CANDLES)
    }

    private fun invokeFunction(contractHash: String, operation: String, params: JSONArray): JSONArray {
        val result = rpc(
            "invokefunction",
            JSONArray()
                .put(contractHash)
                .put(operation)
                .put(params)
        ).getJSONObject("result")

        if (result.optString("state") == "FAULT") {
            throw IllegalStateException(result.optString("exception", "Contract invocation fault"))
        }
        return result.getJSONArray("stack")
    }

    private fun rpc(method: String, params: JSONArray): JSONObject {
        val request = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", method)
            .put("params", params)

        val response = postRpcBody(request.toString())
        val json = JSONObject(response)
        if (json.has("error")) throw IllegalStateException(json.getJSONObject("error").toString())
        return json
    }

    private fun rpcBatch(calls: List<RpcBatchCall>): List<JSONObject?> {
        if (calls.isEmpty()) return emptyList()

        val request = JSONArray()
        calls.forEachIndexed { index, call ->
            request.put(
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", index + 1)
                    .put("method", call.method)
                    .put("params", call.params)
            )
        }

        val response = postRpcBody(request.toString(), readTimeoutMs = 60_000)
        val responseArray = runCatching { JSONArray(response) }
            .getOrElse { JSONArray().put(JSONObject(response)) }
        val byId = mutableMapOf<Int, JSONObject?>()

        for (index in 0 until responseArray.length()) {
            val item = responseArray.optJSONObject(index) ?: continue
            val id = item.optInt("id", index + 1)
            if (item.has("error")) {
                val error = item.getJSONObject("error")
                val message = error.optString("message")
                byId[id] = if (message.contains("unknown transaction", ignoreCase = true)) {
                    null
                } else {
                    throw IllegalStateException("RPC batch error: ${error}")
                }
            } else {
                byId[id] = item
            }
        }

        return (1..calls.size).map { id -> byId[id] }
    }

    private fun postRpcBody(bodyText: String, readTimeoutMs: Int = 15_000): String {
        val body = bodyText.toByteArray(StandardCharsets.UTF_8)
        val connection = (URL(rpcUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Length", body.size.toString())
        }

        connection.outputStream.use { output: OutputStream -> output.write(body) }

        val status = connection.responseCode
        val response = readAll(if (status in 200..299) connection.inputStream else connection.errorStream)
        if (status !in 200..299) throw IllegalStateException("RPC HTTP $status: $response")
        return response
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

    private fun hashParam(hash: String): JSONObject =
        JSONObject()
            .put("type", "Hash160")
            .put("value", hash)

    private fun integerParam(value: BigInteger): JSONObject =
        JSONObject()
            .put("type", "Integer")
            .put("value", value.toString())

    private fun nativeToken(contractHash: String): ForgeToken? =
        when (contractHash.lowercase(Locale.US)) {
            NEO_HASH -> ForgeToken(
                contractHash = NEO_HASH,
                symbol = "NEO",
                name = "NeoToken",
                supply = BigInteger("100000000"),
                decimals = 0,
                creatorHash = null,
                mode = null,
                tier = null,
                createdAt = null,
                isNative = true
            )
            GAS_HASH -> ForgeToken(
                contractHash = GAS_HASH,
                symbol = "GAS",
                name = "GasToken",
                supply = BigInteger.ZERO,
                decimals = 8,
                creatorHash = null,
                mode = null,
                tier = null,
                createdAt = null,
                isNative = true
            )
            else -> null
        }

    private fun decodeHash(base64: String): String? =
        runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            if (bytes.size != 20) return null
            "0x" + bytes.reversedArray().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }.getOrNull()

    private fun stackHash160(item: JSONObject?): String? {
        if (item == null) return null
        if (item.optString("type") == "String") {
            val value = item.optString("value")
            if (Regex("^0x[0-9a-fA-F]{40}$").matches(value)) return normalizeHash(value)
        }
        return stackHash(item)
    }

    private fun stackHash(item: JSONObject): String? =
        if (item.optString("type") == "ByteString" || item.optString("type") == "ByteArray") {
            decodeHash(item.optString("value"))
        } else {
            null
        }

    private fun stackText(item: JSONObject): String {
        val value = item.optString("value", "")
        return when (item.optString("type")) {
            "ByteString", "ByteArray" -> runCatching {
                String(Base64.decode(value, Base64.DEFAULT), StandardCharsets.UTF_8)
            }.getOrDefault("")
            else -> value
        }
    }

    private fun stackBigInteger(item: JSONObject): BigInteger {
        val value = item.optString("value", "0")
        return when (item.optString("type")) {
            "Integer", "String" -> parseBigInteger(value)
            "Boolean" -> if (item.optBoolean("value", false)) BigInteger.ONE else BigInteger.ZERO
            "ByteString", "ByteArray" -> runCatching {
                littleEndianBigInteger(Base64.decode(value, Base64.DEFAULT))
            }.getOrDefault(BigInteger.ZERO)
            else -> BigInteger.ZERO
        }
    }

    private fun stackBoolean(item: JSONObject): Boolean =
        when (item.optString("type")) {
            "Boolean" -> item.optBoolean("value", false)
            else -> stackBigInteger(item) != BigInteger.ZERO
        }

    private fun littleEndianBigInteger(bytes: ByteArray): BigInteger {
        if (bytes.isEmpty()) return BigInteger.ZERO
        return BigInteger(1, bytes.reversedArray())
    }

    private fun normalizeHash(hash: String): String =
        if (hash.startsWith("0x")) hash.lowercase(Locale.US) else "0x${hash.lowercase(Locale.US)}"

    private fun normalizeTimestampToMs(raw: Long): Long =
        if (raw > 10_000_000_000L) raw else raw * 1000L

    private fun normalizeTimestampToSeconds(raw: Long): Long =
        if (raw > 10_000_000_000L) raw / 1000L else raw

    private fun toBucketStartSeconds(raw: Long): Long {
        val timestampSeconds = normalizeTimestampToSeconds(raw)
        return (timestampSeconds / FIFTEEN_MINUTES_SECONDS) * FIFTEEN_MINUTES_SECONDS
    }

    private fun normalizeMode(mode: String): String? =
        when (mode.lowercase(Locale.US)) {
            "community" -> "community"
            "speculation", "speculative" -> "speculative"
            "crowdfunding", "crowdfund" -> "crowdfund"
            "premium" -> "premium"
            else -> null
        }

    private fun normalizeLaunchProfile(profile: String): String? =
        when (profile.trim().lowercase(Locale.US)) {
            "starter" -> "starter"
            "standard" -> "standard"
            "growth" -> "growth"
            "flagship" -> "flagship"
            else -> null
        }

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

    private fun parseBigInteger(value: String): BigInteger =
        runCatching { BigInteger(value.ifBlank { "0" }) }.getOrDefault(BigInteger.ZERO)

    companion object {
        private const val NEO_HASH = "0xef4073a0f2b305a38ec4050e4d3d28bc40ea63f5"
        private const val GAS_HASH = "0xd2a4cff31913016155e38e474a2c06d08be276cf"
        private const val FIFTEEN_MINUTES_SECONDS = 15L * 60L
        private const val MAX_MOBILE_CANDLES = 96
        private const val MAX_MOBILE_TRADES = 250
        private const val DEFAULT_ACTIVITY_LIMIT = 25
        private const val BLOCK_BATCH_SIZE = 250
        private const val LOG_BATCH_SIZE = 100
        private const val ZERO_HASH = "0x0000000000000000000000000000000000000000"
        private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        private val PRICE_SCALE = BigInteger("1000000000000000000")

        fun quoteAssetContractHash(quoteAsset: String): String =
            if (quoteAsset.equals("NEO", ignoreCase = true)) NEO_HASH else GAS_HASH
    }
}

data class ForgeDashboard(
    val networkMagic: Int,
    val factoryHash: String,
    val routerHash: String,
    val factoryConfig: ForgeFactoryConfig?,
    val tokens: List<ForgeToken>,
    val pairs: List<ForgePair>,
    val walletBalances: List<ForgeWalletBalance>,
    val warning: String?
)

data class ForgeToken(
    val contractHash: String,
    val symbol: String,
    val name: String,
    val supply: BigInteger,
    val decimals: Int,
    val creatorHash: String?,
    val mode: String?,
    val tier: Int?,
    val createdAt: Long?,
    val isNative: Boolean,
    val walletBalance: BigInteger? = null
)

data class ForgeWalletBalance(
    val contractHash: String,
    val symbol: String,
    val name: String,
    val amount: BigInteger,
    val decimals: Int
)

data class ForgePair(
    val token: ForgeToken,
    val quoteAsset: String,
    val pairLabel: String,
    val currentPrice: BigInteger,
    val realQuote: BigInteger,
    val graduationThreshold: BigInteger,
    val progressBps: Int,
    val graduationReady: Boolean,
    val totalTrades: BigInteger,
    val marketCap: BigInteger,
    val createdAt: Long?,
    val contractStatus: String,
    val currentCurveInventory: BigInteger,
    val launchCurveInventory: BigInteger,
    val launchRetainedInventory: BigInteger,
    val totalSupply: BigInteger,
    val launchProfile: String?,
    val candles: List<ForgeMarketCandle> = emptyList()
)

data class ForgeMarketCandle(
    val time: Long,
    val open: BigInteger,
    val high: BigInteger,
    val low: BigInteger,
    val close: BigInteger,
    val volume: BigInteger
)

data class ForgeMarketActivity(
    val tokenHash: String,
    val indexedThroughBlock: Int,
    val trades: List<ForgeTradeHistoryEntry>,
    val holders: List<ForgeHolderEntry>,
    val topTraders: List<ForgeTopTraderEntry>
)

data class ForgeTradeHistoryEntry(
    val id: String,
    val occurredAt: Long,
    val side: String,
    val trader: String,
    val quoteAsset: String,
    val quoteAmount: BigInteger,
    val tokenAmount: BigInteger,
    val price: BigInteger,
    val txHash: String
)

data class ForgeHolderEntry(
    val rank: Int,
    val address: String,
    val balance: BigInteger,
    val shareBps: Int?
)

data class ForgeTopTraderEntry(
    val rank: Int,
    val address: String,
    val totalTrades: Int,
    val buyVolume: BigInteger,
    val sellVolume: BigInteger,
    val netQuoteVolume: BigInteger
)

data class ForgeCurve(
    val tokenHash: String,
    val contractStatus: String,
    val quoteAsset: String,
    val realQuote: BigInteger,
    val currentCurveInventory: BigInteger,
    val graduationThreshold: BigInteger,
    val graduationReady: Boolean,
    val currentPrice: BigInteger,
    val totalTrades: BigInteger,
    val createdAt: Long,
    val curveInventory: BigInteger,
    val retainedInventory: BigInteger,
    val totalSupply: BigInteger,
    val launchProfile: String?
)

data class ForgeBuyQuote(
    val tokenHash: String,
    val grossQuoteIn: BigInteger,
    val quoteConsumed: BigInteger,
    val quoteRefund: BigInteger,
    val grossTokenOut: BigInteger,
    val burnAmount: BigInteger,
    val netTokenOut: BigInteger,
    val platformFee: BigInteger,
    val creatorFee: BigInteger,
    val nextPrice: BigInteger,
    val capped: Boolean
)

data class ForgeSellQuote(
    val tokenHash: String,
    val grossTokenIn: BigInteger,
    val burnAmount: BigInteger,
    val netTokenIn: BigInteger,
    val grossQuoteOut: BigInteger,
    val netQuoteOut: BigInteger,
    val platformFee: BigInteger,
    val creatorFee: BigInteger,
    val nextPrice: BigInteger,
    val liquidityOkay: Boolean
)

data class ForgeFactoryConfig(
    val creationFee: BigInteger,
    val operationFee: BigInteger,
    val paused: Boolean,
    val ownerHash: String,
    val templateHash: String,
    val templateVersion: BigInteger,
    val templateNefStored: Boolean,
    val templateManifestStored: Boolean
)

fun formatTokenAmount(amount: BigInteger, decimals: Int, maxFractionDigits: Int = 4): String {
    if (decimals <= 0) return NumberFormat.getIntegerInstance(Locale.US).format(amount)
    val decimal = BigDecimal(amount, decimals).stripTrailingZeros()
    val plain = decimal.toPlainString()
    if (!plain.contains(".")) return NumberFormat.getIntegerInstance(Locale.US).format(decimal.toBigInteger())

    val whole = plain.substringBefore(".")
    val fraction = plain.substringAfter(".").take(maxFractionDigits).trimEnd('0')
    val wholeFormatted = NumberFormat.getIntegerInstance(Locale.US).format(BigInteger(whole.ifBlank { "0" }))
    return if (fraction.isBlank()) wholeFormatted else "$wholeFormatted.$fraction"
}

fun formatQuoteAmount(amount: BigInteger, quoteAsset: String, maxFractionDigits: Int = 2): String {
    val decimals = if (quoteAsset == "GAS") 8 else 0
    return "${formatTokenAmount(amount, decimals, maxFractionDigits)} $quoteAsset"
}

fun formatMarketPrice(amount: BigInteger, quoteAsset: String, tokenDecimals: Int): String {
    if (amount == BigInteger.ZERO) return "0 $quoteAsset"
    val quoteDecimals = if (quoteAsset == "GAS") 8 else 0
    val scale = 18 + quoteDecimals - tokenDecimals
    return "${formatTokenAmount(amount, scale.coerceAtLeast(0), 6)} $quoteAsset"
}

fun formatMarketPriceUsd(
    amount: BigInteger,
    quoteAsset: String,
    tokenDecimals: Int,
    reference: QuoteAssetUsdReference?
): String? {
    if (reference == null) return null
    val quoteDecimals = if (quoteAsset == "GAS") 8 else 0
    val scale = (18 + quoteDecimals - tokenDecimals).coerceAtLeast(0)
    val value = BigDecimal(amount, scale).multiply(BigDecimal.valueOf(reference.priceUsd))
    return formatUsdPrice(value)
}

fun formatQuoteAmountUsd(
    amount: BigInteger,
    quoteAsset: String,
    reference: QuoteAssetUsdReference?,
    compact: Boolean = false
): String? {
    if (reference == null) return null
    val decimals = if (quoteAsset == "GAS") 8 else 0
    val value = BigDecimal(amount, decimals).multiply(BigDecimal.valueOf(reference.priceUsd))
    return if (compact) formatUsdCompactAmount(value) else formatUsdPrice(value)
}

private fun formatUsdPrice(value: BigDecimal): String {
    val absolute = value.abs()
    val maximumFractionDigits = when {
        absolute >= BigDecimal.ONE -> 2
        absolute >= BigDecimal("0.01") -> 4
        absolute >= BigDecimal("0.0001") -> 6
        else -> 8
    }

    return NumberFormat.getCurrencyInstance(Locale.US).apply {
        minimumFractionDigits = 2
        this.maximumFractionDigits = maximumFractionDigits
    }.format(value)
}

private fun formatUsdCompactAmount(value: BigDecimal): String {
    val absolute = value.abs().toDouble()
    if (absolute < 1_000.0) return formatUsdPrice(value)

    val suffix = when {
        absolute >= 1_000_000_000.0 -> "B"
        absolute >= 1_000_000.0 -> "M"
        else -> "K"
    }
    val divisor = when (suffix) {
        "B" -> 1_000_000_000.0
        "M" -> 1_000_000.0
        else -> 1_000.0
    }
    val formatted = NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 1
    }.format(value.toDouble() / divisor)

    return "$$formatted$suffix"
}

fun shortHash(hash: String): String =
    if (hash.length <= 16) hash else "${hash.take(6)}...${hash.takeLast(4)}"

fun shortAddress(address: String): String =
    if (address.length <= 18) address else "${address.take(7)}...${address.takeLast(5)}"

fun compactAddress(address: String): String =
    if (address.length <= 12) address else "${address.take(5)}...${address.takeLast(4)}"

fun relativeAge(timestamp: Long?): String {
    if (timestamp == null || timestamp <= 0L) return "-"
    val createdMs = if (timestamp > 10_000_000_000L) timestamp else timestamp * 1000L
    val minutes = ((System.currentTimeMillis() - createdMs) / 60_000L).coerceAtLeast(1L)
    if (minutes < 60) return "${minutes}m ago"
    val hours = minutes / 60
    if (hours < 24) return "${hours}h ago"
    return "${hours / 24}d ago"
}
