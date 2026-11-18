package it.vault.custode

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppVm : ViewModel() {
    lateinit var store: VaultStore
    var unlocked by mutableStateOf(false)
    var version by mutableIntStateOf(0)            // forza il refresh delle liste dopo una modifica
    var busy by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var bioEnabled by mutableStateOf(false)
    val groupStack = mutableStateListOf<String>()  // percorso di gruppi (UUID) sotto la radice
    var selectedEntry by mutableStateOf<String?>(null)
    var keyFile by mutableStateOf<ByteArray?>(null)
    var keyFileName by mutableStateOf<String?>(null)
    var pendingImport by mutableStateOf<ByteArray?>(null)
    var autoPrompted = false
    var keepUnlocked = false                       // true mentre è aperto un selettore file di sistema

    fun init(ctx: Context) {
        if (!::store.isInitialized) store = VaultStore.get(ctx)
        // Il database può essere già sbloccato (stesso processo, es. dopo la compilazione automatica).
        if (store.db != null && !unlocked) unlocked = true
    }

    fun lock() {
        if (::store.isInitialized) store.lock()
        unlocked = false; groupStack.clear(); selectedEntry = null
        keyFile = null; keyFileName = null; autoPrompted = false; version++
    }

    /** Esegue un'operazione lenta (Argon2) fuori dal thread UI, con indicatore di attesa. */
    fun <T> work(msg: String, block: () -> T, onOk: (T) -> Unit = {}) {
        busy = msg
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) { runCatching(block) }
            busy = null
            r.onSuccess(onOk).onFailure { message = VaultStore.describe(it) }
        }
    }

    /** Salva subito (rapido: la KDF è già stata eseguita all'apertura). */
    fun persist() {
        try { store.save(); version++ } catch (e: Exception) { message = "Salvataggio non riuscito: ${e.message}" }
    }
}

class Actions(
    val copy: (String, String) -> Unit,
    val bioAvailable: Boolean,
    val unlockBio: () -> Unit,
    val setBio: (Boolean) -> Unit,
    val export: () -> Unit,
    val pickDatabase: (() -> Unit)?,
    val pickKeyFile: () -> Unit,
    val saveFile: (String, ByteArray) -> Unit,
    val pickAttachment: ((String, ByteArray) -> Unit) -> Unit,
    val autofillEnabled: () -> Boolean,
    val enableAutofill: () -> Unit,
    val settings: Settings,
)

class MainActivity : BaseVaultActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val lockTask = Runnable { vm.lock() }
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val actions = buildActions(allowImport = true)
        setContent { CustodeTheme { App(vm, actions) { MainScreen(vm, actions) } } }
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(lockTask)
        // Se il telefono ha "congelato" l'app, il timer può non essere partito: si controlla il tempo trascorso.
        val d = settings.lockDelaySec
        if (stoppedAt != 0L && vm.unlocked && d > 0 && SystemClock.elapsedRealtime() - stoppedAt >= d * 1000L) vm.lock()
        stoppedAt = 0L
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations || vm.keepUnlocked || vm.busy != null) return
        val d = settings.lockDelaySec
        if (d <= 0) vm.lock() else { stoppedAt = SystemClock.elapsedRealtime(); handler.postDelayed(lockTask, d * 1000L) }
    }
}
