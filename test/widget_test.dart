import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:medya_indirici/core/channels/download_channel.dart';
import 'package:medya_indirici/core/models/app_settings.dart';
import 'package:medya_indirici/core/models/download_item.dart';
import 'package:medya_indirici/main.dart';
import 'package:medya_indirici/providers/download_providers.dart';

class FakeDownloadChannel extends DownloadChannel {
  final StreamController<DownloadItem> controller =
      StreamController<DownloadItem>.broadcast();

  final List<DownloadItem> items = [];
  AppSettings settings = const AppSettings();
  final List<String> retriedIds = [];
  final List<String> cancelledIds = [];
  final List<String> openedUris = [];
  bool simulateDuplicateNext = false;

  @override
  Stream<DownloadItem> get progressStream => controller.stream;

  @override
  Future<List<DownloadItem>> getHistory() async => List.unmodifiable(items);

  @override
  Future<AppSettings> getSettings() async => settings;

  @override
  Future<AppSettings> saveSettings(AppSettings settings) async {
    this.settings = settings;
    return settings;
  }

  @override
  Future<SystemStatus> getSystemStatus() async => const SystemStatus(
        notificationsGranted: true,
        batteryOptimizationIgnored: true,
      );

  @override
  Future<String> getYtDlpVersion() async => '2025.02.19';

  @override
  Future<StartDownloadResponse> startDownload({
    required String url,
    String? quality,
    bool force = false,
  }) async {
    if (!url.startsWith('http')) {
      return const StartDownloadResponse(
        status: 'invalid_url',
        message: 'Geçerli bir http/https bağlantısı bulunamadı.',
      );
    }
    if (simulateDuplicateNext && !force) {
      return StartDownloadResponse(
        status: 'duplicate',
        url: url,
        message: 'Bu bağlantı daha önce indirildi.',
      );
    }
    final newItem = DownloadItem(
      id: 'dl_${items.length + 1}',
      url: url,
      title: 'Test Medya ${items.length + 1}',
      status: DownloadStatus.downloading,
      progress: 55.0,
      etaInSeconds: 10,
      quality: quality ?? 'best',
      mediaType: 'video',
      timestamp: DateTime.now(),
    );
    items.insert(0, newItem);
    controller.add(newItem);
    return StartDownloadResponse(
      status: 'enqueued',
      downloadId: newItem.id,
      url: url,
    );
  }

  @override
  Future<bool> retryDownload(String id) async {
    retriedIds.add(id);
    final idx = items.indexWhere((e) => e.id == id);
    if (idx >= 0) {
      final old = items[idx];
      final updated = DownloadItem(
        id: old.id,
        url: old.url,
        title: old.title,
        status: DownloadStatus.downloading,
        progress: 10.0,
        etaInSeconds: 20,
        quality: old.quality,
        mediaType: old.mediaType,
        timestamp: DateTime.now(),
      );
      items[idx] = updated;
      controller.add(updated);
    }
    return true;
  }

  @override
  Future<void> cancelDownload(String id) async {
    cancelledIds.add(id);
  }

  @override
  Future<bool> openMedia({
    required String mediaUri,
    String? mimeType,
  }) async {
    openedUris.add(mediaUri);
    return true;
  }

  void dispose() {
    controller.close();
  }
}

void main() {
  test('DownloadItem model serialization and status labels', () {
    final item = DownloadItem.fromMap({
      'id': '123',
      'url': 'https://example.com/video.mp4',
      'title': 'Örnek Video',
      'status': 'downloading',
      'progress': 45.5,
      'etaInSeconds': 12,
      'quality': '720p',
      'mediaType': 'video',
      'timestamp': 1700000000000,
    });

    expect(item.id, '123');
    expect(item.status, DownloadStatus.downloading);
    expect(item.status.isActive, isTrue);
    expect(item.qualityLabel, '720p HD');
    expect(item.statusLabel, 'İndiriliyor');
  });

  testWidgets(
    'Uçtan uca indirme, ilerleme, tamamlanma popup, hata popup ve retry akışı testi',
    (WidgetTester tester) async {
      final fakeChannel = FakeDownloadChannel();
      addTearDown(fakeChannel.dispose);

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            downloadChannelProvider.overrideWithValue(fakeChannel),
          ],
          child: const MedyaIndiriciApp(),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));

      // 1. Manuel URL gir ve İndir'e tıkla -> Kuyruğa alındı popup + aktif indirme kartı
      await tester.enterText(
        find.byType(TextField),
        'https://example.com/video1',
      );
      await tester.tap(find.widgetWithText(FilledButton, 'İndir'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.text('İndirme kuyruğa alındı.'), findsOneWidget);
      expect(find.text('Test Medya 1'), findsOneWidget);
      expect(find.textContaining('%55.0 • 10sn kaldı'), findsOneWidget);

      // 2. İndirme tamamlandığında "İndirildi: Test Medya 1" popup ve AÇ aksiyonu
      final completedItem = DownloadItem(
        id: 'dl_1',
        url: 'https://example.com/video1',
        title: 'Test Medya 1',
        status: DownloadStatus.completed,
        progress: 100.0,
        etaInSeconds: 0,
        quality: 'best',
        mediaType: 'video',
        filePath: 'Movies/MedyaIndirici/Test_Medya_1.mp4',
        mediaUri: 'content://media/external/video/media/101',
        mimeType: 'video/mp4',
        timestamp: DateTime.now(),
      );
      fakeChannel.items[0] = completedItem;
      fakeChannel.controller.add(completedItem);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.text('İndirildi: Test Medya 1'), findsOneWidget);
      expect(find.text('AÇ'), findsOneWidget);

      // 3. Hata durumu simülasyonu -> "İndirilemedi: Giriş / Cookie gerekli" popup ve TEKRAR DENE aksiyonu
      final failedItem = DownloadItem(
        id: 'dl_2',
        url: 'https://instagram.com/p/secret',
        title: 'Gizli Medya',
        status: DownloadStatus.failed,
        progress: 0.0,
        etaInSeconds: -1,
        quality: 'best',
        mediaType: 'video',
        errorMessage: 'Giriş / Cookie gerekli',
        timestamp: DateTime.now(),
      );
      fakeChannel.items.insert(0, failedItem);
      fakeChannel.controller.add(failedItem);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(
        find.text('İndirilemedi: Giriş / Cookie gerekli'),
        findsOneWidget,
      );
      expect(find.text('TEKRAR DENE'), findsOneWidget);

      // 4. Geçmiş ekranına geç ve "Tekrar Dene" butonunu test et
      await tester.tap(find.text('Geçmiş'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.text('Gizli Medya'), findsOneWidget);
      expect(find.text('Tekrar Dene'), findsOneWidget);

      await tester.tap(find.text('Tekrar Dene'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(fakeChannel.retriedIds, contains('dl_2'));
    },
  );

  testWidgets(
    'Mükerrer (Duplicate) URL uyarı diyaloğu ve Tekrar İndir onayı testi',
    (WidgetTester tester) async {
      final fakeChannel = FakeDownloadChannel()..simulateDuplicateNext = true;
      addTearDown(fakeChannel.dispose);

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            downloadChannelProvider.overrideWithValue(fakeChannel),
          ],
          child: const MedyaIndiriciApp(),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));

      await tester.enterText(
        find.byType(TextField),
        'https://youtube.com/watch?v=dup123',
      );
      await tester.tap(find.widgetWithText(FilledButton, 'İndir'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      // Mükerrer uyarı popup diyaloğu görünmeli
      expect(find.text('Daha Önce İndirildi'), findsOneWidget);
      expect(find.text('Tekrar İndir'), findsOneWidget);

      await tester.tap(find.text('Tekrar İndir'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      // Zorla indirme (force=true) ile kuyruğa alınmalı
      expect(fakeChannel.items.length, 1);
      expect(fakeChannel.items.first.url, 'https://youtube.com/watch?v=dup123');
    },
  );
}
