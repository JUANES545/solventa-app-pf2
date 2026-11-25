package co.solventa.mobile.feature.evidence

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import androidx.compose.runtime.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import co.solventa.mobile.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PdfPreview(file: File) = key(file) {
    var index by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Pair<Bitmap,Int>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(index) {
        try { preview = withContext(Dispatchers.IO) { pdfPage(file,index) } }
        catch (_: java.io.IOException) { failed = true }
        catch (_: SecurityException) { failed = true }
        catch (_: IllegalArgumentException) { failed = true }
    }
    if (failed) Text(stringResource(R.string.evidence_preview_unavailable))
    else preview?.let { (image,count) ->
        DisposableEffect(image) { onDispose { image.recycle() } }
        Image(image.asImageBitmap(), stringResource(R.string.evidence_pages,index+1,count))
        Row {
            TextButton(enabled=index>0,onClick={index--}) { Text(stringResource(R.string.evidence_previous)) }
            TextButton(enabled=index+1<count,onClick={index++}) { Text(stringResource(R.string.evidence_next)) }
        }
    }
}

internal fun pdfPage(file: File, index: Int): Pair<Bitmap,Int> =
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { pdf ->
            require(index in 0 until pdf.pageCount)
            pdf.openPage(index).use { page ->
                val scale = 1200.0 / maxOf(page.width, page.height)
                val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap to pdf.pageCount
            }
        }
    }
