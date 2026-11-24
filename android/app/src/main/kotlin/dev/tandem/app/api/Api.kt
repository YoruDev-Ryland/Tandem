package dev.tandem.app.api

import dev.tandem.app.model.*
import java.io.InputStream

/**
 * The server's answer for a game's cover (GET /v1/web/art/{emulator}/{key}): [Image] (200) with its bytes, [Pending]
 * (202: it is being fetched, ask again in a moment), [None] (404: the sources have nothing, or art is off), or
 * [Failed]: the question didn't get an answer (no network, a timeout, a 5xx). Failed is not "no art": it is worth
 * asking again later.
 */
sealed interface ArtResult {
    class Image(val bytes: ByteArray) : ArtResult
    data object Pending : ArtResult
    data object None : ArtResult
    data object Failed : ArtResult
}

open class ApiException(val status: Int, val code: String?, message: String?) : Exception(message)

interface Api {
    /** The server's name and whether it takes new accounts; asked before signing in. */
    suspend fun serverInfo(): ServerInfo
    suspend fun register(email: String, password: String, deviceName: String): DeviceKeyResponse
    suspend fun login(email: String, password: String, deviceName: String): DeviceKeyResponse
    suspend fun redeemPairing(code: String, deviceName: String): DeviceKeyResponse
    suspend fun putStatus(req: DeviceStatusRequest)
    /** [putStatus] that fails (an IOException) once [callTimeoutMillis] pass, instead of completing late. */
    suspend fun putStatus(req: DeviceStatusRequest, callTimeoutMillis: Long) = putStatus(req)
    suspend fun putInstalls(emulatorId: String, games: List<InstalledGame>): InstallsResponse
    /** Drops the server's record of what this device holds for [emulatorIds] (after a local forget). */
    suspend fun forget(emulatorIds: List<String>)
    suspend fun missingBlobs(hashes: List<String>): List<String>
    /** Which of [items] the server has no copy of (a game deleted on the web). */
    suspend fun missingSaves(items: List<SaveItemKey>): List<SaveItemKey> = emptyList()
    suspend fun putBlob(hash: String, size: Long, source: () -> InputStream)
    suspend fun getBlob(hash: String): InputStream
    suspend fun uploadSave(req: SaveUploadRequest): SaveUploadResponse
    suspend fun uploadConfig(req: ConfigUploadRequest): ConfigUploadResponse
    suspend fun deliveries(): List<DeliveryDto>
    suspend fun ack(id: String, req: AckRequest)

    suspend fun games(emulator: String?): List<GameRow>
    suspend fun game(emulatorId: String, gameKey: String): GameDetail
    suspend fun conflicts(): List<ConflictRow>
    suspend fun resolveConflict(itemId: String, req: ResolveConflictRequest)
    suspend fun firstSync(req: BulkFirstSyncRequest)
    suspend fun configs(): ConfigOverview
    suspend fun joinGroup(groupId: String, deviceId: String)
    suspend fun applyConfig(req: ApplyConfigRequest): ApplyConfigResponse
    suspend fun keepOwn(deviceId: String, emulatorId: String)
    suspend fun setup(deviceId: String): SetupChecklist
    suspend fun activity(device: String?, before: Long?, limit: Int = 50): ActivityPage
    suspend fun devices(): List<DeviceSummary>
    suspend fun me(): DeviceSummary
    /** The vault's items, to learn a Vault delivery's category and name (a delivery carries only the item id). */
    suspend fun vault(): List<VaultItemDto> = emptyList()
    /** The cover of [gameKey], for the shelf's card. Never throws. */
    suspend fun art(emulatorId: String, gameKey: String): ArtResult = ArtResult.None
    /** The real case spine of [gameKey] (/v1/web/spine), same answers as [art]. Never throws. */
    suspend fun spine(emulatorId: String, gameKey: String): ArtResult = ArtResult.None
}
