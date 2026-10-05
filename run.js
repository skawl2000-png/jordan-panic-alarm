const fetch = require('node-fetch');
const admin = require('firebase-admin');

const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });

const db = admin.firestore();

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
    const marketCap = result.meta.regularMarketPrice * result.meta.sharesOutstanding;
    return { price: price.toFixed(2), change, ma60, closes, marketCap };
  } catch(e) {
    console.log('오류:', symbol, e.message);
    return null;
  }
}

async function main() {
  const today = new Date();
  const month = today.getMonth() + 1;
  const day = today.getDate();

  // 13F 알림 날짜 체크 (2월, 5월, 8월, 11월 15일)
  const is13FDay = day === 15 && [2, 5, 8, 11].includes(month);

  if (is13FDay) {
    await admin.messaging().send({
      notification: {
        title: '📋 13F 공개됐어요!',
        body: 'WhaleWisdom에서 기관 투자자 동향을 확인하세요!\nwhalewisdom.com/stock/nvda'
      },
      topic: 'jordan_panic'
    });
    console.log('13F 알림 발송 완료!');
  }

  const nasdaq = await getStock('%5EIXIC');
  const nvda = await getStock('NVDA');
  const aapl = await getStock('AAPL');

  // 공황 카운트
  let panicCount = 0;
  if (nasdaq) {
    for (let i = 1; i < nasdaq.closes.length; i++) {
      const chg = ((nasdaq.closes[i] - nasdaq.closes[i-1]) / nasdaq.closes[i-1]) * 100;
      if (chg <= -3) panicCount++;
    }
  }

  // 시총 차이 계산
  let marketCapDiff = '계산 불가';
  let jordanRatio = '확인 불가';
  if (nvda && aapl && nvda.marketCap && aapl.marketCap) {
    const diff = ((nvda.marketCap - aapl.marketCap) / aapl.marketCap * 100).toFixed(1);
    marketCapDiff = diff + '%';
    jordanRatio = Math.abs(parseFloat(diff)) < 10 ? 'NVDA 50% + AAPL 50%' : 'NVDA 100%';
  }

  // 60일선 신호
  const nvdaSignal = nvda
    ? (parseFloat(nvda.price) > parseFloat(nvda.ma60) ? '매수 신호 (60일선 위)' : '매수 중단 (60일선 아래)')
    : '정보없음';

  const isPanic = panicCount >= 4;
  const reentrySignal = panicCount === 0 ? '재진입 가능' : '대기 중';

  // Firestore 저장
  await db.collection('market').doc('latest').set({
    nasdaqChange: nasdaq ? nasdaq.change : '오류',
    nvdaPrice: nvda ? nvda.price : '오류',
    nvdaChange: nvda ? nvda.change : '오류',
    nvdaSignal,
    aaplPrice: aapl ? aapl.price : '오류',
    aaplChange: aapl ? aapl.change : '오류',
    marketCapDiff,
    jordanRatio,
    panicCount,
    reentrySignal,
    isPanic,
    updatedAt: new Date().toISOString()
  });

  console.log('Firestore 저장 완료!');

  const title = isPanic ? '🚨 공황 신호 감지!' : '✅ 조던 모닝';
  const body = [
    '📊 나스닥: ' + (nasdaq ? nasdaq.change + '%' : '오류'),
    '🏆 NVDA: $' + (nvda ? nvda.price + ' (' + nvda.change + '%)' : '오류'),
    '🥈 AAPL: $' + (aapl ? aapl.price + ' (' + aapl.change + '%)' : '오류'),
    '📊 시총 차이: ' + marketCapDiff,
    '⚖️ 조던 비율: ' + jordanRatio,
    '📈 NVDA 60일선: ' + nvdaSignal,
    '⚠️ 공황 횟수: ' + panicCount + '회',
    '🔄 재진입: ' + reentrySignal
  ].join('\n');

  await admin.messaging().send({
    notification: { title, body },
    topic: 'jordan_panic'
  });

  console.log('알림 발송 완료!');
}

main().catch(console.error);
