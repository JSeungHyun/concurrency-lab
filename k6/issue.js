// 선착순 쿠폰 발급 부하 시나리오.
// 매 실행마다 새 쿠폰을 만들고, 서로 다른 userId 로 발급을 요청한 뒤
// 정합성(초과 발급, 카운터와 실제 행 수 일치)과 성능 지표를 results/ 에 남긴다.
//
// SCENARIO 로 부하 모양을 고른다 (기본 burst). 각 시나리오의 조절 변수는 scenarioConfig() 참고.
//   smoke  : 기능 확인용 아주 약한 부하
//   burst  : 오픈 순간 동시 폭주 (닫힌 모델, 정해진 요청 수)
//   load   : 일정 도착률 유지 (열린 모델) + SLO 판정
//   stress : 도착률을 계단식으로 올려 한계점 찾기
//   spike  : 평소 → 급상승 → 복귀, 회복 여부 + SLO 판정
//   soak   : 중간 도착률을 오래 유지 + SLO 판정
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Gauge } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const STRATEGY = __ENV.STRATEGY || 'naive';
const SCENARIO = (__ENV.SCENARIO || 'burst').toLowerCase();
const RUN_ID = __ENV.RUN_ID || new Date().toISOString().replace(/[:.]/g, '-');
const WARMUP = __ENV.WARMUP === '1';

const SLO = { p95Ms: 500, p99Ms: 1000, errorRate: 0.01 };
const SLO_SCENARIOS = ['load', 'spike', 'soak'];

const SCENARIO_CONFIG = scenarioConfig(SCENARIO);
// 시간 기반 시나리오는 수량이 작으면 금방 매진돼 나머지 시간 동안 거절만 측정하게 된다.
// 그래서 기본 수량을 예상 총 요청 수로 잡아 끝까지 쓰기 경로를 태운다.
const QUANTITY = num('QUANTITY', defaultQuantity(SCENARIO, SCENARIO_CONFIG));

const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

const outcome = {
  ISSUED: new Counter('issue_issued'),
  SOLD_OUT: new Counter('issue_sold_out'),
  DUPLICATE: new Counter('issue_duplicate'),
  UNAVAILABLE: new Counter('issue_unavailable'),
  ERROR: new Counter('issue_error'),
};
const issuedRows = new Gauge('coupon_issued_rows');
const issuedCounter = new Gauge('coupon_issued_counter');
const totalQuantity = new Gauge('coupon_total');
const optimisticRetries = new Gauge('optimistic_retries');

http.setResponseCallback(http.expectedStatuses(200, 409));

export const options = {
  tags: { strategy: STRATEGY, run_id: RUN_ID, scenario: SCENARIO },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: thresholds(),
  scenarios: { [SCENARIO]: SCENARIO_CONFIG },
};

export function setup() {
  waitForApp();
  const created = http.post(`${BASE_URL}/api/coupons`,
    JSON.stringify({ name: `${STRATEGY}-${RUN_ID}`, totalQuantity: QUANTITY }), JSON_HEADERS);
  if (created.status !== 201) {
    exec.test.abort(`쿠폰 생성 실패: ${created.status} ${created.body}`);
  }
  return {
    couponId: created.json('id'),
    retriesBefore: http.get(`${BASE_URL}/api/stats`).json('optimisticRetries'),
  };
}

export default function (data) {
  const userId = exec.scenario.iterationInTest + 1;
  const res = http.post(`${BASE_URL}/api/coupons/${data.couponId}/issue?strategy=${STRATEGY}`,
    JSON.stringify({ userId }), { ...JSON_HEADERS, tags: { name: 'issue' } });

  check(res, { 'status is 200 or 409': (r) => r.status === 200 || r.status === 409 });
  outcome[classify(res)].add(1);
}

export function teardown(data) {
  const coupon = http.get(`${BASE_URL}/api/coupons/${data.couponId}`).json();
  issuedRows.add(coupon.issuedRows);
  issuedCounter.add(coupon.issuedQuantity);
  totalQuantity.add(coupon.totalQuantity);
  optimisticRetries.add(http.get(`${BASE_URL}/api/stats`).json('optimisticRetries') - data.retriesBefore);
}

export function handleSummary(data) {
  const report = buildReport(data);
  const text = renderMarkdown(report);
  if (WARMUP) {
    return { stdout: `[warmup] ${SCENARIO}/${STRATEGY} 완료 (기록하지 않음)\n` };
  }
  const base = `/results/${RUN_ID}_${SCENARIO}_${STRATEGY}`;
  return {
    stdout: text,
    [`${base}.json`]: JSON.stringify(report, null, 2),
    [`${base}.md`]: text,
  };
}

// ---------- 시나리오 ----------

function scenarioConfig(name) {
  switch (name) {
    case 'smoke':
      return { executor: 'shared-iterations', vus: num('VUS', 2), iterations: num('REQUESTS', 50), maxDuration: '1m' };
    case 'burst':
      return { executor: 'shared-iterations', vus: num('VUS', 200), iterations: num('REQUESTS', 3000), maxDuration: '5m' };
    case 'load':
      return constantRate(num('RATE', 300), str('DURATION', '3m'), num('VUS', 100), num('MAX_VUS', 1000));
    case 'soak':
      return constantRate(num('RATE', 100), str('DURATION', '30m'), num('VUS', 50), num('MAX_VUS', 500));
    case 'stress': {
      const start = num('START_RATE', 100);
      const peak = num('PEAK_RATE', 3000);
      const steps = num('STEPS', 6);
      const hold = str('STEP_DURATION', '30s');
      const stages = [];
      for (let i = 0; i < steps; i++) {
        const rate = Math.round(steps === 1 ? peak : start + ((peak - start) * i) / (steps - 1));
        stages.push({ target: rate, duration: '5s' }, { target: rate, duration: hold });
      }
      return rampingRate(start, stages, num('VUS', 200), num('MAX_VUS', 3000));
    }
    case 'spike': {
      const base = num('BASE_RATE', 50);
      const peak = num('PEAK_RATE', 1000);
      return rampingRate(base, [
        { target: base, duration: '30s' },
        { target: peak, duration: '5s' },
        { target: peak, duration: str('SPIKE_DURATION', '30s') },
        { target: base, duration: '5s' },
        { target: base, duration: '60s' }, // 회복 구간
      ], num('VUS', 100), num('MAX_VUS', 2000));
    }
    default:
      throw new Error(`알 수 없는 SCENARIO '${name}'. smoke, burst, load, stress, spike, soak 중 하나`);
  }
}

function constantRate(rate, duration, preAllocatedVUs, maxVUs) {
  return { executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration, preAllocatedVUs, maxVUs };
}

function rampingRate(startRate, stages, preAllocatedVUs, maxVUs) {
  return { executor: 'ramping-arrival-rate', startRate, timeUnit: '1s', stages, preAllocatedVUs, maxVUs };
}

function defaultQuantity(name, config) {
  if (name === 'smoke') return 10;
  if (name === 'burst') return 100;
  return estimateRequests(config);
}

function estimateRequests(config) {
  if (config.executor === 'shared-iterations') return config.iterations;
  if (config.executor === 'constant-arrival-rate') return Math.round(config.rate * seconds(config.duration));
  let rate = config.startRate;
  let total = 0;
  for (const stage of config.stages) {
    total += ((rate + stage.target) / 2) * seconds(stage.duration);
    rate = stage.target;
  }
  return Math.round(total);
}

function thresholds() {
  if (SLO_SCENARIOS.includes(SCENARIO)) {
    return {
      'http_req_duration{name:issue}': [`p(95)<${SLO.p95Ms}`, `p(99)<${SLO.p99Ms}`],
      'http_req_failed{name:issue}': [`rate<${SLO.errorRate}`],
      'http_reqs{name:issue}': ['count>=0'],
    };
  }
  // 항상 통과하는 임계값. 발급 요청만 따로 집계한 하위 지표를 summary 에 노출하려는 용도다.
  return {
    'http_req_duration{name:issue}': ['max>=0'],
    'http_reqs{name:issue}': ['count>=0'],
  };
}

// ---------- 유틸 ----------

function str(name, fallback) {
  const v = __ENV[name];
  return v === undefined || v === '' ? fallback : v;
}

function num(name, fallback) {
  return Number(str(name, fallback));
}

// "90s", "3m", "1h", "1m30s" → 초
function seconds(duration) {
  const units = { h: 3600, m: 60, s: 1 };
  let total = 0;
  for (const [, value, unit] of String(duration).matchAll(/(\d+(?:\.\d+)?)(h|m|s)/g)) {
    total += Number(value) * units[unit];
  }
  return total;
}

function classify(res) {
  if (res.status === 200) return 'ISSUED';
  if (res.status === 409) {
    const result = safeJson(res, 'result');
    return result === 'DUPLICATE' ? 'DUPLICATE' : 'SOLD_OUT';
  }
  if (res.status === 503) return 'UNAVAILABLE';
  return 'ERROR';
}

function safeJson(res, path) {
  try {
    return res.json(path);
  } catch (e) {
    return undefined;
  }
}

function waitForApp() {
  for (let i = 0; i < 60; i++) {
    const res = http.get(`${BASE_URL}/actuator/health`, { tags: { name: 'healthcheck' } });
    if (res.status === 200) return;
    sleep(1);
  }
  exec.test.abort('앱이 60초 안에 뜨지 않았다');
}

// ---------- 결과 ----------

function metric(data, name, stat) {
  const m = data.metrics[name];
  return m ? m.values[stat] : 0;
}

function sloResult(data) {
  if (!SLO_SCENARIOS.includes(SCENARIO)) return null;
  const checks = {};
  for (const name of ['http_req_duration{name:issue}', 'http_req_failed{name:issue}']) {
    const t = (data.metrics[name] || {}).thresholds || {};
    for (const [expr, result] of Object.entries(t)) {
      checks[expr] = result.ok;
    }
  }
  return { target: SLO, checks, passed: Object.values(checks).every(Boolean) };
}

function buildReport(data) {
  const total = metric(data, 'coupon_total', 'value');
  const rows = metric(data, 'coupon_issued_rows', 'value');
  const counter = metric(data, 'coupon_issued_counter', 'value');
  const issueRequests = metric(data, 'http_reqs{name:issue}', 'count');
  return {
    runId: RUN_ID,
    scenario: SCENARIO,
    strategy: STRATEGY,
    conditions: {
      quantity: QUANTITY,
      requests: issueRequests,
      maxVus: metric(data, 'vus_max', 'max'),
      config: SCENARIO_CONFIG,
    },
    integrity: {
      totalQuantity: total,
      issuedRows: rows,
      issuedCounter: counter,
      overIssued: Math.max(0, rows - total),
      lostUpdates: Math.max(0, rows - counter),
      // 쿠폰이 남았는데 락 대기 시간 초과 등으로 못 나간 수. 정합성 위반은 아니지만 가용성 문제다.
      underIssued: Math.max(0, Math.min(total, issueRequests) - rows),
      passed: rows <= total && rows === counter,
    },
    outcomes: {
      issued: metric(data, 'issue_issued', 'count'),
      soldOut: metric(data, 'issue_sold_out', 'count'),
      duplicate: metric(data, 'issue_duplicate', 'count'),
      unavailable: metric(data, 'issue_unavailable', 'count'),
      error: metric(data, 'issue_error', 'count'),
      // 도착률 시나리오에서 VU 가 모자라 k6 가 보내지 못한 요청. 0 이 아니면 부하 발생기가 한계다.
      dropped: metric(data, 'dropped_iterations', 'count'),
    },
    performance: {
      durationSec: Number((data.state.testRunDurationMs / 1000).toFixed(2)),
      rps: Number(metric(data, 'http_reqs{name:issue}', 'rate').toFixed(1)),
      errorRate: Number(metric(data, 'http_req_failed{name:issue}', 'rate').toFixed(4)),
      latencyMs: {
        avg: round(metric(data, 'http_req_duration{name:issue}', 'avg')),
        p50: round(metric(data, 'http_req_duration{name:issue}', 'med')),
        p95: round(metric(data, 'http_req_duration{name:issue}', 'p(95)')),
        p99: round(metric(data, 'http_req_duration{name:issue}', 'p(99)')),
        max: round(metric(data, 'http_req_duration{name:issue}', 'max')),
      },
    },
    slo: sloResult(data),
    optimisticRetries: metric(data, 'optimistic_retries', 'value'),
  };
}

function round(v) {
  return Number(v.toFixed(1));
}

function describeConfig(c) {
  switch (c.executor) {
    case 'shared-iterations':
      return `요청 ${c.iterations}건 / 동시 사용자 ${c.vus}`;
    case 'constant-arrival-rate':
      return `${c.rate} req/s × ${c.duration}`;
    default:
      return `${c.startRate} → ${c.stages.map((s) => `${s.target}(${s.duration})`).join(' → ')} req/s`;
  }
}

function renderMarkdown(r) {
  const i = r.integrity;
  const o = r.outcomes;
  const p = r.performance;
  const slo = r.slo
    ? `\n## SLO: ${r.slo.passed ? 'PASS' : 'FAIL'}\n\n| 기준 | 결과 |\n|---|---|\n${
      Object.entries(r.slo.checks).map(([expr, ok]) => `| \`${expr}\` | ${ok ? 'PASS' : 'FAIL'} |`).join('\n')}\n`
    : '';
  return `# ${r.scenario} / ${r.strategy} — ${r.runId}

조건: ${describeConfig(r.conditions.config)} / 쿠폰 수량 ${r.conditions.quantity} / 실제 발급 요청 ${r.conditions.requests} / 최대 VU ${r.conditions.maxVus}

## 정합성: ${i.passed ? 'PASS' : 'FAIL'}

| 항목 | 값 |
|---|---|
| 쿠폰 수량 | ${i.totalQuantity} |
| 실제 발급 행 | ${i.issuedRows} |
| 카운터 컬럼 | ${i.issuedCounter} |
| 초과 발급 | ${i.overIssued} |
| 갱신 손실 (행 - 카운터) | ${i.lostUpdates} |
| 미발급 (재고 남았는데 실패) | ${i.underIssued} |
${slo}
## 응답 분포

| 발급 | 매진 | 중복 | 503 | 기타 오류 | 못 보낸 요청 (dropped) |
|---|---|---|---|---|---|
| ${o.issued} | ${o.soldOut} | ${o.duplicate} | ${o.unavailable} | ${o.error} | ${o.dropped} |

## 성능

| 소요(s) | RPS | 오류율 | avg | p50 | p95 | p99 | max (ms) |
|---|---|---|---|---|---|---|---|
| ${p.durationSec} | ${p.rps} | ${(p.errorRate * 100).toFixed(2)}% | ${p.latencyMs.avg} | ${p.latencyMs.p50} | ${p.latencyMs.p95} | ${p.latencyMs.p99} | ${p.latencyMs.max} |
${r.strategy === 'optimistic' ? `\n낙관적 락 재시도: ${r.optimisticRetries}회\n` : ''}${o.dropped > 0 ? '\n> ⚠️ dropped 가 0 이 아니다. 목표 도착률을 k6 가 못 냈으므로 MAX_VUS 를 늘리거나 결과를 부하 발생기 한계로 해석한다.\n' : ''}`;
}
