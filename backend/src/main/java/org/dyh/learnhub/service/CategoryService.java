package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.entity.Category;
import org.dyh.learnhub.mapper.CategoryMapper;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.dyh.learnhub.mapper.NoteMapper;
import org.dyh.learnhub.mapper.QuickRefMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryMapper categoryMapper;
    private final NoteMapper noteMapper;
    private final QuickRefMapper quickRefMapper;
    private final FileInfoMapper fileInfoMapper;

    /** 返回分类树（根节点列表） */
    public List<Category> tree() {
        List<Category> all = categoryMapper.selectList(
                Wrappers.<Category>lambdaQuery().orderByAsc(Category::getSortOrder).orderByAsc(Category::getId));
        Map<Long, Category> byId = new LinkedHashMap<>();
        for (Category c : all) {
            c.setChildren(new ArrayList<>());
            byId.put(c.getId(), c);
        }
        List<Category> roots = new ArrayList<>();
        for (Category c : all) {
            if (c.getParentId() == null || c.getParentId() == 0) {
                roots.add(c);
            } else {
                Category parent = byId.get(c.getParentId());
                if (parent != null) {
                    parent.getChildren().add(c);
                } else {
                    roots.add(c);
                }
            }
        }
        return roots;
    }

    public Category getById(Long id) {
        return categoryMapper.selectById(id);
    }

    /** 新增分类：名称全局唯一（表级 UNIQUE）；parentId 为空视为顶级(0) */
    @Transactional
    public void save(Category category) {
        if (!StringUtils.hasText(category.getName())) {
            throw new IllegalArgumentException("分类名称不能为空");
        }
        Long parentId = category.getParentId() == null ? 0L : category.getParentId();
        if (parentId != 0 && categoryMapper.selectById(parentId) == null) {
            throw new IllegalArgumentException("父分类不存在");
        }
        checkDuplicateName(null, category.getName().trim());
        category.setName(category.getName().trim());
        category.setParentId(parentId);
        categoryMapper.insert(category);
    }

    /**
     * 编辑分类。仅更新传入的非空字段：
     * name=重命名；parentId=移动归属；sortOrder=调整排序
     */
    @Transactional
    public void update(Long id, Category patch) {
        Category exist = categoryMapper.selectById(id);
        if (exist == null) {
            throw new IllegalArgumentException("分类不存在或已被删除");
        }
        String name = patch.getName();
        if (name != null) {
            if (!StringUtils.hasText(name)) {
                throw new IllegalArgumentException("分类名称不能为空");
            }
            name = name.trim();
            checkDuplicateName(id, name);
        }
        // 移动归属校验：不能把分类设为自己或自己的后代，否则树成环
        Long targetParent = patch.getParentId() != null ? patch.getParentId() : exist.getParentId();
        if (targetParent != null && targetParent != 0) {
            if (targetParent.equals(id)) {
                throw new IllegalArgumentException("不能把分类移动到自身下");
            }
            Category parent = categoryMapper.selectById(targetParent);
            if (parent == null) {
                throw new IllegalArgumentException("父分类不存在");
            }
            if (isDescendant(id, targetParent)) {
                throw new IllegalArgumentException("不能把分类移动到自己的子分类下");
            }
        }
        Category target = new Category();
        target.setId(id);
        target.setName(name != null ? name : null);
        target.setParentId(patch.getParentId() != null ? patch.getParentId() : null);
        target.setSortOrder(patch.getSortOrder());
        categoryMapper.updateById(target);
    }

    /**
     * 删除分类。有子分类则拒绝；其下笔记/速查卡/资料自动变为「未分类」。
     *
     * @return 被解绑（移到未分类）的内容条目总数
     */
    @Transactional
    public int delete(Long id) {
        long childCount = categoryMapper.selectCount(
                Wrappers.<Category>lambdaQuery().eq(Category::getParentId, id));
        if (childCount > 0) {
            throw new IllegalArgumentException("该分类下还有子分类，无法删除");
        }
        int affected = 0;
        affected += noteMapper.unbindNotesByCategory(id);
        affected += quickRefMapper.unbindQuickRefsByCategory(id);
        affected += fileInfoMapper.unbindFilesByCategory(id);
        categoryMapper.deleteById(id);
        return affected;
    }

    private boolean isDescendant(Long ancestorId, Long maybeChildId) {
        // 从 maybeChildId 向上追溯父链，看是否经过 ancestorId
        Long cursor = maybeChildId;
        int guard = 0;
        while (cursor != null && cursor != 0 && guard++ < 100) {
            if (cursor.equals(ancestorId)) {
                return true;
            }
            Category c = categoryMapper.selectById(cursor);
            cursor = c == null ? null : c.getParentId();
        }
        return false;
    }

    private void checkDuplicateName(Long selfId, String name) {
        Category dup = categoryMapper.selectOne(Wrappers.<Category>lambdaQuery()
                .eq(Category::getName, name)
                .last("LIMIT 1"));
        if (dup != null && !dup.getId().equals(selfId)) {
            throw new IllegalArgumentException("已存在同名分类「" + name + "」");
        }
    }
}
