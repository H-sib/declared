package lab.fixture

import android.inputmethodservice.InputMethodService
import android.view.View

/** An input method with an empty view. It never receives or stores any typed text. */
class FixtureIme : InputMethodService() {
    override fun onCreateInputView(): View = View(this)
}
