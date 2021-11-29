package nya.miku.wishmaster.http.stormwall;

import java.util.HashMap;
import java.util.Map;

/** Cookie token generator */
public class StormwallTokenGenerator {
    public static String encrypt(int b) {
        int x = 123456789;
        int i = 0;
        int k = 0;
        for(i = 0; i < 1677696; i++) {
            x = (x + b ^ x + x % 3 + x % 17 + b ^ i) % (16776960);
            if(x % 117 == 0) {
                k = (k + 1) % 1111;
            }
        }
        return Integer.toString(k);
    }
}
