package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final FileInfoMapper fileInfoMapper;

    /** 文件存放目录：后端工作目录下的 uploads/ */
    private Path storageDir() {
        Path dir = Paths.get(System.getProperty("user.dir"), "uploads");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建上传目录: " + dir, e);
        }
        return dir;
    }

    /** 资料列表：支持分类 / 关键词过滤 */
    public List<Map<String, Object>> list(Long categoryId, String kw) {
        LambdaQueryWrapper<FileInfo> wrapper = Wrappers.<FileInfo>lambdaQuery()
                .orderByDesc(FileInfo::getCreatedAt);
        if (categoryId != null && categoryId > 0) {
            wrapper.eq(FileInfo::getCategoryId, categoryId);
        }
        if (StringUtils.hasText(kw)) {
            wrapper.like(FileInfo::getOriginName, kw.trim());
        }
        return fileInfoMapper.selectList(wrapper).stream().map(this::toMap).collect(Collectors.toList());
    }

    /** 上传文件：保存到磁盘 + 写入元数据 */
    @Transactional
    public FileInfo upload(MultipartFile file, Long categoryId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        String originName = StringUtils.cleanPath(
                file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename());
        String ext = "";
        int dot = originName.lastIndexOf('.');
        if (dot >= 0 && dot < originName.length() - 1) {
            ext = originName.substring(dot + 1).toLowerCase();
        }
        String storeName = UUID.randomUUID().toString().replace("-", "") + (ext.isEmpty() ? "" : "." + ext);

        try {
            Path target = storageDir().resolve(storeName);
            try (var in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("文件保存失败: " + e.getMessage(), e);
        }

        FileInfo info = new FileInfo();
        info.setOriginName(originName);
        info.setStoreName(storeName);
        info.setSize(file.getSize());
        info.setExt(ext);
        info.setCategoryId(categoryId);
        fileInfoMapper.insert(info);
        log.info("文件上传成功: {} ({} bytes) -> {}", originName, file.getSize(), storeName);
        return info;
    }

    /** 下载：返回文件内容与下载文件名 */
    public DownloadItem download(Long id) {
        FileInfo info = fileInfoMapper.selectById(id);
        if (info == null) {
            throw new IllegalArgumentException("资料不存在: " + id);
        }
        Path path = storageDir().resolve(info.getStoreName());
        if (!Files.exists(path)) {
            throw new IllegalStateException("文件已丢失: " + info.getStoreName());
        }
        String encodedName = URLEncoder.encode(info.getOriginName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return new DownloadItem(new FileSystemResource(path), encodedName, info.getOriginName());
    }

    /** 删除：删记录 + 删磁盘文件 */
    @Transactional
    public void delete(Long id) {
        FileInfo info = fileInfoMapper.selectById(id);
        if (info == null) {
            throw new IllegalArgumentException("资料不存在: " + id);
        }
        fileInfoMapper.deleteById(id);
        try {
            Files.deleteIfExists(storageDir().resolve(info.getStoreName()));
        } catch (IOException e) {
            log.warn("磁盘文件删除失败(记录已删): {}", info.getStoreName(), e);
        }
    }

    public Map<String, Object> toMap(FileInfo info) {
        return Map.of(
                "id", info.getId(),
                "originName", info.getOriginName(),
                "size", info.getSize() == null ? 0L : info.getSize(),
                "ext", info.getExt() == null ? "" : info.getExt(),
                "categoryId", info.getCategoryId() == null ? 0L : info.getCategoryId(),
                "createdAt", info.getCreatedAt() == null ? "" : info.getCreatedAt().toString());
    }

    /** 下载结果载体 */
    public record DownloadItem(Resource resource, String encodedName, String originName) {
    }
}
