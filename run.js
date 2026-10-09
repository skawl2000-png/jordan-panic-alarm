const fetch = require('node-fetch');
const admin = require('firebase-admin');

const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });

const db = admin.firestore();

// 종목코드: 발행주식수(억주) — 2026년 기준
const DEFAULT_CANDIDATES = {
  NVDA: 243.0,
  AAPL: 148.0,
  MSFT: 74.3,
  GOOGL: 121.0,
  AMZN: 106.0,
  META: 25.2,
  AVGO: 47.0,
  TSLA: 32.2
};

async function getStock(symbol) {
  try {
    const url = 'https://query1.finance.yahoo.com/v8/finance/chart/' + symbol + '?interval=1d&range=3mo';
    const res = await fetch(url, { headers: { 'User-Agent': 'Mozilla/5.0' } });
    const json = await res.json();
    const result = json.chart.result[0];
    const ts = result.timestamp || [];
    const q = result.indicators.quote[0];
    const raw = q.close;

    // 날짜 + 종가를 짝지어서 보관 (최근 30일 공황 계산에 필요)
    const series = [];
    let lastIdx = -1;
    for (let i = 0; i < raw.length; i++) {
      if (raw[i] !== null && raw[i] !== undefined) {
        series.push({ t: ts[i], c: raw[i] });
        lastIdx = i;
      }
    }
    const closes = series.map(s => s.c);
    const price = closes[closes.length - 1];
    const prevPrice = closes[closes.length - 2];
    const change = ((price - prevPrice) / prevPrice * 100).toFixed(2);
    const ma60 = (closes.slice(-60).reduce((a, b) => a + b, 0) / Math.min(closes.length, 60)).toFixed(2);

    // 마지막 거래일의 저가/고가 (가격 알림 체크용)
    const lowArr = q.low || [];
    const highArr = q.high || [];
    const lastLow = (lastIdx >= 0 && lowArr[lastIdx] != null) ? lowArr[lastIdx] : price;
    const lastHigh = (lastIdx >= 0 && highArr[lastIdx] != null) ? highArr[lastIdx] : price;

    return { symbol, price: price.toFixed(2), rawPrice: price, change, ma60, closes, series, lastLow, lastHigh };
  } catch (e) {
    console.log('오류:', symbol, e.message);
    return null;
  }
}

async function main() {
  const today = new Date();
  const month = today.getMonth() + 1;
  const day = today.getDate();

  // 13F 알림 (2월, 5월, 8월, 11월 15일)
  if (day === 15 && [2, 5, 8, 11].includes(month)) {
    await admin.messaging().send({
      notification: {
        title: '📋 13F 공개됐어요!',
        body: 'WhaleWisdom에서 기관 투자자 동향을 확인하세요!\nwhalewisdom.com/stock/nvda'
      },
      topic: 'jordan_panic'
    });
    console.log('13F 알림 발송!');
  }

  // Firestore에서 후보 종목 읽기
  let candidates = DEFAULT_CANDIDATES;
  try {
    const doc = await db.collection('settings').doc('candidates').get();
    if (doc.exists && doc.data().stocks) {
      candidates = doc.data().stocks;
    } else {
      await db.collection('settings').doc('candidates').set({ stocks: DEFAULT_CANDIDATES });
    }
  } catch (e) {
    console.log('설정 읽기 오류, 기본값 사용');
  }

  console.log('후보 종목:', Object.keys(candidates).join(', '));

  // 모든 후보 종목 데이터 수집 + 시총 계산
  const stocks = [];
  for (const sym of Object.keys(candidates)) {
    const data = await getStock(sym);
    if (data) {
      data.shares = candidates[sym];
      data.marketCap = data.rawPrice * candidates[sym];
      stocks.push(data);
    }
  }

  stocks.sort((a, b) => b.marketCap - a.marketCap);

  const first = stocks[0];
  const second = stocks[1];

  const nasdaq = await getStock('%5EIXIC');

  // 원/달러 환율
  let fx = await getStock('KRW=X');
  if (!fx) fx = await getStock('USDKRW=X');
  const usdKrw = fx ? fx.price : '';
  console.log('환율:', usdKrw);

  // ===== 최근 30일 기준 공황 횟수 =====
  const panicDays = [];
  if (nasdaq && nasdaq.series.length > 1) {
    const cutoff = Math.floor(Date.now() / 1000) - 30 * 24 * 60 * 60;
    for (let i = 1; i < nasdaq.series.length; i++) {
      const cur = nasdaq.series[i];
      const prev = nasdaq.series[i - 1];
      if (!cur.t || cur.t < cutoff) continue;
      const chg = (cur.c - prev.c) / prev.c * 100;
      if (chg <= -3) {
        const d = new Date(cur.t * 1000);
        const mm = String(d.getUTCMonth() + 1).padStart(2, '0');
        const dd = String(d.getUTCDate()).padStart(2, '0');
        panicDays.push(mm + '/' + dd + ' ' + chg.toFixed(2) + '%');
      }
    }
  }
  const panicCount = panicDays.length;
  const isPanic = panicCount >= 4;
  console.log('최근 30일 -3% 횟수:', panicCount, panicDays.join(', '));

  // ===== 단계별 경고 =====
  let title, panicStage;
  if (panicCount >= 4) {
    title = '🚨 공황 신호! 전량 매도';
    panicStage = '전량 매도 검토하세요!';
  } else if (panicCount === 3) {
    title = '⚠️ 위험 3/4 — 매도 준비';
    panicStage = '한 번만 더 하락하면 공황입니다';
  } else if (panicCount === 2) {
    title = '⚠️ 경고 2/4';
    panicStage = '주의 깊게 지켜보세요';
  } else if (panicCount === 1) {
    title = '조던 모닝 (주의 1/4)';
    panicStage = '-3% 1회 발생';
  } else {
    title = '✅ 조던 모닝';
    panicStage = '정상';
  }

  // 공황이 아닌데 가격 알림이 울렸으면 제목을 그쪽으로
  if (panicCount < 2 && alertLines.length > 0) {
    title = '🎯 가격 도달! ' + (alertLines.length > 1 ? alertLines.length + '건' : '');
  }

  // ===== 가격 알림 체크 (내가 지정한 눌림목/돌파 가격) =====
  let alerts = [];
  try {
    const doc = await db.collection('settings').doc('alerts').get();
    if (doc.exists && Array.isArray(doc.data().list)) alerts = doc.data().list;
  } catch (e) {
    console.log('알림 설정 읽기 오류');
  }

  const alertLines = [];
  let alertsChanged = false;
  const todayStr = today.toISOString().slice(0, 10);

  for (const a of alerts) {
    if (a.hit) continue;                      // 이미 울린 건 건너뜀
    const target = parseFloat(a.price);
    if (!isFinite(target) || target <= 0) continue;

    // 이미 받아온 종목이면 재사용, 아니면 따로 조회
    let s = stocks.find(x => x.symbol === a.symbol);
    if (!s) s = await getStock(a.symbol);
    if (!s) continue;

    // 종가가 아니라 장중 저가/고가로 판단 (밤에 스쳐도 잡히게)
    const reached = (a.dir === 'above') ? (s.lastHigh >= target) : (s.lastLow <= target);
    if (reached) {
      a.hit = true;
      a.hitDate = todayStr;
      alertsChanged = true;
      alertLines.push(
        (a.dir === 'above' ? '📈 돌파! ' : '📉 눌림목! ') +
        a.symbol + ' $' + target.toFixed(2) + (a.dir === 'above' ? ' 이상' : ' 이하') +
        ' (저가 $' + s.lastLow.toFixed(2) + ' / 고가 $' + s.lastHigh.toFixed(2) + ')' +
        (a.note ? ' — ' + a.note : '')
      );
    }
  }

  if (alertsChanged) {
    await db.collection('settings').doc('alerts').set({ list: alerts });
    console.log('가격 알림 도달:', alertLines.length + '건');
  } else {
    console.log('가격 알림 도달 없음 (설정 ' + alerts.length + '건)');
  }

  let marketCapDiff = '계산 불가';
  let jordanRatio = '확인 불가';
  if (first && second) {
    const diff = ((first.marketCap - second.marketCap) / second.marketCap * 100).toFixed(1);
    marketCapDiff = diff + '%';
    jordanRatio = parseFloat(diff) < 10
      ? first.symbol + ' 50% + ' + second.symbol + ' 50%'
      : first.symbol + ' 100%';
  }

  const firstSignal = first
    ? (parseFloat(first.price) > parseFloat(first.ma60) ? '매수 신호 (60일선 위)' : '매수 중단 (60일선 아래)')
    : '정보없음';

  const reentrySignal = panicCount === 0 ? '재진입 가능' : '대기 중';

  await db.collection('market').doc('latest').set({
    nasdaqChange: nasdaq ? nasdaq.change : '오류',
    firstSymbol: first ? first.symbol : '오류',
    firstPrice: first ? first.price : '오류',
    firstChange: first ? first.change : '오류',
    firstSignal,
    secondSymbol: second ? second.symbol : '오류',
    secondPrice: second ? second.price : '오류',
    secondChange: second ? second.change : '오류',
    marketCapDiff,
    jordanRatio,
    panicCount,
    panicDays,
    panicStage,
    reentrySignal,
    isPanic,
    usdKrw,
    updatedAt: new Date().toISOString()
  });

  console.log('Firestore 저장 완료!');

  const lines = [];

  // 가격 알림은 제일 위에 (제일 급한 정보)
  for (const line of alertLines) lines.push(line);
  if (alertLines.length > 0) lines.push('');

  lines.push(
    '📊 나스닥: ' + (nasdaq ? nasdaq.change + '%' : '오류'),
    '🏆 1위 ' + (first ? first.symbol + ': $' + first.price + ' (' + first.change + '%)' : '오류'),
    '🥈 2위 ' + (second ? second.symbol + ': $' + second.price + ' (' + second.change + '%)' : '오류'),
    '📊 시총 차이: ' + marketCapDiff,
    '⚖️ 조던 비율: ' + jordanRatio,
    '📈 60일선: ' + firstSignal,
    '💱 환율: ' + (usdKrw ? usdKrw + '원' : '조회실패'),
    '⚠️ 공황: ' + panicCount + '/4회 (최근 30일) — ' + panicStage
  );
  if (panicDays.length > 0) {
    lines.push('   ' + panicDays.join(', '));
  }
  lines.push('🔄 재진입: ' + reentrySignal);

  await admin.messaging().send({
    notification: { title, body: lines.join('\n') },
    topic: 'jordan_panic'
  });

  console.log('알림 발송 완료!');
}

main().catch(console.error);
