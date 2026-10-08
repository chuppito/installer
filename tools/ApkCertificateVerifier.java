import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/** Read certificates from a cryptographically verified APK, without parsing CLI text. */
class ApkCertificateVerifier {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected one APK path");
        ApkVerifier.Result result = new ApkVerifier.Builder(new File(args[0])).build().verify();
        if (!result.isVerified()) {
            throw new IllegalStateException("APK signature verification failed: " + result.getErrors());
        }
        Set<String> fingerprints = new HashSet<>();
        for (X509Certificate certificate : result.getSignerCertificates()) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            fingerprints.add(HexFormat.of().formatHex(digest));
        }
        if (fingerprints.size() != 1) {
            throw new IllegalStateException("Expected one APK signing identity, found " + fingerprints.size());
        }
        System.out.println(fingerprints.iterator().next());
    }
}
