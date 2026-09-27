package spriteatlas;

import doja.tools.image.GifImage;
import doja.tools.io.FileIO;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/** 按照原精靈圖的可見區域，逐張重排 & 重映射遊戲精靈，以節約圖形面積。 */
public final class SpriteAtlasBuild {
    private static final int TILE = 12;
    private static final int MAX_DIMENSION = 240;

    private SpriteAtlasBuild() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: SpriteAtlasBuild <game-classes> <generated-assets>");
        File game = new File(args[0]);
        File output = new File(args[1], "sprite_atlas");
        FileIO.ensureDirectory(output);
        File[] files = game.listFiles();
        if (files == null) throw new IOException("cannot list " + game);
        Arrays.sort(files);
        List<File> converted = new ArrayList<File>();
        List<File> sourceGifs = new ArrayList<File>();
        List<Boolean> packed = new ArrayList<Boolean>();
        long beforeArea = 0;
        long afterArea = 0;
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            String name = file.getName();
            if (!name.endsWith(".gif")) continue;
            sourceGifs.add(file);
            boolean launchIcon = name.equals("icon48.gif");
            boolean sprite = isSprite(name);
            GifImage.Result gif = null;
            BufferedImage source;
            if (sprite) {
                byte[] bytes = FileIO.read(file);
                gif = GifImage.decode(bytes, 0);
                if (gif.encodedLength != bytes.length) throw new IOException(name + ": unexpected GIF data");
                source = gif.image;
            } else {
                source = ImageIO.read(file);
            }
            if (source == null || !(source.getColorModel() instanceof IndexColorModel)) {
                throw new IOException(name + ": unexpected GIF image");
            }
            Plan plan = null;
            boolean map = false;
            if (sprite) {
                boolean backgroundMap = name.matches("bm_p[0-9]+\\.gif");
                boolean layerMap = name.matches("p[0-9]+_[ab]\\.gif");
                map = backgroundMap || layerMap;
                boolean vertical = name.equals("en19.gif");
                boolean boss = name.matches("bm_en(91|92|93|95)\\.gif");
                List<Box> boxes = map ? mapTiles(source, backgroundMap ? 218 : 231)
                        : vertical ? gridCells(source, 16)
                        : boss ? bossCells(source, name)
                        : name.equals("bm_en84.gif") ? bmEn84Cells(source) : components(source);
                if (boxes != null && !boxes.isEmpty()) {
                    plan = plan(source, boxes, vertical);
                    if (plan != null && name.equals("player2.gif")) plan = packPlayer2(source, plan);
                    if (plan != null && name.equals("bm_en84.gif")) plan = packBmEn84(source, plan);
                    int maximumPercent = layerMap || boss ? 95 : 85;
                    if (plan != null && plan.width * plan.height * 100 >= source.getWidth() * source.getHeight() * maximumPercent) {
                        plan = null;
                    }
                }
            }
            String stem = name.substring(0, name.length() - 4);
            BufferedImage image = compactPalette(plan == null ? source : render(source, plan));
            if (plan != null) verify(source, image, plan);
            File png = new File(launchIcon ? output.getParentFile() : output, stem + ".png");
            if (!ImageIO.write(image, "png", png)) throw new IOException("PNG writer unavailable");
            verifyPixels(plan == null ? source : image, ImageIO.read(png));
            if (!launchIcon && plan != null) {
                writeMap(new File(output, stem + ".bin"), gif, plan, map);
                beforeArea += source.getWidth() * source.getHeight();
                afterArea += plan.width * plan.height;
            }
            if (!launchIcon) {
                converted.add(file);
                packed.add(Boolean.valueOf(plan != null));
            }
        }
        writeIndex(new File(output, "index.bin"), converted, packed);
        for (int i = 0; i < sourceGifs.size(); i++) {
            if (!sourceGifs.get(i).delete()) throw new IOException("cannot remove converted build copy " + sourceGifs.get(i));
        }
        System.out.println("SpriteAtlasBuild: " + sourceGifs.size() + " GIFs converted to PNG; packed area "
                + beforeArea + " -> " + afterArea + " pixels ("
                + (beforeArea == 0 ? 0 : afterArea * 100 / beforeArea) + "%)");
    }

    private static boolean isSprite(String name) {
        return name.matches("(bm_system|bm_p[0-9]+|bm_en[0-9]+|bm_effect|bm_wep1|player2?|wep0|"
                + "p[0-9]+_[ab]|en[0-9]+|npc[0-9]+|m_npc[0-9]+)\\.gif");
    }

    private static List<Box> mapTiles(BufferedImage image, int occupiedLimit) throws IOException {
        if (image.getWidth() != 192 || image.getHeight() != 192) throw new IOException("unexpected map tile sheet size");
        List<Box> boxes = gridCells(image, TILE);
        // bm_p 達 85%、pN_a/b 達 90% 圖塊佔用率時，維持原圖。
        return boxes.size() >= occupiedLimit ? null : boxes;
    }

    private static List<Box> gridCells(BufferedImage image, int size) {
        List<Box> boxes = new ArrayList<Box>();
        for (int y = 0; y < image.getHeight() / size; y++) {
            for (int x = 0; x < image.getWidth() / size; x++) {
                if (hasPixel(image, x * size, y * size, size, size)) {
                    boxes.add(new Box(x * size, y * size, size, size));
                }
            }
        }
        return boxes;
    }

    private static List<Box> bossCells(BufferedImage image, String name) throws IOException {
        boolean tall = name.equals("bm_en91.gif") || name.equals("bm_en92.gif");
        if (image.getWidth() != (tall ? 72 : 96) || image.getHeight() != (tall ? 168 : 120)) {
            throw new IOException(name + ": unexpected boss sprite sheet size");
        }
        // 91／92 的兩幀本體有重複圖塊；保留完整格位去重，比逐格收邊更緊湊。
        if (tall) return gridCells(image, 24);
        List<Box> boxes = new ArrayList<Box>();
        for (int y = 0; y < image.getHeight(); y += 24) {
            for (int x = 0; x < image.getWidth(); x += 24) {
                Box box = visibleBox(image, x, y, 24, 24);
                if (box != null) boxes.add(box);
            }
        }
        return boxes;
    }

    private static List<Box> bmEn84Cells(BufferedImage image) throws IOException {
        if (image.getWidth() != 144 || image.getHeight() != 56) {
            throw new IOException("unexpected bm_en84 sprite sheet size");
        }
        List<Box> boxes = new ArrayList<Box>();
        for (int x = 0; x < 144; x += 24) {
            Box sprite = visibleBox(image, x, 0, 24, 36);
            if (sprite != null) boxes.add(sprite);
            if (x < 48) {
                Box weapon = visibleBox(image, x, 36, 24, 20);
                if (weapon != null) boxes.add(weapon);
            }
        }
        return boxes;
    }

    private static Box visibleBox(BufferedImage image, int x, int y, int width, int height) {
        int left = width, top = height, right = 0, bottom = 0;
        for (int yy = 0; yy < height; yy++) {
            for (int xx = 0; xx < width; xx++) {
                if ((image.getRGB(x + xx, y + yy) >>> 24) == 0) continue;
                left = Math.min(left, xx);
                top = Math.min(top, yy);
                right = Math.max(right, xx + 1);
                bottom = Math.max(bottom, yy + 1);
            }
        }
        return right > left ? new Box(x + left, y + top, right - left, bottom - top) : null;
    }

    private static List<Box> components(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        boolean[] seen = new boolean[width * height];
        int[] queue = new int[width * height];
        List<Box> boxes = new ArrayList<Box>();
        for (int start = 0; start < seen.length; start++) {
            if (seen[start] || (image.getRGB(start % width, start / width) >>> 24) == 0) continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            int left = width, top = height, right = 0, bottom = 0;
            while (head < tail) {
                int p = queue[head++];
                int x = p % width, y = p / width;
                if (x < left) left = x;
                if (y < top) top = y;
                if (x + 1 > right) right = x + 1;
                if (y + 1 > bottom) bottom = y + 1;
                for (int yy = Math.max(0, y - 1); yy <= Math.min(height - 1, y + 1); yy++) {
                    for (int xx = Math.max(0, x - 1); xx <= Math.min(width - 1, x + 1); xx++) {
                        int q = yy * width + xx;
                        if (seen[q] || (image.getRGB(xx, yy) >>> 24) == 0) continue;
                        seen[q] = true;
                        queue[tail++] = q;
                    }
                }
            }
            boxes.add(new Box(left, top, right - left, bottom - top));
            if (boxes.size() > 512) return null;
        }
        // 僅合併外框重疊的元素；相鄰精靈不得因此連成整張圖。
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < boxes.size() && !changed; i++) {
                Box a = boxes.get(i);
                for (int j = i + 1; j < boxes.size(); j++) {
                    Box b = boxes.get(j);
                    if (a.x >= b.x + b.w || b.x >= a.x + a.w
                            || a.y >= b.y + b.h || b.y >= a.y + a.h) continue;
                    int right = Math.max(a.x + a.w, b.x + b.w);
                    int bottom = Math.max(a.y + a.h, b.y + b.h);
                    a.x = Math.min(a.x, b.x);
                    a.y = Math.min(a.y, b.y);
                    a.w = right - a.x;
                    a.h = bottom - a.y;
                    boxes.remove(j);
                    changed = true;
                    break;
                }
            }
        } while (changed);
        return boxes;
    }

    private static boolean hasPixel(BufferedImage image, int x, int y, int width, int height) {
        for (int yy = y; yy < y + height; yy++) {
            for (int xx = x; xx < x + width; xx++) {
                if ((image.getRGB(xx, yy) >>> 24) != 0) return true;
            }
        }
        return false;
    }

    private static Plan plan(BufferedImage source, List<Box> boxes, boolean vertical) {
        Map<Key, Item> seen = new HashMap<Key, Item>();
        List<Item> items = new ArrayList<Item>();
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            int[] pixels = new int[box.w * box.h];
            int p = 0;
            for (int y = 0; y < box.h; y++) {
                for (int x = 0; x < box.w; x++) {
                    int color = source.getRGB(box.x + x, box.y + y);
                    // 透明像素的補色不會繪出，不應阻止相同圖塊共用。
                    pixels[p++] = (color >>> 24) == 0 ? 0 : color;
                }
            }
            Key key = new Key(box.w, box.h, pixels);
            Item item = seen.get(key);
            if (item == null) {
                item = new Item(box);
                seen.put(key, item);
                items.add(item);
            }
            box.item = item;
        }
        Collections.sort(items, new Comparator<Item>() {
            public int compare(Item a, Item b) {
                int byHeight = b.source.h - a.source.h;
                return byHeight != 0 ? byHeight : b.source.w - a.source.w;
            }
        });
        int widest = 0;
        for (int i = 0; i < items.size(); i++) widest = Math.max(widest, items.get(i).source.w);
        int bestArea = Integer.MAX_VALUE, bestSpan = Integer.MAX_VALUE;
        int bestWidth = 0, bestHeight = 0;
        int[] bestX = null, bestY = null;
        for (int width = widest; width <= MAX_DIMENSION; width++) {
            int[] skyline = new int[width];
            int[] xs = new int[items.size()];
            int[] ys = new int[items.size()];
            int usedWidth = 0, usedHeight = 0;
            boolean fits = true;
            for (int i = 0; i < items.size(); i++) {
                Item item = items.get(i);
                int xBest = -1, yBest = Integer.MAX_VALUE;
                for (int x = 0; x <= width - item.source.w; x++) {
                    int y = 0;
                    for (int xx = x; xx < x + item.source.w; xx++) y = Math.max(y, skyline[xx]);
                    if (y < yBest) { xBest = x; yBest = y; }
                }
                if (xBest < 0 || yBest + item.source.h > MAX_DIMENSION) { fits = false; break; }
                xs[i] = xBest;
                ys[i] = yBest;
                for (int x = xBest; x < xBest + item.source.w; x++) skyline[x] = yBest + item.source.h;
                usedWidth = Math.max(usedWidth, xBest + item.source.w);
                usedHeight = Math.max(usedHeight, yBest + item.source.h);
            }
            if (!fits) continue;
            int area = usedWidth * usedHeight;
            int span = Math.max(usedWidth, usedHeight);
            if (area < bestArea || area == bestArea
                    && (vertical ? usedWidth < bestWidth : span < bestSpan)) {
                bestArea = area;
                bestSpan = span;
                bestWidth = usedWidth;
                bestHeight = usedHeight;
                bestX = xs;
                bestY = ys;
            }
        }
        if (bestX == null) return null;
        for (int i = 0; i < items.size(); i++) {
            items.get(i).x = bestX[i];
            items.get(i).y = bestY[i];
        }
        return new Plan(boxes, items, bestWidth, bestHeight);
    }

    private static Plan packPlayer2(BufferedImage source, Plan plan) throws IOException {
        if (source.getWidth() != 144 || source.getHeight() != 112 || plan.items.size() != 12) {
            throw new IOException("unexpected player2 sprite sheet");
        }
        List<Item> tall = new ArrayList<Item>();
        List<Item> shortSprites = new ArrayList<Item>();
        List<Item> small = new ArrayList<Item>();
        for (int i = 0; i < plan.items.size(); i++) {
            Item item = plan.items.get(i);
            if (item.source.h >= 50) tall.add(item);
            else if (item.source.w >= 20 && item.source.h >= 20) shortSprites.add(item);
            else small.add(item);
        }
        if (tall.size() != 7 || shortSprites.size() != 4 || small.size() != 1) {
            throw new IOException("unexpected player2 sprite regions");
        }

        int left = 0, height = 0;
        for (int i = 0; i < tall.size(); i++) {
            Item item = tall.get(i);
            item.x = left;
            item.y = 0;
            left += item.source.w;
            height = Math.max(height, item.source.h);
        }
        // 高精靈右側的四格分為上下兩列，將原先伸出最右側的兩格放到下列。
        int topX = left, topHeight = 0;
        for (int i = 0; i < 2; i++) {
            Item item = shortSprites.get(i);
            item.x = topX;
            item.y = 0;
            topX += item.source.w;
            topHeight = Math.max(topHeight, item.source.h);
        }
        int bottomX = left;
        for (int i = 2; i < 4; i++) {
            Item item = shortSprites.get(i);
            item.x = bottomX;
            item.y = topHeight;
            bottomX += item.source.w;
            height = Math.max(height, topHeight + item.source.h);
        }
        Item dot = small.get(0);
        dot.x = topX;
        dot.y = 0;
        int width = Math.max(bottomX, topX + dot.source.w);
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IOException("player2 atlas exceeds maximum dimension");
        }
        return new Plan(plan.boxes, plan.items, width, height);
    }

    private static Plan packBmEn84(BufferedImage source, Plan plan) throws IOException {
        if (source.getWidth() != 144 || source.getHeight() != 56 || plan.items.size() != 8) {
            throw new IOException("unexpected bm_en84 sprite regions");
        }
        List<Item> front = new ArrayList<Item>();
        List<Item> side = new ArrayList<Item>();
        List<Item> weapons = new ArrayList<Item>();
        for (int i = 0; i < plan.items.size(); i++) {
            Item item = plan.items.get(i);
            if (item.source.y >= 36) weapons.add(item);
            else if (item.source.x >= 96) side.add(item);
            else front.add(item);
        }
        if (front.size() != 4 || side.size() != 2 || weapons.size() != 2) {
            throw new IOException("unexpected bm_en84 sprite groups");
        }
        Comparator<Item> sourceOrder = new Comparator<Item>() {
            public int compare(Item a, Item b) { return a.source.x - b.source.x; }
        };
        Collections.sort(front, sourceOrder);
        Collections.sort(side, sourceOrder);
        Collections.sort(weapons, sourceOrder);

        int width = 0, topHeight = 0;
        for (int i = 0; i < front.size(); i++) {
            Item item = front.get(i);
            item.x = width;
            item.y = 0;
            width += item.source.w;
            topHeight = Math.max(topHeight, item.source.h);
        }
        // 兩把武器與右側兩個側身幀共用下列，不再為側身幀增設右欄。
        int bottomWidth = 0, bottomHeight = 0;
        for (int i = 0; i < weapons.size() + side.size(); i++) {
            Item item = i < weapons.size() ? weapons.get(i) : side.get(i - weapons.size());
            item.x = bottomWidth;
            item.y = topHeight;
            bottomWidth += item.source.w;
            bottomHeight = Math.max(bottomHeight, item.source.h);
        }
        width = Math.max(width, bottomWidth);
        int height = topHeight + bottomHeight;
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IOException("bm_en84 atlas exceeds maximum dimension");
        }
        return new Plan(plan.boxes, plan.items, width, height);
    }

    private static BufferedImage render(BufferedImage source, Plan plan) {
        IndexColorModel colors = (IndexColorModel)source.getColorModel();
        WritableRaster output = colors.createCompatibleWritableRaster(plan.width, plan.height);
        int transparent = colors.getTransparentPixel();
        if (transparent >= 0) {
            for (int y = 0; y < plan.height; y++) {
                for (int x = 0; x < plan.width; x++) output.setSample(x, y, 0, transparent);
            }
        }
        Raster input = source.getRaster();
        for (int i = 0; i < plan.items.size(); i++) {
            Item item = plan.items.get(i);
            Box box = item.source;
            for (int y = 0; y < box.h; y++) {
                for (int x = 0; x < box.w; x++) {
                    output.setSample(item.x + x, item.y + y, 0, input.getSample(box.x + x, box.y + y, 0));
                }
            }
        }
        return new BufferedImage(colors, output, false, null);
    }

    private static BufferedImage compactPalette(BufferedImage image) {
        IndexColorModel colors = (IndexColorModel)image.getColorModel();
        Raster source = image.getRaster();
        int width = image.getWidth(), height = image.getHeight();
        boolean[] used = new boolean[colors.getMapSize()];
        int count = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = source.getSample(x, y, 0);
                if (!used[index]) { used[index] = true; count++; }
            }
        }
        byte[] red = new byte[count], green = new byte[count];
        byte[] blue = new byte[count], alpha = new byte[count];
        int[] remap = new int[used.length];
        boolean opaque = true;
        int next = 0;
        for (int old = 0; old < used.length; old++) {
            if (!used[old]) continue;
            remap[old] = next;
            red[next] = (byte)colors.getRed(old);
            green[next] = (byte)colors.getGreen(old);
            blue[next] = (byte)colors.getBlue(old);
            alpha[next] = (byte)colors.getAlpha(old);
            if (colors.getAlpha(old) != 255) opaque = false;
            next++;
        }
        int bits = count <= 2 ? 1 : count <= 4 ? 2 : count <= 16 ? 4 : 8;
        IndexColorModel compact = opaque
                ? new IndexColorModel(bits, count, red, green, blue)
                : new IndexColorModel(bits, count, red, green, blue, alpha);
        WritableRaster output = compact.createCompatibleWritableRaster(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                output.setSample(x, y, 0, remap[source.getSample(x, y, 0)]);
            }
        }
        return new BufferedImage(compact, output, false, null);
    }

    private static void verify(BufferedImage original, BufferedImage atlas, Plan plan) throws IOException {
        int width = original.getWidth(), height = original.getHeight();
        int[] restored = new int[width * height];
        boolean[] covered = new boolean[restored.length];
        for (int i = 0; i < plan.boxes.size(); i++) {
            Box box = plan.boxes.get(i);
            for (int y = 0; y < box.h; y++) {
                for (int x = 0; x < box.w; x++) {
                    int position = (box.y + y) * width + box.x + x;
                    if (covered[position]) throw new IOException("overlapping sprite elements");
                    covered[position] = true;
                    restored[position] = atlas.getRGB(box.item.x + x, box.item.y + y);
                }
            }
        }
        for (int p = 0; p < restored.length; p++) {
            int old = original.getRGB(p % width, p / width);
            int now = restored[p];
            if ((old >>> 24) != (now >>> 24) || (old >>> 24) != 0 && old != now) {
                throw new IOException("sprite atlas pixel mismatch at " + p % width + "," + p / width);
            }
        }
    }

    private static void verifyPixels(BufferedImage expected, BufferedImage actual) throws IOException {
        if (actual == null || expected.getWidth() != actual.getWidth()
                || expected.getHeight() != actual.getHeight()) {
            throw new IOException("PNG dimensions differ from source image");
        }
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                int old = expected.getRGB(x, y);
                int now = actual.getRGB(x, y);
                if ((old >>> 24) != (now >>> 24) || (old >>> 24) != 0 && old != now) {
                    throw new IOException("PNG pixel mismatch at " + x + "," + y);
                }
            }
        }
    }

    private static void writeMap(File file, GifImage.Result gif, Plan plan, boolean tiled) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeBytes("SAT1");
        out.writeByte(tiled ? 1 : 2);
        out.writeShort(gif.width);
        out.writeShort(gif.height);
        out.writeShort(plan.width);
        out.writeShort(plan.height);
        if (tiled) {
            out.writeShort(TILE);
            int[] mapping = new int[256];
            Arrays.fill(mapping, -1);
            for (int i = 0; i < plan.boxes.size(); i++) {
                Box box = plan.boxes.get(i);
                mapping[box.y / TILE * 16 + box.x / TILE] = box.item.y / TILE * (plan.width / TILE)
                        + box.item.x / TILE;
            }
            out.writeShort(plan.width / TILE);
            for (int i = 0; i < mapping.length; i++) out.writeShort(mapping[i]);
        } else {
            out.writeShort(plan.boxes.size());
            for (int i = 0; i < plan.boxes.size(); i++) {
                Box box = plan.boxes.get(i);
                out.writeShort(box.x);
                out.writeShort(box.y);
                out.writeShort(box.w);
                out.writeShort(box.h);
                out.writeShort(box.item.x);
                out.writeShort(box.item.y);
            }
        }
        out.close();
        FileIO.write(file, bytes.toByteArray());
    }

    private static void writeIndex(File file, List<File> converted, List<Boolean> packed) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeBytes("SAI2");
        out.writeShort(converted.size());
        for (int i = 0; i < converted.size(); i++) {
            String name = converted.get(i).getName();
            out.writeUTF(name.substring(0, name.length() - 4));
            out.writeBoolean(packed.get(i).booleanValue());
        }
        out.close();
        FileIO.write(file, bytes.toByteArray());
    }

    private static final class Box {
        int x, y, w, h;
        Item item;
        Box(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }
    }

    private static final class Key {
        final int width, height;
        final int[] pixels;
        final int hash;
        Key(int width, int height, int[] pixels) {
            this.width = width; this.height = height; this.pixels = pixels;
            this.hash = (31 * width + height) * 31 + Arrays.hashCode(pixels);
        }
        public int hashCode() { return hash; }
        public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key)other;
            return width == key.width && height == key.height && Arrays.equals(pixels, key.pixels);
        }
    }

    private static final class Item {
        final Box source;
        int x, y;
        Item(Box source) { this.source = source; }
    }

    private static final class Plan {
        final List<Box> boxes;
        final List<Item> items;
        final int width, height;
        Plan(List<Box> boxes, List<Item> items, int width, int height) {
            this.boxes = boxes; this.items = items; this.width = width; this.height = height;
        }
    }
}
