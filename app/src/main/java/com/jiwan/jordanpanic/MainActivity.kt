package com.jiwan.jordanpanic

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.sqrt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.jiwan.jordanpanic.ui.theme.JordanPanicAlarmTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

        // 알림을 눌러서 들어온 경우, 알림 내용을 받아둔다
        val nTitle = intent?.getStringExtra("notiTitle") ?: ""
        val nBody = intent?.getStringExtra("notiBody") ?: ""

        setContent {
            JordanPanicAlarmTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF0F4F8)) {
                    JordanDashboard(nTitle, nBody)
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

// ================= 차트용 데이터 =================
// closes = 표시할 구간의 종가, ma60 = 같은 길이의 60일선 (값 없으면 NaN)
data class Series(val closes: List<Double>, val ma60: List<Double>)

fun closesLast(s: Series): Double = s.closes.lastOrNull() ?: 0.0

// 기간 선택지: 화면에 보여줄 거래일 수와 받아올 범위
enum class ChartRange(val label: String, val days: Int, val yahooRange: String) {
    M1("1개월", 21, "6mo"),
    M3("3개월", 63, "9mo"),
    M6("6개월", 126, "1y"),
    Y1("1년", 252, "2y")
}

fun fetchSeries(symbol: String, showDays: Int = 63, yahooRange: String = "6mo"): Series? {
    return try {
        val enc = symbol.replace("^", "%5E")
        // 60일선을 그리려면 표시 구간보다 60일 더 받아야 한다
        val conn = URL("https://query1.finance.yahoo.com/v8/finance/chart/$enc?interval=1d&range=$yahooRange")
            .openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        val txt = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val arr = JSONObject(txt).getJSONObject("chart").getJSONArray("result")
            .getJSONObject(0).getJSONObject("indicators").getJSONArray("quote")
            .getJSONObject(0).getJSONArray("close")

        val all = ArrayList<Double>()
        for (i in 0 until arr.length()) if (!arr.isNull(i)) all.add(arr.getDouble(i))
        if (all.size < 2) return null

        // 60일 이동평균 (앞쪽 59개는 계산 불가 -> NaN)
        val ma = ArrayList<Double>()
        for (i in all.indices) {
            if (i < 59) {
                ma.add(Double.NaN)
            } else {
                var s = 0.0
                for (j in (i - 59)..i) s += all[j]
                ma.add(s / 60.0)
            }
        }

        val from = if (all.size > showDays) all.size - showDays else 0
        Series(all.subList(from, all.size).toList(), ma.subList(from, ma.size).toList())
    } catch (e: Exception) {
        null
    }
}

// 봉우리(전고점) 찾기: 앞뒤로 볼 수 있는 범위 안에서 가장 높은 날
// 끝쪽도 포함하되, 비교할 날이 양쪽에 최소 2일은 있어야 인정한다
fun findPeaks(closes: List<Double>, window: Int = 5): List<Int> {
    val peaks = mutableListOf<Int>()
    if (closes.size < 5) return peaks
    for (i in closes.indices) {
        val from = maxOf(0, i - window)
        val to = minOf(closes.size - 1, i + window)
        if (i - from < 2 || to - i < 2) continue
        var isPeak = true
        for (j in from..to) {
            if (j != i && closes[j] >= closes[i]) {
                isPeak = false
                break
            }
        }
        if (isPeak) peaks.add(i)
    }
    return peaks
}

// ================= 차트 그리기 =================
@Composable
fun PriceChart(
    series: Series,
    myAvg: Double = 0.0,
    pickPrice: Double = 0.0,
    savedAlerts: List<PriceAlert> = listOf(),
    onPick: (Double) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val closes = series.closes
    val ma = series.ma60
    if (closes.size < 2) return

    // 평단가와 설정된 알림 가격도 범위에 넣어야 차트 밖으로 안 벗어남
    val valid = closes + ma.filter { !it.isNaN() } +
        (if (myAvg > 0.0) listOf(myAvg) else listOf()) +
        savedAlerts.map { it.price }
    var lo = valid.minOrNull() ?: return
    var hi = valid.maxOrNull() ?: return
    if (hi - lo < 0.0001) { hi += 1.0; lo -= 1.0 }
    val pad = (hi - lo) * 0.08
    lo -= pad
    hi += pad

    val density = LocalDensity.current
    val labelPx = with(density) { 10.sp.toPx() }
    val labelPaint = remember(labelPx) {
        android.graphics.Paint().apply {
            color = android.graphics.Color.GRAY
            textSize = labelPx
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.RIGHT
        }
    }
    // 봉우리 가격표용 (가운데 정렬, 진한 색)
    val peakPaint = remember(labelPx) {
        android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(90, 90, 90)
            textSize = labelPx * 0.95f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }
    }

    // 기간 최고가 선 라벨용 (왼쪽 정렬)
    val highPaint = remember(labelPx) {
        android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(230, 81, 0)
            textSize = labelPx * 0.95f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.LEFT
            isFakeBoldText = true
        }
    }

    // 평단가 선 라벨용 (오른쪽 정렬 — 최고가 라벨과 안 겹치게)
    val avgPaint = remember(labelPx) {
        android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(142, 36, 170)
            textSize = labelPx * 0.95f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.RIGHT
            isFakeBoldText = true
        }
    }

    // 내가 고른 가격 선 라벨용
    val pickPaint = remember(labelPx) {
        android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(0, 121, 107)
            textSize = labelPx * 1.05f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.LEFT
            isFakeBoldText = true
        }
    }

    // 설정해둔 알림 선 라벨용 (오른쪽 정렬)
    val savedPaint = remember(labelPx) {
        android.graphics.Paint().apply {
            textSize = labelPx * 0.9f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.RIGHT
            isFakeBoldText = true
        }
    }

    // 기간이 길수록 작은 봉우리는 무시 (1년 차트에 숫자가 빽빽해지지 않게)
    val peakWindow = when {
        closes.size <= 30 -> 3     // 1개월
        closes.size <= 80 -> 5     // 3개월
        closes.size <= 160 -> 8    // 6개월
        else -> 12                 // 1년
    }
    val peaks = remember(closes, peakWindow) { findPeaks(closes, peakWindow) }

    // 화면 좌표 <-> 가격 변환에 쓰는 여백값 (그릴 때와 똑같이 맞춰야 함)
    val gPadL = labelPx * 2.9f
    val gPadR = 14f
    val gPadT = labelPx * 1.6f
    val gPadB = 10f

    val touchModifier = modifier
        .pointerInput(closes, lo, hi) {
            detectTapGestures { off ->
                val h = size.height - gPadT - gPadB
                val w = size.width - gPadL - gPadR
                if (h <= 0f || w <= 0f) return@detectTapGestures

                // 봉우리 가까이 눌렀으면 그 가격으로 딱 맞춰줌
                var bestI = -1
                var bestD = Float.MAX_VALUE
                for (i in peaks) {
                    val px = gPadL + w * i / (closes.size - 1).toFloat()
                    val py = gPadT + h * (1.0 - (closes[i] - lo) / (hi - lo)).toFloat()
                    val dx = px - off.x
                    val dy = py - off.y
                    val d = sqrt(dx * dx + dy * dy)
                    if (d < bestD) {
                        bestD = d
                        bestI = i
                    }
                }
                if (bestI >= 0 && bestD <= 70f) {
                    onPick(closes[bestI])
                } else {
                    val frac = ((off.y - gPadT) / h).coerceIn(0f, 1f)
                    onPick(hi - (hi - lo) * frac)
                }
            }
        }
        .pointerInput(closes, lo, hi) {
            detectVerticalDragGestures { change, _ ->
                change.consume()
                val h = size.height - gPadT - gPadB
                if (h <= 0f) return@detectVerticalDragGestures
                val frac = ((change.position.y - gPadT) / h).coerceIn(0f, 1f)
                onPick(hi - (hi - lo) * frac)
            }
        }

    Canvas(touchModifier) {
        val padL = gPadL
        val padR = gPadR
        val padT = gPadT
        val padB = gPadB
        val w = size.width - padL - padR
        val h = size.height - padT - padB
        if (w <= 0f || h <= 0f) return@Canvas

        fun xAt(i: Int) = padL + w * i / (closes.size - 1).toFloat()
        fun yAt(v: Double) = padT + h * (1.0 - (v - lo) / (hi - lo)).toFloat()

        // 가로 눈금선 5개 + 가격 라벨
        val grid = Color(0xFFE0E0E0)
        for (k in 0..4) {
            val v = lo + (hi - lo) * k / 4.0
            val y = yAt(v)
            drawLine(grid, Offset(padL, y), Offset(size.width - padR, y), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(
                String.format(Locale.US, "%,.0f", v),
                padL - 6f, y + labelPx * 0.35f, labelPaint
            )
        }

        // 60일선 (회색 점선)
        // 하루치씩 그리면 선이 짧아서 점선이 안 보이므로, 전체를 하나의 경로로 그린다
        val maPath = Path()
        var started = false
        for (i in ma.indices) {
            if (ma[i].isNaN()) continue
            if (!started) {
                maPath.moveTo(xAt(i), yAt(ma[i]))
                started = true
            } else {
                maPath.lineTo(xAt(i), yAt(ma[i]))
            }
        }
        if (started) {
            drawPath(
                maPath, Color(0xFF9E9E9E),
                style = Stroke(
                    width = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
                )
            )
        }

        // 주가 선 (기간 내 올랐으면 빨강, 내렸으면 파랑)
        val up = closes.last() >= closes.first()
        val lineColor = if (up) Color(0xFFD32F2F) else Color(0xFF1565C0)
        val path = Path()
        path.moveTo(xAt(0), yAt(closes[0]))
        for (i in 1 until closes.size) path.lineTo(xAt(i), yAt(closes[i]))
        drawPath(path, lineColor, style = Stroke(width = 4f))

        // 기간 최고가 가로선 (천장이 어디인지 한눈에)
        val periodHigh = closes.max()
        val hy = yAt(periodHigh)
        drawLine(
            Color(0xFFFFAB40),
            Offset(padL, hy), Offset(size.width - padR, hy),
            strokeWidth = 2.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 7f), 0f)
        )
        drawContext.canvas.nativeCanvas.drawText(
            "최고 " + String.format(Locale.US, "%,.0f", periodHigh),
            padL + 4f, (hy - labelPx * 0.4f).coerceAtLeast(labelPx * 0.9f),
            highPaint
        )

        // 내 평단가 가로선 (보라)
        if (myAvg > 0.0) {
            val ay = yAt(myAvg)
            drawLine(
                Color(0xFF8E24AA),
                Offset(padL, ay), Offset(size.width - padR, ay),
                strokeWidth = 2.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f), 0f)
            )
            drawContext.canvas.nativeCanvas.drawText(
                "내 평단 " + String.format(Locale.US, "%,.2f", myAvg),
                size.width - padR - 4f,
                (ay + labelPx * 1.15f).coerceAtMost(size.height - 4f),
                avgPaint
            )
        }

        // 봉우리(전고점) 표시 — 제일 높은 건 더 크게
        val topPeak = peaks.maxByOrNull { closes[it] }
        var lastLabelX = -9999f
        for (i in peaks) {
            val px = xAt(i)
            val py = yAt(closes[i])
            val isTop = (i == topPeak)
            val c = if (isTop) Color(0xFFE65100) else Color(0xFF757575)
            drawCircle(c, radius = if (isTop) 9f else 6f, center = Offset(px, py))
            drawCircle(Color.White, radius = if (isTop) 4.5f else 3f, center = Offset(px, py))

            // 숫자가 서로 겹칠 만큼 가까우면 동그라미만 찍고 숫자는 생략
            if (!isTop && px - lastLabelX < labelPx * 2.6f) continue
            lastLabelX = px

            peakPaint.color =
                if (isTop) android.graphics.Color.rgb(230, 81, 0)
                else android.graphics.Color.rgb(110, 110, 110)
            drawContext.canvas.nativeCanvas.drawText(
                String.format(Locale.US, "%,.0f", closes[i]),
                px, (py - labelPx * 0.8f).coerceAtLeast(labelPx),
                peakPaint
            )
        }

        // 현재가 위치에 동그라미
        drawCircle(lineColor, radius = 8f, center = Offset(xAt(closes.size - 1), yAt(closes.last())))

        // 설정해둔 알림들 (이상=빨강 / 이하=파랑 / 이미 도달=회색)
        for (a in savedAlerts) {
            if (a.price < lo || a.price > hi) continue
            val sy = yAt(a.price)
            val sc = when {
                a.hit -> Color(0xFFBDBDBD)
                a.dir == "above" -> UP_COLOR
                else -> DOWN_COLOR
            }
            drawLine(
                sc, Offset(padL, sy), Offset(size.width - padR, sy),
                strokeWidth = if (a.hit) 2f else 3f,
                pathEffect = if (a.hit) PathEffect.dashPathEffect(floatArrayOf(5f, 9f), 0f) else null
            )
            savedPaint.color = when {
                a.hit -> android.graphics.Color.rgb(150, 150, 150)
                a.dir == "above" -> android.graphics.Color.rgb(211, 47, 47)
                else -> android.graphics.Color.rgb(21, 101, 192)
            }
            val mark = if (a.dir == "above") "▲" else "▼"
            val txt = mark + String.format(Locale.US, "%,.0f", a.price) +
                (if (a.note.isNotEmpty()) " " + a.note else "")
            drawContext.canvas.nativeCanvas.drawText(
                txt,
                size.width - padR - 4f,
                (sy - labelPx * 0.35f).coerceAtLeast(labelPx * 0.9f),
                savedPaint
            )
        }

        // 내가 고른 알림 가격 (초록 굵은 선 + 왼쪽에 손잡이)
        if (pickPrice > 0.0 && pickPrice in lo..hi) {
            val py = yAt(pickPrice)
            val pc = Color(0xFF00796B)
            drawLine(pc, Offset(padL, py), Offset(size.width - padR, py), strokeWidth = 4f)
            drawCircle(pc, radius = 11f, center = Offset(padL + 2f, py))
            drawCircle(Color.White, radius = 5f, center = Offset(padL + 2f, py))
            drawContext.canvas.nativeCanvas.drawText(
                String.format(Locale.US, "%,.2f", pickPrice),
                padL + 20f,
                (py - labelPx * 0.45f).coerceAtLeast(labelPx * 1.0f),
                pickPaint
            )
        }
    }
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
fun JordanDashboard(notiTitle: String = "", notiBody: String = "") {
    val ctx = LocalContext.current
    val activity = ctx as? Activity

    // 알림 눌러서 들어왔으면 내용을 먼저 보여준다
    var showNoti by remember { mutableStateOf(notiTitle.isNotEmpty() || notiBody.isNotEmpty()) }

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

    var chartSeries by remember { mutableStateOf<Series?>(null) }
    var chartSymbol by remember { mutableStateOf("") }
    var chartLoading by remember { mutableStateOf(false) }
    var chartRange by remember { mutableStateOf(ChartRange.M3) }
    var showBigChart by remember { mutableStateOf(false) }

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

    // ---- 차트 데이터 받기 (종목이나 기간이 바뀌면 다시) ----
    LaunchedEffect(showAlerts, showBigChart, alertSymbol, chartRange) {
        if (!showAlerts && !showBigChart) return@LaunchedEffect
        val sym = alertSymbol.trim()
        if (sym.length < 2) return@LaunchedEffect
        if (sym == chartSymbol && chartSeries != null &&
            chartSeries!!.closes.size == chartRange.days
        ) return@LaunchedEffect
        // 글자 칠 때마다 조회하지 않도록 잠깐 기다림 (NVD -> NVDA 치는 중이면 취소됨)
        delay(400)
        chartLoading = true
        val s = withContext(Dispatchers.IO) {
            fetchSeries(sym, chartRange.days, chartRange.yahooRange)
        }
        if (s != null) {
            chartSeries = s
            chartSymbol = sym
        }
        chartLoading = false
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

    // ================= 팝업: 알림 내용 (알림 눌러서 들어왔을 때) =================
    if (showNoti) {
        Dialog(onDismissRequest = { showNoti = false }) {
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        if (notiTitle.isNotEmpty()) notiTitle else "조던 모닝 알림",
                        fontSize = 19.sp, fontWeight = FontWeight.Bold,
                        color = if (notiTitle.contains("공황") || notiTitle.contains("위험")) UP_COLOR
                        else Color(0xFF1A1A1A)
                    )
                    Spacer(Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(notiBody, fontSize = 14.sp, lineHeight = 21.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { showNoti = false },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5C6BC0))
                        ) { Text("대시보드 보기", fontSize = 13.sp) }
                        Button(
                            onClick = {
                                showNoti = false
                                activity?.finish()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF757575))
                        ) { Text("닫기", fontSize = 13.sp) }
                    }
                }
            }
        }
    }

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

    // ================= 전체화면 차트 =================
    if (showBigChart) {
        Dialog(
            onDismissRequest = { showBigChart = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF7F9FB)) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(chartSymbol, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { showBigChart = false }) {
                            Text("닫기", fontSize = 15.sp)
                        }
                    }

                    // 기간 선택
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (r in ChartRange.values()) {
                            Button(
                                onClick = { chartRange = r },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (chartRange == r) Color(0xFF37474F)
                                    else Color(0xFFCFD8DC)
                                )
                            ) {
                                Text(
                                    r.label, fontSize = 12.sp,
                                    color = if (chartRange == r) Color.White else Color(0xFF455A64)
                                )
                            }
                        }
                    }

                    val bcs = chartSeries
                    if (chartLoading || bcs == null) {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text("차트 불러오는 중...", fontSize = 14.sp, color = Color.Gray)
                        }
                    } else {
                        val myAvgBig = holdings.firstOrNull { it.symbol == chartSymbol }?.avg ?: 0.0
                        PriceChart(
                            bcs,
                            myAvg = myAvgBig,
                            pickPrice = alertPrice.toDoubleOrNull() ?: 0.0,
                            savedAlerts = alerts.filter { it.symbol == chartSymbol },
                            onPick = { p -> alertPrice = String.format(Locale.US, "%.2f", p) },
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        )
                    }

                    // 선택한 가격으로 바로 알림 추가
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
                    OutlinedTextField(
                        value = alertNote, onValueChange = { alertNote = it },
                        label = { Text("메모 (예: 1차 눌림목)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = {
                            val p = alertPrice.toDoubleOrNull()
                            if (chartSymbol.isNotBlank() && p != null && p > 0.0) {
                                val l = alerts + PriceAlert(chartSymbol, p, alertDir, alertNote, false, "")
                                alerts = l
                                saveAlerts(l)
                                alertNote = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (alertDir == "above") UP_COLOR else DOWN_COLOR
                        )
                    ) {
                        val p = alertPrice.toDoubleOrNull()
                        Text(
                            if (p != null && p > 0.0)
                                "${usd(p)} " + (if (alertDir == "above") "이상" else "이하") + " 알림 추가"
                            else "차트에서 가격을 먼저 고르세요",
                            fontSize = 14.sp
                        )
                    }
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

                    // ---- 차트 ----
                    val cs = chartSeries
                    if (chartLoading) {
                        Text("차트 불러오는 중...", fontSize = 12.sp, color = Color.Gray,
                            modifier = Modifier.padding(vertical = 8.dp))
                    } else if (cs != null) {
                        val myAvgForChart = holdings.firstOrNull { it.symbol == chartSymbol }?.avg ?: 0.0
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("$chartSymbol  ${chartRange.label}   (회색점선 = 60일선)",
                                fontSize = 11.sp, color = Color.Gray)
                            TextButton(
                                onClick = { showBigChart = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) { Text("크게 보기", fontSize = 12.sp) }
                        }
                        PriceChart(
                            cs,
                            myAvg = myAvgForChart,
                            pickPrice = alertPrice.toDoubleOrNull() ?: 0.0,
                            savedAlerts = alerts.filter { it.symbol == chartSymbol },
                            onPick = { p -> alertPrice = String.format(Locale.US, "%.2f", p) },
                            modifier = Modifier.fillMaxWidth().height(210.dp)
                        )
                        Text(
                            "봉우리를 톡 누르면 그 가격, 아무 데나 누르거나 위아래로 끌면 원하는 가격",
                            fontSize = 10.sp, color = Color.Gray
                        )
                        if (myAvgForChart > 0.0) {
                            val cur = closesLast(cs)
                            val gap = if (cur > 0.0) (cur - myAvgForChart) / myAvgForChart * 100.0 else 0.0
                            Text(
                                "현재가가 내 평단 대비 ${pct(gap)}",
                                fontSize = 11.sp,
                                color = if (gap >= 0.0) UP_COLOR else DOWN_COLOR
                            )
                        }
                    }

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
