
package com.example.emergencysafety

/**
 * Ekranda aktif olarak görüntülenen özel sohbeti takip eder.
 *
 * Bildirim servisi, kullanıcının zaten açık tuttuğu
 * sohbet için gereksiz üst bildirim oluşturmamak
 * amacıyla bu bilgiyi kullanabilir.
 */
internal object ActiveChatTracker {

    @Volatile
    private var visibleConnectionId: String? = null

    fun setVisibleChat(connectionId: String) {
        visibleConnectionId = connectionId
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    fun clearVisibleChat() {
        visibleConnectionId = null
    }

    fun isChatVisible(connectionId: String): Boolean {
        val cleanId = connectionId.trim()

        return cleanId.isNotEmpty() &&
            visibleConnectionId == cleanId
    }
}
