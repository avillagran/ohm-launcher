package cl.villagranquiroz.ohm_launcher

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

/** A delayed UI confirmation must still refer to the same live control session. */
internal object FluxSessionAuthorization {
    fun <T : Any> withCurrent(session: T, current: () -> T?, action: () -> Unit) {
        check(current() === session) { "Flux control session was replaced or closed" }
        action()
    }

    /** Map transitions and pin writes use the same monitor; no check-to-pin gap. */
    fun <T : Any> withMapped(peers: java.util.concurrent.ConcurrentHashMap<String, T>,
                             id: String, session: T, action: () -> Unit) {
        synchronized(peers) {
            check(peers[id] === session) { "Flux control session was replaced or closed" }
            action()
        }
    }

    fun <T : Any> withPending(live: java.util.concurrent.ConcurrentHashMap<String, T>,
                             pending: java.util.concurrent.ConcurrentHashMap<String, T>,
                             id: String, session: T, action: () -> Unit) {
        synchronized(live) {
            check(pending[id] === session && live[id] == null) {
                "Flux pending session was replaced or connected"
            }
            action()
        }
    }

    fun <T : Any> removeMapped(peers: java.util.concurrent.ConcurrentHashMap<String, T>,
                               id: String, session: T, revoke: () -> Unit) {
        synchronized(peers) {
            check(peers.remove(id, session)) { "Flux control session was replaced or closed" }
            revoke()
        }
    }

    /** Ignore stale remote notices; pinning and revocation share the map monitor. */
    fun <T : Any> revokeIfMapped(peers: java.util.concurrent.ConcurrentHashMap<String, T>,
                                id: String, session: T, revoke: () -> Unit): Boolean = synchronized(peers) {
        if (!peers.remove(id, session)) false else { revoke(); true }
    }
}

/** Reads the outbound control link after its pair reply; EOF invalidates delayed confirmation. */
internal class FluxPairLiveness {
    @Volatile var isOpen = true
        private set

    fun close() { isOpen = false }

    fun watch(read: () -> Pair<Boolean, Long>?, onUnpair: () -> Unit, onClosed: () -> Unit) {
        Thread({
            try {
                while (isOpen) {
                    if (read()?.first == false) { onUnpair(); break }
                }
            } catch (_: Exception) {
                // EOF and socket failures both invalidate the pending confirmation.
            } finally {
                close()
                onClosed()
            }
        }, "ohm-flux-outbound-read").apply { isDaemon = true }.start()
    }
}

/** UI-thread-only ownership of the pending outbound pair's current dialog. */
internal class FluxPairDialogRegistry<S : Any, D : Any>(private val close: (D) -> Unit) {
    private val dialogs = mutableMapOf<S, D>()
    fun replace(session: S, dialog: D) { dialogs.put(session, dialog)?.let(close) }
    fun remove(session: S, dialog: D) { dialogs.remove(session, dialog) }
    fun dismiss(session: S) { dialogs.remove(session)?.let(close) }
    fun clear() { dialogs.values.toList().forEach(close); dialogs.clear() }
}

/** Only a secured, still-current desktop control link may update its input policy. */
internal object FluxInputStateDispatch {
    fun <S : Any> deliver(type: String, body: JSONObject, session: S, current: () -> S?,
                          paired: Boolean, capable: Boolean, open: Boolean,
                          received: (FluxWire.InputState) -> Unit) {
        if (!paired || !capable || !open || current() !== session) return
        val enabled = FluxWire.remoteInputState(type, body) ?: return
        if (current() === session) received(enabled)
    }
}

/** Capture the desktop's outgoing catalog permission once, from its initial TLS identity. */
internal class FluxThemeCatalogDispatch(secureIdentity: String) {
    private val capable = supportsCatalog(secureIdentity)

    fun deliver(type: String, body: JSONObject?, paired: Boolean, open: Boolean,
                current: Boolean, playStore: Boolean, received: (FluxThemeCatalog) -> Unit) {
        if (!capable || !paired || !open || !current || playStore) return
        // Optional malformed packets must not terminate the control link.
        val catalog = runCatching { FluxThemePacket.receive(type, body, paired, playStore) }.getOrNull() ?: return
        received(catalog)
    }

    companion object {
        fun supportsCatalog(secureIdentity: String): Boolean {
            val outgoing = JSONObject(secureIdentity).getJSONObject("body")
                .optJSONArray("outgoingCapabilities") ?: return false
            return (0 until outgoing.length()).any { outgoing.optString(it) == FluxThemePacket.TYPE }
        }
    }
}

/** Internal identity boundary; production continues to use the sealed Android identity. */
internal interface FluxSessionIdentity {
    val deviceId: String
    val certificate: X509Certificate
    fun tlsContext(): javax.net.ssl.SSLContext
    fun pinned(id: String): ByteArray?
    fun pin(id: String, certificate: X509Certificate)
    fun forget(id: String)
}

/** Verified TLS session to one Flux desktop. No plugin packet may precede pairing. */
internal class FluxSession private constructor(
    private val socket: SSLSocket, private val raw: Socket, val id: String, val name: String,
    val certificate: X509Certificate, private val identity: FluxSessionIdentity,
    val supportsMedia: Boolean,
    val supportsRemoteInput: Boolean,
    val supportsInputApproval: Boolean,
    val supportsThemeSelection: Boolean,
    val supportsWallpaper: Boolean,
    private val themeCatalogDispatch: FluxThemeCatalogDispatch,
    val dialect: FluxDialect,
) : Closeable {
    @Volatile private var wallpaperReceived:(JSONObject)->Unit = {}
    val wallpaperOrigin:String get()=identity.deviceId
    fun onWallpaper(callback:(JSONObject)->Unit) { wallpaperReceived=callback }
    fun wallpaperIsLive():Boolean = !BuildConfig.PLAY_STORE_DISTRIBUTION && supportsWallpaper && paired && isOpen() && liveSession()
    private val wallpaperSending=java.util.concurrent.atomic.AtomicBoolean()
    fun sendWallpaperOriginal(meta:FluxWallpaper.Meta,bytes:ByteArray) {
        check(wallpaperSending.compareAndSet(false,true))
        val finished=java.util.concurrent.atomic.AtomicBoolean()
        val timeout=FluxWallpaper.deadlines.schedule({if(finished.compareAndSet(false,true))transportClose.abortRaw()},30,java.util.concurrent.TimeUnit.SECONDS)
        try {FluxWallpaper.send(meta,bytes) {check(!finished.get());sendWallpaper(it)}}
        finally {finished.set(true);timeout.cancel(false);wallpaperSending.set(false)}
    }
    fun sendWallpaper(body:JSONObject, authorized:()->Boolean = { true })=synchronized(socket) {
        check(wallpaperIsLive() && authorized());require(body.toString().toByteArray().size<=FluxWallpaper.MAX_MESSAGE)
        send(FluxWallpaper.TYPE,body)
    }
    fun sendBackgroundGallery(body: JSONObject, authorized: () -> Boolean) {
        val finished = java.util.concurrent.atomic.AtomicBoolean()
        val timeout = FluxWallpaper.deadlines.schedule({
            if (finished.compareAndSet(false, true)) transportClose.abortRaw()
        }, 30, java.util.concurrent.TimeUnit.SECONDS)
        try { sendWallpaper(body) { !finished.get() && authorized() } }
        finally { finished.set(true); timeout.cancel(false) }
    }
    val media = FluxMediaState()
    private val mediaAccess = FluxMediaConsent()
    val mediaConsent: Boolean get() = mediaAccess.enabled
    fun mediaToken(): Long = mediaAccess.token()
    fun acceptsMedia(token: Long): Boolean = mediaAccess.accepts(token)
    fun enableMedia() { check(supportsMedia && paired && isOpen() && liveSession()); mediaAccess.enable() }
    @Volatile private var mediaWriter: FluxMediaWriter<FluxSession>? = null
    fun withdrawMedia() { mediaWriter?.withdraw() ?: mediaAccess.disable(); media.clear() }
    fun abortMediaWrite() { mediaWriter?.abortActive() }
    fun disableMedia() { withdrawMedia(); abortMediaWrite() }
    fun sendMedia(body: JSONObject, token: Long) {
        check(!BuildConfig.PLAY_STORE_DISTRIBUTION)
        checkNotNull(mediaWriter).send(token) { send(FluxMediaProtocol.REQUEST, body) }
    }
    val paired: Boolean get() = identity.pinned(id)?.contentEquals(certificate.encoded) == true
    private val liveness = FluxPairLiveness()
    private val transportClose = FluxControlClose(raw, socket)
    fun isOpen(): Boolean = !transportClose.isClosed && liveness.isOpen && !socket.isClosed && !socket.isInputShutdown && !socket.isOutputShutdown
    private val desktopActions = FluxDesktopActions({ paired }, ::send)
    private val notificationActions = FluxNotificationActions({ paired }, ::send)
    private val fileSending = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var liveSession: () -> Boolean = { false }
    @Volatile private var themeIds: () -> Set<String> = { emptySet() }
    @Volatile private var themeSelectionConsent = false
    @Volatile private var themeSelectionResult: (FluxThemeSelection.Result) -> Unit = {}
    private val themeSelection = FluxThemeSelectionChannel<FluxSession>(
        !BuildConfig.PLAY_STORE_DISTRIBUTION,
        { peer -> if (peer == id && liveSession()) this else null },
        { paired }, ::isOpen, { supportsThemeSelection }, { themeSelectionConsent },
        { themeIds() }, { frame ->
            synchronized(socket) {
                check(paired && isOpen() && liveSession() && themeSelectionConsent && supportsThemeSelection)
                socket.outputStream.write(frame.toByteArray(Charsets.UTF_8))
                socket.outputStream.flush()
            }
        }, { result ->
            themeSelectionConsent = false
            themeSelectionResult(result)
        })
    @Volatile var desktopRemoteInputEnabled: Boolean = false
        private set
    @Volatile private var inputGate: FluxRemoteInputGate<FluxSession>? = null
    private val fileGate = FluxTransferGate { paired && isOpen() && liveSession() }
    fun bindLiveSession(isCurrent: () -> Boolean,
                        peers: java.util.concurrent.ConcurrentHashMap<String, FluxSession>,
                        catalogIds: () -> Set<String> = { emptySet() }) {
        liveSession = isCurrent
        themeIds = catalogIds
        themeSelection.bind(id, this)
        mediaWriter = FluxMediaWriter(peers, id, this, socket, mediaAccess,
            { !BuildConfig.PLAY_STORE_DISTRIBUTION && supportsMedia && paired && isOpen() && liveSession() },
            { transportClose.abortRaw() })
        inputGate = FluxRemoteInputGate(peers, id, this, socket,
            pairedOpen = { paired && isOpen() && liveSession() },
            peerAcceptsRequest = { supportsRemoteInput },
            directEdition = { !BuildConfig.PLAY_STORE_DISTRIBUTION },
            elapsedMs = { android.os.SystemClock.elapsedRealtime() },
            abortWrite = { transportClose.abortRaw() })
    }
    /** Only an explicit approved action may send; a received catalog cannot invoke this. */
    fun requestThemeSelection(id: String, userApproved: Boolean): Boolean {
        if (!userApproved || BuildConfig.PLAY_STORE_DISTRIBUTION) return false
        val alreadyApproved = themeSelectionConsent
        themeSelectionConsent = true
        val result = runCatching {
            themeSelection.request(this.id, this, id, java.util.UUID.randomUUID().toString(),
                System.currentTimeMillis())
        }
        if (!result.getOrDefault(false)) themeSelectionConsent = alreadyApproved
        return result.getOrThrow()
    }

    fun cancelThemeSelection() {
        themeSelectionConsent = false
        themeSelection.cancel(id, this)
    }

    /** Must be called only after Android's secure device-credential confirmation succeeds. */
    fun authorizeRemoteInput(): Long = checkNotNull(inputGate).credentialVerified()
    fun sendRemoteInput(token: Long, action: FluxRemoteInputProtocol.Action) {
        checkNotNull(inputGate).send(token, action) { frame ->
            socket.outputStream.write(FluxWire.outgoing(frame, dialect).toByteArray(Charsets.UTF_8))
            socket.outputStream.flush()
        }
    }
    fun closeRemoteInputView() { inputGate?.closeView() }
    fun beginInputApproval(requestId: String) {
        check(!BuildConfig.PLAY_STORE_DISTRIBUTION && supportsRemoteInput && supportsInputApproval)
        checkNotNull(inputGate).beginRequest(requestId)
        desktopRemoteInputEnabled = false
    }
    fun activeInputRequestId(): String? = inputGate?.activeRequestId()
    /** Synchronous state withdrawal is independent of a potentially blocked TLS writer. */
    fun cancelInputApproval(requestId: String) {
        if (activeInputRequestId() == requestId) {
            inputGate?.cancelRequest(requestId)
            desktopRemoteInputEnabled = false
        }
    }
    /** An approval request never enables input locally; only an authenticated desktop status can. */
    fun requestInputApproval(request: Boolean, requestId: String) {
        require(FluxWire.validRequestId(requestId))
        synchronized(socket) {
            check(!BuildConfig.PLAY_STORE_DISTRIBUTION && supportsRemoteInput && supportsInputApproval &&
                paired && isOpen() && liveSession()) { "Flux desktop input approval is unavailable" }
            if (request) check(activeInputRequestId() == requestId)
            socket.outputStream.write(FluxWire.inputApprovalPacket(request, requestId).toByteArray(Charsets.UTF_8))
            socket.outputStream.flush()
        }
    }
    private var remoteAccepted = false
    fun key(timestamp: Long): String = FluxWire.verificationKey(identity.certificate.publicKey.encoded, certificate.publicKey.encoded, timestamp, dialect)
    fun confirmPair(authorizePin: (() -> Unit) -> Unit) {
        authorizePin {
            check(remoteAccepted && isOpen() && !paired && identity.pinned(id) == null)
            identity.pin(id, certificate)
        }
    }

    fun pair(timestamp: Long): Boolean {
        check(!paired && identity.pinned(id) == null)
        require(FluxWire.pairTimestamp(timestamp))
        socket.soTimeout = 30_000
        send(FluxWire.PAIR, JSONObject().put("pair", true).put("timestamp", timestamp))
        try {
            val response = FluxPairReplyDeadline.await(socket.inputStream, { socket.soTimeout = it }, dialect = dialect)
            remoteAccepted = response
            return response
        } finally { socket.soTimeout = 10_000 }
    }

    fun watchPairConnection(onUnpair: () -> Unit, onClosed: () -> Unit,
                            onScreen: (FluxScreenProtocol.Reply) -> Unit = {},
                            onThemeCatalog: (FluxThemeCatalog) -> Unit = {},
                            onMedia: (JSONObject, Long) -> Unit = { _, _ -> },
                            onShare: (FluxIncomingShare) -> Unit = {},
                            onFileOffer: (FluxIncomingFile) -> Unit = {},
                            onInputState: (FluxWire.InputState) -> Unit = {},
                            onThemeSelected: (FluxThemeSelection.Result) -> Unit = {}) {
        socket.soTimeout = 0
        liveness.watch({ readPair(onScreen, onThemeCatalog, onMedia, onShare, onFileOffer, onInputState, onThemeSelected) }, onUnpair) {
            close()
            onClosed()
        }
    }

    /** A successful TLS write is not a desktop delivery acknowledgement. */
    fun sendText(text: String) {
        check(paired)
        require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
        send(FluxWire.SHARE, JSONObject().put("text", text))
    }

    /** A successful TLS write is not a desktop delivery acknowledgement. */
    fun sendPing(message: String = "") = desktopActions.sendPing(message)
    /** Caller supplies one explicit clipboard snapshot; no monitoring is performed here. */
    fun sendClipboard(text: String) = desktopActions.sendClipboard(text)
    fun sendUrl(url: String) = desktopActions.sendUrl(url)
    fun sendBattery(charge: Int, charging: Boolean) = desktopActions.sendBattery(charge, charging)
    /** Only a paired, live direct-edition control link may announce a screen listener. */
    fun sendScreen(packet: String, captureCurrent: () -> Boolean = { true }) {
        check(!BuildConfig.PLAY_STORE_DISTRIBUTION && paired && isOpen())
        val message = JSONObject(packet)
        check(message.getString("type") == FluxScreenProtocol.TYPE)
        FluxControlWriteGate.write(socket, {
            !BuildConfig.PLAY_STORE_DISTRIBUTION && paired && isOpen() && liveSession() && captureCurrent()
        }) {
            socket.outputStream.write(packet.toByteArray(Charsets.UTF_8))
            socket.outputStream.flush()
        }
    }
    /** Explicit UI-confirmed snapshot only; never invoked by notification listener or reconnect. */
    fun sendNotification(id: String, appName: String, title: String, text: String, timeMillis: Long) {
        check(!BuildConfig.PLAY_STORE_DISTRIBUTION) { "Flux notification sending is unavailable in Play builds" }
        synchronized(socket) {
            notificationActions.sendNotification(id, appName, title, text, timeMillis)
        }
    }

    /** Blocking call: invoke on an IO thread after ACTION_OPEN_DOCUMENT returns a content URI.
     * A successful return means bytes were written, not that fluxd confirmed saving them.
     */
    fun sendFile(resolver: ContentResolver, uri: Uri, name: String? = null, size: Long? = null) {
        fileGate.checkActive()
        check(fileSending.compareAndSet(false, true)) { "A file is already being sent" }
        try {
            FluxFileTransfer.send(resolver, uri, name, size, fileGate, certificate, identity::tlsContext) { frame ->
                synchronized(socket) {
                    fileGate.checkActive()
                    socket.outputStream.write(FluxWire.outgoing(frame, dialect).toByteArray(Charsets.UTF_8))
                    socket.outputStream.flush()
                }
            }
        } finally { fileSending.set(false) }
    }

    fun submitFile(resolver: ContentResolver, uri: Uri, finished: (Result<Unit>) -> Unit) {
        FluxFileTransfer.submit(fileGate, work = { sendFile(resolver, uri) }, finished = finished)
    }

    fun cancelTransfers() { fileGate.revokeAsync() }

    fun incomingFileReceiver(cacheDir: File, current: () -> Any?, authorized: () -> Boolean) =
        FluxIncomingFileReceiver(cacheDir, this, current, authorized, raw.inetAddress,
            certificate.encoded, identity::tlsContext,
            sendTunnel = { tunnel, port -> announceIncomingTunnel(tunnel, port) })

    private fun announceIncomingTunnel(tunnel: String, port: Int) {
        check(!BuildConfig.PLAY_STORE_DISTRIBUTION)
        val frame = FluxWire.tunnelPacket(tunnel, port).toByteArray(Charsets.UTF_8)
        synchronized(socket) {
            check(paired && isOpen() && liveSession()) { "Flux tunnel session was replaced" }
            socket.outputStream.write(frame)
            socket.outputStream.flush()
        }
    }

    private val pairCancelInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    private val closeAfterPairCancel = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Safe from dialog callbacks: never wait for the TLS serializer or perform network I/O here. */
    fun cancelPair(closeAfter: Boolean = false) {
        if (closeAfter) closeAfterPairCancel.set(true)
        if (paired) { if (closeAfter) close(); return }
        if (!isOpen()) return
        if (!pairCancelInProgress.compareAndSet(false, true)) return
        FluxPairCancellation.shared.cancel(send = {
            if (!paired) send(FluxWire.PAIR, JSONObject().put("pair", false))
        }, finished = { sent ->
            if (!sent || closeAfterPairCancel.get()) close()
            pairCancelInProgress.set(false)
        })
    }

    /** Consume one bounded frame; only the pinned desktop can deliver a catalog. */
    fun readPair(
        onScreen: (FluxScreenProtocol.Reply) -> Unit = {},
        onThemeCatalog: (FluxThemeCatalog) -> Unit = {},
        onMedia: (JSONObject, Long) -> Unit = { _, _ -> },
        onShare: (FluxIncomingShare) -> Unit = {},
        onFileOffer: (FluxIncomingFile) -> Unit = {},
        onInputState: (FluxWire.InputState) -> Unit = {},
        onThemeSelected: (FluxThemeSelection.Result) -> Unit = {},
    ): Pair<Boolean, Long>? {
        val receivedLine = FluxWire.readLine(socket.inputStream, FluxThemePacket.MAX_PACKET_BYTES)
        // Ignore malformed application frames without tearing down the TLS read loop.
        val message = runCatching { FluxWire.incoming(receivedLine, dialect) }.getOrNull() ?: return null
        val line = message.toString()
        if (message.optString("type") != FluxWire.PAIR) {
            if (paired && !BuildConfig.PLAY_STORE_DISTRIBUTION) {
                if (message.optString("type") == FluxWire.SHARE) {
                    FluxIncomingFileGate.deliver(line, paired, isOpen() && liveSession(), onFileOffer)
                    FluxIncomingShareGate.deliver(line, paired, isOpen() && liveSession(), onShare)
                    return null
                }
                if (message.optString("type") == FluxMediaProtocol.STATE) {
                    if (supportsMedia && mediaConsent && isOpen() && liveSession())
                        message.optJSONObject("body")?.let { onMedia(it, mediaToken()) }
                    return null
                }
                if (message.optString("type") == FluxWire.REMOTE_INPUT_STATE) {
                    val body = message.optJSONObject("body") ?: JSONObject()
                    if (supportsRemoteInput && supportsInputApproval && isOpen() && liveSession()) {
                        val id = body.opt("requestId") as? String
                        val accepted = inputGate?.receiveStatus(FluxWire.REMOTE_INPUT_STATE, body)
                        if (accepted != null && id != null) {
                            desktopRemoteInputEnabled = accepted
                            onInputState(FluxWire.InputState(accepted, id))
                        }
                    }
                    return null
                }
                if (message.optString("type") == FluxWallpaper.TYPE) {
                    if(wallpaperIsLive()) message.optJSONObject("body")?.let(wallpaperReceived)
                    return null
                }
                if (message.optString("type") == FluxThemePacket.TYPE) {
                    themeCatalogDispatch.deliver(FluxThemePacket.TYPE, message.optJSONObject("body"),
                        paired, isOpen(), liveSession(), BuildConfig.PLAY_STORE_DISTRIBUTION) { catalog ->
                        if (paired && isOpen() && liveSession()) onThemeCatalog(catalog)
                    }
                    return null
                }
                if (message.optString("type") == FluxThemeSelection.SELECTED) {
                    themeSelectionResult = onThemeSelected
                    themeSelection.receive(id, this, line + "\n")
                    return null
                }
                FluxScreenProtocol.reply(message.optString("type"), message.optJSONObject("body") ?: JSONObject())
                    ?.let(onScreen)
            }
            return null
        }
        val body = message.optJSONObject("body") ?: return null
        val pair = body.opt("pair") as? Boolean ?: return null
        val timestamp = if (!body.has("timestamp")) 0L else when (val value = body.opt("timestamp")) {
            is Int -> value.toLong()
            is Long -> value
            else -> return null
        }
        return pair to timestamp
    }

    fun acceptIncomingPair(timestamp: Long, authorizePin: (() -> Unit) -> Unit) {
        fileGate.checkNotRevoked()
        require(!paired && identity.pinned(id) == null)
        require(FluxWire.pairTimestamp(timestamp))
        synchronized(socket) {
            check(isOpen() && liveSession())
            send(FluxWire.PAIR, JSONObject().put("pair", true))
            authorizePin {
                fileGate.checkNotRevoked()
                check(isOpen() && liveSession() && identity.pinned(id) == null)
                identity.pin(id, certificate)
                themeSelection.bind(id, this)
            }
        }
    }

    fun rejectIncomingPair() = cancelPair()
    fun unpair() {
        // Trust has already been revoked on user confirmation; the network notice is best effort.
        runCatching { send(FluxWire.PAIR, JSONObject().put("pair", false)) }
        close()
    }
    fun removeTrust() { themeSelectionConsent = false; themeSelection.revoke(id, this); activeInputRequestId()?.let(::cancelInputApproval); closeRemoteInputView(); desktopRemoteInputEnabled = false; withdrawMedia(); fileGate.revokeAsync(); identity.forget(id) }
    fun setReadTimeout(millis: Int) { socket.soTimeout = millis }

    private fun send(type: String, body: JSONObject) {
        FluxControlWriteGate.write(socket, {
            isOpen() && (type == FluxWire.PAIR || paired && liveSession())
        }) {
            socket.outputStream.write(FluxWire.packet(type, body, dialect).toByteArray(Charsets.UTF_8))
            socket.outputStream.flush()
        }
    }
    override fun close() {
        val scheduled = transportClose.close {
            liveness.close()
            themeSelectionConsent = false
            themeSelection.revoke(id, this)
            activeInputRequestId()?.let(::cancelInputApproval)
            closeRemoteInputView()
            desktopRemoteInputEnabled = false
            withdrawMedia()
            fileGate.revokeAsync()
        }
        if (!scheduled) android.util.Log.w("OhmFlux", "Control socket cleanup capacity exhausted")
    }

    companion object {
        private fun access(identity: FluxIdentity): FluxSessionIdentity = object : FluxSessionIdentity {
            override val deviceId get() = identity.deviceId
            override val certificate get() = identity.certificate
            override fun tlsContext() = identity.tlsContext()
            override fun pinned(id: String) = identity.pinned(id)
            override fun pin(id: String, certificate: X509Certificate) = identity.pin(id, certificate)
            override fun forget(id: String) = identity.forget(id)
        }

        fun accept(raw: Socket, identity: FluxIdentity): FluxSession = accept(raw, access(identity))

        internal fun accept(raw: Socket, identity: FluxSessionIdentity): FluxSession {
            try {
                raw.soTimeout = 10_000
                raw.tcpNoDelay = true
                val plain = FluxWire.plainIdentity(FluxWire.readLine(raw.inputStream), identity.deviceId)
                val id = plain.id
                val dialect = plain.dialect
                val ssl = identity.tlsContext().socketFactory.createSocket(raw, raw.inetAddress.hostAddress, raw.port, true) as SSLSocket
                ssl.useClientMode = true
                ssl.enabledProtocols = arrayOf("TLSv1.2")
                ssl.soTimeout = 10_000
                ssl.startHandshake()
                val cert = ssl.session.peerCertificates.firstOrNull() as? X509Certificate ?: error("Missing desktop certificate")
                verifyCertificate(identity, id, cert)
                val name = phoneName()
                ssl.outputStream.write(FluxWire.identity(identity.deviceId, name, dialect = dialect).toByteArray(Charsets.UTF_8))
                ssl.outputStream.flush()
                val secureIdentity = FluxWire.readLine(ssl.inputStream)
                val secureName = FluxWire.parseIdentity(secureIdentity, id, dialect)
                return FluxSession(ssl, raw, id, secureName, cert, identity,
                    FluxWire.supportsMedia(secureIdentity), FluxWire.supportsRemoteInput(secureIdentity),
                    FluxWire.supportsInputApproval(secureIdentity), FluxWire.supportsThemeSelection(secureIdentity), FluxWire.supportsWallpaper(secureIdentity),
                    FluxThemeCatalogDispatch(secureIdentity), dialect)
            } catch (e: Exception) {
                raw.close()
                throw e
            }
        }

        private fun phoneName() = (Build.MANUFACTURER + " " + Build.MODEL)
            .replace(Regex("[^a-zA-Z0-9 _-]"), "").trim().take(32)

        private fun verifyCertificate(identity: FluxSessionIdentity, id: String, cert: X509Certificate) {
            FluxWire.verifyCertificate(id, cert, identity.pinned(id))
        }

        fun connect(endpoint: FluxDiscovery.Endpoint, identity: FluxIdentity): FluxSession = connect(endpoint, access(identity))

        internal fun connect(endpoint: FluxDiscovery.Endpoint, identity: FluxSessionIdentity): FluxSession {
            require(FluxWire.validId(endpoint.id) && endpoint.port in 1716..1764)
            val raw = Socket()
            try {
                raw.connect(InetSocketAddress(endpoint.address, endpoint.port), 5000)
                raw.soTimeout = 10_000
                raw.tcpNoDelay = true
                val name = phoneName()
                val dialect = endpoint.dialect
                raw.outputStream.write(FluxWire.identity(identity.deviceId, name, endpoint.id, dialect = dialect).toByteArray(Charsets.UTF_8))
                raw.outputStream.flush()
                val ssl = identity.tlsContext().socketFactory.createSocket(raw, endpoint.address.hostAddress, endpoint.port, true) as SSLSocket
                ssl.useClientMode = false // Protocol 8's TCP dialer is the TLS server in both dialects.
                ssl.needClientAuth = true
                ssl.enabledProtocols = arrayOf("TLSv1.2")
                ssl.soTimeout = 10_000
                ssl.startHandshake()
                val cert = ssl.session.peerCertificates.firstOrNull() as? X509Certificate ?: error("Missing desktop certificate")
                verifyCertificate(identity, endpoint.id, cert)
                ssl.outputStream.write(FluxWire.identity(identity.deviceId, name, dialect = dialect).toByteArray(Charsets.UTF_8))
                ssl.outputStream.flush()
                val secureIdentity = FluxWire.readLine(ssl.inputStream)
                val secureName = FluxWire.parseIdentity(secureIdentity, endpoint.id, dialect)
                return FluxSession(ssl, raw, endpoint.id, secureName, cert, identity,
                    FluxWire.supportsMedia(secureIdentity), FluxWire.supportsRemoteInput(secureIdentity),
                    FluxWire.supportsInputApproval(secureIdentity), FluxWire.supportsThemeSelection(secureIdentity), FluxWire.supportsWallpaper(secureIdentity),
                    FluxThemeCatalogDispatch(secureIdentity), dialect)
            } catch (e: Exception) {
                raw.close()
                throw e
            }
        }
    }
}
