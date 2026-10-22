package org.dyh.learnhub.ai;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Independent section tasks; only the caller publishes progress and assembles the note. */
final class LongNoteSubAgents {
    private static final int MAX_CONCURRENT = 3;
    private static final AtomicInteger POOL_IDS = new AtomicInteger();
    private static final String BUDGET = "round_budget_exhausted";
    private static final String INTERRUPTED = "long_note_interrupted";

    private LongNoteSubAgents() {}

    record Section(String heading, String points) {}
    record Part(int index, Section section, String body, boolean complete, String code) {}
    record Result(List<Part> parts, String code) {
        Result { parts = List.copyOf(parts); }
    }

    @FunctionalInterface
    interface Writer {
        DeepSeekClient.ChatResult write(Section section) throws Exception;
    }

    static Result generate(List<Section> sections, Writer writer, LongSupplier remainingMillis,
                           Consumer<Part> observer) {
        List<Section> plan = List.copyOf(sections);
        Objects.requireNonNull(writer);
        Objects.requireNonNull(remainingMillis);
        Objects.requireNonNull(observer);
        if (plan.isEmpty()) return new Result(List.of(), "");

        int poolId = POOL_IDS.incrementAndGet();
        AtomicInteger threadIds = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT, task -> {
            Thread thread = new Thread(task, "long-note-subagent-" + poolId + "-" + threadIds.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        var completed = new ExecutorCompletionService<Part>(executor);
        Map<Future<Part>, Integer> pending = new HashMap<>();
        Part[] parts = new Part[plan.size()];
        int next = 0;
        String stopped = "";
        boolean interrupted = false;
        try {
            while (next < plan.size() || !pending.isEmpty()) {
                if (Thread.interrupted()) throw new InterruptedException();
                while (next < plan.size() && pending.size() < MAX_CONCURRENT
                        && remainingMillis.getAsLong() > 0) {
                    int index = next++;
                    Future<Part> future = completed.submit(() -> write(index, plan.get(index), writer, remainingMillis));
                    pending.put(future, index);
                }
                Future<Part> future = completed.poll();
                if (future == null) {
                    long remaining = remainingMillis.getAsLong();
                    if (remaining <= 0 || pending.isEmpty()) {
                        stopped = BUDGET;
                        break;
                    }
                    future = completed.poll(remaining, TimeUnit.MILLISECONDS);
                    if (future == null) {
                        stopped = BUDGET;
                        break;
                    }
                }
                collect(future, pending, plan, parts, observer);
            }
        } catch (InterruptedException e) {
            stopped = INTERRUPTED;
            interrupted = true;
        } finally {
            // A completed response is still useful when the deadline expires just before it is observed.
            Future<Part> ready;
            while ((ready = completed.poll()) != null) {
                collect(ready, pending, plan, parts, observer);
            }
            for (var entry : pending.entrySet()) {
                Future<Part> future = entry.getKey();
                if (future.isDone() && !future.isCancelled()) {
                    publish(read(future, entry.getValue(), plan), parts, observer);
                } else if (!future.cancel(true) && future.isDone() && !future.isCancelled()) {
                    // Completion can win the race with cancellation: its real response must not be discarded.
                    publish(read(future, entry.getValue(), plan), parts, observer);
                }
            }
            // Do not use ExecutorService.close(): an uncooperative model client must not hold the caller open.
            executor.shutdownNow();
            if (interrupted) Thread.currentThread().interrupt();
        }

        List<Part> ordered = new ArrayList<>(plan.size());
        for (int index = 0; index < parts.length; index++) {
            if (parts[index] == null) {
                publish(incomplete(index, plan.get(index), stopped.isEmpty()
                        ? "long_note_generation_failed" : stopped), parts, observer);
            }
            ordered.add(parts[index]);
        }
        String firstFailure = ordered.stream().filter(part -> !part.complete())
                .map(Part::code).findFirst().orElse("");
        String code = firstFailure.isEmpty() ? "" : stopped.isEmpty() ? firstFailure : stopped;
        if (interrupted) Thread.currentThread().interrupt();
        return new Result(ordered, code);
    }

    private static Part write(int index, Section section, Writer writer, LongSupplier remainingMillis) {
        try {
            if (Thread.currentThread().isInterrupted()) return incomplete(index, section, INTERRUPTED);
            if (remainingMillis.getAsLong() <= 0) return incomplete(index, section, BUDGET);
            DeepSeekClient.ChatResult response = writer.write(section);
            String body = response == null || response.message() == null ? ""
                    : response.message().path("content").asText("");
            String code = response != null && response.truncated() ? "long_note_output_limit"
                    : response != null && !"stop".equals(response.finishReason()) ? "long_note_generation_incomplete"
                    : body.isBlank() ? "long_note_empty"
                    : headingOnly(body, section) ? "long_note_heading_only" : "";
            return new Part(index, section, body, code.isEmpty(), code);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return incomplete(index, section, failureCode(e));
        }
    }

    private static boolean headingOnly(String body, Section section) {
        String heading = section.heading() == null ? "" : section.heading().strip();
        return body.lines().filter(line -> !line.isBlank()).allMatch(line -> {
            String text = line.stripTrailing();
            // Four spaces or a tab start indented code, whose # lines are not Markdown headings.
            return text.matches(" {0,3}#{1,6}(?:[ \\t]+.*)?") || text.matches(" {0,3}[=-]{3,}")
                    || text.replaceFirst("^ {0,3}", "").equals(heading);
        });
    }

    private static void collect(Future<Part> future, Map<Future<Part>, Integer> pending, List<Section> plan,
                                Part[] parts, Consumer<Part> observer) {
        Integer index = pending.remove(future);
        if (index != null) publish(read(future, index, plan), parts, observer);
    }

    private static Part read(Future<Part> future, int index, List<Section> plan) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return incomplete(index, plan.get(index), INTERRUPTED);
        } catch (ExecutionException e) {
            return incomplete(index, plan.get(index), failureCode(e.getCause()));
        } catch (CancellationException e) {
            return incomplete(index, plan.get(index), INTERRUPTED);
        }
    }

    private static String failureCode(Throwable failure) {
        if (failure instanceof InterruptedException || failure instanceof CancellationException) return INTERRUPTED;
        if (failure instanceof HttpTimeoutException || failure instanceof SocketTimeoutException
                || failure instanceof TimeoutException) return "long_note_generation_timeout";
        return "long_note_generation_failed";
    }

    private static Part incomplete(int index, Section section, String code) {
        return new Part(index, section, "", false, code);
    }

    private static void publish(Part part, Part[] parts, Consumer<Part> observer) {
        if (parts[part.index()] != null) return;
        parts[part.index()] = part;
        try {
            observer.accept(part);
        } catch (RuntimeException ignored) {
            // A disconnected progress listener cannot discard a completed task or trigger another model request.
        }
    }
}
