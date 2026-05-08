package social.hushnetwork.forge

import android.app.Application
import android.util.Log
import com.reown.android.Core
import com.reown.android.CoreClient
import com.reown.appkit.client.AppKit
import com.reown.appkit.client.Modal

class ForgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val metadata = Core.Model.AppMetaData(
            name = "FORGE",
            description = "FORGE Android WalletConnect proof of work.",
            url = "https://hushnetwork.social/forge",
            icons = emptyList(),
            redirect = "hushforge://request",
            linkMode = false
        )

        CoreClient.initialize(
            application = this,
            projectId = BuildConfig.REOWN_PROJECT_ID,
            metaData = metadata,
            onError = { error ->
                Log.e(TAG, "Core initialization failed", error.throwable)
                ForgeWalletConnectDelegate.emit("Reown Core error: ${error.throwable.message ?: error.throwable.javaClass.simpleName}")
            }
        )

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
}
