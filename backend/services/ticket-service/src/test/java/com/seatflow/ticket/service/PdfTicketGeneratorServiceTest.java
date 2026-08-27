package com.seatflow.ticket.service;

import com.seatflow.ticket.model.common.PdfTicketData;
import com.seatflow.ticket.service.impl.PdfTicketGeneratorServiceImpl;
import com.seatflow.ticket.service.impl.QrCodeGeneratorServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PdfTicketGeneratorServiceTest {

    private final QrCodeGeneratorService qrCodeGeneratorService = new QrCodeGeneratorServiceImpl();
    private final PdfTicketGeneratorService pdfTicketGeneratorService = new PdfTicketGeneratorServiceImpl();

    @Test
    void generatesValidPdfWithFiscalBreakdown() {
        byte[] qrBytes = qrCodeGeneratorService.generateQrCodePng("TICKET-PAYLOAD", 140, 140);

        PdfTicketData data = new PdfTicketData(
                UUID.randomUUID(),
                "SF-TKT-0001",
                "VALID",
                "Summer Music Festival",
                "CONCERT",
                Instant.parse("2026-08-01T18:00:00Z"),
                "Sky Arena",
                "Berlin",
                "Floor A",
                "R",
                12,
                "Jane Doe",
                "jane.doe@example.com",
                new BigDecimal("100.00"),
                new BigDecimal("19.00"),
                new BigDecimal("81.00"),
                "USD",
                qrBytes
        );

        byte[] pdf = pdfTicketGeneratorService.generatePdf(data);

        assertThat(pdf).isNotNull().isNotEmpty();
        assertThat(pdf.length).isGreaterThan(1000);
        assertThat(new String(pdf, 0, Math.min(pdf.length, 5))).startsWith("%PDF-");
    }
}
