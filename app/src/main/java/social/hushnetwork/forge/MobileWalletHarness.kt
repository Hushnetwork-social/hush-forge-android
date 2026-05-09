package social.hushnetwork.forge

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import android.util.Log
import org.json.JSONObject

private const val TAG = "ForgeMobileHarness"

object MobileWalletHarness {
    val enabled: Boolean
        get() = BuildConfig.MOBILE_WALLET_HARNESS_PAIR_URL.isNotBlank()

    fun pair(uri: String, onResult: (Result<String>) -> Unit) {
        val pairUrl = BuildConfig.MOBILE_WALLET_HARNESS_PAIR_URL
        if (pairUrl.isBlank()) {
            onResult(Result.failure(IllegalStateException("Mobile wallet harness pair URL is not configured.")))
            return
        }

        Thread {
            val result = runCatching {
                Log.i(TAG, "Posting WalletConnect URI to local harness: $pairUrl")
                postPairUri(pairUrl, uri)
                "Local mobile harness wallet paired."
            }
            result
                .onSuccess { Log.i(TAG, "Local harness wallet pair request succeeded.") }
                .onFailure { Log.e(TAG, "Local harness wallet pair request failed.", it) }
            onResult(result)
        }.start()
    }

    private fun postPairUri(pairUrl: String, uri: String) {
        val body = JSONObject()
            .put("uri", uri)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

        val connection = (URL(pairUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Length", body.size.toString())
        }

        try {
            connection.outputStream.use { output: OutputStream -> output.write(body) }

            val status = connection.responseCode
            val response = readAll(if (status in 200..299) connection.inputStream else connection.errorStream)
            Log.i(TAG, "Harness pair response: HTTP $status $response")
            if (status !in 200..299) {
                throw IllegalStateException("Harness pair failed: HTTP $status $response")
            }
        } finally {
            connection.disconnect()
        }
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
}
