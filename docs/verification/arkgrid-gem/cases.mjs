import { ITEMS } from './sim.mjs';
import { solve } from './dp.mjs';
// ITEMS 순서: 옵션 k(0 의지력,1 포인트,2 효과1,3 효과2) × [+1,+2,+3,+4,-1], 이후 change1,change2,cost+,cost-,keep,reroll1,reroll2
const it = (k, d) => ITEMS[k * 5 + [1, 2, 3, 4, -1].indexOf(d)];
const keep = ITEMS.find((x) => x.t === 'keep');
const cases = [
  { name: '목표 사이트 기준(1·1·1·1, 9회, 새로고침 2)', o: [1, 1, 1, 1], n: 9, r: 2, processed: false, shown: [it(0, 1), it(1, 1), it(2, 1), it(3, 1)], loatto: { 16: 22, 19: 2 } },
  { name: '사례1(3·3·3·3, 5회, 1)', o: [3, 3, 3, 3], n: 5, r: 1, processed: true, shown: [it(0, 1), it(1, 1), it(2, 1), it(3, 1)], loatto: { 16: 66, 19: 3 } },
  { name: '사례2(W4 P3 E4 E3, 4회, 0)', o: [4, 3, 4, 3], n: 4, r: 0, processed: true, shown: [it(1, 2), it(3, 2), it(0, -1), keep], loatto: { 16: 76, 19: 9 } },
  { name: '사례4(W4 P4 E3 E4, 2회, 1)', o: [4, 4, 3, 4], n: 2, r: 1, processed: true, shown: [it(1, 1), it(3, 1), it(0, -1), it(2, -1)], loatto: { 16: 78 } },
];
const K = Number(process.argv[2] ?? 128);
const models = { 16: solve({ target: 16, K, useReset: false }), 19: solve({ target: 19, K, useReset: false }) };
for (const c of cases) {
  const row = [c.name];
  for (const T of [16, 19]) {
    if (c.loatto[T] == null) continue;
    const s = { o: c.o, n: c.n, r: c.r, c: 0, reset: false, processed: c.processed };
    const v = models[T].vShown(s, c.shown) * 100;
    row.push(`목표${T}: 계산 ${v.toFixed(2)}% / 목표 사이트 ${c.loatto[T]}% (차 ${(v - c.loatto[T]).toFixed(2)}%p)`);
  }
  console.log(row.join(' | '));
}
