package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Explicit, resumable-by-retry migrations. Reading a document never starts a model or conversion job. */
@Service
public class DocumentProcessingJobs {
    private final FileStorageService files;
    private final VectorIndexService vectors;
    private final ObjectMapper json = new ObjectMapper();
    private final Path jobDirectory=Path.of(System.getProperty("user.dir"),"uploads",".derived","document-jobs");
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),task -> {
        Thread thread = new Thread(task,"document-markdown-jobs"); thread.setDaemon(true); return thread;
    });

    public DocumentProcessingJobs(FileStorageService files, VectorIndexService vectors) { this.files=files; this.vectors=vectors; }

    private static final class Job {
        final String id=UUID.randomUUID().toString(), kind, key;
        final Long fileId;
        final Boolean resume;
        String status="queued", stage="等待处理", error;
        int done,total,failed,partial;
        long updatedAt=System.currentTimeMillis();
        long progressPersistedAt;
        DocumentIngestionService.Progress pageProgress;
        Map<String,Object> scanProgress;
        boolean pendingUpgrade, scanMode;
        long pageRound;
        Map<String,Object> index=Map.of();
        final List<Map<String,Object>> items=new ArrayList<>();
        Job(String kind,String key) { this(kind,key,null,null); }
        Job(String kind,String key,Long fileId,Boolean resume) {
            this.kind=kind; this.key=key; this.fileId=fileId; this.resume=resume;
        }
        synchronized Map<String,Object> view() {
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("id",id); result.put("kind",kind); result.put("status",status); result.put("done",done); result.put("total",total);
            if (fileId!=null) result.put("fileId",String.valueOf(fileId));
            if (resume!=null) result.put("resume",resume);
            if (pageProgress!=null) result.put("pageProgress",Map.of("stage",pageProgress.stage(),
                    "completedPages",pageProgress.completedPages(),"totalPages",pageProgress.totalPages(),
                    "currentPage",pageProgress.currentPage()));
            if (scanMode) result.put("scanMode", true);
            result.put("scanProgress", scanProgress);
            result.put("pendingUpgrade", pendingUpgrade);
            result.put("failed",failed); result.put("partial",partial); result.put("stage",stage); result.put("error",error);
            result.put("index",index); result.put("items",List.copyOf(items)); result.put("updatedAt",updatedAt); return result;
        }
        synchronized boolean active() { return status.equals("queued") || status.equals("running"); }
    }

    public synchronized Map<String,Object> startMedia(Long id,boolean force) {
        return startMedia(id,force,false);
    }

    /** Local image scanning is independent from the paid visual-model force flag. */
    public synchronized Map<String,Object> startMedia(Long id,boolean force,boolean localScan) {
        String key="media:"+id+(localScan?":local-scan":"");
        Job prior=activeFileJob(id,key);
        if (prior!=null) return prior.view();
        files.detail(id);
        Job job=new Job("media",key,id,null); job.total=1;
        submit(job,() -> run(job,List.of(id),force,true,true,localScan)); return job.view();
    }

    public synchronized Map<String,Object> startMigration(boolean force,boolean processMedia) {
        Job prior=jobs.values().stream().filter(job -> job.key.equals("migration") && job.active()).findFirst().orElse(null);
        if (prior!=null) return prior.view();
        Job job=new Job("migration","migration");
        submit(job,() -> {
            try { run(job,files.markdownCandidates(force,processMedia),force,processMedia,false); }
            catch (Exception failed) { fail(job,"无法读取待转换资料，请稍后重试"); }
        }); return job.view();
    }

    public Map<String,Object> startStructure(Long id) { return startStructure(id,false); }

    public Map<String,Object> startStructure(Long id,boolean resume) { return startBody(id,"structure",resume); }

    public Map<String,Object> startExtraction(Long id,boolean resume) { return startBody(id,"extraction",resume); }

    public Map<String,Object> startExtraction(Long id,boolean resume,boolean force) { return startBody(id,"extraction",resume,force); }

    private Map<String,Object> startBody(Long id,String kind,boolean resume) { return startBody(id,kind,resume,false); }

    private synchronized Map<String,Object> startBody(Long id,String kind,boolean resume,boolean force) {
        String key=kind+":"+id+":"+resume+(force?":replace-generated":"");
        Job prior=activeFileJob(id,key);
        if (prior!=null) return prior.view();
        files.detail(id);
        Job job=new Job(kind,key,id,resume);
        job.total=1;
        job.scanMode="structure".equals(kind) || files.prefersScan(id);
        submit(job,() -> runBody(job,force));
        return job.view();
    }

    private Job activeFileJob(Long id,String key) {
        Job prior=jobs.values().stream().filter(job -> Objects.equals(job.fileId,id) && job.active()).findFirst().orElse(null);
        if (prior!=null && !prior.key.equals(key)) throw new IllegalArgumentException("该资料已有处理任务，请等待任务结束");
        return prior;
    }

    private void runBody(Job job,boolean force) {
        boolean structured="structure".equals(job.kind);
        synchronized(job) { job.status="running"; job.stage="准备抽取正文"; }
        persist(job);
        Map<String,Object> item=new LinkedHashMap<>();
        item.put("id",job.fileId);
        try {
            var detail=runPageBatches(job,job.fileId,structured,job.resume,force);
            String textStatus=String.valueOf(detail.get("textStatus"));
            boolean noProgress=Boolean.TRUE.equals(detail.get("noProgress"));
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("正文抽取已中断，已保存的结果保留");
            if (!noProgress && DocumentTextService.STATUS_FAILED.equals(textStatus)) {
                String reason=detail.get("textError") instanceof String error && !error.isBlank() ? error : "正文抽取失败，原件已保留";
                throw new IllegalStateException(reason);
            }
            var report=json.valueToTree(scanSummary(detail)).path("report");
            List<String> warnings = new ArrayList<>();
            report.path("warnings").forEach(warning -> { if (warning.isTextual()) warnings.add(warning.asText()); });
            String limitHint = DocumentExtractionLimits.jobHint(warnings);
            long candidates=detail.get("pendingFormulas") instanceof Number count ? count.longValue() : 0;
            boolean incomplete=report.path("partial").asBoolean() || report.path("truncated").asBoolean()
                    || candidates>0 || noProgress || Boolean.TRUE.equals(detail.get("pendingUpgrade"));
            String reason=(noProgress || Boolean.TRUE.equals(detail.get("pendingUpgrade")))
                    && detail.get("resumeMessage") instanceof String message ? message : null;
            item.put("textStatus",textStatus);
            item.put("status",incomplete?"partial":"completed");
            item.put("pendingFormulas",candidates);
            if (noProgress) item.put("noProgress",true);
            if (reason!=null) item.put("error",reason);
            if (detail.containsKey("scanProgress")) item.put("scanProgress",detail.get("scanProgress"));
            if (Boolean.TRUE.equals(detail.get("pendingUpgrade"))) item.put("pendingUpgrade",true);
            synchronized(job) {
                job.items.add(Map.copyOf(item));
                job.done=1;
                if (incomplete) job.partial=1;
            }
            boolean usable=DocumentTextService.STATUS_OK.equals(textStatus);
            boolean indexed=!usable || noProgress || Boolean.TRUE.equals(detail.get("pendingUpgrade")) || updateIndex(job);
            synchronized(job) {
                job.status=incomplete || !indexed?"partial":"completed";
                if (reason!=null) job.stage=reason;
                else if (incomplete && limitHint!=null) job.stage=limitHint;
                else if (candidates>0) job.stage="本批识别已结束，"+candidates+" 处公式待核对";
                else if (incomplete) job.stage="本批正文抽取已结束，请查看抽取报告";
                else if (!usable) job.stage=detail.get("textError") instanceof String message && !message.isBlank()
                        ? message : "处理已结束，未提取到可检索正文";
                else if (!indexed) job.stage="正文已保存，向量索引待更新";
                else if (Boolean.FALSE.equals(job.index.get("configured"))) job.stage="正文、Markdown 与定位已保存，可使用关键词检索";
                else job.stage="Markdown、定位与检索已更新";
                persist(job);
            }
        } catch (Exception failed) {
            String reason=safe(failed);
            synchronized(job) {
                job.failed=1; job.done=1;
                job.items.add(Map.of("id",job.fileId,"status","failed","error",reason));
            }
            fail(job,reason);
        }
    }

    private static Object scanSummary(Map<String,Object> detail) {
        return detail.containsKey("scanProgress") ? detail.get("scanProgress") : detail.get("extraction");
    }

    /** A background request can span bounded page batches; failed or capacity-limited pages never spin forever. */
    private Map<String,Object> runPageBatches(Job job,Long id,boolean structured,boolean resume,boolean replaceGeneratedProse) {
        Map<String,Object> detail=Map.of();
        int priorCompleted=-1,priorProcessed=-1;
        for (int round=0;round<100;round++) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("扫描已中断，已保存的进度保留");
            final long revision;
            synchronized(job) { revision=++job.pageRound; job.pageProgress=null; job.scanMode=structured || files.prefersScan(id); }
            java.util.function.Consumer<DocumentIngestionService.Progress> listener=progress -> {
                synchronized(job) { if (job.pageRound==revision) recordPageProgress(job,progress); }
            };
            detail=replaceGeneratedProse ? files.extractInBackground(id,structured,resume || round>0,listener,true)
                    : files.extractInBackground(id,structured,resume || round>0,listener);
            var summary=json.valueToTree(scanSummary(detail));
            boolean scan=DocumentIngestionService.STRUCTURED_PIPELINE_VERSION.equals(summary.path("pipelineVersion").asText());
            synchronized(job) {
                job.scanMode=scan;
                job.pendingUpgrade=Boolean.TRUE.equals(detail.get("pendingUpgrade"));
                job.scanProgress=scan ? json.convertValue(summary,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() { }) : null;
            }
            persist(job);
            int total=summary.path("report").path("totalPages").asInt();
            int processed=summary.path("report").path("processedPages").asInt();
            int completed=summary.path("completedPages").asInt();
            boolean capacity=false;
            for (var limit:summary.path("limitReasons")) if ("document_capacity".equals(limit.path("code").asText())) capacity=true;
            capacity &= !summary.path("canResumeCharacterLimit").asBoolean();
            if (!scan || total<=0 || completed>=total || capacity || Boolean.TRUE.equals(detail.get("noProgress"))
                    || completed<=priorCompleted && processed<=priorProcessed) break;
            priorCompleted=completed; priorProcessed=processed;
        }
        return detail;
    }

    private void recordPageProgress(Job job,DocumentIngestionService.Progress progress) {
        synchronized(job) {
            var previous = job.pageProgress;
            if (!"running".equals(job.status) || (!"migration".equals(job.kind) && job.done > 0) || progress.equals(previous)) return;
            if (previous != null && previous.totalPages() > 0 && progress.totalPages() == 0) return;
            // An OCR callback may arrive as a timed-out batch moves on to saving.
            if (previous != null && "extracting".equals(progress.stage())
                    && ("media".equals(previous.stage()) || "saving".equals(previous.stage()))) return;
            boolean stageChanged=previous==null || !progress.stage().equals(previous.stage());
            boolean totalChanged=previous==null || progress.totalPages()!=previous.totalPages();
            job.pageProgress=progress;
            job.stage=switch(progress.stage()) {
                case "extracting" -> progress.currentPage()>0 ? "正在抽取第 "+progress.currentPage()+" 页" : "正在抽取正文";
                case "media" -> "正在处理公式与图片";
                case "saving" -> "正在保存正文与定位";
                default -> "准备抽取正文";
            };
            job.updatedAt=System.currentTimeMillis();
            if (stageChanged || totalChanged || job.updatedAt-job.progressPersistedAt>=1_000) {
                job.progressPersistedAt=job.updatedAt;
                persist(job);
            }
        }
    }

    private void submit(Job job,Runnable task) {
        jobs.put(job.id,job); persist(job);
        try { worker.execute(task); }
        catch (RuntimeException busy) { fail(job,"文档处理队列已满，请稍后重试"); }
    }

    private void run(Job job,List<Long> ids,boolean force,boolean processMedia,boolean mediaOnly) {
        run(job,ids,force,processMedia,mediaOnly,false);
    }

    private void run(Job job,List<Long> ids,boolean force,boolean processMedia,boolean mediaOnly,boolean forceLocalScan) {
        synchronized(job) { job.status="running"; job.total=ids.size(); job.stage="转换 Markdown 与处理媒体"; }
        persist(job);
        for (Long id:ids) {
            if (Thread.currentThread().isInterrupted()) { fail(job,"任务已中断，已完成的资料保留；重试会跳过已转换版本"); return; }
            Map<String,Object> item=new LinkedHashMap<>(); item.put("id",id);
            try {
                boolean scan=!mediaOnly && files.prefersScan(id);
                boolean localScan=forceLocalScan || !mediaOnly && force && processMedia;
                var localSession=new DocumentMediaProcessor.LocalScanSession();
                Map<String,Object> detail=scan ? runPageBatches(job,id,true,files.hasPendingScan(id) || files.hasUnfinishedScan(id),force)
                        : (mediaOnly || !force && files.isCurrentMarkdown(id) && processMedia)
                        ? files.processMedia(id,force,localScan,localSession)
                        : localScan ? files.reextract(id,processMedia,true,localSession) : files.reextract(id,processMedia);
                // Explicit background work continues bounded rounds; successful regions stay cached.
                // Do not repeatedly guess uncertain/failed results, or loop when a round makes no progress.
                int pending=processMedia && !scan?files.pendingMediaRegions(id):0;
                var localProgress=json.valueToTree(scanSummary(detail)).path("localScanProgress");
                for(int round=0; pending>0 && round<100 && !Thread.currentThread().isInterrupted(); round++) {
                    if (localProgress.path("requested").asBoolean() && localProgress.path("unattempted").asInt()==0
                            && localProgress.path("retryableFailures").asInt()==0) break;
                    synchronized(job) { job.stage="继续处理公式与图片，剩余 "+pending+" 个区域"; }
                    persist(job);
                    int actualCalls=localSession.totalCalls();
                    detail=files.processMedia(id,false,localScan,localSession);
                    int remaining=files.pendingMediaRegions(id);
                    var nextLocal=json.valueToTree(scanSummary(detail)).path("localScanProgress");
                    boolean attemptedMore=localProgress.path("requested").asBoolean() && nextLocal.path("requested").asBoolean()
                            && (nextLocal.path("unattempted").asInt()<localProgress.path("unattempted").asInt()
                                || nextLocal.path("retryableFailures").asInt()<localProgress.path("retryableFailures").asInt());
                    boolean progressed=remaining<pending || attemptedMore || localSession.totalCalls()>actualCalls;
                    pending=remaining;
                    localProgress=nextLocal;
                    if(!progressed) break;
                }
                var summary=json.valueToTree(scanSummary(detail));
                long candidates=detail.get("pendingFormulas") instanceof Number count ? count.longValue() : 0;
                boolean incomplete=pending>0 || summary.path("report").path("partial").asBoolean() || summary.path("report").path("truncated").asBoolean()
                        || candidates>0 || Boolean.TRUE.equals(detail.get("pendingUpgrade")) || Boolean.TRUE.equals(detail.get("noProgress"));
                boolean empty=!"ok".equals(String.valueOf(detail.get("textStatus")));
                item.put("status",empty?"failed":incomplete?"partial":"completed");
                item.put("pendingFormulas",candidates);
                if (localProgress.path("requested").asBoolean()) item.put("localScanProgress",json.convertValue(localProgress,Map.class));
                if (detail.containsKey("scanProgress")) item.put("scanProgress",detail.get("scanProgress"));
                if (Boolean.TRUE.equals(detail.get("pendingUpgrade"))) item.put("pendingUpgrade",true);
                if (detail.get("resumeMessage") instanceof String message) item.put("message",message);
                if (empty) item.put("error","尚无通过质量检查的检索正文，原件和 Markdown 候选已保留");
                synchronized(job) { if (empty) job.failed++; else if (incomplete) job.partial++; }
            } catch (Exception failed) {
                item.put("status","failed"); item.put("error",safe(failed));
                synchronized(job) { job.failed++; }
            }
            synchronized(job) { job.items.add(Map.copyOf(item)); job.done++; job.stage="已处理 "+job.done+" / "+job.total+" 份资料"; }
            persist(job);
        }
        boolean indexed=updateIndex(job);
        synchronized(job) {
            job.status=job.failed>0 || job.partial>0 || !indexed?"partial":"completed";
            job.stage=job.status.equals("completed")?"Markdown、定位与检索已更新":"转换已结束，部分资料或媒体仍需核对";
        }
        persist(job);
    }

    private boolean updateIndex(Job job) {
        synchronized(job) { job.stage="更新分块与索引"; }
        persist(job);
        try {
            Map<String,Object> indexed=vectors.reindexChanged();
            synchronized(job) { job.index=indexed; }
            return true;
        } catch (Exception failed) {
            synchronized(job) { job.index=Map.of("updated",false,"error","向量索引未完成，可在知识库重试索引"); }
            return false;
        }
    }

    private static String safe(Exception failed) {
        String text=failed instanceof IllegalArgumentException || failed instanceof IllegalStateException?failed.getMessage():null;
        if (text==null || text.isBlank()) return "处理失败，原文件保留，可稍后重试";
        return text.replaceAll("[\\r\\n]"," ").substring(0,Math.min(240,text.length()));
    }
    private void fail(Job job,String reason) {
        synchronized(job) {
            job.status="failed"; job.stage="任务未完成"; job.error=reason;
            persist(job);
        }
    }
    private Path directory() { return jobDirectory; }
    private void persist(Job job) {
        synchronized(job) {
            job.updatedAt=System.currentTimeMillis();
            try {
                Path dir=directory(); Files.createDirectories(dir);
                Path temporary=Files.createTempFile(dir,"job-",".tmp");
                try {
                    json.writeValue(temporary.toFile(),job.view());
                    try { Files.move(temporary,dir.resolve(job.id+".json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
                    catch(java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary,dir.resolve(job.id+".json"),StandardCopyOption.REPLACE_EXISTING); }
                } finally { Files.deleteIfExists(temporary); }
            } catch(Exception ignored) { /* Processing results themselves are versioned independently of progress. */ }
        }
    }
    public Map<String,Object> status(String id,String kind) {
        if (id==null || !id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("任务不存在");
        Job live=jobs.get(id);
        if (live!=null && live.kind.equals(kind)) return live.view();
        try {
            Path file=directory().resolve(id+".json");
            if (!Files.isRegularFile(file) || Files.size(file)>4L*1024*1024) throw new IllegalArgumentException("任务不存在");
            Map<String,Object> saved=json.readValue(Files.readAllBytes(file),new com.fasterxml.jackson.core.type.TypeReference<>(){});
            if (!kind.equals(saved.get("kind"))) throw new IllegalArgumentException("任务不存在");
            if (List.of("queued","running").contains(saved.get("status"))) {
                saved.put("status","failed");
                saved.put("error",List.of("structure","extraction").contains(kind)
                        ? "应用已重启，正文抽取中断。已保存的正文和定位保留，可继续抽取剩余页"
                        : "应用已重启，任务未完成；重试会跳过已完成版本");
            }
            return saved;
        } catch(IllegalArgumentException missing) { throw missing; }
        catch(Exception missing) { throw new IllegalArgumentException("任务不存在或记录无法读取"); }
    }
    @PreDestroy public void close() { worker.shutdownNow(); }
}
