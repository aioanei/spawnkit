package dev.spawnkit.app.release

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

@Composable
actual fun rememberReleasePlatform(): ReleasePlatform {
    val context = LocalContext.current.applicationContext
    var pending by remember { mutableStateOf<CancellableContinuation<Uri?>?>(null) }
    val create =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val request = pending
            pending = null
            if (request?.isActive == true) request.resume(uri)
        }
    return remember(context, create) {
        object : ReleasePlatform {
            override val available = true

            override suspend fun exportWeb(
                suggestedName: String,
                packageJson: String,
            ): Boolean =
                withContext(Dispatchers.IO) {
                    val temp = File.createTempFile("spawnkit-web-", ".zip", context.cacheDir)
                    try {
                        openPlayerBundle().use { bundle ->
                            temp.outputStream().use { writeWebArchive(bundle, packageJson, it) }
                        }
                        val uri =
                            withContext(Dispatchers.Main.immediate) {
                                suspendCancellableCoroutine<Uri?> { result ->
                                    check(pending == null) { "Finish choosing a ZIP file in the open save window." }
                                    pending = result
                                    try {
                                        create.launch(archiveName(suggestedName))
                                    } catch (problem: Exception) {
                                        pending = null
                                        throw IllegalStateException("Unable to open the system file picker. Try exporting again.", problem)
                                    }
                                }
                            } ?: return@withContext false
                        val file =
                            checkNotNull(context.contentResolver.openFileDescriptor(uri, "rwt")) {
                                "The selected file cannot be written. Choose another location."
                            }
                        file.use { descriptor ->
                            val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
                            try {
                                temp.inputStream().use { it.copyCancellableTo(output) }
                                output.close()
                            } catch (problem: Exception) {
                                runCatching { descriptor.closeWithError("Export interrupted") }
                                throw problem
                            }
                        }
                        true
                    } finally {
                        temp.delete()
                    }
                }

            override suspend fun publish(
                arcadeUrl: String,
                packageJson: String,
            ): String = publishGame(arcadeUrl, packageJson)
        }
    }
}
