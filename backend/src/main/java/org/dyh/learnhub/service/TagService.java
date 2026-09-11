package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.entity.Tag;
import org.dyh.learnhub.mapper.TagMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TagService {

    private final TagMapper tagMapper;

    /** 全部标签（含被笔记引用次数） */
    public List<Tag> list() {
        return tagMapper.selectAllWithCount();
    }

    public Tag getByName(String name) {
        return tagMapper.selectOne(
                Wrappers.<Tag>lambdaQuery().eq(Tag::getName, name));
    }

    /** 不存在则创建，返回存在的/新建的标签 */
    public Tag findOrCreate(String name) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("标签名不能为空");
        }
        Tag exist = getByName(name.trim());
        if (exist != null) {
            return exist;
        }
        Tag tag = new Tag();
        tag.setName(name.trim());
        tagMapper.insert(tag);
        return tag;
    }

    /** 重命名（标签名唯一，冲突抛业务异常） */
    @Transactional
    public void rename(Long id, String name) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("标签名不能为空");
        }
        String newName = name.trim();
        Tag exist = getByName(newName);
        if (exist != null && !exist.getId().equals(id)) {
            throw new IllegalArgumentException("已存在同名标签「" + newName + "」");
        }
        Tag tag = new Tag();
        tag.setId(id);
        tag.setName(newName);
        if (tagMapper.updateById(tag) == 0) {
            throw new IllegalArgumentException("标签不存在或已被删除");
        }
    }

    /**
     * 删除标签，并解除它与所有笔记的关联
     *
     * @return 受影响（被移除该标签）的笔记数量
     */
    @Transactional
    public int remove(Long id) {
        int affected = tagMapper.deleteNoteLinks(id);
        tagMapper.deleteById(id);
        return affected;
    }
}
