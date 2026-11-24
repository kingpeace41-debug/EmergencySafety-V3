package com.example.emergencysafety

import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.EventContent
import de.connect2x.trixnity.core.model.events.m.room.EncryptedToDeviceEventContent.OlmEncryptedToDeviceEventContent
import de.connect2x.trixnity.crypto.key.DeviceTrustLevel
import de.connect2x.trixnity.crypto.olm.OlmEncryptionService
import de.connect2x.trixnity.crypto.olm.OlmEncryptionServiceImpl
import de.connect2x.trixnity.crypto.olm.OlmStore
import org.koin.dsl.module

internal object MatrixPolicyAudit {
    val checks = java.util.concurrent.atomic.AtomicLong()
    val denied = java.util.concurrent.atomic.AtomicLong()
    @Volatile var policy: java.lang.ref.WeakReference<MatrixSecurityPolicy>? = null
}

// Test policy: all non-verification Olm payloads require verified recipients.
internal class MatrixSecurityPolicy(
    private val delegate: OlmEncryptionService,
    private val store: OlmStore
) : OlmEncryptionService by delegate {
    init { MatrixPolicyAudit.policy = java.lang.ref.WeakReference(this) }

    // Harmless synthetic payload; no room key and no send-to-device call.
    suspend fun testDenial(user: UserId, device: String): Boolean {
        val trust = store.getTrustLevel(user, device)
        check(when (trust) {
            is DeviceTrustLevel.Valid -> !trust.verified
            is DeviceTrustLevel.CrossSigned -> !trust.verified
            else -> true
        }) { "TEST_DEVICE_ALREADY_VERIFIED" }
        val payload = de.connect2x.trixnity.core.model.events.m.room.EncryptionEventContent()
        val before = MatrixPolicyAudit.denied.get()
        val single = encryptOlm(payload, user, device)
        val batch = encryptOlm(payload, setOf(user to device))[user to device]
        return single.exceptionOrNull() is OlmEncryptionService.EncryptOlmError.NoOlmSupported &&
            batch?.exceptionOrNull() is OlmEncryptionService.EncryptOlmError.NoOlmSupported &&
            MatrixPolicyAudit.denied.get() >= before + 2
    }

    private suspend fun allowed(content: EventContent, user: UserId, device: String): Boolean {
        val name = content::class.qualifiedName.orEmpty()
        if (name.startsWith("de.connect2x.trixnity.core.model.events.m.key.verification.")) return true
        MatrixPolicyAudit.checks.incrementAndGet()
        val permitted = when (val trust = store.getTrustLevel(user, device)) {
            is DeviceTrustLevel.Valid -> trust.verified
            is DeviceTrustLevel.CrossSigned -> trust.verified
            else -> false
        }
        if (!permitted) MatrixPolicyAudit.denied.incrementAndGet()
        return permitted
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
