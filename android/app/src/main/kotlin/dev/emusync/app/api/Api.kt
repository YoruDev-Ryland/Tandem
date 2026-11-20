package dev.emusync.app.api

import dev.emusync.app.model.*
import java.io.InputStream

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
}
