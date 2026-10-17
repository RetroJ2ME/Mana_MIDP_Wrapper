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

import com.nttdocomo.opt.ui.Graphics2;
import com.nttdocomo.ui.Graphics;
import com.nttdocomo.ui.Image;
import com.nttdocomo.ui.MediaImage;
import com.nttdocomo.ui.MediaManager;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Hashtable;

/** 載入逐圖 PNG，並映射舊的精靈到重排後的新精靈 */
public final class SpriteAtlasHack {
    private static final String DIRECTORY = "/assets/sprite_atlas/";
    private static final SpriteAtlasHack ANCHOR = new SpriteAtlasHack();
    private static final Hashtable names = new Hashtable();
    private static final Hashtable pending = new Hashtable();
    private static final Hashtable images = new Hashtable();
    private static final Hashtable flips = new Hashtable();
    private static final FlipState[] FLIP_STATES = {
        new FlipState(0), new FlipState(1), new FlipState(2), new FlipState(3),
        new FlipState(4), new FlipState(5), new FlipState(6), new FlipState(7)
    };
    private static boolean indexLoaded;

    private SpriteAtlasHack() {}

    public static MediaImage loadImage(String uri) {
        if (uri == null || !uri.endsWith(".gif")) return MediaManager.getImage(uri);
        loadIndex();
        int slash = uri.lastIndexOf('/');
        String name = uri.substring(slash + 1, uri.length() - 4);
        if (!names.containsKey(name)) throw new IllegalArgumentException("missing converted GIF " + name);
        Object entry = names.get(name);
        String png = "resource:///assets/sprite_atlas/" + name + ".png";
        if (entry == Boolean.FALSE) return MediaManager.getImage(png);
        Atlas atlas;
        if (entry instanceof Atlas) {
            atlas = (Atlas)entry;
        } else {
            atlas = readAtlas(name);
            names.put(name, atlas);
        }
        MediaImage media = MediaManager.getImage(png);
        pending.put(media, atlas);
        return media;
    }

    public static Image getImage(MediaImage media) {
        Image image = media.getImage();
        Atlas atlas = (Atlas)pending.remove(media);
        if (atlas != null) images.put(image, atlas);
        return image;
    }

    public static void dispose(Image image) {
        images.remove(image);
        image.dispose();
    }

    public static int width(Image image) {
        Atlas atlas = (Atlas)images.get(image);
        return atlas == null ? image.getWidth() : atlas.oldWidth;
    }

    public static int height(Image image) {
        Atlas atlas = (Atlas)images.get(image);
        return atlas == null ? image.getHeight() : atlas.oldHeight;
    }

    public static void setFlipMode(Graphics2 graphics, int mode) {
        graphics.setFlipMode(mode);
        flips.put(graphics, flipState(mode));
    }

    public static void draw(Graphics2 graphics, Image image, int dx, int dy,
            int sx, int sy, int width, int height) {
        Atlas atlas = (Atlas)images.get(image);
        if (atlas == null) {
            graphics.drawImage(image, dx, dy, sx, sy, width, height);
            return;
        }
        FlipState state = (FlipState)flips.get(graphics);
        int flip = state == null ? 0 : state.mode;
        if (atlas.kind == 1) {
            drawTiles(graphics, image, atlas, dx, dy, sx, sy, width, height, flip);
            return;
        }
        drawSections(graphics, image, atlas, dx, dy, sx, sy, width, height, flip);
    }

    private static void drawTiles(Graphics2 graphics, Image image, Atlas atlas, int dx, int dy,
            int sx, int sy, int width, int height, int flip) {
        // pN_a/b 也會被原版以 24×24 裁切；拆成 12×12 格可同時支援兩種尺寸。
        if (width <= 0 || height <= 0) return;
        long requestedRight = (long)sx + width;
        long requestedBottom = (long)sy + height;
        int left = sx < 0 ? 0 : sx;
        int top = sy < 0 ? 0 : sy;
        int right = requestedRight > atlas.oldWidth ? atlas.oldWidth : (int)requestedRight;
        int bottom = requestedBottom > atlas.oldHeight ? atlas.oldHeight : (int)requestedBottom;
        if (left >= right || top >= bottom) return;

        int firstColumn = left / 12;
        int lastColumn = (right - 1) / 12;
        int firstRow = top / 12;
        int lastRow = (bottom - 1) / 12;
        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = firstColumn; column <= lastColumn; column++) {
                int cell = atlas.tiles[row * 16 + column];
                if (cell < 0) continue;
                int tileX = column * 12;
                int tileY = row * 12;
                int pieceLeft = Math.max(left, tileX);
                int pieceTop = Math.max(top, tileY);
                int pieceRight = Math.min(right, tileX + 12);
                int pieceBottom = Math.min(bottom, tileY + 12);
                int pieceWidth = pieceRight - pieceLeft;
                int pieceHeight = pieceBottom - pieceTop;
                int localX = pieceLeft - sx;
                int localY = pieceTop - sy;
                if (flip == Graphics.FLIP_HORIZONTAL || flip == Graphics.FLIP_ROTATE) {
                    localX = width - localX - pieceWidth;
                }
                if (flip == Graphics.FLIP_VERTICAL || flip == Graphics.FLIP_ROTATE) {
                    localY = height - localY - pieceHeight;
                }
                graphics.drawImage(image, dx + localX, dy + localY,
                        cell % atlas.columns * 12 + pieceLeft - tileX,
                        cell / atlas.columns * 12 + pieceTop - tileY,
                        pieceWidth, pieceHeight);
            }
        }
    }

    public static void drawWhole(Graphics graphics, Image image, int dx, int dy) {
        Atlas atlas = (Atlas)images.get(image);
        if (atlas == null) {
            graphics.drawImage(image, dx, dy);
            return;
        }
        if (atlas.kind == 1) {
            for (int old = 0; old < atlas.tiles.length; old++) {
                int cell = atlas.tiles[old];
                if (cell < 0) continue;
                graphics.drawImage(image, dx + old % 16 * 12, dy + old / 16 * 12,
                        cell % atlas.columns * 12, cell / atlas.columns * 12, 12, 12);
            }
            return;
        }
        drawSections(graphics, image, atlas, dx, dy, 0, 0, atlas.oldWidth, atlas.oldHeight, 0);
    }

    private static void drawSections(Graphics graphics, Image image, Atlas atlas, int dx, int dy,
            int sx, int sy, int width, int height, int flip) {
        int[] boxes = atlas.boxes;
        for (int i = 0; i < boxes.length; i += 6) {
            int oldX = boxes[i], oldY = boxes[i + 1];
            int left = Math.max(sx, oldX);
            int top = Math.max(sy, oldY);
            int right = Math.min(sx + width, oldX + boxes[i + 2]);
            int bottom = Math.min(sy + height, oldY + boxes[i + 3]);
            if (left >= right || top >= bottom) continue;
            int pieceWidth = right - left, pieceHeight = bottom - top;
            int localX = left - sx, localY = top - sy;
            if (flip == Graphics.FLIP_HORIZONTAL || flip == Graphics.FLIP_ROTATE) {
                localX = width - localX - pieceWidth;
            }
            if (flip == Graphics.FLIP_VERTICAL || flip == Graphics.FLIP_ROTATE) {
                localY = height - localY - pieceHeight;
            }
            graphics.drawImage(image, dx + localX, dy + localY,
                    boxes[i + 4] + left - oldX, boxes[i + 5] + top - oldY,
                    pieceWidth, pieceHeight);
        }
    }

    private static void loadIndex() {
        if (indexLoaded) return;
        DataInputStream in = stream(DIRECTORY + "index.bin");
        try {
            if (in.readInt() != 0x53414932) throw new IOException("invalid sprite atlas index");
            int count = in.readUnsignedShort();
            for (int i = 0; i < count; i++) {
                String name = in.readUTF();
                names.put(name, in.readBoolean() ? Boolean.TRUE : Boolean.FALSE);
            }
            indexLoaded = true;
        } catch (IOException failure) {
            throw new IllegalStateException(failure.toString());
        } finally {
            close(in);
        }
    }

    private static Atlas readAtlas(String name) {
        DataInputStream in = stream(DIRECTORY + name + ".bin");
        try {
            if (in.readInt() != 0x53415431) throw new IOException("invalid sprite atlas " + name);
            Atlas atlas = new Atlas();
            atlas.kind = in.readUnsignedByte();
            atlas.oldWidth = in.readUnsignedShort();
            atlas.oldHeight = in.readUnsignedShort();
            // 地圖頭仍包含新圖尺寸；繪製只使用原圖尺寸與映射座標。
            in.readUnsignedShort();
            in.readUnsignedShort();
            if (atlas.kind == 1) {
                if (in.readUnsignedShort() != 12) throw new IOException("invalid tile size " + name);
                atlas.columns = in.readUnsignedShort();
                atlas.tiles = new int[256];
                for (int i = 0; i < atlas.tiles.length; i++) atlas.tiles[i] = in.readShort();
            } else if (atlas.kind == 2) {
                int count = in.readUnsignedShort();
                atlas.boxes = new int[count * 6];
                for (int i = 0; i < atlas.boxes.length; i++) atlas.boxes[i] = in.readUnsignedShort();
            } else {
                throw new IOException("invalid sprite atlas kind " + name);
            }
            return atlas;
        } catch (IOException failure) {
            throw new IllegalStateException(failure.toString());
        } finally {
            close(in);
        }
    }

    private static DataInputStream stream(String path) {
        InputStream input = ANCHOR.getClass().getResourceAsStream(path);
        if (input == null) throw new IllegalStateException("missing " + path);
        return new DataInputStream(input);
    }

    private static void close(InputStream input) {
        try { input.close(); } catch (IOException ignored) {}
    }

    private static FlipState flipState(int mode) {
        return mode >= 0 && mode < FLIP_STATES.length ? FLIP_STATES[mode] : FLIP_STATES[0];
    }

    private static final class Atlas {
        int kind, oldWidth, oldHeight, columns;
        int[] tiles, boxes;
    }

    private static final class FlipState {
        final int mode;
        FlipState(int value) { mode = value; }
    }
}
