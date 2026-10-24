package app.ray8

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.flow.MutableStateFlow

/** Text that arrived from outside (a link tapped in a browser, "share to 8ray") and waits for the user to confirm. */
object Pending {
    val text = MutableStateFlow<String?>(null)
}

object Util {
    fun copy(ctx: Context, label: String, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    fun paste(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
    }

    fun share(ctx: Context, text: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()

    /** Black-on-white QR code (white margin included) so every scanner can read it. */
    fun qr(text: String, size: Int = 640): Bitmap? = try {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8"))
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    } catch (_: Exception) {
        null
    }
}

/** Turns pasted text into configs and subscriptions. Returns a message for the user. */
object Importer {
    suspend fun importText(ctx: Context, text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return ctx.getString(R.string.import_nothing)
        var links = Links.extract(t)
        if (links.isEmpty()) Links.jsonToLink(t)?.let { links = listOf(it) }
        val urls = t.split(Regex("\\s+")).filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
        val parts = ArrayList<String>()
        if (links.isNotEmpty()) {
            val r = NodeStore.addLinks(links)
            parts.add(ctx.getString(R.string.import_result, r.added, r.duplicates, r.bad))
            if (r.errors.isNotEmpty() && r.added == 0) parts.add(r.errors.first())
        } else if (urls.isNotEmpty()) {
            for (u in urls.take(5)) {
                val sub = NodeStore.addSub(u)
                val r = Subs.update(sub.id)
                parts.add(if (r.error == null) ctx.getString(R.string.sub_updated, NodeStore.sub(sub.id)?.name ?: u, r.added)
                else ctx.getString(R.string.sub_failed, r.error))
            }
        }
        return if (parts.isEmpty()) ctx.getString(R.string.import_nothing) else parts.joinToString("\n")
    }
}
