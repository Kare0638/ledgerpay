package com.ledgerpay.payment.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.ledgerpay.payment.settlement.SettlementCsvParser.Result;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SettlementCsvParserTest {

  static final LocalDate DAY = LocalDate.parse("2026-10-13");
  static final String HEADER =
      "psp_reference,psp_request_id,txn_type,merchant_id,amount_minor,currency,succeeded_at\r\n";
  static final String CAPTURE = "psp_1,req_1,CAPTURE,m_1,10000,GBP,2026-10-13T10:00:00.123456Z\r\n";
  static final String REFUND = "psp_2,req_2,REFUND,m_1,3000,GBP,2026-10-13T11:00:00Z\r\n";

  final SettlementCsvParser parser = new SettlementCsvParser(100_000, 1_000);

  Result parse(String csv) {
    return parser.parse(csv.getBytes(StandardCharsets.UTF_8), DAY, true);
  }

  @Test
  void aValidReportGivesEveryRowWithItsLine() {
    Result result = parse(HEADER + CAPTURE + REFUND);

    assertThat(result.valid()).isTrue();
    assertThat(result.rowCount()).isEqualTo(2);
    assertThat(result.rows())
        .containsExactly(
            new SettlementRow(
                2,
                "psp_1",
                "req_1",
                "CAPTURE",
                "m_1",
                10000,
                "GBP",
                Instant.parse("2026-10-13T10:00:00.123456Z")),
            new SettlementRow(
                3,
                "psp_2",
                "req_2",
                "REFUND",
                "m_1",
                3000,
                "GBP",
                Instant.parse("2026-10-13T11:00:00Z")));
  }

  @Test
  void columnsMayComeInAnyOrderAndAFileMayHaveNoRows() {
    Result reordered =
        parse(
            "succeeded_at,currency,amount_minor,merchant_id,txn_type,psp_request_id,psp_reference\n"
                + "2026-10-13T00:00:00Z,GBP,1,m_1,REFUND,req_9,psp_9\n");
    assertThat(reordered.valid()).isTrue();
    assertThat(reordered.rows().getFirst().pspReference()).isEqualTo("psp_9");

    Result empty = parse(HEADER);
    assertThat(empty.valid()).isTrue();
    assertThat(empty.rowCount()).isZero();
  }

  @Test
  void aByteOrderMarkIsIgnoredAndFormulaEscapesAreUndone() {
    Result result =
        parse("﻿" + HEADER + "psp_1,req_1,CAPTURE,'=m_1,10000,GBP,2026-10-13T10:00:00Z\n");

    assertThat(result.valid()).isTrue();
    assertThat(result.rows().getFirst().merchantId()).isEqualTo("=m_1");
  }

  /**
   * Only mock-psp escapes formulas. From any other PSP a leading quote is part of the value: taking
   * it off would store a reference that matches nothing, with no error to say why.
   */
  @Test
  void aLeadingQuoteIsKeptFromAPspThatDoesNotEscapeFormulas() {
    Result result =
        parser.parse(
            (HEADER + "'+A1B2,req_1,CAPTURE,'=m_1,10000,GBP,2026-10-13T10:00:00Z\n")
                .getBytes(StandardCharsets.UTF_8),
            DAY,
            false);

    assertThat(result.valid()).isTrue();
    assertThat(result.rows().getFirst().pspReference()).isEqualTo("'+A1B2");
    assertThat(result.rows().getFirst().merchantId()).isEqualTo("'=m_1");
  }

  @Test
  void aMissingUnknownOrRepeatedColumnInvalidatesTheWholeFile() {
    Result result =
        parse(
            "psp_reference,psp_request_id,txn_type,merchant_id,amount,amount_minor,amount_minor,"
                + "succeeded_at\n"
                + CAPTURE);

    assertThat(result.valid()).isFalse();
    assertThat(result.rows()).isEmpty();
    assertThat(result.rowCount()).isEqualTo(1);
    assertThat(result.errors())
        .containsExactly(
            new LineError(
                1,
                "amount",
                "unknown column; expected " + String.join(", ", SettlementCsvParser.COLUMNS)),
            new LineError(1, "amount_minor", "column appears more than once"),
            new LineError(1, "currency", "missing column"));
  }

  @Test
  void oneBadAmountInvalidatesTheWholeFileAndNamesItsLine() {
    Result result =
        parse(
            HEADER
                + CAPTURE
                + "psp_3,req_3,CAPTURE,m_1,100.00,GBP,2026-10-13T12:00:00Z\n"
                + "psp_4,req_4,CAPTURE,m_1,-5,GBP,2026-10-13T12:00:00Z\n"
                + "psp_5,req_5,CAPTURE,m_1,0,GBP,2026-10-13T12:00:00Z\n"
                + "psp_6,req_6,CAPTURE,m_1,1e3,GBP,2026-10-13T12:00:00Z\n"
                + REFUND);

    assertThat(result.valid()).isFalse();
    assertThat(result.rows()).isEmpty();
    assertThat(result.rowCount()).isEqualTo(6);
    assertThat(result.errors())
        .extracting(LineError::line, LineError::column)
        .containsExactly(
            tuple(3, "amount_minor"),
            tuple(4, "amount_minor"),
            tuple(5, "amount_minor"),
            tuple(6, "amount_minor"));
    assertThat(result.errors().getFirst().message())
        .isEqualTo("must be a positive whole number of minor units, not '100.00'");
  }

  @Test
  void aRowOutsideTheUtcBusinessDayIsAnError() {
    Result result =
        parse(
            HEADER
                + "psp_1,req_1,CAPTURE,m_1,1,GBP,2026-10-12T23:59:59.999999Z\n"
                + "psp_2,req_2,CAPTURE,m_1,1,GBP,2026-10-13T00:00:00Z\n"
                + "psp_3,req_3,CAPTURE,m_1,1,GBP,2026-10-13T23:59:59.999999999Z\n"
                + "psp_4,req_4,CAPTURE,m_1,1,GBP,2026-10-14T00:00:00Z\n");

    assertThat(result.errors())
        .containsExactly(
            new LineError(
                2,
                "succeeded_at",
                "2026-10-12T23:59:59.999999Z is outside the business day 2026-10-13 (UTC)"),
            new LineError(
                5,
                "succeeded_at",
                "2026-10-14T00:00:00Z is outside the business day 2026-10-13 (UTC)"));
  }

  @Test
  void timestampsMustBeUtcWithZAndRealDates() {
    Result result =
        parse(
            HEADER
                + "psp_1,req_1,CAPTURE,m_1,1,GBP,2026-10-13T10:00:00+01:00\n"
                + "psp_2,req_2,CAPTURE,m_1,1,GBP,2026-10-13 10:00:00\n"
                + "psp_3,req_3,CAPTURE,m_1,1,GBP,2026-02-30T10:00:00Z\n"
                + "psp_4,req_4,CAPTURE,m_1,1,GBP,2026-10-13\n");

    assertThat(result.errors()).extracting(LineError::line).containsExactly(2, 3, 4, 5);
    assertThat(result.errors().get(2).message())
        .isEqualTo("not a real date and time: '2026-02-30T10:00:00Z'");
  }

  @Test
  void typesCurrenciesAndTextFieldsAreChecked() {
    Result result =
        parse(
            HEADER
                + "psp_1,req_1,AUTHORIZE,m_1,1,GBP,2026-10-13T10:00:00Z\n"
                + "psp_2,req_2,CAPTURE,m_1,1,gbp,2026-10-13T10:00:00Z\n"
                + "psp_3,req_3,CAPTURE,m_1,1,XYZ,2026-10-13T10:00:00Z\n"
                + ",req_4,CAPTURE,m_1,1,GBP,2026-10-13T10:00:00Z\n"
                + "psp_5 ,req_5,CAPTURE,m_1,1,GBP,2026-10-13T10:00:00Z\n"
                + "psp_6,req_6,CAPTURE,"
                + "m".repeat(256)
                + ",1,GBP,2026-10-13T10:00:00Z\n");

    assertThat(result.errors())
        .extracting(LineError::line, LineError::column)
        .containsExactly(
            tuple(2, "txn_type"),
            tuple(3, "currency"),
            tuple(4, "currency"),
            tuple(5, "psp_reference"),
            tuple(6, "psp_reference"),
            tuple(7, "merchant_id"));
  }

  @Test
  void blankLinesAndWrongFieldCountsAreErrorsNotSkippedRows() {
    Result result =
        parse(HEADER + CAPTURE + "\n" + "psp_2,req_2,REFUND,m_1,3000,GBP\n" + REFUND + "\r\n");

    assertThat(result.rowCount()).isEqualTo(5);
    assertThat(result.errors())
        .containsExactly(
            new LineError(3, null, "blank line"),
            new LineError(4, null, "expected 7 fields, found 6"),
            new LineError(6, null, "blank line"));
  }

  @Test
  void brokenQuotingOrEncodingNamesTheLine() {
    assertThat(parse(HEADER + CAPTURE + "psp_\"2,req_2\n").errors())
        .containsExactly(new LineError(3, null, "quote inside an unquoted field"));

    byte[] latin1 =
        (HEADER + "psp_1,req_1,CAPTURE,m_é,1,GBP,2026-10-13T10:00:00Z\n")
            .getBytes(StandardCharsets.ISO_8859_1);
    Result result = parser.parse(latin1, DAY, true);
    assertThat(result.errors()).hasSize(1);
    assertThat(result.errors().getFirst().line()).isEqualTo(2);
    assertThat(result.errors().getFirst().message()).startsWith("not valid UTF-8");

    assertThat(parse("").errors())
        .containsExactly(new LineError(1, null, "the file is empty: expected a header row"));
  }

  @Test
  void tooManyRowsIsOneErrorAtTheFirstRowOverTheLimit() {
    Result result =
        new SettlementCsvParser(2, 1_000)
            .parse(
                (HEADER + CAPTURE + REFUND + CAPTURE).getBytes(StandardCharsets.UTF_8), DAY, true);

    assertThat(result.rowCount()).isEqualTo(3);
    assertThat(result.errors())
        .containsExactly(
            new LineError(4, null, "more than 2 rows: split the report or raise the limit"));
  }

  @Test
  void everyErrorIsCountedButOnlyTheFirstOnesAreKept() {
    String bad = "psp_x,req_x,CAPTURE,m_1,1.5,GBP,2026-10-13T10:00:00Z\n";
    Result result =
        new SettlementCsvParser(100, 3)
            .parse((HEADER + bad.repeat(10)).getBytes(StandardCharsets.UTF_8), DAY, true);

    assertThat(result.errorCount()).isEqualTo(10);
    assertThat(result.errors()).extracting(LineError::line).containsExactly(2, 3, 4);
  }

  @Test
  void duplicateReferencesAreNotAnImportErrorButABreakForReconciliation() {
    Result result = parse(HEADER + CAPTURE + CAPTURE.replace("req_1", "req_9"));

    assertThat(result.valid()).isTrue();
    assertThat(result.rows()).hasSize(2);
  }

  @Test
  void controlCharactersAreRejectedAndShownEscaped() {
    Result result = parse(HEADER + "psp_\u0000x,req_1,CAPTURE,m_1,1,GBP,2026-10-13T10:00:00Z\n");

    assertThat(result.errors())
        .containsExactly(
            new LineError(2, "psp_reference", "contains a control character: 'psp_\\u0000x'"));

    Result header = parse("\u0000".repeat(100) + "\n");
    assertThat(header.errors().getFirst().column()).startsWith("\\u0000").endsWith("…");
  }
}
