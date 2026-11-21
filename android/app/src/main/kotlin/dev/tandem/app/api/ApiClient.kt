package dev.tandem.app.api

import dev.tandem.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.InputStream

const val PROTOCOL_VERSION = "1"
private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
private val OCTET = "application/octet-stream".toMediaType()

@Serializable
private data class ErrorBody(val code: String? = null, val message: String? = null)

fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
    .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
    .build()

/** A 401 `device_revoked` (C11): this device was removed on the server, and its key works nowhere now. */
val ApiException.isRevoked: Boolean get() = status == 401 && code == "device_revoked"

/** [onRevoked] hears every 401 `device_revoked` answer, before the call throws it. */
class ApiClient(
    private val baseUrl: String,
    private val deviceKey: String?,
    private val http: OkHttpClient = defaultHttpClient(),
    private val onRevoked: () -> Unit = {},
) : Api {
    private fun req(path: String): Request.Builder {
        val b = Request.Builder().url(baseUrl + path).header("Tandem-Protocol", PROTOCOL_VERSION)
        if (deviceKey != null) b.header("Authorization", "Bearer $deviceKey")
        return b
    }

    private fun check(r: Response): Response {
        if (r.isSuccessful) return r
        val text = r.use { it.body.string() }
        val eb = runCatching { ProtocolJson.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
        val e = ApiException(r.code, eb?.code, eb?.message ?: text.ifBlank { r.message })
        if (e.isRevoked) onRevoked()
        throw e
    }

    private fun <T> jsonBody(ser: KSerializer<T>, v: T): RequestBody =
        ProtocolJson.encodeToString(ser, v).toRequestBody(JSON_TYPE)

    private suspend fun <B, R> call(
        method: String, path: String, bodySer: KSerializer<B>?, body: B?, respSer: KSerializer<R>?,
        client: OkHttpClient = http,
    ): R? = withContext(Dispatchers.IO) {
        val rb = if (bodySer != null) jsonBody(bodySer, body as B) else if (method == "GET") null else ByteArray(0).toRequestBody(null)
        val r = client.newCall(req(path).method(method, rb).build()).execute()
        check(r).use { resp ->
            if (respSer == null) null else ProtocolJson.decodeFromString(respSer, resp.body.string())
        }
    }

    override suspend fun serverInfo(): ServerInfo =
        call("GET", "/v1/server-info", null as KSerializer<Unit>?, null, ServerInfo.serializer())!!

    override suspend fun register(email: String, password: String, deviceName: String) =
        call("POST", "/v1/auth/register", RegisterRequest.serializer(),
            RegisterRequest(email, password, deviceName, "android"), DeviceKeyResponse.serializer())!!

    override suspend fun login(email: String, password: String, deviceName: String) =
        call("POST", "/v1/auth/login", LoginRequest.serializer(),
            LoginRequest(email, password, deviceName, "android"), DeviceKeyResponse.serializer())!!

    override suspend fun redeemPairing(code: String, deviceName: String) =
        call("POST", "/v1/pairing/redeem", RedeemPairingRequest.serializer(),
            RedeemPairingRequest(code, deviceName, "android"), DeviceKeyResponse.serializer())!!

    override suspend fun putStatus(req: DeviceStatusRequest) {
        call("PUT", "/v1/devices/me/status", DeviceStatusRequest.serializer(), req, null as KSerializer<Unit>?)
    }

    override suspend fun putStatus(req: DeviceStatusRequest, callTimeoutMillis: Long) {
        val bounded = http.newBuilder().callTimeout(callTimeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        call("PUT", "/v1/devices/me/status", DeviceStatusRequest.serializer(), req, null as KSerializer<Unit>?, bounded)
    }

    override suspend fun putInstalls(emulatorId: String, games: List<InstalledGame>) =
        call("PUT", "/v1/devices/me/installs/$emulatorId", InstallsRequest.serializer(),
            InstallsRequest(games), InstallsResponse.serializer())!!

    override suspend fun forget(emulatorIds: List<String>) {
        call("POST", "/v1/devices/me/forget", ForgetRequest.serializer(), ForgetRequest(emulatorIds), null as KSerializer<Unit>?)
    }

    override suspend fun missingBlobs(hashes: List<String>): List<String> =
        call("POST", "/v1/blobs/missing", MissingBlobsRequest.serializer(),
            MissingBlobsRequest(hashes), MissingBlobsResponse.serializer())!!.missing

    override suspend fun putBlob(hash: String, size: Long, source: () -> InputStream) {
        withContext(Dispatchers.IO) {
            val body = object : RequestBody() {
                override fun contentType() = OCTET
                override fun contentLength() = size
                override fun isOneShot() = false
                override fun writeTo(sink: BufferedSink) {
                    source().use { sink.writeAll(it.source()) }
                }
            }
            check(http.newCall(req("/v1/blobs/$hash").put(body).build()).execute()).close()
        }
    }

    override suspend fun getBlob(hash: String): InputStream = withContext(Dispatchers.IO) {
        val r = check(http.newCall(req("/v1/blobs/$hash").get().build()).execute())
        r.body.byteStream()
    }

    override suspend fun uploadSave(req: SaveUploadRequest) =
        call("POST", "/v1/saves/upload", SaveUploadRequest.serializer(), req, SaveUploadResponse.serializer())!!

    override suspend fun uploadConfig(req: ConfigUploadRequest) =
        call("POST", "/v1/configs/upload", ConfigUploadRequest.serializer(), req, ConfigUploadResponse.serializer())!!

    override suspend fun deliveries(): List<DeliveryDto> =
        call("GET", "/v1/deliveries", null as KSerializer<Unit>?, null, DeliveryList.serializer())!!.items

    override suspend fun ack(id: String, req: AckRequest) {
        call("POST", "/v1/deliveries/$id/ack", AckRequest.serializer(), req, null as KSerializer<Unit>?)
    }

    // ---- app UI (/v1/web/* and the conflict/config endpoints; the device key is accepted) ----

    private suspend fun <R> get(path: String, ser: KSerializer<R>): R =
        call("GET", path, null as KSerializer<Unit>?, null, ser)!!

    override suspend fun games(emulator: String?): List<GameRow> =
        get("/v1/web/games" + query("emulator" to emulator), ListSerializer(GameRow.serializer()))

    override suspend fun game(emulatorId: String, gameKey: String): GameDetail =
        get("/v1/web/games/${encodeSegment(emulatorId)}/${encodeKey(gameKey)}", GameDetail.serializer())

    override suspend fun conflicts(): List<ConflictRow> =
        get("/v1/web/conflicts", ListSerializer(ConflictRow.serializer()))

    override suspend fun resolveConflict(itemId: String, req: ResolveConflictRequest) {
        call("POST", "/v1/conflicts/${encodeSegment(itemId)}/resolve", ResolveConflictRequest.serializer(), req, null as KSerializer<Unit>?)
    }

    override suspend fun firstSync(req: BulkFirstSyncRequest) {
        call("POST", "/v1/conflicts/first-sync/resolve", BulkFirstSyncRequest.serializer(), req, null as KSerializer<Unit>?)
    }

    override suspend fun configs(): ConfigOverview = get("/v1/web/configs", ConfigOverview.serializer())

    override suspend fun joinGroup(groupId: String, deviceId: String) {
        call("POST", "/v1/configs/groups/${encodeSegment(groupId)}/members", JoinGroupRequest.serializer(),
            JoinGroupRequest(deviceId), null as KSerializer<Unit>?)
    }

    override suspend fun applyConfig(req: ApplyConfigRequest) =
        call("POST", "/v1/configs/apply", ApplyConfigRequest.serializer(), req, ApplyConfigResponse.serializer())!!

    override suspend fun keepOwn(deviceId: String, emulatorId: String) {
        call("POST", "/v1/web/devices/${encodeSegment(deviceId)}/setup/keep-own/${encodeSegment(emulatorId)}",
            null as KSerializer<Unit>?, null, null as KSerializer<Unit>?)
    }

    override suspend fun setup(deviceId: String): SetupChecklist =
        get("/v1/web/devices/${encodeSegment(deviceId)}/setup", SetupChecklist.serializer())

    override suspend fun activity(device: String?, before: Long?, limit: Int): ActivityPage =
        get("/v1/activity" + query("device" to device, "before" to before, "limit" to limit), ActivityPage.serializer())

    override suspend fun devices(): List<DeviceSummary> =
        get("/v1/devices", ListSerializer(DeviceSummary.serializer()))

    override suspend fun me(): DeviceSummary = get("/v1/devices/me", DeviceSummary.serializer())

    override suspend fun vault(): List<VaultItemDto> = get("/v1/vault", ListSerializer(VaultItemDto.serializer()))
}

private fun query(vararg params: Pair<String, Any?>): String =
    params.filter { (_, v) -> v != null && v.toString().isNotEmpty() }
        .joinToString("&") { (k, v) -> "$k=${encodeSegment(v.toString())}" }
        .let { if (it.isEmpty()) "" else "?$it" }

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

/** Percent-encodes like JavaScript's encodeURIComponent (UTF-8; leaves A-Z a-z 0-9 - _ . ! ~ * ' ( )). */
internal fun encodeSegment(s: String): String = buildString {
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = (b.toInt() and 0xFF)
        if (c < 128 && UNRESERVED.indexOf(c.toChar()) >= 0) append(c.toChar())
        else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
    }
}

/** A game key as URL path: each segment encoded, '/' kept as the separator (the web client's encodeKey). */
internal fun encodeKey(key: String): String = key.split('/').joinToString("/") { encodeSegment(it) }
