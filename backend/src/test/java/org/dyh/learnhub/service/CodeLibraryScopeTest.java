package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dyh.learnhub.entity.CodeSnippet;
import org.dyh.learnhub.entity.CodeSymbol;
import org.dyh.learnhub.mapper.CodeRepoMapper;
import org.dyh.learnhub.mapper.CodeSnippetMapper;
import org.dyh.learnhub.mapper.CodeSymbolMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CodeLibraryScopeTest {
    private final CodeSnippetMapper snippets = mock(CodeSnippetMapper.class);
    private final CodeSymbolMapper symbols = mock(CodeSymbolMapper.class);
    private CodeLibraryService service;

    @BeforeEach void setup() {
        for (Class<?> type : List.of(CodeSnippet.class, CodeSymbol.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "code-scope-test"), type);
        service = new CodeLibraryService(mock(CodeRepoMapper.class), snippets, symbols, mock(CodeIndexService.class));
    }

    @Test void keywordScopeAndPathAreInSqlBeforeLimit() {
        when(snippets.selectList(any())).thenReturn(List.of());
        service.search("connect", "keyword", "java", 7L, false, "src/_private", 8);
        var query = captureSnippets();
        assertThat(query.getSqlSegment()).contains("lang =", "repo_id =", "file_path LIKE", "LIMIT 100");
        assertThat(query.getParamNameValuePairs().values()).contains("java", 7L, "%src/\\_private%");
    }

    @Test void symbolScopeUsesParameterizedExistsBeforeGlobalSymbolLimit() {
        when(symbols.selectList(any())).thenReturn(List.of());
        service.search("connect", "symbol", "java' OR 1=1 --", 7L, false, "src", 8);
        var captor = org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(symbols, times(2)).selectList(captor.capture());
        for (Object captured : captor.getAllValues()) {
            var query = (LambdaQueryWrapper<?>) captured;
            assertThat(query.getSqlSegment()).contains("EXISTS (SELECT 1 FROM code_snippet scoped", "scoped.lang =", "scoped.repo_id =", "scoped.file_path LIKE", "LIMIT 100");
            assertThat(query.getSqlSegment()).doesNotContain("java' OR 1=1");
            assertThat(query.getParamNameValuePairs().values()).contains("java' OR 1=1 --", 7L, "%src%");
        }
    }

    @Test void blankSearchKeepsUnassignedScopeInsteadOfListingAllRepos() {
        when(snippets.selectList(any())).thenReturn(List.of());
        service.search("", "all", "python", 7L, true, 8);
        var query = captureSnippets();
        assertThat(query.getSqlSegment()).contains("repo_id IS NULL", "lang =").doesNotContain("repo_id =");
    }

    @Test void readKeepsLeadingBlankLinesAndNormalizesCrLfWithCorrectLineNumbers() {
        when(snippets.selectOne(any())).thenReturn(snippet("\r\n\r\nconnect();\r\n"));
        Map<String, Object> result = service.readWindow(1L, "java", 7L, false, 1, 3);
        assertThat(result).containsEntry("quote", "\n\nconnect();").containsEntry("startLine", 1).containsEntry("endLine", 3);
        assertThat(result.get("numberedCode")).isEqualTo("1: \n2: \n3: connect();\n");
    }

    @Test void readWindowIsBoundedAndOversizedSingleLineIsNotPartiallyCited() {
        when(snippets.selectOne(any())).thenReturn(snippet("x".repeat(5000) + "\nnext();"));
        assertThat(service.readWindow(1L, "java", 7L, false, 1, Integer.MAX_VALUE)).isEmpty();
        assertThat(service.readWindow(1L, "java", 7L, false, Integer.MAX_VALUE, Integer.MAX_VALUE)).isEmpty();
        when(snippets.selectOne(any())).thenReturn(snippet("line();\n".repeat(200)));
        assertThat(service.readWindow(1L, "java", 7L, false, 1, Integer.MAX_VALUE)).containsEntry("endLine", 80);
    }

    @Test void missingScopedReadNeverLoadsByUnscopedId() {
        when(snippets.selectOne(any())).thenReturn(null);
        assertThat(service.readWindow(999L, "java", 7L, true, 1, 10)).isEmpty();
        verify(snippets, never()).selectById(any());
        var captor = org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(snippets).selectOne(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("repo_id IS NULL", "lang =", "id =");
    }

    private LambdaQueryWrapper<?> captureSnippets() {
        var captor = org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(snippets).selectList(captor.capture());
        return captor.getValue();
    }
    private static CodeSnippet snippet(String text) {
        CodeSnippet snippet = new CodeSnippet(); snippet.setId(1L); snippet.setTitle("测试片段");
        snippet.setLang("java"); snippet.setCode(text); snippet.setLineCount(text.split("\n", -1).length); return snippet;
    }
}
