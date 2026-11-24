package com.example.emergencysafety

import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.EventContent
import de.connect2x.trixnity.core.model.events.m.room.EncryptedToDeviceEventContent.OlmEncryptedToDeviceEventContent
import de.connect2x.trixnity.crypto.key.DeviceTrustLevel
import de.connect2x.trixnity.crypto.olm.OlmEncryptionService
import de.connect2x.trixnity.crypto.olm.OlmEncryptionServiceImpl
import de.connect2x.trixnity.crypto.olm.OlmStore
import org.koin.dsl.module

// Test policy: all non-verification Olm payloads require verified recipients.
internal class MatrixSecurityPolicy(
    private val delegate: OlmEncryptionService,
    private val store: OlmStore
) : OlmEncryptionService by delegate {
    private suspend fun allowed(content: EventContent, user: UserId, device: String): Boolean {
        val name = content::class.qualifiedName.orEmpty()
        if (name.startsWith("de.connect2x.trixnity.core.model.events.m.key.verification.")) return true
        return when (val trust = store.getTrustLevel(user, device)) {
            is DeviceTrustLevel.Valid -> trust.verified
            is DeviceTrustLevel.CrossSigned -> trust.verified
            else -> false
        }
    }

    private fun denied(): Result<OlmEncryptedToDeviceEventContent> =
        Result.failure(OlmEncryptionService.EncryptOlmError.NoOlmSupported("ES: recipient not verified"))

    override suspend fun encryptOlm(content: EventContent, recipientUserId: UserId,
        recipientDeviceId: String): Result<OlmEncryptedToDeviceEventContent> =
        if (allowed(content, recipientUserId, recipientDeviceId))
            delegate.encryptOlm(content, recipientUserId, recipientDeviceId)
        else denied()

    override suspend fun encryptOlm(content: EventContent, recipients: Set<Pair<UserId, String>>):
        Map<Pair<UserId, String>, Result<OlmEncryptedToDeviceEventContent>> {
        val permitted = recipients.filter { (user, device) -> allowed(content, user, device) }.toSet()
        val rejected = (recipients - permitted).associateWith { denied() }
        return rejected + if (permitted.isEmpty()) emptyMap() else delegate.encryptOlm(content, permitted)
    }
}

internal fun createMatrixSecurityModule() = module {
    single<OlmEncryptionService> { MatrixSecurityPolicy(get<OlmEncryptionServiceImpl>(), get<OlmStore>()) }
}
