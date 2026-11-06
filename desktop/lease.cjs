const crypto = require('node:crypto');
function verifyLease(lease, jwk, identity, now = Date.now()) {
  try {
    const parts = lease.split('.');
    if (parts.length !== 3) return null;
    const header = JSON.parse(Buffer.from(parts[0], 'base64url'));
    const claims = JSON.parse(Buffer.from(parts[1], 'base64url'));
    const seconds = Math.floor(now / 1000);
    const binding = crypto.createHash('sha256').update(Buffer.from(identity.credential, 'base64url')).digest('base64url');
    if (header.alg !== 'ES256' || header.kid !== jwk.kid || jwk.kty !== 'EC' || jwk.crv !== 'P-256' ||
        claims.iss !== 'https://megastrem.megastation.uk' || claims.aud !== 'megastream-desktop' ||
        claims.sub !== identity.installationId || claims.credentialBinding !== binding || claims.decision !== 'allowed' ||
        !['iat', 'nbf', 'exp', 'licenseStartsAt', 'licenseEndsAt'].every(key => Number.isSafeInteger(claims[key])) ||
        claims.nbf > seconds || claims.iat > seconds + 60 || claims.exp <= seconds || claims.licenseStartsAt > seconds ||
        claims.licenseEndsAt <= seconds || claims.exp - claims.iat > 3 * 86400) return null;
    const valid = crypto.verify('sha256', Buffer.from(parts[0] + '.' + parts[1]), {
      key: crypto.createPublicKey({ key: jwk, format: 'jwk' }), dsaEncoding: 'ieee-p1363'
    }, Buffer.from(parts[2], 'base64url'));
    return valid ? claims : null;
  } catch { return null; }
}
module.exports = { verifyLease };
