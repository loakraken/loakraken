// 최적 정책 동적 계획법: 목표 포인트 합 달성 확률 최대화
// 등장 4개의 분포는 상태별 K개 표본(고정 시드)으로 근사한다.
import { ITEMS, allowed, draw, apply, total, rng } from './sim.mjs';

export function solve({ target, grade = { n: 9, r: 2 }, K = 256, rMax = 6, useReset = true, useReroll = true }) {
  const init = { o: [1, 1, 1, 1], n: grade.n, r: useReroll ? grade.r : 0, c: 0, reset: useReset, processed: false };
  const memo = new Map();
  const key = (s) => `${s.o.join('')}|${s.n}|${Math.min(s.r, rMax)}|${s.c}|${s.reset ? 1 : 0}|${s.processed ? 1 : 0}`;
  const initAfterReset = { ...init, reset: false };

  function vPost(s) { // 가공 직후(다음 등장 전)
    if (total(s) >= target) return 1;
    if (s.n === 0) return s.reset ? vPre(initAfterReset) : 0;
    return vPre(s);
  }
  function vShown(s, shown) {
    let best = 0;
    const proc = shown.reduce((t, it) => t + vPost(apply(s, it)), 0) / shown.length;
    best = Math.max(best, proc);
    if (s.processed) {
      if (total(s) >= target) best = 1;
      if (useReroll && s.r > 0) best = Math.max(best, vPre({ ...s, r: s.r - 1 }));
      if (s.reset) best = Math.max(best, vPre(initAfterReset));
    }
    return best;
  }
  function vPre(s) {
    const k = key(s);
    if (memo.has(k)) return memo.get(k);
    memo.set(k, 0); // 순환 방지(새로고침은 r 감소라 순환 없음)
    let h = 2166136261; for (const ch of k) h = Math.imul(h ^ ch.charCodeAt(0), 16777619);
    const rand = rng(h);
    let sum = 0;
    for (let i = 0; i < K; i++) sum += vShown(s, draw(s, rand));
    const v = sum / K;
    memo.set(k, v);
    return v;
  }
  return { vPre, vShown, init, memo };
}

if ((process.argv[1] ?? '').endsWith('dp.mjs')) {
  const firstPlus = [ITEMS[0], ITEMS[5], ITEMS[10], ITEMS[15]];
  const variants = [['전체', {}], ['초기화 미사용', { useReset: false }], ['새로고침 미사용', { useReroll: false }], ['둘 다 미사용', { useReset: false, useReroll: false }]];
  for (const [name, o] of variants) for (const T of [16, 19]) {
    const t0 = Date.now();
    const m = solve({ target: T, K: Number(process.argv[2] ?? 128), ...o });
    process.stdout.write(name + ' ');
    const v = m.vShown(m.init, firstPlus);
    console.log(`최적정책 목표 ${T}: 처음 등장 [각 옵션 +1]에서 ${(v * 100).toFixed(2)}%  (사전 기대 ${(m.vPre(m.init) * 100).toFixed(2)}%, 상태 ${m.memo.size}, ${((Date.now() - t0) / 1000).toFixed(1)}s)`);
  }
}
