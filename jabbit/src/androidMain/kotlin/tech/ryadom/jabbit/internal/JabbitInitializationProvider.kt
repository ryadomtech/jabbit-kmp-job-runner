package tech.ryadom.jabbit.internal

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Captures the application context before any application code runs, so `jabbit { }` can be called
 * from common code without being handed a `Context`.
 *
 * The system instantiates it from the library manifest; applications never touch it.
 */
public class JabbitInitializationProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.applicationContext?.let(JabbitRuntime::installContext)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
