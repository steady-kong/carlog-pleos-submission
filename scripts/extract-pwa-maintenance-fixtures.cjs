// Reads the original PWA source, never writes to that repository.
// Run with TZ=UTC or TZ=America/New_York and a local TypeScript compiler; output is JSON on stdout.
const fs = require('node:fs');
const crypto = require('node:crypto');
const vm = require('node:vm');
const path = require('node:path');
if (!['UTC', 'America/New_York'].includes(process.env.TZ)) throw new Error('Set TZ to UTC or America/New_York');
const ts = require(process.env.CARLOG_TYPESCRIPT_PATH || '/usr/share/code/resources/app/extensions/node_modules/typescript/lib/typescript.js');
const pwaRoot = process.env.CARLOG_PWA_ROOT || path.resolve(__dirname, '../../carlog');
const source = fs.readFileSync(path.join(pwaRoot, 'src/db/maintenance.ts'), 'utf8');
const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText;
const pwaExports = {};
vm.runInNewContext(js, {
  exports: pwaExports,
  require: name => { if (name === './db') return { db: {} }; throw new Error(`Unexpected PWA import: ${name}`); },
});
const cases = [
  { name: 'distance_soon', schedule: { intervalDistance: 10000, lastDoneOdometer: 10000 }, odometer: 19250, now: '2025-01-01T00:00:00Z' },
  { name: 'distance_overdue', schedule: { intervalDistance: 10000, lastDoneOdometer: 10000 }, odometer: 21001, now: '2025-01-01T00:00:00Z' },
  { name: 'month_end', schedule: { intervalMonths: 6, lastDoneDate: '2024-08-31T00:00:00Z' }, odometer: 1000, now: '2025-02-28T00:00:00Z' },
  { name: 'january_leap_month_end', schedule: { intervalMonths: 1, lastDoneDate: '2024-01-31T00:00:00Z' }, odometer: 0, now: '2024-02-29T00:00:00Z' },
  { name: 'leap_day_next_year', schedule: { intervalMonths: 12, lastDoneDate: '2024-02-29T00:00:00Z' }, odometer: 0, now: '2025-02-28T00:00:00Z' },
  { name: 'half_day_before', schedule: { intervalMonths: 1, lastDoneDate: '2025-01-31T00:00:00Z' }, odometer: 0, now: '2025-02-27T12:00:00Z' },
  { name: 'half_day_after', schedule: { intervalMonths: 1, lastDoneDate: '2025-01-31T00:00:00Z' }, odometer: 0, now: '2025-02-28T12:00:00Z' },
  { name: 'spring_forward_gap', schedule: { intervalMonths: 1, lastDoneDate: '2025-02-09T07:30:00Z' }, odometer: 0, now: '2025-03-09T07:30:00Z' },
  { name: 'fall_back_overlap', schedule: { intervalMonths: 1, lastDoneDate: '2025-10-02T05:30:00Z' }, odometer: 0, now: '2025-11-02T05:30:00Z' },
  { name: 'hours_soon', schedule: { intervalEngineHours: 100 }, odometer: 1000, now: '2025-01-01T00:00:00Z', engineHours: 90 },
  { name: 'distance_hours_tie', schedule: { intervalDistance: 10000, lastDoneOdometer: 10000, intervalEngineHours: 100 }, odometer: 19500, now: '2025-01-01T00:00:00Z', engineHours: 95 },
  { name: 'no_interval', schedule: {}, odometer: 1000, now: '2025-01-01T00:00:00Z' },
];
const fixtures = cases.map(input => {
  const out = pwaExports.computeScheduleStatus(input.schedule, input.odometer, Date.parse(input.now), input.engineHours);
  return { ...input, expected: { status: out.status, distance: out.remainingDistance, days: out.remainingDays, reason: out.reason } };
});
process.stdout.write(JSON.stringify({ source: 'src/db/maintenance.ts', sourceSha256: crypto.createHash('sha256').update(source).digest('hex'), timezone: process.env.TZ, fixtures }, null, 2) + '\n');
