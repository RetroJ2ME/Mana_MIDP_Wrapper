import doja.Resources;
import java.io.IOException;
import java.io.InputStream;

/** 文本輸入邏輯優化 */
public final class NameInputHack {
    private static final int NAME_LENGTH = 4;
    private static final int EMPTY = 255;
    private static final int UNICODE_TAG = 0x10000;
    private static String originalCharacters;

    private NameInputHack() {}

    public static String accept(String input, String original) {
        return input == null || input.length() == 0 ? original : input;
    }

    public static int[] encode(c game, String name) {
        String characters = originalCharacters();
        int[] values = new int[NAME_LENGTH];
        for (int i = 0; i < NAME_LENGTH; i++) {
            if (i >= name.length()) {
                values[i] = EMPTY;
            } else {
                char c = name.charAt(i);
                int index = characters.indexOf(c);
                values[i] = index >= 0 ? index : UNICODE_TAG | c;
            }
        }
        return values;
    }

    public static String decode(c game, int[] values) {
        String characters = originalCharacters();
        StringBuffer name = new StringBuffer(NAME_LENGTH);
        for (int i = 0; i < NAME_LENGTH; i++) {
            int value = values[i];
            if (value == EMPTY) continue;
            if (value >= 0 && value < characters.length()) {
                name.append(characters.charAt(value));
            } else if ((value & 0xFFFF0000) == UNICODE_TAG) {
                name.append((char)value);
            }
        }
        return name.toString();
    }

    private static String originalCharacters() {
        if (originalCharacters != null) return originalCharacters;
        InputStream in = Resources.open("/namesz.txt");
        if (in == null) throw new IllegalStateException("namesz.txt missing");
        StringBuffer characters = new StringBuffer();
        try {
            int low;
            while ((low = in.read()) >= 0) {
                int high = in.read();
                if (high < 0) throw new IOException("invalid namesz.txt length");
                char c = (char)(low | high << 8);
                if (c != '\uFEFF') characters.append(c);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e.toString());
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
        originalCharacters = characters.toString();
        return originalCharacters;
    }
}
