// The benchmarks in plain Java, one per name: `java Bench <name>`.
// Each does the same work as benchmarks/nex/<name>.nex and prints the same result,
// then the work's own time in microseconds.
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.LongUnaryOperator;

public class Bench {
    static long fib(long n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }

    static long add(long a, long b) { return a + b; }

    static final class Counter {
        final long bias;
        Counter(long b) { bias = b; }
        long step(long x) { return x + bias; }
    }

    interface Shape { long value(long x); }
    static final class S1 implements Shape { public long value(long x) { return x + 1; } }
    static final class S2 implements Shape { public long value(long x) { return x + 2; } }
    static final class S3 implements Shape { public long value(long x) { return x + 3; } }
    static final class S4 implements Shape { public long value(long x) { return x + 4; } }

    static ArrayList<Long> randoms(int n) {
        ArrayList<Long> xs = new ArrayList<>();
        long seed = 12345;
        for (int i = 0; i < n; i++) {
            seed = (seed * 1103515245L + 12345) % 2147483648L;
            xs.add(seed);
        }
        return xs;
    }

    static long sum(List<Long> xs) {
        long total = 0;
        for (long y : xs) total = (total + y) % 1000003;
        return total;
    }

    static long poly(List<Shape> shapes, long n) {
        long total = 0;
        int k = shapes.size();
        for (long i = 0; i < n; i++) total = (total + shapes.get((int) (i % k)).value(i)) % 1000003;
        return total;
    }

    public static void main(String[] args) {
        long total = 0;
        long t0 = System.nanoTime();
        switch (args[0]) {
            case "startup" -> total = 0;
            case "fib" -> total = fib(35);
            case "loop" -> { for (long i = 0; i < 50000000L; i++) total = (total + i * i) % 1000003; }
            case "sort" -> { ArrayList<Long> xs = randoms(2000000); xs.sort((a, b) -> Long.compare(a, b)); total = sum(xs); }
            case "sort_natural" -> { ArrayList<Long> xs = randoms(2000000); xs.sort(null); total = sum(xs); }
            case "map" -> {
                int n = 500000;
                HashMap<String, Long> m = new HashMap<>();
                for (long i = 0; i < n; i++) m.put("k" + i, i);
                for (long i = 0; i < n; i++) total = (total + m.get("k" + i)) % 1000003;
            }
            case "concat" -> { for (long i = 0; i < 5000000L; i++) total += ("k" + i).length(); }
            case "call_function" -> { for (long i = 0; i < 20000000L; i++) total = add(total, i) % 1000003; }
            case "call_method" -> {
                Counter c = new Counter(3);
                for (long i = 0; i < 20000000L; i++) total = (total + c.step(i)) % 1000003;
            }
            case "call_poly2" -> total = poly(new ArrayList<>(List.of(new S1(), new S2())), 20000000L);
            case "call_poly4" -> total = poly(new ArrayList<>(List.of(new S1(), new S2(), new S3(), new S4())), 20000000L);
            case "call_closure" -> {
                LongUnaryOperator f = x -> x + 1;
                for (long i = 0; i < 20000000L; i++) total = (total + f.applyAsLong(i)) % 1000003;
            }
            default -> throw new IllegalArgumentException("unknown benchmark " + args[0]);
        }
        long t1 = System.nanoTime();
        System.out.println(total);
        System.out.println((t1 - t0) / 1000);
    }
}
