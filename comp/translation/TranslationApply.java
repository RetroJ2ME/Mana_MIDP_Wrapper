package translation;

import doja.tools.font.FontUsage;
import doja.tools.translation.ClassText;
import doja.tools.translation.ResolvedText;
import doja.tools.translation.ResolvedTextTable;
import doja.tools.translation.TextOccurrence;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class TranslationApply {
    private TranslationApply() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "Usage: TranslationApply <game-classes> <generated-assets> <config.properties> <Translation.tsv> <resolved.tsv>");
        }
        File classes = new File(args[0]);
        File assets = new File(args[1]);
        File config = new File(args[2]);
        File translationFile = new File(args[3]);
        File resolvedFile = new File(args[4]);
        if (!config.isFile()) throw new IllegalArgumentException("missing " + config);

        GameTranslations translations = GameTranslations.read(translationFile);
        List<ResolvedText> resolved = new ArrayList<ResolvedText>();
        FontUsage usage = new FontUsage();

        List<TextOccurrence> classEntries = TranslationSupport.extractClassTextDirectory(classes);
        Map<String,Map<Integer,String>> patches = ClassText.newPatchMap();
        for (int i = 0; i < classEntries.size(); i++) {
            TextOccurrence entry = classEntries.get(i);
            String text = translations.resolve("CLASS",
                    entry.file + ":#" + entry.location, entry.source);
            resolved.add(new ResolvedText("class", entry.file, entry.location, text));
            usage.addRenderText(text);
            if (!text.equals(entry.source)) ClassText.addPatch(patches, entry.file, entry.location, text);
        }
        ClassText.patchDirectory(classes, patches);

        TranslationSupport.applyResourceTranslations(classes, assets, translations, resolved, usage);
        usage.write(new File(assets.getParentFile(), "font-usage.bin"));
        ResolvedTextTable.write(resolvedFile, resolved);
        System.out.println("TranslationApply: translated=" + translations.translatedCount()
                + ", class occurrences=" + classEntries.size() + ", verified resources="
                + (resolved.size() - classEntries.size()) + "; font usage emitted");
    }
}
