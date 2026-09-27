package translation;

import doja.tools.io.FileIO;
import doja.tools.translation.ClassText;
import doja.tools.translation.ResolvedText;
import doja.tools.translation.ResolvedTextTable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class TranslationVerifier {
    private TranslationVerifier() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: TranslationVerifier <raw.jar> <resolved.tsv> <config.properties> <generated-assets>");
        }
        File jarFile = new File(args[0]);
        List<ResolvedText> resolved = ResolvedTextTable.read(new File(args[1]));
        File config = new File(args[2]);
        if (!config.isFile()) throw new IOException("missing " + config);

        ZipFile jar = new ZipFile(jarFile);
        try {
            Map<String,byte[]> cache = new HashMap<String,byte[]>();
            Map<String,List<ScpText.Entry>> scripts = new HashMap<String,List<ScpText.Entry>>();
            byte[] logicalSp = null;
            String font = null;
            int classes = 0, dat = 0, txt = 0, scp = 0, sp = 0;
            for (int i = 0; i < resolved.size(); i++) {
                ResolvedText entry = resolved.get(i);
                if ("class".equals(entry.kind)) {
                    byte[] bytes = resource(jar, cache, entry.file);
                    ClassText.verify(bytes, entry.location, entry.text,
                            entry.file + ":#" + entry.location);
                    classes++;
                } else if ("dat".equals(entry.kind)) {
                    TranslationSupport.verifyDat(resource(jar, cache, entry.file), entry.location, entry.text);
                    dat++;
                } else if ("txt".equals(entry.kind)) {
                    TranslationSupport.verifyTxt(resource(jar, cache, entry.file), entry.file,
                            entry.location, entry.text);
                    txt++;
                } else if ("scp".equals(entry.kind)) {
                    if (font == null) font = ScpText.font(resource(jar, cache, "font.txt"));
                    List<ScpText.Entry> entries = scripts.get(entry.file);
                    if (entries == null) {
                        entries = ScpText.scan(resource(jar, cache, entry.file), font, entry.file);
                        scripts.put(entry.file, entries);
                    }
                    if (entry.location < 0 || entry.location >= entries.size()
                            || !entry.text.equals(entries.get(entry.location).source)) {
                        throw new IOException(entry.file + ": SCP text " + entry.location + " mismatch");
                    }
                    scp++;
                } else if ("sp".equals(entry.kind)) {
                    if (logicalSp == null) logicalSp = TranslationSupport.reconstructScratchpadFromJar(jar);
                    TranslationSupport.verifySp(logicalSp, entry.location, entry.text);
                    sp++;
                } else {
                    throw new IOException("unknown resolved kind " + entry.kind);
                }
            }
            System.out.println("TranslationVerifier: class=" + classes + ", dat=" + dat
                    + ", txt=" + txt + ", scp=" + scp + ", sp=" + sp + " verified");
        } finally {
            jar.close();
        }
    }

    private static byte[] resource(ZipFile jar, Map<String,byte[]> cache, String name) throws IOException {
        byte[] bytes = cache.get(name);
        if (bytes != null) return bytes;
        ZipEntry entry = jar.getEntry(name);
        if (entry == null) throw new IOException("final JAR has no " + name);
        InputStream in = jar.getInputStream(entry);
        try {
            bytes = FileIO.read(in);
        } finally {
            in.close();
        }
        cache.put(name, bytes);
        return bytes;
    }
}
