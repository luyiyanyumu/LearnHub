package org.dyh.learnhub.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Conservative cross-page noise removal: matching text alone never deletes a body paragraph. */
final class DocumentPageCleanup {
    private DocumentPageCleanup() { }
    record Result(String text, List<DocumentIngestionService.Page> pages, int removed) { }

    static Result clean(List<DocumentIngestionService.Page> pages) {
        Map<String, Set<Integer>> occurrences = new HashMap<>();
        for (var page : pages) for (var block : page.blocks()) {
            String key = marginalKey(page, block);
            if (key != null) occurrences.computeIfAbsent(key, unused -> new HashSet<>()).add(page.page());
        }
        int need = Math.max(3, (int) Math.ceil(pages.size() * .6));
        StringBuilder text = new StringBuilder();
        List<DocumentIngestionService.Page> cleaned = new ArrayList<>();
        int removed = 0;
        for (var page : pages) {
            List<DocumentIngestionService.Block> blocks = new ArrayList<>();
            for (var block : page.blocks()) {
                String key = marginalKey(page, block);
                boolean furniture = key != null && occurrences.get(key).size() >= need;
                boolean indexed = block.indexed() && !furniture;
                int start = -1, end = -1;
                if (indexed) {
                    if (!text.isEmpty()) text.append("\n\n");
                    start = text.length(); text.append(block.cleanText()); end = text.length();
                }
                if (furniture) removed++;
                blocks.add(new DocumentIngestionService.Block(block.id(), block.type(), block.rawText(), block.cleanText(),
                        block.page(), block.rect(), block.width(), block.height(), block.confidence(),
                        furniture ? "page_furniture" : block.quality(), indexed, start, end,
                        block.level(), block.src(), block.latex(), block.latexStatus(), block.latexMessage(),
                        block.markdown(), block.markdownStart(), block.markdownEnd(), block.presentation()));
            }
            cleaned.add(new DocumentIngestionService.Page(page.page(), page.width(), page.height(), page.coordinateUnit(), page.method(), List.copyOf(blocks), page.label()));
        }
        return new Result(text.toString(), List.copyOf(cleaned), removed);
    }

    private static String marginalKey(DocumentIngestionService.Page page, DocumentIngestionService.Block block) {
        if (!block.indexed() || !Set.of("para", "paragraph", "meta", "page_number", "header", "footer").contains(block.type())
                || block.cleanText() == null || block.cleanText().length() > 100 || !(page.height() > 0)
                || block.rect() == null || block.rect().length != 4) return null;
        double[] rect = block.rect();
        String band = rect[3] <= page.height() * .10 ? "header:" : rect[1] >= page.height() * .90 ? "footer:" : null;
        if (band == null) return null;
        String key = block.cleanText().toLowerCase(Locale.ROOT).replaceAll("\\d+", "#").replaceAll("\\s+", " ").strip();
        return key.isBlank() ? null : band + key;
    }
}
