package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.dyh.learnhub.service.DocumentIngestionService.*;

/** Adds bounded, source-preserving visual derivatives; never depends on the file storage/service graph. */
@Service
public class DocumentMediaProcessor {
    private static final int MAX_REGIONS = 12;
    private static final long TIMEOUT_MS = 180000;
    private static final Set<String> IMAGES = Set.of("png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif", "webp");
    private static final Pattern WORD_IMAGE = Pattern.compile("!\\[([^]]*)]\\((?:word|ppt|xlsx):[^)\\r\\n]+\\)");
    private final PdfLayoutExtractor layout;
    private final DocumentOcrClient ocr;
    private final DocumentVisionClient vision;
    private final ObjectMapper json = new ObjectMapper();
    private final Semaphore slots = new Semaphore(2);
    private final ExecutorService workers = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "document-media"); thread.setDaemon(true); return thread;
    });

    public DocumentMediaProcessor(PdfLayoutExtractor layout, DocumentOcrClient ocr, DocumentVisionClient vision) {
        this.layout = layout; this.ocr = ocr; this.vision = vision;
    }

    public Extraction process(Long fileId, Path source, String ext, Extraction input, boolean force) {
        return process(fileId,source,ext,input,force,true);
    }

    /** Structural refresh may reuse retained visual results without issuing any new OCR/model request. */
    public Extraction process(Long fileId, Path source, String ext, Extraction input, boolean force, boolean allowVision) {
        if (input == null) throw new IllegalArgumentException("缺少抽取结果");
        Context context = new Context(input,allowVision);
        if (!slots.tryAcquire()) { context.warn("图片处理繁忙，原图及未处理范围已保留，请稍后重试", true); return context.result(); }
        Future<Extraction> future;
        try {
            future = workers.submit(() -> {
                try { run(fileId, source, ext == null ? "" : ext.toLowerCase(java.util.Locale.ROOT), input, force, context); }
                catch (Stopped stopped) { context.warn("图片处理达到 180 秒预算，剩余区域待处理；再次处理可复用缓存继续", true); }
                catch (Exception failed) { context.warn("图片资源未能处理，已保留原件；请检查文件格式或派生目录", false); }
                finally { slots.release(); }
                return context.result();
            });
        } catch (RuntimeException unavailable) {
            slots.release(); context.warn("图片处理服务不可用", true); return context.result();
        }
        try { return future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) {
            context.stopped = true; future.cancel(true); Thread.currentThread().interrupt();
            context.warn("图片处理已中断，剩余区域待处理", true); return context.result();
        } catch (Exception failed) {
            context.stopped = true; future.cancel(true);
            context.warn("图片处理超时或未完成，剩余区域待处理；再次处理可复用缓存继续", true); return context.result();
        }
    }

    private void run(Long id, Path source, String ext, Extraction input, boolean force, Context context) throws Exception {
        check(context);
        DocumentMediaAssets assets = new DocumentMediaAssets(id, source);
        if ("pdf".equals(ext)) {
            try (PDDocument document = Loader.loadPDF(source.toFile())) {
                for (Page page : input.pages()) { check(context); processPage(source, ext, page, document, assets, force, context); }
                if (context.allowVision && !force && context.calls == 0 && context.skippedFailures) {
                    context.beginFailureRetry();
                    for (Page page : context.snapshotPages()) { check(context); processPage(source, ext, page, document, assets, false, context); }
                }
            }
        } else {
            for (Page page : input.pages()) { check(context); processPage(source, ext, page, null, assets, force, context); }
            if (context.allowVision && !force && context.calls == 0 && context.skippedFailures) {
                context.beginFailureRetry();
                for (Page page : context.snapshotPages()) { check(context); processPage(source, ext, page, null, assets, false, context); }
            }
        }
        context.rewriteWordReferences();
    }

    private void processPage(Path source, String ext, Page page, PDDocument document, DocumentMediaAssets assets,
                             boolean force, Context context) throws Exception {
        List<Block> result = new ArrayList<>();
        String wholeId = "p" + page.page() + "-media";
        boolean rotatedImageFallback = document != null && Math.floorMod(document.getPage(page.page() - 1).getRotation(), 360) != 0
                && page.blocks().stream().noneMatch(block -> "figure".equals(block.type()) && !block.id().equals(wholeId))
                && hasRasterImage(document, page.page());
        boolean whole = IMAGES.contains(ext) || (document != null && ("ocr".equals(page.method())
                || rotatedImageFallback || page.blocks().isEmpty() || page.blocks().stream().anyMatch(block -> "ocr_required".equals(block.quality()))));
        if (rotatedImageFallback) context.info("第 " + page.page() + " 页包含旋转插图，已保留整页原图并使用整页定位；未声明精确插图坐标");
        List<Block> previousWholeFormulas = page.blocks().stream().filter(block -> block.id().startsWith(wholeId + "-vision-formula-")).toList();
        for (Block block : page.blocks()) {
            check(context);
            if (block.id().equals(wholeId)) continue;
            if (whole && block.id().startsWith(wholeId + "-vision-formula-")) continue;
            if (block.id().contains("-vision-formula-")) {
                String parent = block.id().substring(0, block.id().indexOf("-vision-formula-"));
                if (!context.refreshed.contains(parent)) result.add(block);
                continue;
            }
            if (!Set.of("figure", "formula").contains(block.type()) || (whole && "figure".equals(block.type()))) {
                result.add(block); continue;
            }
            try {
                Region region = region(source, page, block, document, assets, context);
                List<Block> processed = processRegion(region, block, assets, force, context, true);
                result.addAll(processed);
                if (officeSource(block.src()) && !processed.isEmpty())
                    context.reference(block.src(), processed.getFirst().src());
                List<Block> checkpoint = new ArrayList<>(result);
                checkpoint.addAll(page.blocks().subList(page.blocks().indexOf(block) + 1, page.blocks().size()));
                context.page(new Page(page.page(), page.width(), page.height(), page.coordinateUnit(), page.method(), List.copyOf(checkpoint),page.label()));
            } catch (Stopped stopped) { throw stopped; }
            catch (Exception failed) {
                context.warn("第 " + page.page() + " 个逻辑页/页面的图片 " + block.id() + " 无法安全提取，原件保留", false);
                result.add(reliable(block) ? block : copy(block, block.src(), "failed", false, block.cleanText(), block.latex(),
                        block.latexStatus(), "图片提取失败，原件保留", "[图片未能提取，请查看原文]"));
            }
        }
        if (whole) {
            Block previous = page.blocks().stream().filter(block -> block.id().equals(wholeId)).findFirst().orElse(null);
            try {
                var asset = previous != null && previous.src() != null && previous.src().startsWith("assets/")
                        ? assets.existing(previous.src()) : document != null ? assets.pdf(document, page.page(), null)
                        : assets.raster(source, Math.max(0, page.page() - 1));
                double[] rect = page.width() > 0 && page.height() > 0 ? new double[]{0, 0, page.width(), page.height()} : null;
                Block image = previous != null ? previous : new Block(wholeId, "figure", "", "", page.page(), rect,
                        page.width(), page.height(), null, "pending", false, -1, -1,0,null,null,null,null,null,-1,-1,
                        new Presentation(null,context.hasIndexedPage(page.page())?"annotation":"block",null,null,null,"page-image:"+wholeId));
                Region region = new Region(asset, rect, page.width(), page.height(), true);
                List<Block> processed = processRegion(region, image, assets, force, context, false);
                boolean formulaOnly = processed.stream().anyMatch(block -> block.id().equals(wholeId) && "formula".equals(block.type())
                        && block.indexed() && "recognized".equals(block.latexStatus()));
                if (formulaOnly && ("ocr".equals(page.method()) || IMAGES.contains(ext))) result.replaceAll(block -> block.indexed() && !block.id().contains("-vision-formula-")
                        ? copy(block, block.src(), "superseded_by_formula", false, block.cleanText(), block.latex(), block.latexStatus(),
                        "整页由视觉模型识别为公式，OCR 候选保留但不参与索引", block.markdown()) : block);
                if(formulaOnly && result.stream().noneMatch(Block::indexed)) {
                    processed=processed.stream().map(block -> block.id().equals(wholeId)?withPlacement(block,"block"):block).toList();
                }
                result.addAll(processed);
                // Preserve the image/caption/formula group in source order on cache hits and failed force attempts.
                if (!context.refreshed.contains(wholeId)) result.addAll(previousWholeFormulas);
            } catch (Stopped stopped) { throw stopped; }
            catch (Exception failed) {
                context.warn("第 " + page.page() + " 页整页原图未能生成，原件保留", false);
                if (previous != null) result.add(previous);
                result.addAll(previousWholeFormulas);
            }
        }
        LinkedHashMap<String, Block> unique = new LinkedHashMap<>(); for (Block block : result) unique.put(block.id(), block);
        context.page(new Page(page.page(), page.width(), page.height(), page.coordinateUnit(), page.method(), List.copyOf(unique.values()),page.label()));
    }

    private record Region(DocumentMediaAssets.Asset asset, double[] rect, double width, double height, boolean whole) { }

    private static boolean hasRasterImage(PDDocument document, int pageNumber) {
        var page = document.getPage(pageNumber - 1); var box = page.getCropBox();
        double area = box.getWidth() * box.getHeight();
        return new PdfFigureScanner(page).scan().stream().anyMatch(rect -> rect.length == 4
                && Double.isFinite(rect[0]) && Double.isFinite(rect[1]) && Double.isFinite(rect[2]) && Double.isFinite(rect[3])
                && rect[2] - rect[0] >= 24 && rect[3] - rect[1] >= 24
                && (rect[2] - rect[0]) * (rect[3] - rect[1]) >= area * .01);
    }

    private Region region(Path source, Page page, Block block, PDDocument document, DocumentMediaAssets assets, Context context) throws Exception {
        if (block.src() != null && block.src().startsWith("assets/"))
            return new Region(assets.existing(block.src()), block.rect(), block.width(), block.height(), false);
        if (block.src() != null && block.src().startsWith("word:"))
            return new Region(assets.word(source, block.src()), null, 0, 0, false);
        if (block.src() != null && block.src().startsWith("ppt:"))
            return new Region(assets.presentation(source, block.src()), null, 0, 0, false);
        if (block.src() != null && block.src().startsWith("xlsx:"))
            return new Region(assets.spreadsheet(source, block.src()), null, 0, 0, false);
        if (document == null) throw new IllegalArgumentException();
        double[] rect = block.rect();
        double[] cropRect=rect;
        if (block.src() != null && block.src().matches("[0-9]+-[0-9]+")) {
            String[] parts = block.src().split("-");
            if (Integer.parseInt(parts[0]) != page.page()) throw new IllegalArgumentException();
            // Keep the v2 crop raster stable so a display-only refresh can reuse its visual cache.
            int[] pixels=rect==null?layout.figureRect(source,page.page(),Integer.parseInt(parts[1]),72)
                    :new int[]{(int)Math.round(rect[0]),(int)Math.round(rect[1]),Math.max(1,(int)Math.round(rect[2]-rect[0])),
                    Math.max(1,(int)Math.round(rect[3]-rect[1]))};
            if (pixels != null) {
                cropRect=new double[]{pixels[0],pixels[1],pixels[0]+pixels[2],pixels[1]+pixels[3]};
                if(rect==null) rect=cropRect;
            }
        }
        boolean full = rect == null;
        if (full) {
            rect = new double[]{0, 0, page.width(), page.height()};
            cropRect=rect;
            context.warn("第 " + page.page() + " 页图片缺少可靠裁图坐标，保留整页原图；定位范围为整页", false);
        }
        return new Region(assets.pdf(document, page.page(), cropRect), rect, page.width(), page.height(), full);
    }

    private List<Block> processRegion(Region region, Block original, DocumentMediaAssets assets, boolean force,
                                      Context context, boolean localOcr) throws Exception {
        check(context);
        Presentation presentation=original.presentation();
        if(presentation==null && "figure".equals(original.type())) presentation=new Presentation(null,"block",null,null,null,
                original.src()==null?"page-image:"+original.id():original.src());
        Block located = new Block(original.id(), original.type(), original.rawText(), original.cleanText(), original.page(),
                region.rect(), region.width(), region.height(), original.confidence(), original.quality(), original.indexed(),
                original.start(), original.end(), original.level(), region.asset().src(), original.latex(), original.latexStatus(),
                original.latexMessage(), original.markdown(), -1, -1,presentation);
        String image = "![原图](" + region.asset().src() + ")";
        if ("formula".equals(original.type()) && original.latex() != null && "restored".equals(original.latexStatus()))
            return List.of(copy(located, region.asset().src(), original.quality(), original.indexed(), original.cleanText(),
                    original.latex(), original.latexStatus(), original.latexMessage(), image + "\n\n$$\n" + original.latex() + "\n$$"));
        if (!force && reliable(original) && original.markdown() != null
                && ("recognized".equals(original.latexStatus()) || "recognized".equals(original.quality())
                && (original.latexMessage() == null || !original.latexMessage().startsWith("视觉识别未完成"))))
            return List.of(located);
        String purpose = "formula".equals(original.type()) ? "formula" : "image";
        String identity = vision.identity();
        Path cache = cachePath(assets, region.asset().src(), purpose, identity == null ? "unavailable" : identity);
        DocumentVisionClient.Result previous = readCache(cache);
        boolean cachedFailure = previous != null && (!"recognized".equals(previous.status()) || previous.uncertain());
        if (!force && !context.retryFailures && cachedFailure) context.skippedFailures = true;
        DocumentVisionClient.Result recognized = !force && !(context.retryFailures && cachedFailure) ? previous : null;
        if (recognized == null) {
            boolean available = context.allowVision && Boolean.TRUE.equals(vision.capabilities().get("available"));
            if (!context.allowVision) recognized = new DocumentVisionClient.Result("image","pending","","","",List.of(),true,
                    List.of("本次仅刷新结构和复用已有识别缓存，尚未识别的原图已保留"),"","",DocumentVisionClient.PROMPT_VERSION);
            else if (!available) recognized = new DocumentVisionClient.Result("image", "unavailable", "", "", "", List.of(), true,
                    List.of("未配置可用的图片模型，原图及本地 OCR 保留"), "", "", DocumentVisionClient.PROMPT_VERSION);
            else if (context.calls >= MAX_REGIONS) recognized = new DocumentVisionClient.Result("image", "pending", "", "", "", List.of(), true,
                    List.of("本次已达到 12 个视觉区域上限，再次处理可复用缓存继续"), "", "", DocumentVisionClient.PROMPT_VERSION);
            else {
                context.calls++; recognized = vision.analyze(region.asset().png(), purpose, remaining(context)); check(context);
                if (recognized != null && Set.of("recognized", "partial", "failed").contains(recognized.status())
                        && !(previous != null && "recognized".equals(previous.status()) && !previous.uncertain()
                        && (!"recognized".equals(recognized.status()) || recognized.uncertain()))
                        && java.util.Objects.equals(identity, vision.identity())) {
                    try { writeCache(cache, recognized); }
                    catch (Exception failed) { context.warn("视觉缓存未能保存，本次可靠识别结果仍保留", false); }
                }
            }
        }
        if (recognized == null) throw new IllegalStateException();
        if (!"recognized".equals(recognized.status()) || recognized.uncertain()) {
            context.warn("区域 " + original.id() + "：" + String.join("；", recognized.notes()), "pending".equals(recognized.status()));
            if (reliable(original)) return List.of(located);
            if (previous != null && "recognized".equals(previous.status()) && !previous.uncertain()) {
                context.warn("重新识别未成功，保留已有可靠识别结果", false); recognized = previous;
            }
            else {
                String local = context.allowVision && localOcr && !"formula".equals(original.type()) ? localText(region.asset().png(), context) : "";
                String quality = switch (recognized.status()) { case "unavailable" -> "vision_unavailable"; case "partial" -> "vision_uncertain"; case "pending" -> "pending"; default -> "failed"; };
                boolean index = !local.isBlank();
                return List.of(copy(located, region.asset().src(), index ? "recognized" : quality, index, local,
                        original.latex(), "formula".equals(original.type()) ? "partial" : original.latexStatus(),
                        "视觉识别未完成；本地 OCR 如有合格正文则保留", image + (local.isBlank() ? "" : "\n\n" + prose(local))
                                + "\n\n> 图片识别状态：" + quality));
            }
        }
        context.info("区域 " + original.id() + " 由视觉档案「" + recognized.profile() + "」/" + recognized.model()
                + " 识别，提示词 " + recognized.promptVersion() + "；模型未提供校准置信度，请对照原图");
        if (purpose.equals("formula") && !recognized.kind().equals("formula")) {
            context.warn("区域 " + original.id() + " 未返回可靠公式，保留原图", false);
            return List.of(reliable(original) ? located : copy(located, region.asset().src(), "vision_uncertain", false,
                    original.cleanText(), original.latex(), "partial", "图片模型未识别为公式", image + "\n\n> 公式待核对"));
        }
        List<Block> output = new ArrayList<>();
        if (recognized.kind().equals("formula")) {
            String latex = recognized.latex();
            output.add(copy(located, region.asset().src(), "recognized", true, "$$\n" + latex + "\n$$", latex,
                    "recognized", "视觉模型按原图转写，请核对", image + "\n\n$$\n" + latex + "\n$$"));
        } else {
            String text = join(recognized.ocrText(), recognized.description());
            // Whole OCR pages already carry trusted OCR text; avoid indexing a second copy from the vision response.
            boolean existingOcr = region.whole() && context.hasIndexedPage(original.page());
            if (existingOcr) text = recognized.description();
            output.add(copy(located, region.asset().src(), "recognized", !text.isBlank(), text, null, null,
                    "视觉模型仅描述可见事实，请核对原图", image + (recognized.description().isBlank() ? "" : "\n\n" + prose(recognized.description()))
                            + (recognized.ocrText().isBlank() || existingOcr ? "" : "\n\n" + prose(recognized.ocrText()))));
        }
        int number = 0;
        for (String latex : recognized.formulas()) {
            if (latex.equals(recognized.latex())) continue;
            output.add(new Block(original.id() + "-vision-formula-" + (++number), "formula", "", "$$\n" + latex + "\n$$",
                    original.page(), region.rect(), region.width(), region.height(), null, "recognized", true, -1, -1,
                    0, region.asset().src(), latex, "recognized", "从原图提取的独立公式，请核对", "$$\n" + latex + "\n$$", -1, -1,
                    new Presentation(presentation==null?null:presentation.groupId(),"annotation",null,null,null,
                            presentation==null?original.src():presentation.sourceRef())));
        }
        context.refreshed.add(original.id());
        return output;
    }

    private String localText(byte[] png, Context context) {
        if (!ocr.configured()) return "";
        try {
            var result = ocr.recognize(png, remaining(context)); check(context);
            List<String> text = new ArrayList<>();
            for (var block : result.blocks()) if (block.confidence() >= .65
                    && "accepted".equals(DocumentIngestionService.quality(DocumentTextService.tidy(block.text()), block.confidence()))) text.add(DocumentTextService.tidy(block.text()));
            if (!text.isEmpty()) context.info("图片可见文字由本地 OCR 提取；未将视觉模型状态当作 OCR 置信度");
            return String.join("\n\n", text);
        } catch (Stopped stopped) { throw stopped; }
        catch (Exception failed) { context.warn("图片本地 OCR 未成功，已保留原图", false); return ""; }
    }

    private Path cachePath(DocumentMediaAssets assets, String image, String purpose, String identity) throws Exception {
        Path directory = assets.root.resolve("vision");
        if (Files.isSymbolicLink(directory)) throw new IllegalStateException();
        if (!Files.exists(directory)) Files.createDirectory(directory);
        return directory.resolve(DocumentVisionClient.hash(image + "\n" + purpose + "\n" + identity) + ".json");
    }
    private DocumentVisionClient.Result readCache(Path path) {
        try {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 128000) return null;
            var result = json.readValue(Files.readAllBytes(path), DocumentVisionClient.Result.class);
            if (!Set.of("recognized", "partial", "failed").contains(result.status())
                    || ("recognized".equals(result.status()) && result.uncertain()) || result.formulas() == null || result.notes() == null
                    || result.latex() == null || result.ocrText() == null || result.description() == null
                    || result.formulas().size() > 12 || result.notes().size() > 20 || result.ocrText().length() > 20000
                    || result.description().length() > 2000 || !Set.of("formula", "image", "document").contains(result.kind())) return null;
            if (!result.latex().isBlank()) FormulaRecognitionService.validateLatex(result.latex());
            for (String latex : result.formulas()) FormulaRecognitionService.validateLatex(latex);
            return result;
        } catch (Exception invalid) { return null; }
    }
    private void writeCache(Path path, DocumentVisionClient.Result result) throws Exception {
        byte[] bytes = json.writeValueAsBytes(result); if (bytes.length > 128000 || Files.isSymbolicLink(path)) return;
        Path temporary = Files.createTempFile(path.getParent(), "vision-", ".tmp");
        try { Files.write(temporary, bytes); Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary); }
    }
    private static boolean reliable(Block block) { return block.indexed() && !block.cleanText().isBlank(); }
    private static boolean officeSource(String src) { return src != null && (src.startsWith("word:") || src.startsWith("ppt:") || src.startsWith("xlsx:")); }
    private static String join(String first, String second) { return first.isBlank() ? second : second.isBlank() ? first : first + "\n\n" + second; }
    private static String prose(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("`", "\\`")
                .replace("*", "\\*").replace("_", "\\_").replace("#", "\\#").replace("!", "\\!");
    }
    private static Block copy(Block block, String src, String quality, boolean indexed, String clean, String latex,
                              String latexStatus, String message, String markdown) {
        String type = latex != null && !latex.isBlank() && "recognized".equals(latexStatus) && "recognized".equals(quality) ? "formula" : block.type();
        return new Block(block.id(), type, block.rawText(), clean, block.page(), block.rect(), block.width(), block.height(),
                // Vision results never carry a fabricated numerical confidence.
                block.confidence(), quality, indexed, -1, -1, block.level(), src, latex, latexStatus, message, markdown, -1, -1,block.presentation());
    }
    private static Block withPlacement(Block block,String placement) {
        var p=block.presentation();
        return new Block(block.id(),block.type(),block.rawText(),block.cleanText(),block.page(),block.rect(),block.width(),block.height(),
                block.confidence(),block.quality(),block.indexed(),block.start(),block.end(),block.level(),block.src(),block.latex(),block.latexStatus(),
                block.latexMessage(),block.markdown(),block.markdownStart(),block.markdownEnd(),
                new Presentation(p==null?null:p.groupId(),placement,p==null?null:p.widthPx(),p==null?null:p.heightPx(),p==null?null:p.align(),p==null?null:p.sourceRef()));
    }
    private static Duration remaining(Context context) { check(context); return Duration.ofNanos(Math.max(1, context.deadline - System.nanoTime())); }
    private static void check(Context context) {
        if (context.stopped || Thread.currentThread().isInterrupted() || System.nanoTime() >= context.deadline) throw new Stopped();
    }
    private static final class Stopped extends RuntimeException { }
    @PreDestroy public void close() { workers.shutdownNow(); }

    private static final class Context {
        final Extraction input;
        final boolean allowVision;
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        final List<Page> pages;
        final List<String> warnings;
        final List<String> baselineWarnings;
        final Map<String, String> wordReferences = new LinkedHashMap<>();
        final Set<String> refreshed = new java.util.HashSet<>();
        volatile boolean stopped;
        boolean partial, truncated;
        boolean skippedFailures, retryFailures;
        int calls;
        Context(Extraction input,boolean allowVision) {
            this.allowVision=allowVision;
            this.input = input; pages = new ArrayList<>(input.pages()); warnings = new ArrayList<>(input.report().warnings());
            baselineWarnings = List.copyOf(input.report().warnings());
            // Historical media-only failures can be repaired by this run; assess remaining blocks again below.
            partial = false; truncated = input.report().truncated();
        }
        synchronized void warn(String value, boolean limited) { info(value); partial = true; }
        synchronized void truncate(String value) { warn(value, true); truncated = true; }
        synchronized void info(String value) { if (warnings.size() < 200 && !warnings.contains(value)) warnings.add(value); }
        synchronized List<Page> snapshotPages() { return List.copyOf(pages); }
        synchronized void beginFailureRetry() {
            retryFailures = true; skippedFailures = false; partial = false; refreshed.clear();
            warnings.clear(); warnings.addAll(baselineWarnings);
            info("未发现新的未尝试区域；复用可靠结果后重试此前失败或不确定的图片区域");
        }
        synchronized boolean hasIndexedPage(int page) {
            return pages.stream().filter(p -> p.page() == page).flatMap(p -> p.blocks().stream())
                    .anyMatch(block -> block.indexed() && !block.id().equals("p" + page + "-media") && !block.id().contains("-vision-formula-"));
        }
        synchronized void reference(String source, String asset) { if (asset != null && asset.matches("assets/[a-f0-9]{64}\\.png")) wordReferences.put(source, asset); }
        synchronized void page(Page page) {
            if (stopped) return;
            for (int i = 0; i < pages.size(); i++) if (pages.get(i).page() == page.page()) { pages.set(i, page); return; }
            pages.add(page);
        }
        synchronized void rewriteWordReferences() {
            for (int p = 0; p < pages.size(); p++) {
                Page page = pages.get(p); List<Block> blocks = new ArrayList<>();
                for (Block block : page.blocks()) {
                    String markdown = block.markdown();
                    String src = block.src();
                    String quality = block.quality();
                    if (officeSource(src)) {
                        String converted = wordReferences.get(src);
                        if (converted != null) src = converted;
                        else {
                            // Keep the controlled source relationship for a later retry, but never expose it as an image URL.
                            markdown = "[图片未能提取，请查看原文]";
                            if ("non_text".equals(quality) && Set.of("figure", "image").contains(block.type())) quality = "pending";
                        }
                    }
                    if (markdown != null && (markdown.contains("word:") || markdown.contains("ppt:") || markdown.contains("xlsx:"))) {
                        for (var entry : wordReferences.entrySet()) markdown = markdown.replace("](" + entry.getKey() + ")", "](" + entry.getValue() + ")")
                                .replace("src=\""+entry.getKey()+"\"","src=\""+entry.getValue()+"\"")
                                .replace("src='"+entry.getKey()+"'","src='"+entry.getValue()+"'");
                        markdown = WORD_IMAGE.matcher(markdown).replaceAll("[图片未能提取，请查看原文]");
                        markdown=markdown.replaceAll("<img\\b[^>]*\\bsrc=[\"'](?:word|ppt|xlsx):[^>]*>","[图片未能提取，请查看原文]");
                    }
                    blocks.add(copy(block, src, quality, block.indexed(), block.cleanText(), block.latex(), block.latexStatus(), block.latexMessage(), markdown));
                }
                pages.set(p, new Page(page.page(), page.width(), page.height(), page.coordinateUnit(), page.method(), List.copyOf(blocks),page.label()));
            }
        }
        synchronized Extraction result() {
            rewriteWordReferences();
            StringBuilder text = new StringBuilder(); List<Page> finalPages = new ArrayList<>(); int rejected = 0;
            boolean incomplete = input.report().processedPages() < input.report().totalPages() || pages.size() < input.report().totalPages();
            for (Page page : pages) {
                List<Block> blocks = new ArrayList<>();
                for (Block block : page.blocks()) {
                    boolean indexed = block.indexed(); int start = -1, end = -1;
                    if (indexed && text.length() + block.cleanText().length() + 2 > DocumentTextService.MAX_TEXT_CHARS) { indexed = false; truncate("视觉结果超过 40 万字符上限，剩余块未进入索引"); }
                    if (indexed && !block.cleanText().isBlank()) { if (!text.isEmpty()) text.append("\n\n"); start = text.length(); text.append(block.cleanText()); end = text.length(); }
                    if (!indexed && !Set.of("empty", "non_text", "page_furniture", "superseded_by_formula").contains(block.quality())) incomplete = true;
                    blocks.add(new Block(block.id(), block.type(), block.rawText(), block.cleanText(), block.page(), block.rect(), block.width(), block.height(),
                            block.confidence(), block.quality(), indexed, start, end, block.level(), block.src(), block.latex(), block.latexStatus(), block.latexMessage(), block.markdown(), -1, -1,block.presentation()));
                }
                if (blocks.stream().noneMatch(Block::indexed)) rejected++;
                finalPages.add(new Page(page.page(), page.width(), page.height(), page.coordinateUnit(), page.method(), List.copyOf(blocks),page.label()));
            }
            String status = text.isEmpty() ? input.status().equals("failed") ? "failed" : "empty" : "ok";
            return new Extraction(status, text.toString(), text.length(), text.isEmpty() ? input.error() : null, input.sourceSha256(),
                    PIPELINE_VERSION, List.copyOf(finalPages), new QualityReport(input.report().totalPages(), input.report().processedPages(),
                    input.report().ocrPages(), rejected, input.report().meanConfidence(), List.copyOf(warnings), truncated, partial || incomplete));
        }
    }
}
