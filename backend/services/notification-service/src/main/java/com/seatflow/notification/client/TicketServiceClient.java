package com.seatflow.notification.client;

import java.util.UUID;

public interface TicketServiceClient {

    byte[] fetchTicketPdf(UUID ticketId);
}
