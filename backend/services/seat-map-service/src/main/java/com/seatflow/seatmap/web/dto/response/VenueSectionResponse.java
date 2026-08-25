package com.seatflow.seatmap.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Venue section response")
public record VenueSectionResponse(
    @Schema(description = "Section UUID") UUID id,
    @Schema(description = "Section name") String name,
    @Schema(description = "Number of rows") Integer rowCount,
    @Schema(description = "Number of columns (seats per row)") Integer colCount,
    @Schema(description = "Total active seats in this section") Long activeSeatCount
) {}
