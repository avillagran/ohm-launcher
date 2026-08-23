// ============================================================================
//  AI CLIENT — provider-agnostic client (OpenAI-compatible)
// ============================================================================
//  Talks to any /v1/chat/completions endpoint (OpenAI, Ollama,
//  LM Studio, OpenRouter, Anthropic via proxy, Kimi, Codex, opencode, etc.).
//
//  The model response usually brings text + a component block
//  (JSON from DynamicWidgetEngine or QML from the bridge). [parseWidget] extracts the
//  the first ```json / ```qml / ```widget block and returns its content for
//  the UI to inject it hot.
// ============================================================================

import 'dart:convert';
import 'dart:io';

class AiMessage {
  const AiMessage({required this.role, required this.content});
  final String role; // 'system' | 'user' | 'assistant'
  final String content;

  Map<String, dynamic> toJson() => {'role': role, 'content': content};
}

class AiResponse {
  const AiResponse({
    required this.text,
    this.widgetSource,
    this.widgetFormat, // 'json' | 'qml' | null
  });

  final String text;
  final String? widgetSource;
  final String? widgetFormat;
}

class AiClient {
  AiClient({
    required this.baseUrl,
    required this.apiKey,
    required this.model,
    this.systemPrompt =
        'Eres Ohm, un asistente que construye la interfaz del launcher. '
        'Cuando crees o modifiques un componente de UI, responde con tu '
        'explicación y luego un bloque ```json (nodo del DynamicWidgetEngine: '
        'container/text/clock/tiling_layout/box/spacer/...) o ```qml '
        '(componente Quickshell). Solo un bloque de componente por respuesta.',
    this.temperature = 0.7,
  });

  final String baseUrl; // p.ej. https://api.openai.com/v1
  final String apiKey;
  final String model;
  final String systemPrompt;
  final double temperature;

  bool get configured =>
      baseUrl.isNotEmpty && model.isNotEmpty && (!_needsKey || apiKey.isNotEmpty);

  bool get _needsKey => !baseUrl.contains('localhost') && !baseUrl.contains('127.0.0.1');

  Future<AiResponse> chat(String prompt, {List<AiMessage>? history}) async {
    final messages = <AiMessage>[
      AiMessage(role: 'system', content: systemPrompt),
      ...?history,
      AiMessage(role: 'user', content: prompt),
    ];

    final uri = Uri.parse(baseUrl.endsWith('/') ? '${baseUrl}chat/completions' : '$baseUrl/chat/completions');
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 30);
    try {
      final request = await client.postUrl(uri);
      request.headers.contentType = ContentType.json;
      if (apiKey.isNotEmpty) {
        request.headers.set('Authorization', 'Bearer $apiKey');
      }
      request.write(jsonEncode({
        'model': model,
        'messages': messages.map((m) => m.toJson()).toList(),
        'temperature': temperature,
      }));
      final response = await request.close();
      final body = await response.transform(utf8.decoder).join();
      if (response.statusCode != 200) {
        return AiResponse(text: 'Error HTTP ${response.statusCode}: $body');
      }
      final decoded = jsonDecode(body) as Map<String, dynamic>;
      final choices = decoded['choices'] as List?;
      final content = choices != null && choices.isNotEmpty
          ? (choices.first['message']?['content'] as String? ?? '')
          : '';
      return parseWidget(content);
    } catch (e) {
      return AiResponse(text: 'ai_client_error: $e');
    } finally {
      client.close(force: true);
    }
  }

  /// Given the full response text, separates the explanation from the block
  /// of component (```json / ```qml / ```widget).
  static AiResponse parseWidget(String content) {
    final regex = RegExp(r'```(?:json|qml|widget)?\s*\n(.*?)```',
        dotAll: true, caseSensitive: false);
    final match = regex.firstMatch(content);
    if (match == null) {
      return AiResponse(text: content.trim());
    }
    final block = match.group(1)?.trim() ?? '';
    final lower = content.substring(match.start, match.start + 6).toLowerCase();
    final format = lower.contains('qml') ? 'qml' : 'json';
    final before = content.substring(0, match.start).trim();
    final after = content.substring(match.end).trim();
    final text = [before, after].where((s) => s.isNotEmpty).join('\n\n').trim();
    return AiResponse(text: text.isEmpty ? '(componente generado)' : text, widgetSource: block, widgetFormat: format);
  }
}
