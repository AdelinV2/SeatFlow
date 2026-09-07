package com.seatflow.ai.tool;

import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.context.AiRequestContextFactory;
import com.seatflow.ai.exception.AiToolError;
import com.seatflow.ai.exception.AiToolException;
import com.seatflow.ai.service.SeatAvailabilityService;
import com.seatflow.ai.tool.dto.AvailableSeatsResult;
import com.seatflow.ai.tool.dto.FindBestSeatsRequest;
import com.seatflow.ai.tool.dto.FindBestSeatsResult;
import com.seatflow.ai.tool.dto.GetAvailableSeatsRequest;
import com.seatflow.ai.tool.dto.SeatRankingStrategy;
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
class SeatAvailabilityToolsTest {

    @Mock
    private SeatAvailabilityService seatAvailabilityService;

    @Mock
    private AiRequestContextFactory requestContexts;

    @InjectMocks
    private SeatAvailabilityTools tools;

    @Test
    @DisplayName("exactly the two seat tools are registered, no state-changing tool")
    void registersExactlyTwoSeatTools() {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build()
                .getToolCallbacks();

        Set<String> names = Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrder("getAvailableSeats", "findBestSeats");
    }

    @Test
    @DisplayName("tool methods delegate with the authenticated server-side context")
    void toolsDelegateWithServerContext() {
        AiRequestContext context = new AiRequestContext("token", "corr", "user");
        when(requestContexts.requireAuthenticated()).thenReturn(context);
        UUID sessionId = UUID.randomUUID();
        when(seatAvailabilityService.getAvailableSeats(any(), any()))
                .thenReturn(new AvailableSeatsResult(sessionId, null, "EUR", List.of(), List.of()));
        when(seatAvailabilityService.findBestSeats(any(), any()))
                .thenReturn(new FindBestSeatsResult(sessionId, null, "NO_MATCH", List.of(),
                        List.of("hint"), List.of()));

        tools.getAvailableSeats(new GetAvailableSeatsRequest(sessionId.toString(), null, null,
                null, null, null));
        tools.findBestSeats(new FindBestSeatsRequest(sessionId.toString(), 2, null, null, null,
                null, null, SeatRankingStrategy.BEST_VALUE));

        verify(seatAvailabilityService).getAvailableSeats(
                any(GetAvailableSeatsRequest.class), any(AiRequestContext.class));
        verify(seatAvailabilityService).findBestSeats(
                any(FindBestSeatsRequest.class), any(AiRequestContext.class));
    }

    @Test
    @DisplayName("tool calls without authentication fail instead of calling the service")
    void toolsRejectAnonymousCallers() {
        when(requestContexts.requireAuthenticated()).thenThrow(
                new AiToolException(AiToolError.UNAUTHENTICATED, "Authentication is required."));

        assertThatThrownBy(() -> tools.getAvailableSeats(
                new GetAvailableSeatsRequest(UUID.randomUUID().toString(), null, null, null, null, null)))
                .isInstanceOf(AiToolException.class);
        assertThatThrownBy(() -> tools.findBestSeats(
                new FindBestSeatsRequest(UUID.randomUUID().toString(), 2, null, null, null, null,
                        null, SeatRankingStrategy.BEST_VALUE)))
                .isInstanceOf(AiToolException.class);
    }

    @Test
    @DisplayName("tool layer has no dependency on domain repositories, entities, or JPA")
    void toolLayerHasNoForbiddenDependencies() {
        List<Class<?>> owned = List.of(
                SeatAvailabilityTools.class,
                com.seatflow.ai.service.impl.SeatAvailabilityServiceImpl.class,
                com.seatflow.ai.service.seat.SeatCandidateAssembler.class,
                com.seatflow.ai.service.seat.impl.SeatRankingServiceImpl.class,
                com.seatflow.ai.client.impl.ReservationAvailabilityClientImpl.class,
                AvailableSeatsResult.class,
                FindBestSeatsResult.class,
                GetAvailableSeatsRequest.class,
                FindBestSeatsRequest.class);

        Set<String> referenced = owned.stream()
                .flatMap(SeatAvailabilityToolsTest::referencedTypeNames)
                .collect(Collectors.toSet());

        assertThat(referenced.stream()
                .filter(name -> name.startsWith("com.seatflow.event.")
                        || name.startsWith("com.seatflow.reservation.")
                        || name.startsWith("com.seatflow.seatmap.")
                        || name.startsWith("jakarta.persistence.")
                        || name.startsWith("javax.persistence.")
                        || name.startsWith("org.springframework.data.")
                        || name.startsWith("org.springframework.orm."))
                .toList()).isEmpty();
    }

    @Test
    @DisplayName("tool results expose only customer-safe fields, no tokens or internals")
    void toolResultsOmitInternalFields() {
        Set<String> seatFields = componentNames(
                com.seatflow.ai.tool.dto.AvailableSeatItem.class);
        assertThat(seatFields).doesNotContain(
                "bearerToken", "token", "version", "createdAt", "updatedAt");
        assertThat(seatFields).contains(
                "seatId", "sectionId", "rowLabel", "seatNumber", "priceMinor", "currency");
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
