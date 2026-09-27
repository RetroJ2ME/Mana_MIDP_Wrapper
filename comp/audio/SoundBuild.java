package audio;

import doja.tools.io.FileIO;
import doja.tools.scratchpad.SoundConverter;
import doja.tools.scratchpad.SoundIndex;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 使 DoJa 音效封存檔改寫為 SND 標記。
 *
 * 遊戲邏輯本身已將音訊分為兩大類別：
 * - BGM ID 0..86 -> 對應 0.bgm..28.bgm，使用 presenter port 0
 * - SFX ID 87..140 -> 對應 se.dat 中的 54 個項目，使用 presenter port 1
 *
 */
public final class SoundBuild {
    private static final int BGM_ARCHIVES = 29;
    private static final int SFX_COUNT = 54;

    private SoundBuild() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: SoundBuild <game-dir> <generated-assets>");
        }
        File gameDir = new File(args[0]);
        File assets = new File(args[1]);
        if (!gameDir.isDirectory()) throw new IOException("missing game dir: " + gameDir);
        if (!assets.isDirectory() && !assets.mkdirs()) throw new IOException("cannot create " + assets);

        List<SoundIndex.Entry> index = new ArrayList<SoundIndex.Entry>();
        buildBgm(gameDir, assets, index);
        buildSfx(gameDir, assets, index);
        SoundIndex.write(new File(assets, "sound-index.bin"), index);
        System.out.println("SoundBuild: BGM=" + BGM_ARCHIVES + ", SFX=" + SFX_COUNT
                + " (SFX forced to WAV), index=" + index.size());
    }

    private static void buildBgm(File gameDir, File assets, List<SoundIndex.Entry> index)
            throws Exception {
        for (int i = 0; i < BGM_ARCHIVES; i++) {
            File archiveFile = new File(gameDir, i + ".bgm");
            byte[][] archive = readArchive(archiveFile);
            if (archive.length != 1) {
                throw new IOException(archiveFile + ": expected one BGM payload, got " + archive.length);
            }
            requireMld(archiveFile, archive[0]);
            String stem = "audio/bgm/" + i;
            SoundConverter.Result converted = SoundConverter.convert(
                    archive[0], new File(assets, stem), false);
            String resourceStem = "/assets/" + stem;
            index.add(converted.indexEntry(resourceStem));
            archive[0] = token(resourceStem + converted.primarySuffix());
            FileIO.write(archiveFile, writeArchive(archive));
        }
    }

    private static void buildSfx(File gameDir, File assets, List<SoundIndex.Entry> index)
            throws Exception {
        File archiveFile = new File(gameDir, "se.dat");
        byte[][] archive = readArchive(archiveFile);
        if (archive.length != SFX_COUNT) {
            throw new IOException(archiveFile + ": expected " + SFX_COUNT + " SFX payloads, got " + archive.length);
        }
        for (int i = 0; i < archive.length; i++) {
            requireMld(archiveFile, archive[i]);
            String number = i < 10 ? "0" + i : String.valueOf(i);
            String stem = "audio/sfx/" + number;
            SoundConverter.Result converted = SoundConverter.convert(
                    archive[i], new File(assets, stem), true);
            if (converted.suffixes.length != 1 || !".wav".equals(converted.primarySuffix())) {
                throw new IOException("SFX " + i + " did not resolve to one WAV");
            }
            String resourceStem = "/assets/" + stem;
            index.add(converted.indexEntry(resourceStem));
            archive[i] = token(resourceStem + converted.primarySuffix());
        }
        FileIO.write(archiveFile, writeArchive(archive));
    }

    /** The game's b.a(int) format: N little-endian u32 offsets, where offset[0] == N*4. */
    private static byte[][] readArchive(File file) throws IOException {
        byte[] data = FileIO.read(file);
        if (data.length < 4) throw new IOException(file + ": archive too small");
        int first = le32(data, 0);
        if (first < 4 || (first & 3) != 0 || first > data.length) {
            throw new IOException(file + ": invalid first offset " + first);
        }
        int count = first / 4;
        byte[][] result = new byte[count][];
        int previous = first;
        for (int i = 0; i < count; i++) {
            int start = le32(data, i * 4);
            int end = i + 1 < count ? le32(data, (i + 1) * 4) : data.length;
            if (start != previous || end < start || end > data.length) {
                throw new IOException(file + ": invalid offset table at entry " + i);
            }
            byte[] payload = new byte[end - start];
            System.arraycopy(data, start, payload, 0, payload.length);
            result[i] = payload;
            previous = end;
        }
        return result;
    }

    private static byte[] writeArchive(byte[][] entries) throws IOException {
        int header = entries.length * 4;
        long total = header;
        for (int i = 0; i < entries.length; i++) total += entries[i].length;
        if (total > Integer.MAX_VALUE) throw new IOException("sound archive too large");
        byte[] out = new byte[(int)total];
        int p = header;
        for (int i = 0; i < entries.length; i++) {
            putLe32(out, i * 4, p);
            System.arraycopy(entries[i], 0, out, p, entries[i].length);
            p += entries[i].length;
        }
        return out;
    }

    private static byte[] token(String path) throws Exception {
        return ("SND:" + path + "\n").getBytes("ISO-8859-1");
    }

    private static void requireMld(File file, byte[] data) throws IOException {
        if (data.length < 4 || data[0] != 'm' || data[1] != 'e' || data[2] != 'l' || data[3] != 'o') {
            throw new IOException(file + ": expected MLD/melo payload");
        }
    }

    private static int le32(byte[] data, int p) throws IOException {
        if (p < 0 || p + 4 > data.length) throw new IOException("truncated little-endian u32");
        return (data[p] & 255) | ((data[p + 1] & 255) << 8)
                | ((data[p + 2] & 255) << 16) | ((data[p + 3] & 255) << 24);
    }

    private static void putLe32(byte[] data, int p, int value) {
        data[p] = (byte)value;
        data[p + 1] = (byte)(value >>> 8);
        data[p + 2] = (byte)(value >>> 16);
        data[p + 3] = (byte)(value >>> 24);
    }
}
