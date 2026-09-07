package com.seatflow.ai.tool;

import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.context.AiRequestContextFactory;
import com.seatflow.ai.exception.AiToolError;
import com.seatflow.ai.exception.AiToolException;
import com.seatflow.ai.service.EventToolService;
import com.seatflow.ai.tool.dto.EventSessionsToolResult;
import com.seatflow.ai.tool.dto.EventToolResult;
import com.seatflow.ai.tool.dto.GetEventRequest;
import com.seatflow.ai.tool.dto.GetEventSessionsRequest;
import com.seatflow.ai.tool.dto.SearchEventsRequest;
import com.seatflow.ai.tool.dto.SearchEventsResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventDiscoveryToolsTest {

    @Mock
    private EventToolService eventToolService;

    @Mock
    private AiRequestContextFactory requestContexts;

    @InjectMocks
    private EventDiscoveryTools tools;

    @Test
    @DisplayName("exactly the three read-only tools are registered, no state-changing tool")
    void registersExactlyThreeReadOnlyTools() {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build()
                .getToolCallbacks();

        Set<String> names = Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrder("searchEvents", "getEvent", "getEventSessions");
    }

    @Test
    @DisplayName("tool methods delegate with the authenticated server-side context")
    void toolsDelegateWithServerContext() {
        AiRequestContext context = new AiRequestContext("token", "corr", "user");
        when(requestContexts.requireAuthenticated()).thenReturn(context);
        UUID eventId = UUID.randomUUID();
        when(eventToolService.searchEvents(any(), any())).thenReturn(new SearchEventsResult(List.of()));
        when(eventToolService.getEvent(any(), any())).thenReturn(
                new EventToolResult(eventId, "T", null, "THEATRE", null, "PUBLISHED"));
        when(eventToolService.getEventSessions(any(), any()))
                .thenReturn(new EventSessionsToolResult(eventId, List.of()));

        tools.searchEvents(new SearchEventsRequest(null, null, null, null, null));
        tools.getEvent(new GetEventRequest(eventId.toString()));
        tools.getEventSessions(new GetEventSessionsRequest(eventId.toString(), null, null, null));

        verify(eventToolService).searchEvents(any(SearchEventsRequest.class), any(AiRequestContext.class));
        verify(eventToolService).getEvent(any(GetEventRequest.class), any(AiRequestContext.class));
        verify(eventToolService).getEventSessions(
                any(GetEventSessionsRequest.class), any(AiRequestContext.class));
    }

    @Test
    @DisplayName("tool calls without authentication fail instead of calling the service")
    void toolsRejectAnonymousCallers() {
        when(requestContexts.requireAuthenticated()).thenThrow(
                new AiToolException(AiToolError.UNAUTHENTICATED, "Authentication is required."));

        assertThatThrownBy(() -> tools.searchEvents(new SearchEventsRequest(null, null, null, null, null)))
                .isInstanceOf(AiToolException.class);
    }

    @Test
    @DisplayName("tool layer has no dependency on Event Service internals, JPA, or raw entities")
    void toolLayerHasNoForbiddenDependencies() {
        List<Class<?>> owned = List.of(
                EventDiscoveryTools.class,
                com.seatflow.ai.service.impl.EventToolServiceImpl.class,
                com.seatflow.ai.client.impl.EventServiceClientImpl.class,
                EventToolResult.class,
                SearchEventsResult.class,
                EventSessionsToolResult.class,
                SearchEventsRequest.class,
                GetEventRequest.class,
                GetEventSessionsRequest.class);

        Set<String> referenced = owned.stream()
                .flatMap(EventDiscoveryToolsTest::referencedTypeNames)
                .collect(Collectors.toSet());

        assertThat(referenced.stream()
                .filter(name -> name.startsWith("com.seatflow.event.")
                        || name.startsWith("jakarta.persistence.")
                        || name.startsWith("javax.persistence.")
                        || name.startsWith("org.springframework.data.")
                        || name.startsWith("org.springframework.orm."))
                .toList()).isEmpty();
    }

    @Test
    @DisplayName("tool results expose only customer-safe fields, no admin or audit internals")
    void toolResultsOmitInternalFields() {
        Set<String> eventFields = componentNames(EventToolResult.class);
        assertThat(eventFields).containsExactlyInAnyOrder(
                "eventId", "title", "descriptionSummary", "category", "venueId", "status");

        Set<String> searchFields = componentNames(
                com.seatflow.ai.tool.dto.EventSearchItem.class);
        assertThat(searchFields).doesNotContain(
                "bannerUrl", "version", "createdAt", "updatedAt", "description");

        Set<String> sessionFields = componentNames(
                com.seatflow.ai.tool.dto.SessionToolItem.class);
        assertThat(sessionFields).doesNotContain("createdAt", "updatedAt", "version");
    }

    private static Set<String> componentNames(Class<?> recordClass) {
        return Arrays.stream(recordClass.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }

    private static Stream<String> referencedTypeNames(Class<?> clazz) {
        Stream<String> fields = Arrays.stream(clazz.getDeclaredFields())
                .map(Field::getType)
                .map(Class::getName);
        Stream<String> methods = Arrays.stream(clazz.getDeclaredMethods())
                .flatMap(method -> Stream.concat(
                        Arrays.stream(method.getParameterTypes()).map(Class::getName),
                        Stream.of(method.getReturnType().getName())));
        Stream<String> hierarchy = Stream.concat(
                Stream.ofNullable(clazz.getSuperclass()).map(Class::getName),
                Arrays.stream(clazz.getInterfaces()).map(Class::getName));
        return Stream.concat(Stream.concat(fields, methods), hierarchy);
    }
}
