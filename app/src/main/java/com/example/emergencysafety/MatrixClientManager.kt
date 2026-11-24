package com.example.emergencysafety

import android.content.Context
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
