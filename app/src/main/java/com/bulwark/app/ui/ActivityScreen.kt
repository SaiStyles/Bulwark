package com.bulwark.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bulwark.app.observability.AlarmWakeups
import com.bulwark.app.observability.AppOpLedger
import com.bulwark.app.observability.DozeExemptions
import com.bulwark.app.observability.LocationRequests
import com.bulwark.app.observability.SensorRegistrations
import com.bulwark.app.shizuku.DumpsysAccess
import com.bulwark.app.shizuku.ShizukuState
import com.bulwark.app.ui.theme.bulwark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything the Activity screen renders, as data.
 *
 * **No defaults**, for the same reason as `AuditReadings`: a field nobody
 * wires should be a compile error rather than a card that quietly renders
 * empty. Each read carries its own "could not tell", because these are five
 * separate dumps and one of them failing is not the others failing.
 */
data class ActivityReadings(
    val ledger: List<AppOpLedger.Summary>?,
    val ledgerCouldNotTell: String?,
    val doze: DozeExemptions.Reading?,
    val dozeCouldNotTell: String?,
    val wakeups: AlarmWakeups.Reading?,
    val wakeupsCouldNotTell: String?,
    val sensors: List<SensorRegistrations.Summary>?,
    val sensorsCouldNotTell: String?,
    val location: LocationRequests.Reading?,
    val locationCouldNotTell: String?,
    val shizukuReady: Boolean,
)

/**
 * What the apps on this phone actually **did**.
 *
 * The fourth destination, added 2026-09-13. `design.md` rule 1 is one screen,
 * one question, and Audit had quietly grown two: *what can the software here
 * do to me* (capability - permissions, special access, the firewall) and *what
 * did it do* (behaviour - wakeups, sensor reads, location). Those are different
 * questions and they were competing for the top of the same scroll.
 *
 * Everything here comes from `dumpsys` at uid 2000, which is
 * `signatureOrSystem` - so this screen is the part of Bulwark that no Play
 * Store app can copy. `capability-research/observability.md` carries the
 * ranking and the walls.
 *
 * **This is the most sensitive screen in the app**, which is why
 * `MainActivity` marks it `FLAG_SECURE` exactly as it marks Audit: a
 * timestamped record of who used the microphone is the thing `security.md`
 * OPEN-1 was written about.
 *
 * ## What it refuses to do
 *
 * It does not accuse, and it uses no colour to rank anything. A music app
 * reading the accelerometer, a messaging app waking the phone, a navigation
 * app holding a location request - each is doing the job it was installed for.
 * Bulwark's contribution is that these facts exist at all; the judgement is
 * the reader's. `design.md` rules 4 and 7.
 */
@Composable
fun ActivityScreen(
    state: ShizukuState,
    modifier: Modifier = Modifier,
) {
    var ledger by remember { mutableStateOf<List<AppOpLedger.Summary>?>(null) }
    var ledgerCouldNotTell by remember { mutableStateOf<String?>(null) }
    var doze by remember { mutableStateOf<DozeExemptions.Reading?>(null) }
    var dozeCouldNotTell by remember { mutableStateOf<String?>(null) }
    var wakeups by remember { mutableStateOf<AlarmWakeups.Reading?>(null) }
    var wakeupsCouldNotTell by remember { mutableStateOf<String?>(null) }
    var sensors by remember { mutableStateOf<List<SensorRegistrations.Summary>?>(null) }
    var sensorsCouldNotTell by remember { mutableStateOf<String?>(null) }
    var location by remember { mutableStateOf<LocationRequests.Reading?>(null) }
    var locationCouldNotTell by remember { mutableStateOf<String?>(null) }

    val ready = state is ShizukuState.Ready

    // One effect for five reads, run in sequence off the main thread.
    //
    // Sequential rather than parallel on purpose: these are binder
    // transactions into the system server, the largest is 150 KB, and firing
    // five at once at a cheap phone to save a few hundred milliseconds is a
    // cost paid by the whole device rather than by this screen.
    //
    // Everything is cleared when Shizuku is not up. Data Bulwark cannot
    // re-read is a claim with no source - the same rule the permission sweep
    // follows.
    // What apps did while you were not looking is, by definition, most stale
    // the moment you come back.
    val returns = rememberResumeTicker()

    LaunchedEffect(ready, returns) {
        if (!ready) {
            ledger = null; ledgerCouldNotTell = null
            doze = null; dozeCouldNotTell = null
            wakeups = null; wakeupsCouldNotTell = null
            sensors = null; sensorsCouldNotTell = null
            location = null; locationCouldNotTell = null
            return@LaunchedEffect
        }

        withContext(Dispatchers.IO) {
            // -- what apps did while you were not looking
            val ledgerReadings = mutableListOf<AppOpLedger.Reading>()
            val ledgerFailures = mutableListOf<String>()
            for (op in AppOpLedger.Op.entries) {
                when (val dump = DumpsysAccess.read(dumpFor(op))) {
                    is DumpsysAccess.Reading.Lines ->
                        ledgerReadings += AppOpLedger.parse(dump.lines, op)
                    is DumpsysAccess.Reading.CouldNotTell -> ledgerFailures += dump.why
                }
            }
            ledger = if (ledgerReadings.isEmpty()) null else AppOpLedger.summarise(ledgerReadings)
            ledgerCouldNotTell = when {
                ledgerReadings.isEmpty() -> ledgerFailures.firstOrNull()
                    ?: "Bulwark could not read this phone's record of app activity."
                ledgerFailures.isNotEmpty() ->
                    "Bulwark could not read all three of these, so the list may be short."
                else -> AppOpLedger.unreadableNotice(ledgerReadings)
            }

            // -- what keeps running while the phone sleeps
            when (val dump = DumpsysAccess.read(DumpsysAccess.Dump.DOZE_WHITELIST)) {
                is DumpsysAccess.Reading.Lines -> {
                    val parsed = DozeExemptions.parse(dump.lines)
                    doze = parsed
                    dozeCouldNotTell = if (parsed.readNothing) {
                        "Bulwark reached this phone's sleep settings but could not " +
                            "read the answer it got back."
                    } else {
                        DozeExemptions.unreadableNotice(parsed)
                    }
                }
                is DumpsysAccess.Reading.CouldNotTell -> {
                    doze = null
                    dozeCouldNotTell = dump.why
                }
            }

            // -- who wakes the phone
            when (val dump = DumpsysAccess.read(DumpsysAccess.Dump.ALARM_WAKEUPS)) {
                is DumpsysAccess.Reading.Lines -> {
                    val parsed = AlarmWakeups.parse(dump.lines)
                    wakeups = parsed
                    wakeupsCouldNotTell = if (parsed.readNothing) {
                        "Bulwark reached this phone's alarm record but could not read it."
                    } else {
                        AlarmWakeups.unreadableNotice(parsed)
                    }
                }
                is DumpsysAccess.Reading.CouldNotTell -> {
                    wakeups = null
                    wakeupsCouldNotTell = dump.why
                }
            }

            // -- who reads the sensors
            when (val dump = DumpsysAccess.read(DumpsysAccess.Dump.SENSOR_REGISTRATIONS)) {
                is DumpsysAccess.Reading.Lines -> {
                    val parsed = SensorRegistrations.parse(dump.lines)
                    sensors = SensorRegistrations.summarise(parsed)
                    sensorsCouldNotTell = if (parsed.readNothing) {
                        "Bulwark reached this phone's sensor record but could not read it."
                    } else {
                        SensorRegistrations.unreadableNotice(parsed)
                    }
                }
                is DumpsysAccess.Reading.CouldNotTell -> {
                    sensors = null
                    sensorsCouldNotTell = dump.why
                }
            }

            // -- who is asking where the phone is
            when (val dump = DumpsysAccess.read(DumpsysAccess.Dump.LOCATION_REQUESTS)) {
                is DumpsysAccess.Reading.Lines -> {
                    val parsed = LocationRequests.parse(dump.lines)
                    location = parsed
                    locationCouldNotTell = if (parsed.readNothing) {
                        "Bulwark reached this phone's location settings but could not read them."
                    } else {
                        LocationRequests.unreadableNotice(parsed)
                    }
                }
                is DumpsysAccess.Reading.CouldNotTell -> {
                    location = null
                    locationCouldNotTell = dump.why
                }
            }
        }
    }

    ActivityContent(
        readings = ActivityReadings(
            ledger = ledger,
            ledgerCouldNotTell = ledgerCouldNotTell,
            doze = doze,
            dozeCouldNotTell = dozeCouldNotTell,
            wakeups = wakeups,
            wakeupsCouldNotTell = wakeupsCouldNotTell,
            sensors = sensors,
            sensorsCouldNotTell = sensorsCouldNotTell,
            location = location,
            locationCouldNotTell = locationCouldNotTell,
            shizukuReady = ready,
        ),
        modifier = modifier,
    )
}

/** Which dump answers for which op. */
private fun dumpFor(op: AppOpLedger.Op): DumpsysAccess.Dump = when (op) {
    AppOpLedger.Op.MICROPHONE -> DumpsysAccess.Dump.MICROPHONE_USES
    AppOpLedger.Op.CAMERA -> DumpsysAccess.Dump.CAMERA_USES
    AppOpLedger.Op.PRECISE_LOCATION -> DumpsysAccess.Dump.PRECISE_LOCATION_USES
}

/**
 * The Activity screen, drawn from data alone.
 *
 * Every read is already done and there is nothing to press, so this renders
 * identically on any phone - which is what lets `ActivityContentTest` assert
 * that each card has content in it.
 *
 * **Every card emits its slot from the first composition.** A late read that
 * adds a `LazyColumn` item shifts everything below it relative to the scroll
 * anchor, and that already hid a card on the Audit screen once. Five reads
 * landing at five different moments makes this screen the worst possible place
 * to repeat it.
 */
@Composable
fun ActivityContent(
    readings: ActivityReadings,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Text(
                "What your apps did",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Ordered by how much a person can act on it, not by how the
                // reads happen to be written above.
                item(key = "ledger") {
                    ObservationCard(
                        title = "What apps did while you were not looking",
                        shizukuReady = readings.shizukuReady,
                        unavailable = "Shizuku is not running. This record is kept by " +
                            "Android and never shown to you, and reading it needs the " +
                            "access Shizuku provides.",
                        couldNotTell = readings.ledgerCouldNotTell,
                        loaded = readings.ledger,
                        headline = { AppOpLedger.headline(it) },
                        detail = { AppOpLedger.detail(it) },
                        rows = { it.map(AppOpLedger::line) },
                    )
                }

                item(key = "location") {
                    ObservationCard(
                        title = "What is asking where you are",
                        shizukuReady = readings.shizukuReady,
                        unavailable = "Shizuku is not running, so Bulwark cannot read " +
                            "which apps have a standing request for your location.",
                        couldNotTell = readings.locationCouldNotTell,
                        loaded = readings.location,
                        headline = { LocationRequests.headline(it) },
                        detail = { LocationRequests.detail(it) },
                        rows = { it.requests.map(LocationRequests::line) },
                    )
                }

                item(key = "sensors") {
                    ObservationCard(
                        title = "What read this phone's sensors",
                        shizukuReady = readings.shizukuReady,
                        unavailable = "Shizuku is not running, so Bulwark cannot read " +
                            "which apps asked the sensors for data.",
                        couldNotTell = readings.sensorsCouldNotTell,
                        loaded = readings.sensors,
                        headline = { SensorRegistrations.headline(it) },
                        detail = { SensorRegistrations.detail(it) },
                        rows = { it.map(SensorRegistrations::line) },
                    )
                }

                item(key = "wakeups") {
                    ObservationCard(
                        title = "What wakes this phone up",
                        shizukuReady = readings.shizukuReady,
                        unavailable = "Shizuku is not running, so Bulwark cannot read " +
                            "which apps have been waking the phone.",
                        couldNotTell = readings.wakeupsCouldNotTell,
                        loaded = readings.wakeups,
                        headline = { AlarmWakeups.headline(it) },
                        detail = { null },
                        rows = { it.wakers.map(AlarmWakeups::line) },
                    )
                }

                item(key = "doze") {
                    ObservationCard(
                        title = "What keeps running while your phone sleeps",
                        shizukuReady = readings.shizukuReady,
                        unavailable = "Shizuku is not running. Android does not let an " +
                            "ordinary app ask which apps are exempt from sleeping, which " +
                            "is why this list is worth showing - and why Bulwark cannot " +
                            "read it on its own.",
                        couldNotTell = readings.dozeCouldNotTell,
                        loaded = readings.doze,
                        headline = { DozeExemptions.headline(it) },
                        detail = { DozeExemptions.detail(it) },
                        rows = { it.exemptions.map { e -> e.packageName } },
                        monospace = true,
                    )
                }
            }
        }
    }
}

/**
 * One observation, in the four states every one of them has.
 *
 * Written once rather than five times because the states are the hard part and
 * getting them wrong is what this screen must not do: **no Shizuku**, **could
 * not read**, **still reading**, and **read**. Five hand-written cards would
 * be five chances to let one of those silently become a blank.
 *
 * `T` is whatever the reading is; the card never inspects it and only asks the
 * three functions for words.
 */
@Composable
private fun <T> ObservationCard(
    title: String,
    shizukuReady: Boolean,
    unavailable: String,
    couldNotTell: String?,
    loaded: T?,
    headline: (T) -> String,
    detail: (T) -> String?,
    rows: (T) -> List<String>,
    /**
     * Monospace suits a bare package name and ruins a sentence.
     *
     * Seen on the phone: `com.google.android.gms.location.history used your
     * precise location once…` in monospace wraps mid-identifier, so the name
     * breaks across lines and the sentence reads as neither. Only the Doze
     * card, whose rows are package names and nothing else, sets this.
     */
    monospace: Boolean = false,
) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)

            when {
                !shizukuReady -> Text(
                    unavailable,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.bulwark.incomplete,
                )

                loaded == null && couldNotTell != null -> Text(
                    couldNotTell,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.bulwark.incomplete,
                )

                loaded == null -> Text(
                    "Checking…",
                    style = MaterialTheme.typography.bodySmall,
                )

                else -> {
                    Text(headline(loaded), style = MaterialTheme.typography.bodyMedium)
                    detail(loaded)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    // A partial read is said beside the list it qualifies,
                    // never folded away: a short list read as complete is the
                    // false all-clear this screen exists to avoid.
                    couldNotTell?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.bulwark.incomplete)
                    }
                    // Capped, and the cap is **stated**. The sensor card ran
                    // to 47 rows on the Agni 2 - past the point where a list
                    // informs anyone - but a list silently cut short is the
                    // false all-clear this screen is written against, so the
                    // remainder is counted out loud rather than dropped.
                    val all = rows(loaded)
                    all.take(MAX_ROWS).forEach { row ->
                        Text(
                            row,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = if (monospace) FontFamily.Monospace else null,
                        )
                    }
                    if (all.size > MAX_ROWS) {
                        Text(
                            "and ${all.size - MAX_ROWS} more",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/**
 * How many rows a card shows before it says "and N more".
 *
 * Twelve is about a screenful. The number is a readability choice and nothing
 * more - what matters is that the remainder is always stated, never trimmed
 * away.
 */
private const val MAX_ROWS = 12
