package dev.starlight.charge

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

data class NoteItem(val app: String, val title: String, val icon: Drawable?)

/** Keeps the latest notifications so the stand-by screen can list them. */
class NotificationWatcher : NotificationListenerService() {

    companion object {
        @Volatile
        var items: List<NoteItem> = emptyList()
            private set
        var onChange: (() -> Unit)? = null

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, NotificationWatcher::class.java).flattenToString()
            return enabled.split(':').any { it == me }
        }

        /** Android doesn't always bind a listener after an update; ask it to. */
        fun rebind(context: Context) {
            if (isEnabled(context)) requestRebind(ComponentName(context, NotificationWatcher::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onListenerConnected() = refresh()
    override fun onNotificationPosted(sbn: StatusBarNotification) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification) = refresh()

    private fun refresh() {
        val active = try {
            activeNotifications ?: return
        } catch (_: SecurityException) {
            return
        }
        items = active
            .filter { !it.isOngoing && it.packageName != packageName &&
                it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .sortedByDescending { it.postTime }
            .mapNotNull { sbn ->
                val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                    ?: return@mapNotNull null
                NoteItem(label(sbn.packageName), title, icon(sbn.packageName))
            }
            .distinctBy { it.app + it.title }
            .take(4)
        main.post { onChange?.invoke() }
    }

    private fun label(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun icon(pkg: String) = try {
        packageManager.getApplicationIcon(pkg)
    } catch (_: Exception) {
        null
    }
}
