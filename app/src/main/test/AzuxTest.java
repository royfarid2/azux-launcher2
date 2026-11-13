public class AzuxTest {
    public static void main(String[] args) throws Exception {
        System.out.println("AZUX JVM OK");
        String[] keys = {"java.version", "java.vm.name", "java.vendor", "os.name", "os.arch", "java.home", "user.home"};
        for (String k : keys) System.out.println(k + " = " + System.getProperty(k));
        System.out.println("cpus = " + Runtime.getRuntime().availableProcessors());
        System.out.println("max memory MB = " + Runtime.getRuntime().maxMemory() / (1024 * 1024));
        long sum = 0;
        for (int i = 0; i < 5_000_000; i++) sum += i % 7;
        System.out.println("compute = " + sum);
        Thread t = new Thread(() -> System.out.println("thread ok"));
        t.start();
        t.join();
        System.out.println("DONE");
    }
}
