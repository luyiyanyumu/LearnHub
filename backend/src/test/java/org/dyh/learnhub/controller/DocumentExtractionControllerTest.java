package org.dyh.learnhub.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dyh.learnhub.service.*;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentExtractionControllerTest {
    @Test void markdownReadExportAndPublicationForwardTheExactSelectedRevision() {
        var files=mock(FileStorageService.class);
        var controller=new DocumentExtractionController(files,mock(DocumentOcrClient.class),
                mock(DocumentVisionClient.class),mock(DocumentProcessingJobs.class),mock(DocumentStructureClient.class));
        when(files.markdownOf(8L,"candidate","source","artifact")).thenReturn(Map.of("selectedVersion","candidate"));
        when(files.markdownBytes(8L,"candidate","source","artifact")).thenReturn(new byte[]{1});
        when(files.markdownBundle(8L,"published","source","artifact")).thenReturn(new byte[]{2});
        controller.markdown(8L,"candidate","source","artifact");
        assertArrayEquals(new byte[]{1},controller.markdownDownload(8L,"candidate","source","artifact").getBody());
        assertArrayEquals(new byte[]{2},controller.bundle(8L,"published","source","artifact").getBody());
        controller.publishScan(8L,new DocumentExtractionController.ScanPublication("source","artifact"));
        verify(files).markdownOf(8L,"candidate","source","artifact");
        verify(files).markdownBytes(8L,"candidate","source","artifact");
        verify(files).markdownBundle(8L,"published","source","artifact");
        verify(files).publishScan(8L,"source","artifact");
        verifyNoMoreInteractions(files);
    }

    @Test void generatedProseReplacementRequiresAnExplicitForceOptionOnSingleFileExtraction() throws Exception {
        var jobs=mock(DocumentProcessingJobs.class);
        var controller=new DocumentExtractionController(mock(FileStorageService.class),mock(DocumentOcrClient.class),
                mock(DocumentVisionClient.class),jobs,mock(DocumentStructureClient.class));
        var json=new ObjectMapper();
        var ordinary=json.readValue("{\"resume\":true}",DocumentExtractionController.ExtractionRequest.class);
        assertFalse(ordinary.force());
        controller.startExtraction(8L,ordinary);
        controller.startExtraction(8L,null);
        controller.startExtraction(8L,json.readValue("{\"resume\":true,\"force\":true}",DocumentExtractionController.ExtractionRequest.class));
        verify(jobs).startExtraction(8L,true);
        verify(jobs).startExtraction(8L,false);
        verify(jobs).startExtraction(8L,true,true);
    }

    @Test void localScanIsOptionalAndNeverTurnsOnExternalForce() throws Exception {
        var jobs=mock(DocumentProcessingJobs.class);
        var controller=new DocumentExtractionController(mock(FileStorageService.class),mock(DocumentOcrClient.class),
                mock(DocumentVisionClient.class),jobs,mock(DocumentStructureClient.class));
        when(jobs.startMedia(8L,false)).thenReturn(Map.of("id","legacy"));
        when(jobs.startMedia(8L,true)).thenReturn(Map.of("id","external"));
        when(jobs.startMedia(8L,false,true)).thenReturn(Map.of("id","local"));
        var json=new ObjectMapper();
        var legacy=json.readValue("{\"force\":false}",DocumentExtractionController.MediaRequest.class);
        assertFalse(legacy.localScan());
        controller.processMedia(8L,legacy);
        controller.processMedia(8L,null);
        controller.processMedia(8L,new DocumentExtractionController.MediaRequest(true));
        controller.processMedia(8L,json.readValue("{\"force\":false,\"localScan\":true}",DocumentExtractionController.MediaRequest.class));
        verify(jobs,times(2)).startMedia(8L,false);
        verify(jobs).startMedia(8L,true);
        verify(jobs).startMedia(8L,false,true);
        verify(jobs,never()).startMedia(8L,true,true);
    }
}
