package org.dyh.learnhub.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.sl.usermodel.PictureShape;
import org.apache.poi.sl.usermodel.SlideShowFactory;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.w3c.dom.Element;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

/** Only controlled PNG derivatives are persisted; neither relationship targets nor source text become disk paths. */
final class DocumentMediaAssets {
    static final long MAX_PIXELS = 5_000_000;
    static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final long MAX_ALL_BYTES = 64L * 1024 * 1024;
    final Path root;
    final Path assets;
    record Asset(String src, byte[] png) { }

    DocumentMediaAssets(Long id, Path source) throws IOException {
        if (id == null || id <= 0 || source == null || source.toAbsolutePath().getParent() == null) throw new IOException("无效资料标识");
        Path parent = source.toAbsolutePath().getParent().toRealPath();
        root = directory(directory(parent.resolve(".derived")).resolve(id.toString()));
        assets = directory(root.resolve("assets"));
    }

    private static Path directory(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("派生目录不可用");
        } else Files.createDirectory(path);
        return path;
    }

    Asset save(byte[] png) throws Exception {
        DocumentVisionClient.validatePng(png);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
        Path target = assets.resolve(hash + ".png");
        if (Files.isSymbolicLink(target)) throw new IOException("派生图片不可用");
        boolean validExisting = false;
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("派生图片目标不是普通文件");
            if (Files.size(target) <= MAX_BYTES) {
                byte[] current = Files.readAllBytes(target);
                validExisting = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(current)).equals(hash);
            }
        }
        if (!validExisting) {
            long total = 0; int count = 0;
            try (var entries = Files.newDirectoryStream(assets)) {
                for (Path entry : entries) {
                    if (++count > 4096 || Files.isSymbolicLink(entry)) throw new IOException("派生图片目录超过安全上限");
                    if (!entry.equals(target) && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) total += Files.size(entry);
                }
            }
            if (total + png.length > MAX_ALL_BYTES) throw new IOException("派生图片总量超过 64 MiB");
            Path temporary = Files.createTempFile(assets, "image-", ".tmp");
            try {
                Files.write(temporary, png);
                try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temporary); }
        }
        return new Asset("assets/" + hash + ".png", png);
    }

    Asset existing(String src) throws Exception {
        if (src == null || !src.matches("assets/[a-f0-9]{64}\\.png")) throw new IOException("图片引用无效");
        Path path = assets.resolve(src.substring(7));
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES) throw new IOException("派生图片缺失");
        byte[] png = Files.readAllBytes(path); DocumentVisionClient.validatePng(png);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
        if (!src.equals("assets/" + hash + ".png")) throw new IOException("派生图片内容校验失败");
        return new Asset(src, png);
    }

    Asset raster(Path source, int frame) throws Exception {
        if (Files.size(source) > 64L * 1024 * 1024) throw new IOException("原图超过读取上限");
        try (var input = ImageIO.createImageInputStream(source.toFile())) { return save(decode(input, frame)); }
    }

    Asset word(Path source, String reference) throws Exception {
        if (!reference.matches("word:word/[A-Za-z0-9_./-]+\\.xml#[A-Za-z0-9_.-]{1,100}")) throw new IOException("Word 图片引用无效");
        int hash = reference.lastIndexOf('#'); String part = reference.substring(5, hash), id = reference.substring(hash + 1);
        if (part.contains("..") || part.contains("//")) throw new IOException("Word 图片部件无效");
        int slash = part.lastIndexOf('/');
        String relationships = part.substring(0, slash + 1) + "_rels/" + part.substring(slash + 1) + ".rels";
        try (ZipFile zip = new ZipFile(source.toFile())) {
            if (zip.size() > 2048) throw new IOException("Word 内部对象过多");
            long expanded = 0;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                long size = entries.nextElement().getSize();
                if (size > 64L * 1024 * 1024) throw new IOException("Word 解压内容超过上限");
                expanded += Math.max(0, size);
                if (expanded > 64L * 1024 * 1024) throw new IOException("Word 解压内容超过上限");
            }
            var xml = WordLayoutExtractor.secureParse(read(zip, relationships, 1024 * 1024));
            var nodes = xml.getElementsByTagNameNS("http://schemas.openxmlformats.org/package/2006/relationships", "Relationship");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element relation = (Element) nodes.item(i);
                if (!relation.getAttribute("Id").equals(id)) continue;
                if (relation.getAttribute("TargetMode").equalsIgnoreCase("External") || !relation.getAttribute("Type").endsWith("/image")) throw new IOException("不获取外部图片或非图像对象");
                String target = relation.getAttribute("Target");
                if (target.contains("\\") || target.contains("%") || target.contains(":") || target.startsWith("/")) throw new IOException("Word 图片目标无效");
                java.net.URI resolved = java.net.URI.create(part).resolve(target).normalize();
                String name = resolved.getPath();
                if (name == null || !name.startsWith("word/media/") || name.contains("..") || resolved.getQuery() != null || resolved.getFragment() != null) throw new IOException("Word 图片目标越界");
                byte[] bytes = read(zip, name, MAX_BYTES);
                try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) { return save(decode(input, 0)); }
            }
            throw new IOException("Word 内嵌图片关系缺失");
        }
    }

    Asset presentation(Path source, String reference) throws Exception {
        int[] index = officeIndices(reference, "ppt", true);
        validateOffice(source);
        try (var slideshow = SlideShowFactory.create(source.toFile(), null, true)) {
            if (index[0] >= slideshow.getSlides().size()) throw new IOException("幻灯片索引越界");
            var shapes = slideshow.getSlides().get(index[0]).getShapes();
            if (index[1] >= shapes.size() || !(shapes.get(index[1]) instanceof PictureShape<?, ?> picture))
                throw new IOException("目标不是幻灯片内嵌图片");
            if (picture instanceof XSLFPictureShape xslf && xslf.isExternalLinkedPicture()) throw new IOException("不获取外部链接图片");
            var data = picture.getPictureData();
            if (data == null) throw new IOException("幻灯片内嵌图片缺失");
            return embeddedRaster(data.getData());
        }
    }

    Asset spreadsheet(Path source, String reference) throws Exception {
        int[] index = officeIndices(reference, "xlsx", false);
        validateOffice(source);
        try (var workbook = WorkbookFactory.create(source.toFile(), null, true)) {
            if (index[0] >= workbook.getNumberOfSheets()) throw new IOException("工作表索引越界");
            var drawing = workbook.getSheetAt(index[0]).getDrawingPatriarch();
            if (drawing == null) throw new IOException("工作表没有图片绘图层");
            int shapeIndex = 0;
            for (var shape : drawing) {
                if (shapeIndex++ != index[1]) continue;
                if (!(shape instanceof Picture picture)) throw new IOException("目标不是工作表内嵌图片");
                if (picture instanceof XSSFPicture xssf && xssf.getCTPicture().getBlipFill() != null
                        && xssf.getCTPicture().getBlipFill().getBlip() != null
                        && xssf.getCTPicture().getBlipFill().getBlip().isSetLink()) throw new IOException("不获取外部链接图片");
                var data = picture.getPictureData();
                if (data == null) throw new IOException("工作表内嵌图片缺失");
                return embeddedRaster(data.getData());
            }
            throw new IOException("工作表图形索引越界");
        }
    }

    private Asset embeddedRaster(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("内嵌图片为空或超过 8 MiB");
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) { return save(decode(input, 0)); }
    }

    private static int[] officeIndices(String reference, String protocol, boolean oneBasedPage) throws IOException {
        if (reference == null || !reference.matches(protocol + ":[0-9]{1,6}#[0-9]{1,6}")) throw new IOException("Office 图片引用无效");
        String[] parts = reference.substring(protocol.length() + 1).split("#");
        int page = Integer.parseInt(parts[0]), shape = Integer.parseInt(parts[1]);
        if ((oneBasedPage && page < 1) || page > 100000 || shape > 100000) throw new IOException("Office 图片索引越界");
        return new int[]{oneBasedPage ? page - 1 : page, shape};
    }

    private static void validateOffice(Path source) throws IOException {
        if (!Files.isRegularFile(source) || Files.size(source) > MAX_ALL_BYTES) throw new IOException("Office 文件缺失或超过 64 MiB");
        byte[] signature;
        try (var input = Files.newInputStream(source)) { signature = input.readNBytes(2); }
        if (signature.length == 2 && signature[0] == 'P' && signature[1] == 'K') {
            try (ZipFile zip = new ZipFile(source.toFile())) {
                if (zip.size() > 2048) throw new IOException("Office 内部对象过多");
                long expanded = 0; var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    long size = entries.nextElement().getSize();
                    if (size < 0 || size > MAX_ALL_BYTES || (expanded += size) > MAX_ALL_BYTES) throw new IOException("Office 解压内容超过 64 MiB");
                }
            }
        }
    }

    Asset pdf(PDDocument document, int page, double[] rect) throws Exception {
        var source = document.getPage(page - 1); var box = source.getCropBox();
        boolean sideways = Math.floorMod(source.getRotation(), 180) != 0;
        double width = sideways ? box.getHeight() : box.getWidth(), height = sideways ? box.getWidth() : box.getHeight();
        double[] area = rect == null ? new double[]{0, 0, width, height} : rect.clone();
        if (area.length != 4) throw new IOException("图片区域无效");
        for (int i = 0; i < 4; i++) if (!Double.isFinite(area[i])) throw new IOException("图片坐标无效");
        double x0 = Math.max(0, area[0]), y0 = Math.max(0, area[1]);
        double x1 = Math.min(width, area[2]), y1 = Math.min(height, area[3]);
        if (x1 <= x0 || y1 <= y0) throw new IOException("图片区域位于页面外");
        double scale = Math.min(300 / 72.0, Math.sqrt((MAX_PIXELS - 10000) / ((x1 - x0) * (y1 - y0))));
        int left = (int) Math.floor(x0 * scale), top = (int) Math.floor(y0 * scale);
        int pixelsWide = Math.max(1, (int) Math.ceil(x1 * scale) - left), pixelsHigh = Math.max(1, (int) Math.ceil(y1 * scale) - top);
        if ((long) pixelsWide * pixelsHigh > MAX_PIXELS) throw new IOException("图片像素超过上限");
        BufferedImage bitmap = new BufferedImage(pixelsWide, pixelsHigh, BufferedImage.TYPE_INT_RGB);
        var graphics = bitmap.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, pixelsWide, pixelsHigh); graphics.translate(-left, -top);
            new PDFRenderer(document).renderPageToGraphics(page - 1, graphics, (float) scale, (float) scale);
        } finally { graphics.dispose(); }
        try { return save(encode(bitmap)); } finally { bitmap.flush(); }
    }

    private static byte[] decode(javax.imageio.stream.ImageInputStream input, int frame) throws Exception {
        if (input == null) throw new IOException("图片无法读取");
        var readers = ImageIO.getImageReaders(input); if (!readers.hasNext()) throw new IOException("不支持矢量图片或该图像格式");
        ImageReader reader = readers.next();
        try {
            if (!List.of("png", "jpeg", "jpg", "gif", "bmp", "webp", "tif", "tiff").contains(reader.getFormatName().toLowerCase(Locale.ROOT))) throw new IOException("不支持该图片格式");
            reader.setInput(input, false, true);
            int width = reader.getWidth(frame), height = reader.getHeight(frame);
            if (width <= 0 || height <= 0 || (long) width * height > 100_000_000) throw new IOException("原图尺寸超过安全上限");
            int subsample = Math.max(1, (int) Math.ceil(Math.sqrt((double) width * height / MAX_PIXELS)));
            var parameters = reader.getDefaultReadParam(); parameters.setSourceSubsampling(subsample, subsample, 0, 0);
            BufferedImage bitmap = reader.read(frame, parameters);
            if ((long) bitmap.getWidth() * bitmap.getHeight() > MAX_PIXELS) throw new IOException("解码图片像素超过上限");
            try { return encode(bitmap); } finally { bitmap.flush(); }
        } finally { reader.dispose(); }
    }
    private static byte[] encode(BufferedImage bitmap) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(bitmap, "png", output) || output.size() > MAX_BYTES) throw new IOException("派生 PNG 超过 8 MiB");
        return output.toByteArray();
    }
    private static byte[] read(ZipFile zip, String name, int limit) throws IOException {
        var entry = zip.getEntry(name);
        if (entry == null || entry.getSize() > limit) throw new IOException("Word 图片对象缺失或过大");
        try (var input = zip.getInputStream(entry)) {
            byte[] data = input.readNBytes(limit + 1); if (data.length > limit) throw new IOException("Word 图片解压超过上限"); return data;
        }
    }
}
