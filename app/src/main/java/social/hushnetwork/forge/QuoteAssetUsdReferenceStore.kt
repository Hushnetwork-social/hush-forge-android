package social.hushnetwork.forge

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.json.JSONObject

data class QuoteAssetUsdReference(
    val asset: String,
    val provider: String,
    val priceUsd: Double,
    val lastUpdatedAt: Long?,
    val fetchedAt: Long
)

object QuoteAssetUsdReferenceStore {
    private const val CACHE_TTL_MS = 60_000L

    private val lock = Any()
    private var references: Map<String, QuoteAssetUsdReference> = emptyMap()
    private var loading = false
    private var lastError: String? = null

    fun reference(asset: String): QuoteAssetUsdReference? =
        synchronized(lock) { references[asset.uppercase(Locale.US)] }

    fun error(): String? =
        synchronized(lock) { lastError }

    fun refresh(assets: Collection<String>, force: Boolean = false): Map<String, QuoteAssetUsdReference> {
        val requestedAssets = normalizeAssets(assets)
        if (requestedAssets.isEmpty()) return emptyMap()

        synchronized(lock) {
            val now = System.currentTimeMillis()
            val fresh = requestedAssets.all { asset ->
                references[asset]?.let { now - it.fetchedAt < CACHE_TTL_MS } == true
            }
            if (!force && fresh) {
                return requestedAssets.mapNotNull { asset -> references[asset]?.let { asset to it } }.toMap()
            }
            if (loading) {
                return requestedAssets.mapNotNull { asset -> references[asset]?.let { asset to it } }.toMap()
            }
            loading = true
        }

        return try {
            val loaded = fetch(requestedAssets)
            synchronized(lock) {
                references = references + loaded
                lastError = null
                requestedAssets.mapNotNull { asset -> references[asset]?.let { asset to it } }.toMap()
            }
        } catch (error: Exception) {
            synchronized(lock) {
                lastError = error.message ?: error.javaClass.simpleName
                requestedAssets.mapNotNull { asset -> references[asset]?.let { asset to it } }.toMap()
            }
        } finally {
            synchronized(lock) {
                loading = false
            }
        }
    }

    private fun fetch(assets: List<String>): Map<String, QuoteAssetUsdReference> =
        if (BuildConfig.FORGE_MARKET_REFERENCE_API_BASE_URL.isNotBlank()) {
            fetchFromForgeApi(assets)
        } else {
            fetchFromCoinGecko(assets)
        }

    private fun fetchFromForgeApi(assets: List<String>): Map<String, QuoteAssetUsdReference> {
        val baseUrl = BuildConfig.FORGE_MARKET_REFERENCE_API_BASE_URL.trim().trimEnd('/')
        val encodedAssets = URLEncoder.encode(assets.joinToString(","), StandardCharsets.UTF_8.name())
        val payload = httpGetJson("$baseUrl/api/market-reference?assets=$encodedAssets")
        val provider = payload.optString("provider", "CoinGecko")
        val fetchedAt = payload.optLong("fetchedAt", System.currentTimeMillis())
        val prices = payload.optJSONObject("prices") ?: return emptyMap()

        return assets.mapNotNull { asset ->
            val entry = prices.optJSONObject(asset) ?: return@mapNotNull null
            if (!entry.has("usd")) return@mapNotNull null
            asset to QuoteAssetUsdReference(
                asset = asset,
                provider = provider,
                priceUsd = entry.optDouble("usd"),
                lastUpdatedAt = entry.optLong("lastUpdatedAt").takeIf { it > 0 },
                fetchedAt = fetchedAt
            )
        }.toMap()
    }

    private fun fetchFromCoinGecko(assets: List<String>): Map<String, QuoteAssetUsdReference> {
        val ids = assets.mapNotNull { coinGeckoId(it) }.distinct()
        if (ids.isEmpty()) return emptyMap()

        val query = "ids=${URLEncoder.encode(ids.joinToString(","), StandardCharsets.UTF_8.name())}" +
            "&vs_currencies=usd&include_last_updated_at=true"
        val payload = httpGetJson("https://api.coingecko.com/api/v3/simple/price?$query")
        val fetchedAt = System.currentTimeMillis()

        return assets.mapNotNull { asset ->
            val id = coinGeckoId(asset) ?: return@mapNotNull null
            val entry = payload.optJSONObject(id) ?: return@mapNotNull null
            if (!entry.has("usd")) return@mapNotNull null
            asset to QuoteAssetUsdReference(
                asset = asset,
                provider = "CoinGecko",
                priceUsd = entry.optDouble("usd"),
                lastUpdatedAt = entry.optLong("last_updated_at").takeIf { it > 0 },
                fetchedAt = fetchedAt
            )
        }.toMap()
    }

    private fun httpGetJson(endpoint: String): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
        }

        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (status !in 200..299) throw IllegalStateException("Market reference HTTP $status: $response")
        return JSONObject(response)
    }

    private fun normalizeAssets(assets: Collection<String>): List<String> =
        assets
            .map { it.uppercase(Locale.US) }
            .filter { it == "GAS" || it == "NEO" }
            .distinct()

    private fun coinGeckoId(asset: String): String? =
        when (asset.uppercase(Locale.US)) {
            "GAS" -> "gas"
            "NEO" -> "neo"
            else -> null
        }
}
