package it.vault.custode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher

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

    fun init(dir: File) { if (!::store.isInitialized) store = VaultStore(dir) }

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
    val pickDatabase: () -> Unit,
    val pickKeyFile: () -> Unit,
)

class MainActivity : FragmentActivity() {
    private val vm: AppVm by viewModels()
    private lateinit var bio: BioUnlock

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        vm.keepUnlocked = false
        if (uri != null) {
            val ok = runCatching { contentResolver.openOutputStream(uri)?.use { vm.store.exportTo(it) } ?: error("") }.isSuccess
            toast(if (ok) "Backup cifrato salvato" else "Esportazione non riuscita")
        }
    }
    private val dbLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        vm.keepUnlocked = false
        readSmall(uri, 50_000_000)?.let { vm.pendingImport = it }
    }
    private val keyLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        vm.keepUnlocked = false
        readSmall(uri, 5_000_000)?.let { vm.keyFile = it; vm.keyFileName = displayName(uri!!) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE) // niente screenshot
        vm.init(filesDir)
        bio = BioUnlock(this)
        vm.bioEnabled = bio.enabled && bio.available()
        val actions = Actions(
            copy = ::copySecret,
            bioAvailable = bio.available(),
            unlockBio = ::unlockWithBio,
            setBio = ::setBio,
            export = {
                vm.keepUnlocked = true
                exportLauncher.launch("custode-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".kdbx")
            },
            pickDatabase = { vm.keepUnlocked = true; dbLauncher.launch(arrayOf("*/*")) },
            pickKeyFile = { vm.keepUnlocked = true; keyLauncher.launch(arrayOf("*/*")) },
        )
        setContent { CustodeTheme { App(vm, actions) } }
    }

    override fun onResume() {
        super.onResume()
        if (!vm.unlocked && vm.store.exists && vm.bioEnabled && !vm.autoPrompted && vm.busy == null) {
            vm.autoPrompted = true
            unlockWithBio()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !vm.keepUnlocked && vm.busy == null) vm.lock() // blocco automatico
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun readSmall(uri: Uri?, max: Int): ByteArray? {
        if (uri == null) return null
        return try {
            val b = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            if (b.size > max) { toast("File troppo grande"); null } else b
        } catch (e: Exception) { toast("File non leggibile"); null }
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: "file chiave"

    // ---- Impronta digitale ----

    private fun prompt(title: String, cipher: Cipher, onOk: (Cipher) -> Unit) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText("Usa la password")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                result.cryptoObject?.cipher?.let(onOk)
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                if (code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                    code != BiometricPrompt.ERROR_CANCELED) toast(msg.toString())
            }
        }).authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun setBio(on: Boolean) {
        if (!on) { bio.disable(); vm.bioEnabled = false; return }
        val db = vm.store.db ?: return
        try {
            val cipher = bio.encryptCipher()
            prompt("Attiva lo sblocco con impronta", cipher) { c ->
                val key = db.compositeKey()
                try { bio.store(c, key); vm.bioEnabled = true; toast("Sblocco con impronta attivato") }
                catch (e: Exception) { bio.disable(); toast("Attivazione non riuscita") }
                finally { key.fill(0) }
            }
        } catch (e: Exception) { bio.disable(); toast("Impronta non disponibile su questo telefono") }
    }

    private fun unlockWithBio() {
        val cipher = try { bio.decryptCipher() }
        catch (e: KeyPermanentlyInvalidatedException) {
            bio.disable(); vm.bioEnabled = false
            toast("Le impronte sono cambiate: usa la password e riattiva lo sblocco"); return
        } catch (e: Exception) { toast("Sblocco con impronta non disponibile: usa la password"); return }
        prompt("Sblocca Custode", cipher) { c ->
            val key = runCatching { bio.read(c) }.getOrNull()
            if (key == null) { toast("Sblocco non riuscito: usa la password"); return@prompt }
            vm.work("Sblocco…", { try { vm.store.openWith(key) } finally { key.fill(0) } }) { vm.unlocked = true; vm.version++ }
        }
    }

    // ---- Appunti: contrassegnati come sensibili e cancellati dopo 30 secondi ----

    private fun copySecret(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text).apply {
            description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        cm.setPrimaryClip(clip)
        Handler(Looper.getMainLooper()).postDelayed({
            if (cm.primaryClip?.getItemAt(0)?.text?.toString() == text) {
                if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }, 30_000)
    }
}
