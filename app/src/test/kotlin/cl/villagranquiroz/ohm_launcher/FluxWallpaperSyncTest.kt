package cl.villagranquiroz.ohm_launcher
import org.junit.Test
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.json.JSONObject
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FluxWallpaperSyncTest {
 @get:Rule val temp=TemporaryFolder()
 private class Peer(val id:String="desktop")
 private fun image():ByteArray=ByteArrayOutputStream().also{ImageIO.write(BufferedImage(128,72,BufferedImage.TYPE_INT_RGB),"png",it)}.toByteArray()
 private fun catalog(revision:String)=FluxWallpaperState(current="matte-black",revision=revision)
 @Test fun pendingIntentSurvivesRestartAckDoesNotEchoAndStaleConflictKeepsLocal() {
  val root=temp.newFolder();val bytes=image();var active=Peer();var revision="a".repeat(64);var applied:File?=null;val emitted=mutableListOf<JSONObject>()
  fun sync()=FluxWallpaperSync<Peer>(root,{it.id},{"phone"},{_,j->emitted+=j},{catalog(revision)},{it===active},{f,a->check(a());applied=f},{})
  val first=sync();first.choose(active,bytes.inputStream());val op=emitted.first().getString("operation")
  assertArrayEquals(bytes,applied!!.readBytes());val count=emitted.size;first.retry(active);assertEquals(count,emitted.size)
  active=Peer();val restored=sync();restored.retry(active);assertEquals(count*2,emitted.size);assertEquals(op,emitted[count].getString("operation"))
  restored.receive(active,JSONObject().put("kind","ack").put("operation",op).put("ok",false).put("error","conflict"));active=Peer();sync().retry(active)
  assertEquals(count*2,emitted.size);assertArrayEquals(bytes,applied!!.readBytes())
  // A fresh explicit choice uses the current revision, retaining original bytes.
  revision="b".repeat(64);val last=sync();last.choose(active,bytes.inputStream());val fresh=emitted.last().getString("operation")
  last.receive(active,JSONObject().put("kind","ack").put("operation",fresh).put("ok",true).put("revision","c".repeat(64)))
  val after=emitted.size;active=Peer();sync().retry(active);assertEquals(after,emitted.size)
 }
 @Test fun receiveOriginalCommitsOnceOnWireAndOnlyRepliesNeverEchoesImage() {
  val root=temp.newFolder();val active=Peer();val bytes=image();val replies=mutableListOf<JSONObject>();var applied:File?=null
  val s=FluxWallpaperSync<Peer>(root,{it.id},{"phone"},{_,j->replies+=j},{catalog("a".repeat(64))},{it===active},{f,a->check(a());applied=f},{})
  val (mime,w,h)=FluxWallpaper.inspect(bytes);val m=FluxWallpaper.Meta("b".repeat(32),active.id,"a".repeat(64),"matte-black",FluxWallpaper.digest(bytes),mime,bytes.size,w,h)
  FluxWallpaper.send(m,bytes){s.receive(active,it)}
  assertArrayEquals(bytes,applied!!.readBytes());assertEquals(1,replies.size);assertEquals("ack",replies.single().getString("kind"));assertTrue(replies.single().getBoolean("ok"))
  s.retry(active);assertEquals(1,replies.size)
 }
 @Test fun staleSessionAndRevisionCannotOverwriteAndFailedLocalCommitRollsBackIntent() {
  val root=temp.newFolder();val active=Peer();val bytes=image();var writes=0;val emitted=mutableListOf<JSONObject>()
  val s=FluxWallpaperSync<Peer>(root,{it.id},{"phone"},{_,j->emitted+=j},{catalog("a".repeat(64))},{it===active},{_,_->writes++;error("disk unavailable")},{})
  assertThrows(IllegalStateException::class.java){s.choose(active,bytes.inputStream())};s.retry(active);assertTrue(emitted.isEmpty())
  val (mime,w,h)=FluxWallpaper.inspect(bytes);val m=FluxWallpaper.Meta("b".repeat(32),active.id,"c".repeat(64),"matte-black",FluxWallpaper.digest(bytes),mime,bytes.size,w,h)
  FluxWallpaper.send(m,bytes){s.receive(Peer(),it)};assertTrue(emitted.isEmpty())
  FluxWallpaper.send(m,bytes){s.receive(active,it)};assertEquals(1,writes);assertFalse(emitted.last().optBoolean("ok"))
 }
 @Test fun rejectedChoiceKeepsLocalUntilNewDesktopRevisionThenRemoteSyncRecoversWithoutEcho() {
  val root=temp.newFolder();val active=Peer();val local=image();var revision="a".repeat(64)
  var selected:File?=null;val emitted=mutableListOf<JSONObject>()
  val s=FluxWallpaperSync<Peer>(root,{it.id},{"phone"},{_,j->emitted+=j},{catalog(revision)},
   {it===active},{f,a->check(a());selected=f},{})
  s.choose(active,local.inputStream());val retained=selected!!;val operation=emitted.first().getString("operation")
  s.receive(active,JSONObject().put("kind","ack").put("operation",operation).put("ok",false).put("error","conflict"))
  assertEquals(retained,selected);assertArrayEquals(local,retained.readBytes())
  val remote=ByteArrayOutputStream().also{ImageIO.write(BufferedImage(240,160,BufferedImage.TYPE_INT_RGB),"png",it)}.toByteArray()
  val (mime,w,h)=FluxWallpaper.inspect(remote)
  fun snapshot() {
   val meta=FluxWallpaper.Meta("c".repeat(32),active.id,revision,"matte-black",FluxWallpaper.digest(remote),mime,remote.size,w,h)
   FluxWallpaper.send(meta,remote){s.receive(active,it)}
  }
  val before=emitted.size;snapshot() // The rejected choice still wins against its unchanged base.
  assertEquals(retained,selected);assertFalse(emitted.last().optBoolean("ok"))
  revision="b".repeat(64);snapshot()
  assertArrayEquals(remote,selected!!.readBytes());assertArrayEquals(local,retained.readBytes())
  assertEquals(2,emitted.size-before);assertTrue(emitted.last().getBoolean("ok"))
  assertTrue(emitted.drop(before).all{it.getString("kind")=="ack"})
  val after=emitted.size;s.retry(active);assertEquals(after,emitted.size)
 }
 @Test fun unacknowledgedLocalChoiceStillBlocksEvenANewerAuthorizedDesktopSnapshot() {
  val root=temp.newFolder();val active=Peer();val bytes=image();var revision="a".repeat(64)
  var selected:File?=null;val emitted=mutableListOf<JSONObject>()
  val s=FluxWallpaperSync<Peer>(root,{it.id},{"phone"},{_,j->emitted+=j},{catalog(revision)},
   {it===active},{f,a->check(a());selected=f},{})
  s.choose(active,bytes.inputStream());val retained=selected;revision="b".repeat(64)
  val (mime,w,h)=FluxWallpaper.inspect(bytes)
  val meta=FluxWallpaper.Meta("c".repeat(32),active.id,revision,"matte-black",FluxWallpaper.digest(bytes),mime,bytes.size,w,h)
  FluxWallpaper.send(meta,bytes){s.receive(active,it)}
  assertEquals(retained,selected);assertFalse(emitted.last().getBoolean("ok"))
  assertTrue(JSONObject(File(root,"wallpaper-intent.json").readText()).has("peer"))
 }
 @Test fun failedRecoveryRetainsRejectedIntentUntilAnActualCommitSucceeds() {
  val root=temp.newFolder();val active=Peer();val bytes=image();var revision="a".repeat(64);var fail=false
  val emitted=mutableListOf<JSONObject>()
  val s=FluxWallpaperSync<Peer>(root,{it.id},{"phone"},{_,j->emitted+=j},{catalog(revision)},
   {it===active},{_,a->check(a());check(!fail){"disk unavailable"}},{})
  s.choose(active,bytes.inputStream());val operation=emitted.first().getString("operation")
  s.receive(active,JSONObject().put("kind","ack").put("operation",operation).put("ok",false))
  revision="b".repeat(64);fail=true
  val (mime,w,h)=FluxWallpaper.inspect(bytes)
  val meta=FluxWallpaper.Meta("c".repeat(32),active.id,revision,"matte-black",FluxWallpaper.digest(bytes),mime,bytes.size,w,h)
  FluxWallpaper.send(meta,bytes){s.receive(active,it)}
  assertFalse(emitted.last().getBoolean("ok"))
  assertTrue(JSONObject(File(root,"wallpaper-intent.json").readText()).getBoolean("blocked"))
  fail=false;FluxWallpaper.send(meta,bytes){s.receive(active,it)}
  assertTrue(emitted.last().getBoolean("ok"));assertEquals(0,JSONObject(File(root,"wallpaper-intent.json").readText()).length())
 }
}
