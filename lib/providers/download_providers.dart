import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../core/channels/download_channel.dart';
import '../core/models/app_settings.dart';
import '../core/models/download_item.dart';

final downloadChannelProvider = Provider<DownloadChannel>((ref) {
  return DownloadChannel();
});

class DownloadsNotifier extends Notifier<List<DownloadItem>> {
  StreamSubscription<DownloadItem>? _subscription;

  @override
  List<DownloadItem> build() {
    final channel = ref.watch(downloadChannelProvider);
    _subscription?.cancel();
    _subscription = channel.progressStream.listen(_onProgressEvent);
    ref.onDispose(() {
      _subscription?.cancel();
    });
    Future.microtask(refresh);
    return const [];
  }

  void _onProgressEvent(DownloadItem incoming) {
    final current = List<DownloadItem>.from(state);
    final idx = current.indexWhere((e) => e.id == incoming.id);
    if (idx >= 0) {
      current[idx] = incoming;
    } else {
      current.insert(0, incoming);
    }
    current.sort((a, b) => b.timestamp.compareTo(a.timestamp));
    state = current;
  }

  Future<void> refresh() async {
    try {
      final list = await ref.read(downloadChannelProvider).getHistory();
      state = list;
    } catch (_) {}
  }

  Future<StartDownloadResponse> startDownload({
    required String url,
    String? quality,
    bool force = false,
  }) async {
    final res = await ref.read(downloadChannelProvider).startDownload(
          url: url,
          quality: quality,
          force: force,
        );
    if (res.status == 'enqueued') {
      await refresh();
    }
    return res;
  }

  Future<void> cancelDownload(String id) async {
    await ref.read(downloadChannelProvider).cancelDownload(id);
    await refresh();
  }

  Future<void> retryDownload(String id) async {
    await ref.read(downloadChannelProvider).retryDownload(id);
    await refresh();
  }

  Future<void> deleteItem(String id) async {
    await ref.read(downloadChannelProvider).deleteHistoryItem(id);
    state = state.where((e) => e.id != id).toList();
  }

  Future<void> clearCompletedAndFailed() async {
    await ref.read(downloadChannelProvider).clearHistory();
    await refresh();
  }

  Future<bool> openMedia(DownloadItem item) async {
    if (item.mediaUri == null || item.mediaUri!.isEmpty) return false;
    return ref.read(downloadChannelProvider).openMedia(
          mediaUri: item.mediaUri!,
          mimeType: item.mimeType,
        );
  }
}

final downloadsProvider =
    NotifierProvider<DownloadsNotifier, List<DownloadItem>>(
  DownloadsNotifier.new,
);

final activeDownloadsProvider = Provider<List<DownloadItem>>((ref) {
  final all = ref.watch(downloadsProvider);
  return all.where((e) => e.status.isActive).toList();
});

final historyDownloadsProvider = Provider<List<DownloadItem>>((ref) {
  final all = ref.watch(downloadsProvider);
  return all.where((e) => !e.status.isActive).toList();
});

class SettingsNotifier extends Notifier<AppSettings> {
  @override
  AppSettings build() {
    Future.microtask(refresh);
    return const AppSettings();
  }

  Future<void> refresh() async {
    try {
      final loaded = await ref.read(downloadChannelProvider).getSettings();
      state = loaded;
    } catch (_) {}
  }

  Future<void> updateSettings(AppSettings updated) async {
    state = updated;
    try {
      final saved =
          await ref.read(downloadChannelProvider).saveSettings(updated);
      state = saved;
    } catch (_) {}
  }
}

final settingsProvider = NotifierProvider<SettingsNotifier, AppSettings>(
  SettingsNotifier.new,
);

class SystemStatusNotifier extends Notifier<SystemStatus> {
  @override
  SystemStatus build() {
    Future.microtask(refresh);
    return const SystemStatus();
  }

  Future<void> refresh() async {
    try {
      final status = await ref.read(downloadChannelProvider).getSystemStatus();
      state = status;
    } catch (_) {}
  }

  Future<bool> requestNotifications() async {
    final granted = await ref
        .read(downloadChannelProvider)
        .requestNotificationPermission();
    await refresh();
    return granted;
  }

  Future<void> requestBatteryExemption() async {
    await ref
        .read(downloadChannelProvider)
        .requestIgnoreBatteryOptimizations();
    await Future<void>.delayed(const Duration(seconds: 1));
    await refresh();
  }
}

final systemStatusProvider =
    NotifierProvider<SystemStatusNotifier, SystemStatus>(
  SystemStatusNotifier.new,
);

class YtDlpVersionNotifier extends Notifier<String> {
  @override
  String build() {
    Future.microtask(refresh);
    return 'Yükleniyor...';
  }

  Future<void> refresh() async {
    try {
      final v = await ref.read(downloadChannelProvider).getYtDlpVersion();
      state = v;
    } catch (_) {
      state = 'Hazır';
    }
  }

  Future<YtDlpUpdateResult> updateEngine(String channel) async {
    final result = await ref
        .read(downloadChannelProvider)
        .updateYtDlp(channel: channel);
    if (result.version.isNotEmpty) {
      state = result.version;
    } else {
      await refresh();
    }
    return result;
  }
}

final ytDlpVersionProvider = NotifierProvider<YtDlpVersionNotifier, String>(
  YtDlpVersionNotifier.new,
);

