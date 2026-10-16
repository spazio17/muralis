/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Calendar;
import java.util.Date;

/**
 * This panel's identity in a fleet: an EC P-256 key pair made once, at the first start that needs
 * it, and a self-signed certificate over it.
 *
 * <p>Separate from the web admin's key on purpose: a person may upload a certificate for the web
 * admin, and that must never change who this panel is to the other panels. The identity is the
 * SHA-256 of the public key ({@link FleetTls#spkiOf}), not of the certificate, so a certificate
 * made again over the same key changes nothing; that is how Syncthing and KDE Connect let a device
 * be recognised by its key.
 *
 * <p>Kept like the web admin's own key: PKCS#8 and DER in {@link SecretStore}, in device-protected
 * storage, made in software. A key that cannot be read right now (a Keystore that waits for the
 * first unlock) is not replaced by a new one, which would end every membership: the member
 * endpoint simply waits for the unlock, where {@code KioskService} starts it again.
 */
final class FleetIdentity {
    private static final String TAG = "MuralisFleet";
    private static final String KEY_NAME = "fleet_key";
    private static final String CERTIFICATE_NAME = "fleet_certificate";
    private static final int VALID_YEARS = 10;

    final PrivateKey key;
    final X509Certificate certificate;
    /** SHA-256 of the public key, lowercase hex: what every other panel knows this one by. */
    final String spki;

    private FleetIdentity(PrivateKey key, X509Certificate certificate) {
        this.key = key;
        this.certificate = certificate;
        this.spki = FleetTls.spkiOf(certificate);
    }

    /** This panel's identity, made on the first call; null when it cannot be read right now. */
    static FleetIdentity load(Context context) {
        try {
            SecretStore secrets = new SecretStore(KioskConfig.storageContext(context));
            String storedKey = secrets.getOrNull(KEY_NAME);
            String storedCertificate = secrets.getOrNull(CERTIFICATE_NAME);
            if (storedKey == null || storedCertificate == null) {
                Log.w(TAG, "The fleet key cannot be read right now; the member endpoint waits");
                return null;
            }
            if (storedKey.isEmpty() || storedCertificate.isEmpty()) {
                // As the web admin's key: before the first unlock a Keystore may refuse the write,
                // and a key served but not kept would change at the next start. The unlock starts
                // the endpoint again.
                android.os.UserManager users = context.getSystemService(android.os.UserManager.class);
                if (users != null && !users.isUserUnlocked()) {
                    Log.i(TAG, "No fleet key before the first unlock; the member endpoint waits");
                    return null;
                }
                KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
                generator.initialize(new ECGenParameterSpec("secp256r1"));
                KeyPair pair = generator.generateKeyPair();
                X509Certificate certificate = issue(pair, KioskConfig.load(context).deviceId);
                secrets.put(KEY_NAME,
                        Base64.encodeToString(pair.getPrivate().getEncoded(), Base64.NO_WRAP));
                secrets.put(CERTIFICATE_NAME,
                        Base64.encodeToString(certificate.getEncoded(), Base64.NO_WRAP));
                if (!secrets.has(KEY_NAME) || !secrets.has(CERTIFICATE_NAME)) {
                    Log.w(TAG, "The fleet key could not be kept; a new one is made at the next start");
                }
                FleetIdentity made = new FleetIdentity(pair.getPrivate(), certificate);
                Log.i(TAG, "Created this panel's fleet key " + FleetTls.shortForm(made.spki));
                return made;
            }
            PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(
                    Base64.decode(storedKey, Base64.NO_WRAP)));
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(
                            Base64.decode(storedCertificate, Base64.NO_WRAP)));
            return new FleetIdentity(key, certificate);
        } catch (GeneralSecurityException | RuntimeException failure) {
            Log.e(TAG, "This panel's fleet key cannot be used", failure);
            return null;
        }
    }

    /**
     * Ten years, as the web admin's own. Nothing checks the dates: the key is what is pinned, and
     * {@link FleetTls} judges a peer by it alone.
     */
    private static X509Certificate issue(KeyPair pair, String deviceId)
            throws GeneralSecurityException {
        Calendar notBefore = Calendar.getInstance();
        notBefore.add(Calendar.DAY_OF_MONTH, -1);
        Calendar notAfter = Calendar.getInstance();
        notAfter.add(Calendar.YEAR, VALID_YEARS);
        return SelfSignedCertificate.issue(pair, "Muralis fleet " + deviceId,
                new Date(notBefore.getTimeInMillis()), new Date(notAfter.getTimeInMillis()),
                BigInteger.valueOf(System.currentTimeMillis()));
    }
}
