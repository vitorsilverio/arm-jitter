import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import java.lang.reflect.Field;
public class BucketProbe {
    public static void main(String[] a) throws Exception {
        Aarch64Decoder d = new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A);
        Field f = Aarch64Decoder.class.getDeclaredField("advSimdTable"); f.setAccessible(true);
        Object t = f.get(d);
        Field km = t.getClass().getDeclaredField("keyMask"); km.setAccessible(true);
        Field bf = t.getClass().getDeclaredField("buckets"); bf.setAccessible(true);
        int mask = (int) km.get(t); Object[][] b = (Object[][]) bf.get(t);
        int max = 0; long sum = 0; for (Object[] x : b) { max = Math.max(max, x.length); sum += x.length; }
        System.out.printf("keyMask %08x buckets %d rows %d maxBucket %d%n", mask, b.length, sum, max);
        for (String w : new String[]{"4e20b800","4e609800","4ea0f800","0e214800","4ea1b800","0e200800","0e200000","4e284800"}) {
            int word = Integer.parseUnsignedInt(w, 16);
            System.out.printf("%s bucket %d%n", w, b[Integer.compress(word, mask)].length);
        }
    }
}
