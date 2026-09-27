package translation;

import doja.tools.translation.TsvCodec;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;

final class GameTranslations {
    static final String HEADER = "Source\tLocation\tOriginal\tTranslation";

    static final class Row {
        final String source;
        final String location;
        final String original;
        final String translation;

        Row(String source, String location, String original, String translation) {
            this.source = source;
            this.location = location;
            this.original = original;
            this.translation = translation;
        }
    }

    private final LinkedHashMap<String,Row> rows;

    private GameTranslations(LinkedHashMap<String,Row> rows) {
        this.rows = rows;
    }

    static GameTranslations read(File file) throws IOException {
        if (!file.isFile()) return new GameTranslations(new LinkedHashMap<String,Row>());
        LinkedHashMap<String,Row> rows = new LinkedHashMap<String,Row>();
        BufferedReader reader = TsvCodec.reader(file);
        try {
            String header = TsvCodec.stripBom(reader.readLine());
            if (!HEADER.equals(header)) throw new IOException(file + ": expected " + HEADER);
            String line;
            int number = 1;
            while ((line = reader.readLine()) != null) {
                number++;
                if (line.length() == 0) continue;
                String[] columns = TsvCodec.splitExact(line, 4, file, number);
                Row row = new Row(columns[0], columns[1],
                        TsvCodec.unescape(columns[2], file, number),
                        TsvCodec.unescape(columns[3], file, number));
                String key = key(row.source, row.location);
                if (rows.put(key, row) != null) throw new IOException(file + ":" + number
                        + ": duplicate source location " + row.source + " " + row.location);
            }
        } finally {
            reader.close();
        }
        return new GameTranslations(rows);
    }

    String resolve(String source, String location, String original) throws IOException {
        Row row = rows.get(key(source, location));
        if (row == null || row.translation.length() == 0) return original;
        if (!row.original.equals(original)) throw new IOException(source + " " + location
                + ": translation base text no longer matches the original package");
        return row.translation;
    }

    int translatedCount() {
        int count = 0;
        for (Row row : rows.values()) {
            if (row.translation.length() != 0 && !row.translation.equals(row.original)) count++;
        }
        return count;
    }

    void write(File file, List<Row> current) throws IOException {
        LinkedHashMap<String,Row> available = new LinkedHashMap<String,Row>();
        for (int i = 0; i < current.size(); i++) {
            Row source = current.get(i);
            Row old = rows.get(key(source.source, source.location));
            String translation = old == null ? "" : old.translation;
            if (old != null && !old.original.equals(source.original)) throw new IOException(
                    source.source + " " + source.location + ": original text changed");
            available.put(key(source.source, source.location), new Row(
                    source.source, source.location, source.original, translation));
        }
        for (String old : rows.keySet()) {
            if (!available.containsKey(old)) throw new IOException(file
                    + ": translation location not present in the base package: " + old);
        }

        BufferedWriter writer = TsvCodec.writer(file);
        try {
            writer.write(HEADER);
            writer.write('\n');
            for (Row row : available.values()) {
                writer.write(row.source);
                writer.write('\t');
                writer.write(row.location);
                writer.write('\t');
                writer.write(TsvCodec.escape(row.original));
                writer.write('\t');
                writer.write(TsvCodec.escape(row.translation));
                writer.write('\n');
            }
        } finally {
            writer.close();
        }
    }

    private static String key(String source, String location) {
        return source + '\u0000' + location;
    }
}
