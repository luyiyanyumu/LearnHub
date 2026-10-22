package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic model answers and latch-controlled tasks only; no network, database, or sleeps. */
class LongNoteSubAgentsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void boundsConcurrencyAndPublishesOnCallerInCompletionOrderWhileReturningOutlineOrder() throws Exception {
        List<LongNoteSubAgents.Section> plan = sections(6);
        CountDownLatch[] release = latches(6);
        CountDownLatch[] observed = latches(6);
        CountDownLatch firstWave = new CountDownLatch(3);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<Thread> workers = new CopyOnWriteArrayList<>();
        List<Thread> observers = new CopyOnWriteArrayList<>();
        List<Integer> completionOrder = new CopyOnWriteArrayList<>();
        var call = new Call(() -> LongNoteSubAgents.generate(plan, section -> {
            int index = Integer.parseInt(section.points());
            workers.add(Thread.currentThread());
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            firstWave.countDown();
            try {
                assertTrue(release[index].await(5, TimeUnit.SECONDS));
                return answer("合成正文 " + index, "stop");
            } finally {
                active.decrementAndGet();
            }
        }, () -> 10_000, part -> {
            observers.add(Thread.currentThread());
            completionOrder.add(part.index());
            observed[part.index()].countDown();
        }));
        try {
            await(firstWave);
            for (int index : new int[]{2, 1, 0}) {
                release[index].countDown();
                await(observed[index]);
            }
            for (int index = 3; index < release.length; index++) release[index].countDown();
            LongNoteSubAgents.Result result = call.result();

            assertEquals(3, peak.get());
            assertEquals(List.of(2, 1, 0), completionOrder.subList(0, 3));
            assertEquals(IntStream.range(0, 6).boxed().toList(), result.parts().stream()
                    .map(LongNoteSubAgents.Part::index).toList());
            assertTrue(result.parts().stream().allMatch(LongNoteSubAgents.Part::complete));
            assertEquals("", result.code());
            assertEquals(6, workers.size());
            assertTrue(workers.stream().allMatch(Thread::isDaemon));
            assertTrue(observers.stream().allMatch(thread -> thread == call.thread));
        } finally {
            for (CountDownLatch latch : release) latch.countDown();
            call.close();
        }
        assertStopped(workers);
    }

    @Test void failedTasksDoNotDiscardSuccessfulOrTruncatedBodiesAndEveryFailureIsExplicit() {
        List<LongNoteSubAgents.Section> plan = sections(8);
        List<LongNoteSubAgents.Part> observed = new ArrayList<>();
        Thread caller = Thread.currentThread();
        LongNoteSubAgents.Result result = LongNoteSubAgents.generate(plan, section -> switch (section.points()) {
            case "0" -> throw new IllegalStateException("synthetic model failure");
            case "1" -> answer("  合成有效正文  \n", "stop");
            case "2" -> answer("合成但尚未完成的正文", "length");
            case "3" -> answer("# 合成标题\n\n## 合成小标题", "stop");
            case "4" -> answer(" \n", "stop");
            case "5" -> null;
            case "6" -> throw new HttpTimeoutException("synthetic timeout");
            default -> throw new InterruptedException("synthetic worker interruption");
        }, () -> 10_000, part -> {
            assertSame(caller, Thread.currentThread());
            observed.add(part);
        });

        assertEquals(8, observed.size());
        assertEquals(List.of("long_note_generation_failed", "", "long_note_output_limit", "long_note_heading_only",
                        "long_note_empty", "long_note_empty", "long_note_generation_timeout", "long_note_interrupted"),
                result.parts().stream().map(LongNoteSubAgents.Part::code).toList());
        assertEquals("long_note_generation_failed", result.code());
        assertTrue(result.parts().get(1).complete());
        assertEquals("  合成有效正文  \n", result.parts().get(1).body());
        assertEquals("合成但尚未完成的正文", result.parts().get(2).body());
        assertFalse(result.parts().get(2).complete());
        assertTrue(result.parts().stream().filter(part -> part.index() != 1).noneMatch(LongNoteSubAgents.Part::complete));
        assertFalse(Thread.currentThread().isInterrupted(), "子任务中断不能污染调用线程");
    }

    @Test void anAlreadyExpiredBudgetStartsNoWriterAndStillReportsEveryUnfinishedSection() {
        AtomicInteger calls = new AtomicInteger();
        List<LongNoteSubAgents.Part> observed = new ArrayList<>();

        LongNoteSubAgents.Result result = LongNoteSubAgents.generate(sections(5), section -> {
            calls.incrementAndGet();
            return answer("不会调用的合成正文", "stop");
        }, () -> 0, observed::add);

        assertEquals(0, calls.get());
        assertEquals(5, observed.size());
        assertEquals("round_budget_exhausted", result.code());
        assertTrue(result.parts().stream().allMatch(part -> !part.complete()
                && part.body().isEmpty() && "round_budget_exhausted".equals(part.code())));
    }

    @Test void sharedDeadlineCancelsOutstandingTasksAndDoesNotStartLaterSections() throws Exception {
        AtomicLong remaining = new AtomicLong(10_000);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstWave = new CountDownLatch(3);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(2);
        List<Thread> workers = new CopyOnWriteArrayList<>();
        var call = new Call(() -> LongNoteSubAgents.generate(sections(6), section -> {
            calls.incrementAndGet();
            workers.add(Thread.currentThread());
            firstWave.countDown();
            if ("0".equals(section.points())) {
                releaseFirst.await();
                return answer("期限前已开始的合成正文", "stop");
            }
            try {
                blocked.await();
                return answer("不应等到的合成正文", "stop");
            } catch (InterruptedException e) {
                cancelled.countDown();
                throw e;
            }
        }, remaining::get, part -> {}));
        try {
            await(firstWave);
            remaining.set(0);
            releaseFirst.countDown();
            LongNoteSubAgents.Result result = call.result();
            await(cancelled);

            assertEquals(3, calls.get());
            assertEquals("round_budget_exhausted", result.code());
            assertTrue(result.parts().getFirst().complete());
            assertEquals("期限前已开始的合成正文", result.parts().getFirst().body());
            assertTrue(result.parts().subList(1, 6).stream().allMatch(part -> !part.complete()
                    && part.body().isEmpty() && "round_budget_exhausted".equals(part.code())));
        } finally {
            releaseFirst.countDown();
            blocked.countDown();
            call.close();
        }
        assertStopped(workers);
    }

    @Test void callerInterruptionCancelsEveryWorkerAndRestoresTheCallerInterruptFlag() throws Exception {
        CountDownLatch firstWave = new CountDownLatch(3);
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(3);
        List<Thread> workers = new CopyOnWriteArrayList<>();
        List<Thread> observers = new CopyOnWriteArrayList<>();
        var call = new Call(() -> LongNoteSubAgents.generate(sections(6), section -> {
            workers.add(Thread.currentThread());
            firstWave.countDown();
            try {
                blocked.await();
                return answer("不会等到的合成正文", "stop");
            } catch (InterruptedException e) {
                cancelled.countDown();
                throw e;
            }
        }, () -> 10_000, part -> observers.add(Thread.currentThread())));
        try {
            await(firstWave);
            call.thread.interrupt();
            LongNoteSubAgents.Result result = call.result();
            await(cancelled);

            assertEquals("long_note_interrupted", result.code());
            assertTrue(call.interrupted.get());
            assertEquals(3, workers.size());
            assertEquals(6, observers.size());
            assertTrue(observers.stream().allMatch(thread -> thread == call.thread));
            assertTrue(result.parts().stream().allMatch(part -> !part.complete()
                    && "long_note_interrupted".equals(part.code())));
        } finally {
            blocked.countDown();
            call.close();
        }
        assertStopped(workers);
    }

    @Test void brokenProgressObserverDoesNotRepeatOrDiscardModelRequests() {
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger notices = new AtomicInteger();
        LongNoteSubAgents.Result result = LongNoteSubAgents.generate(sections(5), section -> {
            writes.incrementAndGet();
            return answer("合成完整正文", "stop");
        }, () -> 10_000, part -> {
            notices.incrementAndGet();
            throw new IllegalStateException("synthetic disconnected observer");
        });

        assertEquals(5, writes.get());
        assertEquals(5, notices.get());
        assertEquals("", result.code());
        assertTrue(result.parts().stream().allMatch(LongNoteSubAgents.Part::complete));
    }

    @Test void indentedCodeAndItsCommentMarkersArePreservedAsRealBody() {
        String spaces = "    # 合成程序注释\n    ## 仍然是代码注释\n";
        String tabs = "\t# 合成程序注释\n";
        List<LongNoteSubAgents.Section> plan = sections(3);

        LongNoteSubAgents.Result result = LongNoteSubAgents.generate(plan, section -> answer(
                switch (section.points()) {
                    case "0" -> spaces;
                    case "1" -> tabs;
                    default -> "   ## 合成标题\n# 另一个合成标题";
                }, "stop"), () -> 10_000, part -> {});

        assertTrue(result.parts().get(0).complete());
        assertEquals(spaces, result.parts().get(0).body());
        assertTrue(result.parts().get(1).complete());
        assertEquals(tabs, result.parts().get(1).body());
        assertFalse(result.parts().get(2).complete());
        assertEquals("long_note_heading_only", result.parts().get(2).code());
    }

    @Test void nonStopFinishReasonsKeepActualBodyAndDoNotReportACompleteSection() {
        List<String> reasons = new ArrayList<>();
        reasons.add("content_filter");
        reasons.add("tool_calls");
        reasons.add(null);
        String body = "合成提供商返回的部分正文";

        LongNoteSubAgents.Result result = LongNoteSubAgents.generate(sections(reasons.size()), section ->
                answer(body, reasons.get(Integer.parseInt(section.points()))), () -> 10_000, part -> {});

        assertEquals("long_note_generation_incomplete", result.code());
        assertTrue(result.parts().stream().allMatch(part -> !part.complete()
                && "long_note_generation_incomplete".equals(part.code()) && body.equals(part.body())));
    }

    private static List<LongNoteSubAgents.Section> sections(int count) {
        return IntStream.range(0, count).mapToObj(index -> new LongNoteSubAgents.Section(
                "合成章节 " + index, String.valueOf(index))).toList();
    }

    private static CountDownLatch[] latches(int count) {
        return IntStream.range(0, count).mapToObj(ignored -> new CountDownLatch(1)).toArray(CountDownLatch[]::new);
    }

    private static DeepSeekClient.ChatResult answer(String body, String reason) {
        return new DeepSeekClient.ChatResult(JSON.createObjectNode().put("content", body), reason, 10, 20, 0);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "合成任务应通过显式锁存器推进");
    }

    private static void assertStopped(List<Thread> workers) throws InterruptedException {
        for (Thread worker : workers.stream().distinct().toList()) {
            worker.join(5_000);
            assertFalse(worker.isAlive(), "协作式模型任务应在取消后退出");
        }
    }

    private static final class Call {
        private final AtomicReference<LongNoteSubAgents.Result> result = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean interrupted = new AtomicBoolean();
        private final Thread thread;

        Call(Supplier<LongNoteSubAgents.Result> task) {
            thread = new Thread(() -> {
                try {
                    result.set(task.get());
                    interrupted.set(Thread.currentThread().isInterrupted());
                } catch (Throwable error) {
                    failure.set(error);
                }
            }, "synthetic-long-note-caller");
            thread.setDaemon(true);
            thread.start();
        }

        LongNoteSubAgents.Result result() throws InterruptedException {
            thread.join(5_000);
            assertFalse(thread.isAlive(), "子智能体协调器应及时返回");
            if (failure.get() != null) throw new AssertionError("合成子智能体任务失败", failure.get());
            assertNotNull(result.get());
            return result.get();
        }

        void close() throws InterruptedException {
            thread.interrupt();
            thread.join(5_000);
        }
    }
}
