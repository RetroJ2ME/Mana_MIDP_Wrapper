import com.nttdocomo.ui.SoftKeys;

/** 在遊戲切換 UI 顏色時，同步更新 softkey 背景。 */
public final class WindowHack {
    private WindowHack() {}

    public static void load(a images, int slot, String resource) {
        images.a(slot, resource);
        SoftKeys.setColor(resource.charAt(5) - '0');
    }
}
