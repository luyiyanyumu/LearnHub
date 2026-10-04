package org.dyh.learnhub.service;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converts the actual OOXML equation tree; never infers an equation from surrounding prose. */
public final class OmmlLatexConverter {
    static final String M = "http://schemas.openxmlformats.org/officeDocument/2006/math";
    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final int MAX_DEPTH = 80;
    private static final int MAX_NODES = 16_384;
    private static final int MAX_OUTPUT = 32_768;
    private static final Map<Integer, String> SYMBOLS = Map.ofEntries(
            Map.entry((int) 'α', "alpha"), Map.entry((int) 'β', "beta"), Map.entry((int) 'γ', "gamma"),
            Map.entry((int) 'δ', "delta"), Map.entry((int) 'ε', "varepsilon"), Map.entry((int) 'ϵ', "epsilon"),
            Map.entry((int) 'ζ', "zeta"), Map.entry((int) 'η', "eta"), Map.entry((int) 'θ', "theta"),
            Map.entry((int) 'ϑ', "vartheta"), Map.entry((int) 'ι', "iota"), Map.entry((int) 'κ', "kappa"),
            Map.entry((int) 'λ', "lambda"), Map.entry((int) 'μ', "mu"), Map.entry((int) 'ν', "nu"),
            Map.entry((int) 'ξ', "xi"), Map.entry((int) 'π', "pi"), Map.entry((int) 'ϖ', "varpi"),
            Map.entry((int) 'ρ', "rho"), Map.entry((int) 'ϱ', "varrho"), Map.entry((int) 'σ', "sigma"),
            Map.entry((int) 'ς', "varsigma"), Map.entry((int) 'τ', "tau"), Map.entry((int) 'υ', "upsilon"),
            Map.entry((int) 'φ', "varphi"), Map.entry((int) 'ϕ', "phi"), Map.entry((int) 'χ', "chi"),
            Map.entry((int) 'ψ', "psi"), Map.entry((int) 'ω', "omega"), Map.entry((int) 'Γ', "Gamma"),
            Map.entry((int) 'Δ', "Delta"), Map.entry((int) 'Θ', "Theta"), Map.entry((int) 'Λ', "Lambda"),
            Map.entry((int) 'Ξ', "Xi"), Map.entry((int) 'Π', "Pi"), Map.entry((int) 'Σ', "Sigma"),
            Map.entry((int) 'Υ', "Upsilon"), Map.entry((int) 'Φ', "Phi"), Map.entry((int) 'Ψ', "Psi"),
            Map.entry((int) 'Ω', "Omega"), Map.entry((int) '∑', "sum"), Map.entry((int) '∏', "prod"),
            Map.entry((int) '∐', "coprod"), Map.entry((int) '∫', "int"), Map.entry((int) '∬', "iint"),
            Map.entry((int) '∭', "iiint"), Map.entry((int) '∮', "oint"), Map.entry((int) '∞', "infty"),
            Map.entry((int) '∂', "partial"), Map.entry((int) '∇', "nabla"), Map.entry((int) '±', "pm"),
            Map.entry((int) '∓', "mp"), Map.entry((int) '×', "times"), Map.entry((int) '÷', "div"),
            Map.entry((int) '·', "cdot"), Map.entry((int) '⋅', "cdot"), Map.entry((int) '∗', "ast"),
            Map.entry((int) '≤', "leq"), Map.entry((int) '≥', "geq"), Map.entry((int) '≠', "neq"),
            Map.entry((int) '≈', "approx"), Map.entry((int) '≡', "equiv"), Map.entry((int) '∝', "propto"),
            Map.entry((int) '∈', "in"), Map.entry((int) '∉', "notin"), Map.entry((int) '∋', "ni"),
            Map.entry((int) '⊂', "subset"), Map.entry((int) '⊆', "subseteq"), Map.entry((int) '⊃', "supset"),
            Map.entry((int) '⊇', "supseteq"), Map.entry((int) '∪', "cup"), Map.entry((int) '∩', "cap"),
            Map.entry((int) '∅', "emptyset"), Map.entry((int) '∀', "forall"), Map.entry((int) '∃', "exists"),
            Map.entry((int) '¬', "neg"), Map.entry((int) '∧', "land"), Map.entry((int) '∨', "lor"),
            Map.entry((int) '⊕', "oplus"), Map.entry((int) '⊗', "otimes"), Map.entry((int) '→', "to"),
            Map.entry((int) '←', "leftarrow"), Map.entry((int) '↔', "leftrightarrow"),
            Map.entry((int) '⇒', "Rightarrow"), Map.entry((int) '⇐', "Leftarrow"), Map.entry((int) '⇔', "Leftrightarrow"),
            Map.entry((int) '…', "ldots"), Map.entry((int) '⋯', "cdots"), Map.entry((int) '⋮', "vdots"),
            Map.entry((int) '⋱', "ddots"), Map.entry((int) 'ℝ', "mathbb{R}"), Map.entry((int) 'ℕ', "mathbb{N}"),
            Map.entry((int) 'ℤ', "mathbb{Z}"), Map.entry((int) 'ℚ', "mathbb{Q}"), Map.entry((int) 'ℂ', "mathbb{C}"),
            Map.entry((int) 'ℓ', "ell"), Map.entry((int) 'ℏ', "hbar"), Map.entry((int) '′', "prime"),
            Map.entry((int) '∠', "angle"), Map.entry((int) '⊥', "perp"), Map.entry((int) '∥', "parallel"));
    private static final Set<String> FUNCTIONS = Set.of("sin", "cos", "tan", "cot", "sec", "csc", "sinh", "cosh",
            "tanh", "arcsin", "arccos", "arctan", "log", "ln", "exp", "lim", "min", "max", "det", "gcd", "sup", "inf", "Pr");

    public record Result(String latex, String status, String message, String originalText) { }

    public Result convert(Node equation) {
        Context context = new Context();
        String original = originalText(equation, 0, new int[]{0});
        try {
            String latex = visit(equation, context, 0).trim();
            if (latex.isEmpty()) return new Result(null, "unavailable", "公式没有可还原的数学内容；已保留原始字符。", original);
            return new Result(latex, context.warnings.isEmpty() ? "restored" : "partial",
                    context.warnings.isEmpty() ? null : String.join("；", context.warnings), original);
        } catch (LimitException e) {
            return new Result(null, "unavailable", e.getMessage() + "；已保留原始字符，请对照原文。", original);
        }
    }

    private String visit(Node node, Context c, int depth) {
        if (node == null) return "";
        if (depth > MAX_DEPTH || ++c.nodes > MAX_NODES) throw new LimitException("公式结构过深或过大，未自动还原");
        if (node.getNodeType() != Node.ELEMENT_NODE) return "";
        String name = local(node);
        if (W.equals(node.getNamespaceURI())) {
            return switch (name) {
                case "t" -> escaped(text(node), c);
                case "br", "cr" -> "\\\\ ";
                case "r", "sdt", "sdtContent" -> children(node, c, depth);
                default -> "";
            };
        }
        if (!M.equals(node.getNamespaceURI())) {
            c.warn("未知公式命名空间，已保留可读内容");
            return unknownContents(node, c, depth);
        }
        if (name.endsWith("Pr") || Set.of("ctrlPr", "argPr").contains(name)) return "";
        String result = switch (name) {
            case "oMath", "oMathPara", "num", "den", "e", "sub", "sup", "deg", "lim", "fName" -> children(node, c, depth);
            case "t" -> escaped(text(node), c);
            case "r" -> run(node, c, depth);
            case "f" -> {
                String type = value(child(child(node, "fPr"), "type"));
                yield (type.equals("noBar") ? "\\genfrac{}{}{0pt}{}" : "\\frac") + br(argument(node, "num", c, depth)) + br(argument(node, "den", c, depth));
            }
            case "rad" -> {
                String degree = argument(node, "deg", c, depth);
                boolean hide = on(child(child(node, "radPr"), "degHide"));
                yield "\\sqrt" + (degree.isEmpty() || hide ? "" : "[" + br(degree) + "]") + br(argument(node, "e", c, depth));
            }
            case "sSup" -> br(argument(node, "e", c, depth)) + "^" + br(argument(node, "sup", c, depth));
            case "sSub" -> br(argument(node, "e", c, depth)) + "_" + br(argument(node, "sub", c, depth));
            case "sSubSup" -> br(argument(node, "e", c, depth)) + "_" + br(argument(node, "sub", c, depth)) + "^" + br(argument(node, "sup", c, depth));
            case "sPre" -> "{}_" + br(argument(node, "sub", c, depth)) + "^" + br(argument(node, "sup", c, depth)) + br(argument(node, "e", c, depth));
            case "nary" -> nary(node, c, depth);
            case "d" -> delimiters(node, c, depth);
            case "m" -> matrix(node, c, depth);
            case "eqArr" -> "\\begin{aligned}" + joinArguments(node, "e", " \\\\ ", c, depth) + "\\end{aligned}";
            case "acc" -> accent(node, c, depth);
            case "bar" -> (value(child(child(node, "barPr"), "pos")).equals("top") ? "\\overline" : "\\underline") + br(argument(node, "e", c, depth));
            case "groupChr" -> groupCharacter(node, c, depth);
            case "func" -> function(node, c, depth);
            case "limLow" -> br(argument(node, "e", c, depth)) + "_" + br(argument(node, "lim", c, depth));
            case "limUpp" -> br(argument(node, "e", c, depth)) + "^" + br(argument(node, "lim", c, depth));
            case "box" -> br(argument(node, "e", c, depth));
            case "borderBox" -> {
                if (child(node, "borderBoxPr") != null) c.warn("公式边框按方框近似还原");
                yield "\\boxed" + br(argument(node, "e", c, depth));
            }
            case "phant" -> {
                c.warn("幻影占位结构按\\phantom近似还原");
                yield "\\phantom" + br(argument(node, "e", c, depth));
            }
            default -> {
                c.warn("未支持的公式结构 " + name + "，已保留其子内容");
                yield unknownContents(node, c, depth);
            }
        };
        if (result.length() > MAX_OUTPUT) throw new LimitException("公式 LaTeX 超过长度上限");
        return result;
    }

    private String run(Node node, Context c, int depth) {
        String contents = children(node, c, depth);
        Node props = child(node, "rPr");
        String style = value(child(props, "sty"));
        String script = value(child(props, "scr"));
        if (!script.isEmpty()) {
            String command = switch (script) {
                case "double-struck" -> "mathbb";
                case "fraktur" -> "mathfrak";
                case "script" -> "mathcal";
                case "sans-serif" -> "mathsf";
                case "monospace" -> "mathtt";
                case "roman" -> "mathrm";
                default -> "";
            };
            if (!command.isEmpty()) contents = "\\" + command + br(contents);
            else c.warn("未支持的数学字体 " + script + "，字符已保留");
        }
        if (style.equals("b") || style.equals("bi")) contents = (style.equals("bi") ? "\\boldsymbol" : "\\mathbf") + br(contents);
        else if (style.equals("p") && script.isEmpty()) contents = "\\mathrm" + br(contents);
        if (on(child(props, "nor"))) contents = "\\text" + br(escapedText(textContent(node)));
        return contents;
    }

    private String nary(Node node, Context c, int depth) {
        Node props = child(node, "naryPr");
        String symbol = value(child(props, "chr"));
        if (symbol.isEmpty()) symbol = "∫";
        String op = escaped(symbol, c);
        String location = value(child(props, "limLoc"));
        if (location.equals("undOvr")) op += "\\limits";
        else if (location.equals("subSup")) op += "\\nolimits";
        String sub = argument(node, "sub", c, depth), sup = argument(node, "sup", c, depth);
        return op + (sub.isEmpty() || on(child(props, "subHide")) ? "" : "_" + br(sub))
                + (sup.isEmpty() || on(child(props, "supHide")) ? "" : "^" + br(sup))
                + br(argument(node, "e", c, depth));
    }

    private String delimiters(Node node, Context c, int depth) {
        Node props = child(node, "dPr");
        String begin = property(props, "begChr", "("), end = property(props, "endChr", ")");
        String separator = property(props, "sepChr", "|");
        String middle = separator.isEmpty() ? "" : "\\middle" + delimiter(separator, c) + " ";
        return "\\left" + delimiter(begin, c) + " " + joinArguments(node, "e", middle, c, depth)
                + "\\right" + delimiter(end, c) + " ";
    }

    private String matrix(Node node, Context c, int depth) {
        List<String> rows = new ArrayList<>();
        int columns = -1;
        for (Node row : namedChildren(node, "mr")) {
            List<Node> cells = namedChildren(row, "e");
            if (columns >= 0 && columns != cells.size()) c.warn("矩阵各行列数不一致，请核对原文");
            columns = Math.max(columns, cells.size());
            rows.add(joinArguments(row, "e", " & ", c, depth + 1));
        }
        return "\\begin{matrix}" + String.join(" \\\\ ", rows) + "\\end{matrix}";
    }

    private String accent(Node node, Context c, int depth) {
        String accent = property(child(node, "accPr"), "chr", "̂");
        String cmd = switch (accent) {
            case "̂", "^" -> "hat";
            case "̅", "¯" -> "bar";
            case "̃", "~" -> "tilde";
            case "⃗", "→" -> "vec";
            case "̇", "." -> "dot";
            case "̈", "¨" -> "ddot";
            case "̆" -> "breve";
            case "̌" -> "check";
            case "́" -> "acute";
            case "̀" -> "grave";
            default -> "";
        };
        String base = argument(node, "e", c, depth);
        if (!cmd.isEmpty()) return "\\" + cmd + br(base);
        c.warn("未支持的重音 " + accent + "，按上方标记保留");
        return "\\overset" + br(escaped(accent, c)) + br(base);
    }

    private String groupCharacter(Node node, Context c, int depth) {
        Node props = child(node, "groupChrPr");
        String symbol = property(props, "chr", "⏟");
        boolean bottom = property(props, "pos", "bot").equals("bot");
        String base = argument(node, "e", c, depth);
        if (Set.of("⏟", "⏞", "{", "}").contains(symbol)) return (bottom ? "\\underbrace" : "\\overbrace") + br(base);
        c.warn("分组符号 " + symbol + " 的位置按上下标近似保留");
        return (bottom ? "\\underset" : "\\overset") + br(escaped(symbol, c)) + br(base);
    }

    private String function(Node node, Context c, int depth) {
        Node name = child(node, "fName");
        String raw = textContent(name).trim();
        String rendered = argument(node, "fName", c, depth);
        boolean simple = true;
        if (name != null) for (Node n = name.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && !(M.equals(n.getNamespaceURI()) && (local(n).equals("r") || local(n).endsWith("Pr")))) simple = false;
        }
        String fn = simple && FUNCTIONS.contains(raw) ? "\\" + raw : simple
                ? "\\operatorname" + br(escaped(raw, c)) : rendered;
        return fn + br(argument(node, "e", c, depth));
    }

    private String argument(Node node, String name, Context c, int depth) {
        Node argument = child(node, name);
        if (argument == null) { c.warn("公式缺少 " + name + " 参数，已留空待核对"); return ""; }
        return visit(argument, c, depth + 1);
    }

    private String joinArguments(Node node, String name, String separator, Context c, int depth) {
        List<String> values = new ArrayList<>();
        for (Node item : namedChildren(node, name)) values.add(visit(item, c, depth + 1));
        String result = String.join(separator, values);
        if (result.length() > MAX_OUTPUT) throw new LimitException("公式 LaTeX 超过长度上限");
        return result;
    }

    private String children(Node node, Context c, int depth) {
        StringBuilder out = new StringBuilder();
        for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) {
            out.append(visit(n, c, depth + 1));
            if (out.length() > MAX_OUTPUT) throw new LimitException("公式 LaTeX 超过长度上限");
        }
        return out.toString();
    }

    private String unknownContents(Node node, Context c, int depth) {
        String content = children(node, c, depth);
        return content.isEmpty() ? escaped(textContent(node), c) : content;
    }

    private static String escaped(String value, Context c) {
        StringBuilder result = new StringBuilder();
        value.codePoints().forEach(cp -> {
            String symbol = SYMBOLS.get(cp);
            if (symbol != null) { result.append('\\').append(symbol).append(' '); return; }
            // Mathematical alphanumeric symbols have semantic font variants; retain the variant explicitly.
            if (cp >= 0x1D400 && cp <= 0x1D7FF) {
                String normal = Normalizer.normalize(new String(Character.toChars(cp)), Normalizer.Form.NFKC);
                if (cp >= 0x1D538 && cp <= 0x1D56B) result.append("\\mathbb{").append(normal).append('}');
                else if (cp <= 0x1D433 || cp >= 0x1D7CE && cp <= 0x1D7D7) result.append("\\mathbf{").append(normal).append('}');
                else { result.append(normal); c.warn("数学字母字体按普通字母保留，请核对字形"); }
                return;
            }
            switch (cp) {
                case '\\' -> result.append("\\backslash ");
                case '{', '}', '#', '$', '%', '&', '_' -> result.append('\\').appendCodePoint(cp);
                case '^' -> result.append("\\hat{} ");
                case '~' -> result.append("\\sim ");
                case '\n', '\r', '\t', ' ' -> result.append("\\ ");
                case 0x2212, 0x2013 -> result.append('-');
                case 0x00A0 -> result.append("\\ ");
                default -> result.appendCodePoint(cp);
            }
        });
        return result.toString();
    }

    private static String delimiter(String value, Context c) {
        return switch (value) {
            case "" -> ".";
            case "{", "}" -> "\\" + value;
            case "⟨", "〈" -> "\\langle";
            case "⟩", "〉" -> "\\rangle";
            case "‖", "∥" -> "\\|";
            case "⌊" -> "\\lfloor";
            case "⌋" -> "\\rfloor";
            case "⌈" -> "\\lceil";
            case "⌉" -> "\\rceil";
            case "(", ")", "[", "]", "|", "/", "." -> value;
            default -> { c.warn("未支持的括号 " + value + "，已作为普通符号保留"); yield "." + br(escaped(value, c)); }
        };
    }

    private static String originalText(Node node, int depth, int[] count) {
        if (node == null || depth > MAX_DEPTH || ++count[0] > MAX_NODES) return "[公式结构超限]";
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE)
            return node.getNodeValue().isBlank() ? "" : node.getNodeValue();
        if (node.getNodeType() != Node.ELEMENT_NODE) return "";
        if (local(node).equals("t")) {
            String value = node.getTextContent();
            return value.length() > MAX_OUTPUT ? value.substring(0, MAX_OUTPUT) + "[原始字符超过上限]" : value;
        }
        StringBuilder out = new StringBuilder();
        if (M.equals(node.getNamespaceURI()) && local(node).equals("chr")) out.append(value(node));
        for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) {
            out.append(originalText(n, depth + 1, count));
            if (out.length() > MAX_OUTPUT) return out.substring(0, MAX_OUTPUT) + "[原始字符超过上限]";
        }
        return out.toString();
    }

    private static String textContent(Node node) { return originalText(node, 0, new int[]{0}); }
    private static String escapedText(String value) {
        StringBuilder out = new StringBuilder();
        value.codePoints().forEach(cp -> {
            switch (cp) {
                case '\\' -> out.append("\\textbackslash{}");
                case '{', '}', '#', '$', '%', '&', '_' -> out.append('\\').appendCodePoint(cp);
                case '^' -> out.append("\\^{}");
                case '~' -> out.append("\\~{}");
                case '\n', '\r', '\t' -> out.append(' ');
                default -> out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }
    private static String text(Node node) {
        String value = node == null ? "" : node.getTextContent();
        if (value.length() > MAX_OUTPUT) throw new LimitException("公式字符超过长度上限");
        return value;
    }
    private static String br(String value) { return "{" + value + "}"; }
    static String local(Node node) { String name = node.getLocalName(); return name == null ? node.getNodeName().replaceFirst("^.*:", "") : name; }
    static Node child(Node node, String name) {
        if (node == null) return null;
        for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling())
            if (n.getNodeType() == Node.ELEMENT_NODE && M.equals(n.getNamespaceURI()) && local(n).equals(name)) return n;
        return null;
    }
    private static List<Node> namedChildren(Node node, String name) {
        List<Node> result = new ArrayList<>();
        if (node != null) for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling())
            if (n.getNodeType() == Node.ELEMENT_NODE && M.equals(n.getNamespaceURI()) && local(n).equals(name)) result.add(n);
        return result;
    }
    private static String property(Node props, String name, String fallback) { Node p = child(props, name); return p == null ? fallback : value(p); }
    private static String value(Node node) { return node instanceof Element e ? e.getAttributeNS(M, "val") : ""; }
    private static boolean on(Node node) { return node != null && !Set.of("0", "false", "off").contains(value(node)); }
    private static final class Context {
        int nodes;
        final Set<String> warnings = new LinkedHashSet<>();
        void warn(String message) { if (warnings.size() < 12) warnings.add(message); }
    }
    private static final class LimitException extends RuntimeException { LimitException(String message) { super(message); } }
}
