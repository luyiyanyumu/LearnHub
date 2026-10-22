package org.dyh.learnhub.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.dyh.learnhub.entity.FileInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Exercise the injected mapper SQL and parameter bindings without connecting to a database. */
class FileInfoMapperSqlTest {
    private static MappedStatement updateById;

    @BeforeAll
    static void registerMapper() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        var global = GlobalConfigUtils.defaults();
        global.setSqlInjector(new DefaultSqlInjector());
        GlobalConfigUtils.setGlobalConfig(configuration, global);
        configuration.addMapper(FileInfoMapper.class);
        updateById = configuration.getMappedStatement(FileInfoMapper.class.getName() + ".updateById");
    }

    @Test
    void successfulExtractionClearsThePreviouslyStoredError() throws Exception {
        FileInfo info = syntheticFile();
        info.setTextError("文件超过 20MB，未抽取正文");
        info.setTextStatus("ok");
        info.setTextContent("合成文档的抽取正文。");
        info.setTextChars(info.getTextContent().length());
        info.setTextError(null);

        Map<String, Object> parameters = Map.of("et", info);
        BoundSql sql = updateById.getBoundSql(parameters);
        int errorIndex = textErrorParameterIndex(sql);
        assertThat(updateById.getConfiguration().newMetaObject(parameters).getValue("et.textError")).isNull();
        PreparedStatement statement = mock(PreparedStatement.class);
        new DefaultParameterHandler(updateById, parameters, sql).setParameters(statement);
        verify(statement).setNull(errorIndex, updateById.getConfiguration().getJdbcTypeForNull().TYPE_CODE);
    }

    @Test
    void failedExtractionStillBindsTheCurrentErrorMessage() throws Exception {
        FileInfo info = syntheticFile();
        info.setTextStatus("failed");
        info.setTextError("合成文件解析失败");

        Map<String, Object> parameters = Map.of("et", info);
        BoundSql sql = updateById.getBoundSql(parameters);
        int errorIndex = textErrorParameterIndex(sql);
        PreparedStatement statement = mock(PreparedStatement.class);
        new DefaultParameterHandler(updateById, parameters, sql).setParameters(statement);
        verify(statement).setString(errorIndex, info.getTextError());
    }

    private static int textErrorParameterIndex(BoundSql sql) {
        assertThat(sql.getSql()).containsPattern("\\btext_error\\s*=\\s*\\?");
        int index = IntStream.range(0, sql.getParameterMappings().size())
                .filter(i -> "et.textError".equals(sql.getParameterMappings().get(i).getProperty()))
                .findFirst().orElseThrow(() -> new AssertionError("text_error parameter is missing"));
        return index + 1;
    }

    private static FileInfo syntheticFile() {
        FileInfo info = new FileInfo();
        info.setId(91001L);
        info.setOriginName("合成资料.pdf");
        info.setStoreName("synthetic-file.pdf");
        info.setExt("pdf");
        return info;
    }
}
