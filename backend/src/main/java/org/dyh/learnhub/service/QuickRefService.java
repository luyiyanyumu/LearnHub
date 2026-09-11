package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.dto.QuickRefDTO;
import org.dyh.learnhub.entity.Category;
import org.dyh.learnhub.entity.QuickRef;
import org.dyh.learnhub.mapper.CategoryMapper;
import org.dyh.learnhub.mapper.QuickRefMapper;
import org.dyh.learnhub.vo.QuickRefVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class QuickRefService {

    private final QuickRefMapper quickRefMapper;
    private final CategoryMapper categoryMapper;

    /** 速查卡列表：支持分类 / 关键词过滤 */
    public List<QuickRefVO> list(Long categoryId, String kw) {
        LambdaQueryWrapper<QuickRef> wrapper = Wrappers.<QuickRef>lambdaQuery()
                .orderByDesc(QuickRef::getUpdatedAt);
        if (categoryId != null && categoryId > 0) {
            wrapper.eq(QuickRef::getCategoryId, categoryId);
        }
        if (StringUtils.hasText(kw)) {
            String like = kw.trim();
            wrapper.and(w -> w.like(QuickRef::getTitle, like).or().like(QuickRef::getContent, like));
        }
        List<QuickRef> list = quickRefMapper.selectList(wrapper);
        if (list.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> categoryIds = list.stream().map(QuickRef::getCategoryId)
                .filter(id -> id != null).distinct().collect(Collectors.toList());
        Map<Long, String> names = categoryIds.isEmpty() ? Map.of()
                : categoryMapper.selectBatchIds(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName, (a, b) -> a));
        return list.stream().map(r -> toVO(r, names)).collect(Collectors.toList());
    }

    public QuickRefVO detail(Long id) {
        QuickRef ref = quickRefMapper.selectById(id);
        if (ref == null) {
            throw new IllegalArgumentException("速查项不存在: " + id);
        }
        Map<Long, String> names = ref.getCategoryId() == null ? Map.of()
                : categoryMapper.selectBatchIds(List.of(ref.getCategoryId())).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName, (a, b) -> a));
        return toVO(ref, names);
    }

    @Transactional
    public QuickRefVO save(QuickRefDTO dto) {
        QuickRef ref = new QuickRef();
        apply(ref, dto);
        quickRefMapper.insert(ref);
        return detail(ref.getId());
    }

    @Transactional
    public QuickRefVO update(Long id, QuickRefDTO dto) {
        QuickRef exist = quickRefMapper.selectById(id);
        if (exist == null) {
            throw new IllegalArgumentException("速查项不存在: " + id);
        }
        // 必须用显式 UpdateWrapper：MyBatis-Plus 的 updateById 默认走 NOT_NULL 策略，
        // 值为 null 的字段会被整段跳过 —— 于是「把分类清空」「把内容清空」都保存不了。
        quickRefMapper.update(null, Wrappers.<QuickRef>lambdaUpdate()
                .eq(QuickRef::getId, id)
                .set(QuickRef::getTitle, dto.getTitle().trim())
                .set(QuickRef::getContent, dto.getContent())
                .set(QuickRef::getCategoryId, dto.getCategoryId()));
        return detail(id);
    }

    @Transactional
    public void delete(Long id) {
        quickRefMapper.deleteById(id);
    }

    private void apply(QuickRef ref, QuickRefDTO dto) {
        ref.setTitle(dto.getTitle().trim());
        ref.setContent(dto.getContent());
        ref.setCategoryId(dto.getCategoryId());
    }

    private QuickRefVO toVO(QuickRef ref, Map<Long, String> names) {
        QuickRefVO vo = new QuickRefVO();
        vo.setId(ref.getId());
        vo.setTitle(ref.getTitle());
        vo.setContent(ref.getContent());
        vo.setCategoryId(ref.getCategoryId());
        vo.setCategoryName(ref.getCategoryId() == null ? null : names.get(ref.getCategoryId()));
        vo.setCreatedAt(ref.getCreatedAt());
        vo.setUpdatedAt(ref.getUpdatedAt());
        return vo;
    }
}
