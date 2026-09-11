package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.entity.Category;
import org.dyh.learnhub.entity.Note;
import org.dyh.learnhub.entity.QuickRef;
import org.dyh.learnhub.entity.Tag;
import org.dyh.learnhub.mapper.CategoryMapper;
import org.dyh.learnhub.mapper.NoteMapper;
import org.dyh.learnhub.mapper.QuickRefMapper;
import org.dyh.learnhub.mapper.TagMapper;
import org.dyh.learnhub.vo.NoteVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StatsService {

    private final NoteMapper noteMapper;
    private final QuickRefMapper quickRefMapper;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final NoteService noteService;

    /** 工作台总览数据 */
    public Map<String, Object> dashboard() {
        List<Category> categories = categoryMapper.selectList(
                Wrappers.<Category>lambdaQuery().orderByAsc(Category::getSortOrder));
        List<Note> notes = noteMapper.selectList(Wrappers.<Note>lambdaQuery()
                .select(Note::getId, Note::getCategoryId));
        List<QuickRef> refs = quickRefMapper.selectList(Wrappers.<QuickRef>lambdaQuery()
                .select(QuickRef::getId, QuickRef::getCategoryId));

        Map<Long, Long> noteCountByCat = notes.stream()
                .filter(n -> n.getCategoryId() != null)
                .collect(Collectors.groupingBy(Note::getCategoryId, Collectors.counting()));
        Map<Long, Long> refCountByCat = refs.stream()
                .filter(r -> r.getCategoryId() != null)
                .collect(Collectors.groupingBy(QuickRef::getCategoryId, Collectors.counting()));

        List<Map<String, Object>> categoryStats = new ArrayList<>();
        for (Category c : categories) {
            categoryStats.add(Map.of(
                    "id", c.getId(),
                    "name", c.getName(),
                    "noteCount", noteCountByCat.getOrDefault(c.getId(), 0L),
                    "refCount", refCountByCat.getOrDefault(c.getId(), 0L)));
        }

        List<NoteVO> recentNotes = noteService.recent(6);

        return Map.of(
                "noteTotal", noteMapper.selectCount(null),
                "refTotal", quickRefMapper.selectCount(null),
                "categoryTotal", (long) categories.size(),
                "tagTotal", tagMapper.selectCount(Wrappers.<Tag>query()),
                "categoryStats", categoryStats,
                "recentNotes", recentNotes);
    }
}
