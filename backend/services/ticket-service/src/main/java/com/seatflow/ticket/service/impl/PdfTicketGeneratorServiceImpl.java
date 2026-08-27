package com.seatflow.ticket.service.impl;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.seatflow.ticket.model.common.PdfTicketData;
import com.seatflow.ticket.service.PdfTicketGeneratorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

@Slf4j
@Service
public class PdfTicketGeneratorServiceImpl implements PdfTicketGeneratorService {

    private static final float QR_SIZE = 140f;
    private static final int LABEL_COLSPAN = 1;
    private static final int VALUE_COLSPAN = 1;

    @Override
    public byte[] generatePdf(PdfTicketData ticketData) {
        Document document = new Document(PageSize.A4);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, baos);
            document.open();
            // Ensure document is closed even on exception to release resources
            try {

            Font titleFont = new Font(Font.HELVETICA, 20, Font.BOLD, Color.BLACK);
            Font subtitleFont = new Font(Font.HELVETICA, 12, Font.NORMAL, Color.DARK_GRAY);
            Font headerFont = new Font(Font.HELVETICA, 12, Font.BOLD, Color.BLACK);
            Font labelFont = new Font(Font.HELVETICA, 9, Font.BOLD, Color.GRAY);
            Font valueFont = new Font(Font.HELVETICA, 11, Font.NORMAL, Color.BLACK);

            document.add(new Paragraph("SeatFlow Digital Ticket", titleFont));
            document.add(new Paragraph("Ticket Code: " + ticketData.ticketCode(), subtitleFont));
            document.add(new Paragraph("Status: " + ticketData.status(), subtitleFont));
            document.add(new Paragraph(" "));

            PdfPTable eventTable = new PdfPTable(2);
            addRow(eventTable, "Event", ticketData.eventTitle(), labelFont, valueFont);
            addRow(eventTable, "Category", ticketData.eventCategory(), labelFont, valueFont);
            addRow(eventTable, "Date", formatInstant(ticketData.eventDate()), labelFont, valueFont);
            addRow(eventTable, "Venue", ticketData.venueName(), labelFont, valueFont);
            addRow(eventTable, "City", ticketData.venueCity(), labelFont, valueFont);
            document.add(eventTable);
            document.add(new Paragraph(" "));

            PdfPTable seatTable = new PdfPTable(2);
            addRow(seatTable, "Section", ticketData.sectionName(), labelFont, valueFont);
            addRow(seatTable, "Row", ticketData.rowLabel(), labelFont, valueFont);
            addRow(seatTable, "Seat", ticketData.seatNumber() == null ? "" : ticketData.seatNumber().toString(), labelFont, valueFont);
            addRow(seatTable, "Attendee", ticketData.attendeeName(), labelFont, valueFont);
            addRow(seatTable, "Email", ticketData.customerEmail(), labelFont, valueFont);
            document.add(seatTable);
            document.add(new Paragraph(" "));

            PdfPTable fiscalTable = new PdfPTable(2);
            fiscalTable.setHeaderRows(0);
            addRow(fiscalTable, "Net Base Price", formatMoney(ticketData.netAmount(), ticketData.currency()), labelFont, valueFont);
            addRow(fiscalTable, "Tax / VAT Included", formatMoney(ticketData.taxAmount(), ticketData.currency()), labelFont, valueFont);
            addRow(fiscalTable, "Total Paid", formatMoney(ticketData.price(), ticketData.currency()), headerFont, valueFont);
            document.add(new Paragraph("Fiscal Breakdown", headerFont));
            document.add(fiscalTable);
            document.add(new Paragraph(" "));

            Image qr = Image.getInstance(ticketData.qrCodeImagePng());
            qr.scaleToFit(QR_SIZE, QR_SIZE);
            document.add(qr);
            document.add(new Paragraph("Scan this QR code at entry gate", labelFont));
            } finally {
                if (document.isOpen()) {
                    document.close();
                }
            }
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render PDF ticket for code " + ticketData.ticketCode(), e);
        }
    }

    private void addRow(PdfPTable table, String label, String value, Font labelFont, Font valueFont) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, labelFont));
        PdfPCell valueCell = new PdfPCell(new Phrase(value == null ? "" : value, valueFont));
        labelCell.setColspan(LABEL_COLSPAN);
        valueCell.setColspan(VALUE_COLSPAN);
        table.addCell(labelCell);
        table.addCell(valueCell);
    }

    private String formatMoney(BigDecimal amount, String currency) {
        if (amount == null) {
            return "";
        }
        try {
            if (currency != null && !currency.isBlank()) {
                Currency cur = Currency.getInstance(currency.trim().toUpperCase(Locale.ROOT));
                NumberFormat formatter = NumberFormat.getCurrencyInstance(Locale.US);
                formatter.setCurrency(cur);
                return formatter.format(amount);
            }
        } catch (Exception ignored) {
            // Fallback to default US formatting if currency code is invalid
        }
        NumberFormat formatter = NumberFormat.getCurrencyInstance(Locale.US);
        return formatter.format(amount);
    }

    private String formatInstant(java.time.Instant instant) {
        return instant == null ? "" : instant.toString();
    }
}
