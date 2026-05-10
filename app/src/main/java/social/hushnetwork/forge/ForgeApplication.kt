package social.hushnetwork.forge

import android.app.Application
import android.util.Log
import com.reown.android.Core
import com.reown.android.CoreClient
import com.reown.android.relay.ConnectionType
import com.reown.appkit.client.AppKit
import com.reown.appkit.client.Modal

class ForgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val metadata = Core.Model.AppMetaData(
            name = "FORGE",
            description = "FORGE Android WalletConnect proof of work.",
            url = "https://hushnetwork.social/forge",
            icons = listOf(BuildConfig.APP_ICON_URL),
            redirect = "hushforge://request",
            linkMode = false
        )

        initializeReownCore(metadata)

        AppKit.setChains(listOf(ForgeWalletConnect.neo3PrivateChain))
        AppKit.initialize(
            Modal.Params.Init(core = CoreClient),
            onSuccess = {
                ForgeWalletConnectDelegate.bind()
                ForgeWalletConnect.isReady = true
                ForgeWalletConnectDelegate.emit("WalletConnect ready for ${BuildConfig.WALLETCONNECT_CHAIN_ID}")
            },
            onError = { error ->
                Log.e(TAG, "AppKit initialization failed", error.throwable)
                ForgeWalletConnectDelegate.emit("AppKit error: ${error.throwable.message ?: error.throwable.javaClass.simpleName}")
            }
        )
    }

    private companion object {
        private const val TAG = "ForgeApplication"
    }

    private fun initializeReownCore(metadata: Core.Model.AppMetaData) {
        if (BuildConfig.REOWN_PROJECT_ID.isBlank()) {
            Log.w(TAG, "Reown Core not initialized because FORGE_REOWN_PROJECT_ID is not configured.")
            ForgeWalletConnectDelegate.emit("WalletConnect disabled: configure FORGE_REOWN_PROJECT_ID.")
            return
        }

        val onError = { error: Core.Model.Error ->
            Log.e(TAG, "Core initialization failed", error.throwable)
            ForgeWalletConnectDelegate.emit("Reown Core error: ${error.throwable.message ?: error.throwable.javaClass.simpleName}")
        }

        if (BuildConfig.REOWN_RELAY_URL.isBlank()) {
            CoreClient.initialize(
                application = this,
                projectId = BuildConfig.REOWN_PROJECT_ID,
                metaData = metadata,
                onError = onError
            )
            return
        }

        CoreClient.initialize(
            metaData = metadata,
            relayServerUrl = reownRelayServerUrl(),
            connectionType = ConnectionType.AUTOMATIC,
            application = this,
            onError = onError
        )
    }

    private fun reownRelayServerUrl(): String {
        val relayUrl = BuildConfig.REOWN_RELAY_URL
        if (relayUrl.contains("projectId=")) return relayUrl
        val separator = if (relayUrl.contains("?")) "&" else "?"
        return "$relayUrl${separator}projectId=${BuildConfig.REOWN_PROJECT_ID}"
    }
}
