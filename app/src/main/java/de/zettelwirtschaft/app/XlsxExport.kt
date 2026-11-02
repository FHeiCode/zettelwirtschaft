package de.zettelwirtschaft.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Schreibt eine echte Excel-Datei (.xlsx) ohne zusätzliche Bibliothek.
 * Eine .xlsx ist nur ein ZIP-Archiv mit ein paar XML-Dateien.
 *
 * Inhalt: Kopfzeile (fixiert), Datum als echtes Datum, Betrag als Zahl im
 * Währungsformat und eine Summenzeile mit Formel – man kann also direkt weiterrechnen.
 */
object XlsxExport {
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    fun fileFor(context: Context, month: YearMonth) =
        File(CsvExport.exportDir(context), "zettelwirtschaft_$month.xlsx")

    fun create(context: Context, month: YearMonth): File {
        val expenses = ExpenseStore(context).forMonth(month).sortedBy { it.date }
        val file = fileFor(context, month)
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            fun put(name: String, xml: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", ROOT_RELS)
            put("xl/workbook.xml", workbook(monthLabel(month)))
            put("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            put("xl/styles.xml", STYLES)
            put("xl/worksheets/sheet1.xml", sheet(expenses))
        }
        return file
    }

    // Excel zählt Tage ab dem 30.12.1899
    private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)

    private fun sheet(expenses: List<Expense>): String {
        val sb = StringBuilder()
        sb.append(XML_HEAD)
        sb.append("<worksheet xmlns=\"$NS_MAIN\">")
        // Kopfzeile beim Scrollen fixieren
        sb.append("<sheetViews><sheetView workbookViewId=\"0\">")
        sb.append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
        sb.append("</sheetView></sheetViews>")
        sb.append("<cols>")
        sb.append("<col min=\"1\" max=\"1\" width=\"12\" customWidth=\"1\"/>")
        sb.append("<col min=\"2\" max=\"2\" width=\"40\" customWidth=\"1\"/>")
        sb.append("<col min=\"3\" max=\"3\" width=\"14\" customWidth=\"1\"/>")
        sb.append("</cols>")
        sb.append("<sheetData>")

        sb.append("<row r=\"1\">")
        sb.append(textCell("A1", "Datum", S_HEADER))
        sb.append(textCell("B1", "Zweck", S_HEADER))
        sb.append(textCell("C1", "Betrag", S_HEADER))
        sb.append("</row>")

        expenses.forEachIndexed { i, e ->
            val r = i + 2
            val serial = ChronoUnit.DAYS.between(EXCEL_EPOCH, e.date)
            sb.append("<row r=\"$r\">")
            sb.append("<c r=\"A$r\" s=\"$S_DATE\"><v>$serial</v></c>")
            sb.append(textCell("B$r", e.purpose, 0))
            sb.append("<c r=\"C$r\" s=\"$S_AMOUNT\"><v>${euros(e.cents)}</v></c>")
            sb.append("</row>")
        }

        // Leerzeile, dann Summe als Formel (mit vorberechnetem Wert für Vorschau-Apps)
        val last = maxOf(expenses.size + 1, 2)
        val r = expenses.size + 3
        sb.append("<row r=\"$r\">")
        sb.append(textCell("B$r", "Summe", S_TOTAL_LABEL))
        sb.append("<c r=\"C$r\" s=\"$S_TOTAL\"><f>SUM(C2:C$last)</f><v>${euros(expenses.sumOf { it.cents })}</v></c>")
        sb.append("</row>")

        sb.append("</sheetData>")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun textCell(ref: String, text: String, style: Int) =
        "<c r=\"$ref\" t=\"inlineStr\" s=\"$style\"><is><t xml:space=\"preserve\">${esc(text)}</t></is></c>"

    private fun euros(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

    /** XML-Sonderzeichen maskieren und in XML unzulässige Steuerzeichen entfernen. */
    private fun esc(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun workbook(sheetName: String) =
        XML_HEAD +
            "<workbook xmlns=\"$NS_MAIN\" xmlns:r=\"$NS_REL\">" +
            "<sheets><sheet name=\"${esc(sheetName.take(31))}\" sheetId=\"1\" r:id=\"rId1\"/></sheets>" +
            "<calcPr fullCalcOnLoad=\"1\"/>" +
            "</workbook>"

    // Stil-Indizes in cellXfs (siehe STYLES)
    private const val S_HEADER = 1
    private const val S_DATE = 2
    private const val S_AMOUNT = 3
    private const val S_TOTAL_LABEL = 4
    private const val S_TOTAL = 5

    private const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private const val CONTENT_TYPES = XML_HEAD +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
        "</Types>"

    private const val ROOT_RELS = XML_HEAD +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private const val WORKBOOK_RELS = XML_HEAD +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
        "</Relationships>"

    private const val STYLES = XML_HEAD +
        "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
        "<numFmts count=\"2\">" +
        "<numFmt numFmtId=\"164\" formatCode=\"DD.MM.YYYY\"/>" +
        "<numFmt numFmtId=\"165\" formatCode=\"#,##0.00\\ &quot;€&quot;\"/>" +
        "</numFmts>" +
        "<fonts count=\"2\">" +
        "<font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>" +
        "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>" +
        "</fonts>" +
        "<fills count=\"3\">" +
        "<fill><patternFill patternType=\"none\"/></fill>" +
        "<fill><patternFill patternType=\"gray125\"/></fill>" +
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFCDE8DA\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
        "</fills>" +
        "<borders count=\"3\">" +
        "<border><left/><right/><top/><bottom/><diagonal/></border>" +
        "<border><left/><right/><top/><bottom style=\"thin\"><color rgb=\"FF2E7D5B\"/></bottom><diagonal/></border>" +
        "<border><left/><right/><top style=\"thin\"><color auto=\"1\"/></top><bottom style=\"double\"><color auto=\"1\"/></bottom><diagonal/></border>" +
        "</borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"6\">" +
        // 0 Standard
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        // 1 Kopfzeile
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>" +
        // 2 Datum
        "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
        // 3 Betrag
        "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
        // 4 „Summe“
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
        // 5 Summenbetrag
        "<xf numFmtId=\"165\" fontId=\"1\" fillId=\"0\" borderId=\"2\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyBorder=\"1\"/>" +
        "</cellXfs>" +
        "<cellStyles count=\"1\"><cellStyle name=\"Standard\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
        "</styleSheet>"
}
