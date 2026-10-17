/*
 * Copyright (C) 2026 Magstic
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

/** 音頻 HACK：擴展音樂控制為 ALL、ONLY BGM、ONLY SFX、ALL OFF。 */
public final class SoundModeHack {
    private SoundModeHack() {}

    public static String label(int mode) {
        switch (mode) {
            case 0: return "ＯＦＦ";
            case 1: return "ＡＬＬ";
            case 2: return "ＢＧＭ";
            case 3: return "ＳＦＸ";
            default: throw new IllegalArgumentException("sound mode");
        }
    }

    public static void setMode(b audio, int mode) {
        if (mode == 0 || mode == 1) {
            audio.a(mode);
            return;
        }

        // 先全部靜音，再沿用原版音量流程只開啟選中的一組。
        audio.a(0);
        audio.o = 1;
        if (mode == 2) audio.b(audio.q);
        else audio.c(audio.p);
        audio.o = mode;
    }

    public static void setBgmVolume(b audio, int volume) {
        int mode = audio.o;
        audio.o = mode == 1 || mode == 2 ? 1 : 0;
        audio.b(volume);
        audio.o = mode;
    }

    public static void setSfxVolume(b audio, int volume) {
        int mode = audio.o;
        audio.o = mode == 1 || mode == 3 ? 1 : 0;
        audio.c(volume);
        audio.o = mode;
    }

    public static void play(b audio, int sound) {
        int mode = audio.o;
        if (mode == 2 && sound >= 87) return;
        if (mode == 0 || mode == 1 || sound == -1) {
            audio.d(sound);
            return;
        }

        // 沿用原版播放與曲目追蹤；未啟用的 BGM 保持靜音，以便切回時恢復。
        audio.o = sound < 87 && mode == 3 ? 0 : 1;
        audio.d(sound);
        audio.o = mode;
    }
}
