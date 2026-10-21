/*
 * Copyright 2026 Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

/**
 * Host tests for {@link CertificateUpload}: every shape a certificate and key arrive in from
 * certbot, acme.sh and openssl, and every refusal. The fixtures were made once with OpenSSL 3.5
 * (a root, an intermediate, an EC P-256 leaf for panel.test and two addresses, an RSA 2048 leaf,
 * the keys in PKCS#8, SEC1 and PKCS#1, two encrypted, one that matches nothing, an Ed25519
 * certificate and a certificate request; on 2026-10-05 a client certificate, one whose key usage
 * is key agreement alone, openssl req -x509 as it comes and a self-signed server certificate;
 * then one whose common name is markup and quotes, one with sixty-one names, and one whose
 * name is longer than any DNS name);
 * the leaves are valid until 2036.
 */
public final class CertificateUploadTest {

    public static void main(String[] args) throws Exception {
        // certbot: fullchain.pem (the root pasted on too) and privkey.pem
        CertificateUpload.Result full = CertificateUpload.parse(
                EC_LEAF + "\n" + INTERMEDIATE + "\n" + ROOT, EC_PKCS8);
        check("EC, PKCS#8, full chain: accepted", full.problem == null);
        check("the root is left out", full.upload.chain.length == 2);
        check("the server's certificate first",
                CertificateUpload.names(full.upload.chain[0]).equals(
                        "panel.test, 10.0.14.51, 10.0.14.52"));
        check("the key belongs to it",
                CertificateUpload.belongsTo(full.upload.key, full.upload.chain[0]));

        CertificateUpload restored = CertificateUpload.restore(full.upload.chainText(),
                full.upload.keyText());
        check("kept and read back", restored != null && restored.chain.length == 2
                && restored.chain[0].equals(full.upload.chain[0])
                && restored.chain[1].equals(full.upload.chain[1]));
        CertificateUpload other = CertificateUpload.parse(RSA_LEAF, RSA_PKCS8).upload;
        check("read back with another key: refused",
                CertificateUpload.restore(full.upload.chainText(), other.keyText()) == null);
        check("nothing kept: nothing read back", CertificateUpload.restore("", "") == null
                && CertificateUpload.restore(null, full.upload.keyText()) == null);

        check("EC, SEC1 (acme.sh, openssl ec): accepted",
                CertificateUpload.parse(EC_LEAF, EC_SEC1).problem == null);
        check("RSA, PKCS#8: accepted", CertificateUpload.parse(RSA_LEAF, RSA_PKCS8).problem == null);
        CertificateUpload.Result asClient = CertificateUpload.parseClient(RSA_LEAF, RSA_PKCS8);
        check("a server certificate as MQTT's client certificate: refused, or taken when its"
                + " usage allows any", asClient.problem == null
                        || asClient.problem.contains("clientAuth"));
        check("RSA, PKCS#1 (BEGIN RSA PRIVATE KEY): accepted",
                CertificateUpload.parse(RSA_LEAF + "\n" + INTERMEDIATE, RSA_PKCS1).problem == null);
        check("the RSA name is its DNS name",
                CertificateUpload.names(other.chain[0]).equals("rsa.panel.test"));
        check("one file holding both: accepted",
                CertificateUpload.parse(EC_LEAF + "\n" + EC_PKCS8, "").problem == null);
        check("the two files swapped: accepted",
                CertificateUpload.parse(EC_PKCS8, EC_LEAF).problem == null);
        check("Windows line ends: accepted", CertificateUpload.parse(
                EC_LEAF.replace("\n", "\r\n"), EC_SEC1.replace("\n", "\r\n")).problem == null);

        expect("nothing chosen", CertificateUpload.parse("", ""), "no certificate was chosen");
        expect("nothing chosen, null", CertificateUpload.parse(null, null),
                "no certificate was chosen");
        expect("a key alone", CertificateUpload.parse("", EC_PKCS8),
                "no certificate was found; a certificate file begins with -----BEGIN CERTIFICATE-----");
        expect("a text that is no PEM", CertificateUpload.parse("hello", "world"),
                "no certificate was found; a certificate file begins with -----BEGIN CERTIFICATE-----");
        expect("a certificate request", CertificateUpload.parse(EC_REQUEST, EC_PKCS8),
                "that is a certificate request; upload the certificate the authority sent back for it");
        expect("a certificate alone", CertificateUpload.parse(EC_LEAF, ""),
                "no private key was found; a key file begins with -----BEGIN PRIVATE KEY-----");
        expect("two keys", CertificateUpload.parse(EC_LEAF, EC_PKCS8 + "\n" + EC_SEC1),
                "more than one private key was given");
        String passphrase = "the private key is protected by a passphrase, and a panel has nowhere "
                + "to type one when it starts; save it without one with openssl pkey -in key.pem "
                + "-out key-open.pem";
        expect("an encrypted PKCS#8 key", CertificateUpload.parse(EC_LEAF, EC_ENCRYPTED),
                passphrase);
        expect("an encrypted traditional key", CertificateUpload.parse(RSA_LEAF, RSA_ENCRYPTED),
                passphrase);
        expect("a key of another certificate", CertificateUpload.parse(EC_LEAF, OTHER_EC),
                "the private key does not belong to the certificate");
        expect("an RSA key for an EC certificate", CertificateUpload.parse(EC_LEAF, RSA_PKCS1),
                "the private key does not belong to the certificate");
        expect("an EC key for an RSA certificate", CertificateUpload.parse(RSA_LEAF, EC_PKCS8),
                "the private key does not belong to the certificate");
        expect("a SEC1 key with no EC certificate", CertificateUpload.parse(RSA_LEAF, EC_SEC1),
                "the private key does not belong to the certificate");
        expect("the intermediate first", CertificateUpload.parse(
                INTERMEDIATE + "\n" + EC_LEAF, EC_PKCS8),
                "the private key belongs to another certificate in the file; the server's own "
                        + "certificate has to come first");
        // Nine different certificates: one repeated would count once.
        String[] distinct = {EC_LEAF, INTERMEDIATE, ROOT, RSA_LEAF, ED_LEAF, CLIENT_LEAF,
                KEY_AGREEMENT_LEAF, DIY_LEAF, SELF_SERVER_LEAF};
        check("nine to test with", distinct.length == CertificateUpload.MAX_CERTIFICATES + 1);
        String many = String.join("\n", distinct);
        expect("too many certificates", CertificateUpload.parse(many, EC_PKCS8),
                "more than " + CertificateUpload.MAX_CERTIFICATES + " certificates were given");
        expect("a broken certificate", CertificateUpload.parse(
                "-----BEGIN CERTIFICATE-----\nTUlJQ\n-----END CERTIFICATE-----", EC_PKCS8),
                "a certificate in the file cannot be read");
        expect("a broken key", CertificateUpload.parse(EC_LEAF,
                "-----BEGIN PRIVATE KEY-----\nTUlJQ\n-----END PRIVATE KEY-----"),
                "the private key cannot be read");
        CertificateUpload.Result edwards = CertificateUpload.parse(ED_LEAF, ED_KEY);
        check("an Ed25519 certificate: refused, its kind named", edwards.problem != null
                && edwards.problem.startsWith("the certificate's key is ")
                && edwards.problem.endsWith("; the web admin serves RSA and EC keys"));

        // The same file chosen in both fields: each block counts once.
        String both = EC_LEAF + "\n" + INTERMEDIATE + "\n" + EC_PKCS8;
        CertificateUpload.Result twice = CertificateUpload.parse(both, both);
        check("one file holding both, chosen twice: accepted", twice.problem == null
                && twice.upload.chain.length == 2);
        check("the full chain chosen twice: served once", CertificateUpload.parse(
                EC_LEAF + "\n" + INTERMEDIATE, EC_LEAF + "\n" + INTERMEDIATE + "\n" + EC_SEC1)
                .upload.chain.length == 2);
        expect("a client certificate", CertificateUpload.parse(CLIENT_LEAF, CLIENT_KEY),
                "the certificate is not one for a server (its extended key usage has no "
                        + "serverAuth), and no browser accepts it for one");
        expect("a key usage without digitalSignature",
                CertificateUpload.parse(KEY_AGREEMENT_LEAF, KEY_AGREEMENT_KEY),
                "the certificate's key usage has no digitalSignature, and no browser accepts it "
                        + "for a server");
        check("openssl req -x509 as it comes (CA:TRUE): accepted",
                CertificateUpload.parse(DIY_LEAF, DIY_KEY).problem == null);
        check("a self-signed server certificate (CA:FALSE): accepted",
                CertificateUpload.parse(SELF_SERVER_LEAF, SELF_SERVER_KEY).problem == null);
        // An authority's certificate signs certificates, not handshakes: its key usage says so.
        expect("an authority with its key", CertificateUpload.parse(INTERMEDIATE, INTERMEDIATE_KEY),
                "the certificate's key usage has no digitalSignature, and no browser accepts it "
                        + "for a server");

        // Names as a person reads them: escapes undone, control characters out, long lists cut.
        CertificateUpload.Result odd = CertificateUpload.parse(ODD_NAME_LEAF, ODD_NAME_KEY);
        check("a common name with markup reads as written",
                odd.problem == null && CertificateUpload.names(odd.upload.chain[0])
                        .equals("<img src=x onerror=alert(1)>\"'&amp;"));
        CertificateUpload.Result sixtyOne = CertificateUpload.parse(MANY_NAMES_LEAF, MANY_NAMES_KEY);
        check("sixty-one names: the first three and how many more",
                sixtyOne.problem == null && CertificateUpload.names(sixtyOne.upload.chain[0]).equals(
                        "host00.very-long-subdomain-name.example.home.lan, "
                                + "host01.very-long-subdomain-name.example.home.lan, "
                                + "host02.very-long-subdomain-name.example.home.lan, and 58 more"));
        check("RFC 2253: an escaped comma", "Panel, Hall".equals(
                CertificateUpload.commonName("CN=Panel\\, Hall,O=Home")));
        check("RFC 2253: hex escapes, UTF-8 included", "<è>".equals(
                CertificateUpload.commonName("CN=\\3C\\C3\\A8\\3E")));
        check("RFC 2253: the common name after another part, a plus kept",
                "a+b".equals(CertificateUpload.commonName("O=Home,CN=a\\+b")));
        check("RFC 2253: no common name, the whole name",
                "O=Home".equals(CertificateUpload.commonName("O=Home")));
        check("control characters out", "abc".equals(CertificateUpload.cleanName("a\nb\tc")));
        expect("a name longer than any DNS name", CertificateUpload.parse(LONG_NAME_LEAF,
                LONG_NAME_KEY), "a name in the certificate is longer than 253 characters, which "
                        + "no browser matches to an address");

        long day = 24L * 60 * 60 * 1000;
        long notAfter = full.upload.chain[0].getNotAfter().getTime();
        check("valid: until the day, held together", CertificateUpload.validity(notAfter,
                notAfter - day).matches("until\u00a0\\d{1,2}\u00a0[A-Z][a-z]{2}\u00a0\\d{4}"));
        check("past it: expired on the day", CertificateUpload.validity(notAfter, notAfter + day)
                .startsWith("expired on\u00a0") && CertificateUpload.expired(notAfter, notAfter + 1)
                && !CertificateUpload.expired(notAfter, notAfter));
        System.out.println("CertificateUpload host tests passed");
    }

    private static void expect(String name, CertificateUpload.Result result, String problem) {
        if (result.upload != null || !problem.equals(result.problem)) {
            throw new AssertionError(name + ": expected \"" + problem + "\", got \""
                    + result.problem + "\"");
        }
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            throw new AssertionError(name);
        }
    }

    private static final String EC_LEAF = """
-----BEGIN CERTIFICATE-----
MIIBxzCCAW2gAwIBAgIUQmiuFWDbOnwT3vxriYx0hVX9DUcwCgYIKoZIzj0EAwIw
JDEiMCAGA1UEAwwZTXVyYWxpcyBUZXN0IEludGVybWVkaWF0ZTAeFw0yNjEwMDQy
MTEyMzhaFw0zNjEwMDEyMTEyMzhaMBUxEzARBgNVBAMMCnBhbmVsLnRlc3QwWTAT
BgcqhkjOPQIBBggqhkjOPQMBBwNCAASsXtgfEcvn2BS4twR5Zr8EHRSwGbR/Qpm7
NrydBkQo93HqE24Pa2+DbHHBkyIt2p4T56RcMQg5Yx5RbQyHJTsWo4GLMIGIMCEG
A1UdEQQaMBiCCnBhbmVsLnRlc3SHBAoADjOHBAoADjQwEwYDVR0lBAwwCgYIKwYB
BQUHAwEwDgYDVR0PAQH/BAQDAgeAMB0GA1UdDgQWBBQfkL0tKW//S5MkxzsQSVLA
IUvZ2zAfBgNVHSMEGDAWgBT7V5ioEt00e0v3XTDLwy1ihRbIFDAKBggqhkjOPQQD
AgNIADBFAiAbi+a6N2MWXrCwUnjDLXeQ3R2YIRbEczorNW9bLGy97AIhAKY0eCBd
zAXFx5EmDpj9TPfNliHBHlSaFXSWfh0Iy2TF
-----END CERTIFICATE-----
""";

    private static final String INTERMEDIATE = """
-----BEGIN CERTIFICATE-----
MIIBqjCCAVCgAwIBAgIUfBdf+1uFBC2ZgPc5NVUBLDIHIP8wCgYIKoZIzj0EAwIw
HDEaMBgGA1UEAwwRTXVyYWxpcyBUZXN0IFJvb3QwIBcNMjYxMDA0MjExMjM4WhgP
MjEyNTA0MjgyMTEyMzhaMCQxIjAgBgNVBAMMGU11cmFsaXMgVGVzdCBJbnRlcm1l
ZGlhdGUwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAARnqJo1ceH07MDZCRjceFZi
T1eveXqRuWJzNmnaYPu6ftEJBHMGUwgiWC25Adj+rADxtIcbNQuCh8F4iSLPyVP4
o2YwZDASBgNVHRMBAf8ECDAGAQH/AgEAMA4GA1UdDwEB/wQEAwIBBjAdBgNVHQ4E
FgQU+1eYqBLdNHtL910wy8MtYoUWyBQwHwYDVR0jBBgwFoAUzmiOEqhI7lItcZH8
tfjL/J0PwHMwCgYIKoZIzj0EAwIDSAAwRQIgdf8fbn1Jk8aYp2PtOPMBdA0svQH7
4cQgYG3dtQ1GZF8CIQDnty3kul1HdsHOfbqmScyaPZuYCwc3K4A39n5rpz2yYA==
-----END CERTIFICATE-----
""";

    private static final String ROOT = """
-----BEGIN CERTIFICATE-----
MIIBnjCCAUWgAwIBAgIUeMVNmO2xwm1/pfEzDKnBMPyAy5owCgYIKoZIzj0EAwIw
HDEaMBgGA1UEAwwRTXVyYWxpcyBUZXN0IFJvb3QwIBcNMjYxMDA0MjExMjM4WhgP
MjEyNjA5MTAyMTEyMzhaMBwxGjAYBgNVBAMMEU11cmFsaXMgVGVzdCBSb290MFkw
EwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEhvJ/uPEk5BPdv0IWX1leXa7ZqQ/W0kxF
fTI+azlTS0VzOfniXKtQdIHVgT4czO8QXyAPV1PehRD9nSxZpN1JL6NjMGEwHQYD
VR0OBBYEFM5ojhKoSO5SLXGR/LX4y/ydD8BzMB8GA1UdIwQYMBaAFM5ojhKoSO5S
LXGR/LX4y/ydD8BzMA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0PAQH/BAQDAgEGMAoG
CCqGSM49BAMCA0cAMEQCIDipJ4PKfIfWB55ZRCn35Ewf2zid/RYo5JFv31l140Dg
AiBUd6AjuJN+ZqDVgSm+Yn5GATLDM+QbBbgP06cCm04guQ==
-----END CERTIFICATE-----
""";

    private static final String RSA_LEAF = """
-----BEGIN CERTIFICATE-----
MIICezCCAiKgAwIBAgIUQmiuFWDbOnwT3vxriYx0hVX9DUgwCgYIKoZIzj0EAwIw
JDEiMCAGA1UEAwwZTXVyYWxpcyBUZXN0IEludGVybWVkaWF0ZTAeFw0yNjEwMDQy
MTEyMzhaFw0zNjEwMDEyMTEyMzhaMBkxFzAVBgNVBAMMDnJzYS5wYW5lbC50ZXN0
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA2uhnzZPS9RCA1RjNxtbl
usc6sRQiRoMomosHMOHMV8vt9wfDQ3DHT14izoN0CrljBomkaSMa8GmOXYiUWqHY
++rf+bbZHDrtVnjWp5rkXRl8m0sKApzkjr6H5xewOHE4DUJ3tQraeVev8uF3C7Yi
SKkDziOEMnurAzVMIq/w/HXP9l75y4IC9OsnvoqJQq7MtvHhexW17VqpgO7211Z4
qEOKrAaMPWuKOsUTfTKQQnScbriQeOWXAeCKQI2uJmf1HvnsVgwJJJJCsJHq7BEC
ydiDNms1yq1WFqSSzw8UnF+Izlu091jvHdeYRZcNu9+HEBaHSYxi6SOdtbrXdNV6
XwIDAQABo3IwcDAZBgNVHREEEjAQgg5yc2EucGFuZWwudGVzdDATBgNVHSUEDDAK
BggrBgEFBQcDATAdBgNVHQ4EFgQUkAqUZCmtzG3xocCOnPyfsZ9LuUowHwYDVR0j
BBgwFoAU+1eYqBLdNHtL910wy8MtYoUWyBQwCgYIKoZIzj0EAwIDRwAwRAIgBu4N
tsaAHL3L276sfHPfd/K7GFssPV8F0uUsfsbQOy4CID3P+BAR/eawLWpAAVB4iaYL
40ykhOAoMSn6yxLPWI2z
-----END CERTIFICATE-----
""";

    private static final String EC_PKCS8 = """
-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgshHMFRZxciEBUEce
V8tpcmij6aJvbr9Srtl9PSBZX7OhRANCAASsXtgfEcvn2BS4twR5Zr8EHRSwGbR/
Qpm7NrydBkQo93HqE24Pa2+DbHHBkyIt2p4T56RcMQg5Yx5RbQyHJTsW
-----END PRIVATE KEY-----
""";

    private static final String EC_SEC1 = """
-----BEGIN EC PRIVATE KEY-----
MHcCAQEEILIRzBUWcXIhAVBHHlfLaXJoo+mib26/Uq7ZfT0gWV+zoAoGCCqGSM49
AwEHoUQDQgAErF7YHxHL59gUuLcEeWa/BB0UsBm0f0KZuza8nQZEKPdx6hNuD2tv
g2xxwZMiLdqeE+ekXDEIOWMeUW0MhyU7Fg==
-----END EC PRIVATE KEY-----
""";

    private static final String RSA_PKCS8 = """
-----BEGIN PRIVATE KEY-----
MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDa6GfNk9L1EIDV
GM3G1uW6xzqxFCJGgyiaiwcw4cxXy+33B8NDcMdPXiLOg3QKuWMGiaRpIxrwaY5d
iJRaodj76t/5ttkcOu1WeNanmuRdGXybSwoCnOSOvofnF7A4cTgNQne1Ctp5V6/y
4XcLtiJIqQPOI4Qye6sDNUwir/D8dc/2XvnLggL06ye+iolCrsy28eF7FbXtWqmA
7vbXVnioQ4qsBow9a4o6xRN9MpBCdJxuuJB45ZcB4IpAja4mZ/Ue+exWDAkkkkKw
kersEQLJ2IM2azXKrVYWpJLPDxScX4jOW7T3WO8d15hFlw2734cQFodJjGLpI521
utd01XpfAgMBAAECggEAFAolgojPTZbqrFdecx72HeCiK1bEAn98RpWGnX92NO1a
xfwEcJaQ8F+WQSCAjYDGI3GTRvqrOyn2mfJoXlmlov4v7czC2FY+puVeBS7lS+6K
3S6D5pRVSgoAJ/QfsjMPJguZKM4rDOOtnEbunnhYcB1k10iiD/sJPzxwUVaY8Ytu
HAtV2fzFf58K3beILmM4y+oV/WYQQIGpJvNDF5uy7pZ8OvXMHM+E1a5a8RXEW69Y
yfnJsOUJbRsKjqoOK0ARyVzO3B0BSkN/zIodoQvDcgRAnCvzmlTsrlhp1y1socep
lLaaQ7A1oGZ9qT6uqMWM+UjUyJVAp36V9ITBNyfuoQKBgQDtE1G+uh4VXypnNgSR
ryaMZbf5nbPvGBB3SAVKPfzJtgBVW8eKZSH+WR9b8V9m3sordsRvywA8mIzMViPD
gn58J1YSs6Xs1sBIPhD/RM4wDEUOEMIwmJyvGOGj44wIMFzmTS7/IU7LAqTbRYji
/x8jNv/zuk4pYQ507OmADP2KoQKBgQDsYdPHnCCYVZ4rHMkMjVlkN+2CPw1DCw7b
QXNTmC+Q79ruE+46uDyBJC7149LCPHFvWsvBJ3mY3+NBllPHIChS/mY0GT2/p8yk
UNcmXFYyI6J+Uw8IeBXxYBHSlUObJSTRz1E8qV1w81xL5Iorswj7og1K8ECVMrB1
sWztrDHk/wKBgQCQDpA6k/+ZvvUWOEW4fLSw1VJglIp0u6utv46vWVFVBInZVGbA
2sk4M9GRXe85pCE3HfkpcTIYoB3WQHWE5LOVH5X20UD+1FOM0mIjqxybJ6mCbX6C
Zwm2gCkEHE3H4BNjQf1PvF6Tj2y7bNpMDPhPvqjy4f+Ct15AgIE2dS5PYQKBgQCr
6OGKM8OIxguHsyGMHWg7eGkk5hIRBgPB9TGYQ/+j2qxPpPMGhWKLUPNOJGfsluSw
ZUn1qW3HflvaISfAk2SAJz/Q9+JudexgO+KcpV6SjFPqpyExuZPLvWB409EdDcBf
TfT4tvub34DqOMU7zrLjvhEIxP1TBFBM58Ief7KxpQKBgCHESDksDs3f1mkT3ueU
TgzmvBR7TQe3bwHWQlLRbR7yZJHfOOFmwCeB74yeJyWFANZuwOF5m647AxAVVxYr
7jXkO2KJu4GaN774tEkt/TkG3S7ZXLCfmjEITSm11VtNH4/hxjIhe58gP9R+Q4Eo
1zmHOr3o00nXrUtEHbrzKluA
-----END PRIVATE KEY-----
""";

    private static final String RSA_PKCS1 = """
-----BEGIN RSA PRIVATE KEY-----
MIIEpAIBAAKCAQEA2uhnzZPS9RCA1RjNxtblusc6sRQiRoMomosHMOHMV8vt9wfD
Q3DHT14izoN0CrljBomkaSMa8GmOXYiUWqHY++rf+bbZHDrtVnjWp5rkXRl8m0sK
Apzkjr6H5xewOHE4DUJ3tQraeVev8uF3C7YiSKkDziOEMnurAzVMIq/w/HXP9l75
y4IC9OsnvoqJQq7MtvHhexW17VqpgO7211Z4qEOKrAaMPWuKOsUTfTKQQnScbriQ
eOWXAeCKQI2uJmf1HvnsVgwJJJJCsJHq7BECydiDNms1yq1WFqSSzw8UnF+Izlu0
91jvHdeYRZcNu9+HEBaHSYxi6SOdtbrXdNV6XwIDAQABAoIBABQKJYKIz02W6qxX
XnMe9h3goitWxAJ/fEaVhp1/djTtWsX8BHCWkPBflkEggI2AxiNxk0b6qzsp9pny
aF5ZpaL+L+3MwthWPqblXgUu5Uvuit0ug+aUVUoKACf0H7IzDyYLmSjOKwzjrZxG
7p54WHAdZNdIog/7CT88cFFWmPGLbhwLVdn8xX+fCt23iC5jOMvqFf1mEECBqSbz
Qxebsu6WfDr1zBzPhNWuWvEVxFuvWMn5ybDlCW0bCo6qDitAEclcztwdAUpDf8yK
HaELw3IEQJwr85pU7K5YadctbKHHqZS2mkOwNaBmfak+rqjFjPlI1MiVQKd+lfSE
wTcn7qECgYEA7RNRvroeFV8qZzYEka8mjGW3+Z2z7xgQd0gFSj38ybYAVVvHimUh
/lkfW/FfZt7KK3bEb8sAPJiMzFYjw4J+fCdWErOl7NbASD4Q/0TOMAxFDhDCMJic
rxjho+OMCDBc5k0u/yFOywKk20WI4v8fIzb/87pOKWEOdOzpgAz9iqECgYEA7GHT
x5wgmFWeKxzJDI1ZZDftgj8NQwsO20FzU5gvkO/a7hPuOrg8gSQu9ePSwjxxb1rL
wSd5mN/jQZZTxyAoUv5mNBk9v6fMpFDXJlxWMiOiflMPCHgV8WAR0pVDmyUk0c9R
PKldcPNcS+SKK7MI+6INSvBAlTKwdbFs7awx5P8CgYEAkA6QOpP/mb71FjhFuHy0
sNVSYJSKdLurrb+Or1lRVQSJ2VRmwNrJODPRkV3vOaQhNx35KXEyGKAd1kB1hOSz
lR+V9tFA/tRTjNJiI6scmyepgm1+gmcJtoApBBxNx+ATY0H9T7xek49su2zaTAz4
T76o8uH/grdeQICBNnUuT2ECgYEAq+jhijPDiMYLh7MhjB1oO3hpJOYSEQYDwfUx
mEP/o9qsT6TzBoVii1DzTiRn7JbksGVJ9altx35b2iEnwJNkgCc/0PfibnXsYDvi
nKVekoxT6qchMbmTy71geNPRHQ3AX030+Lb7m9+A6jjFO86y474RCMT9UwRQTOfC
Hn+ysaUCgYAhxEg5LA7N39ZpE97nlE4M5rwUe00Ht28B1kJS0W0e8mSR3zjhZsAn
ge+MniclhQDWbsDheZuuOwMQFVcWK+415DtiibuBmje++LRJLf05Bt0u2Vywn5ox
CE0ptdVbTR+P4cYyIXufID/UfkOBKNc5hzq96NNJ161LRB268ypbgA==
-----END RSA PRIVATE KEY-----
""";

    private static final String EC_ENCRYPTED = """
-----BEGIN ENCRYPTED PRIVATE KEY-----
MIH0MF8GCSqGSIb3DQEFDTBSMDEGCSqGSIb3DQEFDDAkBBBhtLIe9UnI2cGMev16
Eh8sAgIIADAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQJp2WT58yHWFRZDCv
UpP5tQSBkJK8WQNyg6Ab3oN9GptAPqnu4u14lcbtQDPuhgMi9FmF9kM2TzawrAPA
5qQHA4nEh8aLAtgHfBMf8yCPxmE6M2jgINAm43AOH330wDGrS/TtVo+1i0kLajl/
bOhlKWYlsjLW3t21qRTy8B4HiF6JbqDf2CKIGFfHjw0OtQl3+7z8qibYldBqsY94
/V4APwKUWw==
-----END ENCRYPTED PRIVATE KEY-----
""";

    private static final String RSA_ENCRYPTED = """
-----BEGIN RSA PRIVATE KEY-----
Proc-Type: 4,ENCRYPTED
DEK-Info: AES-128-CBC,00A809ADDFDC5AD6167B142E4528AF11

Xq6q3oz7jedXX8gIGHaP86I2Yw+0QQ7cwVsxV+1/LZ50Ug7r80KNwU7wAijM01QU
eyI/oJpDZyXLvzVX/WvLGEHZFquU3XryfMGbYD4N8LVc/maEI8mKzhki13+oZmV5
aWEj2MNszVtCtO+jwKLiNLaOeE/kbIpauXxWA3eiH/bJKmBfXMsyV7x+DQi8hubK
L5TMbKo0CrokUcaAVFNgqqteQoqJgiN+UPwq6y2PuxXtcdff1JuPXXtefRkTbzNA
cNxQL98mFTR36ycS+2rEXFl/NlHhR9VS9STmpSBOpqhcXjgH6nKIkgn7kvhKXhMs
nBfv0ZkBYNgDij3VNYLAI82XqmphWL4E0QgRFK9DvkSLgpq5IdT8XygmXyaI5i6K
q4ztrQVks/537F7Atfn0PlLCwWqXJ5lPn0ph6f+dxlFYfmgusfUH413sDZTTs4mt
fZxgmdW7fPBdh+NKZbnaWy9/NQGxLZDnHFUHKkeWHRH0a/2wc1Bb9uCdgZf9bSqK
H/Jyo/J/umQmUF9cvxr2dmp7/yMymwlNgwiKIG+iYT5Ug9FFU7j0vg/X/br60Uo3
CCQzNUC6pfV3hvdFPcPYQDdaLvrWG4XYNq4XwdJiHsHTuHXzjATGCXZN6At3p0Ms
6Q0AXzQE7h6W3JsCA/kQepPJJC+fosjYT01nSKxSvekF5SZvBnTx2ihekBHXsgSm
hecfFDMVyhwM8VJcNUUBVnw1G+Xw0vr0dqWUw4mPc5kAx/X0NQWgEliAFaqusYOk
GtUx54AUBMhT4B26BAtqvwn9i6Y9beCs97LGS+UiO86/vIFEl+J1d8fvQMgYM3Nv
faNOs567rZdT3YrtYTvVvB/xqjhehA89KOS5++4y6T14MjKTDpbqnsuBFd+763Z0
ErTVjUo0O30qMEDyZjWE3MdYWvADL4Ayq35oy4gK4s8TsGZzV2TLnl8bD0auSKvR
TFTWlr+y2G7a4TFA+vd5nXqIFEEQYaZ1I6vryOhCQew+z8jfuXsIK5I6BXiAVtlP
lFnv+v/0SNZbiNL1RPjNqrA92V1cI/rB1PxhvoECvUJnUGFnyKsOP8HcVsN5wshZ
cAYNTtIVycMWIsyHn6XZK3vXZow0WOHrRC4CT8HaCfZSHXnXE19C2kfUhzHpTl+u
EhoTbn/9iJdPERHzB9LIq7dyqh1zl1KvvpLhOv34ZPwBSyAq3uJ9buwMUAXJfa6l
Q2FW7UNg03wEF6frIurRX8EDxilXT3Ya9wMm2qHp+tkMcYjTDUR8NDYnJaxagylc
xE2GgIJWNa0S4JNqK0OY/Q6mcbpaWjsUsaCtmOUgBOUOr51D//daa7/qvNUjRTzj
RsepbfxXNtfDt8IbDkhjS28X2C3JNLf782EyEN5Ffl891y9ctHaUWuRjq3SqVRKd
WGDLxPhjNeq+ETQHcvKSFGYs1ZUVFJmWLyyqC0Fe8SlNfwtmF9dRQQ3iCeDb7pTb
dRK+SklRT3SLid73dNSYYMWmgy0tnh0O8+5DWu3hcWFsPtS5MEF1WSykII9piSq8
DwtwgMlsXSgt8YtaGD4a2G0J87NqtsSiXUExkQPrGB3u6meqA+Nj0AlubzeNTD4G
-----END RSA PRIVATE KEY-----
""";

    private static final String OTHER_EC = """
-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg5OuWMHBOnBzyVExY
6zIfEuk34js5PHQmFBu121lRA1uhRANCAASjAqxpkO/Uzom7B+0kiaNSxVKDKqVj
4nu48LfmrVRvmW9yHTmTmEvtaTXAZeViXhoPsqUYov0p4y+/ZzWr1HTM
-----END PRIVATE KEY-----
""";

    private static final String ED_LEAF = """
-----BEGIN CERTIFICATE-----
MIIBRDCB96ADAgECAhRKzQKmlShzuR7saO2ustiMJFs9yjAFBgMrZXAwGDEWMBQG
A1UEAwwNZWQucGFuZWwudGVzdDAeFw0yNjEwMDQyMTEyMzhaFw0zNjEwMDEyMTEy
MzhaMBgxFjAUBgNVBAMMDWVkLnBhbmVsLnRlc3QwKjAFBgMrZXADIQBcy1n/WKY/
Yh0nywAry5jOOi/mXYzJNWcBw16XLICdQ6NTMFEwHQYDVR0OBBYEFJe8sDx/a66i
ZpTSf8fu9gyyl7/2MB8GA1UdIwQYMBaAFJe8sDx/a66iZpTSf8fu9gyyl7/2MA8G
A1UdEwEB/wQFMAMBAf8wBQYDK2VwA0EAcQk9A3EjXZfiyO+YUqGjpri72xSxrYBX
46UQFLkxzLmbcrYgFxi74xvEyV67NPFiTGVOhE3HoQHs4Z705mN9Dw==
-----END CERTIFICATE-----
""";

    private static final String ED_KEY = """
-----BEGIN PRIVATE KEY-----
MC4CAQAwBQYDK2VwBCIEIKTNN5gekvjSDaepH6RFvUhjGt/ocjj+Xi6Bqc8btUil
-----END PRIVATE KEY-----
""";

    private static final String EC_REQUEST = """
-----BEGIN CERTIFICATE REQUEST-----
MIHPMHcCAQAwFTETMBEGA1UEAwwKcGFuZWwudGVzdDBZMBMGByqGSM49AgEGCCqG
SM49AwEHA0IABKxe2B8Ry+fYFLi3BHlmvwQdFLAZtH9Cmbs2vJ0GRCj3ceoTbg9r
b4NsccGTIi3anhPnpFwxCDljHlFtDIclOxagADAKBggqhkjOPQQDAgNIADBFAiEA
rRf1oWzp/FU4Og7BkYfIzyE9vCy9TC9o7D8Y1UrRXJcCICGlLKv4yW09VLwqV7NU
wH6C0gSmdbu8bx56JUr1bDMm
-----END CERTIFICATE REQUEST-----
""";
    private static final String CLIENT_LEAF = """
-----BEGIN CERTIFICATE-----
MIIBqDCCAU+gAwIBAgIUQmiuFWDbOnwT3vxriYx0hVX9DUkwCgYIKoZIzj0EAwIw
JDEiMCAGA1UEAwwZTXVyYWxpcyBUZXN0IEludGVybWVkaWF0ZTAeFw0yNjEwMDQy
MjE3NTRaFw0zNjEwMDEyMjE3NTRaMBwxGjAYBgNVBAMMEWNsaWVudC5wYW5lbC50
ZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEme9NduaJbi6Y5zn5BdOEi/dA
nJEa195SyPuqurvu5fWBSqwcjQ+XxmIPM65XemsqvD9mfZos9PMBEC+YsSXKtqNn
MGUwEwYDVR0lBAwwCgYIKwYBBQUHAwIwDgYDVR0PAQH/BAQDAgeAMB0GA1UdDgQW
BBTJcX3GZkTTAz74RDbTuTfDwgm5zTAfBgNVHSMEGDAWgBT7V5ioEt00e0v3XTDL
wy1ihRbIFDAKBggqhkjOPQQDAgNHADBEAiBz0hn7aWqxV8uBTXUyCqyuZl0PedoJ
0fYLmmRKAodCkAIgLn9sLN/fiqQxYtFw4xqH1HmVALYSbOv3AGTvrFOVQyk=
-----END CERTIFICATE-----
""";

    private static final String CLIENT_KEY = """
-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgxR/BUPoVadL1L7mj
XOKdh+gcOmvt4oV6UQ8RmnFuknKhRANCAASZ70125oluLpjnOfkF04SL90CckRrX
3lLI+6q6u+7l9YFKrByND5fGYg8zrld6ayq8P2Z9miz08wEQL5ixJcq2
-----END PRIVATE KEY-----
""";

    private static final String KEY_AGREEMENT_LEAF = """
-----BEGIN CERTIFICATE-----
MIIBqTCCAVCgAwIBAgIUQmiuFWDbOnwT3vxriYx0hVX9DUowCgYIKoZIzj0EAwIw
JDEiMCAGA1UEAwwZTXVyYWxpcyBUZXN0IEludGVybWVkaWF0ZTAeFw0yNjEwMDQy
MjE3NTRaFw0zNjEwMDEyMjE3NTRaMBgxFjAUBgNVBAMMDWt1LnBhbmVsLnRlc3Qw
WTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAQnfVksI09SuNydYqeWwuZ9pYELtVtk
kC+MWCKuEyOwE1+oJdbgtGPdvwijcchVqri/s4vCRswttQtge2ASX2/Mo2wwajAO
BgNVHQ8BAf8EBAMCAwgwGAYDVR0RBBEwD4INa3UucGFuZWwudGVzdDAdBgNVHQ4E
FgQUIPFrB13MDt5xVnPDmv8idIytj7owHwYDVR0jBBgwFoAU+1eYqBLdNHtL910w
y8MtYoUWyBQwCgYIKoZIzj0EAwIDRwAwRAIgGZlaE9CHrtqF95pTk1N7v+uNyZfA
18CccSA6RgGf4fECICBUiVv26cEBLaqUqbCpslubQWhCebv15rboLYc3GQEM
-----END CERTIFICATE-----
""";

    private static final String KEY_AGREEMENT_KEY = """
-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgihKtyBEz6ZwTwXpY
oC0Xupsy+Ofal3imC/k2J1pB94yhRANCAAQnfVksI09SuNydYqeWwuZ9pYELtVtk
kC+MWCKuEyOwE1+oJdbgtGPdvwijcchVqri/s4vCRswttQtge2ASX2/M
-----END PRIVATE KEY-----
""";

    private static final String DIY_LEAF = """
-----BEGIN CERTIFICATE-----
MIIBiDCCAS2gAwIBAgIUN+vLAXuhwkeIDenv+g4IncVn7ikwCgYIKoZIzj0EAwIw
GTEXMBUGA1UEAwwOZGl5LnBhbmVsLnRlc3QwHhcNMjYxMDA0MjIxNzU0WhcNMzYx
MDAxMjIxNzU0WjAZMRcwFQYDVQQDDA5kaXkucGFuZWwudGVzdDBZMBMGByqGSM49
AgEGCCqGSM49AwEHA0IABFjbD2s5DJIyu1Qq8ezktMGGUzPWeds5WfP9kpliZP6/
kcRpBchB84dWh5/p5Myh59aBjyt2QPaLDyQvmL7ApxOjUzBRMB0GA1UdDgQWBBTQ
/V1muNIubK8t0NdiKFTxB/zWADAfBgNVHSMEGDAWgBTQ/V1muNIubK8t0NdiKFTx
B/zWADAPBgNVHRMBAf8EBTADAQH/MAoGCCqGSM49BAMCA0kAMEYCIQCATXQXQDt/
EpqlOnUVe5Y54s1f2GYn6+HqCrKaabIlzQIhAJTU9pPI6VDd2D48QRYIQeGJHPLe
eCK2jH2uXaA/+OHv
-----END CERTIFICATE-----
""";

    private static final String DIY_KEY = """
-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgTqZ2Vr8Zk2ZOohhC
z+PdOwWw+i9E8BW8Ojb5zHmYZ+GhRANCAARY2w9rOQySMrtUKvHs5LTBhlMz1nnb
OVnz/ZKZYmT+v5HEaQXIQfOHVoef6eTMoefWgY8rdkD2iw8kL5i+wKcT
-----END PRIVATE KEY-----
""";

    private static final String SELF_SERVER_LEAF = """
-----BEGIN CERTIFICATE-----
MIIBojCCAUigAwIBAgIUIGyMYMDeV4nYV5kVuoYekozYIVMwCgYIKoZIzj0EAwIw
GjEYMBYGA1UEAwwPc2VsZi5wYW5lbC50ZXN0MB4XDTI2MTAwNDIyMTc1NFoXDTM2
MTAwMTIyMTc1NFowGjEYMBYGA1UEAwwPc2VsZi5wYW5lbC50ZXN0MFkwEwYHKoZI
zj0CAQYIKoZIzj0DAQcDQgAEhir4J1rO0Fw159lMEu/iWkCxsFNWdjCw8kSlrnkT
Pqko0tMPs3JAXBV4AZzWXKYytAkszTtz1RkKYwxLY/62qKNsMGowHQYDVR0OBBYE
FEw0xjrXTj3NKcrh8IbLfQ8pVjKlMB8GA1UdIwQYMBaAFEw0xjrXTj3NKcrh8IbL
fQ8pVjKlMAwGA1UdEwEB/wQCMAAwGgYDVR0RBBMwEYIPc2VsZi5wYW5lbC50ZXN0
MAoGCCqGSM49BAMCA0gAMEUCIQDoIK6dKeUkJo3i8nN09LLHZUhQedPyiLrzZ9p7
H851iAIgZ8rIZBUqIEcpAGQnATR6+mh4n2uG29Gy3RpUa3JOfGc=
-----END CERTIFICATE-----
""";

    private static final String SELF_SERVER_KEY = """
-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgo4HvmBrfNti8V4BY
G1nAaeD7mVpihp17G3j7CtRjtGuhRANCAASGKvgnWs7QXDXn2UwS7+JaQLGwU1Z2
MLDyRKWueRM+qSjS0w+zckBcFXgBnNZcpjK0CSzNO3PVGQpjDEtj/rao
-----END PRIVATE KEY-----
""";

    private static final String INTERMEDIATE_KEY = """
-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgpJcIzN4dXv1uc79Y
Nb+WWCoNtB53wlnI4H4K4hMDPGehRANCAARnqJo1ceH07MDZCRjceFZiT1eveXqR
uWJzNmnaYPu6ftEJBHMGUwgiWC25Adj+rADxtIcbNQuCh8F4iSLPyVP4
-----END PRIVATE KEY-----
""";

    private static final String ODD_NAME_LEAF = """
-----BEGIN CERTIFICATE-----
MIIBpjCCAUygAwIBAgIUUMSfbi2HJIMqkGAqiIaRewCRW50wCgYIKoZIzj0EAwIw
JDEiMCAGA1UEAwwZTXVyYWxpcyBUZXN0IEludGVybWVkaWF0ZTAeFw0yNjEwMDUw
ODQ3MTNaFw0zNjEwMDIwODQ3MTNaMC4xLDAqBgNVBAMMIzxpbWcgc3JjPXggb25l
cnJvcj1hbGVydCgxKT4iJyZhbXA7MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE
Sp2FpizOnytdKwHcnCszYOOCXwbTxJhwbM1CUGDTpna+Hqkdah2pyHNS6GFdV8pc
VX8hM2HNCh5mWDyqqu+SpaNSMFAwDgYDVR0PAQH/BAQDAgeAMB0GA1UdDgQWBBSX
++VMMliQXIypaD+CqetyJczbiTAfBgNVHSMEGDAWgBSaAovRl2kQhApiSvdaPivV
YvNUbTAKBggqhkjOPQQDAgNIADBFAiEA4kpVT7g3K2rtk9a19LkcktMOsTBRlu21
hCiKITeXbEQCIFCOtPCQNW8aQaM0zifSZ4G/iHSubDQw6nsKdj9AN9q/
-----END CERTIFICATE-----
""";

    private static final String ODD_NAME_KEY = """
-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgH2yYvcno1hoXtavi
ooz1jJONvfAsJqqa7ZcJmkuicpChRANCAARKnYWmLM6fK10rAdycKzNg44JfBtPE
mHBszUJQYNOmdr4eqR1qHanIc1LoYV1XylxVfyEzYc0KHmZYPKqq75Kl
-----END PRIVATE KEY-----
""";

    private static final String MANY_NAMES_LEAF = """
-----BEGIN CERTIFICATE-----
MIINSTCCDPCgAwIBAgIUUMSfbi2HJIMqkGAqiIaRewCRW54wCgYIKoZIzj0EAwIw
JDEiMCAGA1UEAwwZTXVyYWxpcyBUZXN0IEludGVybWVkaWF0ZTAeFw0yNjEwMDUw
ODQ3MTNaFw0zNjEwMDIwODQ3MTNaMA8xDTALBgNVBAMMBG1hbnkwWTATBgcqhkjO
PQIBBggqhkjOPQMBBwNCAAQw0i+tlzpDThmW3yycX7KWwMFnMoNiJjq+nml2ylB9
AnSRhRTau2cWmaTC3OIUwWASZAcaGhXG6RaWfGvfRWMLo4IMEzCCDA8wggvLBgNV
HREEggvCMIILvoIwaG9zdDAwLnZlcnktbG9uZy1zdWJkb21haW4tbmFtZS5leGFt
cGxlLmhvbWUubGFugjBob3N0MDEudmVyeS1sb25nLXN1YmRvbWFpbi1uYW1lLmV4
YW1wbGUuaG9tZS5sYW6CMGhvc3QwMi52ZXJ5LWxvbmctc3ViZG9tYWluLW5hbWUu
ZXhhbXBsZS5ob21lLmxhboIwaG9zdDAzLnZlcnktbG9uZy1zdWJkb21haW4tbmFt
ZS5leGFtcGxlLmhvbWUubGFugjBob3N0MDQudmVyeS1sb25nLXN1YmRvbWFpbi1u
YW1lLmV4YW1wbGUuaG9tZS5sYW6CMGhvc3QwNS52ZXJ5LWxvbmctc3ViZG9tYWlu
LW5hbWUuZXhhbXBsZS5ob21lLmxhboIwaG9zdDA2LnZlcnktbG9uZy1zdWJkb21h
aW4tbmFtZS5leGFtcGxlLmhvbWUubGFugjBob3N0MDcudmVyeS1sb25nLXN1YmRv
bWFpbi1uYW1lLmV4YW1wbGUuaG9tZS5sYW6CMGhvc3QwOC52ZXJ5LWxvbmctc3Vi
ZG9tYWluLW5hbWUuZXhhbXBsZS5ob21lLmxhboIwaG9zdDA5LnZlcnktbG9uZy1z
dWJkb21haW4tbmFtZS5leGFtcGxlLmhvbWUubGFugjBob3N0MTAudmVyeS1sb25n
LXN1YmRvbWFpbi1uYW1lLmV4YW1wbGUuaG9tZS5sYW6CMGhvc3QxMS52ZXJ5LWxv
bmctc3ViZG9tYWluLW5hbWUuZXhhbXBsZS5ob21lLmxhboIwaG9zdDEyLnZlcnkt
bG9uZy1zdWJkb21haW4tbmFtZS5leGFtcGxlLmhvbWUubGFugjBob3N0MTMudmVy
eS1sb25nLXN1YmRvbWFpbi1uYW1lLmV4YW1wbGUuaG9tZS5sYW6CMGhvc3QxNC52
ZXJ5LWxvbmctc3ViZG9tYWluLW5hbWUuZXhhbXBsZS5ob21lLmxhboIwaG9zdDE1
LnZlcnktbG9uZy1zdWJkb21haW4tbmFtZS5leGFtcGxlLmhvbWUubGFugjBob3N0
MTYudmVyeS1sb25nLXN1YmRvbWFpbi1uYW1lLmV4YW1wbGUuaG9tZS5sYW6CMGhv
c3QxNy52ZXJ5LWxvbmctc3ViZG9tYWluLW5hbWUuZXhhbXBsZS5ob21lLmxhboIw
aG9zdDE4LnZlcnktbG9uZy1zdWJkb21haW4tbmFtZS5leGFtcGxlLmhvbWUubGFu
gjBob3N0MTkudmVyeS1sb25nLXN1YmRvbWFpbi1uYW1lLmV4YW1wbGUuaG9tZS5s
YW6CMGhvc3QyMC52ZXJ5LWxvbmctc3ViZG9tYWluLW5hbWUuZXhhbXBsZS5ob21l
LmxhboIwaG9zdDIxLnZlcnktbG9uZy1zdWJkb21haW4tbmFtZS5leGFtcGxlLmhv
bWUubGFugjBob3N0MjIudmVyeS1sb25nLXN1YmRvbWFpbi1uYW1lLmV4YW1wbGUu
aG9tZS5sYW6CMGhvc3QyMy52ZXJ5LWxvbmctc3ViZG9tYWluLW5hbWUuZXhhbXBs
ZS5ob21lLmxhboIwaG9zdDI0LnZlcnktbG9uZy1zdWJkb21haW4tbmFtZS5leGFt
cGxlLmhvbWUubGFugjBob3N0MjUudmVyeS1sb25nLXN1YmRvbWFpbi1uYW1lLmV4
YW1wbGUuaG9tZS5sYW6CMGhvc3QyNi52ZXJ5LWxvbmctc3ViZG9tYWluLW5hbWUu
ZXhhbXBsZS5ob21lLmxhboIwaG9zdDI3LnZlcnktbG9uZy1zdWJkb21haW4tbmFt
ZS5leGFtcGxlLmhvbWUubGFugjBob3N0MjgudmVyeS1sb25nLXN1YmRvbWFpbi1u
YW1lLmV4YW1wbGUuaG9tZS5sYW6CMGhvc3QyOS52ZXJ5LWxvbmctc3ViZG9tYWlu
LW5hbWUuZXhhbXBsZS5ob21lLmxhboIwaG9zdDMwLnZlcnktbG9uZy1zdWJkb21h
aW4tbmFtZS5leGFtcGxlLmhvbWUubGFugjBob3N0MzEudmVyeS1sb25nLXN1YmRv
bWFpbi1uYW1lLmV4YW1wbGUuaG9tZS5sYW6CMGhvc3QzMi52ZXJ5LWxvbmctc3Vi
ZG9tYWluLW5hbWUuZXhhbXBsZS5ob21lLmxhboIwaG9zdDMzLnZlcnktbG9uZy1z
dWJkb21haW4tbmFtZS5leGFtcGxlLmhvbWUubGFugjBob3N0MzQudmVyeS1sb25n
LXN1YmRvbWFpbi1uYW1lLmV4YW1wbGUuaG9tZS5sYW6CMGhvc3QzNS52ZXJ5LWxv
bmctc3ViZG9tYWluLW5hbWUuZXhhbXBsZS5ob21lLmxhboIwaG9zdDM2LnZlcnkt
bG9uZy1zdWJkb21haW4tbmFtZS5leGFtcGxlLmhvbWUubGFugjBob3N0MzcudmVy
eS1sb25nLXN1YmRvbWFpbi1uYW1lLmV4YW1wbGUuaG9tZS5sYW6CMGhvc3QzOC52
ZXJ5LWxvbmctc3ViZG9tYWluLW5hbWUuZXhhbXBsZS5ob21lLmxhboIwaG9zdDM5
LnZlcnktbG9uZy1zdWJkb21haW4tbmFtZS5leGFtcGxlLmhvbWUubGFugjBob3N0
NDAudmVyeS1sb25nLXN1YmRvbWFpbi1uYW1lLmV4YW1wbGUuaG9tZS5sYW6CMGhv
c3Q0MS52ZXJ5LWxvbmctc3ViZG9tYWluLW5hbWUuZXhhbXBsZS5ob21lLmxhboIw
aG9zdDQyLnZlcnktbG9uZy1zdWJkb21haW4tbmFtZS5leGFtcGxlLmhvbWUubGFu
gjBob3N0NDMudmVyeS1sb25nLXN1YmRvbWFpbi1uYW1lLmV4YW1wbGUuaG9tZS5s
YW6CMGhvc3Q0NC52ZXJ5LWxvbmctc3ViZG9tYWluLW5hbWUuZXhhbXBsZS5ob21l
LmxhboIwaG9zdDQ1LnZlcnktbG9uZy1zdWJkb21haW4tbmFtZS5leGFtcGxlLmhv
bWUubGFugjBob3N0NDYudmVyeS1sb25nLXN1YmRvbWFpbi1uYW1lLmV4YW1wbGUu
aG9tZS5sYW6CMGhvc3Q0Ny52ZXJ5LWxvbmctc3ViZG9tYWluLW5hbWUuZXhhbXBs
ZS5ob21lLmxhboIwaG9zdDQ4LnZlcnktbG9uZy1zdWJkb21haW4tbmFtZS5leGFt
cGxlLmhvbWUubGFugjBob3N0NDkudmVyeS1sb25nLXN1YmRvbWFpbi1uYW1lLmV4
YW1wbGUuaG9tZS5sYW6CMGhvc3Q1MC52ZXJ5LWxvbmctc3ViZG9tYWluLW5hbWUu
ZXhhbXBsZS5ob21lLmxhboIwaG9zdDUxLnZlcnktbG9uZy1zdWJkb21haW4tbmFt
ZS5leGFtcGxlLmhvbWUubGFugjBob3N0NTIudmVyeS1sb25nLXN1YmRvbWFpbi1u
YW1lLmV4YW1wbGUuaG9tZS5sYW6CMGhvc3Q1My52ZXJ5LWxvbmctc3ViZG9tYWlu
LW5hbWUuZXhhbXBsZS5ob21lLmxhboIwaG9zdDU0LnZlcnktbG9uZy1zdWJkb21h
aW4tbmFtZS5leGFtcGxlLmhvbWUubGFugjBob3N0NTUudmVyeS1sb25nLXN1YmRv
bWFpbi1uYW1lLmV4YW1wbGUuaG9tZS5sYW6CMGhvc3Q1Ni52ZXJ5LWxvbmctc3Vi
ZG9tYWluLW5hbWUuZXhhbXBsZS5ob21lLmxhboIwaG9zdDU3LnZlcnktbG9uZy1z
dWJkb21haW4tbmFtZS5leGFtcGxlLmhvbWUubGFugjBob3N0NTgudmVyeS1sb25n
LXN1YmRvbWFpbi1uYW1lLmV4YW1wbGUuaG9tZS5sYW6CMGhvc3Q1OS52ZXJ5LWxv
bmctc3ViZG9tYWluLW5hbWUuZXhhbXBsZS5ob21lLmxhbocECgAOMzAdBgNVHQ4E
FgQU+yspH3hsUDSe7jn+ir+nkXGGPkwwHwYDVR0jBBgwFoAUmgKL0ZdpEIQKYkr3
Wj4r1WLzVG0wCgYIKoZIzj0EAwIDRwAwRAIgWgq/oHmG9xP2D1fbaLwCpJ5JdW+D
joipzrJt0YN0/kwCIH3lH/BSKDIoM420MZEfrmQ0C+v1xYiLzSx4FZ2vxIjP
-----END CERTIFICATE-----
""";

    private static final String MANY_NAMES_KEY = """
-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgjQ2vSoooydlsFDaX
NEqzbN4TFyWE208eD6GNOgfdKr+hRANCAAQw0i+tlzpDThmW3yycX7KWwMFnMoNi
Jjq+nml2ylB9AnSRhRTau2cWmaTC3OIUwWASZAcaGhXG6RaWfGvfRWML
-----END PRIVATE KEY-----
""";

    private static final String LONG_NAME_LEAF = """
-----BEGIN CERTIFICATE-----
MIICyzCCAnKgAwIBAgIUUMSfbi2HJIMqkGAqiIaRewCRW6AwCgYIKoZIzj0EAwIw
JDEiMCAGA1UEAwwZTXVyYWxpcyBUZXN0IEludGVybWVkaWF0ZTAeFw0yNjEwMDUw
OTIxMjZaFw0zNjEwMDIwOTIxMjZaMA8xDTALBgNVBAMMBGxvbmcwWTATBgcqhkjO
PQIBBggqhkjOPQMBBwNCAATYpDWLfcgJ/LzufFEv7P3/HTvtdS9TT4eE8mbfRsYg
JK+yigYh4AJhPssEmz4ZmICc93leXZcGvTUF6DdsjcsQo4IBlTCCAZEwggFNBgNV
HREEggFEMIIBQIKCATxhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFh
YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWEuYWFhYWFhYWFhYWFhYWFhYWFhYWFh
YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhLmFhYWFhYWFh
YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFh
YWFhYS5hYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFh
YWFhYWFhYWFhYWFhYWFhYWEuYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFh
YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhLmV4YW1wbGUubGFuMB0GA1Ud
DgQWBBRGF25wVY4StOv110cr1eJYNgCUVDAfBgNVHSMEGDAWgBSaAovRl2kQhApi
SvdaPivVYvNUbTAKBggqhkjOPQQDAgNHADBEAiBwWNnEbGHbwoIy6zjPRPyPN3G4
0+2PJkvk1Zor2t7jngIgOHmh68J913EEJlAdMHmGWRh9ML8Z8+2A0fnVXjXCxl8=
-----END CERTIFICATE-----
""";

    private static final String LONG_NAME_KEY = """
-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgxMi3ehzIaoAgni1b
1UgiTjY9GCQpUh6Sr1mtXVcLQaehRANCAATYpDWLfcgJ/LzufFEv7P3/HTvtdS9T
T4eE8mbfRsYgJK+yigYh4AJhPssEmz4ZmICc93leXZcGvTUF6DdsjcsQ
-----END PRIVATE KEY-----
""";

}
