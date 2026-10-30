package de.zettelwirtschaft.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val dayFmt = DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)

fun monthLabel(m: YearMonth): String =
    m.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.GERMAN) + " " + m.year

private fun dayLabel(d: LocalDate): String = when (d) {
    LocalDate.now() -> "Heute"
    LocalDate.now().minusDays(1) -> "Gestern"
    else -> d.format(dayFmt)
}

class MainActivity : ComponentActivity() {

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MonthlyExportWorker.schedule(this)
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            ZettelTheme { AppScreen() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen() {
    val context = LocalContext.current
    val store = remember { ExpenseStore(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var month by remember { mutableStateOf(YearMonth.now()) }
    var refresh by remember { mutableIntStateOf(0) }
    val expenses = remember(month, refresh) { store.forMonth(month) }
    // Häufigste Zwecke als Schnellauswahl im Eingabeblatt
    val suggestions = remember(refresh) {
        store.all().groupingBy { it.purpose }.eachCount()
            .entries.sortedByDescending { it.value }.take(8).map { it.key }
    }

    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun delete(e: Expense) {
        store.delete(e.id)
        refresh++
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(
                message = "„${e.purpose}“ gelöscht",
                actionLabel = "Rückgängig",
                duration = SnackbarDuration.Short,
            )
            if (r == SnackbarResult.ActionPerformed) {
                store.restore(e)
                refresh++
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding()) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showSheet = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Beleg erfassen") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.navigationBarsPadding(),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ReceiptHeader(
                month = month,
                expenses = expenses,
                onPrev = { month = month.minusMonths(1) },
                onNext = { month = month.plusMonths(1) },
                onShare = {
                    val file = CsvExport.create(context, month)
                    context.startActivity(CsvExport.shareIntent(context, file))
                },
            )

            if (expenses.isEmpty()) {
                EmptyState(month, Modifier.weight(1f))
            } else {
                ExpenseList(expenses, onDelete = { delete(it) }, modifier = Modifier.weight(1f))
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
        ) {
            EntryForm(
                suggestions = suggestions,
                onSave = { date, purpose, cents ->
                    store.add(date, purpose, cents)
                    month = YearMonth.from(date)
                    refresh++
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) showSheet = false
                    }
                },
            )
        }
    }
}

/** Der „Kassenbon“ oben: Monat, Summe, Teilen. */
@Composable
private fun ReceiptHeader(
    month: YearMonth,
    expenses: List<Expense>,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onShare: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val total = expenses.sumOf { it.cents }

    Surface(
        shape = ReceiptShape(),
        color = colors.surface,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .statusBarsPadding()
                .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 26.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ZETTELWIRTSCHAFT", style = LabelCaps, color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                AssistChip(
                    onClick = onShare,
                    enabled = expenses.isNotEmpty(),
                    label = { Text("CSV teilen") },
                    leadingIcon = { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)) },
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                IconButton(onClick = onPrev) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Vormonat")
                }
                Text(
                    monthLabel(month),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onNext) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Nächster Monat")
                }
            }

            DashedDivider(colors.outline, Modifier.padding(top = 10.dp, bottom = 14.dp, end = 8.dp))

            Row(Modifier.padding(end = 8.dp)) {
                Text("SUMME", style = LabelCaps, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(
                    if (expenses.size == 1) "1 BELEG" else "${expenses.size} BELEGE",
                    style = LabelCaps, color = colors.onSurfaceVariant,
                )
            }
            AnimatedContent(
                targetState = total,
                transitionSpec = {
                    (slideInVertically { it / 2 } + fadeIn()) togetherWith
                        (slideOutVertically { -it / 2 } + fadeOut())
                },
                label = "summe",
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 2.dp),
            ) { value ->
                Text(
                    formatAmount(value),
                    fontFamily = Mono,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 38.sp,
                    lineHeight = 44.sp,
                    color = colors.primary,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(month: YearMonth, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_notification), null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("Noch keine Belege", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Für ${monthLabel(month)} ist noch nichts erfasst.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(80.dp)) // Platz für den Knopf unten
    }
}

/** Ausgaben nach Tagen gruppiert, je Tag eine zusammenhängende „Karte“. */
@Composable
private fun ExpenseList(
    expenses: List<Expense>,
    onDelete: (Expense) -> Unit,
    modifier: Modifier = Modifier,
) {
    val byDay = remember(expenses) { expenses.groupBy { it.date } }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp + bottomInset),
    ) {
        byDay.forEach { (day, list) ->
            item(key = "h$day") {
                Row(
                    Modifier.fillMaxWidth().animateItem().padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        dayLabel(day).uppercase(Locale.GERMAN),
                        style = LabelCaps,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        formatAmount(list.sumOf { it.cents }),
                        style = LabelCaps,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            list.forEachIndexed { i, e ->
                item(key = e.id) {
                    val shape = groupShape(i, list.size)
                    Column(Modifier.animateItem()) {
                        SwipeToDelete(shape, onDelete = { onDelete(e) }) {
                            ExpenseRow(e, shape)
                        }
                        if (i < list.size - 1) Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }
        item(key = "hint") {
            Text(
                "Zum Löschen nach links wischen",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            )
        }
    }
}

/** Außen runde, innen kleine Ecken – die Einträge eines Tages wirken wie ein Block. */
private fun groupShape(index: Int, size: Int): Shape {
    val outer = 18.dp
    val inner = 4.dp
    val top = if (index == 0) outer else inner
    val bottom = if (index == size - 1) outer else inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(
    shape: Shape,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart) {
                onDelete(); true
            } else false
        }
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        modifier = modifier,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Filled.Delete, "Löschen", tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) { content() }
}

@Composable
private fun ExpenseRow(e: Expense, shape: Shape) {
    val stamp = stampColor(e.purpose)
    Surface(shape = shape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(stamp.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    e.purpose.firstOrNull()?.uppercase() ?: "?",
                    color = stamp, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                )
            }
            Spacer(Modifier.width(14.dp))
            Text(
                e.purpose,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                formatAmount(e.cents),
                fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 16.sp,
            )
        }
    }
}

/** Inhalt des Eingabeblatts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryForm(
    suggestions: List<String>,
    onSave: (LocalDate, String, Long) -> Unit,
) {
    val today = LocalDate.now()
    var date by remember { mutableStateOf(today) }
    var purpose by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var amountError by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    val amountFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(250) // warten, bis das Blatt eingeblendet ist
        runCatching { amountFocus.requestFocus() }
    }

    fun trySave() {
        val cents = parseAmount(amount)
        amountError = cents == null || cents == 0L
        if (amountError || purpose.isBlank()) return
        onSave(date, purpose, cents!!)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
    ) {
        Text("Neuer Beleg", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it; amountError = false },
            label = { Text("Betrag") },
            suffix = { Text("€", fontFamily = Mono, fontSize = 24.sp) },
            singleLine = true,
            isError = amountError,
            supportingText = { if (amountError) Text("Bitte einen Betrag wie 12,50 eingeben") },
            textStyle = MaterialTheme.typography.headlineMedium.copy(fontFamily = Mono),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().focusRequester(amountFocus),
        )

        OutlinedTextField(
            value = purpose,
            onValueChange = { purpose = it },
            label = { Text("Wofür?") },
            placeholder = { Text("z. B. Tanken") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { trySave() }),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        if (suggestions.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestions.forEach { s ->
                    SuggestionChip(onClick = { purpose = s }, label = { Text(s) })
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("DATUM", style = LabelCaps, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val yesterday = today.minusDays(1)
            val other = date != today && date != yesterday
            FilterChip(selected = date == today, onClick = { date = today }, label = { Text("Heute") })
            FilterChip(selected = date == yesterday, onClick = { date = yesterday }, label = { Text("Gestern") })
            FilterChip(
                selected = other,
                onClick = { showPicker = true },
                label = { Text(if (other) date.format(dateFmt) else "Anderes Datum") },
                leadingIcon = { Icon(Icons.Filled.DateRange, null, Modifier.size(18.dp)) },
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { trySave() },
            enabled = purpose.isNotBlank() && amount.isNotBlank(),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Icon(Icons.Filled.Check, null)
            Spacer(Modifier.width(8.dp))
            Text("Speichern", fontSize = 16.sp)
        }
    }

    if (showPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Abbrechen") } },
        ) { DatePicker(state) }
    }
}
