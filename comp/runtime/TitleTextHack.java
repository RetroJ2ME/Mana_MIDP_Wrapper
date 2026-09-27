import com.nttdocomo.ui.Font;
import com.nttdocomo.ui.Graphics;

/** 將標題文字改為柔和投影。 */
public final class TitleTextHack {
    private static boolean foregroundPass;

    private TitleTextHack() {}

    /** 開幕署名改為 24px，以確保整數倍率下無縮放鋸齒 */
    public static Font openingFont(int type) {
        return Font.getFont(type, 24);
    }

    public static void setTitleColor(Graphics graphics, int red, int green, int blue) {
        foregroundPass = red == 140 && green == 140 && blue == 140;
        graphics.setColor(Graphics.getColorOfRGB(red, green, blue));
    }

    public static void drawTitleText(Graphics graphics, String text, int x, int y) {
        if (!foregroundPass) return;

        // 全部文本上移 1PX.
        x++;

        graphics.setColor(Graphics.getColorOfRGB(64, 64, 64));
        graphics.drawString(text, x + 1, y);
        graphics.drawString(text, x, y + 1);
        graphics.drawString(text, x + 1, y + 1);
        graphics.setColor(Graphics.getColorOfRGB(255, 255, 255));
        graphics.drawString(text, x, y);
        graphics.setColor(Graphics.getColorOfRGB(140, 140, 140));
    }
}
