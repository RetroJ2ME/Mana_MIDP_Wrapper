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

package translation;

import doja.tools.classfile.ClassFile;
import doja.tools.font.FontUsage;
import doja.tools.io.FileIO;
import doja.tools.translation.TextOccurrence;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class TranslationSupport {
    static final String CODE_STR = "code_str.dat";
    static final String FONT_ONLY_TEXT = "font.txt";
    static final int CODE_STRING_COUNT = 389;
    static final int SP_BLOCK_SIZE = 4096;

    private TranslationSupport() {}

    static final class ResourceText {
        final String kind;
        final String file;
        final int location;
        final String text;
        final String detail;

        ResourceText(String kind, String file, int location, String text) {
            this(kind, file, location, text, null);
        }

        ResourceText(String kind, String file, int location, String text, String detail) {
            this.kind = kind;
            this.file = file;
            this.location = location;
            this.text = text;
            this.detail = detail;
        }
    }

    static final class TextFile {
        final boolean bom;
        final String charset;
        final List<String> pages;

        TextFile(boolean bom, String charset, List<String> pages) {
            this.bom = bom;
            this.charset = charset;
            this.pages = pages;
        }
    }

    static List<TextOccurrence> extractClassText(File jar) throws IOException {
        ZipFile zip = new ZipFile(jar);
        try {
            List<String> names = new ArrayList<String>();
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.endsWith(".class") && !name.startsWith("META-INF/")) names.add(name);
            }
            Collections.sort(names);
            List<TextOccurrence> result = new ArrayList<TextOccurrence>();
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                appendClassText(result, name, readZip(zip, name));
            }
            return result;
        } finally {
            zip.close();
        }
    }

    static List<TextOccurrence> extractClassTextDirectory(File classes) throws IOException {
        List<String> names = new ArrayList<String>();
        collectClassNames(classes, classes, names);
        Collections.sort(names);
        List<TextOccurrence> result = new ArrayList<TextOccurrence>();
        for (int i = 0; i < names.size(); i++) {
            appendClassText(result, names.get(i), FileIO.read(new File(classes, names.get(i))));
        }
        return result;
    }

    private static void appendClassText(List<TextOccurrence> result, String name, byte[] bytes) throws IOException {
        ClassFile cls = ClassFile.read(bytes);
        List<Integer> indices = cls.stringConstants();
        for (int i = 0; i < indices.size(); i++) {
            int index = indices.get(i).intValue();
            String text = cls.string(index);
            if (isClassText(text)) {
                result.add(new TextOccurrence("class", name, index, text));
            }
        }
    }

    private static final Set<String> ASCII_UI_TEXT = new HashSet<String>(Arrays.asList(
            new String[] { "(C)SQUARE ENIX", "KOICHI ISHII", "Presents", "NO", "YES", "<<", ">>" }));

    private static boolean isClassText(String text) {
        if (text == null || text.length() == 0) return false;
        if (ASCII_UI_TEXT.contains(text) || containsVisibleSymbol(text)) return true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x3040 && c <= 0x30ff) || (c >= 0x3400 && c <= 0x9fff)
                    || (c >= 0xf900 && c <= 0xfaff) || (c >= 0xff00 && c <= 0xffef)) return true;
        }
        return false;
    }

    private static boolean containsVisibleSymbol(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 0x7f) continue;
            int type = Character.getType(c);
            if (type == Character.OTHER_SYMBOL || type == Character.MATH_SYMBOL) return true;
        }
        return false;
    }

    static List<ResourceText> extractResourcesFromJar(File jar) throws IOException {
        ZipFile zip = new ZipFile(jar);
        try {
            List<ResourceText> result = new ArrayList<ResourceText>();
            appendCodeStrings(result, readZip(zip, CODE_STR));
            List<String> textFiles = textNames(zip);
            for (int i = 0; i < textFiles.size(); i++) {
                String name = textFiles.get(i);
                appendTextFile(result, name, readZip(zip, name));
            }
            String font = ScpText.font(readZip(zip, FONT_ONLY_TEXT));
            List<String> scripts = ScpText.scriptNames(zip);
            for (int i = 0; i < scripts.size(); i++) {
                String name = scripts.get(i);
                appendScpText(result, name, readZip(zip, name), font);
            }
            return result;
        } finally {
            zip.close();
        }
    }

    static List<ResourceText> extractResourcesFromDirectory(File classes) throws IOException {
        List<ResourceText> result = new ArrayList<ResourceText>();
        appendCodeStrings(result, FileIO.read(new File(classes, CODE_STR)));
        List<File> textFiles = textFiles(classes);
        for (int i = 0; i < textFiles.size(); i++) {
            File file = textFiles.get(i);
            appendTextFile(result, file.getName(), FileIO.read(file));
        }
        String font = ScpText.font(FileIO.read(new File(classes, FONT_ONLY_TEXT)));
        List<File> scripts = ScpText.scriptFiles(classes);
        for (int i = 0; i < scripts.size(); i++) {
            File file = scripts.get(i);
            appendScpText(result, file.getName(), FileIO.read(file), font);
        }
        return result;
    }

    static List<ResourceText> extractScratchpadText(File assets) throws IOException {
        File logical = new File(assets.getParentFile().getParentFile(), "scratchpad.bin");
        if (!logical.isFile()) throw new IOException("missing " + logical);
        byte[] data = FileIO.read(logical);
        List<ResourceText> result = new ArrayList<ResourceText>();
        int p = 0;
        while (p + 1 < data.length) {
            if (!isJapaneseCore(u16le(data, p))) {
                p += 2;
                continue;
            }
            int start = p;
            int japanese = 0;
            int kana = 0;
            StringBuilder text = new StringBuilder();
            while (p + 1 < data.length) {
                int cp = u16le(data, p);
                if (!isTextCodeUnit(cp)) break;
                if (isJapaneseCore(cp)) japanese++;
                if (isKana(cp)) kana++;
                text.append((char)cp);
                p += 2;
            }
            if (japanese >= 3 && kana >= 1 && text.length() >= 5) {
                result.add(new ResourceText("SP", "scratchpad.bin", start, text.toString()));
            } else if (p == start) {
                p += 2;
            }
        }
        return result;
    }

    static void applyResourceTranslations(File classes, File assets, GameTranslations translations,
            List<doja.tools.translation.ResolvedText> resolved, FontUsage usage) throws Exception {
        applyCodeStrings(new File(classes, CODE_STR), translations, resolved, usage);
        List<File> textFiles = textFiles(classes);
        for (int i = 0; i < textFiles.size(); i++) {
            File file = textFiles.get(i);
            applyTextFile(file, file.getName(), translations, resolved, usage);
        }
        ScpText.applyDirectory(classes, translations, resolved, usage);
        applyScratchpad(assets, translations, resolved, usage);
        addFontOnlyText(new File(classes, FONT_ONLY_TEXT), usage);
    }

    private static void applyCodeStrings(File file, GameTranslations translations,
            List<doja.tools.translation.ResolvedText> resolved, FontUsage usage) throws Exception {
        List<String> strings = decodeCodeStrings(FileIO.read(file));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < strings.size(); i++) {
            String source = strings.get(i);
            String value = translations.resolve("DAT", CODE_STR + ":row" + i, source);
            if (value.length() > 255) {
                throw new IOException(CODE_STR + " row " + i + ": translated UTF-16 length exceeds 255");
            }
            assertBmp(value, CODE_STR + " row " + i);
            out.write(value.length());
            writeUtf16Le(out, value);
            resolved.add(new doja.tools.translation.ResolvedText("dat", CODE_STR, i, value));
            usage.addRenderText(value);
        }
        FileIO.write(file, out.toByteArray());
    }

    private static void applyTextFile(File file, String name, GameTranslations translations,
            List<doja.tools.translation.ResolvedText> resolved, FontUsage usage) throws Exception {
        TextFile textFile = parseTextFile(FileIO.read(file));
        List<String> replaced = new ArrayList<String>(textFile.pages.size());
        for (int i = 0; i < textFile.pages.size(); i++) {
            String source = textFile.pages.get(i);
            String value = worthExporting(source) ? translations.resolve(
                    "TXT", name + ":page" + (i + 1), source) : source;
            if (value.indexOf('#') >= 0) {
                throw new IOException(name + " page " + (i + 1) + ": translation must not contain #");
            }
            assertBmp(value, name + " page " + (i + 1));
            replaced.add(value);
            if (worthExporting(source)) {
                resolved.add(new doja.tools.translation.ResolvedText("txt", name, i, value));
            }
            usage.addRenderText(value);
        }
        FileIO.write(file, encodeTextFile(new TextFile(textFile.bom, textFile.charset, replaced)));
    }

    private static void applyScratchpad(File assets, GameTranslations translations,
            List<doja.tools.translation.ResolvedText> resolved, FontUsage usage) throws Exception {
        List<ResourceText> entries = extractScratchpadText(assets);
        if (entries.isEmpty()) return;
        File build = assets.getParentFile().getParentFile();
        File logicalFile = new File(build, "scratchpad.bin");
        byte[] data = FileIO.read(logicalFile);
        for (int i = 0; i < entries.size(); i++) {
            ResourceText entry = entries.get(i);
            String value = translations.resolve("SP",
                    entry.file + ":@" + entry.location, entry.text);
            assertBmp(value, "scratchpad @" + entry.location);
            if (value.length() != entry.text.length()) {
                throw new IOException("scratchpad @" + entry.location
                        + ": translation must keep the same UTF-16 code-unit length ("
                        + entry.text.length() + " -> " + value.length() + ")");
            }
            byte[] encoded = utf16Le(value);
            System.arraycopy(encoded, 0, data, entry.location, encoded.length);
            resolved.add(new doja.tools.translation.ResolvedText("sp", entry.file, entry.location, value));
            usage.addRenderText(value);
        }
        FileIO.write(logicalFile, data);
        rewriteScratchpadBlocks(assets, data);
    }

    static void verifyDat(byte[] bytes, int row, String expected) throws IOException {
        List<String> strings = decodeCodeStrings(bytes);
        if (row < 0 || row >= strings.size()) throw new IOException("code_str row outside range: " + row);
        if (!expected.equals(strings.get(row))) {
            throw new IOException("code_str row " + row + ": expected " + expected + ", got " + strings.get(row));
        }
    }

    static void verifyTxt(byte[] bytes, String file, int page, String expected) throws IOException {
        TextFile parsed = parseTextFile(bytes);
        if (page < 0 || page >= parsed.pages.size()) throw new IOException(file + ": page outside range: " + page);
        if (!expected.equals(parsed.pages.get(page))) {
            throw new IOException(file + " page " + (page + 1) + ": expected " + expected
                    + ", got " + parsed.pages.get(page));
        }
    }

    static void verifySp(byte[] logical, int offset, String expected) throws IOException {
        byte[] encoded = utf16Le(expected);
        if (offset < 0 || offset + encoded.length > logical.length) {
            throw new IOException("scratchpad translation outside range @" + offset);
        }
        for (int i = 0; i < encoded.length; i++) {
            if (logical[offset + i] != encoded[i]) {
                throw new IOException("scratchpad translation mismatch @" + offset);
            }
        }
    }

    static byte[] reconstructScratchpadFromJar(ZipFile jar) throws IOException {
        byte[] meta = readZip(jar, "assets/sp/meta.bin");
        if (meta.length < 16 || meta[0] != 'S' || meta[1] != 'P' || meta[2] != 'B' || meta[3] != 'M') {
            throw new IOException("invalid assets/sp/meta.bin");
        }
        int logicalSize = be32(meta, 4);
        int blockSize = be32(meta, 8);
        int count = be32(meta, 12);
        if (blockSize != SP_BLOCK_SIZE || meta.length != 16 + count) {
            throw new IOException("unexpected scratchpad block metadata");
        }
        byte[] result = new byte[logicalSize];
        for (int i = 0; i < count; i++) {
            if (meta[16 + i] == 0) continue;
            String name = "assets/sp/b" + four(i) + ".bin";
            byte[] block = readZip(jar, name);
            int start = i * blockSize;
            int expected = Math.min(blockSize, logicalSize - start);
            if (block.length != expected) throw new IOException(name + ": unexpected length " + block.length);
            System.arraycopy(block, 0, result, start, block.length);
        }
        return result;
    }

    private static void appendCodeStrings(List<ResourceText> result, byte[] data) throws IOException {
        List<String> strings = decodeCodeStrings(data);
        for (int i = 0; i < strings.size(); i++) {
            String text = strings.get(i);
            if (text.length() != 0) result.add(new ResourceText("DAT", CODE_STR, i, text));
        }
    }

    private static List<String> decodeCodeStrings(byte[] data) throws IOException {
        List<String> result = new ArrayList<String>();
        int p = 0;
        while (p < data.length) {
            int chars = data[p++] & 255;
            int bytes = chars * 2;
            if (p + bytes > data.length) throw new IOException(CODE_STR + ": truncated row " + result.size());
            result.add(new String(data, p, bytes, "UTF-16LE"));
            p += bytes;
        }
        if (result.size() != CODE_STRING_COUNT) {
            throw new IOException(CODE_STR + ": expected " + CODE_STRING_COUNT + " rows, got " + result.size());
        }
        return result;
    }

    private static void appendTextFile(List<ResourceText> result, String name, byte[] data) throws IOException {
        TextFile file = parseTextFile(data);
        for (int i = 0; i < file.pages.size(); i++) {
            String page = file.pages.get(i);
            if (worthExporting(page)) result.add(new ResourceText("TXT", name, i, page));
        }
    }

    private static void appendScpText(List<ResourceText> result, String name,
            byte[] data, String font) throws IOException {
        List<ScpText.Entry> entries = ScpText.scan(data, font, name);
        for (int i = 0; i < entries.size(); i++) {
            ScpText.Entry entry = entries.get(i);
            result.add(new ResourceText("SCP", name, entry.ordinal, entry.source, entry.location()));
        }
    }

    private static boolean worthExporting(String text) {
        if (text == null || text.length() == 0) return false;
        boolean visible = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isJapaneseCore(c) || Character.isLetterOrDigit(c)) visible = true;
        }
        return visible;
    }

    static TextFile parseTextFile(byte[] data) throws IOException {
        boolean bom = data.length >= 2 && (((data[0] & 255) == 0xFF && (data[1] & 255) == 0xFE)
                || ((data[0] & 255) == 0xFE && (data[1] & 255) == 0xFF));
        String charset = detectTextCharset(data, bom);
        int start = bom ? 2 : 0;
        String text = decode(data, start, charset);
        List<String> pages = new ArrayList<String>();
        int p = 0;
        int pageStart = 0;
        while (p < text.length()) {
            if (text.charAt(p) == '#') {
                pages.add(text.substring(pageStart, p));
                pageStart = ++p;
            } else {
                p++;
            }
        }
        pages.add(text.substring(pageStart));
        return new TextFile(bom, charset, pages);
    }

    private static byte[] encodeTextFile(TextFile file) throws IOException {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < file.pages.size(); i++) {
            if (i != 0) text.append('#');
            text.append(file.pages.get(i));
        }
        byte[] body = encode(text.toString(), file.charset);
        if (!file.bom) return body;
        byte[] result = new byte[body.length + 2];
        boolean little = "UTF-16LE".equals(file.charset);
        result[0] = (byte)(little ? 0xFF : 0xFE);
        result[1] = (byte)(little ? 0xFE : 0xFF);
        System.arraycopy(body, 0, result, 2, body.length);
        return result;
    }

    private static String detectTextCharset(byte[] data, boolean bom) throws IOException {
        if (bom) return (data[0] & 255) == 0xFF ? "UTF-16LE" : "UTF-16BE";
        String shiftJis = decodeOrNull(data, 0, "Shift_JIS");
        int shiftJisScore = textScore(shiftJis);
        if ((data.length & 1) == 0) {
            String little = decodeOrNull(data, 0, "UTF-16LE");
            String big = decodeOrNull(data, 0, "UTF-16BE");
            int littleScore = textScore(little);
            int bigScore = textScore(big);
            if (littleScore > shiftJisScore && littleScore >= bigScore) return "UTF-16LE";
            if (bigScore > shiftJisScore && bigScore > littleScore) return "UTF-16BE";
        }
        if (shiftJis != null) return "Shift_JIS";
        throw new IOException("text resource is neither UTF-16 nor Shift-JIS");
    }

    private static int textScore(String text) {
        if (text == null || text.length() == 0) return -1;
        int score = 0;
        int invalid = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isKana(c)) score += 8;
            else if (isJapaneseCore(c)) score += 4;
            else if ((c >= 0x20 && c <= 0x7e) || (c >= 0x2000 && c <= 0x30ff)) score++;
            else if (c < 0x20 && c != '\r' && c != '\n' && c != '\t') invalid++;
            else if (Character.isSurrogate(c) || c == 0xffff) invalid++;
        }
        if (invalid != 0) return -1;
        return score;
    }

    private static String decode(byte[] data, int start, String charset) throws IOException {
        String text = decodeOrNull(Arrays.copyOfRange(data, start, data.length), 0, charset);
        if (text == null) throw new IOException("invalid " + charset + " text resource");
        return text;
    }

    private static String decodeOrNull(byte[] data, int start, String charset) {
        CharsetDecoder decoder = Charset.forName(charset).newDecoder();
        decoder.onMalformedInput(CodingErrorAction.REPORT);
        decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer text = decoder.decode(ByteBuffer.wrap(data, start, data.length - start));
            return text.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static byte[] encode(String text, String charset) throws IOException {
        CharsetEncoder encoder = Charset.forName(charset).newEncoder();
        encoder.onMalformedInput(CodingErrorAction.REPORT);
        encoder.onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer encoded = encoder.encode(CharBuffer.wrap(text));
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            return result;
        } catch (CharacterCodingException e) {
            throw new IOException("text cannot be encoded as " + charset, e);
        }
    }

    private static List<String> textNames(ZipFile jar) {
        List<String> names = new ArrayList<String>();
        java.util.Enumeration<? extends ZipEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.endsWith(".txt") && !FONT_ONLY_TEXT.equals(name)) names.add(name);
        }
        Collections.sort(names);
        return names;
    }

    private static List<File> textFiles(File directory) throws IOException {
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("cannot list " + directory);
        List<File> texts = new ArrayList<File>();
        for (int i = 0; i < files.length; i++) {
            if (files[i].isFile() && files[i].getName().endsWith(".txt")
                    && !FONT_ONLY_TEXT.equals(files[i].getName())) texts.add(files[i]);
        }
        Collections.sort(texts, new Comparator<File>() {
            public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        return texts;
    }

    private static void addFontOnlyText(File file, FontUsage usage) throws IOException {
        if (!file.isFile()) return;
        usage.addRenderText(ScpText.font(FileIO.read(file)));
    }

    private static void rewriteScratchpadBlocks(File assets, byte[] data) throws IOException {
        File sp = new File(assets, "sp");
        byte[] meta = FileIO.read(new File(sp, "meta.bin"));
        if (meta.length < 16 || meta[0] != 'S' || meta[1] != 'P' || meta[2] != 'B' || meta[3] != 'M') {
            throw new IOException("invalid scratchpad meta.bin");
        }
        int logicalSize = be32(meta, 4);
        int blockSize = be32(meta, 8);
        int count = be32(meta, 12);
        if (logicalSize != data.length || blockSize != SP_BLOCK_SIZE || meta.length != 16 + count) {
            throw new IOException("scratchpad metadata does not match logical image");
        }
        for (int i = 0; i < count; i++) {
            if (meta[16 + i] == 0) continue;
            int start = i * blockSize;
            int length = Math.min(blockSize, data.length - start);
            byte[] block = new byte[length];
            System.arraycopy(data, start, block, 0, length);
            FileIO.write(new File(sp, "b" + four(i) + ".bin"), block);
        }
    }

    private static void collectClassNames(File root, File file, List<String> names) {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return;
            Arrays.sort(children, new Comparator<File>() {
                public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
            });
            for (int i = 0; i < children.length; i++) collectClassNames(root, children[i], names);
        } else if (file.getName().endsWith(".class")) {
            String relative = root.toURI().relativize(file.toURI()).getPath();
            names.add(relative);
        }
    }

    private static byte[] readZip(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IOException("missing JAR resource " + name);
        InputStream in = zip.getInputStream(entry);
        try {
            return FileIO.read(in);
        } finally {
            in.close();
        }
    }

    private static void assertBmp(String text, String label) throws IOException {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) || Character.isLowSurrogate(c)) {
                throw new IOException(label + ": MIDP bitmap font supports BMP text only");
            }
        }
    }

    private static byte[] utf16Le(String text) throws IOException {
        assertBmp(text, "UTF-16 text");
        return text.getBytes("UTF-16LE");
    }

    private static void writeUtf16Le(ByteArrayOutputStream out, String text) throws IOException {
        out.write(utf16Le(text));
    }

    private static int u16le(byte[] data, int p) {
        return (data[p] & 255) | ((data[p + 1] & 255) << 8);
    }

    private static int be32(byte[] data, int p) throws IOException {
        if (p < 0 || p + 4 > data.length) throw new IOException("truncated big-endian i32");
        return ((data[p] & 255) << 24) | ((data[p + 1] & 255) << 16)
                | ((data[p + 2] & 255) << 8) | (data[p + 3] & 255);
    }

    private static boolean isJapaneseCore(int c) {
        return isKana(c) || (c >= 0x3400 && c <= 0x9fff)
                || (c >= 0xf900 && c <= 0xfaff);
    }

    private static boolean isKana(int c) {
        return (c >= 0x3040 && c <= 0x30ff) || (c >= 0xff66 && c <= 0xff9f);
    }

    private static boolean isTextCodeUnit(int c) {
        if (isJapaneseCore(c)) return true;
        if (c == 0x3000 || c == 0x3001 || c == 0x3002 || c == 0x30FB || c == 0x30FC) return true;
        if (c >= 0x20 && c <= 0x7E) return true;
        return c >= 0x2000 && c <= 0x206F;
    }

    private static String four(int value) {
        String text = String.valueOf(value);
        while (text.length() < 4) text = "0" + text;
        return text;
    }
}
