package com.jiwan.jordanpanic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.jiwan.jordanpanic.ui.theme.JordanPanicAlarmTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

// ================= 매수 기록 (폰에 저장) =================
// type = "BUY" 또는 "SELL", rate = 거래 당시 원/달러 환율
data class Trade(
    val type: String,
    val symbol: String,
    val shares: Double,
    val price: Double,
    val date: String,
    val rate: Double
)

data class Holding(
    val symbol: String,
    val qty: Double,
    val avg: Double,
    val cost: Double,
    val costWon: Double,
    val cur: Double,
    val ok: Boolean,
    val realized: Double,
    val realizedWon: Double
)

// 한국식 색깔: 오르면 빨강, 내리면 파랑
val UP_COLOR = Color(0xFFD32F2F)
val DOWN_COLOR = Color(0xFF1565C0)

// state = 장 상태 (PRE / REGULAR / POST / CLOSED), note = "마감까지 2시간 10분"
data class Quote(val price: Double, val change: Double, val state: String, val note: String)

data class LiveSnap(
    val nasdaq: Quote?,
    val rate: Double,
    val prices: Map<String, Double>,
    val changes: Map<String, Double>
)

// 가격 알림 (Firestore에 저장 — 클라우드가 읽어야 하므로 폰 저장 아님)
// dir: "below" = 이하로 내려오면, "above" = 이상으로 올라가면
data class PriceAlert(
    val symbol: String,
    val price: Double,
    val dir: String,
    val note: String,
    val hit: Boolean,
    val hitDate: String
)

fun saveAlerts(list: List<PriceAlert>) {
    val arr = list.map {
        mapOf(
            "symbol" to it.symbol,
            "price" to it.price,
            "dir" to it.dir,
            "note" to it.note,
            "hit" to it.hit,
            "hitDate" to it.hitDate
        )
    }
    FirebaseFirestore.getInstance().collection("settings").document("alerts")
        .set(mapOf("list" to arr))
}

// 시총 순위 한 줄 (cap 단위: 억달러)
data class CapRow(
    val symbol: String,
    val shares: Double,
    val price: Double,
    val change: Double,
    val cap: Double
)

fun loadTrades(ctx: Context): List<Trade> {
    val raw = ctx.getSharedPreferences("jordan", Context.MODE_PRIVATE).getString("buys", "[]") ?: "[]"
    val list = mutableListOf<Trade>()
    try {
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                Trade(
                    o.optString("type", "BUY"),   // 예전 기록엔 type이 없음 -> 매수로 간주
                    o.getString("symbol"),
                    o.getDouble("shares"),
                    o.getDouble("price"),
                    o.optString("date", ""),
                    o.optDouble("rate", 0.0)
                )
            )
        }
    } catch (e: Exception) {
    }
    return list
}

fun saveTrades(ctx: Context, trades: List<Trade>) {
    val arr = JSONArray()
    for (t in trades) {
        val o = JSONObject()
        o.put("type", t.type)
        o.put("symbol", t.symbol)
        o.put("shares", t.shares)
        o.put("price", t.price)
        o.put("date", t.date)
        o.put("rate", t.rate)
        arr.put(o)
    }
    ctx.getSharedPreferences("jordan", Context.MODE_PRIVATE).edit()
        .putString("buys", arr.toString()).apply()
}

// ================= 실시간 시세 조회 =================
fun fetchPrice(symbol: String): Quote? {
    return try {
        val enc = symbol.replace("^", "%5E")
        val conn = URL("https://query1.finance.yahoo.com/v8/finance/chart/$enc?interval=1d&range=1mo")
            .openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        val txt = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val result = JSONObject(txt).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val meta = result.optJSONObject("meta")

        // 장 상태: marketState가 있으면 쓰고, 없으면 오늘 거래시간으로 직접 계산
        var state = meta?.optString("marketState", "") ?: ""
        var note = ""
        val ctp = meta?.optJSONObject("currentTradingPeriod")
        if (ctp != null) {
            val now = System.currentTimeMillis() / 1000L
            val reg = ctp.optJSONObject("regular")
            val pre = ctp.optJSONObject("pre")
            val post = ctp.optJSONObject("post")
            val rs = reg?.optLong("start") ?: 0L
            val re = reg?.optLong("end") ?: 0L
            if (state.isEmpty()) {
                state = when {
                    rs > 0L && now >= rs && now < re -> "REGULAR"
                    pre != null && now >= pre.optLong("start") && now < pre.optLong("end") -> "PRE"
                    post != null && now >= post.optLong("start") && now < post.optLong("end") -> "POST"
                    else -> "CLOSED"
                }
            }
            if (rs > 0L) {
                note = when {
                    now in rs until re -> "마감까지 " + hhmm(re - now)
                    now < rs -> "개장까지 " + hhmm(rs - now)
                    else -> ""
                }
            }
        }

        val arr = result.getJSONObject("indicators").getJSONArray("quote")
            .getJSONObject(0).getJSONArray("close")
        val closes = ArrayList<Double>()
        for (i in 0 until arr.length()) if (!arr.isNull(i)) closes.add(arr.getDouble(i))
        if (closes.size < 2) return null
        val price = closes[closes.size - 1]
        val prev = closes[closes.size - 2]
        val change = if (prev > 0.0) (price - prev) / prev * 100.0 else 0.0
        Quote(price, change, state, note)
    } catch (e: Exception) {
        null
    }
}

fun hhmm(sec: Long): String {
    val h = sec / 3600L
    val m = (sec % 3600L) / 60L
    return if (h > 0L) "${h}시간 ${m}분" else "${m}분"
}

// 장 상태 -> 우리말
fun marketText(s: String): String = when (s.uppercase()) {
    "REGULAR" -> "장중"
    "PRE", "PREPRE" -> "프리마켓"
    "POST", "POSTPOST" -> "애프터마켓"
    "CLOSED" -> "장 마감"
    else -> ""
}

fun won(v: Double): String = String.format(Locale.KOREA, "%,.0f원", v)
fun usd(v: Double): String = String.format(Locale.US, "\$%,.2f", v)
fun pct(v: Double): String = String.format(Locale.US, "%+.2f%%", v)
fun num(v: Double): String = String.format(Locale.US, "%.2f", v)

// 억달러 -> "5.81조"
fun capUsd(eok: Double): String = String.format(Locale.US, "%.2f조", eok / 10000.0)

// 억달러 + 환율 -> "7,837조원"
fun capWon(eok: Double, rate: Double): String =
    String.format(Locale.KOREA, "%,.0f조원", eok * rate / 10000.0)

// ================= 화면 =================
@Composable
fun JordanDashboard() {
    val ctx = LocalContext.current

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
    var panicCount by remember { mutableStateOf("0") }
    var panicStage by remember { mutableStateOf("") }
    var panicDays by remember { mutableStateOf(listOf<String>()) }
    var reentrySignal by remember { mutableStateOf("...") }
    var updatedAt by remember { mutableStateOf("") }
    var isPanic by remember { mutableStateOf(false) }

    var showRules by remember { mutableStateOf(false) }
    var showStocks by remember { mutableStateOf(false) }
    var showInvest by remember { mutableStateOf(false) }
    var showAlerts by remember { mutableStateOf(false) }

    var alerts by remember { mutableStateOf(listOf<PriceAlert>()) }
    var alertSymbol by remember { mutableStateOf("") }
    var alertPrice by remember { mutableStateOf("") }
    var alertNote by remember { mutableStateOf("") }
    var alertDir by remember { mutableStateOf("below") }

    var candidates by remember { mutableStateOf(mapOf<String, Double>()) }
    var newSymbol by remember { mutableStateOf("") }
    var newShares by remember { mutableStateOf("") }

    var trades by remember { mutableStateOf(loadTrades(ctx)) }
    var buySymbol by remember { mutableStateOf("") }
    var buyQty by remember { mutableStateOf("") }
    var buyPrice by remember { mutableStateOf("") }
    var buyType by remember { mutableStateOf("BUY") }

    var usdKrw by remember { mutableStateOf(0.0) }
    var livePrice by remember { mutableStateOf(mapOf<String, Double>()) }
    var liveChange by remember { mutableStateOf(mapOf<String, Double>()) }
    var liveTime by remember { mutableStateOf("") }
    var marketState by remember { mutableStateOf("") }
    var marketNote by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    var capPrice by remember { mutableStateOf(mapOf<String, Double>()) }
    var capChange by remember { mutableStateOf(mapOf<String, Double>()) }
    var capLoading by remember { mutableStateOf(false) }
    var capTick by remember { mutableStateOf(0) }

    // ---- Firestore (오전 9시 저장분) ----
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
                panicCount = (doc.getLong("panicCount") ?: 0L).toString()
                panicStage = doc.getString("panicStage") ?: ""
                @Suppress("UNCHECKED_CAST")
                panicDays = (doc.get("panicDays") as? List<String>) ?: listOf()
                reentrySignal = doc.getString("reentrySignal") ?: "오류"
                isPanic = doc.getBoolean("isPanic") ?: false
                updatedAt = doc.getString("updatedAt")?.take(10) ?: ""
                val r = doc.getString("usdKrw")?.toDoubleOrNull()
                if (r != null && r > 0.0 && usdKrw <= 0.0) usdKrw = r
            }
        }
        db.collection("settings").document("candidates").get().addOnSuccessListener { doc ->
            if (doc != null && doc.exists()) {
                val stocks = doc.get("stocks") as? Map<*, *>
                if (stocks != null) {
                    val m = mutableMapOf<String, Double>()
                    for ((k, v) in stocks) {
                        val key = k as? String ?: continue
                        val n = when (v) {
                            is Number -> v.toDouble()
                            is String -> v.toDoubleOrNull() ?: 0.0
                            else -> 0.0
                        }
                        m[key] = n
                    }
                    candidates = m
                }
            }
        }
        db.collection("settings").document("alerts").get().addOnSuccessListener { doc ->
            if (doc != null && doc.exists()) {
                val rawList = doc.get("list") as? List<*>
                if (rawList != null) {
                    val l = mutableListOf<PriceAlert>()
                    for (item in rawList) {
                        val m = item as? Map<*, *> ?: continue
                        val sym = m["symbol"] as? String ?: continue
                        val p = when (val v = m["price"]) {
                            is Number -> v.toDouble()
                            is String -> v.toDoubleOrNull() ?: 0.0
                            else -> 0.0
                        }
                        if (p <= 0.0) continue
                        l.add(
                            PriceAlert(
                                sym, p,
                                (m["dir"] as? String) ?: "below",
                                (m["note"] as? String) ?: "",
                                (m["hit"] as? Boolean) ?: false,
                                (m["hitDate"] as? String) ?: ""
                            )
                        )
                    }
                    alerts = l
                }
            }
        }
    }

    LaunchedEffect(Unit) { loadData() }

    // ---- 실시간 조회 ----
    LaunchedEffect(firstSymbol, refreshTick) {
        if (firstSymbol == "..." || firstSymbol == "오류") return@LaunchedEffect
        loading = true
        val syms = mutableSetOf<String>()
        syms.add(firstSymbol)
        if (secondSymbol != "..." && secondSymbol != "오류") syms.add(secondSymbol)
        for (t in trades) syms.add(t.symbol)

        val snap = withContext(Dispatchers.IO) {
            val nas = fetchPrice("^IXIC")
            val fx = fetchPrice("KRW=X") ?: fetchPrice("USDKRW=X")
            val pm = mutableMapOf<String, Double>()
            val cm = mutableMapOf<String, Double>()
            for (s in syms) {
                val q = fetchPrice(s)
                if (q != null) {
                    pm[s] = q.price
                    cm[s] = q.change
                }
            }
            LiveSnap(nas, fx?.price ?: 0.0, pm, cm)
        }

        if (snap.nasdaq != null) {
            nasdaqChange = num(snap.nasdaq.change)
            marketState = snap.nasdaq.state
            marketNote = snap.nasdaq.note
        }
        if (snap.rate > 0.0) usdKrw = snap.rate
        if (snap.prices.isNotEmpty()) {
            livePrice = snap.prices
            liveChange = snap.changes
        }
        liveTime = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date())
        loading = false
    }

    // ---- 종목관리 열면 후보 전체 시세 조회 (시총 순위용) ----
    LaunchedEffect(showStocks, capTick, candidates) {
        if (!showStocks || candidates.isEmpty()) return@LaunchedEffect
        capLoading = true
        val syms = candidates.keys.toList()
        val res = withContext(Dispatchers.IO) {
            val pm = mutableMapOf<String, Double>()
            val cm = mutableMapOf<String, Double>()
            for (s in syms) {
                val q = fetchPrice(s)
                if (q != null) {
                    pm[s] = q.price
                    cm[s] = q.change
                }
            }
            Pair(pm.toMap(), cm.toMap())
        }
        if (res.first.isNotEmpty()) {
            capPrice = res.first
            capChange = res.second
        }
        capLoading = false
    }

    val capRows = candidates.entries.map { e ->
        val p = capPrice[e.key] ?: 0.0
        CapRow(e.key, e.value, p, capChange[e.key] ?: 0.0, p * e.value)
    }.sortedByDescending { it.cap }

    // ---- 표시값: 실시간 있으면 실시간, 없으면 오전 데이터 ----
    val fPrice = livePrice[firstSymbol]?.let { num(it) } ?: firstPrice
    val fChange = liveChange[firstSymbol]?.let { num(it) } ?: firstChange
    val sPrice = livePrice[secondSymbol]?.let { num(it) } ?: secondPrice
    val sChange = liveChange[secondSymbol]?.let { num(it) } ?: secondChange

    // ---- 내 투자 계산 (이동평균법) ----
    val holdings = trades.groupBy { it.symbol }.map { entry ->
        var qty = 0.0          // 보유 주수
        var cost = 0.0         // 남은 원금 (달러)
        var costWon = 0.0      // 남은 원금 (원, 매수 당시 환율)
        var realized = 0.0     // 실현 손익 (달러)
        var realizedWon = 0.0  // 실현 손익 (원)

        // 날짜순으로 하나씩 처리해야 평균단가가 제대로 나옴
        for (t in entry.value.sortedBy { it.date }) {
            val rate = if (t.rate > 0.0) t.rate else usdKrw
            if (t.type == "SELL") {
                val sellQty = minOf(t.shares, qty)
                if (qty > 0.0 && sellQty > 0.0) {
                    val avgUsd = cost / qty
                    val avgWon = costWon / qty
                    realized += sellQty * (t.price - avgUsd)
                    realizedWon += sellQty * t.price * rate - sellQty * avgWon
                    cost -= sellQty * avgUsd
                    costWon -= sellQty * avgWon
                    qty -= sellQty
                }
            } else {
                qty += t.shares
                cost += t.shares * t.price
                costWon += t.shares * t.price * rate
            }
        }

        val avg = if (qty > 0.0) cost / qty else 0.0
        val raw = livePrice[entry.key] ?: when (entry.key) {
            firstSymbol -> firstPrice.toDoubleOrNull() ?: 0.0
            secondSymbol -> secondPrice.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
        // 시세를 못 받으면 평단으로 대체 → 전체 수익률이 망가지지 않게
        val ok = raw > 0.0
        Holding(entry.key, qty, avg, cost, costWon, if (ok) raw else avg, ok, realized, realizedWon)
    }.filter { it.qty > 0.000001 || it.realized != 0.0 }
    val totalCost = holdings.sumOf { it.cost }
    val totalValue = holdings.sumOf { it.qty * it.cur }
    val profit = totalValue - totalCost
    val profitPct = if (totalCost > 0.0) profit / totalCost * 100.0 else 0.0

    // 원화 기준 (환차익 포함)
    val totalCostWon = holdings.sumOf { it.costWon }
    val totalValueWon = totalValue * usdKrw
    val profitWon = totalValueWon - totalCostWon
    val profitWonPct = if (totalCostWon > 0.0) profitWon / totalCostWon * 100.0 else 0.0

    // 실현 손익 (매도분)
    val totalRealized = holdings.sumOf { it.realized }
    val totalRealizedWon = holdings.sumOf { it.realizedWon }
    val hasRealized = holdings.any { it.realized != 0.0 }

    // ================= 팝업: 투자룰 =================
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

    // ================= 팝업: 종목관리 =================
    if (showStocks) {
        Dialog(onDismissRequest = { showStocks = false }) {
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("시총 순위", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { capTick++ }, enabled = !capLoading) {
                            Text(if (capLoading) "조회중" else "새로고침", fontSize = 13.sp)
                        }
                    }
                    Text("후보 종목을 실시간 시세로 다시 순위매김", fontSize = 11.sp, color = Color.Gray,
                        modifier = Modifier.padding(bottom = 10.dp))

                    if (candidates.isEmpty()) {
                        Text("목록 없음 (오전 9시 실행 후 생성됨)", fontSize = 13.sp, color = Color.Gray)
                    }

                    capRows.forEachIndexed { idx, r ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${idx + 1}위  ${r.symbol}   " +
                                        (if (r.price > 0.0) usd(r.price) + "  " + pct(r.change) else "조회중..."),
                                    fontSize = 15.sp,
                                    fontWeight = if (idx < 2) FontWeight.Bold else FontWeight.Normal,
                                    color = if (r.price <= 0.0) Color.Gray
                                    else if (r.change < 0.0) DOWN_COLOR else UP_COLOR
                                )
                                if (r.cap > 0.0) {
                                    Text(
                                        "시총 \$${capUsd(r.cap)}" +
                                            (if (usdKrw > 0.0) "  (${capWon(r.cap, usdKrw)})" else ""),
                                        fontSize = 12.sp, color = Color.Gray
                                    )
                                }
                                Text("발행 ${r.shares}억주", fontSize = 10.sp, color = Color.LightGray)
                            }
                            TextButton(onClick = {
                                val m = candidates.toMutableMap()
                                m.remove(r.symbol)
                                FirebaseFirestore.getInstance().collection("settings")
                                    .document("candidates").set(mapOf("stocks" to m))
                                candidates = m
                            }) { Text("삭제", color = Color.Red, fontSize = 12.sp) }
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("종목 추가", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = newSymbol, onValueChange = { newSymbol = it.uppercase() },
                        label = { Text("종목코드 (예: TSLA)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = newShares, onValueChange = { newShares = it },
                        label = { Text("발행주식수 (억주, 예: 32.2)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        val sh = newShares.toDoubleOrNull()
                        if (newSymbol.isNotBlank() && sh != null && sh > 0.0) {
                            val m = candidates.toMutableMap()
                            m[newSymbol] = sh
                            FirebaseFirestore.getInstance().collection("settings")
                                .document("candidates").set(mapOf("stocks" to m))
                            candidates = m
                            newSymbol = ""
                            newShares = ""
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("추가") }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showStocks = false }, modifier = Modifier.fillMaxWidth()) { Text("닫기") }
                }
            }
        }
    }

    // ================= 팝업: 내 투자 =================
    if (showInvest) {
        Dialog(onDismissRequest = { showInvest = false }) {
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                    Text("내 거래 기록", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 10.dp))
                    if (trades.isEmpty()) {
                        Text("아직 기록이 없어요", fontSize = 13.sp, color = Color.Gray)
                    }
                    for (t in trades.sortedBy { it.date }) {
                        val isSell = t.type == "SELL"
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    (if (isSell) "매도  " else "매수  ") +
                                        "${t.symbol}  ${num(t.shares)}주 @ ${usd(t.price)}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSell) DOWN_COLOR else UP_COLOR
                                )
                                Text(
                                    t.date + (if (t.rate > 0.0) "   환율 ${won(t.rate)}" else ""),
                                    fontSize = 11.sp, color = Color.Gray
                                )
                            }
                            TextButton(onClick = {
                                val list = trades.toMutableList()
                                list.remove(t)
                                trades = list
                                saveTrades(ctx, list)
                            }) { Text("삭제", color = Color.Red, fontSize = 13.sp) }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text("거래 추가", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))

                    // 매수 / 매도 선택
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { buyType = "BUY" },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (buyType == "BUY") UP_COLOR else Color(0xFFBDBDBD)
                            )
                        ) { Text("매수") }
                        Button(
                            onClick = { buyType = "SELL" },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (buyType == "SELL") DOWN_COLOR else Color(0xFFBDBDBD)
                            )
                        ) { Text("매도") }
                    }

                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = buySymbol, onValueChange = { buySymbol = it.uppercase() },
                        label = { Text("종목 (예: NVDA)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = buyQty, onValueChange = { buyQty = it },
                        label = { Text("주수") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = buyPrice, onValueChange = { buyPrice = it },
                        label = { Text(if (buyType == "SELL") "매도가 (달러)" else "매수가 (달러)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth())

                    // 매도할 때 보유 수량보다 많이 넣으면 알려주기
                    if (buyType == "SELL" && buySymbol.isNotBlank()) {
                        val held = holdings.firstOrNull { it.symbol == buySymbol }?.qty ?: 0.0
                        val want = buyQty.toDoubleOrNull() ?: 0.0
                        Text(
                            if (want > held) "보유 ${num(held)}주보다 많아요 — ${num(held)}주까지만 반영됩니다"
                            else "보유 ${num(held)}주",
                            fontSize = 11.sp,
                            color = if (want > held) Color(0xFFE65100) else Color.Gray
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        val q = buyQty.toDoubleOrNull()
                        val p = buyPrice.toDoubleOrNull()
                        if (buySymbol.isNotBlank() && q != null && q > 0.0 && p != null && p > 0.0) {
                            val today = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
                            val list = trades + Trade(buyType, buySymbol, q, p, today, usdKrw)
                            trades = list
                            saveTrades(ctx, list)
                            buySymbol = ""
                            buyQty = ""
                            buyPrice = ""
                            refreshTick++
                        }
                    }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (buyType == "SELL") DOWN_COLOR else UP_COLOR
                        )) {
                        Text(if (buyType == "SELL") "매도 기록 추가" else "매수 기록 추가")
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showInvest = false }, modifier = Modifier.fillMaxWidth()) { Text("닫기") }
                }
            }
        }
    }

    // ================= 팝업: 가격 알림 =================
    if (showAlerts) {
        Dialog(onDismissRequest = { showAlerts = false }) {
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                    Text("가격 알림", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("지정한 가격에 닿으면 아침 알림에 같이 옵니다", fontSize = 11.sp, color = Color.Gray,
                        modifier = Modifier.padding(bottom = 10.dp))

                    if (alerts.isEmpty()) {
                        Text("설정된 알림이 없어요", fontSize = 13.sp, color = Color.Gray)
                    }

                    for (a in alerts) {
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${a.symbol}  ${usd(a.price)} " + (if (a.dir == "above") "이상" else "이하"),
                                    fontSize = 15.sp,
                                    fontWeight = if (a.hit) FontWeight.Normal else FontWeight.Bold,
                                    color = if (a.hit) Color.Gray
                                    else if (a.dir == "above") UP_COLOR else DOWN_COLOR
                                )
                                if (a.note.isNotEmpty()) {
                                    Text(a.note, fontSize = 12.sp, color = Color.Gray)
                                }
                                if (a.hit) {
                                    Text("도달 ${a.hitDate}", fontSize = 11.sp, color = Color(0xFFE65100))
                                }
                            }
                            TextButton(onClick = {
                                val l = alerts.filter { it !== a }
                                alerts = l
                                saveAlerts(l)
                            }) { Text("삭제", color = Color.Red, fontSize = 12.sp) }
                        }
                        Spacer(Modifier.height(2.dp))
                    }

                    Spacer(Modifier.height(14.dp))
                    Text("알림 추가", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { alertDir = "below" },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (alertDir == "below") DOWN_COLOR else Color(0xFFBDBDBD)
                            )
                        ) { Text("이하 (눌림목)", fontSize = 12.sp) }
                        Button(
                            onClick = { alertDir = "above" },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (alertDir == "above") UP_COLOR else Color(0xFFBDBDBD)
                            )
                        ) { Text("이상 (돌파)", fontSize = 12.sp) }
                    }

                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = alertSymbol, onValueChange = { alertSymbol = it.uppercase() },
                        label = { Text("종목 (예: NVDA)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = alertPrice, onValueChange = { alertPrice = it },
                        label = { Text("가격 (달러)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = alertNote, onValueChange = { alertNote = it },
                        label = { Text("메모 (예: 1차 눌림목)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())

                    // 현재가 대비 안내
                    val curForAlert = livePrice[alertSymbol] ?: 0.0
                    val tgt = alertPrice.toDoubleOrNull() ?: 0.0
                    if (curForAlert > 0.0 && tgt > 0.0) {
                        val gap = (tgt - curForAlert) / curForAlert * 100.0
                        Text(
                            "현재 ${usd(curForAlert)} 에서 ${pct(gap)}",
                            fontSize = 11.sp, color = Color.Gray
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        val p = alertPrice.toDoubleOrNull()
                        if (alertSymbol.isNotBlank() && p != null && p > 0.0) {
                            val l = alerts + PriceAlert(alertSymbol, p, alertDir, alertNote, false, "")
                            alerts = l
                            saveAlerts(l)
                            alertPrice = ""
                            alertNote = ""
                        }
                    }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (alertDir == "above") UP_COLOR else DOWN_COLOR
                        )) { Text("알림 추가") }

                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showAlerts = false }, modifier = Modifier.fillMaxWidth()) { Text("닫기") }
                }
            }
        }
    }

    // ================= 본 화면 =================
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("조던 모닝 대시보드", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            if (liveTime.isNotEmpty()) "실시간 $liveTime  (오전집계 $updatedAt)" else "업데이트: $updatedAt",
            fontSize = 11.sp, color = Color.Gray
        )

        val mText = marketText(marketState)
        if (mText.isNotEmpty()) {
            Text(
                "● 미국장 $mText" + (if (marketNote.isNotEmpty()) "  ($marketNote)" else ""),
                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = when (marketState.uppercase()) {
                    "REGULAR" -> Color(0xFF2E7D32)
                    "PRE", "PREPRE", "POST", "POSTPOST" -> Color(0xFFE65100)
                    else -> Color.Gray
                }
            )
        }
        Spacer(Modifier.height(10.dp))

        Button(
            onClick = { refreshTick++ },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF6C00))
        ) {
            Text(if (loading) "조회중..." else "실시간 조회", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }

        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { showRules = true }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5C6BC0))) {
                Text("투자룰", fontSize = 13.sp)
            }
            Button(onClick = { showStocks = true }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF26A69A))) {
                Text("종목관리", fontSize = 13.sp)
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = {
                if (buySymbol.isBlank() && firstSymbol != "..." && firstSymbol != "오류") buySymbol = firstSymbol
                showInvest = true
            }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E24AA))) {
                Text("내 투자", fontSize = 13.sp)
            }
            Button(onClick = {
                if (alertSymbol.isBlank() && firstSymbol != "..." && firstSymbol != "오류") alertSymbol = firstSymbol
                showAlerts = true
            }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF6C00))) {
                val pending = alerts.count { !it.hit }
                Text(if (pending > 0) "가격알림 ($pending)" else "가격알림", fontSize = 13.sp)
            }
        }

        val pCount = panicCount.toIntOrNull() ?: 0
        if (pCount >= 2) {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (pCount >= 4) Color(0xFFFFCDD2) else Color(0xFFFFF3E0)
                )) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        if (pCount >= 4) "공황 신호! 전량 매도 검토하세요!"
                        else "경고 $pCount/4 — ${4 - pCount}회 남았습니다",
                        fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        color = if (pCount >= 4) UP_COLOR else Color(0xFFE65100)
                    )
                    if (panicStage.isNotEmpty()) {
                        Text(panicStage, fontSize = 13.sp, color = Color.DarkGray)
                    }
                }
            }
        }

        // ---- 내 투자 현황 ----
        if (holdings.isNotEmpty()) {
            // 환율을 못 받았으면 달러 기준으로 색을 정함
            val mainUp = if (usdKrw > 0.0) profitWon >= 0.0 else profit >= 0.0
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (mainUp) Color(0xFFFFEBEE) else Color(0xFFE3F2FD)
                )) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("내 투자 현황", fontSize = 14.sp, color = Color.Gray)
                    Spacer(Modifier.height(4.dp))
                    for (h in holdings) {
                        if (h.qty > 0.000001) {
                            Text("${h.symbol}  ${num(h.qty)}주 · 평단 ${usd(h.avg)}", fontSize = 14.sp)
                            if (h.ok) {
                                Text("   현재 ${usd(h.cur)}", fontSize = 13.sp, color = Color.Gray)
                            } else {
                                Text("   시세 조회 안됨 — 종목코드를 확인하세요", fontSize = 12.sp, color = Color(0xFFE65100))
                            }
                        } else {
                            Text("${h.symbol}  전량 매도 완료", fontSize = 14.sp, color = Color.Gray)
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text("평가금액 ${usd(totalValue)}", fontSize = 15.sp)
                    if (usdKrw > 0.0) {
                        Text(won(totalValueWon), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(10.dp))
                    Text("달러 기준 (주가만)", fontSize = 11.sp, color = Color.Gray)
                    Text(
                        "${pct(profitPct)}   ${usd(profit)}",
                        fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = if (profit >= 0.0) UP_COLOR else DOWN_COLOR
                    )

                    if (usdKrw > 0.0 && totalCostWon > 0.0) {
                        Spacer(Modifier.height(8.dp))
                        Text("원화 기준 (환차익 포함)", fontSize = 11.sp, color = Color.Gray)
                        Text(
                            "${pct(profitWonPct)}   ${won(profitWon)}",
                            fontSize = 24.sp, fontWeight = FontWeight.Bold,
                            color = if (mainUp) UP_COLOR else DOWN_COLOR
                        )
                    }

                    if (hasRealized) {
                        Spacer(Modifier.height(10.dp))
                        Text("실현 손익 (판 것)", fontSize = 11.sp, color = Color.Gray)
                        Text(
                            usd(totalRealized) +
                                (if (usdKrw > 0.0) "   ${won(totalRealizedWon)}" else ""),
                            fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            color = if (totalRealized >= 0.0) UP_COLOR else DOWN_COLOR
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text("투자원금 ${usd(totalCost)}", fontSize = 12.sp, color = Color.Gray)
                    if (totalCostWon > 0.0) {
                        Text("         ${won(totalCostWon)}  (매수 당시 환율 기준)",
                            fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }
        }

        // ---- 환율 ----
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("원/달러 환율", fontSize = 14.sp, color = Color.Gray)
                Text(if (usdKrw > 0.0) "1달러 = ${won(usdKrw)}" else "조회중...",
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
            }
        }

        // ---- 나스닥 ----
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("나스닥", fontSize = 14.sp, color = Color.Gray)
                Text("$nasdaqChange%", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    color = if (nasdaqChange.startsWith("-")) DOWN_COLOR else UP_COLOR)
            }
        }

        // ---- 시총 1위 ----
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("$firstSymbol (시총 1위)", fontSize = 14.sp, color = Color.Gray)
                Text("$$fPrice  $fChange%", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = if (fChange.startsWith("-")) DOWN_COLOR else UP_COLOR)
                if (usdKrw > 0.0) {
                    val p = fPrice.toDoubleOrNull()
                    if (p != null) Text(won(p * usdKrw), fontSize = 12.sp, color = Color.Gray)
                }
                Text(firstSignal, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = if (firstSignal.contains("매수 신호")) Color(0xFF2E7D32) else Color(0xFFE65100))
            }
        }

        // ---- 시총 2위 ----
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("$secondSymbol (시총 2위)", fontSize = 14.sp, color = Color.Gray)
                Text("$$sPrice  $sChange%", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = if (sChange.startsWith("-")) DOWN_COLOR else UP_COLOR)
                if (usdKrw > 0.0) {
                    val p = sPrice.toDoubleOrNull()
                    if (p != null) Text(won(p * usdKrw), fontSize = 12.sp, color = Color.Gray)
                }
            }
        }

        // ---- 시총 차이 ----
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8EAF6))) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("시총 차이 (오전 집계)", fontSize = 14.sp, color = Color.Gray)
                Text(marketCapDiff, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3949AB))
                Text("조던 비율: $jordanRatio", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3949AB))
            }
        }

        // ---- 공황 ----
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("공황 횟수 (최근 30일)", fontSize = 13.sp, color = Color.Gray)
                Text(
                    "$panicCount / 4",
                    fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    color = when {
                        pCount >= 4 -> UP_COLOR
                        pCount >= 2 -> Color(0xFFE65100)
                        else -> Color(0xFF2E7D32)
                    }
                )
                if (panicStage.isNotEmpty()) {
                    Text(panicStage, fontSize = 13.sp, color = Color.DarkGray)
                }
                if (panicDays.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("발생일", fontSize = 11.sp, color = Color.Gray)
                    for (d in panicDays) {
                        Text("  $d", fontSize = 13.sp, color = UP_COLOR)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("재진입 신호: $reentrySignal", fontSize = 14.sp)
            }
        }
    }
}
