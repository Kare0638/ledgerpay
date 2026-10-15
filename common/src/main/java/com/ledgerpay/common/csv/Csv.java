package com.ledgerpay.common.csv;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 CSV, for settlement reports (design §11.1): comma-separated, CRLF line ends, fields with
 * a comma, quote or line break in double quotes, and a quote inside them doubled.
 *
 * <p>Writing escapes spreadsheet formulas: a cell that begins with {@code = + - @}, a tab or a
 * carriage return gets a leading {@code '}, so a spreadsheet shows it as text instead of running
 * it. So does one that already starts with quotes before such a character, which makes the escape
 * reversible: {@link #unescapeFormula} removes exactly one quote, and every value survives the
 * round trip.
 */
public final class Csv {

  private static final String LINE_END = "\r\n";

  private Csv() {}

  /** One record, escaped and quoted as needed, with its CRLF. */
  public static String row(List<String> cells) {
    var line = new StringBuilder();
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) {
        line.append(',');
      }
      line.append(cell(cells.get(i)));
    }
    return line.append(LINE_END).toString();
  }

  /** One field: formula-escaped, then quoted if it holds a comma, quote or line break. */
  public static String cell(String value) {
    String escaped = startsLikeFormula(value) ? "'" + value : value;
    if (escaped.chars().anyMatch(c -> c == ',' || c == '"' || c == '\r' || c == '\n')) {
      return '"' + escaped.replace("\"", "\"\"") + '"';
    }
    return escaped;
  }

  /** Removes the {@code '} that {@link #cell} put in front of a formula-like value. */
  public static String unescapeFormula(String value) {
    return value.startsWith("'") && startsLikeFormula(value) ? value.substring(1) : value;
  }

  /** Optional quotes, then {@code = + - @}, a tab or a carriage return. */
  private static boolean startsLikeFormula(String value) {
    int i = 0;
    while (i < value.length() && value.charAt(i) == '\'') {
      i++;
    }
    if (i == value.length()) {
      return false;
    }
    char first = value.charAt(i);
    return first == '='
        || first == '+'
        || first == '-'
        || first == '@'
        || first == '\t'
        || first == '\r';
  }

  /** A parsed record and the line it starts on, counting from 1. */
  public record Record(int line, List<String> cells) {

    /** A line with nothing on it parses as a single empty field. */
    public boolean isBlank() {
      return cells.size() == 1 && cells.getFirst().isEmpty();
    }
  }

  /** The text cannot be split into records: a quote out of place, or one never closed. */
  public static final class FormatException extends Exception {

    private final int line;

    FormatException(int line, String message) {
      super(message);
      this.line = line;
    }

    public int line() {
      return line;
    }
  }

  /**
   * Splits text into records. Line ends may be CRLF or LF, and the newline after the last record is
   * optional; every other line, blank ones included, is a record, so none is lost unseen.
   */
  public static List<Record> parse(String text) throws FormatException {
    List<Record> records = new ArrayList<>();
    List<String> cells = new ArrayList<>();
    var field = new StringBuilder();
    int line = 1;
    int recordLine = 1;
    int i = 0;
    int n = text.length();
    if (n == 0) {
      return records;
    }
    while (true) {
      // At the start of a field.
      if (i < n && text.charAt(i) == '"') {
        int quoteLine = line;
        i++;
        while (true) {
          if (i >= n) {
            throw new FormatException(quoteLine, "quoted field is never closed");
          }
          char c = text.charAt(i);
          if (c == '"') {
            if (i + 1 < n && text.charAt(i + 1) == '"') {
              field.append('"');
              i += 2;
              continue;
            }
            i++;
            break;
          }
          if (c == '\n') {
            line++;
          }
          field.append(c);
          i++;
        }
        if (i < n && !isDelimiter(text, i)) {
          throw new FormatException(line, "unexpected character after a closing quote");
        }
      } else {
        while (i < n && !isDelimiter(text, i)) {
          if (text.charAt(i) == '"') {
            throw new FormatException(line, "quote inside an unquoted field");
          }
          field.append(text.charAt(i));
          i++;
        }
      }
      cells.add(field.toString());
      field.setLength(0);
      if (i < n && text.charAt(i) == ',') {
        i++;
        continue;
      }
      // End of a record: a line end or the end of the text.
      records.add(new Record(recordLine, List.copyOf(cells)));
      cells.clear();
      if (i >= n) {
        return records;
      }
      i += text.charAt(i) == '\r' ? 2 : 1;
      line++;
      recordLine = line;
      if (i >= n) {
        return records;
      }
    }
  }

  private static boolean isDelimiter(String text, int i) {
    char c = text.charAt(i);
    return c == ','
        || c == '\n'
        || (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n');
  }
}
