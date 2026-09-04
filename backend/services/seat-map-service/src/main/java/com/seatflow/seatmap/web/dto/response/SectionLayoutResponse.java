package com.seatflow.seatmap.web.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Schema(description = "Section layout with complete seat grid")
public record SectionLayoutResponse(
    @Schema(description = "Section UUID") UUID sectionId,
    @Schema(description = "Section name") String name,
    @Schema(description = "Number of rows") Integer rowCount,
    @Schema(description = "Number of columns") Integer colCount,
    @Schema(description = "Seats in this section") List<SeatResponse> seats,
    @Schema(description = "Whether the section is active") Boolean isActive,
    @Schema(description = "Section position X on venue canvas") BigDecimal positionX,
    @Schema(description = "Section position Y on venue canvas") BigDecimal positionY,
    @Schema(description = "Section width") BigDecimal width,
    @Schema(description = "Section height") BigDecimal height,
    @Schema(description = "Section rotation in degrees") BigDecimal rotationDeg,
    @Schema(description = "Section z-index") Integer zIndex,
    @Schema(description = "Optional shape metadata JSON object") Object shapeMetadata
) {
    // Static holder: records cannot inject Spring's configured mapper in the
    // compact constructor, and conversion here is shape-preserving (JsonNode
    // object -> equivalent Map), so mapper configuration drift cannot alter
    // the serialized output. Jackson serializes Map and JsonNode identically.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Logger log = LoggerFactory.getLogger(SectionLayoutResponse.class);

    public SectionLayoutResponse {
        if (shapeMetadata instanceof JsonNode jsonNode) {
            if (jsonNode.isNull()) {
                shapeMetadata = null;
            } else {
                try {
                    shapeMetadata = OBJECT_MAPPER.convertValue(jsonNode, Object.class);
                } catch (IllegalArgumentException e) {
                    log.warn("Failed to convert shapeMetadata JsonNode to Object; "
                            + "keeping original node. sectionId={}", sectionId, e);
                }
            }
        }
    }
}
