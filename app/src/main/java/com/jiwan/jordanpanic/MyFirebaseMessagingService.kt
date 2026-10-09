package com.jiwan.jordanpanic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // data로 온 걸 우선 쓰고, 없으면 notification 쪽을 쓴다
        val title = remoteMessage.data["notiTitle"]
            ?: remoteMessage.notification?.title
            ?: "조던 공황 알림"
        val body = remoteMessage.data["notiBody"]
            ?: remoteMessage.notification?.body
            ?: "나스닥 공황 신호 감지!"
        sendNotification(title, body)
    }

    private fun sendNotification(title: String, body: String) {
        val channelId = "jordan_panic_channel"
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            channelId, "공황 알림",
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(channel)

        // 알림 누르면 앱이 열리면서, 알림 내용을 같이 넘겨준다
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("notiTitle", title)
            putExtra("notiBody", body)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(0, notification)
    }
}
