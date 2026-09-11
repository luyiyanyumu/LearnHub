package org.dyh.learnhub.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.stdout.StdOutImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(100L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    /**
     * 给 StdOutImpl 套一层脱敏，避免 app_setting 表里的 API Key / 提示词全文
     * 被 SQL 参数日志原样打到控制台。
     * <p>
     * 刻意「只在原本就是 StdOutImpl 时才替换」：如果哪天在 application.yml 里
     * 配了别的 log-impl（比如 Slf4jImpl 或干脆关掉），这里就不插手。
     */
    @Bean
    public ConfigurationCustomizer maskingStdoutLogCustomizer() {
        return configuration -> {
            Class<? extends Log> current = configuration.getLogImpl();
            if (current == null || StdOutImpl.class.equals(current)) {
                configuration.setLogImpl(MaskingStdOutImpl.class);
            }
        };
    }
}

