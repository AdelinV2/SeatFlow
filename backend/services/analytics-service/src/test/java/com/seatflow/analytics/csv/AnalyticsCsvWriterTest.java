package com.seatflow.analytics.csv;

import com.seatflow.analytics.web.csv.AnalyticsCsvWriter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-P14-006 §6.10: CSV escaping and spreadsheet-formula protection.
 */
class AnalyticsCsvWriterTest {

    @Test
    void shouldEmitExactStableHeaderOrder() {
        assertThat(AnalyticsCsvWriter.headerLine()).isEqualTo(String.join(",", List.of(
                "row_type",
                "metric_date",
                "event_id",
                "event_session_id",
                "event_title",
                "session_label",
                "currency",
                "reservations_created",
                "reservations_confirmed",
                "reservations_expired",
                "operational_payments_succeeded",
                "payments_with_failure",
                "operational_refunds_completed",
                "tickets_issued",
                "tickets_revoked",
                "tickets_scanned",
                "financial_payments_succeeded",
                "financial_refunds_completed",
                "gross_revenue_minor",
                "refunded_revenue_minor",
                "net_revenue_minor",
                "stripe_test_mode",
                "last_projected_event_at")));
    }

    @Test
    void shouldRenderNullAsEmptyCell() {
        assertThat(AnalyticsCsvWriter.textCell(null)).isEmpty();
        assertThat(AnalyticsCsvWriter.plainCell(null)).isEmpty();
    }

    @Test
    void shouldNeutralizeFormulaLeadingTextSnapshots() {
        assertThat(AnalyticsCsvWriter.textCell("=HYPERLINK(\"https://example.invalid\")"))
                .isEqualTo("\"'=HYPERLINK(\"\"https://example.invalid\"\")\"");
        assertThat(AnalyticsCsvWriter.textCell("+SUM(1,1)"))
                .isEqualTo("\"'+SUM(1,1)\"");
        assertThat(AnalyticsCsvWriter.textCell("@cmd")).isEqualTo("'@cmd");
        assertThat(AnalyticsCsvWriter.textCell("-1+2")).isEqualTo("'-1+2");
    }

    @Test
    void shouldGuardFormulaAfterLeadingWhitespace() {
        assertThat(AnalyticsCsvWriter.textCell("  =cmd")).isEqualTo("'  =cmd");
        assertThat(AnalyticsCsvWriter.textCell("\t+cmd")).isEqualTo("'\t+cmd");
    }

    @Test
    void shouldEscapeCommaQuoteAndNewline() {
        assertThat(AnalyticsCsvWriter.textCell("Normal,title")).isEqualTo("\"Normal,title\"");
        assertThat(AnalyticsCsvWriter.textCell("Title \"quoted\""))
                .isEqualTo("\"Title \"\"quoted\"\"\"");
        assertThat(AnalyticsCsvWriter.textCell("multiline\nname"))
                .isEqualTo("\"multiline\nname\"");
        assertThat(AnalyticsCsvWriter.textCell("carriage\rreturn"))
                .isEqualTo("\"carriage\rreturn\"");
    }

    @Test
    void shouldLeaveBenignTextUntouched() {
        assertThat(AnalyticsCsvWriter.textCell("Hamlet")).isEqualTo("Hamlet");
        assertThat(AnalyticsCsvWriter.textCell("Evening show")).isEqualTo("Evening show");
        assertThat(AnalyticsCsvWriter.textCell("")).isEmpty();
        assertThat(AnalyticsCsvWriter.textCell("   ")).isEqualTo("   ");
    }

    @Test
    void shouldNotApplyFormulaGuardToTypedServerValues() {
        // Typed UUID/date/numeric/currency values are emitted from typed server values and
        // can never be attacker text; only RFC-4180 quoting applies.
        assertThat(AnalyticsCsvWriter.plainCell("RON")).isEqualTo("RON");
        assertThat(AnalyticsCsvWriter.plainCell("2026-09-06")).isEqualTo("2026-09-06");
        assertThat(AnalyticsCsvWriter.plainCell("123")).isEqualTo("123");
    }

    @Test
    void shouldJoinRowCellsWithCommas() {
        assertThat(AnalyticsCsvWriter.row("a", "", "\"b,c\"")).isEqualTo("a,,\"b,c\"");
    }
}
