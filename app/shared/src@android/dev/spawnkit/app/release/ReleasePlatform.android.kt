package dev.spawnkit.app.release

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
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
            if (request?.isActive == true) {
                request.resume(uri)
            } else if (uri != null) {
                // The export that asked for this file is gone (for example after process death), so remove the empty
                // document the picker created rather than leave a broken ZIP behind.
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            }
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
                                    result.invokeOnCancellation { if (pending === result) pending = null }
                                    try {
                                        create.launch(archiveName(suggestedName))
                                    } catch (problem: Exception) {
                                        pending = null
                                        throw IllegalStateException("Unable to open the system file picker. Try exporting again.", problem)
                                    }
                                }
                            } ?: return@withContext false
                        // "wt" (write, truncate) is the mode every documents provider supports, including cloud drives.
                        try {
                            val output =
                                checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) {
                                    "The selected file cannot be written. Choose another location."
                                }
                            output.use { stream -> temp.inputStream().use { it.copyCancellableTo(stream) } }
                        } catch (problem: Exception) {
                            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                            if (problem is IllegalStateException || problem is CancellationException) throw problem
                            throw IllegalStateException("The selected location could not save the ZIP. Choose another location.", problem)
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
