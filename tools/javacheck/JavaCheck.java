import java.io.File;
import java.nio.file.Files;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Runs inside the phone's JVM (Settings > Test Java): checks the basics Minecraft needs. Java 8 compatible. */
public class JavaCheck {
    private static int failures;

    private static void ok(String what, String detail) { System.out.println("OK    " + what + (detail.isEmpty() ? "" : ": " + detail)); }
    private static void fail(String what, Throwable t) { failures++; System.out.println("FAIL  " + what + ": " + t); }

    public static void main(String[] args) {
        System.out.println("=== Azux Java check ===");
        System.out.println("java.version  = " + System.getProperty("java.version"));
        System.out.println("java.vm.name  = " + System.getProperty("java.vm.name"));
        System.out.println("os.name/arch  = " + System.getProperty("os.name") + " / " + System.getProperty("os.arch"));
        System.out.println("java.home     = " + System.getProperty("java.home"));
        System.out.println("max heap      = " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB, cpus " + Runtime.getRuntime().availableProcessors());
        try {
            long sum = 0;
            for (int i = 2; i < 200000; i++) { boolean p = true; for (int j = 2; (long) j * j <= i; j++) if (i % j == 0) { p = false; break; } if (p) sum += i; }
            ok("compute", "sum of primes < 200000 = " + sum);
        } catch (Throwable t) { fail("compute", t); }
        try {
            byte[] data = new byte[100000];
            for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 31);
            Deflater d = new Deflater(); d.setInput(data); d.finish();
            byte[] z = new byte[200000]; int n = d.deflate(z);
            Inflater inf = new Inflater(); inf.setInput(z, 0, n);
            byte[] back = new byte[data.length]; int m = inf.inflate(back);
            ok("zip (native libzip)", n + " bytes compressed, roundtrip " + (m == data.length && java.util.Arrays.equals(data, back)));
        } catch (Throwable t) { fail("zip", t); }
        try {
            File f = File.createTempFile("azux", ".tmp");
            Files.write(f.toPath(), "hello".getBytes("UTF-8"));
            ok("file io + nio", new String(Files.readAllBytes(f.toPath()), "UTF-8") + " in " + f.getParent());
            f.delete();
        } catch (Throwable t) { fail("file io", t); }
        try { ok("headless awt", String.valueOf(java.awt.GraphicsEnvironment.isHeadless())); } catch (Throwable t) { fail("awt", t); }
        try { ok("images (ImageIO)", String.valueOf(javax.imageio.ImageIO.getReaderFormatNames().length) + " formats"); } catch (Throwable t) { fail("imageio", t); }
        try { ok("localhost", java.net.InetAddress.getByName("localhost").getHostAddress()); } catch (Throwable t) { fail("network", t); }
        try { ok("tls (certificates)", javax.net.ssl.SSLContext.getDefault().getProtocol()); } catch (Throwable t) { fail("tls", t); }
        System.out.println(failures == 0 ? "JAVA OK" : "JAVA HAS " + failures + " PROBLEM(S)");
    }
}
