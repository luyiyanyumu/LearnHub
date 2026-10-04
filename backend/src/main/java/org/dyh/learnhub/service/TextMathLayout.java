package org.dyh.learnhub.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keep authored Markdown math intact when displaying extracted text. */
@Service
public class TextMathLayout {
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})\\s+(.+)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-+*]\\s+(.+)$");

    public PdfLayoutExtractor.Layout extract(String source) {
        String text = source == null ? "" : source.replace("\r\n", "\n").replace('\r', '\n');
        List<PdfLayoutExtractor.Block> blocks = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        StringBuilder paragraph = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i], trimmed = line.trim();
            if (paragraph.isEmpty() && indented(line)) {
                StringBuilder code = new StringBuilder();
                int end = i;
                while (end < lines.length && (indented(lines[end]) || lines[end].isBlank())) {
                    if (end > i) code.append('\n');
                    code.append(indented(lines[end]) ? lines[end].substring(lines[end].startsWith("\t") ? 1 : 4) : "");
                    end++;
                }
                blocks.add(new PdfLayoutExtractor.Block("code", code.toString().stripTrailing(), 0));
                i = end - 1;
                continue;
            }
            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                flush(paragraph, blocks);
                String marker = fence.group(1);
                StringBuilder code = new StringBuilder();
                int end = i + 1;
                for (; end < lines.length; end++) {
                    String candidate = lines[end].trim();
                    if (candidate.length() >= marker.length() && candidate.chars().allMatch(c -> c == marker.charAt(0))) break;
                    if (end > i + 1) code.append('\n');
                    code.append(lines[end]);
                }
                // An unclosed fence is still code, never a mathematical expression.
                blocks.add(new PdfLayoutExtractor.Block("code", code.toString(), 0));
                i = end;
                continue;
            }
            String opening = trimmed.startsWith("$$") ? "$$" : trimmed.startsWith("\\[") ? "\\[" : null;
            if (opening != null) {
                String closing = opening.equals("$$") ? "$$" : "\\]";
                StringBuilder expression = new StringBuilder(trimmed.substring(opening.length()));
                int end = i;
                while (!expression.toString().stripTrailing().endsWith(closing) && end + 1 < lines.length
                        && end - i < 256 && expression.length() < 16_384) {
                    // A Markdown block boundary is not part of an unclosed equation.
                    if (FENCE.matcher(lines[end + 1]).matches() || HEADING.matcher(lines[end + 1]).matches()) break;
                    expression.append('\n').append(lines[++end]);
                }
                String candidate = expression.toString().stripTrailing();
                if (candidate.length() <= 16_384 && candidate.endsWith(closing)) {
                    String latex = candidate.substring(0, candidate.length() - closing.length()).trim();
                    if (!latex.isEmpty()) {
                        flush(paragraph, blocks);
                        blocks.add(new PdfLayoutExtractor.Block("formula", latex, 0, null, null, null,
                                latex, "restored", "文档中原有的 LaTeX 公式"));
                        i = end;
                        continue;
                    }
                }
                // Do not swallow following prose when an author forgot the delimiter.
            }
            Matcher heading = HEADING.matcher(line);
            Matcher bullet = BULLET.matcher(line);
            if (trimmed.isEmpty()) flush(paragraph, blocks);
            else if (heading.matches()) {
                flush(paragraph, blocks);
                blocks.add(new PdfLayoutExtractor.Block("heading", heading.group(2), heading.group(1).length()));
            } else if (bullet.matches()) {
                flush(paragraph, blocks);
                blocks.add(new PdfLayoutExtractor.Block("bullet", bullet.group(1), 0));
            } else {
                if (!paragraph.isEmpty()) paragraph.append('\n');
                paragraph.append(line);
            }
        }
        flush(paragraph, blocks);
        return new PdfLayoutExtractor.Layout(blocks.isEmpty() ? "empty" : "ok", null,
                blocks.isEmpty() ? List.of() : List.of(new PdfLayoutExtractor.PageLayout(1, 1, blocks)), text.length(), 1);
    }

    private static boolean indented(String line) {
        return line.startsWith("    ") || line.startsWith("\t");
    }

    private static void flush(StringBuilder paragraph, List<PdfLayoutExtractor.Block> blocks) {
        if (!paragraph.isEmpty()) {
            blocks.add(new PdfLayoutExtractor.Block("para", paragraph.toString(), 0));
            paragraph.setLength(0);
        }
    }
}
