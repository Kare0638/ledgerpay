package com.ledgerpay.payment.settlement;

import com.ledgerpay.common.csv.Csv;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates a whole settlement CSV (design §11.1) and either returns every row or says what is
 * wrong with each line. There is no partial result: one bad line makes the report INVALID, so a row
 * can never be dropped without anyone noticing.
 *
 * <p>The file is strict UTF-8 with a header naming exactly the seven columns, in any order. Each
 * row needs: references and merchant without surrounding spaces, {@code txn_type} CAPTURE or
 * REFUND, {@code amount_minor} a positive whole number of minor units, an ISO 4217 currency, and
 * {@code succeeded_at} an ISO-8601 UTC timestamp ending in {@code Z} inside the business day,
 * {@code [D 00:00Z, D+1 00:00Z)}. Cells escaped against spreadsheet formulas are unescaped first.
 */
public final class SettlementCsvParser {

  static final List<String> COLUMNS =
      List.of(
          "psp_reference",
          "psp_request_id",
          "txn_type",
          "merchant_id",
          "amount_minor",
          "currency",
          "succeeded_at");

  private static final int MAX_TEXT = 255;
  private static final Pattern AMOUNT = Pattern.compile("[1-9][0-9]{0,17}");
  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
  private static final Pattern UTC_TIMESTAMP =
      Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z");

  /**
   * @param rowCount data rows in the file, the header not counted
   * @param rows every row, or none when there is any error
   * @param errorCount every error found
   * @param errors the first {@code maxErrors} of them, in file order
   */
  public record Result(
      int rowCount, List<SettlementRow> rows, int errorCount, List<LineError> errors) {

    public boolean valid() {
      return errorCount == 0;
    }
  }

  private final int maxRows;
  private final int maxErrors;

  public SettlementCsvParser(int maxRows, int maxErrors) {
    this.maxRows = maxRows;
    this.maxErrors = maxErrors;
  }

  public Result parse(byte[] content, LocalDate businessDate) {
    var errors = new Errors(maxErrors);
    String text = decode(content, errors);
    if (text == null) {
      return errors.result(0, List.of());
    }
    if (text.startsWith("﻿")) {
      text = text.substring(1);
    }
    List<Csv.Record> records;
    try {
      records = Csv.parse(text);
    } catch (Csv.FormatException e) {
      errors.add(e.line(), null, e.getMessage());
      return errors.result(0, List.of());
    }
    if (records.isEmpty()) {
      errors.add(1, null, "the file is empty: expected a header row");
      return errors.result(0, List.of());
    }

    int rowCount = records.size() - 1;
    Map<String, Integer> index = header(records.getFirst(), errors);
    if (index == null) {
      return errors.result(rowCount, List.of());
    }
    if (rowCount > maxRows) {
      errors.add(
          records.get(maxRows + 1).line(),
          null,
          "more than " + maxRows + " rows: split the report or raise the limit");
      return errors.result(rowCount, List.of());
    }

    Instant from = businessDate.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant to = businessDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    List<SettlementRow> rows = new ArrayList<>(rowCount);
    for (Csv.Record record : records.subList(1, records.size())) {
      SettlementRow row = row(record, index, from, to, businessDate, errors);
      if (row != null) {
        rows.add(row);
      }
    }
    return errors.result(rowCount, errors.count == 0 ? List.copyOf(rows) : List.of());
  }

  /** Strict UTF-8: a byte sequence that is not UTF-8 is an error on its line, not a {@code ?}. */
  private static String decode(byte[] content, Errors errors) {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    ByteBuffer in = ByteBuffer.wrap(content);
    CharBuffer out = CharBuffer.allocate(content.length);
    CoderResult result = decoder.decode(in, out, true);
    if (!result.isError()) {
      result = decoder.flush(out);
    }
    if (result.isError()) {
      int line = 1;
      for (int i = 0; i < in.position(); i++) {
        if (content[i] == '\n') {
          line++;
        }
      }
      errors.add(line, null, "not valid UTF-8 at byte " + in.position());
      return null;
    }
    return out.flip().toString();
  }

  /** Column name to position, or null when the header is wrong (each problem is an error). */
  private static Map<String, Integer> header(Csv.Record header, Errors errors) {
    Map<String, Integer> index = new HashMap<>();
    for (int i = 0; i < header.cells().size(); i++) {
      String name = header.cells().get(i);
      if (!COLUMNS.contains(name)) {
        errors.add(
            header.line(),
            shorten(printable(name)),
            "unknown column; expected " + String.join(", ", COLUMNS));
      } else if (index.putIfAbsent(name, i) != null) {
        errors.add(header.line(), name, "column appears more than once");
      }
    }
    for (String column : COLUMNS) {
      if (!index.containsKey(column)) {
        errors.add(header.line(), column, "missing column");
      }
    }
    return errors.count == 0 ? index : null;
  }

  private static SettlementRow row(
      Csv.Record record,
      Map<String, Integer> index,
      Instant from,
      Instant to,
      LocalDate businessDate,
      Errors errors) {
    int line = record.line();
    if (record.isBlank()) {
      errors.add(line, null, "blank line");
      return null;
    }
    if (record.cells().size() != COLUMNS.size()) {
      errors.add(
          line, null, "expected " + COLUMNS.size() + " fields, found " + record.cells().size());
      return null;
    }
    int before = errors.count;
    Map<String, String> cell = new HashMap<>();
    index.forEach((column, i) -> cell.put(column, Csv.unescapeFormula(record.cells().get(i))));

    String reference = text(cell, "psp_reference", line, errors);
    String requestId = text(cell, "psp_request_id", line, errors);
    String merchant = text(cell, "merchant_id", line, errors);

    String type = cell.get("txn_type");
    if (!type.equals("CAPTURE") && !type.equals("REFUND")) {
      errors.add(line, "txn_type", "must be CAPTURE or REFUND, not " + quoted(type));
    }

    String amount = cell.get("amount_minor");
    if (!AMOUNT.matcher(amount).matches()) {
      errors.add(
          line,
          "amount_minor",
          "must be a positive whole number of minor units, not " + quoted(amount));
    }

    String currency = cell.get("currency");
    if (!CURRENCY.matcher(currency).matches() || !isIsoCurrency(currency)) {
      errors.add(line, "currency", "must be an ISO 4217 code such as GBP, not " + quoted(currency));
    }

    Instant succeededAt = null;
    String timestamp = cell.get("succeeded_at");
    if (!UTC_TIMESTAMP.matcher(timestamp).matches()) {
      errors.add(
          line,
          "succeeded_at",
          "must be an ISO-8601 UTC timestamp ending in Z, not " + quoted(timestamp));
    } else {
      try {
        succeededAt = Instant.parse(timestamp);
        if (succeededAt.isBefore(from) || !succeededAt.isBefore(to)) {
          errors.add(
              line,
              "succeeded_at",
              timestamp + " is outside the business day " + businessDate + " (UTC)");
        }
      } catch (DateTimeParseException e) {
        errors.add(line, "succeeded_at", "not a real date and time: " + quoted(timestamp));
      }
    }

    if (errors.count > before) {
      return null;
    }
    return new SettlementRow(
        line, reference, requestId, type, merchant, Long.parseLong(amount), currency, succeededAt);
  }

  /**
   * A required text field: not blank, no surrounding spaces (they would defeat matching) and no
   * control characters (PostgreSQL text cannot even hold NUL).
   */
  private static String text(Map<String, String> cell, String column, int line, Errors errors) {
    String value = cell.get(column);
    if (value.isBlank()) {
      errors.add(line, column, "must not be empty");
    } else if (!value.equals(value.strip())) {
      errors.add(line, column, "has leading or trailing spaces: " + quoted(value));
    } else if (value.chars().anyMatch(SettlementCsvParser::isControl)) {
      errors.add(line, column, "contains a control character: " + quoted(value));
    } else if (value.length() > MAX_TEXT) {
      errors.add(line, column, "longer than " + MAX_TEXT + " characters");
    }
    return value;
  }

  private static boolean isIsoCurrency(String code) {
    try {
      Currency.getInstance(code);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** The offending value in quotes, printable and cut short: enough to find it, not the cell. */
  private static String quoted(String value) {
    return "'" + shorten(printable(value)) + "'";
  }

  private static String shorten(String value) {
    return value.length() > 40 ? value.substring(0, 40) + "…" : value;
  }

  /** Control characters written as backslash-u and four hex digits, so a message can be stored. */
  private static String printable(String value) {
    if (value.chars().noneMatch(SettlementCsvParser::isControl)) {
      return value;
    }
    var out = new StringBuilder();
    value
        .chars()
        .forEach(
            c -> out.append(isControl(c) ? String.format("\\u%04x", c) : String.valueOf((char) c)));
    return out.toString();
  }

  private static boolean isControl(int c) {
    return c < 0x20 || c == 0x7f;
  }

  /** Every error counts; only the first {@code max} are kept for the response and the table. */
  private static final class Errors {

    private final int max;
    private final List<LineError> kept = new ArrayList<>();
    private int count;

    Errors(int max) {
      this.max = max;
    }

    void add(int line, String column, String message) {
      count++;
      if (kept.size() < max) {
        kept.add(new LineError(line, column, message));
      }
    }

    Result result(int rowCount, List<SettlementRow> rows) {
      return new Result(rowCount, rows, count, List.copyOf(kept));
    }
  }
}
