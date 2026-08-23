// ============================================================================
//  AI PANEL — floating input to chat with the AI from the launcher
// ============================================================================
//  Sends the prompt to the configured backend (OpenAI-compatible, any
//  provider) and, if the response brings a component block, injects it into
//  the hot floating layer. The panel only shows the explanation and the
//  state; the injection is done by the caller via [onSend].
// ============================================================================

import 'package:flutter/material.dart';

import 'ai_client.dart';

class AiPanel extends StatefulWidget {
  const AiPanel({
    super.key,
    required this.onSend,
    required this.onClose,
    this.onClear,
    this.configured = true,
  });

  final Future<AiResponse> Function(String prompt) onSend;
  final VoidCallback onClose;
  final VoidCallback? onClear;
  final bool configured;

  @override
  State<AiPanel> createState() => _AiPanelState();
}

class _AiPanelState extends State<AiPanel> {
  final TextEditingController _controller = TextEditingController();
  final ScrollController _scroll = ScrollController();
  bool _busy = false;
  String _reply = '';
  bool _injected = false;
  String? _error;

  @override
  void dispose() {
    _controller.dispose();
    _scroll.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final prompt = _controller.text.trim();
    if (prompt.isEmpty || _busy) return;
    setState(() {
      _busy = true;
      _error = null;
      _reply = 'Pensando…';
      _injected = false;
    });
    try {
      final resp = await widget.onSend(prompt);
      if (!mounted) return;
      setState(() {
        _reply = resp.text;
        _injected = resp.widgetSource != null && resp.widgetSource!.isNotEmpty;
        _busy = false;
      });
      _scroll.jumpTo(_scroll.position.maxScrollExtent);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = '$e';
        _busy = false;
        _reply = '';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final mq = MediaQuery.of(context);
    return Material(
      color: Colors.transparent,
      child: GestureDetector(
        onTap: widget.onClose,
        child: Container(
          color: const Color(0x66000000),
          alignment: Alignment.bottomCenter,
          child: GestureDetector(
            onTap: () {}, // avoids closing when touching the panel
            child: SafeArea(
              child: Container(
                width: double.infinity,
                constraints: BoxConstraints(maxHeight: mq.size.height * 0.7),
                margin: const EdgeInsets.all(12),
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: const Color(0xFF0E141A),
                  borderRadius: BorderRadius.circular(18),
                  border: Border.all(color: const Color(0x2BFFFFFF)),
                ),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(
                      children: [
                        const Icon(Icons.bolt, color: Color(0xFF66E0FF), size: 18),
                        const SizedBox(width: 8),
                        const Text('Asistente Ohm',
                            style: TextStyle(fontSize: 14, color: Color(0xFFE8F1F8), fontWeight: FontWeight.w600)),
                        const Spacer(),
                        if (widget.onClear != null)
                          IconButton(
                            icon: const Icon(Icons.delete_sweep_outlined, size: 18, color: Color(0xFF7A8A99)),
                            tooltip: 'Limpiar capa IA',
                            onPressed: widget.onClear,
                          ),
                        IconButton(
                          icon: const Icon(Icons.close, size: 18, color: Color(0xFF7A8A99)),
                          onPressed: widget.onClose,
                        ),
                      ],
                    ),
                    if (!widget.configured)
                      Container(
                        margin: const EdgeInsets.only(bottom: 8),
                        padding: const EdgeInsets.all(8),
                        decoration: BoxDecoration(
                          color: const Color(0xFF1A1F26),
                          borderRadius: BorderRadius.circular(10),
                        ),
                        child: const Text(
                          'Configura la IA en Ajustes (URL base, modelo, clave).',
                          style: TextStyle(fontSize: 11, color: Color(0xFF9AA7B4)),
                        ),
                      ),
                    if (_reply.isNotEmpty)
                      Expanded(
                        child: SingleChildScrollView(
                          controller: _scroll,
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(_reply,
                                  style: const TextStyle(fontSize: 13, color: Color(0xFFE8F1F8))),
                              if (_injected)
                                Container(
                                  margin: const EdgeInsets.only(top: 8),
                                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                                  decoration: BoxDecoration(
                                    color: const Color(0xFF10241A),
                                    borderRadius: BorderRadius.circular(8),
                                  ),
                                  child: const Text('✔ Componente inyectado en la capa flotante.',
                                      style: TextStyle(fontSize: 11, color: Color(0xFF7EE787))),
                                ),
                            ],
                          ),
                        ),
                      ),
                    if (_error != null)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 8),
                        child: Text('Error: $_error',
                            style: const TextStyle(fontSize: 12, color: Color(0xFFFF6B6B))),
                      ),
                    Row(
                      children: [
                        Expanded(
                          child: TextField(
                            controller: _controller,
                            onSubmitted: (_) => _submit(),
                            minLines: 1,
                            maxLines: 3,
                            style: const TextStyle(fontSize: 13, color: Color(0xFFE8F1F8)),
                            decoration: InputDecoration(
                              hintText: 'Pídele un componente: "reloj grande abajo a la izquierda"',
                              hintStyle: const TextStyle(fontSize: 12, color: Color(0xFF5A6B7A)),
                              filled: true,
                              fillColor: const Color(0xFF16202A),
                              border: OutlineInputBorder(
                                borderRadius: BorderRadius.circular(12),
                                borderSide: BorderSide.none,
                              ),
                              contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        IconButton.filled(
                          onPressed: _busy ? null : _submit,
                          icon: _busy
                              ? const SizedBox(
                                  width: 18,
                                  height: 18,
                                  child: CircularProgressIndicator(strokeWidth: 2, color: Color(0xFF0E141A)),
                                )
                              : const Icon(Icons.send, size: 18),
                          style: IconButton.styleFrom(
                            backgroundColor: const Color(0xFF66E0FF),
                            foregroundColor: const Color(0xFF0E141A),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
