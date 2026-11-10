package co.solventa.mobile.mock

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import java.io.File
import java.net.InetAddress
import okhttp3.mockwebserver.MockWebServer

class MockInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = checkNotNull(context)
        backend = MockBackend(File(app.noBackupFilesDir, "mock-server.json"))
        server = MockWebServer().apply {
            dispatcher = backend
            start(InetAddress.getByName("127.0.0.1"), 18725)
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only provider")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only provider")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only provider")

    companion object {
        internal lateinit var backend: MockBackend
        private lateinit var server: MockWebServer
    }
}
