package com.example.emergencysafety

import android.content.Context
import de.connect2x.trixnity.clientserverapi.client.ClassicMatrixClientAuthProviderData
import io.ktor.http.Url
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.create
import de.connect2x.trixnity.client.RepositoriesModule
import de.connect2x.trixnity.client.MediaStoreModule
import de.connect2x.trixnity.client.CryptoDriverModule
import de.connect2x.trixnity.client.store.repository.room.TrixnityRoomDatabase
import de.connect2x.trixnity.client.store.repository.room.room
import de.connect2x.trixnity.client.media.okio.okio
import de.connect2x.trixnity.client.cryptodriver.vodozemac.vodozemac
import okio.Path.Companion.toPath
import java.io.File

internal object MatrixClientManager {
    internal data class StorageModules(
        val repositories: RepositoriesModule,
        val media: MediaStoreModule,
        val crypto: CryptoDriverModule
    )

    // Reopen only an existing local Matrix account. Initial provisioning is separate.
    suspend fun reopenExisting(context: Context, sessionId: String): Result<MatrixClient> =
        runCatching {
            val storage = prepareStorage(context, sessionId)
            MatrixClient.create(
                repositoriesModule = storage.repositories,
                mediaStoreModule = storage.media,
                cryptoDriverModule = storage.crypto
            ).getOrThrow()
        }

    // Initial provisioning only; never overwrite an existing crypto database.
    suspend fun initializeNew(
        context: Context,
        sessionId: String,
        accessToken: String,
        refreshToken: String?,
        expiresInMs: Long?
    ): Result<MatrixClient> = runCatching {
        require(accessToken.isNotBlank())
        val storage = prepareStorage(context, sessionId)
        val database = File(context.applicationContext.noBackupFilesDir, "matrix/$sessionId/matrix.db")
        check(!database.exists()) { "Existing Matrix storage must be reopened" }
        MatrixClient.create(
            repositoriesModule = storage.repositories,
            mediaStoreModule = storage.media,
            cryptoDriverModule = storage.crypto,
            authProviderData = ClassicMatrixClientAuthProviderData(
                baseUrl = Url("https://emergency-safety.com"),
                accessToken = accessToken,
                accessTokenExpiresInMs = expiresInMs,
                refreshToken = refreshToken
            )
        ).getOrThrow()
    }

    fun prepareStorage(context: Context, sessionId: String): StorageModules {
        require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(sessionId))
        val root = File(context.applicationContext.noBackupFilesDir, "matrix/$sessionId")
        check(root.isDirectory || root.mkdirs())
        val media = File(root, "media")
        check(media.isDirectory || media.mkdirs())
        val builder = Room.databaseBuilder<TrixnityRoomDatabase>(
            name = File(root, "matrix.db").absolutePath
        ).setDriver(BundledSQLiteDriver())
        return StorageModules(
            repositories = RepositoriesModule.room(builder),
            media = MediaStoreModule.okio(media.absolutePath.toPath()),
            crypto = CryptoDriverModule.vodozemac()
        )
    }
}
