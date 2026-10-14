package lab.probe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/**
 * Throwaway probe. One screen, plus intent extras so the runner can drive it over adb:
 *   autorun=true label=<text> run_id=<id> redact=<bool> keys=<scope:name,...>
 *   tripwire=create|check   watch=true   heartbeat=true
 */
class MainActivity : Activity() {

    private lateinit var out: TextView
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var watcher: Watcher? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        out = TextView(this).apply {
            textSize = 11f
            setTextIsSelectable(true)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        fun button(label: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
            })
        }
        button("Request permissions") { requestPermissions() }
        button("Run all signals") { runAll("manual", null, null) }
        button("Watch on / off") { toggleWatch() }
        button("Tripwire: create keys") { tripwire("create", null, "manual") }
        button("Tripwire: check keys") { tripwire("check", null, "manual") }
        button("Heartbeat job on") { Heartbeat.schedule(this); show("heartbeat scheduled") }
        button("Heartbeat job off") { Heartbeat.cancel(this); show("heartbeat cancelled") }
        root.addView(out)
        setContentView(ScrollView(this).apply { addView(root) })

        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        watcher?.stop()
        super.onDestroy()
    }

    private fun show(text: String) = main.post { out.text = text }

    private fun requestPermissions() {
        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted.add("android.permission.NEARBY_WIFI_DEVICES")
        requestPermissions(wanted.toTypedArray(), 1)
    }

    private fun handle(i: Intent?) {
        if (i == null) return
        Cfg.redact = i.getBooleanExtra("redact", false)
        val runId = i.getStringExtra("run_id")
        val label = i.getStringExtra("label") ?: "auto"
        when {
            i.getBooleanExtra("autorun", false) -> runAll(label, runId, i.getStringExtra("keys"))
            i.hasExtra("tripwire") -> tripwire(i.getStringExtra("tripwire") ?: "check", runId, label)
            i.getBooleanExtra("watch", false) -> toggleWatch()
            i.getBooleanExtra("heartbeat", false) -> Heartbeat.schedule(this)
        }
    }

    private fun meta(runId: String?, label: String, started: Long): org.json.JSONObject = jo(
        "runId" to runId,
        "label" to label,
        "redact" to Cfg.redact,
        "applicationId" to packageName,
        "targetSdk" to applicationInfo.targetSdkVersion,
        "sdk" to Build.VERSION.SDK_INT,
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "display" to Build.DISPLAY,
        "fingerprint" to if (Cfg.redact) null else Build.FINGERPRINT,
        "time" to System.currentTimeMillis(),
        "elapsedMs" to (System.currentTimeMillis() - started),
    )

    private fun save(readings: List<Reading>, runId: String?, label: String, started: Long) {
        val doc = jo("meta" to meta(runId, label, started), "readings" to readings.map { it.toJson() })
        val text = doc.toString(2)
        File(filesDir, "latest.json").writeText(text)
        File(filesDir, "probe-${System.currentTimeMillis()}.json").writeText(text)
        Log.i("OBS", "OBS_DONE runId=$runId")
        main.post { out.text = readings.joinToString("\n") { "${it.id} ${it.name}: ${it.code}" } }
    }

    private fun runAll(label: String, runId: String?, keys: String?) {
        show("Running...")
        exec.execute {
            val started = System.currentTimeMillis()
            val readings = mutableListOf<Reading>()
            readings += Device.collect(this)
            readings += Device.customKeys(this, keys)
            readings += Keystore.attestation()
            readings += Keystore.strongBox(this)
            readings += Keystore.digests()
            readings += Access.collect(this)
            readings += Packages.collect(this)
            readings += Network.collect(this)
            save(readings, runId, label, started)
        }
    }

    private fun tripwire(mode: String, runId: String?, label: String?) {
        show("Tripwire $mode...")
        exec.execute {
            val started = System.currentTimeMillis()
            val reading = if (mode == "create") Keystore.tripwireCreate(this) else Keystore.tripwireCheck(this)
            save(listOf(reading), runId, "tripwire-$mode-${label ?: ""}", started)
        }
    }

    private fun toggleWatch() {
        val existing = watcher
        if (existing != null && existing.running) {
            existing.stop()
            return
        }
        val log = File(filesDir, "watch.log")
        val w = Watcher(this) { event ->
            val line = "${System.currentTimeMillis()}\t$event"
            log.appendText(line + "\n")
            Log.i("OBS", "WATCH $line")
            main.post { out.append(line + "\n") }
        }
        watcher = w
        w.start()
    }
}
