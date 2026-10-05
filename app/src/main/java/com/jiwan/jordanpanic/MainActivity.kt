package com.jiwan.jordanpanic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.jiwan.jordanpanic.ui.theme.JordanPanicAlarmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseMessaging.getInstance().subscribeToTopic("jordan_panic")
        val channel = NotificationChannel("jordan_panic_channel", "조던 공황 알림", NotificationManager.IMPORTANCE_HIGH)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        setContent {
            JordanPanicAlarmTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF0F4F8)) {
                    JordanDashboard()
                }
            }
        }
    }
}

@Composable
fun JordanDashboard() {
    var nasdaqChange by remember { mutableStateOf("...") }
    var firstSymbol by remember { mutableStateOf("...") }
    var firstPrice by remember { mutableStateOf("...") }
    var firstChange by remember { mutableStateOf("...") }
    var firstSignal by remember { mutableStateOf("...") }
    var secondSymbol by remember { mutableStateOf("...") }
    var secondPrice by remember { mutableStateOf("...") }
    var secondChange by remember { mutableStateOf("...") }
    var marketCapDiff by remember { mutableStateOf("...") }
    var jordanRatio by remember { mutableStateOf("...") }
    var panicCount by remember { mutableStateOf("...") }
    var reentrySignal by remember { mutableStateOf("...") }
    var updatedAt by remember { mutableStateOf("") }
    var isPanic by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }
    var showStocks by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf(listOf<String>()) }
    var newSymbol by remember { mutableStateOf("") }

    fun loadData() {
        val db = FirebaseFirestore.getInstance()
        db.collection("market").document("latest").get().addOnSuccessListener { doc ->
            if (doc != null && doc.exists()) {
                nasdaqChange = doc.getString("nasdaqChange") ?: "오류"
                firstSymbol = doc.getString("firstSymbol") ?: "오류"
                firstPrice = doc.getString("firstPrice") ?: "오류"
                firstChange = doc.getString("firstChange") ?: "오류"
                firstSignal = doc.getString("firstSignal") ?: "오류"
                secondSymbol = doc.getString("secondSymbol") ?: "오류"
                secondPrice = doc.getString("secondPrice") ?: "오류"
                secondChange = doc.getString("secondChange") ?: "오류"
                marketCapDiff = doc.getString("marketCapDiff") ?: "오류"
                jordanRatio = doc.getString("jordanRatio") ?: "오류"
                panicCount = doc.getLong("panicCount").toString()
                reentrySignal = doc.getString("reentrySignal") ?: "오류"
                isPanic = doc.getBoolean("isPanic") ?: false
                updatedAt = doc.getString("updatedAt")?.substring(0, 10) ?: ""
            }
        }
        db.collection("settings").document("candidates").get().addOnSuccessListener { doc ->
            if (doc != null && doc.exists()) {
                @Suppress("UNCHECKED_CAST")
                candidates = (doc.get("list") as? List<String>) ?: listOf()
            }
        }
    }

    LaunchedEffect(Unit) { loadData() }

    if (showRules) {
        Dialog(onDismissRequest = { showRules = false }) {
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                    Text("우리의 투자룰", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                    Text("1. 시총 1위 종목 매수 (자동 선정)")
                    Spacer(Modifier.height(6.dp))
                    Text("2. 시총 차이 10% 미만 → 1위 50% + 2위 50%\n   시총 차이 10% 이상 → 1위 100%\n   (월말 기준 확인)")
                    Spacer(Modifier.height(6.dp))
                    Text("3. 매수 시점: 60일선 위 + 공황 아닐 때\n   매달 남는 돈으로 적립식 매수")
                    Spacer(Modifier.height(6.dp))
                    Text("4. 공황 감지: 나스닥 -3% 4회 → 즉시 전량 매도!")
                    Spacer(Modifier.height(6.dp))
                    Text("5. 재진입: 공황 후 2개월 안정 + 60일선 위")
                    Spacer(Modifier.height(6.dp))
                    Text("6. 13F 확인: 2월, 5월, 8월, 11월 15일")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { showRules = false }, modifier = Modifier.fillMaxWidth()) { Text("확인") }
                }
            }
        }
    }

    if (showStocks) {
        Dialog(onDismissRequest = { showStocks = false }) {
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("후보 종목 관리", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                    candidates.forEach { sym ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(sym, fontSize = 16.sp)
                            TextButton(onClick = {
                                val newList = candidates.filter { it != sym }
                                FirebaseFirestore.getInstance().collection("settings").document("candidates")
                                    .set(mapOf("list" to newList))
                                candidates = newList
                            }) { Text("삭제", color = Color.Red, fontSize = 13.sp) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newSymbol,
                        onValueChange = { newSymbol = it.uppercase() },
                        label = { Text("종목코드 (예: TSLA)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        if (newSymbol.isNotBlank() && !candidates.contains(newSymbol)) {
                            val newList = candidates + newSymbol
                            FirebaseFirestore.getInstance().collection("settings").document("candidates")
                                .set(mapOf("list" to newList))
                            candidates = newList
                            newSymbol = ""
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("추가") }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showStocks = false }, modifier = Modifier.fillMaxWidth()) { Text("닫기") }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("조던 모닝 대시보드", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("업데이트: $updatedAt", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 10.dp))

        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showRules = true }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5C6BC0))) {
                Text("투자룰", fontSize = 13.sp)
            }
            Button(onClick = { showStocks = true }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF26A69A))) {
                Text("종목관리", fontSize = 13.sp)
            }
        }

        if (isPanic) {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))) {
                Text("🚨 공황 신호! 전량 매도 검토하세요!", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = Color.Red, modifier = Modifier.padding(16.dp))
            }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("나스닥", fontSize = 14.sp, color = Color.Gray)
                Text("$nasdaqChange%", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    color = if (nasdaqChange.startsWith("-")) Color.Red else Color(0xFF2E7D32))
            }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("$firstSymbol (시총 1위)", fontSize = 14.sp, color = Color.Gray)
                Text("$$firstPrice  $firstChange%", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = if (firstChange.startsWith("-")) Color.Red else Color(0xFF2E7D32))
                Text(firstSignal, fontSize = 13.sp,
                    color = if (firstSignal.contains("매수 신호")) Color(0xFF2E7D32) else Color.Red)
            }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("$secondSymbol (시총 2위)", fontSize = 14.sp, color = Color.Gray)
                Text("$$secondPrice  $secondChange%", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = if (secondChange.startsWith("-")) Color.Red else Color(0xFF2E7D32))
            }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8EAF6))) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("시총 차이", fontSize = 14.sp, color = Color.Gray)
                Text(marketCapDiff, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3949AB))
                Text("조던 비율: $jordanRatio", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3949AB))
            }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("이달 공황 횟수: ${panicCount}회", fontSize = 15.sp)
                Text("재진입 신호: $reentrySignal", fontSize = 15.sp)
            }
        }
    }
}