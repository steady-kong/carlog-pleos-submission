// Executes the existing PWA calculation read-only; writes fixtures to stdout.
const fs = require('node:fs');
const crypto = require('node:crypto');
const vm = require('node:vm');
const path = require('node:path');
const ts = require(process.env.CARLOG_TYPESCRIPT_PATH || '/usr/share/code/resources/app/extensions/node_modules/typescript/lib/typescript.js');
const root = process.env.CARLOG_PWA_ROOT || path.resolve(__dirname, '../../carlog');
const source = fs.readFileSync(path.join(root, 'src/db/analytics.ts'), 'utf8');
const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText;
const pwa = {};
vm.runInNewContext(js, { exports: pwa, require: name => {
  if (name === './db') return { db: {} };
  if (name === '@/types/models') return {};
  throw new Error(`Unexpected import: ${name}`);
} });
const cases = [
  { name: 'empty', fuels: [] },
  { name: 'partial only', fuels: [
    { date: '2025-01-01T00:00:00.000Z', odometer: 100, amount: 10, isFullTank: true },
    { date: '2025-01-02T00:00:00.000Z', odometer: 300, amount: 20, isFullTank: false },
  ] },
  { name: 'backfilled weighted and deleted', fuels: [
    { date: '2025-01-04T00:00:00.000Z', odometer: 700, amount: 10, isFullTank: false },
    { date: '2025-01-01T00:00:00.000Z', odometer: 100, amount: 20, isFullTank: true },
    { date: '2025-01-02T00:00:00.000Z', odometer: 300, amount: 10, isFullTank: true },
    { date: '2025-01-03T00:00:00.000Z', odometer: 600, amount: 30, isFullTank: true },
    { date: '2025-01-05T00:00:00.000Z', odometer: 900, amount: 10, isFullTank: true, deletedAt: '2025-01-06T00:00:00.000Z' },
  ] },
  { name: 'equal distance and zero fuel', fuels: [
    { date: '2025-01-01T00:00:00.000Z', odometer: 10, amount: 10, isFullTank: true },
    { date: '2025-01-02T00:00:00.000Z', odometer: 10, amount: 20, isFullTank: true },
    { date: '2025-01-03T00:00:00.000Z', odometer: 20, amount: 0, isFullTank: true },
  ] },
  { name: 'partial fills keep last trusted result', fuels: [
    { date: '2025-01-01T00:00:00.000Z', odometer: 100, amount: 10, isFullTank: true },
    { date: '2025-01-02T00:00:00.000Z', odometer: 400, amount: 30, isFullTank: true },
    { date: '2025-01-03T00:00:00.000Z', odometer: 500, amount: 5, isFullTank: false },
    { date: '2025-01-04T00:00:00.000Z', odometer: 600, amount: 10, isFullTank: true },
  ] },
  { name: 'same odometer ordered by date', fuels: [
    { date: '2025-01-03T00:00:00.000Z', odometer: 200, amount: 10, isFullTank: false },
    { date: '2025-01-01T00:00:00.000Z', odometer: 100, amount: 10, isFullTank: true },
    { date: '2025-01-02T00:00:00.000Z', odometer: 200, amount: 10, isFullTank: true },
    { date: '2025-01-04T00:00:00.000Z', odometer: 300, amount: 10, isFullTank: true },
  ] },
  { name: 'odometer order differs from chronological order', fuels: [
    { date: '2025-01-01T00:00:00.000Z', odometer: 900, amount: 10, isFullTank: true },
    { date: '2025-01-02T00:00:00.000Z', odometer: 100, amount: 20, isFullTank: true },
    { date: '2025-01-03T00:00:00.000Z', odometer: 300, amount: 20, isFullTank: true },
  ] },
];
const fixtures = cases.map(input => ({ ...input, expected: pwa.computeFuelStats(input.fuels) }));
const sourceSha256 = crypto.createHash('sha256').update(source).digest('hex');
process.stdout.write(JSON.stringify({ source: 'src/db/analytics.ts', sourceSha256, fixtures }, null, 2) + '\n');
