package dev.emusync.app.api

import dev.emusync.app.model.*
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

class ApiClient(
    private val baseUrl: String,
    private val deviceKey: String?,
    private val http: OkHttpClient = defaultHttpClient(),
) : Api {
    private fun req(path: String): Request.Builder {
        val b = Request.Builder().url(baseUrl + path).header("EmuSync-Protocol", PROTOCOL_VERSION)
        if (deviceKey != null) b.header("Authorization", "Bearer $deviceKey")
        return b
    }

    private fun check(r: Response): Response {
        if (r.isSuccessful) return r
        val text = r.use { it.body.string() }
        val eb = runCatching { ProtocolJson.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
        throw ApiException(r.code, eb?.code, eb?.message ?: text.ifBlank { r.message })
    }

    private fun <T> jsonBody(ser: KSerializer<T>, v: T): RequestBody =
        ProtocolJson.encodeToString(ser, v).toRequestBody(JSON_TYPE)

    private suspend fun <B, R> call(
        method: String, path: String, bodySer: KSerializer<B>?, body: B?, respSer: KSerializer<R>?,
    ): R? = withContext(Dispatchers.IO) {
        val rb = if (bodySer != null) jsonBody(bodySer, body as B) else if (method == "GET") null else ByteArray(0).toRequestBody(null)
        val r = http.newCall(req(path).method(method, rb).build()).execute()
        check(r).use { resp ->
            if (respSer == null) null else ProtocolJson.decodeFromString(respSer, resp.body.string())
        }
    }

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

    override suspend fun putInstalls(emulatorId: String, games: List<InstalledGame>) =
        call("PUT", "/v1/devices/me/installs/$emulatorId", InstallsRequest.serializer(),
            InstallsRequest(games), InstallsResponse.serializer())!!

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
}
