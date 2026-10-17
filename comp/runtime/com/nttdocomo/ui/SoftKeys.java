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

package com.nttdocomo.ui;

import java.io.IOException;

/** Mana 專用 softkey 背景；配色跟隨遊戲的視窗顏色設定。 */
public final class SoftKeys {
    private static final String[] BACKGROUNDS = {
        "/softkey_bg_g.png", // 綠
        "/softkey_bg_r.png", // 灰
        "/softkey_bg_p.png", // 青
        "/softkey_bg_b.png"  // 赤
    };
    private static javax.microedition.lcdui.Image bg;
    private static int[] bgPixels;
    private static volatile int color = 2;
    private static int loadedColor = -1;
    private static final int DEFAULT_W = 44;
    private static final int DEFAULT_H = 20;
    private static final int PAD_X = 5;

    private static String leftText;
    private static String rightText;
    private static javax.microedition.lcdui.Image leftImage;
    private static javax.microedition.lcdui.Image rightImage;

    private SoftKeys() {}

    public static void setColor(int value) {
        if (value >= 0 && value < BACKGROUNDS.length) color = value;
    }

    static void paint(javax.microedition.lcdui.Graphics g, Frame frame, int screenW, int screenH) {
        if (g == null || frame == null || !frame.isSoftLabelVisible()) return;
        String left = frame.getSoftLabel(Frame.SOFT_KEY_1);
        String right = frame.getSoftLabel(Frame.SOFT_KEY_2);
        boolean hasLeft = left != null && left.length() > 0;
        boolean hasRight = right != null && right.length() > 0;
        if (!hasLeft && !hasRight) return;
        ensureBg();

        if (hasLeft) {
            if (!same(left, leftText) || leftImage == null) {
                leftText = left;
                leftImage = buildLabel(left);
            }
            drawCached(g, leftImage, 0, screenH, false);
        } else {
            leftText = null;
            leftImage = null;
        }
        if (hasRight) {
            if (!same(right, rightText) || rightImage == null) {
                rightText = right;
                rightImage = buildLabel(right);
            }
            drawCached(g, rightImage, screenW, screenH, true);
        } else {
            rightText = null;
            rightImage = null;
        }
    }

    private static boolean same(String a, String b) {
        return a == b || (a != null && a.equals(b));
    }

    private static void drawCached(javax.microedition.lcdui.Graphics g,
            javax.microedition.lcdui.Image image, int edgeX, int screenH, boolean rightAligned) {
        if (image == null) return;
        int x = rightAligned ? edgeX - image.getWidth() : edgeX;
        int y = screenH - image.getHeight();
        if (y < 0) y = 0;
        g.drawImage(image, x, y,
            javax.microedition.lcdui.Graphics.TOP | javax.microedition.lcdui.Graphics.LEFT);
    }

    private static javax.microedition.lcdui.Image buildLabel(String text) {
        int bgW = bg == null ? DEFAULT_W : bg.getWidth();
        int bgH = bg == null ? DEFAULT_H : bg.getHeight();
        int textW = BitmapFont.stringWidth(text);
        int boxW = textW + PAD_X * 2;
        if (boxW < bgW) boxW = bgW;
        int[] pixels = new int[boxW * bgH];

        if (bg != null && bgPixels != null) {
            stretchBackground(pixels, boxW, bgH, bgW);
        } else {
            buildFallbackBackground(pixels, boxW, bgH);
        }
        int tx = (boxW - textW) >> 1;
        int baseline = ((bgH - BitmapFont.getHeight()) >> 1) + BitmapFont.getAscent() - 1;
        BitmapFont.drawIntoArgb(pixels, boxW, bgH, text, tx, baseline, 0xffffffff);
        return javax.microedition.lcdui.Image.createRGBImage(pixels, boxW, bgH, true);
    }

    private static void stretchBackground(int[] out, int w, int h, int bw) {
        if (w == bw) {
            System.arraycopy(bgPixels, 0, out, 0, w * h);
            return;
        }
        int cap = bw >> 1;
        if (cap < 1) cap = 1;
        if (cap > w >> 1) cap = w >> 1;
        int centre = bw >> 1;
        for (int y = 0; y < h; y++) {
            int src = y * bw;
            int dst = y * w;
            System.arraycopy(bgPixels, src, out, dst, cap);
            int middleEnd = w - cap;
            for (int x = cap; x < middleEnd; x++) out[dst + x] = bgPixels[src + centre];
            System.arraycopy(bgPixels, src + bw - cap, out, dst + w - cap, cap);
        }
    }

    private static void buildFallbackBackground(int[] out, int w, int h) {
        int blue = 0xff173a8b;
        int white = 0xffffffff;
        for (int i = 0; i < out.length; i++) out[i] = blue;
        if (w < 2 || h < 2) return;
        for (int x = 0; x < w; x++) {
            out[x] = white;
            out[(h - 1) * w + x] = white;
        }
        for (int y = 0; y < h; y++) {
            out[y * w] = white;
            out[y * w + w - 1] = white;
        }
    }

    private static void ensureBg() {
        int nextColor = color;
        if (loadedColor == nextColor) return;
        loadedColor = nextColor;
        leftImage = null;
        rightImage = null;
        try {
            bg = javax.microedition.lcdui.Image.createImage(BACKGROUNDS[nextColor]);
            int w = bg.getWidth();
            int h = bg.getHeight();
            bgPixels = new int[w * h];
            bg.getRGB(bgPixels, 0, w, 0, 0, w, h);
        } catch (IOException e) {
            bg = null;
            bgPixels = null;
        } catch (Throwable t) {
            bg = null;
            bgPixels = null;
        }
    }
}
