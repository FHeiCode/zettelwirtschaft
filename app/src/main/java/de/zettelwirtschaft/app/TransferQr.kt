package de.zettelwirtschaft.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * GiroCode (EPC-QR-Code, Standard EPC069-12): Banking-Apps lesen daraus
 * Empfänger, IBAN, Betrag und Verwendungszweck – Überweisung per Foto.
 */
object GiroCode {
    fun normalizeIban(s: String) = s.replace(" ", "").uppercase()

    fun formatIban(s: String) = normalizeIban(s).chunked(4).joinToString(" ")

    /** Prüft Aufbau und Prüfziffer (Modulo 97) – fängt Tippfehler ab. */
    fun isValidIban(raw: String): Boolean {
        val iban = normalizeIban(raw)
        if (!Regex("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}").matches(iban)) return false
        var rest = 0
        for (ch in iban.drop(4) + iban.take(4)) {
            val v = if (ch.isDigit()) ch - '0' else ch - 'A' + 10
            rest = if (v >= 10) (rest * 100 + v) % 97 else (rest * 10 + v) % 97
        }
        return rest == 1
    }

    fun payload(name: String, iban: String, cents: Long, text: String): String =
        listOf(
            "BCD",                  // Kennung
            "002",                  // Version (BIC optional)
            "1",                    // Zeichensatz UTF-8
            "SCT",                  // SEPA-Überweisung
            "",                     // BIC (leer erlaubt)
            name.trim().take(70),
            normalizeIban(iban),
            "EUR" + BigDecimal.valueOf(cents, 2).toPlainString(),
            "",                     // Purpose-Code
            "",                     // strukturierte Referenz
            text.take(140),         // Verwendungszweck
        ).joinToString("\n")

    fun matrix(payload: String): BitMatrix =
        QRCodeWriter().encode(
            payload, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, // vom Standard verlangt
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 2,
            )
        )
}

/** Empfänger (Name + IBAN) wird einmal eingegeben und gemerkt. */
object Payee {
    private fun prefs(c: Context) = c.getSharedPreferences("payee", Context.MODE_PRIVATE)
    fun name(c: Context) = prefs(c).getString("name", "") ?: ""
    fun iban(c: Context) = prefs(c).getString("iban", "") ?: ""
    fun isSet(c: Context) = name(c).isNotBlank() && GiroCode.isValidIban(iban(c))
    fun save(c: Context, name: String, iban: String) =
        prefs(c).edit().putString("name", name.trim()).putString("iban", GiroCode.normalizeIban(iban)).apply()
}

/** Eine Abrechnung: welche Ausgaben, welcher Zeitraum, welcher Betrag. */
data class Settlement(val items: List<Expense>) {
    val from = items.minOf { it.date }
    val to = items.maxOf { it.date }
    val cents = items.sumOf { it.cents }
    private val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    val period: String = if (from == to) from.format(fmt) else "${from.format(fmt)} – ${to.format(fmt)}"
    /** Verwendungszweck für die Bank (nur einfache Zeichen) */
    val purpose: String = "Ausgaben " + if (from == to) from.format(fmt) else "${from.format(fmt)}-${to.format(fmt)}"
}

/**
 * Ablauf: 1. offene Ausgaben bestätigen → 2. QR-Code anzeigen.
 * Mit dem Erstellen gelten die Ausgaben als abgerechnet und kommen in keinen weiteren QR-Code.
 */
@Composable
fun TransferQrDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ExpenseStore(context) }
    var editingPayee by remember { mutableStateOf(!Payee.isSet(context)) }
    // Wird erst beim Erstellen festgehalten – danach ändert sich der Inhalt nicht mehr
    var created by remember { mutableStateOf<Settlement?>(null) }
    val open = remember { store.open() }

    if (editingPayee) {
        PayeeForm(
            onSaved = { editingPayee = false },
            onCancel = { if (Payee.isSet(context)) editingPayee = false else onDismiss() },
        )
        return
    }

    val settlement = created
    if (settlement == null) {
        ConfirmSettlement(
            pending = if (open.isEmpty()) null else Settlement(open),
            onEditPayee = { editingPayee = true },
            onCreate = { s ->
                store.markSettled(s.items.map { it.id }.toSet(), LocalDate.now())
                created = s
            },
            onDismiss = onDismiss,
        )
    } else {
        ShowQr(settlement, onDismiss)
    }
}

@Composable
private fun ConfirmSettlement(
    pending: Settlement?,
    onEditPayee: () -> Unit,
    onCreate: (Settlement) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Abrechnen") },
        text = {
            if (pending == null) {
                Text("Es gibt keine offenen Ausgaben – alles ist schon abgerechnet.")
            } else {
                Column {
                    Text(if (pending.items.size == 1) "1 offene Ausgabe" else "${pending.items.size} offene Ausgaben")
                    Spacer(Modifier.height(8.dp))
                    Text("Zeitraum", style = LabelCaps, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(pending.period, fontFamily = Mono)
                    Spacer(Modifier.height(6.dp))
                    Text("Betrag", style = LabelCaps, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        formatAmount(pending.cents), fontFamily = Mono, fontWeight = FontWeight.SemiBold,
                        fontSize = 22.sp, color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("an ${Payee.name(context)}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onEditPayee, contentPadding = PaddingValues(0.dp)) {
                        Text("Empfänger ändern")
                    }
                    Text(
                        "Mit dem Erstellen des QR-Codes gelten diese Ausgaben als abgerechnet " +
                            "und können nicht noch einmal per QR-Code geteilt werden.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (pending != null) {
                TextButton(onClick = { onCreate(pending) }) { Text("QR-Code erstellen") }
            } else {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = {
            if (pending != null) TextButton(onClick = onDismiss) { Text("Abbrechen") }
        },
    )
}

@Composable
private fun ShowQr(s: Settlement, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val name = Payee.name(context)
    val iban = Payee.iban(context)
    val matrix = remember(s) { GiroCode.matrix(GiroCode.payload(name, iban, s.cents, s.purpose)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Überweisung per QR-Code") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                // QR immer schwarz auf weiß – auch im Dunkelmodus, sonst scannen Banking-Apps schlecht
                Surface(color = Color.White, shape = RoundedCornerShape(12.dp)) {
                    QrCanvas(matrix, Modifier.size(232.dp).padding(4.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("ZEITRAUM", style = LabelCaps, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(s.period, fontFamily = Mono)
                Spacer(Modifier.height(6.dp))
                Text("BETRAG", style = LabelCaps, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    formatAmount(s.cents),
                    fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 26.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                Text("an $name", textAlign = TextAlign.Center)
                Text(
                    GiroCode.formatIban(iban),
                    fontFamily = Mono, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "In der Banking-App „Foto-Überweisung“ bzw. „GiroCode scannen“ wählen.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
        dismissButton = {
            TextButton(onClick = {
                val file = qrImageFile(context, matrix, s)
                context.startActivity(shareImageIntent(context, file, "${s.purpose}: ${formatAmount(s.cents)}"))
            }) { Text("Als Bild teilen") }
        },
    )
}

@Composable
private fun PayeeForm(onSaved: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(Payee.name(context)) }
    var iban by remember { mutableStateOf(GiroCode.formatIban(Payee.iban(context))) }
    var ibanError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Wer bekommt das Geld?") },
        text = {
            Column {
                Text(
                    "Name und IBAN werden einmal gespeichert und für jeden QR-Code verwendet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name des Kontoinhabers") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = iban, onValueChange = { iban = it.uppercase(); ibanError = false },
                    label = { Text("IBAN") }, singleLine = true,
                    isError = ibanError,
                    supportingText = { if (ibanError) Text("IBAN ungültig – bitte prüfen") },
                    textStyle = LocalTextStyle.current.copy(fontFamily = Mono),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && iban.isNotBlank(),
                onClick = {
                    if (!GiroCode.isValidIban(iban)) {
                        ibanError = true
                    } else {
                        Payee.save(context, name, iban)
                        onSaved()
                    }
                },
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Abbrechen") } },
    )
}

@Composable
private fun QrCanvas(matrix: BitMatrix, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val n = matrix.width
        val cell = size.minDimension / n
        for (y in 0 until n) for (x in 0 until n) {
            if (matrix[x, y]) {
                // +0,5 px gegen feine Lücken zwischen den Modulen
                drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/** QR-Code als PNG mit Zeitraum und Betrag darunter, z. B. zum Verschicken per Messenger. */
private fun qrImageFile(context: Context, matrix: BitMatrix, s: Settlement): File {
    val scale = 12
    val qr = matrix.width * scale
    val width = maxOf(qr, 720)          // breit genug für den Text darunter
    val left = (width - qr) / 2f        // QR-Code mittig
    val textArea = 200
    val bmp = Bitmap.createBitmap(width, qr + textArea, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(android.graphics.Color.WHITE)
    val black = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }
    for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
        if (matrix[x, y]) {
            canvas.drawRect(
                left + x * scale, (y * scale).toFloat(),
                left + (x + 1) * scale, ((y + 1) * scale).toFloat(), black,
            )
        }
    }
    val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
    }
    val cx = width / 2f
    // Schrift verkleinern, bis die Zeile mit Rand ins Bild passt
    fun line(str: String, size: Float, y: Float, bold: Boolean = false) {
        text.isFakeBoldText = bold
        text.textSize = size
        while (text.measureText(str) > width - 40 && text.textSize > 12f) text.textSize -= 1f
        canvas.drawText(str, cx, y, text)
    }
    line("Zeitraum: ${s.period}", 30f, qr + 50f)
    line(formatAmount(s.cents), 52f, qr + 125f, bold = true)
    val count = if (s.items.size == 1) "1 Ausgabe" else "${s.items.size} Ausgaben"
    line("$count · Zettelwirtschaft", 24f, qr + 172f)

    val file = File(CsvExport.exportDir(context), "ueberweisung_${s.from}_${s.to}.png")
    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return file
}

private fun shareImageIntent(context: Context, file: File, text: String): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, "$text – QR-Code mit der Banking-App scannen.")
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, "QR-Code teilen")
}
