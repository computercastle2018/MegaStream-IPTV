package com.MegaStream.app.controlplane.integration

import com.MegaStream.app.BuildConfig
import com.MegaStream.data.licensing.OfflineLeaseVerifier
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64

/** APK-pinned public key only. Network JWKS never enters this trust boundary. */
internal object RuntimeLeasePins {
    fun verifier(): OfflineLeaseVerifier {
        val kid = BuildConfig.MEGASTREAM_LEASE_KID
        require(kid.isNotBlank() && kid.length <= 128)
        val x = coordinate(BuildConfig.MEGASTREAM_LEASE_X)
        val y = coordinate(BuildConfig.MEGASTREAM_LEASE_Y)
        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        val curve = parameters.curve
        val p = (curve.field as ECFieldFp).p
        require(x < p && y < p)
        require(y.multiply(y).mod(p) == x.pow(3).add(curve.a.multiply(x)).add(curve.b).mod(p))
        val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), parameters))
        return OfflineLeaseVerifier(mapOf(kid to key))
    }

    private fun coordinate(value: String): BigInteger {
        require(Regex("[A-Za-z0-9_-]{43}").matches(value))
        val bytes = Base64.getUrlDecoder().decode(value)
        require(bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value)
        return BigInteger(1, bytes)
    }
}
