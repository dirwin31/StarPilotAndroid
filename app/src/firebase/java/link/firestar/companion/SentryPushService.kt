package link.firestar.companion

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class SentryPushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = SentryPush.token(this, token)
    override fun onMessageReceived(message: RemoteMessage) { SentryPush.receive(this, message.data) }
}
