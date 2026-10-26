package de.zettelwirtschaft.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.YearMonth
import java.util.concurrent.TimeUnit

/**
 * Läuft einmal täglich. Sobald ein neuer Monat begonnen hat, wird die CSV
 * des Vormonats genau einmal erstellt und per Benachrichtigung zum Teilen angeboten.
 */
class MonthlyExportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("export", Context.MODE_PRIVATE)
        val previous = YearMonth.now().minusMonths(1)
        val last = prefs.getString(KEY_LAST, null)?.let(YearMonth::parse)

        if (last == null) {
            // Erster Lauf nach Installation: nichts rückwirkend exportieren
            prefs.edit().putString(KEY_LAST, previous.toString()).apply()
            return Result.success()
        }
        if (!previous.isAfter(last)) return Result.success()

        if (ExpenseStore(applicationContext).forMonth(previous).isNotEmpty()) {
            val file = CsvExport.create(applicationContext, previous)
            notify(file, previous)
        }
        prefs.edit().putString(KEY_LAST, previous.toString()).apply()
        return Result.success()
    }

    private fun notify(file: java.io.File, month: YearMonth) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Monats-Export", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val share = PendingIntent.getActivity(ctx, 1, CsvExport.shareIntent(ctx, file), flags)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), flags)

        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Ausgaben ${monthLabel(month)}")
            .setContentText("CSV ist fertig – zum Teilen tippen")
            .setContentIntent(share)
            .addAction(0, "Teilen", share)
            .addAction(0, "App öffnen", open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(month.hashCode(), n)
    }

    companion object {
        private const val KEY_LAST = "last_exported_month"
        private const val CHANNEL = "monthly_export"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<MonthlyExportWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "monthly_export", ExistingPeriodicWorkPolicy.KEEP, req
            )
        }
    }
}
