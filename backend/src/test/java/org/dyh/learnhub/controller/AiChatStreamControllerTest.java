package org.dyh.learnhub.controller;

import org.dyh.learnhub.ai.AgentService;
import org.dyh.learnhub.dto.AiChatRequest;
import org.dyh.learnhub.service.AgentSessionService;
import org.dyh.learnhub.service.SkillService;
import org.dyh.learnhub.vo.AiChatVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiChatStreamControllerTest {
    private final AgentService agent = mock(AgentService.class);
    private final AiController controller = new AiController(agent, mock(AgentSessionService.class), mock(SkillService.class));
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

    @AfterEach void shutdown() { controller.shutdownStreamPool(); }

    @Test void researchChatHasTimeForSeveralSourceBatchesWithoutWaitingInTheTest() {
        when(agent.chat(any(AiChatRequest.class), any(AgentService.ChatListener.class))).thenReturn(new AiChatVO());
        AiChatRequest question = new AiChatRequest(); question.setMessage("合成大资料问题");
        var emitter = controller.chatStream(question, new MockHttpServletResponse());
        assertEquals(900_000L, AgentService.CHAT_ROUND_BUDGET_MS);
        assertEquals(960_000L, emitter.getTimeout());
    }

    @Test void sendsProgressBeforeTheFinalAnswerAndDisablesProxyBuffering() throws Exception {
        CountDownLatch progressSent = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(agent.chat(any(AiChatRequest.class), any(AgentService.ChatListener.class))).thenAnswer(invocation -> {
            AgentService.ChatListener listener = invocation.getArgument(1);
            listener.onSession("synthetic-session");
            listener.onProgress(Map.of("id", "step-1", "kind", "phase", "status", "running", "title", "检索相关知识"));
            progressSent.countDown();
            assertTrue(finish.await(5, TimeUnit.SECONDS));
            listener.onProgress(Map.of("id", "step-1", "kind", "phase", "status", "completed", "title", "检索相关知识"));
            AiChatVO answer = new AiChatVO();
            answer.setSessionId("synthetic-session"); answer.setReply("合成答案");
            return answer;
        });
        MvcResult started = mvc.perform(post("/api/ai/chat-stream").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"合成问题\"}"))
                .andExpect(request().asyncStarted()).andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(header().string("Cache-Control", "no-cache, no-transform")).andReturn();
        try {
            assertTrue(progressSent.await(3, TimeUnit.SECONDS));
            String current = started.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(current.contains("event:session"), current);
            assertTrue(current.contains("event:progress"), current);
            assertTrue(current.contains("running"), current);
            assertFalse(current.contains("event:result"), current);
        } finally { finish.countDown(); }
        started.getAsyncResult(5_000);
        String complete = mvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(complete.contains("completed"), complete);
        assertTrue(complete.contains("event:result"), complete);
        assertTrue(complete.contains("合成答案"), complete);
    }

    @Test void serviceErrorIsAnSseErrorAndDoesNotExposeRawProviderDiagnostics() throws Exception {
        when(agent.chat(any(AiChatRequest.class), any(AgentService.ChatListener.class)))
                .thenThrow(new IllegalStateException("synthetic-secret-provider-diagnostic"));
        MvcResult started = mvc.perform(post("/api/ai/chat-stream").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"合成问题\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        started.getAsyncResult(5_000);
        String text = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(text.contains("event:error"), text);
        assertTrue(text.contains("待确认改动"), text);
        assertFalse(text.contains("synthetic-secret-provider-diagnostic"));
        assertFalse(text.contains("event:result"));
    }

    @Test void ordinaryChatEndpointRetainsTheExistingJsonContract() throws Exception {
        AiChatVO answer = new AiChatVO(); answer.setReply("合成普通回答");
        when(agent.chat(any(AiChatRequest.class))).thenReturn(answer);
        mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"合成问题\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reply").value("合成普通回答"));
        verify(agent, never()).chat(any(AiChatRequest.class), any(AgentService.ChatListener.class));
    }

    @Test void busySessionExplainsThatTheOriginalTurnContinues() throws Exception {
        when(agent.chat(any(AiChatRequest.class), any(AgentService.ChatListener.class)))
                .thenThrow(new AgentService.ChatBusyException());
        MvcResult started = mvc.perform(post("/api/ai/chat-stream").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"合成问题\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        started.getAsyncResult(5_000);
        String text = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(text.contains("当前会话仍在处理中"), text);
        assertFalse(text.contains("event:result"));
    }
}
