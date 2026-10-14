import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Prints the key attestation extension (OID 1.3.6.1.4.1.11129.2.1.17) of the certificate nearest the root that carries
 * one, as hex of the DER OCTET STRING that X509Certificate.getExtensionValue returns. The JDK parses the certificate;
 * the extension itself is parsed by a separate Python reader in conformance_m4.py, so the product's Kotlin parser is
 * compared with an implementation that shares no code with it.
 * Usage: java AttestExt.java chain.pem
 */
public class AttestExt {
    public static void main(String[] args) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        byte[] data = Files.readAllBytes(Paths.get(args[0]));
        List<X509Certificate> chain = new ArrayList<>();
        for (Certificate c : cf.generateCertificates(new ByteArrayInputStream(data))) {
            chain.add((X509Certificate) c);
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            byte[] ext = chain.get(i).getExtensionValue("1.3.6.1.4.1.11129.2.1.17");
            if (ext != null) {
                StringBuilder sb = new StringBuilder();
                for (byte b : ext) {
                    sb.append(String.format("%02x", b));
                }
                System.out.println("EXT\t" + i + "\t" + sb);
                return;
            }
        }
        System.out.println("NONE");
    }
}
