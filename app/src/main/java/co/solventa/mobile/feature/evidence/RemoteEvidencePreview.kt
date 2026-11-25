package co.solventa.mobile.feature.evidence

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun RemoteEvidencePreview(scope: EvidenceScope, id: String, load: suspend (EvidenceScope,String,File) -> String, close: () -> Unit) = key(scope,id) {
    val context = LocalContext.current
    var file by remember { mutableStateOf<File?>(null) }
    var mime by remember { mutableStateOf("") }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(scope,id) {
        val temp = File.createTempFile("solventa-original-", ".tmp", context.cacheDir)
        try {
            mime = load(scope,id,temp)
            if (mime.startsWith("image/")) bitmap = withContext(Dispatchers.IO) {
                EvidenceMedia(context.cacheDir).image(temp,1600, allowPng = true)
            }
            file = temp
            kotlinx.coroutines.awaitCancellation()
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: java.io.IOException) { failed=true }
        catch (_: co.solventa.mobile.feature.auth.AuthProblem) { failed=true }
        catch (_: co.solventa.mobile.feature.auth.AuthHttpError) { failed=true }
        catch (_: IllegalArgumentException) { failed=true }
        finally { temp.delete() }
    }
    AlertDialog(onDismissRequest=close,title={Text(stringResource(when (mime) {
        "image/jpeg", "image/png" -> R.string.evidence_photo_title
        "video/mp4" -> R.string.evidence_video_title
        "application/pdf" -> R.string.evidence_pdf
        else -> R.string.evidence_original
    }))},text={
        val original=file
        when {
            failed -> Text(stringResource(R.string.evidence_preview_unavailable))
            original == null -> CircularProgressIndicator()
            mime == "application/pdf" -> Column { PdfPreview(original) }
            mime == "video/mp4" -> EvidenceVideoPreview(original)
            else -> bitmap?.let { Image(it.asImageBitmap(),stringResource(R.string.evidence_original),Modifier.fillMaxWidth().heightIn(max=360.dp)) }
        }
    }, confirmButton={TextButton(onClick=close){Text(stringResource(R.string.evidence_gallery_close))}})
}
