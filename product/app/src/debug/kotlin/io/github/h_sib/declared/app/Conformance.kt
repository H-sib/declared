package io.github.h_sib.declared.app

import android.util.Base64
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.h_sib.declared.core.flow.AppState
import io.github.h_sib.declared.core.flow.StoreStatus
import io.github.h_sib.declared.core.state.Review
import io.github.h_sib.declared.core.state.SavedState
import io.github.h_sib.declared.core.state.StoreResult
import io.github.h_sib.declared.core.flow.Load
import io.github.h_sib.declared.core.paths.PathsAnalysis
import io.github.h_sib.declared.core.ledger.HistoryState
import io.github.h_sib.declared.core.ledger.LedgerCodec
import io.github.h_sib.declared.core.ledger.LedgerLoad
import io.github.h_sib.declared.core.ledger.TimelineRow
import io.github.h_sib.declared.core.ledger.Verifying
import io.github.h_sib.declared.core.model.Declarations
import io.github.h_sib.declared.core.model.Availability
import io.github.h_sib.declared.core.model.Observation
import io.github.h_sib.declared.core.model.Value
import io.github.h_sib.declared.core.report.Report
import io.github.h_sib.declared.core.sensor.ComponentId
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.sensor.SpecialAccess
import io.github.h_sib.declared.core.sensor.WatchedPermissions
import io.github.h_sib.declared.core.integrity.ChainVerifier
import io.github.h_sib.declared.core.integrity.ReferenceData
import io.github.h_sib.declared.sensors.AndroidAccessibilitySource
import io.github.h_sib.declared.sensors.AndroidAttestationSource
import io.github.h_sib.declared.sensors.AndroidReferenceSource
import io.github.h_sib.declared.sensors.AndroidAppOpsSource
import io.github.h_sib.declared.sensors.AndroidDeviceAdminSource
import io.github.h_sib.declared.sensors.AndroidDeviceSource
import io.github.h_sib.declared.sensors.AndroidNetworkSource
import io.github.h_sib.declared.sensors.AndroidUserCaSource
import io.github.h_sib.declared.sensors.AndroidWifiSource
import io.github.h_sib.declared.sensors.AndroidInputMethodSource
import io.github.h_sib.declared.sensors.AndroidNotificationListenerSource
import io.github.h_sib.declared.sensors.AndroidPackageFactsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Debug-only conformance hook (DESIGN.md section 13, layer 3). When the app is launched with
 * `--ez conformance true --es runId <id>`, it writes what it found, plus the raw readings underneath, to
 * `files/conformance-<id>.json`. A lab script then compares that file with what adb says about the same phone.
 * It exists only in the debug source set, so a release build contains no trace of it.
 */
object Conformance {
    private const val TAG = "DeclaredConformance"

    /** A lab script can always take screenshots, even if the person's setting blocks them, so it can look at the screens. */
    fun allowCapture(activity: ComponentActivity): Boolean = activity.intent.getBooleanExtra("allowCapture", false)

    /** A lab script can ask for every collapsed section to start open, so a screen dump holds all of its text. People always get them closed. */
    fun expandAll(activity: ComponentActivity): Boolean = activity.intent.getBooleanExtra("expandAll", false)

    fun attach(activity: ComponentActivity, state: StateFlow<AppState>) {
        if (!activity.intent.getBooleanExtra("conformance", false)) return
        val runId = activity.intent.getStringExtra("runId") ?: "run"
        // The history as the screens hold it and, read back separately from its encrypted file, as it is on disk (STAGE3_DESIGN.md).
        activity.lifecycleScope.launch {
            state.map { it.history }.distinctUntilChanged().collect { history ->
                val text = withContext(Dispatchers.IO) { historyJson(activity, history).toString(2) }
                withContext(Dispatchers.IO) { File(activity.filesDir, "conformance-$runId-history.json").writeText(text) }
            }
        }
        // What the live watcher heard and what the report said afterwards, rewritten on every change, for the live-view script.
        activity.lifecycleScope.launch {
            state.map { liveJson(it).toString(2) }.distinctUntilChanged().collect { text ->
                withContext(Dispatchers.IO) { File(activity.filesDir, "conformance-$runId-live.json").writeText(text) }
            }
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val settled = state.first { (it.load is Load.Ready || it.load is Load.Failed) && it.store != StoreStatus.Loading }
                val ready = settled.load
                val json = withContext(Dispatchers.Default) {
                    when (ready) {
                        is Load.Ready -> dump(activity, ready.report, runId).put("state", stateJson(activity, settled))
                        is Load.Failed -> JSONObject().put("runId", runId).put("failed", ready.reason)
                        else -> JSONObject()
                    }
                }
                File(activity.filesDir, "conformance-$runId.json").writeText(json.toString(2))
                Log.i(TAG, "written conformance-$runId.json")
            }
        }
    }

    private fun liveJson(s: AppState): JSONObject = JSONObject()
        .put("watching", s.live.watching)
        .put("events", JSONArray(s.live.events.map { JSONObject().put("at", it.at.toString()).put("source", it.source.name).put("what", it.what) }))
        .put("stack", JSONArray(s.stack.map { it.toString() }))
        .put("refreshing", s.refreshing)
        .put("refreshError", s.refreshError ?: JSONObject.NULL)
        .put("reportTakenAt", s.report?.takenAt?.toString() ?: JSONObject.NULL)
        .put("facts", JSONObject().also { o -> s.report?.facts?.forEach { o.put(it.kind.name, it.summary) } })
        .put("findingIds", JSONArray(s.report?.findings?.map { it.id }.orEmpty()))
        .put("appCount", s.report?.apps?.size ?: JSONObject.NULL)
        .put("historyEntries", s.history.entries)
        .put("changes", s.changes?.let { c -> JSONArray(c.items.map { i -> i.title }) } ?: JSONObject.NULL)

    private fun ids(set: Set<ComponentId>) = JSONArray(set.map { it.flatten() }.sorted())

    private fun nullable(value: Any?): Any = value ?: JSONObject.NULL

    /** A raw reading as JSON, or the text of the refusal. A refused or failed read is data, not a crash. */
    private fun <T> raw(result: ReadResult<T>, render: (T) -> JSONObject): Any = when (result) {
        is ReadResult.Ok -> render(result.value)
        else -> result.toString()
    }

    private fun rawReadings(activity: ComponentActivity): JSONObject {
        val raw = JSONObject()
        raw.put(
            "accessibility",
            raw(AndroidAccessibilitySource(activity).read()) { r ->
                JSONObject()
                    .put("installed", JSONArray(r.installed.map {
                        JSONObject().put("component", it.component.flatten()).put("capabilityBits", it.capabilityBits)
                            .put("declaresAccessibilityTool", nullable(it.declaresAccessibilityTool)).put("label", nullable(it.label))
                    }))
                    .put("enabledByManager", ids(r.enabledByManager))
                    .put("enabledBySetting", r.enabledBySetting?.let { ids(it) } ?: JSONObject.NULL)
            },
        )
        raw.put(
            "notification",
            raw(AndroidNotificationListenerSource(activity).read()) { r ->
                JSONObject()
                    .put("installed", JSONArray(r.installed.map { JSONObject().put("component", it.component.flatten()).put("label", nullable(it.label)) }))
                    .put("enabled", ids(r.enabled))
            },
        )
        raw.put(
            "admins",
            raw(AndroidDeviceAdminSource(activity).read()) { r ->
                JSONObject()
                    .put("installed", JSONArray(r.installed.map {
                        JSONObject().put("component", it.component.flatten()).put("policyBits", nullable(it.policyBits)).put("label", nullable(it.label))
                    }))
                    .put("active", ids(r.active))
            },
        )
        raw.put(
            "keyboards",
            raw(AndroidInputMethodSource(activity).read()) { r ->
                JSONObject()
                    .put("installed", JSONArray(r.installed.map { JSONObject().put("component", it.component.flatten()).put("label", nullable(it.label)) }))
                    .put("enabled", ids(r.enabled))
                    .put("default", r.default?.flatten() ?: JSONObject.NULL)
            },
        )
        val started = System.nanoTime()
        val packages = AndroidPackageFactsSource(activity).all()
        raw.put("packagesMillis", (System.nanoTime() - started) / 1_000_000)
        raw.put(
            "packages",
            raw(packages) { list ->
                JSONObject().put("count", list.size).put("items", JSONArray(list.map { f ->
                    JSONObject().put("pkg", f.pkg).put("label", nullable(f.label)).put("uid", f.uid).put("system", f.system)
                        .put("updatedSystem", f.updatedSystem).put("platformSigned", nullable(f.platformSigned))
                        .put("installing", nullable(f.installingPackage)).put("initiating", nullable(f.initiatingPackage))
                        .put("origin", nullable(f.installOrigin?.name)).put("installRecordReadable", f.installRecordReadable)
                        .put("signerSha256", JSONArray(f.signerSha256)).put("hasLauncherIcon", f.hasLauncherIcon)
                        .put("requestedWatched", JSONArray(f.requestedWatched.sorted())).put("grantedWatched", JSONArray(f.grantedWatched.sorted()))
                }))
            },
        )
        // Special access for every app that could hold it, read directly, so the lab can compare each with `appops get`.
        val ops = AndroidAppOpsSource(activity)
        val opsJson = JSONObject()
        if (packages is ReadResult.Ok) {
            for (f in packages.value) {
                if (f.requestedWatched.none { it in WatchedPermissions.SPECIAL }) continue
                val modes = ops.modes(f.pkg, f.uid)
                opsJson.put(
                    f.pkg,
                    if (modes is ReadResult.Ok) {
                        JSONObject().also { o -> for (a in SpecialAccess.entries) o.put(a.name, modes.value[a]?.name ?: "UNKNOWN") }
                    } else {
                        modes.toString()
                    },
                )
            }
        }
        raw.put("appOps", opsJson)
        val device = AndroidDeviceSource(activity).read()
        fun part(r: ReadResult<*>): Any = when (r) {
            is ReadResult.Ok -> nullable(r.value)
            else -> r.toString()
        }
        raw.put(
            "device",
            JSONObject().put("release", device.androidRelease).put("sdk", device.sdkInt).put("securityPatch", part(device.securityPatch))
                .put("screenLock", part(device.screenLock)).put("usbDebugging", part(device.usbDebugging))
                .put("wirelessDebugging", part(device.wirelessDebugging)).put("developerOptions", part(device.developerOptions))
                .put("advancedProtection", device.advancedProtection.toString()),
        )
        raw.put(
            "network",
            raw(AndroidNetworkSource(activity).read()) { n ->
                JSONObject().put("present", n.present).put("transports", JSONArray(n.transports.map { it.name }.sorted()))
                    .put("internet", n.internet).put("validated", n.validated).put("captivePortal", n.captivePortal)
                    .put("privateDnsActive", nullable(n.privateDnsActive)).put("privateDnsServerName", nullable(n.privateDnsServerName))
                    .put("httpProxy", nullable(n.httpProxy))
            },
        )
        raw.put(
            "wifi",
            raw(AndroidWifiSource(activity).read()) { w ->
                JSONObject().put("connected", w.connected).put("security", nullable(w.security?.name)).put("note", nullable(w.note))
            },
        )
        raw.put("userCas", raw(AndroidUserCaSource().read()) { c -> JSONObject().put("sha256", JSONArray(c.sha256)) })
        raw.put("integrity", integrity(activity))
        return raw
    }

    private fun savedJson(saved: SavedState): JSONObject = JSONObject()
        .put("version", saved.version).put("baselineAt", saved.baselineAt.toString())
        .put("reviewedAt", saved.reviewed.takenAt.toString()).put("reviewedApi", saved.reviewed.androidApi)
        .put("recordCount", saved.reviewed.records.size)
        .put("declarations", declarationsJson(saved.declarations))
        .put("answers", JSONObject().also { o ->
            for ((k, a) in saved.answers) o.put(k, JSONObject().put("answer", a.answer.name).put("signers", JSONArray(a.signerSha256)))
        })

    private fun historyJson(activity: ComponentActivity, h: io.github.h_sib.declared.core.ledger.History): JSONObject {
        val onDisk: Any = when (val r = EncryptedLedgerStore(activity).load()) {
            LedgerLoad.Empty -> JSONObject().put("status", "Empty")
            is LedgerLoad.Loaded -> JSONObject().put("status", "Loaded").put("text", LedgerCodec.encode(r.ledger))
            is LedgerLoad.Corrupt -> JSONObject().put("status", "Corrupt").put("reason", r.reason)
            is LedgerLoad.Unavailable -> JSONObject().put("status", "Unavailable").put("reason", r.reason)
        }
        return JSONObject()
            .put("state", h.state::class.simpleName)
            .put("reason", when (val st = h.state) { is HistoryState.Damaged -> st.reason; is HistoryState.Unavailable -> st.reason; else -> JSONObject.NULL })
            .put("entries", h.entries)
            .put("head", h.head?.let { JSONObject().put("seq", it.seq).put("hash", it.hash).put("at", it.at.toString()) } ?: JSONObject.NULL)
            .put("keyHeld", h.keyHeld?.name ?: JSONObject.NULL)
            .put("lastTripwire", h.lastTripwire?.let { JSONObject().put("code", it.code).put("at", it.at.toString()) } ?: JSONObject.NULL)
            .put("signerKey", io.github.h_sib.declared.sensors.AndroidLedgerSigner().publicKeySpki?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) } ?: JSONObject.NULL)
            .put("tripwire", h.tripwire?.let { t ->
                JSONObject().put("verdict", t.verdict::class.simpleName).put("notPossible", JSONObject().also { o -> t.notPossible.forEach { (k, v) -> o.put(k.name, v) } })
            } ?: JSONObject.NULL)
            .put("verifying", when (val v = h.verifying) {
                Verifying.NotRun -> "NotRun"
                Verifying.Running -> "Running"
                is Verifying.Done -> "Done:${v.result.intact}:${v.result.authentic}:${v.result.problems.size}"
            })
            .put("timelineRows", JSONArray(h.timeline.map { row ->
                when (row) {
                    is TimelineRow.Recorded -> JSONObject().put("seq", row.seq).put("taken", row.taken.name).put("items", row.items.size).put("events", JSONArray(row.events)).put("start", row.isStart)
                    is TimelineRow.Gap -> JSONObject().put("gapHours", row.duration.toHours())
                }
            }))
            .put("onDisk", onDisk)
    }

    private fun declarationsJson(d: Declarations) = JSONObject().put("smsCodes", d.smsCodes.name).put("publicWifi", d.publicWifi.name).put("profile", d.profile.name)

    /** The paths the screens show, from the same code, so a lab script can compare them with an independent evaluation of the same rules. */
    private fun pathsJson(s: AppState): Any {
        val report = s.report ?: return JSONObject.NULL
        val a = PathsAnalysis.of(report, s.saved, s.declarations)
        return JSONObject()
            .put("profile", a.view.profile.name)
            .put("declarations", declarationsJson(s.declarations))
            .put("paths", JSONArray(a.view.paths.map { JSONObject().put("id", it.id).put("severity", it.severity.name).put("confidence", it.confidence.name).put("restsOnWhatYouSaid", it.restsOnWhatYouSaid).put("stoppedBy", JSONArray(it.stoppedBy.map { l -> l.id })) }))
            .put("notRuledOut", JSONArray(a.view.notRuledOut.map { JSONObject().put("id", it.id).put("needsAnAnswer", it.needsAnAnswer).put("because", JSONArray(it.because)) }))
            .put("plan", a.view.plan?.let { p -> JSONObject().put("levers", JSONArray(p.levers.map { l -> l.id })).put("exact", p.exact).put("cut", p.cut.size).put("unstoppable", JSONArray(p.unstoppable.map { g -> g.node.value })) } ?: JSONObject.NULL)
    }

    /** What the app holds in memory and, read back independently from the encrypted file, what is on disk. */
    private fun stateJson(activity: ComponentActivity, s: AppState): JSONObject {
        val persisted: Any = when (val r = EncryptedFileStore(activity).load()) {
            StoreResult.Empty -> JSONObject().put("status", "Empty")
            is StoreResult.Loaded -> savedJson(r.saved).put("status", "Loaded")
            is StoreResult.Corrupt -> JSONObject().put("status", "Corrupt").put("reason", r.reason)
            is StoreResult.Unavailable -> JSONObject().put("status", "Unavailable").put("reason", r.reason)
        }
        return JSONObject()
            .put("windowSecure", (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0)
            .put("store", s.store::class.simpleName)
            .put("stack", JSONArray(s.stack.map { it.toString() }))
            .put("pending", JSONArray(s.pending.map { Review.key(it) }))
            .put("saved", s.saved?.let { savedJson(it) } ?: JSONObject.NULL)
            .put("changes", s.changes?.let { c ->
                JSONObject().put("raw", c.rawChanges).put("items", JSONArray(c.items.map { i ->
                    JSONObject().put("attention", i.attention.name).put("kind", i.kind.name).put("title", i.title)
                }))
            } ?: JSONObject.NULL)
            .put("paths", pathsJson(s))
            .put("persisted", persisted)
    }

    private fun chainJson(chain: List<ByteArray>, reference: ReferenceData): JSONObject {
        val r = ChainVerifier().verify(chain, reference, Instant.now())
        return JSONObject()
            .put("trusted", r.trusted).put("endsAtTrustedRoot", r.endsAtTrustedRoot).put("legacyRoot", r.legacyRoot)
            .put("links", JSONArray(r.links.map {
                JSONObject().put("index", it.index).put("subject", it.subject).put("serial", it.serialDecimal)
                    .put("signatureOk", nullable(it.signatureOk)).put("validNow", it.validNow)
            }))
            .put("revoked", JSONArray(r.revoked.map { it.first })).put("problems", JSONArray(r.problems()))
            .put("attestationError", nullable(r.attestationError))
            .put("attestation", r.attestation?.let { a ->
                JSONObject().put("securityLevel", a.attestationSecurityLevel).put("keyMintSecurityLevel", a.keyMintSecurityLevel)
                    .put("challengeHex", a.challengeHex).put("bootState", nullable(a.verifiedBootState)).put("locked", nullable(a.deviceLocked))
                    .put("osPatchLevel", nullable(a.osPatchLevel)).put("vendorPatchLevel", nullable(a.vendorPatchLevel))
                    .put("attestationVersion", a.attestationVersion)
            } ?: JSONObject.NULL)
    }

    /**
     * The attestation chain and what the product's verifier says about it in four situations, so the lab can check each
     * against an independent verifier: as it is, with one signature byte flipped, trusting only the chain's own top
     * certificate, and the flipped chain against that same anchor.
     */
    private fun integrity(activity: ComponentActivity): Any {
        val evidence = AndroidAttestationSource().read()
        val reference = AndroidReferenceSource(activity).read()
        if (evidence !is ReadResult.Ok || reference !is ReadResult.Ok) return JSONObject().put("evidence", evidence.toString()).put("reference", reference.toString())
        val chain = evidence.value.chainDer
        val tampered = chain.mapIndexed { i, der -> if (i == 0) der.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() } else der }
        val selfAnchored = ReferenceData(listOf(chain.last()), reference.value.revocation, reference.value.retrievedOn)
        return JSONObject()
            .put("challengeHex", hexOf(evidence.value.challenge))
            .put("chainBase64", JSONArray(chain.map { Base64.encodeToString(it, Base64.NO_WRAP) }))
            .put("tamperedLeafBase64", Base64.encodeToString(tampered[0], Base64.NO_WRAP))
            .put("referenceRetrievedOn", nullable(reference.value.retrievedOn?.toString()))
            .put("referenceRoots", reference.value.roots.size)
            .put("referenceRevocations", reference.value.revocation.size)
            .put("real", chainJson(chain, reference.value))
            .put("tampered", chainJson(tampered, reference.value))
            .put("selfAnchored", chainJson(chain, selfAnchored))
            .put("selfAnchoredTampered", chainJson(tampered, selfAnchored))
    }

    private fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** What the platform itself hands this app about the network, for working out why a reading differs from the system's (debug only). */
    @Suppress("DEPRECATION")
    private fun networkDiag(activity: ComponentActivity): JSONObject {
        val o = JSONObject().put("sdk", android.os.Build.VERSION.SDK_INT)
        try {
            val cm = activity.getSystemService(android.net.ConnectivityManager::class.java)
            o.put("activeNetwork", cm.activeNetwork?.toString() ?: JSONObject.NULL)
            o.put("defaultProxy", cm.defaultProxy?.toString() ?: JSONObject.NULL)
            o.put("activeNetworkInfo", cm.activeNetworkInfo?.toString() ?: JSONObject.NULL)
            o.put("allNetworks", JSONArray(cm.allNetworks.map { n ->
                JSONObject().put("net", n.toString()).put("caps", cm.getNetworkCapabilities(n)?.toString() ?: JSONObject.NULL)
                    .put("proxy", cm.getLinkProperties(n)?.httpProxy?.toString() ?: JSONObject.NULL)
            }))
        } catch (e: Exception) {
            o.put("error", e.javaClass.name + ": " + e.message)
        }
        return o
    }

    private fun dump(activity: ComponentActivity, report: Report, runId: String): JSONObject = JSONObject()
        .put("runId", runId)
        .put("networkDiag", networkDiag(activity))
        .put("api", report.androidApi)
        .put("takenAt", report.takenAt.toString())
        .put("signals", JSONArray(report.signals.map {
            val (state, reason) = when (val a = it.availability) {
                Availability.Available -> "AVAILABLE" to ""
                is Availability.Degraded -> "DEGRADED" to a.reason
                is Availability.Unavailable -> "UNAVAILABLE" to a.reason
            }
            JSONObject().put("id", it.id.value).put("availability", state).put("reason", reason)
        }))
        .put("holders", JSONArray(report.holders.map {
            JSONObject().put("kind", it.kind.name).put("key", it.key).put("pkg", it.pkg).put("label", it.label)
                .put("subLabel", nullable(it.subLabel)).put("active", it.active)
                .put("tier", it.tier.tier.name).put("tierConfidence", it.tier.confidence.name)
                .put("powers", JSONArray(it.powers.powers.map { p -> p.key })).put("unrecognizedBits", it.powers.unrecognizedBits)
                .put("findingId", nullable(it.findingId))
        }))
        .put("findings", JSONArray(report.findings.map {
            JSONObject().put("id", it.id).put("attention", it.attention.name).put("confidence", it.confidence.name)
                .put("subject", it.subject)
                .put("claim", observations(it.claim)).put("context", observations(it.context))
                .put("cannotTellCount", it.whatItCannotTell.size).put("actionCount", it.actions.size)
                .put("settings", it.settings.target.name)
        }))
        .put("apps", JSONArray(report.apps.map {
            JSONObject().put("pkg", it.pkg).put("tier", it.tier.tier.name).put("hasLauncherIcon", it.hasLauncherIcon)
                .put("kinds", JSONArray(it.kinds.map { k -> k.name }.sorted())).put("grants", JSONArray(it.grants))
        }))
        .put("facts", JSONArray(report.facts.map {
            val availability = when (it.availability) {
                Availability.Available -> "AVAILABLE"
                is Availability.Degraded -> "DEGRADED"
                is Availability.Unavailable -> "UNAVAILABLE"
            }
            JSONObject().put("kind", it.kind.name).put("summary", it.summary).put("availability", availability)
        }))
        .put("raw", rawReadings(activity))

    private fun observations(list: List<Observation>) = JSONArray(list.map {
        JSONObject().put("id", it.id.value).put("provenance", it.provenance.name).put("confidence", it.confidence.name)
            .put("value", when (val v = it.value) {
                is Value.Flag -> v.on
                is Value.Text -> v.text
                is Value.Count -> v.n
                is Value.Items -> JSONArray(v.items)
            })
            .put("source", it.source.api).put("minApi", it.source.minApi).put("limitCount", it.limits.size)
    })
}
