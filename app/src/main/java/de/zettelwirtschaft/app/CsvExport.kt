package de.zettelwirtschaft.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.time.YearMonth
import java.time.format.DateTimeFormatter

object CsvExport {
    private val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun exportDir(context: Context) = File(context.filesDir, "exports").apply { mkdirs() }

    fun fileFor(context: Context, month: YearMonth) =
        File(exportDir(context), "zettelwirtschaft_$month.csv")

    /**
     * Schreibt die CSV für den Monat. Format für deutsches Excel/LibreOffice:
     * Semikolon als Trenner, Komma als Dezimalzeichen, UTF-8 mit BOM.
     */
    fun create(context: Context, month: YearMonth): File {
        val expenses = ExpenseStore(context).forMonth(month).sortedBy { it.date }
        val sb = StringBuilder("﻿")
        sb.append("Datum;Zweck;Betrag\r\n")
        for (e in expenses) {
            sb.append(e.date.format(dateFmt)).append(';')
                .append(escape(e.purpose)).append(';')
                .append(formatAmount(e.cents, withSymbol = false)).append("\r\n")
        }
        val file = fileFor(context, month)
        file.writeText(sb.toString(), Charsets.UTF_8)
        return file
    }

    /** Standard-Android-Teilen-Dialog (Mail, Messenger, Drive, …) */
    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Ausgaben ${file.nameWithoutExtension.substringAfter('_')}")
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "CSV teilen")
    }

    private fun escape(s: String): String =
        if (s.any { it == ';' || it == '"' || it == '\n' || it == '\r' })
            "\"" + s.replace("\"", "\"\"") + "\""
        else s
}

fun formatAmount(cents: Long, withSymbol: Boolean = true): String {
    val sign = if (cents < 0) "-" else ""
    val abs = kotlin.math.abs(cents)
    // In der App mit Tausenderpunkt (1.234,56 €), in der CSV ohne (1234,56)
    val euros = if (withSymbol) String.format(java.util.Locale.GERMAN, "%,d", abs / 100)
                else (abs / 100).toString()
    val s = String.format(java.util.Locale.ROOT, "%s%s,%02d", sign, euros, abs % 100)
    return if (withSymbol) "$s €" else s
}

/** Akzeptiert "12,50", "12.50", "12" – liefert Cent oder null bei ungültiger Eingabe. */
fun parseAmount(input: String): Long? {
    val norm = input.trim().replace(" ", "").replace("€", "").replace(',', '.')
    if (!Regex("""\d+(\.\d{1,2})?""").matches(norm)) return null
    return norm.toBigDecimal().movePointRight(2).longValueExact()
}
