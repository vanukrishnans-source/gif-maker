package com.vanu.gifmaker

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import com.vanu.gifmaker.ui.GifMakerTheme
import com.vanu.gifmaker.ui.MainScreen

class MainActivity : ComponentActivity() {

    private val vm: GifViewModel by viewModels()

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.openVideo(uri)
    }

    private val pickDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) {}
            vm.openVideo(uri)
        }
    }

    private val writePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.save()
        else Toast.makeText(this, "Storage permission is needed to save to Pictures", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GifMakerTheme {
                Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    MainScreen(
                        vm = vm,
                        onPick = ::pick,
                        onSave = ::requestSave,
                        onShare = ::share,
                        onOpen = ::openSaved,
                    )
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun pick() {
        try {
            if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this)) {
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            } else {
                pickDocument.launch(arrayOf("video/*"))
            }
        } catch (_: ActivityNotFoundException) {
            pickDocument.launch(arrayOf("video/*"))
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // Debug-build-only automation hook (stripped from release logic by BuildConfig.DEBUG).
        if (BuildConfig.DEBUG) {
            intent.getStringExtra("test_file")?.let { name ->
                vm.autoCreateAfterProbe = intent.getBooleanExtra("autostart", false)
                vm.autoSaveAfterCreate = intent.getBooleanExtra("autosave", false)
                if (intent.hasExtra("start_ms")) vm.testRange = intent.getLongExtra("start_ms", 0) to intent.getLongExtra("end_ms", 5000)
                vm.openVideo(Uri.fromFile(java.io.File(filesDir, name)))
                return
            }
        }
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) vm.openVideo(uri)
    }

    private fun requestSave() {
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else vm.save()
    }

    private fun share() {
        val st = vm.state.value
        val res = st.result ?: return
        val uri = st.savedUri ?: FileProvider.getUriForFile(this, "$packageName.files", res.file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/gif"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(res.file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, "Share GIF"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app to share with", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSaved() {
        val uri = vm.state.value.savedUri ?: return
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/gif")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(view) } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show()
        }
    }
}
