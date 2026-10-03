package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.common.KnowledgeChangedEvent;
import org.dyh.learnhub.common.PageResult;
import org.dyh.learnhub.dto.NoteDTO;
import org.dyh.learnhub.dto.NoteEditRequest;
import org.dyh.learnhub.dto.NoteTagRow;
import org.dyh.learnhub.entity.Category;
import org.dyh.learnhub.entity.Note;
import org.dyh.learnhub.entity.Tag;
import org.dyh.learnhub.mapper.CategoryMapper;
import org.dyh.learnhub.mapper.NoteMapper;
import org.dyh.learnhub.mapper.TagMapper;
import org.dyh.learnhub.vo.NoteVO;
import org.dyh.learnhub.vo.NoteEditVO;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NoteService {

    private final NoteMapper noteMapper;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final LearningActivityService learningActivity;
    /** 内容变更广播：页面保存、智能体写入都会发；wiki 的自动增量更新订阅它 */
    private final ApplicationEventPublisher events;
    private final NoteContentEditor contentEditor;

    /** 完整验证整批操作后返回预览，数据库零改动。 */
    public NoteEditVO previewEdit(Long id, NoteEditRequest request) {
        Note note = noteMapper.selectById(id);
        if (note == null) throw new IllegalArgumentException("笔记不存在: " + id);
        var edit = contentEditor.apply(note.getContent(), request);
        return editVO(note, edit);
    }

    /** 只更新正文与摘要，标题、分类、标签等不参与这次写入。 */
    @Transactional
    public NoteEditVO editContent(Long id, NoteEditRequest request) {
        Note note = noteMapper.selectForEdit(id);
        if (note == null) throw new IllegalArgumentException("笔记不存在: " + id);
        var edit = contentEditor.apply(note.getContent(), request);
        NoteEditVO result = editVO(note, edit);
        if (result.changed()) {
            noteMapper.update(null, Wrappers.<Note>lambdaUpdate().eq(Note::getId, id)
                    .set(Note::getContent, edit.content())
                    .set(Note::getSummary, plainSummary(edit.content()))
                    .set(Note::getUpdatedAt, java.time.LocalDateTime.now()));
            learningActivity.record("note", id);
            publishChanged(note.getCategoryId());
        }
        return result;
    }

    private NoteEditVO editVO(Note note, NoteContentEditor.Edit edit) {
        String before = NoteContentEditor.hash(note.getContent());
        String after = NoteContentEditor.hash(edit.content());
        return new NoteEditVO(note.getId(), note.getTitle(), before, after, !before.equals(after), edit.changes());
    }

    /**
     * 列表/总览用的查询骨架：刻意只选这几列，**不含 content**。
     * <p>
     * content 是 LONGTEXT，单篇 5000~8000 字，一页 10 条就是几十 KB 的无用传输；
     * 摘要已经落成 note.summary 一列，直接读就行。
     */
    private static LambdaQueryWrapper<Note> listQuery() {
        return Wrappers.<Note>lambdaQuery()
                .select(Note::getId, Note::getTitle, Note::getSummary,
                        Note::getCategoryId, Note::getCreatedAt, Note::getUpdatedAt);
    }

    /** 分页查询笔记：支持分类 / 标签 / 关键词过滤 */
    public PageResult<NoteVO> page(Long categoryId, Long tagId, String kw, long current, long size) {
        LambdaQueryWrapper<Note> wrapper = listQuery();
        if (categoryId != null && categoryId > 0) {
            wrapper.eq(Note::getCategoryId, categoryId);
        }
        if (tagId != null && tagId > 0) {
            List<Long> noteIds = noteMapper.selectNoteIdsByTag(tagId);
            wrapper.in(Note::getId, CollectionUtils.isEmpty(noteIds) ? List.of(-1L) : noteIds);
        }
        if (StringUtils.hasText(kw)) {
            String like = kw.trim();
            wrapper.and(w -> w.like(Note::getTitle, like).or().like(Note::getContent, like));
        }
        wrapper.orderByDesc(Note::getUpdatedAt);

        Page<Note> page = noteMapper.selectPage(new Page<>(current, size), wrapper);
        Map<Long, String> categoryNames = loadCategoryNames(
                page.getRecords().stream().map(Note::getCategoryId).collect(Collectors.toSet()));

        List<NoteVO> vos = page.getRecords().stream()
                .map(n -> toVO(n, false, categoryNames))
                .collect(Collectors.toList());
        attachTags(vos);
        return PageResult.of(page.getTotal(), vos);
    }

    /** 笔记详情：含正文与标签 */
    public NoteVO detail(Long id) {
        Note note = noteMapper.selectById(id);
        if (note == null) {
            throw new IllegalArgumentException("笔记不存在: " + id);
        }
        // 分类可能被删除/解绑为 null，此时无需查分类名
        Map<Long, String> categoryNames = note.getCategoryId() == null
                ? Map.of()
                : loadCategoryNames(List.of(note.getCategoryId()));
        NoteVO vo = toVO(note, true, categoryNames);
        vo.setTags(tagMapper.selectByNoteId(id));
        return vo;
    }

    /** 最近更新的 N 条笔记（总览页用） */
    public List<NoteVO> recent(int limit) {
        LambdaQueryWrapper<Note> wrapper = listQuery()
                .orderByDesc(Note::getUpdatedAt)
                .last("LIMIT " + limit);
        List<Note> list = noteMapper.selectList(wrapper);
        Map<Long, String> categoryNames = loadCategoryNames(
                list.stream().map(Note::getCategoryId).collect(Collectors.toSet()));
        return list.stream().map(n -> toVO(n, false, categoryNames)).collect(Collectors.toList());
    }

    /**
     * 批量装配标签（列表页）。
     * <p>
     * 原来这里是 {@code for (vo : vos) vo.setTags(tagMapper.selectByNoteId(vo.getId()))}，
     * 一页 N 条就是 N 次查询。现在是 1 次连表 + 内存分组。
     */
    private void attachTags(List<NoteVO> vos) {
        if (CollectionUtils.isEmpty(vos)) {
            return;
        }
        List<Long> noteIds = vos.stream().map(NoteVO::getId).toList();
        Map<Long, List<Tag>> grouped = tagMapper.selectByNoteIds(noteIds).stream()
                .collect(Collectors.groupingBy(NoteTagRow::getNoteId,
                        Collectors.mapping(NoteTagRow::toTag, Collectors.toList())));
        for (NoteVO vo : vos) {
            // 没有任何标签的笔记不会出现在连表结果里 → 兜底成空数组，
            // 前端拿到的必须是 []，否则 el-tag 的 v-for 会因为 undefined 报警告
            vo.setTags(grouped.getOrDefault(vo.getId(), List.of()));
        }
    }

    @Transactional
    public NoteVO save(NoteDTO dto) {
        Note note = new Note();
        note.setTitle(dto.getTitle().trim());
        // 标题只存 title 字段：正文开头那行重复的 `# 标题` 在这里统一剥掉（见 stripDuplicatedTitle）
        String content = normalizeNoteContent(dto.getContent(), note.getTitle());
        note.setContent(content);
        // 摘要随手写好落库：列表页就再也不用把整段 LONGTEXT 拉回来算这一行了
        note.setSummary(plainSummary(content));
        note.setCategoryId(dto.getCategoryId());
        noteMapper.insert(note);
        saveTags(note.getId(), dto.getTagIds());
        learningActivity.record("note", note.getId());
        // 通知 wiki：该分类的主题页已过期，按需在后台增量重生成
        publishChanged(dto.getCategoryId());
        return detail(note.getId());
    }

    /**
     * 更新笔记。
     * <p>
     * 标签语义刻意区分两种「空」：
     * <ul>
     *   <li>{@code tagIds == null}：本次不涉及标签 → 完全不动原有标签关联。
     *       这样 AI 的「只改标题」这类局部更新才不会把标签清空。</li>
     *   <li>{@code tagIds == []}：明确要求清空标签 → 删掉全部关联。
     *       前端正常保存时发的就是空数组，行为与以前一致。</li>
     * </ul>
     */
    @Transactional
    public NoteVO update(Long id, NoteDTO dto) {
        Note exist = noteMapper.selectById(id);
        if (exist == null) {
            throw new IllegalArgumentException("笔记不存在: " + id);
        }
        // 与 save 同一条规则：正文开头重复的 `# 标题` 一律剥掉
        //（AI 的「整篇重写 / 追加」最容易把标题又带回正文）
        String content = normalizeNoteContent(dto.getContent(), dto.getTitle().trim());
        // 用显式 UpdateWrapper：categoryId 为 null 时也能真正清空（updateById 会忽略 null）
        noteMapper.update(null, Wrappers.<Note>lambdaUpdate()
                .eq(Note::getId, id)
                .set(Note::getTitle, dto.getTitle().trim())
                .set(Note::getContent, content)
                .set(Note::getSummary, plainSummary(content))
                .set(Note::getCategoryId, dto.getCategoryId()));
        if (dto.getTagIds() != null) {
            noteMapper.deleteNoteTags(id);
            saveTags(id, dto.getTagIds());
        }
        // 新旧分类都要通知：改分类后，旧分类的主题页也少了一条素材
        learningActivity.record("note", id);
        publishChanged(exist.getCategoryId(), dto.getCategoryId());
        return detail(id);
    }

    /**
     * 剥掉正文开头那行「与标题重复」的一级标题。
     * <p>
     * 约定（见 skills/markdown-beautify/SKILL.md）：标题只存 {@code title} 字段，正文用
     * {@code ##} / {@code ###} 组织层级。但历史数据与模型输出经常两处都写，于是打开笔记
     * 就看到两个标题、导出 .md 时还会在前面再拼一次。这里在保存与更新的唯一入口统一剥掉。
     * <p>
     * 只在"确实是同一个标题"时剥：忽略空白与常见标点后两边相等，或一边包含另一边
     *（模型爱把标题简写，例如标题「Spring Boot 启动流程（高频面试题）」对应 H1「Spring Boot 启动流程」）。
     */
    static String stripDuplicatedTitle(String content, String title) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) {
            return content;
        }
        int lineEnd = content.indexOf('\n');
        String firstLine = (lineEnd < 0 ? content : content.substring(0, lineEnd)).trim();
        if (!firstLine.startsWith("# ")) {
            return content;   // 只处理一级标题行（`## ` 不满足这个前缀）
        }
        String heading = firstLine.substring(2).trim();
        if (!sameTitleText(heading, t)) {
            return content;
        }
        String rest = lineEnd < 0 ? "" : content.substring(lineEnd + 1);
        // 连标题下面的空行一起去掉，别让正文一开头留一堆空行
        return rest.replaceFirst("^(\\s*\\r?\\n)+", "");
    }

    /** 两个标题是否"同一个"：归一化后相等或互相包含 */
    private static boolean sameTitleText(String a, String b) {
        String x = normalizeTitle(a);
        String y = normalizeTitle(b);
        if (x.isEmpty() || y.isEmpty()) {
            return false;
        }
        return x.equals(y) || x.contains(y) || y.contains(x);
    }

    private static String normalizeTitle(String s) {
        return s == null ? "" : s.replaceAll("[\\s`*：:、，,。.（）()【】\\[\\]\\-]", "");
    }
    /**
     * 保存 / 更新前的正文规范化，只做两件与「生成笔记」直接相关的事，其余一字不动：
     * <ol>
     *   <li>剥掉开头那行与本笔记标题重复的一级标题（标题只存 title 字段）；</li>
     *   <li>合并「相邻（中间只有空行）且互相包含」的重复小节标题 —— 生成链路（模型整篇写、
     *       长文分节拼装）经常把同一个小节标题写两遍，正文与右侧大纲于是都出现两条。</li>
     * </ol>
     * 之前只在长文分节那一条路径里去了重复，模型整篇写或用户粘贴进来的照样会带进来，
     * 所以放到保存的唯一入口统一处理。
     */
    static String normalizeNoteContent(String content, String title) {
        return collapseAdjacentDuplicateHeadings(stripDuplicatedTitle(content, title));
    }

    private static final java.util.regex.Pattern HEADING_LINE =
            java.util.regex.Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final java.util.regex.Pattern FENCE_LINE =
            java.util.regex.Pattern.compile("^\\s*(```|~~~)");

    /** 相邻且互相包含的同级标题只留更具体的那个（代码块里的 # 注释不动） */
    static String collapseAdjacentDuplicateHeadings(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        String[] lines = content.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        boolean inFence = false;
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (FENCE_LINE.matcher(line).find()) {
                inFence = !inFence;
                out.add(line);
                i++;
                continue;
            }
            if (!inFence) {
                java.util.regex.Matcher m1 = HEADING_LINE.matcher(line.trim());
                if (m1.matches()) {
                    String level = m1.group(1);
                    String text = m1.group(2).trim();
                    int j = i + 1;
                    while (j < lines.length && lines[j].trim().isEmpty()) {
                        j++;
                    }
                    if (j < lines.length) {
                        java.util.regex.Matcher m2 = HEADING_LINE.matcher(lines[j].trim());
                        if (m2.matches() && m2.group(1).equals(level)) {
                            String text2 = m2.group(2).trim();
                            if (sameTitleText(text, text2)) {
                                // 保留更具体（更长）的那一行，且原样写回它，避免动到换行风格
                                out.add(text2.length() > text.length() ? lines[j] : line);
                                i = j + 1;
                                continue;
                            }
                        }
                    }
                }
            }
            out.add(line);
            i++;
        }
        return String.join("\n", out);
    }

    /** 内容变更通知：wiki 的自动增量更新靠它（见 WikiService#onKnowledgeChanged） */
    private void publishChanged(Long... categoryIds) {
        java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
        for (Long id : categoryIds) {
            if (id != null) {
                ids.add(id);
            }
        }
        if (!ids.isEmpty()) {
            events.publishEvent(new KnowledgeChangedEvent(ids, "note"));
        }
    }

    @Transactional
    public void delete(Long id) {
        // 先取分类：删完再查就没有这条记录了，而 wiki 需要知道该刷新哪个主题
        Note exist = noteMapper.selectById(id);
        noteMapper.deleteById(id);
        noteMapper.deleteNoteTags(id);
        if (exist != null) {
            publishChanged(exist.getCategoryId());
        }
    }

    /** 保存标签关联；tagIds 中的标签若不存在则自动创建 */
    private void saveTags(Long noteId, List<Long> tagIds) {
        if (CollectionUtils.isEmpty(tagIds)) {
            return;
        }
        List<Long> validIds = new ArrayList<>();
        for (Long tagId : tagIds) {
            Tag tag = tagMapper.selectById(tagId);
            if (tag == null) {
                throw new IllegalArgumentException("标签不存在: " + tagId);
            }
            validIds.add(tagId);
        }
        noteMapper.insertNoteTags(noteId, validIds);
    }

    private Map<Long, String> loadCategoryNames(Collection<Long> categoryIds) {
        Collection<Long> ids = categoryIds == null ? List.of() : categoryIds.stream()
                .filter(id -> id != null).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return categoryMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName, (a, b) -> a));
    }

    private NoteVO toVO(Note note, boolean withContent, Map<Long, String> categoryNames) {
        NoteVO vo = new NoteVO();
        vo.setId(note.getId());
        vo.setTitle(note.getTitle());
        vo.setContent(withContent ? note.getContent() : null);
        vo.setSummary(resolveSummary(note));
        vo.setCategoryId(note.getCategoryId());
        vo.setCategoryName(note.getCategoryId() == null ? null : categoryNames.get(note.getCategoryId()));
        vo.setCreatedAt(note.getCreatedAt());
        vo.setUpdatedAt(note.getUpdatedAt());
        return vo;
    }

    /**
     * 取摘要：优先读 note.summary 这一列。
     * <p>
     * 兜底分支是给「老数据」的：summary 列是后加的，历史行里是 NULL。
     * 这种情况如果正文恰好被查出来了（详情接口 / 启动补数），就现算一次，
     * 至少不会让用户看到一篇空白摘要。列表接口查不到正文，就交给启动补数去填。
     */
    private String resolveSummary(Note note) {
        if (note.getSummary() != null) {
            return note.getSummary();
        }
        return note.getContent() == null ? "" : plainSummary(note.getContent());
    }

    /**
     * 去掉标记后截取前 120 字作摘要。写成 static 是为了能脱离 Spring 上下文单测。
     * <p>
     * <b>顺序是这个方法唯一的难点：必须先摘 HTML 标签，再清 Markdown 符号。</b>
     * 反过来的话，{@code <font style="color:rgb(0,0,0)">} 里那个 {@code >} 和两个括号
     * 会先被当成 Markdown 标点删掉，标签就不再是标签了，残留的
     * {@code <font style=color:rgb0, 0, 0} 原样漏进列表预览。
     * 这不是假想 —— 笔记本里到处是从语雀粘来的彩色文字，实测摘要就长这样。
     * <p>
     * <b>改了这个方法要注意</b>：库里已有行的 summary 是「算好落库」的，不会自动跟着变。
     * 想让历史行按新规则重算，执行 {@code UPDATE note SET summary = NULL;} 再重启，
     * 让 {@link #backfillSummaries()} 挑 {@code summary IS NULL} 的行重算一遍。
     */
    static String plainSummary(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String plain = content
                // 1) 代码块整体丢弃 —— 摘要是给人扫一眼的，塞半截代码没意义
                .replaceAll("(?s)```.*?```", " ")
                .replaceAll("(?s)~~~.*?~~~", " ")
                // 2) HTML：注释 → 标签 → 「断在中间的残尾」。
                //    残尾那条要求 < 后面像个标签名（<font / </font），
                //    这样正文里的「价格 < 100」不会被误吃。
                .replaceAll("(?s)<!--.*?-->", " ")
                .replaceAll("(?s)<[^<>]{0,300}>", " ")
                .replaceAll("(?s)<\\s*/?\\s*[a-zA-Z][^<>]{0,300}$", " ")
                // 3) 实体解码要赶在第 4 步之前，否则 &#39; 里的 # 会先被当标点删掉
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                // 4) 分隔线 / 表格骨架行整行去掉。
                //    必须排在「行首 Markdown 前缀」之前 —— 否则 `---` 会先被列表符规则
                //    吃掉一个 '-'，剩两个就不满足分隔线要 3 个以上的条件，残渣留在摘要里。
                .replaceAll("(?m)^\\s*[-=*_]{3,}\\s*$", " ")
                .replaceAll("(?m)^[\\s|:-]+$", " ")
                // 5) 行首的 Markdown 前缀：标题 / 列表 / 引用
                .replaceAll("(?m)^\\s*(?:#{1,6}|[-*+]|>)\\s*", "")
                // 6) [文字](链接) → 文字
                .replaceAll("\\[([^\\[\\]]*)]\\([^()]*\\)", "$1")
                // 7) 剩余 Markdown 标点。刻意不含 '#' 和 '-'：
                //    '#' 交给第 5 步只吃行首，否则「C#」会被啃成「C」；
                //    '-' 交给第 4/5 步，否则「发布-订阅」会变成「发布订阅」。
                .replaceAll("[`*_~|\\[\\]]", "")
                .replaceAll("\\s+", " ")
                .trim();
        return plain.length() > 120 ? plain.substring(0, 120) + "…" : plain;
    }

    /**
     * 补历史数据的摘要。
     * <p>
     * note.summary 是后加的列，老行里是 NULL —— 列表接口已经不查正文了，
     * 不补就会出现「正文有内容、摘要却是空的」。启动时跑一次，幂等：
     * 只挑 {@code summary IS NULL} 的行，补完下次启动就是 0 条。
     *
     * @return 实际补齐的条数
     */
    @Transactional
    public int backfillSummaries() {
        List<Note> stale = noteMapper.selectList(Wrappers.<Note>lambdaQuery()
                .select(Note::getId, Note::getContent)
                .isNull(Note::getSummary));
        for (Note n : stale) {
            noteMapper.update(null, Wrappers.<Note>lambdaUpdate()
                    .eq(Note::getId, n.getId())
                    .set(Note::getSummary, plainSummary(n.getContent())));
        }
        return stale.size();
    }
}
