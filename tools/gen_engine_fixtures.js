// Generates app/src/test/resources/engine_fixtures.json from the web engine (reference implementation)
// usage: node tools/gen_engine_fixtures.js <path/to/web/js/engine.js> <path/to/scene_rules.json>
const fs = require('fs'); const path = require('path');
const E = require(path.resolve(process.argv[2]));
const rules = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
let seed = 20261029; const rnd = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648);
const pick = (a) => a[Math.floor(rnd() * a.length)];
const roadTypes = ['MOTORWAY', 'URBAN_EXPRESSWAY', 'DOWNTOWN', 'ALLEY', 'BRIDGE', 'TUNNEL', 'UNKNOWN'];
const scenes = [];
for (let i = 0; i < 400; i++) {
  const ctx = { roadType: pick(roadTypes), rain: rnd() < .3, coastal: rnd() < .2, river: rnd() < .2,
    hour: Math.floor(rnd() * 24), speedMps: rnd() < .1 ? null : Math.round(rnd() * 350) / 10 };
  scenes.push({ ctx, expect: E.pickScene(ctx, rules).id });
}
const hw = Object.keys(rules.road.highwayMap).concat(['footway', 'cycleway']);
const osm = [];
for (let i = 0; i < 200; i++) {
  const els = []; const n = Math.floor(rnd() * 4);
  for (let k = 0; k < n; k++) {
    const t = { highway: pick(hw) };
    if (rnd() < .2) t.bridge = pick(['yes', 'no', 'viaduct']);
    if (rnd() < .15) t.tunnel = pick(['yes', 'no']);
    if (rnd() < .5) t.name = '도로' + k; if (rnd() < .2) t['name:ko'] = '한글' + k; if (rnd() < .2) t.ref = String(k);
    els.push({ tags: t });
  }
  if (rnd() < .2) els.push({ tags: { waterway: pick(['river', 'riverbank', 'stream']) } });
  if (rnd() < .1) els.push({ tags: { natural: 'water', water: 'river' } });
  if (rnd() < .15) els.push({ tags: { natural: 'coastline' } });
  const speed = rnd() < .1 ? null : Math.round(rnd() * 300) / 10;
  osm.push({ elements: els, speedMps: speed, expect: E.classifyOsm(els, speed, rules) });
}
const stab = [];
for (let i = 0; i < 30; i++) {
  const s = new E.Stabilizer(rules.stabilizer); let t = 0; const steps = [];
  for (let k = 0; k < 40; k++) { t += Math.floor(rnd() * 8000); const id = pick(['DOWNTOWN', 'ALLEY', 'BRIDGE', 'TUNNEL', 'HIGHWAY']); steps.push({ t, id, expect: s.propose(id, t) }); }
  stab.push(steps);
}
// taste plans: replay a recorded random sequence (rs) on the Kotlin side
const plans = []; const gk = Object.keys(rules.genres), mk = Object.keys(rules.taste.moods);
for (let i = 0; i < 120; i++) {
  const profile = { genres: gk.filter(() => rnd() < .3), moods: mk.filter(() => rnd() < .3),
    artists: rnd() < .3 ? [{ id: 'A' + i, name: '가수' + i }] : [], w: {}, skips: {}, liked: [], done: true };
  if (rnd() < .5) profile.w['mood:' + pick(mk)] = Math.round(rnd() * 30) / 10 + .2;
  if (profile.genres.length && rnd() < .5) profile.w['genre:' + profile.genres[0]] = Math.round(rnd() * 30) / 10 + .2;
  const scene = pick(rules.scenes), speedMps = rnd() < .2 ? null : Math.round(rnd() * 350) / 10, rain = rnd() < .3;
  const nowMs = Date.UTC(2026, Math.floor(rnd() * 12), 1 + Math.floor(rnd() * 28), Math.floor(rnd() * 24), Math.floor(rnd() * 60)) - 9 * 3600000;  // KST wall time
  const ctx = { now: new Date(nowMs), wxCode: rnd() < .3 ? null : pick([0, 2, 45, 61, 65, 73, 95]), rain, tempC: rnd() < .2 ? null : Math.round(rnd() * 450 - 100) / 10,
    sunrise: nowMs - Math.floor(rnd() * 3) * 1800000, sunset: nowMs + Math.floor(rnd() * 4) * 1800000, speedMps, jam: rnd() < .2, driveMin: Math.floor(rnd() * 120),
    sceneLabel: rnd() < .5 ? scene.label : null, roadReasons: rnd() < .3 ? ['해안도로'] : [] };
  const rs = Array.from({ length: 40 }, () => rnd()); let k = 0;
  plans.push({ scene: scene.id, profile, ctx: Object.assign({}, ctx, { now: nowMs }), rs, expect: E.buildPlans(scene, profile, rules, ctx, () => rs[k++]) });
}
fs.writeFileSync(path.join(__dirname, '../app/src/test/resources/engine_fixtures.json'),
  JSON.stringify({ generatedFrom: 'web/js/engine.js', rulesVersion: rules.version, scenes, osm, stab, plans }));
console.log('fixtures', scenes.length, osm.length, stab.length);
