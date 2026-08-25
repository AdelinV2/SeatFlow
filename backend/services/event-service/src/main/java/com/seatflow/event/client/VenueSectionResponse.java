package com.seatflow.event.client;

import java.util.List;
import java.util.UUID;

public record VenueSectionResponse(
        UUID sectionId,
        String name,
        Integer rowCount,
        Integer colCount,
        Integer seatCount,
        List<VenueSeatResponse> seats
) {}
