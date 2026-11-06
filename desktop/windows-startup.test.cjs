const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

for (const platform of ['win32', 'darwin']) {
  test(`${platform} configures compatible rendering before Electron initialization without weakening web security`, () => {
    const calls = [];
    const electron = {
      app: {
        disableHardwareAcceleration: () => calls.push('software-rendering'),
        requestSingleInstanceLock: () => { calls.push('instance-lock'); return false; },
        quit: () => calls.push('quit'),
      },
      protocol: { registerSchemesAsPrivileged: schemes => {
        calls.push('scheme-registration');
        assert.equal(schemes[0].privileges.bypassCSP, undefined);
      } },
    };
    vm.runInNewContext(fs.readFileSync(require.resolve('./main.cjs'), 'utf8'), {
      require: name => name === 'electron' ? electron : require(name),
      process: { platform }, __dirname,
    });
    assert.deepEqual(calls, platform === 'win32'
      ? ['software-rendering', 'scheme-registration', 'instance-lock', 'quit']
      : ['scheme-registration', 'instance-lock', 'quit']);
  });
}
