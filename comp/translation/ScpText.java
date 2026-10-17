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

import doja.tools.font.FontUsage;
import doja.tools.io.FileIO;
import doja.tools.translation.ResolvedText;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ScpText {
    private static final int DIRECTORY_SIZE = 512;
    private static final int CELL_COUNT = 256;
    private static final int TEXT_COMMAND = 19;
    private static final int FIRST_CONTROL = 16300;
    private static final int FIRST_UNUSED_GLYPH = 16300;
    private static final int END = 16302;
    private static final int WAIT = 16305;
    private static final int NAME = 16306;
    private static final int NEWLINE = 16307;

    private ScpText() {}

    static final class Entry {
        final int ordinal;
        final int cell;
        final int function;
        final int command;
        final int length;
        final String source;
        final String rendered;

        Entry(int ordinal, int cell, int function, int command, int length,
                String source, String rendered) {
            this.ordinal = ordinal;
            this.cell = cell;
            this.function = function;
            this.command = command;
            this.length = length;
            this.source = source;
            this.rendered = rendered;
        }

        String location() {
            return "cell" + cell + "/function" + function + "/text" + ordinal;
        }
    }

    private static final class Cell {
        final byte[] data;
        final List<Entry> entries = new ArrayList<Entry>();

        Cell(byte[] data) { this.data = data; }
    }

    private static final class Parsed {
        final byte[] original;
        final Cell[] cells = new Cell[CELL_COUNT];
        final List<Entry> entries = new ArrayList<Entry>();

        Parsed(byte[] original) { this.original = original; }
    }

    private static final class Replacement {
        final Entry entry;
        final byte[] payload;

        Replacement(Entry entry, byte[] payload) {
            this.entry = entry;
            this.payload = payload;
        }

        int delta() { return payload.length - entry.length; }
        int oldEnd() { return entry.command + 3 + entry.length; }
    }

    private static final class FontTable {
        final StringBuilder text;
        final Map<Character,Integer> indices = new HashMap<Character,Integer>();

        FontTable(String original) {
            text = new StringBuilder(original);
            for (int i = 0; i < original.length(); i++) {
                Character c = Character.valueOf(original.charAt(i));
                if (!indices.containsKey(c)) indices.put(c, Integer.valueOf(i));
            }
        }

        int index(char c) throws IOException {
            if (Character.isHighSurrogate(c) || Character.isLowSurrogate(c) || c < 0x20) {
                throw new IOException("SCP text contains an unsupported character U+"
                        + Integer.toHexString(c).toUpperCase());
            }
            Integer existing = indices.get(Character.valueOf(c));
            if (existing != null) return existing.intValue();
            if (text.length() >= FIRST_UNUSED_GLYPH) throw new IOException("font.txt has no free SCP glyph indices");
            int index = text.length();
            text.append(c);
            indices.put(Character.valueOf(c), Integer.valueOf(index));
            return index;
        }
    }

    static String font(byte[] data) throws IOException {
        if ((data.length & 1) != 0) throw new IOException("font.txt has odd UTF-16LE length");
        return new String(data, "UTF-16LE");
    }

    static List<Entry> scan(byte[] data, String font, String name) throws IOException {
        return parse(data, font, name).entries;
    }

    static void applyDirectory(File classes, GameTranslations translations,
            List<ResolvedText> resolved, FontUsage usage) throws IOException {
        File fontFile = new File(classes, "font.txt");
        String originalFont = font(FileIO.read(fontFile));
        FontTable fontTable = new FontTable(originalFont);
        List<File> files = scriptFiles(classes);
        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            Parsed parsed = parse(FileIO.read(file), originalFont, file.getName());
            List<Replacement> replacements = new ArrayList<Replacement>();
            for (int j = 0; j < parsed.entries.size(); j++) {
                Entry entry = parsed.entries.get(j);
                String value = translations.resolve("SCP",
                        file.getName() + ":" + entry.location(), entry.source);
                String rendered = entry.rendered;
                if (!value.equals(entry.source)) {
                    StringBuilder visible = new StringBuilder();
                    byte[] payload = encode(entry, value, fontTable, visible);
                    replacements.add(new Replacement(entry, payload));
                    rendered = visible.toString();
                }
                usage.addRenderText(rendered);
                resolved.add(new ResolvedText("scp", file.getName(), entry.ordinal, value));
            }
            if (!replacements.isEmpty()) FileIO.write(file, rebuild(parsed, replacements, file.getName()));
        }
        if (fontTable.text.length() != originalFont.length()) {
            FileIO.write(fontFile, fontTable.text.toString().getBytes("UTF-16LE"));
        }
    }

    static List<File> scriptFiles(File classes) throws IOException {
        File[] files = classes.listFiles();
        if (files == null) throw new IOException("cannot list " + classes);
        List<File> scripts = new ArrayList<File>();
        for (int i = 0; i < files.length; i++) {
            if (files[i].isFile() && isScriptName(files[i].getName())) scripts.add(files[i]);
        }
        Collections.sort(scripts, new Comparator<File>() {
            public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        if (scripts.isEmpty()) throw new IOException("no m*.scp scripts in " + classes);
        return scripts;
    }

    static List<String> scriptNames(ZipFile jar) throws IOException {
        List<String> scripts = new ArrayList<String>();
        java.util.Enumeration<? extends ZipEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (isScriptName(name)) scripts.add(name);
        }
        Collections.sort(scripts);
        if (scripts.isEmpty()) throw new IOException("no m*.scp scripts in original game JAR");
        return scripts;
    }

    static boolean isScriptName(String name) {
        if (!name.startsWith("m") || !name.endsWith(".scp")) return false;
        for (int i = 1; i < name.length() - 4; i++) {
            if (name.charAt(i) < '0' || name.charAt(i) > '9') return false;
        }
        return name.length() > 5;
    }

    private static Parsed parse(byte[] data, String font, String name) throws IOException {
        if (data.length < DIRECTORY_SIZE) throw new IOException(name + ": truncated SCP directory");
        Parsed parsed = new Parsed(data);
        int cursor = DIRECTORY_SIZE;
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            int size = u16(data, cell * 2);
            if (size == 0) continue;
            if (size < 28 || cursor + size > data.length) {
                throw new IOException(name + ": invalid cell " + cell + " length " + size);
            }
            byte[] bytes = Arrays.copyOfRange(data, cursor, cursor + size);
            Cell current = new Cell(bytes);
            parsed.cells[cell] = current;
            int base = i32(bytes, 20);
            int functions = u16(bytes, 24);
            if (base < 28 + functions * 16 || base > size) {
                throw new IOException(name + ": invalid function table in cell " + cell);
            }
            int previousEnd = base;
            for (int function = 0; function < functions; function++) {
                int record = 28 + function * 16;
                int nameOffset = i32(bytes, record);
                int bodyOffset = i32(bytes, record + 8);
                int bodyLength = i32(bytes, record + 12);
                long start = (long)base + nameOffset + bodyOffset;
                long end = start + bodyLength;
                if (nameOffset < 0 || bodyOffset < 0 || bodyLength < 0
                        || start < previousEnd || end > size) {
                    throw new IOException(name + ": invalid function " + function + " in cell " + cell);
                }
                previousEnd = (int)end;
                int q = (int)start;
                while (q + 3 <= end) {
                    // 腳本命令緊接在函式起點或前一命令的逗號後，避免把參數中的 19 誤當文本命令。
                    if ((bytes[q] & 255) != TEXT_COMMAND
                            || (q > start && bytes[q - 1] != ',')) {
                        q++;
                        continue;
                    }
                    int length = u16(bytes, q + 1);
                    if (length == 0 || q + 3 + length > end) {
                        q++;
                        continue;
                    }
                    Entry entry = decode(bytes, q, length, font, parsed.entries.size(), cell, function);
                    if (entry == null) {
                        q++;
                        continue;
                    }
                    parsed.entries.add(entry);
                    current.entries.add(entry);
                    q += 3 + length;
                }
            }
            cursor += size;
        }
        if (cursor != data.length) throw new IOException(name + ": SCP directory does not cover the file");
        return parsed;
    }

    private static Entry decode(byte[] cell, int command, int length, String font,
            int ordinal, int cellNumber, int function) {
        int p = command + 3;
        int end = p + length;
        StringBuilder source = new StringBuilder();
        StringBuilder rendered = new StringBuilder();
        while (p < end) {
            int first = cell[p++] & 255;
            int code;
            if ((first & 0xC0) == 0xC0) {
                if (p >= end) return null;
                code = (first & 0x3F) | ((cell[p++] & 255) << 6);
            } else {
                code = first;
            }
            if (code < font.length()) {
                char c = font.charAt(code);
                if (c == '{' || c == '}') return null;
                source.append(c);
                rendered.append(c);
            } else if (code == NEWLINE) {
                source.append('\n');
                rendered.append('\n');
            } else if (code >= FIRST_CONTROL && code <= NEWLINE) {
                int arguments = code == WAIT ? 1 : (code == NAME ? 2 : 0);
                String[] values = new String[arguments];
                for (int i = 0; i < arguments; i++) {
                    int argumentStart = p;
                    while (p < end && cell[p] != ',') p++;
                    if (p >= end) return null;
                    values[i] = ascii(cell, argumentStart, p);
                    if (values[i] == null || values[i].length() == 0) return null;
                    p++;
                }
                String marker = marker(code, values);
                source.append(marker);
            } else {
                return null;
            }
        }
        if (source.length() == 0) return null;
        return new Entry(ordinal, cellNumber, function, command, length,
                source.toString(), rendered.toString());
    }

    private static byte[] encode(Entry entry, String value, FontTable font,
            StringBuilder visible) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '{') {
                int close = value.indexOf('}', i + 1);
                if (close < 0) throw new IOException(entry.location() + ": unclosed SCP control marker");
                writeMarker(out, value.substring(i, close + 1), entry.location());
                i = close;
            } else if (c == '}' || c == '\r') {
                throw new IOException(entry.location() + ": unsupported SCP text character");
            } else if (c == '\n') {
                writeCode(out, NEWLINE);
                visible.append(c);
            } else {
                writeCode(out, font.index(c));
                visible.append(c);
            }
        }
        if (out.size() > 65535) throw new IOException(entry.location() + ": SCP text exceeds 65535 bytes");
        return out.toByteArray();
    }

    private static void writeMarker(ByteArrayOutputStream out, String marker, String location)
            throws IOException {
        if ("{P}".equals(marker)) {
            writeControl(out, NAME, new String[] { "0", "0" });
        } else if ("{H}".equals(marker)) {
            writeControl(out, NAME, new String[] { "1", "0" });
        } else if ("{END}".equals(marker)) {
            writeControl(out, END, new String[0]);
        } else if (marker.startsWith("{WAIT:") && marker.endsWith("}")) {
            String value = marker.substring(6, marker.length() - 1);
            if (!isDecimal(value)) throw new IOException(location + ": invalid wait marker " + marker);
            writeControl(out, WAIT, new String[] { value });
        } else if (marker.startsWith("{CTRL:") && marker.endsWith("}")) {
            String[] fields = split(marker.substring(6, marker.length() - 1), ':');
            if (fields.length == 0 || !isDecimal(fields[0])) {
                throw new IOException(location + ": invalid control marker " + marker);
            }
            int code;
            try {
                code = Integer.parseInt(fields[0]);
            } catch (NumberFormatException e) {
                throw new IOException(location + ": invalid control marker " + marker);
            }
            int arguments = code == WAIT ? 1 : (code == NAME ? 2 : 0);
            if (code < FIRST_CONTROL || code >= NEWLINE || fields.length != arguments + 1) {
                throw new IOException(location + ": invalid control marker " + marker);
            }
            String[] values = new String[arguments];
            for (int i = 0; i < arguments; i++) {
                values[i] = fields[i + 1];
                if (!isAsciiArgument(values[i])) {
                    throw new IOException(location + ": invalid control argument " + marker);
                }
            }
            writeControl(out, code, values);
        } else {
            throw new IOException(location + ": unknown SCP control marker " + marker);
        }
    }

    private static void writeControl(ByteArrayOutputStream out, int code, String[] arguments) {
        writeCode(out, code);
        for (int i = 0; i < arguments.length; i++) {
            String value = arguments[i];
            for (int j = 0; j < value.length(); j++) out.write(value.charAt(j));
            out.write(',');
        }
    }

    private static boolean isDecimal(String value) {
        if (value.length() == 0) return false;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;
        }
        return true;
    }

    private static boolean isAsciiArgument(String value) {
        if (value.length() == 0) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7e || c == ':' || c == '{' || c == '}') return false;
        }
        return true;
    }

    private static String[] split(String value, char separator) {
        List<String> parts = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i <= value.length(); i++) {
            if (i == value.length() || value.charAt(i) == separator) {
                parts.add(value.substring(start, i));
                start = i + 1;
            }
        }
        return parts.toArray(new String[parts.size()]);
    }

    private static String marker(int code, String[] arguments) {
        if (code == END) return "{END}";
        if (code == WAIT) return "{WAIT:" + arguments[0] + "}";
        if (code == NAME && "0".equals(arguments[0]) && "0".equals(arguments[1])) return "{P}";
        if (code == NAME && "1".equals(arguments[0]) && "0".equals(arguments[1])) return "{H}";
        StringBuilder marker = new StringBuilder("{CTRL:").append(code);
        for (int i = 0; i < arguments.length; i++) marker.append(':').append(arguments[i]);
        return marker.append('}').toString();
    }

    private static String ascii(byte[] data, int start, int end) {
        StringBuilder value = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            int c = data[i] & 255;
            if (c < 0x20 || c > 0x7e || c == '{' || c == '}') return null;
            value.append((char)c);
        }
        return value.toString();
    }

    private static byte[] rebuild(Parsed parsed, List<Replacement> replacements, String name) throws IOException {
        Collections.sort(replacements, new Comparator<Replacement>() {
            public int compare(Replacement a, Replacement b) {
                if (a.entry.cell != b.entry.cell) return a.entry.cell - b.entry.cell;
                return a.entry.command - b.entry.command;
            }
        });
        byte[] directory = Arrays.copyOf(parsed.original, DIRECTORY_SIZE);
        ByteArrayOutputStream file = new ByteArrayOutputStream(parsed.original.length);
        file.write(directory, 0, directory.length);
        int next = 0;
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            Cell original = parsed.cells[cell];
            if (original == null) continue;
            List<Replacement> changes = new ArrayList<Replacement>();
            while (next < replacements.size() && replacements.get(next).entry.cell == cell) {
                changes.add(replacements.get(next++));
            }
            byte[] bytes = changes.isEmpty() ? original.data : rebuildCell(original.data, changes, name, cell);
            if (bytes.length > 65535) throw new IOException(name + ": cell " + cell + " exceeds 65535 bytes");
            put16(directory, cell * 2, bytes.length);
            file.write(bytes, 0, bytes.length);
        }
        byte[] result = file.toByteArray();
        System.arraycopy(directory, 0, result, 0, DIRECTORY_SIZE);
        return result;
    }

    private static byte[] rebuildCell(byte[] original, List<Replacement> changes,
            String name, int cell) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(original.length);
        int cursor = 0;
        for (int i = 0; i < changes.size(); i++) {
            Replacement change = changes.get(i);
            if (change.entry.command < cursor) throw new IOException(name + ": overlapping SCP texts in cell " + cell);
            out.write(original, cursor, change.entry.command + 1 - cursor);
            out.write(change.payload.length & 255);
            out.write(change.payload.length >>> 8);
            out.write(change.payload, 0, change.payload.length);
            cursor = change.oldEnd();
        }
        out.write(original, cursor, original.length - cursor);
        byte[] result = out.toByteArray();
        int base = i32(original, 20);
        int functions = u16(original, 24);
        for (int function = 0; function < functions; function++) {
            int record = 28 + function * 16;
            int nameOffset = i32(original, record);
            int bodyLength = i32(original, record + 12);
            int shiftedName = nameOffset;
            int shiftedBody = bodyLength;
            for (int i = 0; i < changes.size(); i++) {
                Replacement change = changes.get(i);
                if (change.oldEnd() <= base + nameOffset) shiftedName += change.delta();
                if (change.entry.function == function) shiftedBody += change.delta();
            }
            put32(result, record, shiftedName);
            put32(result, record + 12, shiftedBody);
        }
        return result;
    }

    private static void writeCode(ByteArrayOutputStream out, int code) {
        if (code < 192) {
            out.write(code);
        } else {
            out.write(0xC0 | (code & 0x3F));
            out.write(code >>> 6);
        }
    }

    private static int u16(byte[] data, int p) {
        return (data[p] & 255) | ((data[p + 1] & 255) << 8);
    }

    private static int i32(byte[] data, int p) {
        return (data[p] & 255) | ((data[p + 1] & 255) << 8)
                | ((data[p + 2] & 255) << 16) | ((data[p + 3] & 255) << 24);
    }

    private static void put16(byte[] data, int p, int value) {
        data[p] = (byte)value;
        data[p + 1] = (byte)(value >>> 8);
    }

    private static void put32(byte[] data, int p, int value) {
        data[p] = (byte)value;
        data[p + 1] = (byte)(value >>> 8);
        data[p + 2] = (byte)(value >>> 16);
        data[p + 3] = (byte)(value >>> 24);
    }
}
