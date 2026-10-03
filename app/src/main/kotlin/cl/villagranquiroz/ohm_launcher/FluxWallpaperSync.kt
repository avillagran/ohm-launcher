package cl.villagranquiroz.ohm_launcher

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Durable user intent, separate from received snapshots. Only choose() creates
 * an outbound operation; applying a snapshot/ACK never creates an echo. */
internal data class FluxWallpaperState(val current:String,val revision:String)

internal class FluxWallpaperSync<S:Any>(private val files: File,
    private val peerId:(S)->String, private val origin:(S)->String, private val send:(S,JSONObject)->Unit,
    private val current:(S)->FluxWallpaperState?,
    private val allowed:(S)->Boolean,
    private val apply:(File,()->Boolean)->Unit,
    private val report:(String)->Unit,
    private val transfer:((S,FluxWallpaper.Meta,ByteArray)->Unit)?=null,
    private val remoteAllowed:(S,FluxWallpaper.Meta)->Boolean={_,_->true}) {
    private val journal=AtomicFile(File(files,"wallpaper-intent.json"))
    // Several launcher Activities share this journal. Serialize their commits
    // and invalidate queued delivery after any newer explicit choice.
    companion object {
        private val gate=Any()
        @Volatile private var selectionGeneration=0L
    }
    private var attempted: Pair<S,String>?=null
    private var receiverSession: S?=null
    private var receiver=FluxWallpaper.Receiver()
    private fun pending():JSONObject? = synchronized(gate) {
        try {JSONObject(journal.openRead().use {it.bufferedReader().readText()})} catch(_:java.io.FileNotFoundException){null}
    }
    private fun save(j:JSONObject)=synchronized(gate) {
        val out=journal.startWrite()
        try {out.write(j.toString().toByteArray());journal.finishWrite(out)} catch(e:Exception){journal.failWrite(out);throw e}
    }
    fun receive(session:S,j:JSONObject) {
        if(!allowed(session))return
        if(j.optString("kind")=="ack") {
            synchronized(gate) {
                val p=pending() ?: return
                if(p.optString("peer")!=peerId(session) || p.getJSONObject("meta").getString("operation")!=j.optString("operation"))return
                if(j.optBoolean("ok") && Regex("[0-9a-f]{64}").matches(j.optString("revision"))) {save(JSONObject());report("Fondo sincronizado")}
                else {p.put("blocked",true);save(p);report("El fondo local se conserva; no se sincronizó. Vuelve a seleccionarlo tras revisar el escritorio.")}
            }
            return
        }
        synchronized(gate) {
            if(receiverSession!==session){receiver.reset();receiverSession=session}
            val ack=receiver.receive(j,peerId(session)) {meta,data ->
                val c=checkNotNull(current(session));check(allowed(session) && remoteAllowed(session,meta) && c.current==meta.theme && c.revision==meta.revision)
                val intent=pending()
                // A rejected upload remains local until this same authenticated desktop
                // publishes another revision. It must not disable every future snapshot.
                val recoverRejected=intent?.optBoolean("blocked")==true &&
                    intent.optString("peer")==peerId(session) &&
                    intent.optJSONObject("meta")?.optString("revision")!=meta.revision
                check(intent?.optString("peer").orEmpty().isEmpty() || recoverRejected) {"Local choice pending"}
                val generation=selectionGeneration
                val authority={allowed(session) && current(session)?.revision==meta.revision && selectionGeneration==generation && remoteAllowed(session,meta)}
                val file=FluxWallpaper.store(files,meta,data,authority)
                apply(file,authority)
                check(authority())
                if(recoverRejected)save(JSONObject())
                meta.revision
            }
            if(ack!=null && allowed(session))send(session,ack)
        }
    }
    /** Called only for the explicitly selected SAF document, never an album scan. */
    fun choose(session:S,input:InputStream) {
        val catalog=checkNotNull(current(session));val revision=checkNotNull(catalog.revision)
        check(allowed(session))
        val bytes=input.use(FluxWallpaper::read)
        val (mime,w,h)=FluxWallpaper.inspect(bytes)
        val meta=FluxWallpaper.Meta(UUID.randomUUID().toString().replace("-",""),origin(session),revision,catalog.current,
            FluxWallpaper.digest(bytes),mime,bytes.size,w,h)
        synchronized(gate) {
            check(allowed(session) && current(session)?.revision==revision)
            val file=FluxWallpaper.store(files,meta,bytes) {allowed(session)}
            // Keep the local original and pending intent even if transport/ACK fails.
            val previous=pending() ?: JSONObject()
            selectionGeneration++
            save(JSONObject().put("peer",peerId(session)).put("meta",meta.json()).put("path",file.absolutePath))
            try {apply(file) {allowed(session)}} catch(e:Exception){save(previous);throw e}
            attempted=null
        }
        retry(session)
    }
    fun retry(session:S) {
        val pair=synchronized(gate) {
            val p=pending() ?: return
            if(p.optString("peer")!=peerId(session) || p.optBoolean("blocked") || !allowed(session))return
            val m=FluxWallpaper.meta(p.getJSONObject("meta"))
            if(attempted?.first===session && attempted?.second==m.operation)return
            attempted=session to m.operation
            val dir=File(files,"wallpaper-originals").canonicalFile
            val f=File(p.getString("path")).canonicalFile;check(f.parentFile==dir)
            m to f.inputStream().use(FluxWallpaper::read)
        }
        runCatching {
            if(transfer!=null) transfer.invoke(session,pair.first,pair.second)
            else FluxWallpaper.send(pair.first,pair.second) {check(allowed(session));send(session,it)}
        }
            .onFailure {report("Fondo conservado; pendiente de reconexión")}
    }
    fun close()=synchronized(gate){receiver.reset();receiverSession=null;attempted=null}
}
