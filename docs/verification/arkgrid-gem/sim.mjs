// 공식 확률표(젬 가공, 2025-08-20) 기반 젬 가공 시뮬레이터
// 상태: o=[의지력 효율, 포인트, 효과1, 효과2], n 남은 가공, r 다른 항목 보기, c 비용 단계(-1/0/1), reset 가능
const OPT_ITEMS = [];
for (let k = 0; k < 4; k++) {
  OPT_ITEMS.push({ k, d: 1, w: 11.65 }, { k, d: 2, w: 4.4 }, { k, d: 3, w: 1.75 }, { k, d: 4, w: 0.45 }, { k, d: -1, w: 3.0 });
}
const OTHER = [
  { t: 'change1', w: 3.25 }, { t: 'change2', w: 3.25 },
  { t: 'cost+', w: 1.75 }, { t: 'cost-', w: 1.75 }, { t: 'keep', w: 1.75 },
  { t: 'reroll1', w: 2.5 }, { t: 'reroll2', w: 0.75 },
];
export const ITEMS = [...OPT_ITEMS, ...OTHER];

export function allowed(item, s) {
  if (item.k !== undefined) {
    const v = s.o[item.k];
    if (item.d > 0) return v + item.d <= 5;
    return v > 1;
  }
  if (item.t === 'cost+') return s.c < 1 && s.n > 1;
  if (item.t === 'cost-') return s.c > -1 && s.n > 1;
  if (item.t === 'reroll1' || item.t === 'reroll2') return s.n > 1;
  return true;
}

// 가중치 비복원 추출로 4개
export function draw(s, rand) {
  const pool = ITEMS.filter((it) => allowed(it, s));
  const out = [];
  for (let j = 0; j < 4 && pool.length; j++) {
    const W = pool.reduce((t, x) => t + x.w, 0);
    let u = rand() * W; let idx = 0;
    while (u >= pool[idx].w && idx < pool.length - 1) { u -= pool[idx].w; idx++; }
    out.push(pool.splice(idx, 1)[0]);
  }
  return out;
}

export function apply(s, it) {
  const t = { ...s, o: [...s.o], n: s.n - 1, processed: true };
  if (it.k !== undefined) t.o[it.k] += it.d;
  else if (it.t === 'cost+') t.c += 1;
  else if (it.t === 'cost-') t.c -= 1;
  else if (it.t === 'reroll1') t.r += 1;
  else if (it.t === 'reroll2') t.r += 2;
  return t;
}

export const total = (s) => s.o.reduce((a, b) => a + b, 0);
export function rng(seed) { let x = seed >>> 0; return () => { x ^= x << 13; x >>>= 0; x ^= x >> 17; x ^= x << 5; x >>>= 0; return x / 4294967296; }; }

// 정책 A: 목표 도달 시 완료, 아니면 계속 가공(새로고침·초기화 미사용). 첫 4개는 주어진 값
export function simulateA(start, first, target, N, seed = 1) {
  const rand = rng(seed); let ok = 0;
  for (let i = 0; i < N; i++) {
    let s = { ...start, o: [...start.o] };
    let shown = first;
    while (s.n > 0) {
      const it = shown[Math.floor(rand() * 4)];
      s = apply(s, it);
      if (total(s) >= target) break;
      if (s.n > 0) shown = draw(s, rand);
    }
    if (total(s) >= target) ok++;
  }
  return ok / N;
}

if ((process.argv[1] ?? '').endsWith('sim.mjs')) {
  const start = { o: [1, 1, 1, 1], n: 9, r: 2, c: 0, reset: true, processed: false };
  const first = [OPT_ITEMS[0], OPT_ITEMS[5], OPT_ITEMS[10], OPT_ITEMS[15]]; // 각 옵션 +1
  for (const T of [16, 19]) console.log(`정책A 목표 ${T}: ${(simulateA(start, first, T, 400000, 7) * 100).toFixed(2)}%`);
}
