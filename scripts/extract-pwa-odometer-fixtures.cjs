// Executes the original PWA projection read-only; writes fixtures to stdout.
const fs = require('node:fs');
const crypto = require('node:crypto');
const path = require('node:path');
const vm = require('node:vm');
const ts = require(process.env.CARLOG_TYPESCRIPT_PATH || '/usr/share/code/resources/app/extensions/node_modules/typescript/lib/typescript.js');
const root = process.env.CARLOG_PWA_ROOT || path.resolve(__dirname, '../../carlog');
const source = fs.readFileSync(path.join(root, 'src/db/maintenance.ts'), 'utf8');
const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText;
const pwa = {};
vm.runInNewContext(js, { exports: pwa, require: name => {
  if (name === './db') return { db: {} };
  throw new Error(`Unexpected import: ${name}`);
} });
const cases = [
  { name: 'empty', initial: 500, now: '2025-02-01T00:00:00Z', readings: [] },
  { name: 'short history', initial: 0, now: '2025-02-01T00:00:00Z', readings: [
    { date: '2025-01-01T00:00:00Z', odometer: 100 }, { date: '2025-01-14T00:00:00Z', odometer: 300 } ] },
  { name: 'fourteen days', initial: 0, now: '2025-01-16T00:00:00Z', readings: [
    { date: '2025-01-01T00:00:00Z', odometer: 100 }, { date: '2025-01-15T00:00:00Z', odometer: 240 } ] },
  { name: 'backfill and deleted', initial: 0, now: '2025-02-01T00:00:00Z', readings: [
    { date: '2025-01-20T00:00:00Z', odometer: 250 }, { date: '2025-01-01T00:00:00Z', odometer: 300 },
    { date: '2025-01-10T00:00:00Z', odometer: 900, deletedAt: '2025-01-11T00:00:00Z' } ] },
  { name: 'daily cap and large value', initial: 0, now: '2025-01-16T00:00:00Z', readings: [
    { date: '2025-01-01T00:00:00Z', odometer: 3000000000 }, { date: '2025-01-15T00:00:00Z', odometer: 3000004200 } ] },
];
const fixtures = cases.map(input => ({ ...input, expected: pwa.estimateCurrentOdometer(input.readings, { initialOdometer: input.initial }, Date.parse(input.now)) }));
process.stdout.write(JSON.stringify({ source: 'src/db/maintenance.ts', sourceSha256: crypto.createHash('sha256').update(source).digest('hex'), fixtures }, null, 2) + '\n');
