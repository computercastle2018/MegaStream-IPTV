const fs = require('node:fs');
function encryptedStore(file, encryption) {
  function read() {
    if (!encryption.isEncryptionAvailable()) throw new Error('System encryption unavailable');
    return fs.existsSync(file) ? JSON.parse(encryption.decryptString(fs.readFileSync(file))) : {};
  }
  return {
    get: key => read()[key] ?? null,
    set(key, value) {
      const data = read();
      if (value === null) delete data[key]; else data[key] = value;
      fs.writeFileSync(file + '.tmp', encryption.encryptString(JSON.stringify(data)), { mode: 0o600 });
      fs.renameSync(file + '.tmp', file);
    }
  };
}
module.exports = { encryptedStore };
