package social.hushnetwork.forge

import android.os.Handler
import android.os.Looper
import com.reown.android.Core
import com.reown.android.CoreClient
import com.reown.appkit.client.AppKit
import com.reown.appkit.client.Modal
import com.reown.sign.client.Sign
import com.reown.sign.client.SignClient

object ForgeWalletConnect {
    @Volatile
    var isReady: Boolean = false

    val neoMethods = listOf(
        "invokeFunction",
        "testInvoke",
        "signMessage",
        "verifyMessage",
        "getWalletInfo",
        "encrypt",
        "decrypt",
        "decryptFromArray",
        "calculateFee",
        "signTransaction",
        "getNetworkVersion"
    )

    val neoEvents = emptyList<String>()

    val neo3PrivateChain = Modal.Model.Chain(
        chainName = "Neo3 Private Chain",
        chainNamespace = "neo3",
        chainReference = "private",
        requiredMethods = emptyList(),
        optionalMethods = neoMethods,
        events = neoEvents,
        token = Modal.Model.Token(
            name = "GAS",
            symbol = "GAS",
            decimal = 8
        ),
        rpcUrl = BuildConfig.DEFAULT_RPC_URL,
        blockExplorerUrl = null
    )

    fun proposalNamespaces(): Map<String, Modal.Model.Namespace.Proposal> =
        mapOf(
            "neo3" to Modal.Model.Namespace.Proposal(
                chains = listOf(BuildConfig.WALLETCONNECT_CHAIN_ID),
                methods = neoMethods,
                events = neoEvents
            )
        )

    fun activeSessions(): List<Sign.Model.Session> =
        runCatching { SignClient.getListOfActiveSessions() }
            .getOrDefault(emptyList())
            .filter { session ->
                session.namespaces.values.any { namespace ->
                    namespace.accounts.any { account -> account.startsWith("${BuildConfig.WALLETCONNECT_CHAIN_ID}:") } ||
                        namespace.chains.orEmpty().contains(BuildConfig.WALLETCONNECT_CHAIN_ID)
                }
            }

    fun activeSession(preferredTopic: String? = ForgeWalletConnectDelegate.selectedSessionTopic): Sign.Model.Session? {
        val sessions = activeSessions()
        if (preferredTopic != null) {
            sessions.firstOrNull { it.topic == preferredTopic }?.let { return it }
        }

        return sessions.maxByOrNull { it.expiry }
    }

    fun accountAddress(session: Sign.Model.Session): String? =
        session.namespaces.values
            .flatMap { it.accounts }
            .firstOrNull { it.startsWith("${BuildConfig.WALLETCONNECT_CHAIN_ID}:") }
            ?.substringAfterLast(":")

    fun chainId(session: Sign.Model.Session): String? {
        val accounts = session.namespaces.values.flatMap { it.accounts }
        val accountChain = accounts
            .mapNotNull { account ->
                val parts = account.split(":")
                if (parts.size >= 3) "${parts[0]}:${parts[1]}" else null
            }
            .firstOrNull { it == BuildConfig.WALLETCONNECT_CHAIN_ID || it.startsWith("neo3:") }

        if (accountChain != null) {
            return accountChain
        }

        return session.namespaces.values
            .flatMap { it.chains.orEmpty() }
            .firstOrNull { it == BuildConfig.WALLETCONNECT_CHAIN_ID || it.startsWith("neo3:") }
    }
}

object ForgeWalletConnectDelegate : AppKit.ModalDelegate, CoreClient.CoreDelegate {
    private val mainHandler = Handler(Looper.getMainLooper())

    var onEvent: ((String) -> Unit)? = null
    var onSessionChanged: (() -> Unit)? = null
    var onSessionRequestResponse: ((Modal.Model.SessionRequestResponse) -> Unit)? = null
    var selectedSessionTopic: String? = null
        private set

    fun bind() {
        AppKit.setDelegate(this)
        CoreClient.setDelegate(this)
    }

    fun emit(message: String) {
        mainHandler.post {
            onEvent?.invoke(message)
        }
    }

    fun selectSession(topic: String?) {
        selectedSessionTopic = topic
        notifySessionChanged()
    }

    override fun onConnectionStateChange(state: Modal.Model.ConnectionState) {
        emit("WalletConnect relay ${if (state.isAvailable) "available" else "unavailable"}")
    }

    override fun onSessionApproved(approvedSession: Modal.Model.ApprovedSession) {
        when (approvedSession) {
            is Modal.Model.ApprovedSession.WalletConnectSession -> {
                selectedSessionTopic = approvedSession.topic
                val account = approvedSession.accounts.firstOrNull()
                    ?: approvedSession.namespaces.values.firstNotNullOfOrNull { namespace ->
                        namespace.accounts.firstOrNull()
                    }
                emit("Neon approved session: ${account ?: approvedSession.topic}")
                notifySessionChanged()
            }

            is Modal.Model.ApprovedSession.CoinbaseSession -> {
                emit("Unsupported Coinbase session approved.")
                notifySessionChanged()
            }
        }
    }

    override fun onSessionRejected(rejectedSession: Modal.Model.RejectedSession) {
        emit("Session rejected: ${rejectedSession.reason}")
        notifySessionChanged()
    }

    override fun onSessionUpdate(updatedSession: Modal.Model.UpdatedSession) {
        emit("Session updated: ${updatedSession.topic}")
        notifySessionChanged()
    }

    @Suppress("DEPRECATION")
    override fun onSessionEvent(sessionEvent: Modal.Model.SessionEvent) {
        emit("Session event: ${sessionEvent.name}")
    }

    override fun onSessionEvent(sessionEvent: Modal.Model.Event) {
        emit("Session event: ${sessionEvent.name}")
    }

    override fun onSessionExtend(session: Modal.Model.Session) {
        emit("Session extended: ${session.topic}")
        notifySessionChanged()
    }

    override fun onSessionDelete(deletedSession: Modal.Model.DeletedSession) {
        val message = when (deletedSession) {
            is Modal.Model.DeletedSession.Success -> {
                if (selectedSessionTopic == deletedSession.topic) {
                    selectedSessionTopic = null
                }
                "Session deleted: ${deletedSession.reason}"
            }
            is Modal.Model.DeletedSession.Error -> "Session delete error: ${deletedSession.error.message}"
        }
        emit(message)
        notifySessionChanged()
    }

    override fun onSessionRequestResponse(response: Modal.Model.SessionRequestResponse) {
        val resultText = when (val result = response.result) {
            is Modal.Model.JsonRpcResponse.JsonRpcResult ->
                "result=${result.result ?: "null"}"
            is Modal.Model.JsonRpcResponse.JsonRpcError ->
                "error=${result.code} ${result.message}"
        }
        emit("${response.method} response on ${response.chainId ?: "unknown chain"}: $resultText")
        mainHandler.post {
            onSessionRequestResponse?.invoke(response)
        }
    }

    override fun onSessionAuthenticateResponse(sessionAuthenticateResponse: Modal.Model.SessionAuthenticateResponse) {
        emit("Authentication response: ${sessionAuthenticateResponse.javaClass.simpleName}")
    }

    override fun onProposalExpired(proposal: Modal.Model.ExpiredProposal) {
        emit("Session proposal expired: ${proposal.pairingTopic}")
    }

    override fun onRequestExpired(request: Modal.Model.ExpiredRequest) {
        emit("Wallet request expired: ${request.id}")
    }

    override fun onError(error: Modal.Model.Error) {
        emit("WalletConnect error: ${error.throwable.message ?: error.throwable.javaClass.simpleName}")
    }

    override fun onPairingDelete(deletedPairing: Core.Model.DeletedPairing) {
        emit("Pairing deleted: ${deletedPairing.topic}")
    }

    override fun onPairingExpired(expiredPairing: Core.Model.ExpiredPairing) {
        emit("Pairing expired: ${expiredPairing.pairing.topic}")
    }

    override fun onPairingState(pairingState: Core.Model.PairingState) {
        emit("Pairing state: ${pairingState.isPairingState}")
    }

    private fun notifySessionChanged() {
        mainHandler.post {
            onSessionChanged?.invoke()
        }
    }
}
