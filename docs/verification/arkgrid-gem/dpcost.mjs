// 확률 최대 정책을 따를 때의 기대 가공 비용(900G × (1+비용단계))
import { ITEMS, draw, apply, total, rng } from './sim.mjs';
export function solveCost({ target, grade = { n: 9, r: 2 }, K = 128, base = 900 }) {
  const memo = new Map();
  const key = (s) => `${s.o.join('')}|${s.n}|${Math.min(s.r, 6)}|${s.c}|${s.processed ? 1 : 0}`;
  const cost = (s) => base * (1 + s.c);
  function post(s) { if (total(s) >= target) return [1, 0]; if (s.n === 0) return [0, 0]; return pre(s); }
  function shownVal(s, shown) {
    const outs = shown.map((it) => post(apply(s, it)));
    let best = [outs.reduce((t, x) => t + x[0], 0) / 4, cost(s) + outs.reduce((t, x) => t + x[1], 0) / 4];
    if (s.processed) {
      if (total(s) >= target) return [1, 0];
      const better = (x) => x[0] > best[0] + 1e-9 || (Math.abs(x[0] - best[0]) <= 1e-9 && x[1] < best[1]);
      if (s.r > 0) { const rr = pre({ ...s, r: s.r - 1 }); if (better(rr)) best = rr; }
      const stop = [0, 0]; if (better(stop)) best = stop; // 확률이 같으면(예: 0) 멈춘다
    }
    return best;
  }
  function pre(s) {
    const k = key(s); if (memo.has(k)) return memo.get(k);
    let h = 2166136261; for (const ch of k) h = Math.imul(h ^ ch.charCodeAt(0), 16777619);
    const rand = rng(h); let p = 0, c = 0;
    for (let i = 0; i < K; i++) { const v = shownVal(s, draw(s, rand)); p += v[0]; c += v[1]; }
    const v = [p / K, c / K]; memo.set(k, v); return v;
  }
  return { shownVal, pre };
}
if ((process.argv[1] ?? '').endsWith('dpcost.mjs')) {
  const it = (k, d) => ITEMS[k * 5 + [1, 2, 3, 4, -1].indexOf(d)];
  const keep = ITEMS.find((x) => x.t === 'keep');
  const cases = [
    { name: '기준', o: [1, 1, 1, 1], n: 9, r: 2, processed: false, shown: [it(0, 1), it(1, 1), it(2, 1), it(3, 1)], loatto: { 16: '3.4만', 19: '38.5만' } },
    { name: '사례1', o: [3, 3, 3, 3], n: 5, r: 1, processed: true, shown: [it(0, 1), it(1, 1), it(2, 1), it(3, 1)], loatto: { 16: '5,969', 19: '9.5만' } },
    { name: '사례2', o: [4, 3, 4, 3], n: 4, r: 0, processed: true, shown: [it(1, 2), it(3, 2), it(0, -1), keep], loatto: { 16: '2,666', 19: '2.9만' } },
    { name: '사례4', o: [4, 4, 3, 4], n: 2, r: 1, processed: true, shown: [it(1, 1), it(3, 1), it(0, -1), it(2, -1)], loatto: { 16: '1,616' } },
  ];
  const K = Number(process.argv[2] ?? 96);
  const M = { 16: solveCost({ target: 16, K }), 19: solveCost({ target: 19, K }) };
  for (const c of cases) for (const T of [16, 19]) {
    if (!c.loatto[T]) continue;
    const [p, cost] = M[T].shownVal({ o: c.o, n: c.n, r: c.r, c: 0, processed: c.processed }, c.shown);
    console.log(`${c.name} 목표${T}: 확률 ${(p * 100).toFixed(2)}% 기대 가공비 ${cost.toFixed(0)}G, 성공 1회당 ${(cost / p).toFixed(0)}G | 로아또 ${c.loatto[T]}G`);
  }
}
