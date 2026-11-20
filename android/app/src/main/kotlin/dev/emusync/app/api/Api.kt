package dev.emusync.app.api

import dev.emusync.app.model.*
import java.io.InputStream

class ApiException(val status: Int, val code: String?, message: String?) : Exception(message)

interface Api {
    suspend fun register(email: String, password: String, deviceName: String): DeviceKeyResponse
    suspend fun login(email: String, password: String, deviceName: String): DeviceKeyResponse
    suspend fun redeemPairing(code: String, deviceName: String): DeviceKeyResponse
    suspend fun putStatus(req: DeviceStatusRequest)
    suspend fun putInstalls(emulatorId: String, games: List<InstalledGame>): InstallsResponse
    suspend fun missingBlobs(hashes: List<String>): List<String>
    suspend fun putBlob(hash: String, size: Long, source: () -> InputStream)
    suspend fun getBlob(hash: String): InputStream
    suspend fun uploadSave(req: SaveUploadRequest): SaveUploadResponse
    suspend fun uploadConfig(req: ConfigUploadRequest): ConfigUploadResponse
    suspend fun deliveries(): List<DeliveryDto>
    suspend fun ack(id: String, req: AckRequest)
}
