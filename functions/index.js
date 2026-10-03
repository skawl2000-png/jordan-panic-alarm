const functions = require("firebase-functions");
const admin = require("firebase-admin");
const https = require("https");

admin.initializeApp();

// 매일 오전 9시에 나스닥 체크 (한국시간)
exports.checkNasdaqPanic = functions.pubsub
  .schedule("0 0 * * *")
  .timeZone("Asia/Seoul")
  .onRun(async (context) => {
    
    const declineCount = await getNasdaqDeclineCount();
    console.log(`이번 달 나스닥 -3% 이상 횟수: ${declineCount}`);
    
    if (declineCount >= 4) {
      await sendPanicAlert(declineCount);
    }
    
    return null;
  });

// 나스닥 하락 횟수 체크 함수
async function getNasdaqDeclineCount() {
  return new Promise((resolve, reject) => {
    const options = {
      hostname: "query1.finance.yahoo.com",
      path: "/v8/finance/chart/%5EIXIC?interval=1d&range=1mo",
      method: "GET",
      headers: {
        "User-Agent": "Mozilla/5.0"
      }
    };

    const req = https.request(options, (res) => {
      let data = "";
      res.on("data", (chunk) => { data += chunk; });
      res.on("end", () => {
        try {
          const json = JSON.parse(data);
          const closes = json.chart.result[0].indicators.quote[0].close;
          
          let declineCount = 0;
          for (let i = 1; i < closes.length; i++) {
            const changePercent = ((closes[i] - closes[i-1]) / closes[i-1]) * 100;
            if (changePercent <= -3) {
              declineCount++;
            }
          }
          resolve(declineCount);
        } catch (e) {
          console.error("데이터 파싱 오류:", e);
          resolve(0);
        }
      });
    });
    
    req.on("error", (e) => {
      console.error("요청 오류:", e);
      resolve(0);
    });
    req.end();
  });
}

// 공황 알림 발송 함수
async function sendPanicAlert(count) {
  const message = {
    notification: {
      title: "🚨 조던 공황 신호 감지!",
      body: `이번 달 나스닥 -3% 이상 ${count}회 발생! 매도 검토하세요!`
    },
    topic: "jordan_panic"
  };
  
  await admin.messaging().send(message);