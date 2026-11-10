import 'dart:async';
import 'package:flutter/services.dart';
import '../models/app_settings.dart';
import '../models/download_item.dart';

class StartDownloadResponse {
  final String status;
  final String? downloadId;
  final String? url;
  final String? message;

  const StartDownloadResponse({
    required this.status,
    this.downloadId,
    this.url,
    this.message,
  });

  factory StartDownloadResponse.fromMap(Map<dynamic, dynamic> map) {
    return StartDownloadResponse(
      status: (map['status'] as String?) ?? 'error',
      downloadId: map['downloadId'] as String?,
      url: map['url'] as String?,
      message: map['message'] as String?,
    );
  }
}

class YtDlpUpdateResult {
  final String status;
  final String version;
  final String message;

  const YtDlpUpdateResult({
    required this.status,
    required this.version,
    required this.message,
  });

  factory YtDlpUpdateResult.fromMap(Map<dynamic, dynamic> map) {
    return YtDlpUpdateResult(
      status: (map['status'] as String?) ?? 'ERROR',
      version: (map['version'] as String?) ?? '',
      message: (map['message'] as String?) ?? '',
    );
  }
}

class DownloadChannel {
  static const MethodChannel _methodChannel =
      MethodChannel('com.ismail.medya_indirici/commands');
  static const EventChannel _eventChannel =
      EventChannel('com.ismail.medya_indirici/progress');

  Stream<DownloadItem>? _progressStream;

  Stream<DownloadItem> get progressStream {
    _progressStream ??= _eventChannel.receiveBroadcastStream().map((event) {
      final map = Map<dynamic, dynamic>.from(event as Map);
      return DownloadItem.fromMap(map);
    });
    return _progressStream!;
  }

  Future<StartDownloadResponse> startDownload({
    required String url,
    String? quality,
    bool force = false,
  }) async {
    final result = await _methodChannel.invokeMethod<Map<dynamic, dynamic>>(
      'startDownload',
      {
        'url': url,
        'quality': ?quality,
        'force': force,
      },
    );
    return StartDownloadResponse.fromMap(result ?? const {});
  }

  Future<void> cancelDownload(String id) async {
    await _methodChannel.invokeMethod<bool>('cancelDownload', {'id': id});
  }

  Future<bool> retryDownload(String id) async {
    final res =
        await _methodChannel.invokeMethod<bool>('retryDownload', {'id': id});
    return res ?? false;
  }

  Future<List<DownloadItem>> getHistory() async {
    final list = await _methodChannel.invokeMethod<List<dynamic>>('getHistory');
    if (list == null) return const [];
    return list
        .whereType<Map<dynamic, dynamic>>()
        .map(DownloadItem.fromMap)
        .toList();
  }

  Future<void> deleteHistoryItem(String id) async {
    await _methodChannel.invokeMethod<bool>('deleteHistoryItem', {'id': id});
  }

  Future<void> clearHistory() async {
    await _methodChannel.invokeMethod<bool>('clearHistory');
  }

  Future<AppSettings> getSettings() async {
    final map =
        await _methodChannel.invokeMethod<Map<dynamic, dynamic>>('getSettings');
    return AppSettings.fromMap(map ?? const {});
  }

  Future<AppSettings> saveSettings(AppSettings settings) async {
    final map = await _methodChannel.invokeMethod<Map<dynamic, dynamic>>(
      'saveSettings',
      settings.toMap(),
    );
    return AppSettings.fromMap(map ?? settings.toMap());
  }

  Future<String> getYtDlpVersion() async {
    final version =
        await _methodChannel.invokeMethod<String>('getYtDlpVersion');
    return version ?? 'Hazır';
  }

  Future<YtDlpUpdateResult> updateYtDlp({String? channel}) async {
    final map = await _methodChannel.invokeMethod<Map<dynamic, dynamic>>(
      'updateYtDlp',
      {'channel': ?channel},
    );
    return YtDlpUpdateResult.fromMap(map ?? const {});
  }

  Future<bool> openMedia({
    required String mediaUri,
    String? mimeType,
  }) async {
    final res = await _methodChannel.invokeMethod<bool>(
      'openMedia',
      {
        'mediaUri': mediaUri,
        'mimeType': ?mimeType,
      },
    );
    return res ?? false;
  }

  Future<SystemStatus> getSystemStatus() async {
    final map = await _methodChannel
        .invokeMethod<Map<dynamic, dynamic>>('getSystemStatus');
    return SystemStatus.fromMap(map ?? const {});
  }

  Future<bool> requestNotificationPermission() async {
    final res = await _methodChannel
        .invokeMethod<bool>('requestNotificationPermission');
    return res ?? false;
  }

  Future<bool> requestIgnoreBatteryOptimizations() async {
    final res = await _methodChannel
        .invokeMethod<bool>('requestIgnoreBatteryOptimizations');
    return res ?? false;
  }
}

