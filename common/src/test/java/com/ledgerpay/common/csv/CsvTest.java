package com.ledgerpay.common.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerpay.common.csv.Csv.Record;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

class CsvTest {

  @Test
  void plainCellsAreWrittenAsTheyAre() {
    assertThat(Csv.row(List.of("psp_1", "CAPTURE", "10000"))).isEqualTo("psp_1,CAPTURE,10000\r\n");
  }

  @Test
  void cellsWithCommasQuotesOrLineBreaksAreQuoted() {
    assertThat(Csv.cell("a,b")).isEqualTo("\"a,b\"");
    assertThat(Csv.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    assertThat(Csv.cell("two\nlines")).isEqualTo("\"two\nlines\"");
  }

  @Test
  void formulaLikeCellsAreEscapedSoASpreadsheetShowsThemAsText() {
    assertThat(Csv.cell("=HYPERLINK(\"http://evil\")"))
        .isEqualTo("\"'=HYPERLINK(\"\"http://evil\"\")\"");
    assertThat(Csv.cell("+1")).isEqualTo("'+1");
    assertThat(Csv.cell("-1")).isEqualTo("'-1");
    assertThat(Csv.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
    assertThat(Csv.cell("\tx")).isEqualTo("'\tx");
    assertThat(Csv.cell("a=b")).isEqualTo("a=b");
    assertThat(Csv.cell("")).isEmpty();
  }

  @Test
  void unescapingRemovesOnlyTheEscape() {
    assertThat(Csv.unescapeFormula("'=1+1")).isEqualTo("=1+1");
    assertThat(Csv.unescapeFormula("'quoted")).isEqualTo("'quoted");
    assertThat(Csv.unescapeFormula("plain")).isEqualTo("plain");
    assertThat(Csv.unescapeFormula("'")).isEqualTo("'");
    assertThat(Csv.cell("'=x")).isEqualTo("''=x");
    assertThat(Csv.unescapeFormula("''=x")).isEqualTo("'=x");
  }

  @Test
  void parsesRecordsWithTheLineEachStartsOn() throws Exception {
    List<Record> records = Csv.parse("a,b\r\n\"x\ny\",2\nlast,3");

    assertThat(records)
        .containsExactly(
            new Record(1, List.of("a", "b")),
            new Record(2, List.of("x\ny", "2")),
            new Record(4, List.of("last", "3")));
  }

  @Test
  void theFinalLineEndIsOptionalButBlankLinesAreRecords() throws Exception {
    assertThat(Csv.parse("a\r\n")).containsExactly(new Record(1, List.of("a")));
    assertThat(Csv.parse("a\n\nb\n"))
        .containsExactly(
            new Record(1, List.of("a")), new Record(2, List.of("")), new Record(3, List.of("b")));
    assertThat(Csv.parse("a\n\n").get(1).isBlank()).isTrue();
    assertThat(Csv.parse("")).isEmpty();
  }

  @Test
  void emptyFieldsAreKept() throws Exception {
    assertThat(Csv.parse(",,\n").getFirst().cells()).containsExactly("", "", "");
  }

  @Test
  void misplacedQuotesAreRejectedWithTheirLine() {
    assertThatThrownBy(() -> Csv.parse("a\nb\"c\n"))
        .isInstanceOf(Csv.FormatException.class)
        .hasMessageContaining("quote inside")
        .extracting(e -> ((Csv.FormatException) e).line())
        .isEqualTo(2);
    assertThatThrownBy(() -> Csv.parse("a\n\"open\n"))
        .isInstanceOf(Csv.FormatException.class)
        .hasMessageContaining("never closed");
    assertThatThrownBy(() -> Csv.parse("\"a\"b\n"))
        .isInstanceOf(Csv.FormatException.class)
        .hasMessageContaining("after a closing quote");
  }

  @Property
  void whatIsWrittenParsesBackToTheSameValues(@ForAll @Size(min = 1, max = 6) List<String> cells)
      throws Exception {
    List<Record> records = Csv.parse(Csv.row(cells));

    assertThat(records).hasSize(1);
    assertThat(records.getFirst().cells().stream().map(Csv::unescapeFormula).toList())
        .isEqualTo(cells);
  }
}
