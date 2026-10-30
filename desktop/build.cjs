const { spawnSync } = require('node:child_process');
const path = require('node:path');
const root = path.resolve(__dirname, '../webos');
require('node:fs').copyFileSync(path.join(root, 'src/services/subscriptionReport.mjs'), path.join(__dirname, 'subscription-report.mjs'));
for (const [tool, args] of [['typescript/bin/tsc', ['-b']], ['vite/bin/vite.js', ['build']]]) {
  const result = spawnSync(process.execPath, [path.join(root, 'node_modules', tool), ...args], {
    cwd: root, stdio: 'inherit', env: { ...process.env, MEGASTREAM_DESKTOP: '1' }
  });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
}
