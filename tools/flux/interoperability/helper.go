// This test peer uses Flux's real protocol and LAN implementation. It creates
// disposable software identities and binds only loopback test sockets.
package main

import (
 "context"
 "crypto/x509"
 "encoding/base64"
 "encoding/json"
 "flag"
 "fmt"
 "os"
 "path/filepath"
 "strings"
 "sync"
 "time"

 "flux/internal/lan"
 "flux/internal/proto"
)

func must(err error) { if err != nil { fmt.Fprintln(os.Stderr, err); os.Exit(1) } }
func emit(value any) { must(json.NewEncoder(os.Stdout).Encode(value)) }
func formatted(key string) string { var out []string;for len(key)>0 {n:=min(4,len(key));out=append(out,key[:n]);key=key[n:]};return strings.Join(out," ") }

func main() {
 mode:=flag.String("mode","fixture","fixture, listen or dial")
 fixtures:=flag.String("fixtures","","directory of disposable identities")
 dialect:=flag.String("dialect","native","native or legacy; must match the linked Flux source")
 port:=flag.Int("port",1748,"loopback TCP listener port")
 udp:=flag.Int("udp-port",28763,"isolated loopback discovery port")
 peerPort:=flag.Int("peer-port",0,"Kotlin listener port for dial mode")
 pairing:=flag.String("pair","","initiate or respond; defaults to initiate for dial")
 flag.Parse()
 if *fixtures=="" || (*mode!="fixture" && *mode!="listen" && *mode!="dial") {must(fmt.Errorf("invalid mode or missing fixtures"))}
 native:=proto.TypeIdentity=="flux.identity"
 if (native && *dialect!="native") || (!native && *dialect!="legacy") {must(fmt.Errorf("dialect does not match the linked Flux source"))}
 own,ownID,err:=proto.LoadOrCreateCert(filepath.Join(*fixtures,"go"));must(err)
 peer,peerID,err:=proto.LoadOrCreateCert(filepath.Join(*fixtures,"kotlin"));must(err)
 for _,entry:=range []struct{name string;cert any;key any}{ {"go",own.Leaf,own.PrivateKey},{"kotlin",peer.Leaf,peer.PrivateKey} } {
  cert:=entry.cert.(*x509.Certificate);key,err:=x509.MarshalPKCS8PrivateKey(entry.key);must(err)
  must(os.WriteFile(filepath.Join(*fixtures,entry.name,"certificate.der"),cert.Raw,0600))
  must(os.WriteFile(filepath.Join(*fixtures,entry.name,"privateKey.pkcs8.der"),key,0600))
 }
 const ts int64=1790000000
 key:=proto.VerificationKey(own.Leaf,peer.Leaf,ts)
 identity:=proto.NewIdentity(ownID,"Flux interoperability test",*port)
 fixture:=map[string]any{
  "dialect":*dialect,"identityType":proto.TypeIdentity,"pairType":proto.TypePair,"pingType":proto.TypePing,
  "goId":ownID,"kotlinId":peerID,"identity":identity,"timestamp":ts,"verificationKey":key,"formattedKey":formatted(key),
  "goSpki":base64.StdEncoding.EncodeToString(own.Leaf.RawSubjectPublicKeyInfo),"kotlinSpki":base64.StdEncoding.EncodeToString(peer.Leaf.RawSubjectPublicKeyInfo),
 }
 bytes,err:=json.MarshalIndent(fixture,"","  ");must(err)
 must(os.WriteFile(filepath.Join(*fixtures,"fixture-"+*dialect+".json"),bytes,0600))
 if *mode=="fixture" {emit(map[string]any{"ready":true,"dialect":*dialect,"id":ownID});return}
 if *port<1 || *port>65535 || *udp<1765 || *udp>65535 {must(fmt.Errorf("invalid isolated ports"))}
 ctx,cancel:=context.WithTimeout(context.Background(),30*time.Second);defer cancel()
 var once sync.Once
 var links sync.Map
 config:=lan.Config{
  Cert:own,Identity:func()proto.Identity{return identity},
  Trusted:func(id string)(*x509.Certificate,bool){if id==peerID{return peer.Leaf,true};return nil,true},
  HasLink:func(id string)bool{_,ok:=links.Load(id);return ok},
  Logf:func(format string,args ...any){fmt.Fprintf(os.Stderr,format+"\n",args...)},
  UDPPort:*udp,FirstTCPPort:*port,LoopbackOnly:true,
 }
 config.OnLink=func(link *lan.Link){
  links.Store(link.DeviceID(),true)
  go func(){
   defer links.Delete(link.DeviceID())
   var sent bool
   if *pairing=="initiate" || (*pairing=="" && *mode=="dial") {
    must(link.Send(proto.New(proto.TypePair,map[string]any{"pair":true,"timestamp":time.Now().Unix()})))
    sent=true
   }
   err:=link.Receive(func(packet *proto.Packet){
    if packet.Type==proto.TypeShare {
     var share struct{Text string `json:"text"`}
     if packet.Decode(&share)==nil {emit(map[string]any{"receivedText":share.Text,"type":packet.Type})}
     return
    }
    if packet.Type!=proto.TypePair{return}
    var body struct{Pair bool `json:"pair"`;Timestamp int64 `json:"timestamp"`}
    if packet.Decode(&body)!=nil || !body.Pair{return}
    if !sent {must(link.Send(proto.New(proto.TypePair,map[string]any{"pair":true})));sent=true}
    once.Do(func(){
     emit(map[string]any{"paired":true,"dialect":*dialect,"peerCertificateMatched":link.Cert.Equal(peer.Leaf)})
     must(link.Send(proto.New(proto.TypePing,map[string]any{"message":"interop"})))
     must(link.Send(proto.New(proto.TypeShare,map[string]any{"text":"Go interoperability fixture"})))
    })
   })
   if err!=nil && ctx.Err()==nil {fmt.Fprintln(os.Stderr,err)}
  }()
 }
 provider:=lan.New(config);must(provider.Start(ctx))
 emit(map[string]any{"ready":true,"id":ownID,"port":provider.TCPPort(),"udpPort":*udp,"dialect":*dialect})
 if *mode=="dial" {
  if *peerPort<1 || *peerPort>65535 {must(fmt.Errorf("missing peer listener port"))}
  provider.Dial(ctx,"127.0.0.1",*peerPort,proto.Identity{DeviceID:peerID,DeviceName:"Ohm interoperability test",ProtocolVersion:proto.ProtocolVersion})
 }
 <-ctx.Done()
}
