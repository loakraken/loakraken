// 어빌리티 스톤 세공(돌파고) 확률 규칙 재현.
// 규칙: 성공 확률 75%에서 시작, 성공 −10%p, 실패 +10%p, 범위 25~75%, 세 줄이 확률을 공유한다.
// 사용: node stone.mjs <expected.json>
import fs from 'node:fs';

const STEP = 10; const LO = 25; const HI = 75; const START = 75;

// 총 시도 T회의 성공 총합 분포(%). 줄 선택과 무관하다.
export function totalDistribution(T) {
  let cur = new Map([[`${START}|0`, 1]]);
  for (let t = 0; t < T; t++) {
    const nxt = new Map();
    for (const [k, pr] of cur) {
      const [p, s] = k.split('|').map(Number);
      const a = `${Math.max(LO, p - STEP)}|${s + 1}`; const b = `${Math.min(HI, p + STEP)}|${s}`;
      nxt.set(a, (nxt.get(a) ?? 0) + pr * p / 100); nxt.set(b, (nxt.get(b) ?? 0) + pr * (1 - p / 100));
    }
    cur = nxt;
  }
  const d = new Array(T + 1).fill(0);
  for (const [k, pr] of cur) d[Number(k.split('|')[1])] += pr * 100;
  return d;
}

// 수동 최적 선택: goal(s1, s2, s3) 달성 확률 최대
export function bestManual(N, goal) {
  const memo = new Map();
  function V(a1, s1, a2, s2, a3, s3, p) {
    if (a1 === N && a2 === N && a3 === N) return goal(s1, s2, s3) ? 1 : 0;
    const key = [a1, s1, a2, s2, a3, s3, p].join(','); if (memo.has(key)) return memo.get(key);
    const up = Math.max(LO, p - STEP); const dn = Math.min(HI, p + STEP); const q = p / 100; let b = 0;
    if (a1 < N) b = Math.max(b, q * V(a1 + 1, s1 + 1, a2, s2, a3, s3, up) + (1 - q) * V(a1 + 1, s1, a2, s2, a3, s3, dn));
    if (a2 < N) b = Math.max(b, q * V(a1, s1, a2 + 1, s2 + 1, a3, s3, up) + (1 - q) * V(a1, s1, a2 + 1, s2, a3, s3, dn));
    if (a3 < N) b = Math.max(b, q * V(a1, s1, a2, s2, a3 + 1, s3 + 1, up) + (1 - q) * V(a1, s1, a2, s2, a3 + 1, s3, dn));
    memo.set(key, b); return b;
  }
  return V(0, 0, 0, 0, 0, 0, START);
}
export const stonesForQuantile = (p, x) => Math.ceil(Math.log(1 - x) / Math.log(1 - p));

if ((process.argv[1] ?? '').endsWith('stone.mjs')) {
  const exp = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  for (const [N, official] of Object.entries(exp.officialTotalSuccessPct)) {
    const d = totalDistribution(3 * Number(N));
    let maxAbs = 0; d.forEach((v, i) => { maxAbs = Math.max(maxAbs, Math.abs(v - official[i])); });
    console.log(`공식 자동 세공 ${N}칸: 성공 총합 분포 최대 차 ${maxAbs.toExponential(2)}%p (허용 ${exp.tolerance.totalSuccessPctPoint})`);
  }
  for (const c of exp.manualCases) {
    const goal = new Function('a', 'b', 'c', `return ${c.goalExpr};`);
    const p = bestManual(c.slots, goal);
    const q = c.stoneQuantiles.map((x) => stonesForQuantile(p, x));
    console.log(`${c.id}: 계산 ${(p * 100).toFixed(4)}% / 일로아 ${c.iloaPct}% | 필요 돌 ${q.join('/')} / 일로아 ${c.iloaStones.join('/')}`);
  }
}
