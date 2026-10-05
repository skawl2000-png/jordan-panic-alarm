package com.jiwan.jordanpanic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.jiwan.jordanpanic.ui.theme.JordanPanicAlarmTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FirebaseMessaging.getInstance().subscribeToTopic("jordan_panic")

        val channel = NotificationChannel(
            "jordan_panic_channel",
            "조던 공황 알림",
            NotificationManager.IMPORTANCE_HIGH
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        setContent {
            JordanPanicAlarmTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    JordanDashboard()
                }
            }
        }
    }
}

@Composable
fun JordanDashboard() {
    var nasdaqChange by remember { mutableStateOf("불러오는 중...") }
    var nvdaPrice by remember { mutableStateOf("불러오는 중...") }
    var nvdaChange by remember { mutableStateOf("") }
    var nvdaSignal by remember { mutableStateOf("불러오는 중...") }
    var aaplPrice by remember { mutableStateOf("불러오는 중...") }
    var aaplChange by remember { mutableStateOf("") }
    var panicCount by remember { mutableStateOf("불러오는 중...") }
    var reentrySignal by remember { mutableStateOf("불러오는 중...") }
    var updatedAt by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val db = FirebaseFirestore.getInstance()
        db.collection("market").document("latest")
            .get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    nasdaqChange = document.getString("nasdaqChange") + "%"
                    nvdaPrice = "$" + document.getString("nvdaPrice")
                    nvdaChange = document.getString("nvdaChange") + "%"
                    nvdaSignal = document.getString("nvdaSignal") ?: ""
                    aaplPrice = "$" + document.getString("aaplPrice")
                    aaplChange = document.getString("aaplChange") + "%"
                    panicCount = document.getLong("panicCount").toString() + "회"
                    reentrySignal = document.getString("reentrySignal") ?: ""
                    updatedAt = document.getString("updatedAt") ?: ""
                }
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("조던 모닝 대시보드", fontSize = 24.sp, modifier = Modifier.padding(bottom = 8.dp))
        Text("업데이트: $updatedAt", fontSize = 11.sp, modifier = Modifier.padding(bottom = 16.dp))

        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("나스닥", fontSize = 16.sp)
                Text(nasdaqChange, fontSize = 24.sp)
            }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("NVDA", fontSize = 16.sp)
                Text("$nvdaPrice ($nvdaChange)", fontSize = 20.sp)
                Text(nvdaSignal, fontSize = 14.sp)
            }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("AAPL", fontSize = 16.sp)
                Text("$aaplPrice ($aaplChange)", fontSize = 20.sp)
            }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("이달 공황 횟수: $panicCount", fontSize = 16.sp)
                Text("재진입 신호: $reentrySignal", fontSize = 16.sp)
            }
        }
    }
}