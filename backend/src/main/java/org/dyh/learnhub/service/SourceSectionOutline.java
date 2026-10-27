package org.dyh.learnhub.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative, source-local section ancestry for plain document exports without heading metadata. */
public final class SourceSectionOutline {
    private SourceSectionOutline() { }

    /** Original chunk position and the complete numbered heading of its section opening. */
    public record Parent(int seq, String heading) { }

    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "^[ \\t]{0,3}([1-9]\\d{0,2}(?:\\.[1-9]\\d{0,2}){0,5})[ \\t]+(.+?)\\s*$");
    // A large ordinary-unit budget keeps original lines intact while reusing the protected-block parser.
    private static final TextChunker.Options OUTLINE_UNITS =
            new TextChunker.Options(Integer.MAX_VALUE, 0, Integer.MAX_VALUE);

    /**
     * Returns the nearest verified, non-root ancestor opening for each chunk in a numbered subsection.
     * A parent must occur explicitly, below an explicit root, with at least two ordered direct children.
     * Existing heading metadata takes precedence; neither chunk boundaries nor text are changed.
     */
    public static Map<Integer, Parent> parents(List<TextChunker.Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()
                || chunks.stream().anyMatch(chunk -> chunk != null
                && chunk.heading() != null && !chunk.heading().isBlank())) return Map.of();

        List<Node> active = new ArrayList<>();
        Map<String, Node> currentRoot = new HashMap<>();
        List<Node> atChunk = new ArrayList<>(chunks.size());
        for (int seq = 0; seq < chunks.size(); seq++) {
            TextChunker.Chunk chunk = chunks.get(seq);
            if (chunk == null) {
                active.clear();
                currentRoot.clear();
                atChunk.add(null);
                continue;
            }
            for (TextChunker.Unit unit : TextChunker.structuralUnits(chunk.text(), OUTLINE_UNITS)) {
                if (unit.protectedBlock()) continue;
                for (String line : unit.text().split("\\n", -1)) {
                    Heading heading = heading(line);
                    if (heading == null) continue;
                    int level = heading.parts().length;
                    Node parent = level == 1 ? null
                            : active.size() >= level - 1 ? active.get(level - 2) : null;
                    if (level > 1 && (parent == null
                            || !heading.number().startsWith(parent.number + "."))) {
                        // A new, disconnected section is a barrier, never an implicit old-section child.
                        active.clear();
                        currentRoot.clear();
                        continue;
                    }

                    Node seen = currentRoot.get(heading.number());
                    boolean overlap = seen != null && seen.title.equals(heading.title())
                            && seen.parent == parent && seen.lastSeenSeq >= seq - 1;
                    if (overlap) {
                        seen.lastSeenSeq = seq;
                        // Repeated parent headings in overlap must not move their opening or lose a child.
                        if (active.size() < level || active.get(level - 1) != seen) {
                            truncate(active, level - 1);
                            active.add(seen);
                        }
                        continue;
                    }

                    if (level == 1) {
                        active.clear();
                        currentRoot.clear();
                    } else {
                        truncate(active, level - 1);
                    }
                    Node node = new Node(heading.number(), heading.title(), level, seq, parent);
                    if (parent != null) parent.child(heading.parts()[level - 1]);
                    active.add(node);
                    currentRoot.put(node.number, node);
                }
            }
            atChunk.add(active.isEmpty() ? null : active.getLast());
        }

        Map<Integer, Parent> result = new LinkedHashMap<>();
        for (int seq = 0; seq < atChunk.size(); seq++) {
            Node section = atChunk.get(seq);
            if (section == null || section.level < 3) continue;
            for (Node parent = section.parent; parent != null && parent.level >= 2; parent = parent.parent) {
                if (parent.orderedChildren && parent.children >= 2 && parent.seq != seq) {
                    result.put(seq, new Parent(parent.seq, parent.number + " " + parent.title));
                    break;
                }
            }
        }
        return Map.copyOf(result);
    }

    private static void truncate(List<Node> active, int size) {
        while (active.size() > size) active.removeLast();
    }

    private static Heading heading(String line) {
        Matcher matcher = NUMBERED_HEADING.matcher(line);
        if (!matcher.matches()) return null;
        String title = matcher.group(2).strip();
        if (title.length() < 2 || title.length() > 96 || title.split("\\s+").length > 12
                || title.matches(".*[=<>|\\\\$\\[\\]{};!?。！？；].*")
                || title.indexOf(':') >= 0 && title.indexOf(':') != title.length() - 1
                || title.indexOf('：') >= 0 && title.indexOf('：') != title.length() - 1) return null;
        int first = title.codePointAt(0);
        if (!Character.isUpperCase(first) && Character.UnicodeScript.of(first) != Character.UnicodeScript.HAN)
            return null;
        // Reject a sentence followed by more prose; a single final period is common in exported headings.
        String body = title.endsWith(".") ? title.substring(0, title.length() - 1) : title;
        if (body.indexOf('.') >= 0 || body.indexOf(',') >= 0 || body.indexOf('，') >= 0) return null;
        String number = matcher.group(1);
        String[] numbers = number.split("\\.");
        int[] parts = new int[numbers.length];
        for (int i = 0; i < numbers.length; i++) parts[i] = Integer.parseInt(numbers[i]);
        return new Heading(number, title, parts);
    }

    private record Heading(String number, String title, int[] parts) { }

    private static final class Node {
        final String number;
        final String title;
        final int level;
        final int seq;
        final Node parent;
        int lastSeenSeq;
        int lastChild;
        int children;
        boolean orderedChildren = true;

        Node(String number, String title, int level, int seq, Node parent) {
            this.number = number;
            this.title = title;
            this.level = level;
            this.seq = seq;
            this.lastSeenSeq = seq;
            this.parent = parent;
        }

        void child(int number) {
            if (number <= lastChild) orderedChildren = false;
            children++;
            lastChild = number;
        }
    }
}
