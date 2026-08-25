package com.seatflow.seatmap.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "Section layout with complete seat grid")
public record SectionLayoutResponse(
    @Schema(description = "Section UUID") UUID sectionId,
    @Schema(description = "Section name") String name,
    @Schema(description = "Number of rows") Integer rowCount,
    @Schema(description = "Number of columns") Integer colCount,
    @Schema(description = "Seats in this section") List<SeatResponse> seats
) {}
