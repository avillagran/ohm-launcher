// ============================================================================
//  OMARCHY DISCOVERY — auto-detection of the OhmLauncher peer on LAN / BT
// ============================================================================
//  - Announces the service via mDNS (_ohm._tcp) with `nsd` so the plugin
//    Omarchy QML discovers it via a network scan.
//  - Generates a `ohm://<lan_ip>:<port>` QR as a manual fallback.
//  - Scans BLE to discover a nearby Omarchy peer (button in the dialog).
//
//  The Omarchy QML plugin (see examples/plugins/io.github.ohm.omarchy-link)
//  performs an mDNS `_ohm._tcp` scan and/or reads the QR, then consumes the
//  REST/WS contract defined in omarchy_link.dart.
// ============================================================================

import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_blue_plus/flutter_blue_plus.dart';
import 'package:nsd/nsd.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:flutter/material.dart';
import 'package:ohm_launcher/l10n/app_localizations.dart';

/// Announces the launcher on the LAN via mDNS (nsd).
class OmarchyAnnouncer {
  OmarchyAnnouncer({required this.port, required this.lanIp, this.name = 'OhmLauncher'});

  final int port;
  final String lanIp;
  final String name;

  Registration? _registration;
  bool _running = false;
  bool get isRunning => _running;

  Future<void> start() async {
    if (_running) return;
    try {
      _registration = await register(
        Service(
          name: name,
          type: '_ohm._tcp',
          port: port,
          txt: {
            'ohm': Uint8List.fromList('1'.codeUnits),
            'port': Uint8List.fromList('$port'.codeUnits),
          },
        ),
      );
      _running = true;
    } catch (e) {
      _running = false;
      if (kDebugLog) print('[OmarchyAnnouncer] no pudo anunciar mDNS: $e');
    }
  }

  Future<void> stop() async {
    _running = false;
    if (_registration != null) {
      try {
        await unregister(_registration!);
      } catch (_) {}
      _registration = null;
    }
  }

  /// URI for the manual fallback QR.
  String get qrUri => 'ohm://$lanIp:$port';
}

/// Scans BLE for the Omarchy peer (devices named "Omarchy"
/// or a known Ohm service). Returns the list of candidates.
class OmarchyBtScanner {
  OmarchyBtScanner({this.serviceUuid = '0000ohm0-0000-1000-8000-00805f9b34fb'});

  final String serviceUuid;

  Future<List<ScanResult>> scan({Duration timeout = const Duration(seconds: 6)}) async {
    if (!await FlutterBluePlus.isSupported) return [];
    if (FlutterBluePlus.adapterStateNow != BluetoothAdapterState.on) {
      // The adapter cannot be turned on automatically; it requires that
      // Bluetooth must be enabled on the system.
      return [];
    }
    final found = <ScanResult>[];
    final done = Completer<void>();
    final sub = FlutterBluePlus.onScanResults.listen((results) {
      for (final r in results) {
        final name = r.device.platformName.toLowerCase();
        final hasService = r.advertisementData.serviceUuids
            .any((u) => u.toString().toLowerCase().contains('ohm'));
        if (name.contains('omarchy') || name.contains('ohm') || hasService) {
          found.add(r);
        }
      }
    });
    try {
      await FlutterBluePlus.startScan(timeout: timeout, withServices: [Guid(serviceUuid)]);
      await Future.any([done.future, Future.delayed(timeout)]);
      await FlutterBluePlus.stopScan();
    } catch (_) {
      try { await FlutterBluePlus.stopScan(); } catch (_) {}
    } finally {
      await sub.cancel();
    }
    return found;
  }
}

/// Dialog that shows the QR, the IP/port and a Bluetooth scan of peers.
class OmarchyQrDialog extends StatefulWidget {
  const OmarchyQrDialog({super.key, required this.uri, required this.lanIp, required this.port});

  final String uri;
  final String lanIp;
  final int port;

  @override
  State<OmarchyQrDialog> createState() => _OmarchyQrDialogState();
}

class _OmarchyQrDialogState extends State<OmarchyQrDialog> {
  final _scanner = OmarchyBtScanner();
  List<ScanResult> _peers = [];
  bool _scanning = false;
  String _btStatus = '';

  Future<void> _doScan() async {
    setState(() { _scanning = true; _btStatus = 'Escaneando...'; _peers = []; });
    if (!await FlutterBluePlus.isSupported) {
      setState(() { _scanning = false; _btStatus = 'Bluetooth no soportado'; });
      return;
    }
    if (FlutterBluePlus.adapterStateNow != BluetoothAdapterState.on) {
      setState(() { _scanning = false; _btStatus = 'Activa Bluetooth para escanear'; });
      return;
    }
    final peers = await _scanner.scan();
    if (!mounted) return;
    setState(() {
      _peers = peers;
      _scanning = false;
      _btStatus = peers.isEmpty ? 'Ningún peer Omarchy cercano' : '${peers.length} peer(s) encontrado(s)';
    });
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    return AlertDialog(
      backgroundColor: const Color(0xFF10161C),
      title: Text(l10n.connectWithOmarchy, style: const TextStyle(color: Color(0xFFE8F1F8))),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(12)),
              child: QrImageView(data: widget.uri, size: 200, backgroundColor: Colors.white),
            ),
            const SizedBox(height: 12),
            Text(l10n.networkConnectionMdnsQr, style: const TextStyle(color: Color(0xFF9FB3C8)), textAlign: TextAlign.center),
            const SizedBox(height: 8),
            SelectableText('${widget.lanIp}:${widget.port}', style: const TextStyle(color: Color(0xFF66E0FF))),
            SelectableText(widget.uri, style: const TextStyle(color: Color(0xFF9FB3C8), fontSize: 12)),
            const Divider(color: Color(0xFF2A3540), height: 24),
            Row(
              children: [
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: _scanning ? null : _doScan,
                    icon: const Icon(Icons.bluetooth, color: Color(0xFF66E0FF)),
                    label: Text(_scanning ? l10n.networkConnectionMdnsQr : l10n.scanBluetooth),
                    style: ElevatedButton.styleFrom(backgroundColor: const Color(0xFF16202A)),
                  ),
                ),
              ],
            ),
            if (_btStatus.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(_btStatus, style: const TextStyle(color: Color(0xFF9FB3C8))),
            ],
            ..._peers.map((p) => ListTile(
                  leading: const Icon(Icons.bluetooth_connected, color: Color(0xFF66E0FF)),
                  title: Text(p.device.platformName.isNotEmpty ? p.device.platformName : '(sin nombre)',
                      style: const TextStyle(color: Color(0xFFE8F1F8))),
                  subtitle: Text(p.device.remoteId.str, style: const TextStyle(color: Color(0xFF9FB3C8), fontSize: 12)),
                )),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cerrar', style: TextStyle(color: Color(0xFF66E0FF))),
        ),
      ],
    );
  }
}

bool get kDebugLog => false;
