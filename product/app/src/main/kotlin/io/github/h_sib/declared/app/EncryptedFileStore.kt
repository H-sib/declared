package io.github.h_sib.declared.app

import android.content.Context
import io.github.h_sib.declared.core.ledger.Ledger
import io.github.h_sib.declared.core.ledger.LedgerCodec
import io.github.h_sib.declared.core.ledger.LedgerLoad
import io.github.h_sib.declared.core.ledger.LedgerStore
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.state.SavedState
import io.github.h_sib.declared.core.state.SealedFile
import io.github.h_sib.declared.core.state.StateCodec
import io.github.h_sib.declared.core.state.StateStore
import io.github.h_sib.declared.core.state.StoreResult

/**
 * The baseline, kept encrypted at rest (DESIGN.md section 9). Backup is switched off in the manifest, and a restored copy of the
 * file would be useless without the key, which is reported as such rather than silently starting over.
 */
class EncryptedFileStore(context: Context, name: String = "declared-state.bin", alias: String = "declared_state_key") : StateStore {

    private val bytes = EncryptedBytes(context, name, alias, SealedFile.PURPOSE, "saved baseline")

    override fun load(): StoreResult = when (val read = bytes.read()) {
        SealedRead.Empty -> StoreResult.Empty
        is SealedRead.Corrupt -> StoreResult.Corrupt(read.reason)
        is SealedRead.Unavailable -> StoreResult.Unavailable(read.reason)
        is SealedRead.Loaded -> when (val decoded = StateCodec.decode(read.plain.toString(Charsets.UTF_8))) {
            is ReadResult.Ok -> StoreResult.Loaded(decoded.value)
            is ReadResult.Refused -> StoreResult.Corrupt(decoded.reason)
            is ReadResult.Failed -> StoreResult.Corrupt(decoded.reason)
        }
    }

    override fun save(saved: SavedState): ReadResult<Unit> = bytes.write(StateCodec.encode(saved).toByteArray(Charsets.UTF_8))
}

/**
 * The history (STAGE3_DESIGN.md section 3), in a file of its own under its own key and purpose, so a damaged history never
 * touches the baseline and neither file can be passed off as the other.
 */
class EncryptedLedgerStore(context: Context, name: String = "declared-history.bin", alias: String = "declared_history_key") : LedgerStore {

    private val bytes = EncryptedBytes(context, name, alias, SealedFile.PURPOSE_LEDGER, "history")

    override fun load(): LedgerLoad = when (val read = bytes.read()) {
        SealedRead.Empty -> LedgerLoad.Empty
        is SealedRead.Corrupt -> LedgerLoad.Corrupt(read.reason)
        is SealedRead.Unavailable -> LedgerLoad.Unavailable(read.reason)
        is SealedRead.Loaded -> when (val decoded = LedgerCodec.decode(read.plain.toString(Charsets.UTF_8))) {
            is ReadResult.Ok -> LedgerLoad.Loaded(decoded.value)
            is ReadResult.Refused -> LedgerLoad.Corrupt(decoded.reason)
            is ReadResult.Failed -> LedgerLoad.Corrupt(decoded.reason)
        }
    }

    override fun save(ledger: Ledger): ReadResult<Unit> = bytes.write(LedgerCodec.encode(ledger).toByteArray(Charsets.UTF_8))

    fun moveAside(): Boolean = bytes.moveAside()
}
