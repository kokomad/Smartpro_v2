package com.khedr.smartassistant.service

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.khedr.smartassistant.ai.GeminiFlashClient
import com.khedr.smartassistant.data.AssistantStore
import com.khedr.smartassistant.data.SecureApiKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Reads notification previews and exposes WhatsApp's inline reply action when available. */
class WhatsAppNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** WhatsApp re-posts the same notification several times; remember what was already handled. */
    private val handled = object : LinkedHashMap<String, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 60
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != "com.whatsapp" && sbn.packageName != "com.whatsapp.w4b") return
        val n = sbn.notification
        // Group summaries ("3 new messages") carry no reply action and no real message.
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val store = AssistantStore(this)
        if (!store.enabled()) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val message = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isBlank() || message.isBlank()) return
        // Only contacts on the user's allow-list are ever processed (case-insensitive match).
        val contact = store.allowedContacts().firstOrNull { it.equals(title, ignoreCase = true) } ?: return

        val dedupeKey = "${sbn.key}|${message.hashCode()}"
        synchronized(handled) {
            if (handled.containsKey(dedupeKey)) return
            handled[dedupeKey] = System.currentTimeMillis()
        }

        scope.launch {
            try {
                val canned = store.replies(contact)
                val profile = store.profile(contact)
                val key = SecureApiKeyStore(this@WhatsAppNotificationListener).read()
                val ai = if (!key.isNullOrBlank()) GeminiFlashClient.generate(key, message, contact, profile, canned) else null
                val proposed = if (ai?.success == true) ReplyComposer.clean(ai.text) else ReplyComposer.compose(message, canned, profile)
                if (proposed.isBlank()) return@launch
                // Auto-send only a successfully generated AI response; otherwise (or if sending is
                // impossible) fall back to the approval notification so nothing is lost.
                val sent = store.autoSend() && ai?.success == true && replyInline(sbn, proposed)
                if (!sent) ReplyReviewNotifier.show(this@WhatsAppNotificationListener, contact, message, proposed, sbn)
            } catch (_: Exception) {
                // Never let a single bad notification crash the listener service.
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun replyInline(sbn: StatusBarNotification, text: String): Boolean {
        val actions = sbn.notification.actions ?: return false
        val action = actions.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true } ?: return false
        return sendReply(action, text)
    }

    companion object {
        fun sendReply(action: Notification.Action, text: String): Boolean {
            val input = action.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: return false
            val intent = Intent()
            val results = android.os.Bundle().apply { putCharSequence(input.resultKey, text) }
            RemoteInput.addResultsToIntent(arrayOf(input), intent, results)
            return try { action.actionIntent.send(null, 0, intent); true } catch (_: Exception) { false }
        }
    }
}

/** Reply cleanup and canned fallback used when Gemini is not configured or unavailable. */
object ReplyComposer {
    fun clean(text: String): String = text.trim()
        .removeSurrounding("\"").removeSurrounding("«", "»").removeSurrounding("“", "”")
        .replace(Regex("[\\t ]{2,}"), " ")
        .replace(Regex("([😂🤣])\\1{2,}"), "$1")
        .replace(Regex("\\n{3,}"), "\n\n").trim().take(1200)

    fun compose(message: String, canned: List<String>, profile: String): String {
        val clean = message.lowercase()
        val selected = when {
            clean.contains("السلام") -> canned.firstOrNull { it.contains("وعليكم") || it.contains("سلام") }
            clean.contains("كيفك") || clean.contains("كيف حالك") -> canned.firstOrNull { it.contains("الحمد") || it.contains("تمام") }
            clean.contains("شكرا") || clean.contains("شكراً") -> canned.firstOrNull { it.contains("عفو") || it.contains("أهلا") }
            else -> null
        }
        // No invented personal commitments: return a neutral, reviewable reply if no match.
        return selected ?: canned.firstOrNull() ?: "وصلتني رسالتك، سأرد عليك قريباً."
    }
}
