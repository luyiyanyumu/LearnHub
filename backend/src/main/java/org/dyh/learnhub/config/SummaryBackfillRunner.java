package org.dyh.learnhub.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dyh.learnhub.service.NoteService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时补齐历史笔记的 note.summary。
 *
 * <p>背景：摘要列是后加的（见 schema.sql 的增量迁移），列表接口改成只 SELECT 摘要
 * 之后，没有摘要的老笔记在列表里会显示成空。这里启动跑一次把它们补上。
 *
 * <p>为什么用 ApplicationRunner 而不是写进 data.sql：摘要算法是 Java 里的几行正则
 * （{@code NoteService#plainSummary}），用 SQL 重写一遍等于同样的规则维护两份，
 * 迟早会不一致。
 *
 * <p>刻意「失败不影响启动」：这是补数据的附加动作，就算炸了也只影响列表页的摘要显示，
 * 不该因此让整个应用起不来。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryBackfillRunner implements ApplicationRunner {

    private final NoteService noteService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int fixed = noteService.backfillSummaries();
            if (fixed > 0) {
                log.info("已为 {} 篇历史笔记补齐摘要（note.summary）", fixed);
            } else {
                // 刻意用 debug：正常启动天天 0 篇，info 会变成噪音。
                // 但排查「摘要是不是没补上」时，把它打开就能一眼看到确实跑过了。
                log.debug("摘要补齐检查完成：无需处理（所有笔记的 note.summary 都已就绪）");
            }
        } catch (Exception e) {
            log.warn("历史笔记摘要补齐失败（不影响启动，列表页摘要可能为空）: {}", e.toString());
        }
    }
}
