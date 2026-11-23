package net.prototux.aare.media

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import android.util.LruCache
import androidx.annotation.RequiresApi
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Loads artwork and icons off the main thread, downscaled, with a small memory cache.
 *
 * Software bitmaps on purpose: art may be re-encoded for the head unit's media status, and
 * hardware bitmaps cannot be read back.
 */
internal class ArtLoader(private val context: Context) {
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun cached(key: String?): Bitmap? = key?.let { cache.get(it) }

    /**
     * Artwork from embedded bytes (preferred) or a content/resource/https URI. [owner] is the package
     * that supplied the URI: we open it with our own permissions, so a content or resource URI is
     * only followed when it is [owner]'s (see [allowed]).
     */
    suspend fun art(uri: Uri?, data: ByteArray?, owner: String?, maxPx: Int = MediaKeys.ART_SIZE_PX): Bitmap? {
        val key = artKey(uri, data, owner, maxPx) ?: return null
        cache.get(key)?.let { return it }
        val bmp = withContext(artWork) {
            try {
                if (data == null && !allowed(context, uri!!, owner)) {
                    Log.d(TAG, "art uri of $owner not allowed")
                    noArt(owner, uri.scheme, "the address is not one Mate may open")
                    return@withContext null
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    // Android 8: no ImageDecoder.
                    val bytes = data ?: if (uri!!.scheme == "https") download(uri)
                        else context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    bytes?.let { decodeLegacy(it, maxPx) }
                } else when {
                    data != null -> decode(ImageDecoder.createSource(ByteBuffer.wrap(data)), maxPx)
                    uri!!.scheme == "https" ->
                        download(uri)?.let { decode(ImageDecoder.createSource(ByteBuffer.wrap(it)), maxPx) }
                    uri.scheme == ContentResolver.SCHEME_FILE -> decode(ImageDecoder.createSource(java.io.File(uri.path!!)), maxPx)
                    else -> decode(ImageDecoder.createSource(context.contentResolver, uri), maxPx)
                }
            } catch (e: Exception) {
                // other apps' content URIs often need a grant we do not have; no art is fine
                // never the URI itself: it may carry a token
                Log.d(TAG, "no art from ${uri?.host}: ${e.javaClass.simpleName}")
                noArt(owner, uri?.scheme ?: "bytes", e.javaClass.simpleName)
                null
            }
        }
        if (bmp != null) cache.put(key, bmp)
        return bmp
    }

    /** A (usually monochrome) icon: a drawable of another package, ours, or a URI. */
    suspend fun icon(ref: IconRef, sizePx: Int): Bitmap? {
        if (ref.resId == 0) return ref.uri?.let { art(it, null, ref.packageName, sizePx) }
        val key = "i:${ref.packageName}:${ref.resId}:$sizePx"
        cache.get(key)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            try {
                val res = if (ref.packageName == null) context.resources
                else context.packageManager.getResourcesForApplication(ref.packageName)
                ResourcesCompat.getDrawable(res, ref.resId, null)?.toBitmap(sizePx, sizePx)
            } catch (e: Exception) {
                Log.d(TAG, "no icon $ref: $e")
                null
            }
        }
        if (bmp != null) cache.put(key, bmp)
        return bmp
    }

    /** BitmapFactory, scaled down by powers of two then to [maxPx]. */
    private fun decodeLegacy(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = maxPx.toFloat() / max(bmp.width, bmp.height)
        return if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).roundToInt().coerceAtLeast(1), (bmp.height * scale).roundToInt().coerceAtLeast(1), true) else bmp
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decode(source: ImageDecoder.Source, maxPx: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = maxPx.toFloat() / max(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
        }

    private fun download(uri: Uri): ByteArray? {
        val conn = URL(uri.toString()).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_DOWNLOAD) return null
                }
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Why an app's cover could not be had: into the diagnosis log once per app, kind of address and reason - never the address. */
    private fun noArt(owner: String?, scheme: String?, why: String) {
        val key = "$owner|$scheme|$why"
        if (synchronized(said) { said.add(key) }) net.prototux.aare.DiagLog.warn("media art: no cover from ${owner ?: "?"} ($scheme) - $why", "AUDIO")
    }

    companion object {
        private val said = HashSet<String>()
        /**
         * Whether [uri], supplied by [owner], may be opened with our identity. Never file:// (it would
         * read our private files) and never plain http. A content URI must belong to [owner], or be one
         * [owner] granted us access to; our own providers are never followed.
         */
        fun allowed(context: Context, uri: Uri, owner: String?): Boolean = when (uri.scheme) {
            "https" -> true
            ContentResolver.SCHEME_ANDROID_RESOURCE -> owner != null && uri.authority == owner
            ContentResolver.SCHEME_CONTENT -> owner != null && contentAllowed(context, uri, owner)
            // Mate's own files only: the covers of Mate Musik's downloaded titles (19.11.2026: "Er zeigt mir irgendwie keine
            // Coverbilder mehr in der Musik-App an. Ich weiß nicht, ob das an den Downloads liegt" - a file address was
            // refused, so every downloaded title went without its cover)
            ContentResolver.SCHEME_FILE -> owner == context.packageName && ownFile(context, uri)
            else -> false
        }

        /** Whether [uri] is a file inside Mate's own storage (its files or cache), after resolving any ".." and links. */
        internal fun ownFile(context: Context, uri: Uri): Boolean {
            val path = uri.path ?: return false
            val f = runCatching { java.io.File(path).canonicalPath }.getOrNull() ?: return false
            // with Mate Musik's folder on the device and Mate's folders on memory cards (downloads there, System L-086)
            return (listOfNotNull(context.filesDir, context.cacheDir, context.noBackupFilesDir, context.getExternalFilesDir(null)) +
                    context.getExternalFilesDirs(null).filterNotNull() + listOfNotNull(net.prototux.aare.Storage.musicFolder(context)))
                .mapNotNull { runCatching { it.canonicalPath }.getOrNull() }.any { f.startsWith(it + java.io.File.separator) }
        }

        /**
         * Whether [icon], supplied by [owner], may be loaded with our identity. Only URI icons are
         * checked; bitmap, resource and data icons carry no URI to scope and pass.
         * An icon whose type cannot be read fails.
         */
        fun iconAllowed(context: Context, icon: Icon, owner: String?): Boolean {
            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (icon.type != Icon.TYPE_URI && icon.type != Icon.TYPE_URI_ADAPTIVE_BITMAP) return true
                icon.uri
            } else {
                // Android 8: getType()/getUri() are hidden. IconCompat would report an unreadable type
                // as TYPE_UNKNOWN, like a bitmap, so read them ourselves and refuse if we cannot.
                val type = runCatching { Icon::class.java.getMethod("getType").invoke(icon) as Int }.getOrNull()
                    ?: return false.also { Log.d(TAG, "icon type of $owner unreadable, not loading") }
                if (type != IconCompat.TYPE_URI && type != IconCompat.TYPE_URI_ADAPTIVE_BITMAP) return true
                runCatching { Icon::class.java.getMethod("getUri").invoke(icon) as Uri }.getOrNull()
                    ?: return false.also { Log.d(TAG, "icon uri of $owner unreadable, not loading") }
            }
            return allowed(context, uri, owner)
        }

        private fun contentAllowed(context: Context, uri: Uri, owner: String): Boolean {
            // an authority may carry a user id prefix ("10@com.example.provider")
            val authority = uri.authority?.substringAfterLast('@') ?: return false
            val info = context.packageManager.resolveContentProvider(authority, 0) ?: return false
            if (info.packageName == context.packageName) return false
            if (info.packageName == owner) return true
            // someone else's provider: only on an explicit grant, not on a permission we hold ourselves
            val perm = info.readPermission
            if (perm != null && context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) return false
            return context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        }

        private const val TAG = "AareArt"
        /**
         * A screen full of tiles asks for all its pictures at once. Few at a time: fetching and decoding dozens
         * together takes the processor from the video encoder and the player, and the car screen stutters.
         */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private val artWork = Dispatchers.IO.limitedParallelism(3)

        private const val CACHE_BYTES = 32 * 1024 * 1024
        private const val MAX_DOWNLOAD = 10 * 1024 * 1024

        /** Keyed by [owner] too: whether a URI may be opened depends on who supplied it. */
        fun artKey(uri: Uri?, data: ByteArray?, owner: String?, maxPx: Int): String? = when {
            data != null -> "d:${data.size}:${data.contentHashCode()}:$maxPx"
            uri != null && uri.scheme in SCHEMES -> "u:$owner:$uri:$maxPx"
            else -> null
        }

        private val SCHEMES = setOf(
            ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_ANDROID_RESOURCE, "https",
        )
    }
}
