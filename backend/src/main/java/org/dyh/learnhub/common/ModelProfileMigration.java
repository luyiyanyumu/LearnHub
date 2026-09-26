package org.dyh.learnhub.common;

import org.dyh.learnhub.service.ModelProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动后把**老模型配置迁移成模型档案**。
 *
 * <p>为什么必须显式做这一步：改造前模型配置是 {@code ai.model / ai.base_url / ai.api_key}
 * 三个设置键（外加 {@code ai.wiki_*} 一组"本地目标"）。改成档案列表之后，
 * 如果不迁移，用户升级后打开设置会发现**密钥"消失"了** —— 那是最糟的一种体验。
 *
 * <p>放在 {@code ApplicationReadyEvent}（而不是 {@code @PostConstruct}）：
 * 那时数据源与设置服务都已就绪，且迁移只读设置、写档案，不会与启动流程抢事务。
 * 迁移本身是幂等的：只要已有任一档案就直接返回。
 */
@Component
public class ModelProfileMigration {

    private static final Logger log = LoggerFactory.getLogger(ModelProfileMigration.class);

    private final ModelProfileService profiles;

    public ModelProfileMigration(ModelProfileService profiles) {
        this.profiles = profiles;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        try {
            int n = profiles.migrateLegacyIfEmpty();
            if (n > 0) {
                log.info("已把原有模型配置迁移为 {} 个模型档案（可在设置里继续新增）", n);
            }
        } catch (Exception e) {
            // 迁移失败不该拦住启动：档案为空时 ModelProfileService.resolve 会回退老设置
            log.warn("模型档案迁移失败（不影响启动，解析会回退老配置）：{}", e.toString());
        }
    }
}
