import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Verifies an Android key attestation certificate chain using only the JDK.
 * Usage: java ChainVerify.java chain.pem roots.pem
 * chain.pem: the chain, leaf first, as concatenated PEM certificates.
 * roots.pem: trusted root certificates, as concatenated PEM certificates.
 * Prints one tab-separated line per certificate, then one ROOT line. Revocation is checked by the caller.
 */
public class ChainVerify {
    public static void main(String[] args) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> chain = load(cf, args[0]);
        List<X509Certificate> roots = load(cf, args[1]);

        for (int i = 0; i < chain.size(); i++) {
            X509Certificate c = chain.get(i);
            String sig;
            if (i + 1 < chain.size()) {
                try {
                    c.verify(chain.get(i + 1).getPublicKey());
                    sig = "ok";
                } catch (Exception e) {
                    sig = "FAIL:" + e.getClass().getSimpleName();
                }
            } else {
                sig = "n/a";
            }
            String validity;
            try {
                c.checkValidity();
                validity = "valid";
            } catch (CertificateException e) {
                validity = "NOT_VALID_NOW:" + e.getClass().getSimpleName();
            }
            System.out.println("CERT\t" + i
                + "\t" + c.getSubjectX500Principal().getName()
                + "\t" + c.getIssuerX500Principal().getName()
                + "\t" + c.getSerialNumber()
                + "\t" + c.getSerialNumber().toString(16)
                + "\t" + c.getNotBefore().getTime()
                + "\t" + c.getNotAfter().getTime()
                + "\t" + sig
                + "\t" + validity);
        }

        X509Certificate top = chain.get(chain.size() - 1);
        int match = -1;
        String how = "";
        for (int r = 0; r < roots.size(); r++) {
            X509Certificate root = roots.get(r);
            if (Arrays.equals(top.getEncoded(), root.getEncoded())) {
                match = r;
                how = "the last certificate in the chain IS trusted root #" + r;
                break;
            }
            try {
                top.verify(root.getPublicKey());
                match = r;
                how = "the last certificate in the chain is signed by trusted root #" + r;
                break;
            } catch (Exception e) {
                // not this root
            }
        }
        System.out.println("ROOT\t" + (match >= 0 ? "TRUSTED\t" + how : "NONE\tno Google root matches the end of this chain"));
    }

    private static List<X509Certificate> load(CertificateFactory cf, String path) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(path));
        List<X509Certificate> out = new ArrayList<>();
        for (Certificate c : cf.generateCertificates(new ByteArrayInputStream(data))) {
            out.add((X509Certificate) c);
        }
        return out;
    }
}
