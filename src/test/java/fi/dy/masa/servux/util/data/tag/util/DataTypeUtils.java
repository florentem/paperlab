package fi.dy.masa.servux.util.data.tag.util;

public class DataTypeUtils {
    public static long getUTFLength(String str) {
        long utflen = 0;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if ((c >= 0x0001) && (c <= 0x007F)) {
                utflen++;
            } else if (c > 0x07FF) {
                utflen += 3;
            } else {
                utflen += 2;
            }
        }
        return utflen;
    }
}
