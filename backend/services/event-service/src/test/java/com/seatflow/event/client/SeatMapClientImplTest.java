package com.seatflow.event.client;

import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.event.client.impl.SeatMapClientImpl;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SeatMapClientImplTest {

    private static final UUID VENUE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SECTION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SEAT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ELEMENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private MockRestServiceServer mockServer;
    private SeatMapClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        SeatMapClientImpl impl = new SeatMapClientImpl(
                builder, CircuitBreakerRegistry.ofDefaults(), "seat-map-service");
        // SeatMapClientImpl applies its own request factory with timeouts at build time,
        // which would replace the mock factory; inject a mock-bound client instead.
        injectRestClient(impl, builder.baseUrl("http://seat-map-service").build());
        client = impl;
    }

    @Test
    @DisplayName("Advanced payload maps geometry and elements while preserving IDs and configured count")
    void mapsAdvancedPayloadPreservingIdsAndCounts() {
        mockServer.expect(requestTo("http://seat-map-service/api/venues/" + VENUE_ID + "/layout"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "venueId": "11111111-1111-1111-1111-111111111111",
                          "name": "Grand Hall",
                          "capacity": 500,
                          "totalConfiguredSeats": 5,
                          "layoutVersion": 7,
                          "sections": [
                            {
                              "sectionId": "22222222-2222-2222-2222-222222222222",
                              "name": "A",
                              "rowCount": 5,
                              "colCount": 10,
                              "isActive": true,
                              "positionX": 10.5,
                              "positionY": 20.25,
                              "width": 440,
                              "height": 220,
                              "rotationDeg": 15.5,
                              "zIndex": 3,
                              "shapeMetadata": {"kind": "rect"},
                              "seats": [
                                {
                                  "seatId": "33333333-3333-3333-3333-333333333333",
                                  "rowLabel": "R1",
                                  "seatNumber": 7,
                                  "gridX": 1,
                                  "gridY": 2,
                                  "isActive": true,
                                  "positionX": 44.5,
                                  "positionY": 88.25
                                }
                              ]
                            }
                          ],
                          "elements": [
                            {
                              "elementId": "44444444-4444-4444-4444-444444444444",
                              "type": "HOLOGRAM",
                              "label": "Future prop",
                              "geometry": {"x": 1.5, "y": 2.5, "width": 100, "height": 50, "rotationDeg": 90},
                              "zIndex": 1
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        SeatMapVenueLayout layout = client.getVenueLayout(VENUE_ID);

        mockServer.verify();
        assertThat(layout.venueId()).isEqualTo(VENUE_ID);
        assertThat(layout.name()).isEqualTo("Grand Hall");
        assertThat(layout.capacity()).isEqualTo(500);
        assertThat(layout.layoutVersion()).isEqualTo(7L);
        assertThat(layout.totalConfiguredSeats()).isEqualTo(5L);

        assertThat(layout.sections()).hasSize(1);
        SeatMapVenueSection section = layout.sections().get(0);
        assertThat(section.sectionId()).isEqualTo(SECTION_ID);
        assertThat(section.name()).isEqualTo("A");
        assertThat(section.rowCount()).isEqualTo(5);
        assertThat(section.colCount()).isEqualTo(10);
        assertThat(section.isActive()).isTrue();
        assertThat(section.positionX()).isEqualByComparingTo("10.5");
        assertThat(section.positionY()).isEqualByComparingTo("20.25");
        assertThat(section.width()).isEqualByComparingTo("440");
        assertThat(section.height()).isEqualByComparingTo("220");
        assertThat(section.rotationDeg()).isEqualByComparingTo("15.5");
        assertThat(section.zIndex()).isEqualTo(3);
        @SuppressWarnings("unchecked")
        Map<String, Object> shape = (Map<String, Object>) section.shapeMetadata();
        assertThat(shape).containsEntry("kind", "rect");

        assertThat(section.seats()).hasSize(1);
        SeatMapVenueSeat seat = section.seats().get(0);
        assertThat(seat.seatId()).isEqualTo(SEAT_ID);
        assertThat(seat.rowLabel()).isEqualTo("R1");
        assertThat(seat.seatNumber()).isEqualTo(7);
        assertThat(seat.gridX()).isEqualTo(1);
        assertThat(seat.gridY()).isEqualTo(2);
        assertThat(seat.isActive()).isTrue();
        assertThat(seat.positionX()).isEqualByComparingTo("44.5");
        assertThat(seat.positionY()).isEqualByComparingTo("88.25");

        assertThat(layout.elements()).hasSize(1);
        SeatMapVenueLayout.LayoutElement element = layout.elements().get(0);
        assertThat(element.elementId()).isEqualTo(ELEMENT_ID);
        assertThat(element.type()).isEqualTo("HOLOGRAM");
        assertThat(element.label()).isEqualTo("Future prop");
        assertThat(element.zIndex()).isEqualTo(1);
        assertThat(element.geometry().x()).isEqualByComparingTo("1.5");
        assertThat(element.geometry().y()).isEqualByComparingTo("2.5");
        assertThat(element.geometry().width()).isEqualByComparingTo("100");
        assertThat(element.geometry().height()).isEqualByComparingTo("50");
        assertThat(element.geometry().rotationDeg()).isEqualByComparingTo("90");
    }

    @Test
    @DisplayName("Legacy grid-only payload derives deterministic 44-unit geometry")
    void mapsLegacyPayloadWith44UnitFallback() {
        mockServer.expect(requestTo("http://seat-map-service/api/venues/" + VENUE_ID + "/layout"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "venueId": "11111111-1111-1111-1111-111111111111",
                          "name": "Grand Hall",
                          "capacity": 500,
                          "sections": [
                            {
                              "sectionId": "22222222-2222-2222-2222-222222222222",
                              "name": "A",
                              "rowCount": 5,
                              "colCount": 10,
                              "seats": [
                                {
                                  "seatId": "33333333-3333-3333-3333-333333333333",
                                  "rowLabel": "R1",
                                  "seatNumber": 1,
                                  "gridX": 2,
                                  "gridY": 3,
                                  "isActive": true
                                }
                              ]
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        SeatMapVenueLayout layout = client.getVenueLayout(VENUE_ID);

        mockServer.verify();
        assertThat(layout.venueId()).isEqualTo(VENUE_ID);
        assertThat(layout.name()).isEqualTo("Grand Hall");
        assertThat(layout.capacity()).isEqualTo(500);
        assertThat(layout.layoutVersion()).isEqualTo(0L);
        assertThat(layout.elements()).isEmpty();

        SeatMapVenueSection section = layout.sections().get(0);
        assertThat(section.sectionId()).isEqualTo(SECTION_ID);
        assertThat(section.name()).isEqualTo("A");
        assertThat(section.rowCount()).isEqualTo(5);
        assertThat(section.colCount()).isEqualTo(10);
        assertThat(section.isActive()).isTrue();
        assertThat(section.positionX()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(section.positionY()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(section.width()).isEqualByComparingTo("440");
        assertThat(section.height()).isEqualByComparingTo("220");
        assertThat(section.rotationDeg()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(section.zIndex()).isZero();

        SeatMapVenueSeat seat = section.seats().get(0);
        assertThat(seat.seatId()).isEqualTo(SEAT_ID);
        assertThat(seat.rowLabel()).isEqualTo("R1");
        assertThat(seat.seatNumber()).isEqualTo(1);
        assertThat(seat.gridX()).isEqualTo(2);
        assertThat(seat.gridY()).isEqualTo(3);
        assertThat(seat.isActive()).isTrue();
        assertThat(seat.positionX()).isEqualByComparingTo("88");
        assertThat(seat.positionY()).isEqualByComparingTo("132");
    }

    @Test
    @DisplayName("Null elements and seats map to empty lists while preserving the source count")
    void mapsNullListsToEmpty() {
        mockServer.expect(requestTo("http://seat-map-service/api/venues/" + VENUE_ID + "/layout"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "venueId": "11111111-1111-1111-1111-111111111111",
                          "name": "Grand Hall",
                          "capacity": 500,
                          "totalConfiguredSeats": 9,
                          "layoutVersion": 2,
                          "sections": [
                            {
                              "sectionId": "22222222-2222-2222-2222-222222222222",
                              "name": "A",
                              "rowCount": 5,
                              "colCount": 10,
                              "isActive": false,
                              "seats": null
                            }
                          ],
                          "elements": null
                        }
                        """, MediaType.APPLICATION_JSON));

        SeatMapVenueLayout layout = client.getVenueLayout(VENUE_ID);

        mockServer.verify();
        assertThat(layout.totalConfiguredSeats()).isEqualTo(9L);
        assertThat(layout.elements()).isEmpty();
        assertThat(layout.sections()).hasSize(1);
        assertThat(layout.sections().get(0).seats()).isEmpty();
        assertThat(layout.sections().get(0).isActive()).isFalse();
    }

    @Test
    @DisplayName("Venue layout 404 maps to ResourceNotFoundException")
    void throwsResourceNotFoundOn404() {
        mockServer.expect(requestTo("http://seat-map-service/api/venues/" + VENUE_ID + "/layout"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getVenueLayout(VENUE_ID))
                .isInstanceOf(ResourceNotFoundException.class);

        mockServer.verify();
    }

    @Test
    @DisplayName("Venue layout 500 maps to SeatMapClientUnavailableException")
    void throwsUnavailableOn500() {
        mockServer.expect(requestTo("http://seat-map-service/api/venues/" + VENUE_ID + "/layout"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.getVenueLayout(VENUE_ID))
                .isInstanceOf(SeatMapClientUnavailableException.class);

        mockServer.verify();
    }

    private static void injectRestClient(SeatMapClientImpl impl, RestClient restClient) {
        try {
            Field field = SeatMapClientImpl.class.getDeclaredField("restClient");
            field.setAccessible(true);
            field.set(impl, restClient);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to inject mock RestClient", e);
        }
    }
}
