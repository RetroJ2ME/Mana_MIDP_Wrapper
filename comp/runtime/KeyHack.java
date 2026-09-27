/** SoftKey Hack：為適配真機進行 Hack */
public final class KeyHack {
    private static int state;
    private static int pending;

    private KeyHack() {}

    /** 在遊戲選擇其操作後，由 MIDP/DoJa 的 processEvent 回呼函數呼叫。 */
    public static synchronized void enqueue(c game, int action) {
        switch (action) {
            case 30:
                pending |= 1;
                return;
            case 31:
                pending |= 2;
                return;
            case 32:
                // 音量與直接操作功能在實機上已可正常運作，且不受限於 K-based UI 鎖定機制。這裡需要保留原始路徑。
                game.b(32);
                return;
            default:
                return;
        }
    }


    // TEST：按鍵按下後會先保存，等到遊戲邏輯真的去要 K 的時候才可讀。
    public static synchronized int readK(c game) {
        if (pending != 0) {
            state |= pending;
            pending = 0;
        }
        return state;
    }

    // 為 K &= mask 整理狀態。
    // 規避卡在 pending 的輸入狀態，以免在測試前跑 clear 時，把 UI 尚未處理的按鍵吃掉。
    public static synchronized int peekK(c game) {
        return state;
    }

    // 原始 K 僅更新已實作的狀態。
    public static synchronized void setK(c game, int value) {
        state = value;
    }
}
