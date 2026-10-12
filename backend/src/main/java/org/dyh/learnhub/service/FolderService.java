package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.dto.FolderMoveRequest;
import org.dyh.learnhub.dto.FolderRequest;
import org.dyh.learnhub.entity.ContentFolder;
import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.entity.Note;
import org.dyh.learnhub.mapper.ContentFolderMapper;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.dyh.learnhub.mapper.NoteMapper;
import org.dyh.learnhub.vo.FolderVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FolderService {
    private final ContentFolderMapper folders;
    private final NoteMapper notes;
    private final FileInfoMapper files;

    public List<FolderVO> list(String kind) {
        requireKind(kind);
        var counts = ("note".equals(kind) ? folders.noteCounts() : folders.fileCounts()).stream()
                .collect(Collectors.toMap(row -> row.getFolderId(), row -> row.getItemCount()));
        return folders.selectList(Wrappers.<ContentFolder>lambdaQuery()
                        .eq(ContentFolder::getKind, kind).orderByAsc(ContentFolder::getName).orderByAsc(ContentFolder::getId))
                .stream().map(folder -> toVO(folder, counts.getOrDefault(folder.getId(), 0L))).toList();
    }

    @Transactional
    public FolderVO create(FolderRequest request) {
        if (request == null) throw new IllegalArgumentException("请输入文件夹信息");
        String kind = requireKind(request.kind());
        String name = requireName(request.name());
        long parentId = parentId(request.parentId());
        Map<Long, ContentFolder> all = lockKind(kind);
        checkParent(kind, null, parentId, all);
        checkDuplicate(null, name, parentId, all.values());
        ContentFolder folder = new ContentFolder();
        folder.setKind(kind);
        folder.setName(name);
        folder.setParentId(parentId);
        try {
            folders.insert(folder);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("这个位置已存在同名文件夹");
        }
        return toVO(folder, 0);
    }

    @Transactional
    public FolderVO update(Long id, FolderRequest request) {
        ContentFolder existing = requireExisting(id);
        if (request == null) throw new IllegalArgumentException("请输入文件夹信息");
        if (request.kind() != null && !existing.getKind().equals(requireKind(request.kind()))) {
            throw new IllegalArgumentException("不能更改文件夹类型");
        }
        Map<Long, ContentFolder> all = lockKind(existing.getKind());
        existing = all.get(id);
        if (existing == null) throw new IllegalArgumentException("文件夹不存在或已被删除");
        String name = request.name() == null ? existing.getName() : requireName(request.name());
        long parentId = request.parentId() == null ? existing.getParentId() : parentId(request.parentId());
        checkParent(existing.getKind(), id, parentId, all);
        checkDuplicate(id, name, parentId, all.values());
        existing.setName(name);
        existing.setParentId(parentId);
        try {
            folders.updateById(existing);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("这个位置已存在同名文件夹");
        }
        long count = "note".equals(existing.getKind())
                ? notes.selectCount(Wrappers.<Note>lambdaQuery().eq(Note::getFolderId, id))
                : files.selectCount(Wrappers.<FileInfo>lambdaQuery().eq(FileInfo::getFolderId, id));
        return toVO(existing, count);
    }

    /** 删除空层级：内容回到“未收纳”，不会删除正文或移动磁盘文件。 */
    @Transactional
    public int delete(Long id) {
        ContentFolder existing = requireExisting(id);
        Map<Long, ContentFolder> all = lockKind(existing.getKind());
        if (!all.containsKey(id)) throw new IllegalArgumentException("文件夹不存在或已被删除");
        if (all.values().stream().anyMatch(folder -> Objects.equals(folder.getParentId(), id))) {
            throw new IllegalArgumentException("文件夹内还有子文件夹，请先移动或删除子文件夹");
        }
        int affected = "note".equals(existing.getKind())
                ? notes.update(null, Wrappers.<Note>lambdaUpdate().eq(Note::getFolderId, id)
                        .set(Note::getFolderId, null).setSql("updated_at = updated_at"))
                : files.update(null, Wrappers.<FileInfo>lambdaUpdate().eq(FileInfo::getFolderId, id).set(FileInfo::getFolderId, null));
        folders.deleteById(id);
        return affected;
    }

    @Transactional
    public int moveItems(FolderMoveRequest request) {
        if (request == null) throw new IllegalArgumentException("请选择要移动的内容");
        String kind = requireKind(request.kind());
        if (request.ids() == null || request.ids().isEmpty() || request.ids().size() > 500) {
            throw new IllegalArgumentException("每次请选择 1 至 500 条内容");
        }
        if (request.ids().stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("内容 id 必须是正整数");
        }
        List<Long> ids = new LinkedHashSet<>(request.ids()).stream().sorted().toList();
        Long target = requireTarget(kind, request.folderId());
        // 先锁定和验证全部内容，验证完成之前不能写入任何一条。
        List<Long> found = "note".equals(kind) ? notes.lockIdsForFolderMove(ids) : files.lockIdsForFolderMove(ids);
        if (found.size() != ids.size() || !new HashSet<>(found).equals(new HashSet<>(ids))) {
            throw new IllegalArgumentException("部分内容不存在或已被删除，请刷新后重试");
        }
        if ("note".equals(kind)) {
            // 收纳操作不算正文编辑，保留时间避免扰动最近笔记和 Wiki/检索的内容新鲜度。
            notes.update(null, Wrappers.<Note>lambdaUpdate().in(Note::getId, ids)
                    .set(Note::getFolderId, target).setSql("updated_at = updated_at"));
        } else {
            files.update(null, Wrappers.<FileInfo>lambdaUpdate().in(FileInfo::getId, ids).set(FileInfo::getFolderId, target));
        }
        return ids.size();
    }

    /** 仅用于事务内分配目录：锁目标，防止验证后目录被删除。0/null 均归为未收纳。 */
    public Long requireTarget(String kind, Long folderId) {
        requireKind(kind);
        if (folderId == null || folderId == 0) return null;
        if (folderId < 0) throw new IllegalArgumentException("文件夹 id 不合法");
        ContentFolder folder = folders.selectForUpdate(folderId);
        if (folder == null || !kind.equals(folder.getKind())) {
            throw new IllegalArgumentException("目标文件夹不存在或类型不匹配");
        }
        return folderId;
    }

    /** 列表筛选不持有写锁；省略/0 的语义由调用方的查询处理。 */
    public void validateFilter(String kind, Long folderId) {
        requireKind(kind);
        if (folderId == null || folderId == 0) return;
        if (folderId < 0) throw new IllegalArgumentException("文件夹 id 不合法");
        ContentFolder folder = folders.selectById(folderId);
        if (folder == null || !kind.equals(folder.getKind())) {
            throw new IllegalArgumentException("文件夹不存在或类型不匹配");
        }
    }

    private ContentFolder requireExisting(Long id) {
        if (id == null || id <= 0) throw new IllegalArgumentException("文件夹 id 不合法");
        ContentFolder folder = folders.selectById(id);
        if (folder == null) throw new IllegalArgumentException("文件夹不存在或已被删除");
        return folder;
    }

    private Map<Long, ContentFolder> lockKind(String kind) {
        return folders.lockKind(kind).stream().collect(Collectors.toMap(ContentFolder::getId, Function.identity()));
    }

    private void checkParent(String kind, Long self, long parent, Map<Long, ContentFolder> all) {
        Set<Long> visited = new HashSet<>();
        Long cursor = parent;
        while (cursor != null && cursor != 0) {
            if (cursor.equals(self)) throw new IllegalArgumentException("不能移动到自己或自己的子文件夹内");
            if (!visited.add(cursor)) throw new IllegalArgumentException("父文件夹层级异常，请重新选择");
            ContentFolder entry = all.get(cursor);
            if (entry == null || !kind.equals(entry.getKind())) {
                throw new IllegalArgumentException("父文件夹不存在或类型不匹配");
            }
            cursor = entry.getParentId();
        }
    }

    private static void checkDuplicate(Long self, String name, long parent, java.util.Collection<ContentFolder> all) {
        if (all.stream().anyMatch(folder -> !Objects.equals(folder.getId(), self)
                && Objects.equals(folder.getParentId(), parent) && folder.getName().equalsIgnoreCase(name))) {
            throw new IllegalArgumentException("这个位置已存在同名文件夹");
        }
    }

    private static String requireKind(String kind) {
        if (!"note".equals(kind) && !"file".equals(kind)) throw new IllegalArgumentException("文件夹类型必须为 note 或 file");
        return kind;
    }

    private static String requireName(String name) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("文件夹名称不能为空");
        String clean = name.trim();
        if (clean.length() > 100 || clean.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '/' || c == '\\')) {
            throw new IllegalArgumentException("文件夹名称限 100 个字符，不能包含控制字符或斜杠");
        }
        return clean;
    }

    private static long parentId(Long parent) {
        if (parent != null && parent < 0) throw new IllegalArgumentException("父文件夹 id 不合法");
        return parent == null ? 0 : parent;
    }

    private static FolderVO toVO(ContentFolder folder, long count) {
        return new FolderVO(folder.getId(), folder.getKind(), folder.getName(), folder.getParentId(), count);
    }
}
