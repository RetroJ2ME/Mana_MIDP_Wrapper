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
