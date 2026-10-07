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
    const closes = result.indicators.quote[0].close.filter(v => v !== null);
    const price = closes[closes.length - 1];
    const prevPrice = closes[closes.length - 2];
    const change = ((price - prevPrice) / prevPrice * 100).toFixed(2);
    const ma60 = (closes.slice(-60).reduce((a,b) => a+b, 0) / Math.min(closes.length, 60)).toFixed(2);
    return { symbol, price: price.toFixed(2), rawPrice: price, change, ma60, closes };
  } catch(e) {
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
  } catch(e) {
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

  let panicCount = 0;
  if (nasdaq) {
    for (let i = 1; i < nasdaq.closes.length; i++) {
      const chg = ((nasdaq.closes[i] - nasdaq.closes[i-1]) / nasdaq.closes[i-1]) * 100;
      if (chg <= -3) panicCount++;
    }
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

  const isPanic = panicCount >= 4;
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
    reentrySignal,
    isPanic,
    usdKrw,
    updatedAt: new Date().toISOString()
  });

  console.log('Firestore 저장 완료!');

  const title = isPanic ? '🚨 공황 신호 감지!' : '✅ 조던 모닝';
  const body = [
    '📊 나스닥: ' + (nasdaq ? nasdaq.change + '%' : '오류'),
    '🏆 1위 ' + (first ? first.symbol + ': $' + first.price + ' (' + first.change + '%)' : '오류'),
    '🥈 2위 ' + (second ? second.symbol + ': $' + second.price + ' (' + second.change + '%)' : '오류'),
    '📊 시총 차이: ' + marketCapDiff,
    '⚖️ 조던 비율: ' + jordanRatio,
    '📈 60일선: ' + firstSignal,
    '⚠️ 공황 횟수: ' + panicCount + '회',
    '🔄 재진입: ' + reentrySignal,
    '💱 환율: ' + (usdKrw ? usdKrw + '원' : '조회실패')
  ].join('\n');

  await admin.messaging().send({
    notification: { title, body },
    topic: 'jordan_panic'
  });

  console.log('알림 발송 완료!');
}

main().catch(console.error);
