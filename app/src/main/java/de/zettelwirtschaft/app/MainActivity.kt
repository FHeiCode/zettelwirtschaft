package de.zettelwirtschaft.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")

fun monthLabel(m: YearMonth): String =
    m.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.GERMAN) + " " + m.year

class MainActivity : ComponentActivity() {

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MonthlyExportWorker.schedule(this)
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { AppScreen() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen() {
    val context = LocalContext.current
    val store = remember { ExpenseStore(context) }

    var month by remember { mutableStateOf(YearMonth.now()) }
    var refresh by remember { mutableIntStateOf(0) }
    val expenses = remember(month, refresh) { store.forMonth(month) }

    var date by remember { mutableStateOf(LocalDate.now()) }
    var purpose by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var amountError by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Expense?>(null) }

    fun save() {
        val cents = parseAmount(amount)
        amountError = cents == null || cents == 0L
        if (amountError || purpose.isBlank()) return
        store.add(date, purpose, cents!!)
        month = YearMonth.from(date)
        purpose = ""; amount = ""
        refresh++
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Zettelwirtschaft") }) }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize()
        ) {
            // --- Eingabe ---
            OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Datum: ${date.format(dateFmt)}")
            }
            OutlinedTextField(
                value = purpose, onValueChange = { purpose = it },
                label = { Text("Zweck") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = amount, onValueChange = { amount = it; amountError = false },
                label = { Text("Betrag in €") }, singleLine = true,
                isError = amountError,
                supportingText = { if (amountError) Text("z. B. 12,50") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { save() },
                enabled = purpose.isNotBlank() && amount.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Ausgabe speichern") }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // --- Monatsübersicht ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Vormonat")
                }
                Text(monthLabel(month), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { month = month.plusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Nächster Monat")
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Summe: ${formatAmount(expenses.sumOf { it.cents })}",
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                )
                FilledTonalButton(
                    enabled = expenses.isNotEmpty(),
                    onClick = {
                        val file = CsvExport.create(context, month)
                        context.startActivity(CsvExport.shareIntent(context, file))
                    }
                ) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("CSV teilen")
                }
            }

            if (expenses.isEmpty()) {
                Text("Keine Ausgaben in diesem Monat.", Modifier.padding(top = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.weight(1f)) {
                items(expenses, key = { it.id }) { e ->
                    ListItem(
                        headlineContent = { Text(e.purpose, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(e.date.format(dateFmt)) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(formatAmount(e.cents))
                                IconButton(onClick = { toDelete = e }) {
                                    Icon(Icons.Filled.Delete, "Löschen")
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Abbrechen") } }
        ) { DatePicker(state) }
    }

    toDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Ausgabe löschen?") },
            text = { Text("${e.date.format(dateFmt)} – ${e.purpose} – ${formatAmount(e.cents)}") },
            confirmButton = {
                TextButton(onClick = { store.delete(e.id); toDelete = null; refresh++ }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Abbrechen") } }
        )
    }
}
