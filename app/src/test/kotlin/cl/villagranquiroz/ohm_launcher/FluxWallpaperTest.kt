package cl.villagranquiroz.ohm_launcher
import org.junit.Test
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FluxWallpaperTest {
 @get:Rule val temp=TemporaryFolder()
 private fun fixture():Pair<FluxWallpaper.Meta,ByteArray> {
  val image=BufferedImage(3840,2160,BufferedImage.TYPE_INT_RGB)
  val out=ByteArrayOutputStream();ImageIO.write(image,"png",out);val b=out.toByteArray()
  return FluxWallpaper.Meta("a".repeat(32),"desktop","b".repeat(64),"matte-black",FluxWallpaper.digest(b),"image/png",b.size,3840,2160) to b
 }
 private fun jpegFixture():Pair<FluxWallpaper.Meta,ByteArray> {
  val image=BufferedImage(3840,2160,BufferedImage.TYPE_INT_RGB)
  val out=ByteArrayOutputStream();assertTrue(ImageIO.write(image,"jpeg",out));val bytes=out.toByteArray()
  assertTrue("fixture must have continuation chunks",bytes.size>FluxWallpaper.CHUNK)
  return FluxWallpaper.Meta("a".repeat(32),"desktop","b".repeat(64),"matte-black",
   FluxWallpaper.digest(bytes),"image/jpeg",bytes.size,3840,2160) to bytes
 }
 /** Explicit Go Message wire shape: Offset has json omitempty, including when zero. */
 private fun goChunk(meta:FluxWallpaper.Meta,bytes:ByteArray,offset:Int):JSONObject = JSONObject()
  .put("kind","chunk").put("operation",meta.operation)
  .put("data",Base64.getEncoder().encodeToString(bytes.copyOfRange(offset,minOf(offset+FluxWallpaper.CHUNK,bytes.size))))
  .also{if(offset!=0)it.put("offset",offset)}
 @Test fun originalCapabilityRequiresBothDirectionsAndStaysOutOfPlay() {
  val direct=FluxWire.identity("phone","Phone",playStore=false)
  val play=FluxWire.identity("phone","Phone",playStore=true)
  assertTrue(FluxWire.supportsWallpaper(direct));assertFalse(FluxWire.supportsWallpaper(play))
  val missing=JSONObject(direct);missing.getJSONObject("body").put("outgoingCapabilities",org.json.JSONArray())
  assertFalse(FluxWire.supportsWallpaper(missing.toString()))
 }
 @Test fun original3840RoundTripsBothDirectionsWithoutRecompression() {
  val (meta,bytes)=fixture()
  for(origin in listOf("phone","desktop")) {
   val m=meta.copy(origin=origin);val receiver=FluxWallpaper.Receiver();var commits=0;var ack:JSONObject?=null
   FluxWallpaper.send(m,bytes) { msg ->
    assertTrue(msg.toString().toByteArray().size<=FluxWallpaper.MAX_MESSAGE)
    receiver.receive(msg,origin) { got,data -> commits++;assertEquals(3840,got.width);assertEquals(2160,got.height);assertArrayEquals(bytes,data)
     val file=FluxWallpaper.store(temp.newFolder(),got,data){true};assertArrayEquals(bytes,file.readBytes());got.revision }?.let {ack=it}
   }
   assertEquals(1,commits);assertTrue(ack!!.getBoolean("ok"))
  }
 }
 @Test fun badHashTruncationAndStaleRevisionDoNotApply() {
  val (m,b)=fixture()
  for(mode in listOf("hash","truncated","stale","origin","offset")) {
   val receiver=FluxWallpaper.Receiver();var applied=0;var ack:JSONObject?=null
   FluxWallpaper.send(m,b) { original ->
    val msg=JSONObject(original.toString())
    if(mode!="truncated" || msg.getString("kind")!="chunk") {
     if(mode=="hash" && msg.getString("kind")=="begin") msg.getJSONObject("meta").put("sha256","0".repeat(64))
     if(mode=="offset" && msg.getString("kind")=="chunk")msg.put("offset",msg.getInt("offset")+1)
     receiver.receive(msg,if(mode=="origin")"other" else m.origin) { got,_ -> check(mode!="stale" && got.revision==m.revision);applied++;got.revision }?.let {ack=it}
    }
   }
   assertEquals(mode,0,applied);assertFalse(ack!!.optBoolean("ok"))
  }
 }
 @Test fun rejectedAuthorityRetainsPreviousOriginalAndNoPartialFinalFile() {
  val (m,b)=fixture();val root=temp.newFolder();val old=FluxWallpaper.store(root,m,b){true}
  assertThrows(IllegalStateException::class.java){FluxWallpaper.store(root,m,b){false}}
  assertArrayEquals(b,old.readBytes());assertFalse(root.walk().any{it.name.endsWith(".tmp")})
 }
 @Test fun trustedRootAliasStoresExactOriginalUnderItsCanonicalDirectory() {
  val (meta,bytes)=fixture();val root=temp.newFolder("private-root")
  val alias=File(temp.root,"android-files-alias")
  Files.createSymbolicLink(alias.toPath(),root.toPath())
  assertNotEquals(alias.absoluteFile,alias.canonicalFile)
  val stored=FluxWallpaper.store(alias,meta,bytes){true}
  assertEquals(File(root.canonicalFile,"wallpaper-originals/${meta.sha256}.png"),stored)
  assertArrayEquals(bytes,stored.readBytes())
  assertFalse(root.walk().any{it.name.endsWith(".tmp")})
 }
 @Test fun wallpaperDirectorySymlinkCannotEscapeTrustedRoot() {
  val (meta,bytes)=fixture();val root=temp.newFolder("private-root");val outside=temp.newFolder("outside-root")
  Files.createSymbolicLink(File(root,"wallpaper-originals").toPath(),outside.toPath())
  assertThrows(IllegalStateException::class.java){FluxWallpaper.store(root,meta,bytes){true}}
  assertTrue(outside.listFiles()!!.isEmpty())
 }
 @Test fun originalSymlinkCannotReadOrReplaceFileOutsideTrustedRoot() {
  val (meta,bytes)=fixture();val root=temp.newFolder("private-root")
  val dir=File(root,"wallpaper-originals").also{assertTrue(it.mkdir())}
  val outside=File(temp.newFolder("outside-root"),"original.png").also{it.writeBytes(bytes)}
  Files.createSymbolicLink(File(dir,"${meta.sha256}.png").toPath(),outside.toPath())
  assertThrows(IllegalStateException::class.java){FluxWallpaper.store(root,meta,bytes){true}}
  assertArrayEquals(bytes,outside.readBytes())
  assertFalse(dir.listFiles()!!.any{it.name.endsWith(".tmp")})
 }
 @Test fun projectionHasDifferentCapabilityAndCannotBecomeOriginal() {
  assertNotEquals(FluxWallpaper.TYPE,"flux.omarchy_theme.background")
  val (m,b)=fixture();val preview=m.copy(width=1280,height=720)
  assertThrows(IllegalArgumentException::class.java){FluxWallpaper.validate(preview,b)}
 }
 @Test fun expiredAndOutOfOrderTransferDoNotCommit() {
  val (m,b)=fixture();var now=0L;val r=FluxWallpaper.Receiver{now};var applied=false;var ack:JSONObject?=null
  FluxWallpaper.send(m,b){msg -> if(msg.getString("kind")=="end")now=61_000_000_000L
   r.receive(msg,m.origin){_,_->applied=true;m.revision}?.let{ack=it}}
  assertFalse(applied);assertFalse(ack!!.optBoolean("ok"))
 }
 @Test fun goFirstChunkMayOmitItsZeroOffset() {
  val (meta,bytes)=jpegFixture();val receiver=FluxWallpaper.Receiver();var commits=0
  val commit={m:FluxWallpaper.Meta,_:ByteArray->commits++;m.revision}
  receiver.receive(JSONObject().put("kind","begin").put("operation",meta.operation).put("meta",meta.json()),meta.origin,commit)
  val first=goChunk(meta,bytes,0);assertFalse(first.has("offset"))
  assertNull(receiver.receive(first,meta.origin,commit));assertEquals(0,commits)
 }
 @Test fun missingOffsetOnContinuationIsRejectedAndNeverCommits() {
  val (meta,bytes)=jpegFixture();val receiver=FluxWallpaper.Receiver();var commits=0
  val commit={m:FluxWallpaper.Meta,_:ByteArray->commits++;m.revision}
  receiver.receive(JSONObject().put("kind","begin").put("operation",meta.operation).put("meta",meta.json()),meta.origin,commit)
  assertNull(receiver.receive(goChunk(meta,bytes,0),meta.origin,commit))
  val continuation=goChunk(meta,bytes,FluxWallpaper.CHUNK).also{it.remove("offset")}
  assertFalse(receiver.receive(continuation,meta.origin,commit)!!.getBoolean("ok"))
  val end=JSONObject().put("kind","end").put("operation",meta.operation)
  assertFalse(receiver.receive(end,meta.origin,commit)!!.getBoolean("ok"));assertEquals(0,commits)
 }
 @Test fun actualGoSerializedFixtureRoundTripsOriginalJpegLargerThanOneChunk() {
  // Serialized by the unchanged desktop wallpaper.Send implementation, not the Kotlin sender.
  val frames=checkNotNull(javaClass.getResourceAsStream("/flux/wallpaper-go-original.jsonl"))
   .bufferedReader().use{it.readLines().filter(String::isNotBlank).map(::JSONObject)}
  val bytes=checkNotNull(javaClass.getResourceAsStream("/flux/wallpaper-go-original.jpg")).use{it.readBytes()}
  val meta=FluxWallpaper.meta(frames.first().getJSONObject("meta"))
  assertTrue(bytes.size>FluxWallpaper.CHUNK);assertEquals(257,meta.width);assertEquals(257,meta.height)
  val chunks=frames.filter{it.getString("kind")=="chunk"}
  assertEquals(3,chunks.size);assertFalse(chunks.first().has("offset"))
  assertEquals(FluxWallpaper.CHUNK,chunks[1].getInt("offset"))
  val receiver=FluxWallpaper.Receiver();var commits=0;var ack:JSONObject?=null
  val commit={got:FluxWallpaper.Meta,data:ByteArray->
   commits++;assertEquals(meta,got);assertArrayEquals(bytes,data)
   val stored=FluxWallpaper.store(temp.newFolder(),got,data){true};assertArrayEquals(bytes,stored.readBytes());got.revision
  }
  frames.forEach{message->receiver.receive(message,meta.origin,commit)?.let{ack=it}}
  val reply=checkNotNull(ack)
  assertEquals(1,commits);assertTrue(reply.getBoolean("ok"));assertEquals(meta.revision,reply.getString("revision"))
 }
}
