package cl.villagranquiroz.ohm_launcher

import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

/** Originals and previews have different capabilities. This bounded protocol uses
 * the existing authenticated control stream and never creates a payload listener. */
internal object FluxWallpaper {
    internal val deadlines = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task,"ohm-wallpaper-deadline").apply { isDaemon=true }
    }
    const val TYPE = "flux.wallpaper.v2"
    const val MAX_BYTES = 32 * 1024 * 1024
    const val MAX_PIXELS = 32L * 1024 * 1024
    const val CHUNK = 24 * 1024
    const val MAX_MESSAGE = 34 * 1024
    private val hash = Regex("[0-9a-f]{64}")
    private val op = Regex("[0-9a-f]{32}")
    data class Meta(val operation: String, val origin: String, val revision: String, val theme: String,
                    val sha256: String, val mime: String, val size: Int, val width: Int, val height: Int) {
        fun json() = JSONObject().put("operation", operation).put("origin", origin).put("revision", revision)
            .put("theme", theme).put("sha256", sha256).put("mime", mime).put("size", size).put("width", width).put("height", height)
        fun valid() = op.matches(operation) && Regex("[a-zA-Z0-9_-]{1,64}").matches(origin) && hash.matches(revision) &&
            Regex("[a-z0-9][a-z0-9._-]{0,63}").matches(theme) && !theme.contains("..") && hash.matches(sha256) &&
            mime in setOf("image/jpeg", "image/png", "image/webp") && size in 1..MAX_BYTES && width > 0 && height > 0 && width.toLong()*height <= MAX_PIXELS
    }
    fun meta(j: JSONObject) = Meta(j.getString("operation"),j.getString("origin"),j.getString("revision"),j.getString("theme"),
        j.getString("sha256"),j.getString("mime"),j.getInt("size"),j.getInt("width"),j.getInt("height")).also { require(it.valid()) {"Invalid wallpaper metadata"} }
    fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun inspect(bytes: ByteArray): Triple<String,Int,Int> {
        require(bytes.size in 1..MAX_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outMimeType in setOf("image/jpeg","image/png","image/webp") && bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight <= MAX_PIXELS) {"Wallpaper image bounds or MIME rejected"}
        return Triple(bounds.outMimeType,bounds.outWidth,bounds.outHeight)
    }
    fun validate(m: Meta, bytes: ByteArray) {
        require(m.valid()) {"Invalid wallpaper metadata"}
        require(bytes.size==m.size) {"Wallpaper length mismatch"}
        require(digest(bytes)==m.sha256) {"Wallpaper SHA256 mismatch"}
        val (mime,w,h)=inspect(bytes);require(mime==m.mime && w==m.width && h==m.height) {"Wallpaper decoded metadata mismatch"}
    }
    fun read(input: InputStream): ByteArray {
        val out=ByteArrayOutputStream();val buffer=ByteArray(CHUNK)
        while(true) { val n=input.read(buffer);if(n<0)break;require(out.size()+n<=MAX_BYTES);out.write(buffer,0,n) }
        return out.toByteArray().also { inspect(it) }
    }
    fun send(m: Meta,bytes: ByteArray,emit:(JSONObject)->Unit) {
        validate(m,bytes)
        emit(JSONObject().put("kind","begin").put("operation",m.operation).put("meta",m.json()))
        var offset=0
        while(offset<bytes.size) {val end=minOf(offset+CHUNK,bytes.size)
            emit(JSONObject().put("kind","chunk").put("operation",m.operation).put("offset",offset)
                .put("data",Base64.getEncoder().encodeToString(bytes.copyOfRange(offset,end))));offset=end}
        emit(JSONObject().put("kind","end").put("operation",m.operation))
    }
    /** Immutable originals; an incomplete receive never replaces the selected file. */
    fun store(root: File,m: Meta,bytes: ByteArray,authorized:()->Boolean): File {
        validate(m,bytes);check(authorized())
        // Context.filesDir may use an Android alias; only descendants must remain canonical.
        val dir=File(root.canonicalFile,"wallpaper-originals");check(dir.isDirectory || dir.mkdirs()) {"Wallpaper directory unavailable"}
        check(dir.canonicalFile==dir.absoluteFile) {"Wallpaper directory is non-canonical"}
        val ext=mapOf("image/jpeg" to "jpg","image/png" to "png","image/webp" to "webp").getValue(m.mime)
        val target=File(dir,"${m.sha256}.$ext")
        if(target.exists()) {check(target.canonicalFile==target.absoluteFile) {"Wallpaper original is non-canonical"};validate(m,target.readBytes());check(authorized());return target}
        val temp=File.createTempFile(".original-",".tmp",dir)
        try {FileOutputStream(temp).use {it.write(bytes);it.fd.sync()};check(authorized());check(temp.renameTo(target)) {"Wallpaper atomic replace failed"};return target}
        finally {temp.delete()}
    }
    class Receiver(private val now:()->Long = System::nanoTime) {
        private var pending: Meta?=null;private var bytes=ByteArrayOutputStream();private var started=0L
        @Synchronized fun reset(){pending=null;bytes=ByteArrayOutputStream()}
        @Synchronized fun receive(j: JSONObject,origin:String,commit:(Meta,ByteArray)->String): JSONObject? {
            val operation=j.optString("operation")
            val kind=j.optString("kind").takeIf{it in setOf("begin","chunk","end","ack")} ?: "other"
            var stage="envelope"
            fun fail(reason:String):JSONObject {reset();return JSONObject().put("kind","ack").put("operation",operation).put("ok",false).put("error",reason)}
            if(j.toString().toByteArray().size>MAX_MESSAGE) {
                rejected(kind,stage,IllegalArgumentException("Wallpaper message exceeds limit"));reset();return null
            }
            if(!op.matches(operation)) {
                rejected(kind,stage,IllegalArgumentException("Invalid wallpaper operation"));reset();return null
            }
            if(j.optString("kind")=="ack")return null
            return try {
                when(j.getString("kind")) {
                    "begin" -> {stage="begin_metadata";reset();val m=meta(j.getJSONObject("meta"));require(m.operation==operation && m.origin==origin) {"Wallpaper origin or operation mismatch"};pending=m;started=now();null}
                    "chunk" -> {stage="chunk_state";val m=checkNotNull(pending) {"No pending wallpaper"};check(m.operation==operation && now()-started<=60_000_000_000L) {"Wallpaper operation changed or expired"}
                        // Go's offset field has omitempty, so its first chunk carries no offset.
                        stage="chunk_offset";val offset=if(j.has("offset"))j.getInt("offset") else 0
                        val data=j.getString("data");require(data.length<=((CHUNK+2)/3)*4 && offset==bytes.size()) {"Wallpaper chunk offset or encoded length mismatch"}
                        stage="chunk_data";val b=Base64.getDecoder().decode(data);require(b.size in 1..CHUNK && bytes.size()+b.size<=m.size && Base64.getEncoder().encodeToString(b)==data) {"Wallpaper chunk size or encoding mismatch"}
                        bytes.write(b);null}
                    "end" -> {stage="end_state";val m=checkNotNull(pending) {"No pending wallpaper"};check(m.operation==operation && now()-started<=60_000_000_000L) {"Wallpaper operation changed or expired"}
                        stage="original_validation";val data=bytes.toByteArray();reset();validate(m,data);stage="commit";val rev=commit(m,data)
                        JSONObject().put("kind","ack").put("operation",operation).put("ok",true).put("revision",rev)}
                    else -> fail("kind")
                }
            } catch (error:Exception) {rejected(kind,stage,error);fail("rejected")}
        }
        /** Do not echo exception messages containing documents, packet values, paths or URIs. */
        private fun rejected(kind:String,stage:String,error:Exception) {
            val safe=setOf("Failed requirement.","Check failed.","Local choice pending",
                "Invalid wallpaper metadata","Wallpaper image bounds or MIME rejected","Wallpaper length mismatch",
                "Wallpaper SHA256 mismatch","Wallpaper decoded metadata mismatch","Wallpaper directory unavailable",
                "Wallpaper directory is non-canonical","Wallpaper original is non-canonical","Wallpaper atomic replace failed",
                "Wallpaper message exceeds limit","Invalid wallpaper operation","Wallpaper origin or operation mismatch",
                "No pending wallpaper","Wallpaper operation changed or expired","Wallpaper chunk offset or encoded length mismatch",
                "Wallpaper chunk size or encoding mismatch")
            val message=error.message?.takeIf{it in safe} ?: "Validation or application failed"
            Log.w("OhmFlux","Wallpaper rejected kind=$kind stage=$stage exception=${error.javaClass.simpleName} message=$message")
        }
    }
}
