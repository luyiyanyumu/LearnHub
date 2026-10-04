package org.dyh.learnhub.service;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conservative PDF mathematics restoration. PDF has positioned glyphs, not an equation tree;
 * only observed scripts, operator limits, and actual fraction rules become LaTeX structure.
 * Unattached rows and unknown glyphs remain a labelled candidate rather than a claimed recovery.
 */
final class PdfMathLatex {
    record Glyph(String text, double x0, double x1, double y, double size, double top, double bottom) { }
    record Bar(double x0, double x1, double y) { }
    record Restoration(String latex, String status, String message, double[] rect) {
        Restoration(String latex, String status, String message) {
            this(latex, status, message, null);
        }
    }

    private static final String CHECK = "依据 PDF 字形与版面还原，请对照原公式核对。";
    private static final Map<Integer, String> SYMBOLS = symbols();
    private static final Map<Integer, String> SUPER = scripts("⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿⁱ", "0123456789+-=()ni");
    private static final Map<Integer, String> SUB = scripts("₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎ₐₑₕᵢⱼₖₗₘₙₒₚᵣₛₜᵤᵥₓ", "0123456789+-=()aehijklmnoprstuvx");
    private static final Set<String> FUNCTIONS = Set.of("sin", "cos", "tan", "log", "ln", "exp", "lim", "max", "min", "det", "mod");

    private record Token(String latex, double x0, double x1, double y, double size, double top, double bottom,
                         boolean uncertain, boolean largeOperator) {
        double center() { return (x0 + x1) / 2; }
    }
    private record Rendered(String latex, boolean uncertain) { }
    private record PositionedRendered(double y, String latex) { }

    private PdfMathLatex() { }

    static Restoration restore(List<Glyph> pageGlyphs, List<Bar> pageBars, double[] rect,
                               String original, boolean upright) {
        if (rect == null || rect.length != 4 || !upright) {
            return new Restoration(null, "unavailable", upright
                    ? "缺少公式字形位置，已保留原文。" : "旋转页面的公式结构无法可靠还原，已保留原文。");
        }
        List<Glyph> glyphs = pageGlyphs.stream().filter(g -> inside(g, rect)).toList();
        if (glyphs.isEmpty()) {
            return new Restoration(null, "unavailable", "没有可用的公式字形，扫描图片需要公式 OCR；已保留原文。");
        }
        List<Token> tokens = new ArrayList<>();
        for (Glyph glyph : glyphs) {
            tokens.add(token(glyph));
        }
        double ruleMargin = glyphs.stream().mapToDouble(Glyph::size).max().orElse(10) * 0.8;
        List<Bar> bars = pageBars.stream().filter(b -> b.x0() >= rect[0] - ruleMargin && b.x1() <= rect[2] + ruleMargin
                && b.y() > rect[1] && b.y() < rect[3]).sorted(Comparator.comparingDouble(b -> b.x1() - b.x0())).toList();
        tokens = fractions(tokens, bars);
        tokens = radicals(tokens, bars);
        Rendered rendered = render(tokens, 0);
        boolean uncertain = rendered.uncertain() || !balanced(original);
        String status = uncertain ? "partial" : "restored";
        String message = uncertain ? "部分字形或上下结构无法确定，提供 LaTeX 候选并保留原文。" + CHECK : CHECK;
        // Synthetic fraction/root tokens include the observed vector-rule endpoints. The existing
        // text-only crop must grow to include them; it must never shrink or omit original glyphs.
        double padding = Math.max(2, maxSize(tokens) * 0.18);
        double[] fullRect = new double[]{
                Math.max(0, Math.min(rect[0], tokens.stream().mapToDouble(Token::x0).min().orElse(rect[0]) - padding)),
                Math.max(0, Math.min(rect[1], tokens.stream().mapToDouble(Token::top).min().orElse(rect[1]) - padding)),
                Math.max(rect[2], tokens.stream().mapToDouble(Token::x1).max().orElse(rect[2]) + padding),
                Math.max(rect[3], tokens.stream().mapToDouble(Token::bottom).max().orElse(rect[3]) + padding)};
        return new Restoration(rendered.latex(), status, message, fullRect);
    }

    private static boolean inside(Glyph glyph, double[] rect) {
        return glyph.text() != null && !glyph.text().isBlank() && glyph.x0() >= rect[0] - 0.3
                && glyph.x1() <= rect[2] + 0.3 && glyph.y() >= rect[1] && glyph.y() <= rect[3];
    }

    private static Token token(Glyph glyph) {
        StringBuilder latex = new StringBuilder();
        boolean uncertain = false;
        boolean operator = false;
        for (int c : glyph.text().codePoints().toArray()) {
            String command = SYMBOLS.get(c);
            if (command != null) {
                latex.append(command).append(' ');
                operator |= c == '∑' || c == '∏' || c == '∫' || c == '∮';
                uncertain |= c == '√'; // Its argument is known only when a matching vinculum is observed.
            } else if (SUPER.containsKey(c)) {
                latex.append("^{").append(SUPER.get(c)).append('}');
            } else if (SUB.containsKey(c)) {
                latex.append("_{").append(SUB.get(c)).append('}');
            } else if (c < 128 && (Character.isLetterOrDigit(c) || " +-=/()[]|,.!:;<>".indexOf(c) >= 0)) {
                latex.appendCodePoint(c);
            } else if (c == '{' || c == '}' || c == '%' || c == '#' || c == '$' || c == '&' || c == '_') {
                latex.append('\\').appendCodePoint(c);
            } else if (c == '^') {
                // A printed caret is not geometrical evidence for a raised argument.
                latex.append("\\mathord{\\wedge} ");
                uncertain = true;
            } else {
                uncertain = true;
                String literal = Character.isISOControl(c) || c == 0xFFFD || c == 0 ? "?" : new String(Character.toChars(c));
                latex.append("\\text{").append(escapeText(literal)).append('}');
            }
        }
        return new Token(latex.toString().trim(), glyph.x0(), glyph.x1(), glyph.y(), glyph.size(),
                glyph.top(), glyph.bottom(), uncertain, operator);
    }

    /** A rule only creates a fraction when compact glyph groups are observed on both sides. */
    private static List<Token> fractions(List<Token> source, List<Bar> bars) {
        List<Token> tokens = new ArrayList<>(source);
        for (Bar bar : bars) {
            List<Token> numerator = new ArrayList<>();
            List<Token> denominator = new ArrayList<>();
            for (Token t : tokens) {
                if (t.x0() < bar.x0() - t.size() * 0.2 || t.x1() > bar.x1() + t.size() * 0.2) {
                    continue;
                }
                if (t.y() < bar.y() - 1 && bar.y() - t.y() <= t.size() * 1.8) {
                    numerator.add(t);
                } else if (t.top() > bar.y() + 0.5 && t.y() - bar.y() <= t.size() * 2.2) {
                    denominator.add(t);
                }
            }
            if (numerator.isEmpty() || denominator.isEmpty()) {
                continue;
            }
            double size = Math.max(maxSize(numerator), maxSize(denominator));
            double contentWidth = Math.max(span(numerator), span(denominator));
            if (bar.x1() - bar.x0() < size * 0.5 || bar.x1() - bar.x0() > contentWidth * 1.5 + size) {
                continue; // A wide table/paragraph rule is not a fraction rule.
            }
            Rendered top = render(numerator, 1);
            Rendered bottom = render(denominator, 1);
            tokens.removeAll(numerator);
            tokens.removeAll(denominator);
            double y0 = numerator.stream().mapToDouble(Token::top).min().orElse(bar.y());
            double y1 = denominator.stream().mapToDouble(Token::bottom).max().orElse(bar.y());
            tokens.add(new Token("\\frac{" + top.latex() + "}{" + bottom.latex() + "}", bar.x0(), bar.x1(),
                    bar.y() + size * 0.3, size, y0, y1, top.uncertain() || bottom.uncertain(), false));
        }
        return tokens;
    }

    /** Only an observed radical glyph and its horizontal vinculum identify a square-root argument. */
    private static List<Token> radicals(List<Token> source, List<Bar> bars) {
        List<Token> tokens = new ArrayList<>(source);
        for (Token root : new ArrayList<>(tokens)) {
            if (!root.latex().equals("\\surd")) {
                continue;
            }
            for (Bar bar : bars) {
                if (bar.x0() < root.x0() || bar.x0() - root.x1() > root.size() * 0.8
                        || bar.x0() - root.x1() < -root.size() * 0.35
                        || bar.y() < root.top() - root.size() * 0.25 || bar.y() >= root.y() - root.size() * 0.25) {
                    continue;
                }
                List<Token> argument = tokens.stream().filter(t -> t != root
                        && t.x0() >= bar.x0() - t.size() * 0.15 && t.x1() <= bar.x1() + t.size() * 0.15
                        && t.top() >= bar.y() - t.size() * 0.35
                        && t.y() > bar.y() && t.y() - bar.y() < t.size() * 2.8).toList();
                if (argument.isEmpty()) {
                    continue;
                }
                Rendered value = render(argument, 1);
                tokens.remove(root);
                tokens.removeAll(argument);
                double bottom = argument.stream().mapToDouble(Token::bottom).max().orElse(root.bottom());
                tokens.add(new Token("\\sqrt{" + value.latex() + "}", root.x0(), bar.x1(), root.y(), root.size(),
                        Math.min(root.top(), bar.y()), Math.max(root.bottom(), bottom), value.uncertain(), false));
                break;
            }
        }
        return tokens;
    }

    private static Rendered render(List<Token> source, int depth) {
        if (source.isEmpty()) {
            return new Rendered("", true);
        }
        if (depth > 5 || source.size() > 1500) {
            return new Rendered(flat(source), true);
        }
        List<List<Token>> rows = rows(source);
        // Separate displayed equations can be preserved as separate rows. Small rows without
        // relations might be a numerator/limit and are handled below instead of guessed as a row.
        if (rows.size() > 1 && rows.stream().allMatch(PdfMathLatex::hasRelation)
                && rows.stream().allMatch(r -> maxSize(r) >= maxSize(source) * 0.8)
                && separateRows(rows)) {
            List<String> renderedRows = new ArrayList<>();
            boolean uncertain = false;
            for (List<Token> row : rows) {
                Rendered value = render(row, depth + 1);
                renderedRows.add(value.latex());
                uncertain |= value.uncertain();
            }
            return new Rendered("\\begin{gathered}" + String.join(" \\\\ ", renderedRows) + "\\end{gathered}", uncertain);
        }
        List<Token> main = rows.stream().max(Comparator.comparingDouble(PdfMathLatex::rowScore)).orElseThrow();
        double mainY = main.stream().mapToDouble(Token::y).average().orElse(0);
        double mainSize = maxSize(main);
        main = new ArrayList<>(main);
        // Big operators and the synthetic baseline of a fraction have a wider baseline tolerance.
        for (Token t : source) {
            if (!main.contains(t) && (t.largeOperator() || t.latex().startsWith("\\frac{"))
                    && Math.abs(t.y() - mainY) < mainSize * 0.6) {
                main.add(t);
            }
        }
        main.sort(Comparator.comparingDouble(Token::x0));
        List<Token> remaining = new ArrayList<>(source);
        remaining.removeAll(main);
        Map<Token, List<Token>> upper = new LinkedHashMap<>();
        Map<Token, List<Token>> lower = new LinkedHashMap<>();
        // Centred limits belong to the observed sum/product/integral glyph, not a nearby variable.
        for (Token base : main) {
            if (!base.largeOperator()) {
                continue;
            }
            List<Token> up = new ArrayList<>();
            List<Token> down = new ArrayList<>();
            for (Token t : remaining) {
                if (t.size() > base.size() * 0.85 || Math.abs(t.center() - base.center()) > base.size() * 0.95
                        || t.x0() < base.x0() - base.size() * 0.7 || t.x1() > base.x1() + base.size() * 0.7) {
                    continue;
                }
                double delta = t.y() - base.y();
                if (delta < -base.size() * 0.18 && delta >= -base.size() * 2.3) {
                    up.add(t);
                } else if (delta > base.size() * 0.18 && delta <= base.size() * 2.3) {
                    down.add(t);
                }
            }
            upper.put(base, up);
            lower.put(base, down);
            remaining.removeAll(up);
            remaining.removeAll(down);
        }
        for (Token script : new ArrayList<>(remaining)) {
            Token anchor = null;
            double nearest = Double.MAX_VALUE;
            for (Token base : main) {
                double dx = script.x0() - base.x1();
                double dy = script.y() - base.y();
                if (script.size() <= base.size() * 0.88 && Math.abs(dy) >= base.size() * 0.18
                        && Math.abs(dy) <= base.size() * 1.3 && dx >= -base.size() * 0.28
                        && dx <= base.size() * 0.8 && Math.abs(dx) < nearest) {
                    nearest = Math.abs(dx);
                    anchor = base;
                }
            }
            if (anchor != null) {
                (script.y() < anchor.y() ? upper : lower).computeIfAbsent(anchor, k -> new ArrayList<>()).add(script);
                remaining.remove(script);
            }
        }
        // Extend a script group horizontally only while its baseline and spacing remain coherent.
        extendScripts(remaining, upper);
        extendScripts(remaining, lower);
        StringBuilder latex = new StringBuilder();
        boolean uncertain = source.stream().anyMatch(Token::uncertain);
        for (Token base : main) {
            if (!latex.isEmpty()) {
                latex.append(' ');
            }
            latex.append(base.latex());
            List<Token> sub = lower.getOrDefault(base, List.of());
            List<Token> sup = upper.getOrDefault(base, List.of());
            if (base.largeOperator() && (!sub.isEmpty() || !sup.isEmpty())) {
                latex.append("\\limits");
            }
            if (!sub.isEmpty()) {
                Rendered value = render(sub, depth + 1);
                latex.append("_{").append(value.latex()).append('}');
                uncertain |= value.uncertain();
            }
            if (!sup.isEmpty()) {
                Rendered value = render(sup, depth + 1);
                latex.append("^{").append(value.latex()).append('}');
                uncertain |= value.uncertain();
            }
        }
        String line = normalizeFunctions(latex.toString());
        if (!remaining.isEmpty()) {
            List<PositionedRendered> unresolved = new ArrayList<>();
            unresolved.add(new PositionedRendered(mainY, line));
            for (List<Token> row : rows(remaining)) {
                unresolved.add(new PositionedRendered(row.getFirst().y(), flat(row)));
            }
            // No division bar or script anchor: preserve detached fragments without inventing a fraction.
            List<String> ordered = unresolved.stream().sorted(Comparator.comparingDouble(PositionedRendered::y))
                    .map(PositionedRendered::latex).toList();
            return new Rendered("\\begin{gathered}" + String.join(" \\\\ ", ordered) + "\\end{gathered}", true);
        }
        return new Rendered(line, uncertain);
    }

    private static void extendScripts(List<Token> remaining, Map<Token, List<Token>> groups) {
        for (Map.Entry<Token, List<Token>> entry : groups.entrySet()) {
            List<Token> group = entry.getValue();
            if (group.isEmpty()) {
                continue;
            }
            boolean changed;
            do {
                changed = false;
                double right = group.stream().mapToDouble(Token::x1).max().orElse(0);
                double y = group.get(0).y();
                double size = maxSize(group);
                for (Token t : new ArrayList<>(remaining)) {
                    if (t.size() <= entry.getKey().size() * 0.88 && Math.abs(t.y() - y) < size * 0.22
                            && t.x0() >= right - size * 0.1 && t.x0() - right <= size * 0.6) {
                        group.add(t);
                        remaining.remove(t);
                        changed = true;
                        break;
                    }
                }
            } while (changed);
        }
    }

    private static List<List<Token>> rows(List<Token> source) {
        List<Token> sorted = source.stream().sorted(Comparator.comparingDouble(Token::y).thenComparingDouble(Token::x0)).toList();
        List<List<Token>> rows = new ArrayList<>();
        for (Token t : sorted) {
            List<Token> row = rows.isEmpty() ? null : rows.getLast();
            if (row == null || Math.abs(row.getFirst().y() - t.y()) > Math.max(1.4, Math.min(row.getFirst().size(), t.size()) * 0.18)) {
                row = new ArrayList<>();
                rows.add(row);
            }
            row.add(t);
        }
        return rows;
    }

    private static boolean separateRows(List<List<Token>> rows) {
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i).getFirst().y() - rows.get(i - 1).getFirst().y() < maxSize(rows.get(i)) * 1.1) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasRelation(List<Token> tokens) {
        return tokens.stream().anyMatch(t -> t.latex().equals("=") || t.latex().equals("\\leq") || t.latex().equals("\\geq"));
    }

    private static double rowScore(List<Token> row) {
        return span(row) + row.stream().mapToDouble(t -> t.size() * Math.min(3, t.latex().length())).sum();
    }

    private static double maxSize(List<Token> tokens) {
        return tokens.stream().mapToDouble(Token::size).max().orElse(10);
    }

    private static double span(List<Token> tokens) {
        return tokens.stream().mapToDouble(Token::x1).max().orElse(0) - tokens.stream().mapToDouble(Token::x0).min().orElse(0);
    }

    private static String flat(List<Token> tokens) {
        return normalizeFunctions(String.join(" ", tokens.stream().sorted(Comparator.comparingDouble(Token::x0)).map(Token::latex).toList()));
    }

    private static String normalizeFunctions(String latex) {
        // Joining glyphs with whitespace keeps command names unambiguous. Only explicit contiguous
        // function names are normalized; split "a r g" fragments are not guessed as argmax.
        for (String function : FUNCTIONS) {
            latex = latex.replaceAll("(?<![A-Za-z\\\\])" + function + "(?![A-Za-z])", "\\\\" + function + " ");
        }
        // Encoded Unicode superscripts/subscripts can be emitted as separate PDF glyphs.
        // Consecutive script characters must form one argument, not duplicate TeX scripts.
        String previous;
        do {
            previous = latex;
            latex = latex.replaceAll("\\^\\{([^{}]*)}\\s*\\^\\{([^{}]*)}", "^{$1$2}")
                    .replaceAll("_\\{([^{}]*)}\\s*_\\{([^{}]*)}", "_{$1$2}");
        } while (!previous.equals(latex));
        return latex.trim();
    }

    private static boolean balanced(String text) {
        if (text == null) {
            return false;
        }
        List<Character> stack = new ArrayList<>();
        for (char c : text.toCharArray()) {
            if (c == '(' || c == '[' || c == '{') {
                stack.add(c);
            } else if (c == ')' || c == ']' || c == '}') {
                if (stack.isEmpty()) {
                    return false;
                }
                char opening = stack.removeLast();
                if ((c == ')' && opening != '(') || (c == ']' && opening != '[') || (c == '}' && opening != '{')) {
                    return false;
                }
            }
        }
        return stack.isEmpty();
    }

    private static String escapeText(String text) {
        return text.replace("\\", "\\textbackslash{}").replace("{", "\\{").replace("}", "\\}")
                .replace("%", "\\%").replace("$", "\\$").replace("#", "\\#").replace("&", "\\&").replace("_", "\\_");
    }

    private static Map<Integer, String> scripts(String from, String to) {
        int[] codes = from.codePoints().toArray();
        int[] replacements = to.codePoints().toArray();
        Map<Integer, String> map = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(codes.length, replacements.length); i++) {
            map.put(codes[i], new String(Character.toChars(replacements[i])));
        }
        return map;
    }

    private static Map<Integer, String> symbols() {
        Map<Integer, String> map = new LinkedHashMap<>();
        String[] pairs = {
                "α:alpha", "β:beta", "γ:gamma", "δ:delta", "ε:epsilon", "ϵ:varepsilon", "ζ:zeta", "η:eta", "θ:theta", "ϑ:vartheta",
                "ι:iota", "κ:kappa", "λ:lambda", "μ:mu", "ν:nu", "ξ:xi", "π:pi", "ϖ:varpi", "ρ:rho", "ϱ:varrho", "σ:sigma",
                "ς:varsigma", "τ:tau", "υ:upsilon", "φ:phi", "ϕ:varphi", "χ:chi", "ψ:psi", "ω:omega",
                "Γ:Gamma", "Δ:Delta", "Θ:Theta", "Λ:Lambda", "Ξ:Xi", "Π:Pi", "Σ:Sigma", "Υ:Upsilon", "Φ:Phi", "Ψ:Psi", "Ω:Omega",
                "±:pm", "∓:mp", "×:times", "÷:div", "⋅:cdot", "·:cdot", "∗:ast", "⋆:star", "⊗:otimes", "⊕:oplus", "⊙:odot",
                "≤:leq", "≥:geq", "≠:neq", "≈:approx", "≡:equiv", "∼:sim", "≅:cong", "∝:propto", "≪:ll", "≫:gg",
                "∈:in", "∉:notin", "⊂:subset", "⊃:supset", "⊆:subseteq", "⊇:supseteq", "∪:cup", "∩:cap", "∅:emptyset",
                "∞:infty", "∂:partial", "∇:nabla", "∑:sum", "∏:prod", "∫:int", "∮:oint", "√:surd",
                "∀:forall", "∃:exists", "¬:neg", "∧:land", "∨:lor", "⊥:perp", "⊤:top", "∥:parallel",
                "→:to", "←:leftarrow", "↔:leftrightarrow", "⇒:Rightarrow", "⇐:Leftarrow", "⇔:Leftrightarrow",
                "…:ldots", "⋯:cdots", "⋮:vdots", "⋱:ddots", "⟨:langle", "⟩:rangle", "⌊:lfloor", "⌋:rfloor", "⌈:lceil", "⌉:rceil",
                "′:prime", "″:prime\\prime", "†:dagger", "‡:ddagger", "ℓ:ell", "ℏ:hbar", "ℝ:mathbb{R}", "ℕ:mathbb{N}", "ℤ:mathbb{Z}", "ℂ:mathbb{C}"
        };
        for (String pair : pairs) {
            int separator = pair.indexOf(':');
            map.put(pair.codePointAt(0), "\\" + pair.substring(separator + 1));
        }
        map.put((int) '−', "-");
        map.put((int) '–', "-");
        return map;
    }

    static List<Bar> scanBars(PDPage page) {
        BarScanner scanner = new BarScanner(page);
        try {
            scanner.processPage(page);
            return scanner.bars;
        } catch (Exception ignored) {
            return List.of(); // A malformed graphics stream must not disable text extraction.
        }
    }

    /** PDFGraphicsStreamEngine supplies already transformed path coordinates. */
    private static final class BarScanner extends PDFGraphicsStreamEngine {
        private final List<Bar> bars = new ArrayList<>();
        private final List<Bar> path = new ArrayList<>();
        private final List<Bar> filledRules = new ArrayList<>();
        private Point2D point = new Point2D.Float();
        private boolean otherSegment;
        private final double cropX;
        private final double cropY;
        private final double pageHeight;

        private BarScanner(PDPage page) {
            super(page);
            cropX = page.getCropBox().getLowerLeftX();
            cropY = page.getCropBox().getLowerLeftY();
            pageHeight = page.getCropBox().getHeight();
        }

        @Override public void moveTo(float x, float y) { point = new Point2D.Float(x, y); }
        @Override public void lineTo(float x, float y) {
            if (Math.abs(y - point.getY()) < 0.65 && Math.abs(x - point.getX()) >= 3) {
                path.add(new Bar(Math.min(x, point.getX()) - cropX, Math.max(x, point.getX()) - cropX,
                        pageHeight - (y + point.getY()) / 2 + cropY));
            } else {
                otherSegment = true;
            }
            point = new Point2D.Float(x, y);
        }
        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            double x0 = Math.min(Math.min(p0.getX(), p1.getX()), Math.min(p2.getX(), p3.getX()));
            double x1 = Math.max(Math.max(p0.getX(), p1.getX()), Math.max(p2.getX(), p3.getX()));
            double y0 = Math.min(Math.min(p0.getY(), p1.getY()), Math.min(p2.getY(), p3.getY()));
            double y1 = Math.max(Math.max(p0.getY(), p1.getY()), Math.max(p2.getY(), p3.getY()));
            if (x1 - x0 >= 3 && y1 - y0 <= 2 && y1 - y0 > 0) {
                filledRules.add(new Bar(x0 - cropX, x1 - cropX, pageHeight - (y0 + y1) / 2 + cropY));
            } else {
                otherSegment = true;
            }
            point = p3;
        }
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { otherSegment = true; point = new Point2D.Float(x3, y3); }
        @Override public Point2D getCurrentPoint() { return point; }
        @Override public void closePath() { otherSegment = true; }
        @Override public void strokePath() {
            if (!otherSegment && getGraphicsState().getLineWidth() <= 2) {
                bars.addAll(path);
                bars.addAll(filledRules);
            }
            endPath();
        }
        @Override public void endPath() { path.clear(); filledRules.clear(); otherSegment = false; }
        @Override public void fillPath(int windingRule) {
            if (!otherSegment) {
                bars.addAll(filledRules);
            }
            endPath();
        }
        @Override public void fillAndStrokePath(int windingRule) { strokePath(); }
        @Override public void clip(int windingRule) { }
        @Override public void drawImage(PDImage image) { }
        @Override public void shadingFill(COSName shadingName) { }
    }
}
