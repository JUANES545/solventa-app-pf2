package co.solventa.mobile.feature.evidence

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class Thumbnail(val bitmap: Bitmap? = null, val failed: Boolean = false)

@Composable
internal fun EvidenceThumbnail(scope: EvidenceScope?, item: EvidenceItem? = null, modifier: Modifier = Modifier) {
    key(scope, item) { ScopedThumbnail(scope, item, modifier) }
}

@Composable
private fun ScopedThumbnail(scope: EvidenceScope?, item: EvidenceItem?, modifier: Modifier) {
    val context = LocalContext.current
    val thumbnail by produceState(Thumbnail(), scope, item) {
        value = Thumbnail()
        if (scope != null) {
            val loaded = withContext(Dispatchers.IO) {
                try {
                EvidenceStore(context.noBackupFilesDir).use { store ->
                    val photo = if (item != null) item.takeIf { it.scope == scope }
                        else store.list(scope).lastOrNull { it.kind != EvidenceKind.VIDEO && it.status != EvidenceStatus.REJECTED }
                    if (photo == null) Thumbnail() else {
                        val file = store.file(photo)
                        Thumbnail(if (photo.kind == EvidenceKind.VIDEO) {
                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(file.path)
                                if (android.os.Build.VERSION.SDK_INT >= 27)
                                    retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 480)
                                else retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            } finally { retriever.release() }
                        } else EvidenceMedia(context.cacheDir).image(file, 640))
                    }
                }
            } catch (_: EvidenceProblem) {
                Log.e("Evidence", "Thumbnail validation failed")
                Thumbnail(failed = true)
            } catch (_: IOException) {
                Log.e("Evidence", "Thumbnail unavailable")
                Thumbnail(failed = true)
            } catch (_: android.database.sqlite.SQLiteException) {
                Log.e("Evidence", "Thumbnail storage unavailable")
                Thumbnail(failed = true)
            } catch (_: SecurityException) {
                Log.e("Evidence", "Thumbnail access denied")
                Thumbnail(failed = true)
            } catch (_: RuntimeException) {
                Thumbnail(failed = true)
            }
            }
            withContext(Dispatchers.Main.immediate) { value = loaded }
        }
    }
    val image = thumbnail.bitmap
    androidx.compose.runtime.DisposableEffect(image) {
        onDispose { image?.recycle() }
    }
    Box(modifier.fillMaxWidth().height(144.dp), contentAlignment = Alignment.Center) {
        val bitmap = thumbnail.bitmap
        when {
            bitmap != null -> Image(bitmap.asImageBitmap(), stringResource(R.string.evidence_local_preview),
                Modifier.matchParentSize().testTag("evidence-thumbnail"), contentScale = ContentScale.Crop)
            thumbnail.failed -> Text(stringResource(R.string.evidence_preview_unavailable),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            item != null -> Text(stringResource(if (item.kind == EvidenceKind.VIDEO)
                R.string.evidence_kind_video else R.string.evidence_kind_photo),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Image(painterResource(R.drawable.claim_vehicle), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        }
    }
}
