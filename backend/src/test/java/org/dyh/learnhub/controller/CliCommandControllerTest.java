package org.dyh.learnhub.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CliCommandControllerTest {
    private static RequestMappingHandlerMapping mappings;
    private static JsonNode commands;

    @BeforeAll
    static void discoverRegisteredApplicationRoutes() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var controllers = new ArrayList<>();
        for (var bean : scanner.findCandidateComponents(CliCommandController.class.getPackageName())) {
            controllers.add(mock(Class.forName(bean.getBeanClassName())));
        }
        var application = MockMvcBuilders.standaloneSetup(controllers.toArray()).build();
        mappings = application.getDispatcherServlet().getWebApplicationContext().getBean(RequestMappingHandlerMapping.class);
        var mapper = new ObjectMapper();
        var catalog = MockMvcBuilders.standaloneSetup(new CliCommandController(mappings, mapper)).build();
        byte[] response = catalog.perform(get("/api/cli/commands"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.version").value(1))
                .andReturn().getResponse().getContentAsByteArray();
        commands = mapper.readTree(response).path("data").path("commands");
    }

    @Test
    void listsEveryRegisteredBusinessRouteOnceWithUniqueCommandIds() {
        Set<String> expected = new HashSet<>();
        mappings.getHandlerMethods().forEach((mapping, handler) -> {
            if (handler.getBeanType() == CliCommandController.class) return;
            for (String path : mapping.getPatternValues()) {
                for (RequestMethod method : mapping.getMethodsCondition().getMethods()) {
                    expected.add(method + " " + path);
                }
            }
        });
        Set<String> actual = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode command : commands) {
            String route = command.path("method").asText() + " " + command.path("path").asText();
            assertTrue(actual.add(route), "Duplicate route: " + route);
            assertTrue(ids.add(command.path("id").asText()), "Duplicate ID: " + command.path("id").asText());
        }
        assertEquals(expected, actual);
        assertTrue(actual.size() > 100, "Exercise the complete application mapping table, actual: " + actual.size());
    }

    @Test
    void describesCommandInputsAndOutputProtocolsFromActualControllers() {
        var page = command("note.page");
        var size = parameter(page, "size");
        assertEquals("query", size.path("in").asText());
        assertEquals("integer", size.path("type").asText());
        assertEquals("10", size.path("default").asText());
        assertFalse(size.path("required").asBoolean());
        assertFalse(page.has("body"));

        var save = command("note.save").path("body");
        assertTrue(save.path("required").asBoolean());
        assertEquals("title", save.at("/schema/required/0").asText());
        assertEquals("integer", save.at("/schema/properties/tagIds/items/type").asText());
        assertFalse(save.at("/schema/properties").has("folderIdProvided"));

        var edit = command("note.edit-content");
        assertEquals("path", parameter(edit, "id").path("in").asText());
        assertEquals("string", edit.at("/body/schema/properties/expected_hash/type").asText());
        assertEquals(50, edit.at("/body/schema/properties/operations/maxItems").asInt());
        assertEquals("integer", edit.at("/body/schema/properties/operations/items/properties/min_level/type").asText());
        assertEquals("replace", edit.at("/body/schema/properties/operations/items/properties/action/enum/0").asText());

        var upload = command("file.upload");
        assertTrue(parameter(upload, "file").path("required").asBoolean());
        assertEquals("file", parameter(upload, "file").path("in").asText());
        assertEquals("form", parameter(upload, "categoryId").path("in").asText());
        assertEquals("binary", command("file.download").path("response").asText());

        var asset = command("document-extraction.asset");
        assertEquals("/api/files/{id}/extracted-assets/{hash}.png", asset.path("path").asText());
        assertEquals("binary", asset.path("response").asText());
        assertFalse(command("document-extraction.process-media").at("/body/required").asBoolean(true));

        var stream = command("ai.chat-stream");
        assertEquals("sse", stream.path("response").asText());
        assertEquals(0, stream.path("parameters").size(), "Servlet response is supplied by Spring");
        assertTrue(stream.path("body").path("required").asBoolean());

        assertEquals("string", command("settings.update").at("/body/schema/properties/model/type").asText());
        assertEquals("query", parameter(command("settings.effective"), "keys").path("in").asText());
        assertEquals("boolean", command("knowledge-maintenance.update").at("/body/schema/properties/enabled/type").asText());
        assertEquals("string", command("code.import-folder").at("/body/schema/properties/files/items/properties/path/type").asText());
    }

    private static JsonNode command(String id) {
        for (JsonNode command : commands) {
            if (id.equals(command.path("id").asText())) return command;
        }
        return fail("Missing command: " + id);
    }

    private static JsonNode parameter(JsonNode command, String name) {
        for (JsonNode parameter : command.path("parameters")) {
            if (name.equals(parameter.path("name").asText())) return parameter;
        }
        return fail("Missing parameter: " + name);
    }
}
