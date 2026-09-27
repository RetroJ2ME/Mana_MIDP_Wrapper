/** RMS HACK：離開選項畫面時，保存配置到 RMS。 */
public final class OptionsHack {
    private OptionsHack() {}

    public static int update(c game, int keys, int actions, int state, int frame) {
        int next = game.f(keys, actions, state, frame);
        if (next != state && (next == 100 || next == 200)) {
            game.e();
            game.i();
        }
        return next;
    }
}
