import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reflection bridge executes the actual product classes, isolated from Spring and the database. */
public final class ChunkExport {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        var mapper = new ObjectMapper();
        JsonNode input = mapper.readTree(Files.readString(Path.of(args[1])));
        Class<?> chunker = Class.forName("org.dyh.learnhub.service.TextChunker");
        Class<?> optionsClass = Class.forName("org.dyh.learnhub.service.TextChunker$Options");
        Object options = optionsClass.getConstructor(int.class, int.class, int.class).newInstance(800, 120, 1200);
        Method split = chunker.getMethod("splitWithHeadings", String.class, optionsClass);
        var output = new ArrayList<Map<String, Object>>();
        for (JsonNode doc : input.path("documents")) {
            String text = doc.path("text").asText();
            long started = System.nanoTime();
            List<?> pieces;
            boolean semanticApplied = false;
            if (mode.equals("units")) {
                pieces = (List<?>) chunker.getMethod("structuralUnits", String.class, optionsClass)
                        .invoke(null, text, options);
            } else if (mode.equals("semantic") && doc.path("kind").asText().equals("file") && text.length() >= 4000) {
                List<?> units = (List<?>) chunker.getMethod("structuralUnits", String.class, optionsClass)
                        .invoke(null, text, options);
                var vectors = new ArrayList<float[]>();
                for (JsonNode vector : doc.path("vectors")) {
                    float[] array = new float[vector.size()];
                    for (int i = 0; i < array.length; i++) array[i] = (float) vector.get(i).asDouble();
                    vectors.add(array);
                }
                Class<?> semantic = Class.forName("org.dyh.learnhub.service.SemanticChunker");
                pieces = (List<?>) semantic.getMethod("split", List.class, optionsClass, List.class, int.class)
                        .invoke(null, units, options, vectors, 85);
                semanticApplied = true;
            } else {
                pieces = (List<?>) split.invoke(null, text, options);
            }
            double elapsed = (System.nanoTime() - started) / 1_000_000.0;
            var exported = new ArrayList<Map<String, Object>>();
            for (Object piece : pieces) {
                var item = new LinkedHashMap<String, Object>();
                item.put("heading", piece.getClass().getMethod("heading").invoke(piece));
                item.put("text", piece.getClass().getMethod("text").invoke(piece));
                if (mode.equals("units")) item.put("protected", piece.getClass().getMethod("protectedBlock").invoke(piece));
                exported.add(item);
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("id", doc.path("id").asText());
            result.put("pieces", exported);
            result.put("split_ms", elapsed);
            result.put("semantic_applied", semanticApplied);
            output.add(result);
        }
        System.out.write((mapper.writeValueAsString(Map.of("documents", output)) + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
