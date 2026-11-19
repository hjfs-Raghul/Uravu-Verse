package com.infinitefamily.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.infinitefamily.R
import com.infinitefamily.data.AppDatabase
import com.infinitefamily.data.FamilyRepository
import com.infinitefamily.domain.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Optional "family in this city" alerts.
 *
 * Privacy / battery design:
 *  - off by default, only scheduled after the user enables it;
 *  - one coarse, network-based fix roughly once an hour (WorkManager). No continuous tracking, no maps;
 *  - the location is matched against city names in profiles on the device and is never stored or sent;
 *  - nobody else's location is involved: it only compares YOUR city with cities typed into profiles.
 *
 * Honest limitation: Android may delay or skip periodic work (Doze, battery saver, OEM task killers) and
 * needs the "allow all the time" location permission to run with the app closed. Alerts are therefore
 * best-effort and can arrive late or be missed.
 */
class FamilyLocationWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val (data, overrides, settings) = FamilyRepository.readAll(db)
        if (settings[SettingKeys.NOTIFY] != "1") return Result.success()
        if (!hasCoarsePermission(applicationContext)) return Result.success()

        val loc = currentLocation(applicationContext) ?: return Result.success()
        val city = CityGazetteer.nearest(loc.latitude, loc.longitude)?.name ?: return Result.success()

        val home = CityGazetteer.canonical(settings[SettingKeys.HOME_CITY].orEmpty().ifBlank { data.root?.location.orEmpty() })
        if (city == home) return Result.success()

        val prefs = applicationContext.getSharedPreferences("family_alerts", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_$city", 0L)
        if (System.currentTimeMillis() - last < 12 * 60 * 60 * 1000L) return Result.success()

        val here = data.persons.values.filter { !it.deceased && it.location.isNotBlank() && CityGazetteer.canonical(it.location) == city && it.id != data.rootId }
        if (here.isEmpty()) return Result.success()

        val engine = RelationshipEngine(data, Terminology(overrides = overrides))
        val lines = here.take(6).map { p ->
            val rel = data.rootId?.let { engine.relation(it, p.id).primary?.summary }.orEmpty()
            if (rel.isBlank()) p.name else "${p.name} ($rel)"
        }
        notify(applicationContext, city.replaceFirstChar { it.uppercase() }, lines, here.size)
        prefs.edit().putLong("last_$city", System.currentTimeMillis()).apply()
        return Result.success()
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(ctx: Context): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                val fresh = withTimeoutOrNull(20_000) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        val signal = CancellationSignal()
                        cont.invokeOnCancellation { signal.cancel() }
                        lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, signal, ctx.mainExecutor) { l -> if (cont.isActive) cont.resume(l) }
                    }
                }
                if (fresh != null) return fresh
            }
            // Fallback: a recent cached fix (never older than 3 hours).
            lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .filter { System.currentTimeMillis() - it.time < 3 * 60 * 60 * 1000L }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) { null }
    }

    private fun notify(ctx: Context, city: String, lines: List<String>, count: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Family nearby (Uravu Verse)", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        val pi = android.app.PendingIntent.getActivity(ctx, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("You're in $city — $count of your family live here")
            .setContentText(lines.joinToString(", "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(pi).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(city.hashCode(), n) }
    }

    companion object {
        const val CHANNEL = "family_nearby"
        private const val WORK = "family_location_check"

        fun hasCoarsePermission(ctx: Context) =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun hasBackgroundPermission(ctx: Context) = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<FamilyLocationWorker>(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancel(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork(WORK) }
    }
}
