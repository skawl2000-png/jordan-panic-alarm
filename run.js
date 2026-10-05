const fetch = require('node-fetch');
const admin = require('firebase-admin');

const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });

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
    return { price: price.toFixed(2), change, ma60, closes };
  } catch(e) {
    console.log('오류:', symbol, e.message);
    return null;
  }
}

async function main() {
  const nasdaq = await getStock('%5EIXIC');
  const nvda = await getStock('NVDA');
  const aapl = await getStock('AAPL');

  let panicCount = 0;
  if (nasdaq) {
    for (let i = 1; i < nasdaq.closes.length; i++) {
      const chg = ((nasdaq.closes[i] - nasdaq.closes[i-1]) / nasdaq.closes[i-1]) * 100;
      if (chg <= -3) panicCount++;
    }
  }

  const nvdaSignal = nvda
    ? (parseFloat(nvda.price) > parseFloat(nvda.ma60) ? '매수 신호 (60일선 위)' : '매수 중단 (60일선 아래)')
    : '정보없음';

  const reentrySignal = panicCount === 0 ? '재진입 가능' : '대기 중';

  const title = panicCount >= 4 ? '🚨 공황 신호 감지!' : '✅ 오늘 시장 현황';
  const body = [
    '📊 나스닥: ' + (nasdaq ? nasdaq.change + '%' : '정보없음'),
    '🏆 NVDA: $' + (nvda ? nvda.price + ' (' + nvda.change + '%)' : '정보없음'),
    '🥈 AAPL: $' + (aapl ? aapl.price + ' (' + aapl.change + '%)' : '정보없음'),
    '📈 NVDA 60일선: ' + nvdaSignal,
    '⚠️ 이달 공황 횟수: ' + panicCount + '회',
    '🔄 재진입 신호: ' + reentrySignal
  ].join('\n');

  console.log(title);
  console.log(body);

  await admin.messaging().send({
    notification: { title, body },
    topic: 'jordan_panic'
  });

  console.log('알림 발송 완료!');
}

main().catch(console.error);