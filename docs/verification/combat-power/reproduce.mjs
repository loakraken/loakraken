// 전투력 모델 가설별 잔차표 재현 스크립트.
// 사용: node reproduce.mjs <samples.json> [--json out.json] [--include-excluded]
// v2(2026-10-09): 빛의 기사 딜러 포함, 달인의 저력 계수(K1), 5겹 교차검증 열 추가
// 모델: ln(전투력) = c + ln(공격력) + Σ ln(1 + 요소) + Σ β·자유 특성 (최소제곱)
import fs from 'node:fs';

const args = process.argv.slice(2);
const samplesPath = args[0];
const jsonOut = args.includes('--json') ? args[args.indexOf('--json') + 1] : null;
const { samples } = JSON.parse(fs.readFileSync(samplesPath, 'utf8'));

// 서포터 각성. v2: 빛의 기사(발키리 딜러)를 서포터에서 뺐다
const SUPPORT_TITLES = new Set(['절실한 구원', '만개', '축복의 오라', '해방자']);
const includeExcluded = args.includes('--include-excluded');
export const dealers = samples.filter((s) => (includeExcluded || !s.excluded) && s.arkPassiveTitle && !SUPPORT_TITLES.has(s.arkPassiveTitle)
  && s.itemLevel >= 1680 && s.baseAttack > 0 && s.expected.combatPower > 0);

// 인벤 딜러 전투력 로직(2025-07-09 패치 반영) 유물 4레벨 기준 각인 계수 범위 [스톤0, 스톤4] %
const ENGRAVING_RANGE = {
  '원한': [21, 27], '아드레날린': [19.4, 25.1], '돌격대장': [19.2, 25.2], '질량 증가': [19, 25],
  '결투의 대가': [18.1, 23.5], '기습의 대가': [18.1, 23.5], '예리한 둔기': [17.36, 22.95],
  '저주받은 인형': [17, 23], '바리케이드': [17, 23], '안정된 상태': [17, 23], '타격의 대가': [17, 23],
  '속전속결': [16.8, 21.6], '슈퍼 차지': [16.8, 21.6], '에테르 포식자': [16.2, 22.2],
  '마나 효율 증가': [16, 22], '약자 무시': [13.2, 18], '정밀 단도': [12.7, 16.9], '추진력': [11.9, 16.1],
};
// 원한·타격의 대가 설명 수치에서 확인한 스톤 레벨별 증가(%p): 0, 3, 3.75, 5.25, 6
const STONE_IDX = [0, 3, 3.75, 5.25, 6];
const GRADE_LEVEL_OFFSET = { '유물': 0, '전설': -4, '영웅': -8 };
const l1p = Math.log1p;

// 공식 설명 구조가 같은 각인(조건부 피해 17%)을 같은 범위로 둔다. 계수 출처는 추정
const EXTRA_ENGRAVING_RANGE = { '달인의 저력': [17, 23] };
function engravingPct(e, levelScale, extra = false) {
  const r = ENGRAVING_RANGE[e.name] ?? (extra ? EXTRA_ENGRAVING_RANGE[e.name] : undefined);
  if (!r) return null;
  const k = (r[1] - r[0]) / 6;
  let v = r[0] + k * STONE_IDX[Math.min(4, Math.max(0, e.stone ?? 0))];
  const lv = e.level + (GRADE_LEVEL_OFFSET[e.grade] ?? 0);
  return v - levelScale * k * 0.75 * (4 - lv); // 원한: 유물 1레벨당 0.75%p
}
const gem4 = (lv) => (lv <= 0 ? 0 : 1.28 + 0.64 * (lv - 1));
const gem3 = (lv) => (lv <= 0 ? 0 : 0.48 + (6.4 - 0.48) / 9 * (lv - 1));

function bracelet(s) {
  let t = s.braceletText ?? '';
  const c = { critRate: 0, critDmg: 0, addDmg: 0, dmg: 0, critHitDmg: 0, weaponBuff: 0 };
  const take = (re, k, mul = () => 1) => { t = t.replace(re, (...m) => { c[k] += Number(m[1]) * mul(m); return ' '; }); };
  take(/공격이 치명타로 적중 시 적에게 주는 피해가 ([\d.]+)% 증가한다\.?/g, 'critHitDmg');
  take(/(?:백어택|헤드어택|방향성 공격이 아닌) 스킬이 적에게 주는 피해가 ([\d.]+)% 증가한다\.?/g, 'directional');
  take(/무기 공격력이 (\d+), 공격 및 이동 속도가 1% 증가한다\. \(최대 (\d+)중첩\)/g, 'weaponBuff', (m) => Number(m[2]));
  take(/무기 공격력이 (\d+) 증가한다\. \(최대 (\d+)중첩\)/g, 'weaponBuff', (m) => Number(m[2]));
  take(/적에게 공격 적중 시 5초 동안 무기 공격력이 (\d+) 증가한다/g, 'weaponBuff');
  take(/치명타 적중률(?: \+|이 )([\d.]+)%/g, 'critRate');
  take(/치명타 피해(?: \+|가 )([\d.]+)%/g, 'critDmg');
  take(/추가 피해(?: \+|가 )([\d.]+)%/g, 'addDmg');
  take(/적에게 주는 피해가 ([\d.]+)% 증가/g, 'dmg');
  const weaponTotal = (s.weapon.weaponAttack || 1) * 1.3;
  return l1p(c.critRate * 0.774 / 100) + l1p(c.critDmg * 0.3 / 100) + l1p(c.addDmg * 0.769 / 100) + l1p(c.dmg / 100)
    + l1p(c.critHitDmg * 0.774 / 100) + 0.5 * l1p(c.weaponBuff / weaponTotal);
}
function braceletV1(s) {
  const b = s.braceletText ?? ''; let br = 0;
  for (const m of b.matchAll(/치명타 피해 \+([\d.]+)%/g)) br += l1p(Number(m[1]) * 0.3 / 100);
  for (const m of b.matchAll(/추가 피해 \+([\d.]+)%/g)) br += l1p(Number(m[1]) * 0.769 / 100);
  for (const m of b.matchAll(/(?:추가 피해가|적에게 주는 피해가|치명타 피해가) ([\d.]+)% 증가한다/g)) br += l1p(Number(m[1]) / 100);
  return br;
}

// 고정 요소의 ln 합(오프셋)
function offset(s, o) {
  const a = s.accessoryPct ?? {}; const g = s.arkGrid.effects ?? {};
  let v = o.totalAttack ? Math.log(s.attack) : Math.log(s.baseAttack);
  v += l1p(s.weapon.addDamagePct / 100);
  v += l1p(0.0003 * (s.stats.crit + s.stats.spec + s.stats.swift));
  const p = s.arkPassive.points;
  v += l1p(0.005 * Math.max(0, p.evolution - 40)) + l1p(0.007 * p.enlightenment) + l1p(0.002 * p.leap);
  for (const e of s.engravings) { const x = engravingPct(e, o.levelScale ?? 0, o.extraEngravings); if (x != null) v += l1p(x / 100); }
  for (const gm of s.gems) v += l1p((['겁화', '작열', '광휘'].includes(gm.kind) ? gem4(gm.level) : gem3(gm.level)) / 100);
  v += l1p((a['추가 피해'] ?? 0) * 0.769 / 100) + l1p((a['적에게 주는 피해'] ?? 0) / 100)
    + l1p((a['치명타 적중률'] ?? 0) * 0.774 / 100) + l1p((a['치명타 피해'] ?? 0) * 0.3 / 100);
  if (!o.totalAttack) v += l1p((a['공격력'] ?? 0) / 100) + l1p((a['공격력(정수)'] ?? 0) / 142857) + l1p((g['공격력'] ?? 0) / 100);
  v += l1p((g['추가 피해'] ?? 0) * 0.769 / 100) + l1p((g['보스 피해'] ?? 0) / 100);
  v += l1p(s.cardElementPct / 100);
  v += o.braceletV2 ? bracelet(s) : braceletV1(s);
  if (o.chaosCores) {
    const e = {};
    for (const c of s.arkGrid.cores) if (c.kind === '혼돈') for (const [k, x] of Object.entries(c.effects)) e[k] = (e[k] ?? 0) + x;
    v += l1p((e.atkPct ?? 0) / 100) + l1p((e.atkFlat ?? 0) / s.baseAttack) + 0.5 * l1p((e.weaponPct ?? 0) / 100)
      + l1p((e.addDmg ?? 0) * 0.769 / 100) + l1p((e.critDmg ?? 0) * 0.3 / 100) + l1p((e.critRate ?? 0) * 0.774 / 100)
      + l1p((e.dmg ?? 0) / 100) + l1p((e.bossDmg ?? 0) / 100) + l1p((e.critHitDmg ?? 0) * 0.774 / 100);
  }
  return v;
}

const order = (s) => s.arkGrid.cores.filter((c) => c.kind === '질서');
const radiance = (s, pred = () => true) => s.gems.filter((g) => g.kind === '광휘' && pred(g)).reduce((t, g) => t + l1p(gem4(g.level) / 100), 0);
const FREE = {
  order10: (s) => order(s).filter((c) => c.point >= 10).length,
  order14: (s) => order(s).filter((c) => c.point >= 14).length,
  order17: (s) => order(s).filter((c) => c.point >= 17).length,
  order18to20: (s) => order(s).reduce((t, c) => t + Math.max(0, Math.min(20, c.point) - 17), 0),
  orb: (s) => l1p(s.orbParadisePower / 1e7),
  karmaEvolution: (s) => s.arkPassive.karmaLevel.evolution,
  karmaEnlightenment: (s) => s.arkPassive.karmaLevel.enlightenment,
  karmaLeap: (s) => s.arkPassive.karmaLevel.leap,
  engravingLevelScale: (s) => -s.engravings.reduce((t, e) => {
    const a = engravingPct(e, 0); const b = engravingPct(e, 1);
    return a == null ? t : t + l1p(a / 100) - l1p(b / 100);
  }, 0),
  radianceExtra: (s) => radiance(s),
  orderAncient17: (s) => order(s).filter((c) => c.grade === '고대' && c.point >= 17).length,
  chaosAncient17: (s) => s.arkGrid.cores.filter((c) => c.kind === '혼돈' && c.grade === '고대' && c.point >= 17).length,
  weaponTerm: (s) => l1p(s.weapon.addDamagePct / 100),
  gridTerm: (s) => { const g = s.arkGrid.effects ?? {}; return l1p((g['추가 피해'] ?? 0) * 0.769 / 100) + l1p((g['보스 피해'] ?? 0) / 100); },
  accTerm: (s) => { const a = s.accessoryPct ?? {}; return l1p((a['추가 피해'] ?? 0) * 0.769 / 100) + l1p((a['적에게 주는 피해'] ?? 0) / 100) + l1p((a['치명타 적중률'] ?? 0) * 0.774 / 100) + l1p((a['치명타 피해'] ?? 0) * 0.3 / 100); },
  statTerm: (s) => l1p(0.0003 * (s.stats.crit + s.stats.spec + s.stats.swift)),
  hybridDealer: (s) => (['홀리나이트', '도화가', '발키리', '바드'].includes(s.className) ? 1 : 0),
  zeroEngravings: (s) => s.engravings.filter((e) => ['선수필승', '승부사', '마나의 흐름', '정기 흡수'].includes(e.name)).length,
  radianceDamageExtra: (s) => radiance(s, (g) => g.mode === 'DAMAGE'),
  radianceCooldownExtra: (s) => radiance(s, (g) => g.mode !== 'DAMAGE'),
};
const ORD = ['order10', 'order14', 'order17', 'order18to20'];
const KAR = ['karmaEvolution', 'karmaEnlightenment', 'karmaLeap'];
const BEST = { chaosCores: true, totalAttack: true, braceletV2: true };
export const HYPOTHESES = [
  { id: 'H0', label: '인벤 2025-07 계수 그대로(상수만 적합)', opt: {}, free: [] },
  { id: 'H1', label: 'H0 + 혼돈 코어 효과(고정 환산) + 질서 코어 구간', opt: { chaosCores: true }, free: ORD },
  { id: 'H2', label: 'H1 + 보주 낙원력', opt: { chaosCores: true }, free: [...ORD, 'orb'] },
  { id: 'H3', label: 'H2 + 팔찌 옵션 분류 환산 + 공식 공격력 총합', opt: BEST, free: [...ORD, 'orb'] },
  { id: 'H4', label: 'H3 + 카르마 레벨', opt: BEST, free: [...ORD, 'orb', ...KAR] },
  { id: 'E0', label: 'H4, 각인 유물 레벨 무시(모두 유물 4)', opt: BEST, free: [...ORD, 'orb', ...KAR] },
  { id: 'E1', label: 'H4, 유물 레벨 선형(원한 설명 비율, 자유 0)', opt: { ...BEST, levelScale: 1 }, free: [...ORD, 'orb', ...KAR] },
  { id: 'E2', label: 'H4 + 유물 레벨 배율 자유', opt: BEST, free: [...ORD, 'orb', ...KAR, 'engravingLevelScale'], probe: ['engravingLevelScale'] },
  { id: 'G1', label: 'E1 + 광휘 추가 배율 자유', opt: { ...BEST, levelScale: 1 }, free: [...ORD, 'orb', ...KAR, 'radianceExtra'], probe: ['radianceExtra'] },
  { id: 'G2', label: 'E1 + 광휘 피해형·쿨감형 분리', opt: { ...BEST, levelScale: 1 }, free: [...ORD, 'orb', ...KAR, 'radianceDamageExtra', 'radianceCooldownExtra'], probe: ['radianceDamageExtra', 'radianceCooldownExtra'] },
  { id: 'K1', label: 'E1 + 달인의 저력 계수(안정된 상태와 같은 범위)', opt: { ...BEST, levelScale: 1, extraEngravings: true }, free: [...ORD, 'orb', ...KAR] },
  { id: 'K2', label: 'K1 + 비전투 각인 수(선수필승·승부사·마나의 흐름·정기 흡수) 자유', opt: { ...BEST, levelScale: 1, extraEngravings: true }, free: [...ORD, 'orb', ...KAR, 'zeroEngravings'], probe: ['zeroEngravings'] },
  { id: 'K3', label: 'K1 + 하이브리드 직업 딜러(홀나·도화가·발키리·바드) 자유', opt: { ...BEST, levelScale: 1, extraEngravings: true }, free: [...ORD, 'orb', ...KAR, 'hybridDealer'], probe: ['hybridDealer'] },
  { id: 'S1', label: 'K1 + 무기 품질·아크 그리드(추피·보피)·연마·치특신 항 배율 자유', opt: { ...BEST, levelScale: 1, extraEngravings: true }, free: [...ORD, 'orb', ...KAR, 'weaponTerm', 'gridTerm', 'accTerm', 'statTerm'], probe: ['weaponTerm', 'gridTerm', 'accTerm', 'statTerm'] },
  { id: 'C1', label: 'K1 + 고대 질서 코어(17P 이상) 수 자유', opt: { ...BEST, levelScale: 1, extraEngravings: true }, free: [...ORD, 'orb', ...KAR, 'orderAncient17'], probe: ['orderAncient17'] },
  { id: 'C2', label: 'C1 + 고대 혼돈 코어(17P 이상) 수 자유', opt: { ...BEST, levelScale: 1, extraEngravings: true }, free: [...ORD, 'orb', ...KAR, 'orderAncient17', 'chaosAncient17'], probe: ['orderAncient17', 'chaosAncient17'] },
];

function ols(X, y) {
  const p = X[0].length;
  const M = Array.from({ length: p }, (_, j) => {
    const row = new Array(p + 1).fill(0);
    for (let i = 0; i < X.length; i++) { for (let k = 0; k < p; k++) row[k] += X[i][j] * X[i][k]; row[p] += X[i][j] * y[i]; }
    row[j] += 1e-9; return row;
  });
  for (let c = 0; c < p; c++) {
    let piv = c; for (let r = c + 1; r < p; r++) if (Math.abs(M[r][c]) > Math.abs(M[piv][c])) piv = r;
    [M[c], M[piv]] = [M[piv], M[c]];
    for (let r = 0; r < p; r++) if (r !== c) { const f = M[r][c] / M[c][c]; for (let k = c; k <= p; k++) M[r][k] -= f * M[c][k]; }
  }
  return M.map((row, i) => row[p] / row[i]);
}
export function fit(rows, h) {
  const y = rows.map((s) => Math.log(s.expected.combatPower) - offset(s, h.opt));
  const X = rows.map((s) => [1, ...h.free.map((k) => FREE[k](s))]);
  const beta = ols(X, y);
  const res = rows.map((s, i) => { const r = y[i] - X[i].reduce((t, v, j) => t + v * beta[j], 0); return { id: s.id, itemLevel: s.itemLevel, r, pct: (Math.exp(r) - 1) * 100 }; });
  const sd = Math.sqrt(res.reduce((t, x) => t + x.r ** 2, 0) / Math.max(1, res.length - beta.length)) * 100;
  return { beta, res, sd };
}
function summary(res) {
  if (!res.length) return null;
  return { n: res.length, rmsLogPct: Math.sqrt(res.reduce((t, x) => t + x.r ** 2, 0) / res.length) * 100,
    meanPct: res.reduce((t, x) => t + x.pct, 0) / res.length, meanAbsPct: res.reduce((t, x) => t + Math.abs(x.pct), 0) / res.length,
    maxAbsPct: Math.max(...res.map((x) => Math.abs(x.pct))) };
}
function bootstrap(h, B = 400) {
  let seed = 20261008; const rand = () => { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; };
  const idx = h.probe.map((k) => 1 + h.free.indexOf(k)); const vals = idx.map(() => []);
  for (let b = 0; b < B; b++) {
    const sample = dealers.map(() => dealers[Math.floor(rand() * dealers.length)]);
    const { beta } = fit(sample, h); idx.forEach((j, t) => vals[t].push(beta[j]));
  }
  return vals.map((v) => { v.sort((a, b) => a - b); return { lo: v[Math.floor(B * 0.025)], hi: v[Math.floor(B * 0.975)] }; });
}

// 5겹 교차검증: id 순서로 5개 묶음을 만들고, 각 묶음을 나머지로 적합한 계수로 예측한다
function crossValidate(h, k = 5) {
  const sorted = [...dealers].sort((a, b) => (a.id < b.id ? -1 : 1));
  const out = [];
  for (let f = 0; f < k; f++) {
    const test = sorted.filter((_, i) => i % k === f); const train = sorted.filter((_, i) => i % k !== f);
    const { beta } = fit(train, h);
    for (const s of test) {
      const y = Math.log(s.expected.combatPower) - offset(s, h.opt);
      const x = [1, ...h.free.map((key) => FREE[key](s))];
      const r = y - x.reduce((t, v, j) => t + v * beta[j], 0);
      out.push({ id: s.id, itemLevel: s.itemLevel, r, pct: (Math.exp(r) - 1) * 100 });
    }
  }
  return out;
}

const lowRelic = (s) => s.engravings.some((e) => e.level < 4 || e.grade !== '유물');
const hasRadiance = (s) => s.gems.some((g) => g.kind === '광휘');
const byId = Object.fromEntries(dealers.map((s) => [s.id, s]));
const results = HYPOTHESES.map((h) => {
  const { beta, res, sd } = fit(dealers, h);
  const out = { id: h.id, label: h.label, n: res.length, params: beta.length, sdLogPct: sd, all: summary(res),
    itemLevel1740Plus: summary(res.filter((x) => x.itemLevel >= 1740)), itemLevelBelow1740: summary(res.filter((x) => x.itemLevel < 1740)),
    relicBelow4: summary(res.filter((x) => lowRelic(byId[x.id]))), relicBelow4At1740Plus: summary(res.filter((x) => lowRelic(byId[x.id]) && x.itemLevel >= 1740)),
    radiance: summary(res.filter((x) => hasRadiance(byId[x.id]))),
    coefficients: Object.fromEntries([['intercept', beta[0]], ...h.free.map((k, j) => [k, beta[j + 1]])]),
    residuals: res.map((x) => ({ id: x.id, residualPct: Number(x.pct.toFixed(2)) })) };
  const cv = crossValidate(h);
  out.crossValidation = { folds: 5, all: summary(cv), itemLevel1740Plus: summary(cv.filter((x) => x.itemLevel >= 1740)) };
  if (h.probe) out.probeCi95 = Object.fromEntries(h.probe.map((k, t) => [k, bootstrap(h)[t]]));
  return out;
});

const f2 = (x) => (x == null ? '-' : x.toFixed(2));
const seg = (s) => (s ? `${s.n} / ${f2(s.rmsLogPct)} / ${f2(s.maxAbsPct)}` : '-');
console.log(`딜러 표본 ${dealers.length}명 (전체 ${samples.length}명, 제외 표시 ${samples.filter((x) => x.excluded).length}명${includeExcluded ? ' 포함' : ' 제외'})`);
console.log('| 가설 | 자유 계수 | 로그 잔차 SD % | 평균 절대 % | 최대 절대 % | 1740+ (n/RMS/최대) | 1740 미만 | 유물<4 | 유물<4·1740+ | 광휘 보유 | 표본 밖 RMS(전체/1740+) |');
console.log('|---|---|---|---|---|---|---|---|---|---|---|');
for (const r of results) console.log(`| ${r.id} ${r.label} | ${r.params} | ${f2(r.sdLogPct)} | ${f2(r.all.meanAbsPct)} | ${f2(r.all.maxAbsPct)} | ${seg(r.itemLevel1740Plus)} | ${seg(r.itemLevelBelow1740)} | ${seg(r.relicBelow4)} | ${seg(r.relicBelow4At1740Plus)} | ${seg(r.radiance)} | ${f2(r.crossValidation.all.rmsLogPct)} / ${f2(r.crossValidation.itemLevel1740Plus.rmsLogPct)} |`);
for (const r of results.filter((x) => x.probeCi95)) {
  console.log(`${r.id}: ${Object.entries(r.probeCi95).map(([k, c]) => `${k}=${r.coefficients[k].toFixed(3)} (95% CI ${c.lo.toFixed(3)}~${c.hi.toFixed(3)})`).join(', ')}`);
}
if (jsonOut) fs.writeFileSync(jsonOut, JSON.stringify({ generatedFrom: samplesPath.split(/[\\/]/).pop(), dealers: dealers.length, results }, null, 1));
