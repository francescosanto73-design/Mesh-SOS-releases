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
import android.view.autofill.AutofillManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher

/** Parti in comune tra la schermata principale e quella di compilazione automatica. */
abstract class BaseVaultActivity : FragmentActivity() {
    protected val vm: AppVm by viewModels()
    protected lateinit var bio: BioUnlock
    protected lateinit var settings: Settings
    private var pendingSave: ByteArray? = null
    private var attachmentCallback: ((String, ByteArray) -> Unit)? = null

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        vm.keepUnlocked = false
        if (uri != null) {
            val ok = runCatching { contentResolver.openOutputStream(uri)?.use { vm.store.exportTo(it) } ?: error("") }.isSuccess
            toast(if (ok) "Backup cifrato salvato" else "Esportazione non riuscita")
        }
    }
    private val saveFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        vm.keepUnlocked = false
        val data = pendingSave; pendingSave = null
        if (uri != null && data != null) {
            val ok = runCatching { contentResolver.openOutputStream(uri)?.use { it.write(data) } ?: error("") }.isSuccess
            toast(if (ok) "Allegato salvato" else "Salvataggio non riuscito")
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
    private val attachLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        vm.keepUnlocked = false
        val cb = attachmentCallback; attachmentCallback = null
        if (uri != null && cb != null) readSmall(uri, 5_000_000)?.let { cb(displayName(uri), it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE) // niente screenshot
        vm.init(this)
        bio = BioUnlock(this)
        settings = Settings(this)
        vm.bioEnabled = bio.enabled && bio.available()
    }

    protected fun buildActions(allowImport: Boolean) = Actions(
        copy = ::copySecret,
        bioAvailable = bio.available(),
        unlockBio = ::unlockWithBio,
        setBio = ::setBio,
        export = {
            vm.keepUnlocked = true
            exportLauncher.launch("custode-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".kdbx")
        },
        pickDatabase = if (allowImport) ({ vm.keepUnlocked = true; dbLauncher.launch(arrayOf("*/*")) }) else null,
        pickKeyFile = { vm.keepUnlocked = true; keyLauncher.launch(arrayOf("*/*")) },
        saveFile = { name, data -> pendingSave = data; vm.keepUnlocked = true; saveFileLauncher.launch(name) },
        pickAttachment = { cb -> attachmentCallback = cb; vm.keepUnlocked = true; attachLauncher.launch(arrayOf("*/*")) },
        autofillEnabled = { runCatching { getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true }.getOrDefault(false) },
        enableAutofill = ::enableAutofill,
        settings = settings,
    )

    override fun onResume() {
        super.onResume()
        if (!vm.unlocked && vm.store.exists && vm.bioEnabled && !vm.autoPrompted && vm.busy == null) {
            vm.autoPrompted = true
            unlockWithBio()
        }
    }

    protected fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

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
    }.getOrNull() ?: "file"

    private fun enableAutofill() {
        val afm = getSystemService(AutofillManager::class.java)
        if (afm == null || !afm.isAutofillSupported) { toast("Questo telefono non supporta la compilazione automatica"); return }
        if (afm.hasEnabledAutofillServices()) { toast("La compilazione automatica con Custode è già attiva"); return }
        try {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:$packageName")))
        } catch (e: Exception) { toast("Apri Impostazioni → Password e account → Servizio di compilazione automatica e scegli Custode") }
    }

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

    protected fun unlockWithBio() {
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

    // ---- Appunti: contrassegnati come sensibili e cancellati dopo il tempo scelto ----

    private fun copySecret(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text).apply {
            description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        cm.setPrimaryClip(clip)
        val sec = settings.clipboardSec
        if (sec > 0) Handler(Looper.getMainLooper()).postDelayed({
            if (cm.primaryClip?.getItemAt(0)?.text?.toString() == text) {
                if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }, sec * 1000L)
    }
}
