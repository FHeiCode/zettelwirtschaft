package de.zettelwirtschaft.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

data class Expense(
    val id: Long,
    val date: LocalDate,
    val purpose: String,
    /** Betrag in Cent, um Rundungsfehler zu vermeiden */
    val cents: Long,
)

/**
 * Sehr einfache Ablage aller Ausgaben als JSON-Datei im privaten App-Speicher.
 * Für ein paar hundert Einträge pro Jahr völlig ausreichend.
 */
class ExpenseStore(context: Context) {
    private val file = File(context.filesDir, "ausgaben.json")

    @Synchronized
    fun all(): List<Expense> {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Expense(
                id = o.getLong("id"),
                date = LocalDate.parse(o.getString("date")),
                purpose = o.getString("purpose"),
                cents = o.getLong("cents"),
            )
        }.sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.id })
    }

    fun forMonth(month: YearMonth): List<Expense> =
        all().filter { YearMonth.from(it.date) == month }

    @Synchronized
    fun add(date: LocalDate, purpose: String, cents: Long) {
        save(all() + Expense(System.currentTimeMillis(), date, purpose.trim(), cents))
    }

    @Synchronized
    fun delete(id: Long) {
        save(all().filterNot { it.id == id })
    }

    private fun save(list: List<Expense>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("date", it.date.toString())
                    .put("purpose", it.purpose)
                    .put("cents", it.cents)
            )
        }
        // Erst in Temp-Datei schreiben, dann umbenennen: kein Datenverlust bei Absturz
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
