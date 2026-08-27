package com.seatflow.ticket.service;

import com.seatflow.ticket.model.common.PdfTicketData;

public interface PdfTicketGeneratorService {

    /**
     * Renders a professional, downloadable PDF ticket containing full fiscal breakdown and QR code.
     */
    byte[] generatePdf(PdfTicketData ticketData);
}
