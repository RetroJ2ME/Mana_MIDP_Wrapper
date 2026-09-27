package translation;

import doja.tools.translation.TextIndex;
import doja.tools.translation.TextOccurrence;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class TranslationPrepare {
    private TranslationPrepare() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "Usage: TranslationPrepare <game.jar> <generated-assets> <config.properties> <Index.tsv> <Translation.tsv>");
        }
        File jar = new File(args[0]);
        File assets = new File(args[1]);
        File config = new File(args[2]);
        File index = new File(args[3]);
        File translations = new File(args[4]);
        if (!config.isFile()) throw new IllegalArgumentException("missing " + config);

        List<TextOccurrence> classText = TranslationSupport.extractClassText(jar);
        List<TranslationSupport.ResourceText> resources = TranslationSupport.extractResourcesFromJar(jar);
        List<TranslationSupport.ResourceText> scratchpad = TranslationSupport.extractScratchpadText(assets);
        List<TextIndex.Entry> indexEntries = new ArrayList<TextIndex.Entry>();
        List<GameTranslations.Row> translationRows = new ArrayList<GameTranslations.Row>();

        int c = 0, d = 0, t = 0, p = 0, s = 0;
        for (int i = 0; i < classText.size(); i++) {
            TextOccurrence entry = classText.get(i);
            String location = entry.file + ":#" + entry.location;
            indexEntries.add(new TextIndex.Entry(id('C', ++c), "CLASS", location, entry.source));
            translationRows.add(new GameTranslations.Row("CLASS", location, entry.source, ""));
        }
        for (int i = 0; i < resources.size(); i++) {
            TranslationSupport.ResourceText entry = resources.get(i);
            char prefix = "DAT".equals(entry.kind) ? 'D' : ("SCP".equals(entry.kind) ? 'P' : 'T');
            int number = prefix == 'D' ? ++d : (prefix == 'P' ? ++p : ++t);
            String location = "DAT".equals(entry.kind) ? entry.file + ":row" + entry.location
                    : ("SCP".equals(entry.kind) ? entry.file + ":" + entry.detail
                    : entry.file + ":page" + (entry.location + 1));
            indexEntries.add(new TextIndex.Entry(id(prefix, number), entry.kind, location, entry.text));
            translationRows.add(new GameTranslations.Row(entry.kind, location, entry.text, ""));
        }
        for (int i = 0; i < scratchpad.size(); i++) {
            TranslationSupport.ResourceText entry = scratchpad.get(i);
            String location = entry.file + ":@" + entry.location;
            indexEntries.add(new TextIndex.Entry(id('S', ++s), "SP", location, entry.text));
            translationRows.add(new GameTranslations.Row("SP", location, entry.text, ""));
        }
        TextIndex.write(index, indexEntries);

        GameTranslations table = GameTranslations.read(translations);
        table.write(translations, translationRows);
        System.out.println("TranslationPrepare: class=" + classText.size() + ", dat=" + d
                + ", txt=" + t + ", scp=" + p
                + ", sp=" + scratchpad.size() + ", occurrences=" + translationRows.size()
                + "; blank translations fall back to original text");
    }

    private static String id(char prefix, int value) {
        String text = String.valueOf(value);
        while (text.length() < 4) text = "0" + text;
        return prefix + text;
    }
}
