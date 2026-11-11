// Reads PWA source only; output is JSON on stdout. Run with TZ=UTC.
const fs = require('node:fs');
const crypto = require('node:crypto');
const vm = require('node:vm');
const path = require('node:path');
if (process.env.TZ !== 'UTC') throw new Error('Run with TZ=UTC');
const ts = require(process.env.CARLOG_TYPESCRIPT_PATH || '/usr/share/code/resources/app/extensions/node_modules/typescript/lib/typescript.js');
const root = process.env.CARLOG_PWA_ROOT || path.resolve(__dirname, '../../carlog');
const analytics = fs.readFileSync(path.join(root, 'src/db/analytics.ts'), 'utf8');
const models = fs.readFileSync(path.join(root, 'src/types/models.ts'), 'utf8');
function load(source, resolver) {
  const target = {};
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText;
  vm.runInNewContext(js, { exports: target, require: resolver });
  return target;
}
const types = load(models, name => { throw new Error(`Unexpected model import: ${name}`); });
const pwa = load(analytics, name => {
  if (name === './db') return { db: {} };
  if (name === '@/types/models') return types;
  throw new Error(`Unexpected analytics import: ${name}`);
});
const records = [
  { type: 'fuel', date: '2025-02-28T23:59:59.000Z', odometer: 1000, cost: 50000 },
  { type: 'maintenance', date: '2025-03-01T00:00:00.000Z', odometer: 1500, cost: 10000 },
  { type: 'expense', date: '2025-03-02T00:00:00.000Z', cost: 2000, category: 'parking' },
  { type: 'odometer', date: '2025-03-03T00:00:00.000Z', odometer: 1600, cost: 0 },
  { type: 'fuel', date: '2025-03-04T00:00:00.000Z', odometer: 9000, cost: 90000, deleted: true },
];
function compute(start) {
  const activeRecord = r => ({ date: r.date, deletedAt: r.deleted ? '2025-03-05T00:00:00.000Z' : null });
  const fuel = records.filter(r => r.type === 'fuel').map(r => ({ ...activeRecord(r), totalCost: r.cost }));
  const care = records.filter(r => r.type === 'maintenance').map(r => ({ ...activeRecord(r), totalCost: r.cost }));
  const expenses = records.filter(r => r.type === 'expense').map(r => ({ ...activeRecord(r), amount: r.cost, category: r.category }));
  const readings = records.filter(r => r.odometer != null).map(r => ({ ...activeRecord(r), odometer: r.odometer }));
  const result = pwa.computeSpending(fuel, care, expenses, readings, start);
  return { total: result.total, fuel: result.fuel, maintenance: result.maintenance, distance: result.distance,
    costPerKm: result.costPerKm, categories: result.byCategory, monthly: result.monthly };
}
const fixtures = [null, '2025-03-01T00:00:00.000Z', '2025-04-01T00:00:00.000Z'].map(start => ({ start, expected: compute(start) }));
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
process.stdout.write(JSON.stringify({ analyticsSha256: hash(analytics), modelsSha256: hash(models), timezone: 'UTC', records, fixtures }, null, 2) + '\n');
