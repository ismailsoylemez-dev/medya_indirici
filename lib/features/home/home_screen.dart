import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/models/download_item.dart';
import '../../providers/download_providers.dart';

class HomeScreen extends ConsumerStatefulWidget {
  const HomeScreen({super.key});

  @override
  ConsumerState<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends ConsumerState<HomeScreen> {
  final TextEditingController _urlController = TextEditingController();
  String? _selectedQualityOverride;
  bool _isSubmitting = false;

  @override
  void dispose() {
    _urlController.dispose();
    super.dispose();
  }

  Future<void> _pasteFromClipboard() async {
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    final text = data?.text?.trim();
    if (text != null && text.isNotEmpty) {
      setState(() {
        _urlController.text = text;
      });
    }
  }

  Future<void> _submitDownload({bool force = false}) async {
    final rawUrl = _urlController.text.trim();
    if (rawUrl.isEmpty) {
      _showSnack('Lütfen bir medya bağlantısı girin veya yapıştırın.');
      return;
    }

    setState(() => _isSubmitting = true);
    try {
      final defaultQuality = ref.read(settingsProvider).quality;
      final qualityToUse = _selectedQualityOverride ?? defaultQuality;

      final response = await ref.read(downloadsProvider.notifier).startDownload(
            url: rawUrl,
            quality: qualityToUse,
            force: force,
          );

      if (!mounted) return;

      switch (response.status) {
        case 'enqueued':
          _urlController.clear();
          _showSnack('İndirme kuyruğa alındı.');
          break;
        case 'duplicate':
          final shouldForce = await _showDuplicateDialog(
            response.url ?? rawUrl,
          );
          if (shouldForce == true && mounted) {
            await _submitDownload(force: true);
          }
          break;
        case 'already_active':
          _showSnack(
            response.message ?? 'Bu bağlantı şu anda zaten indiriliyor.',
          );
          break;
        case 'invalid_url':
          _showSnack(
            response.message ?? 'Geçerli bir http/https bağlantısı bulunamadı.',
          );
          break;
        default:
          _showSnack(response.message ?? 'İndirme başlatılamadı.');
      }
    } finally {
      if (mounted) {
        setState(() => _isSubmitting = false);
      }
    }
  }

  Future<bool?> _showDuplicateDialog(String url) {
    return showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Daha Önce İndirildi'),
        content: Text(
          'Bu bağlantı daha önce başarıyla indirildi:\n\n$url\n\nTekrar indirmek istiyor musunuz?',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Vazgeç'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Tekrar İndir'),
          ),
        ],
      ),
    );
  }

  void _showSnack(String message) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Text(message),
          behavior: SnackBarBehavior.floating,
        ),
      );
  }

  @override
  Widget build(BuildContext context) {
    final settings = ref.watch(settingsProvider);
    final systemStatus = ref.watch(systemStatusProvider);
    final activeDownloads = ref.watch(activeDownloadsProvider);
    final effectiveQuality = _selectedQualityOverride ?? settings.quality;
    final theme = Theme.of(context);

    return RefreshIndicator(
      onRefresh: () async {
        await ref.read(downloadsProvider.notifier).refresh();
        await ref.read(systemStatusProvider.notifier).refresh();
      },
      child: ListView(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
        children: [
          if (!systemStatus.notificationsGranted ||
              !systemStatus.batteryOptimizationIgnored)
            _SystemSetupBanner(
              notificationsGranted: systemStatus.notificationsGranted,
              batteryIgnored: systemStatus.batteryOptimizationIgnored,
              onRequestNotifications: () => ref
                  .read(systemStatusProvider.notifier)
                  .requestNotifications(),
              onRequestBattery: () => ref
                  .read(systemStatusProvider.notifier)
                  .requestBatteryExemption(),
            ),
          Card(
            elevation: 0,
            color: theme.colorScheme.surfaceContainerHighest.withValues(
              alpha: 0.55,
            ),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(20),
            ),
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      Icon(
                        Icons.add_link_rounded,
                        color: theme.colorScheme.primary,
                      ),
                      const SizedBox(width: 8),
                      Text(
                        'Manuel Bağlantı İndir',
                        style: theme.textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _urlController,
                    keyboardType: TextInputType.url,
                    textInputAction: TextInputAction.go,
                    onSubmitted: (_) => _submitDownload(),
                    decoration: InputDecoration(
                      hintText: 'https://... bağlantısını buraya yapıştırın',
                      filled: true,
                      fillColor: theme.colorScheme.surface,
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(14),
                        borderSide: BorderSide.none,
                      ),
                      prefixIcon: const Icon(Icons.link_rounded),
                      suffixIcon: IconButton(
                        tooltip: 'Temizle',
                        onPressed: () => _urlController.clear(),
                        icon: const Icon(Icons.clear_rounded),
                      ),
                    ),
                  ),
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      ChoiceChip(
                        label: const Text('En İyi (Video+Ses)'),
                        selected: effectiveQuality == 'best',
                        onSelected: (_) =>
                            setState(() => _selectedQualityOverride = 'best'),
                      ),
                      ChoiceChip(
                        label: const Text('720p HD'),
                        selected: effectiveQuality == '720p',
                        onSelected: (_) =>
                            setState(() => _selectedQualityOverride = '720p'),
                      ),
                      ChoiceChip(
                        label: const Text('Sadece Ses (MP3)'),
                        selected: effectiveQuality == 'audio',
                        onSelected: (_) =>
                            setState(() => _selectedQualityOverride = 'audio'),
                      ),
                    ],
                  ),
                  const SizedBox(height: 14),
                  Row(
                    children: [
                      Expanded(
                        child: OutlinedButton.icon(
                          onPressed: _pasteFromClipboard,
                          icon: const Icon(Icons.content_paste_rounded),
                          label: const Text('Panodan Yapıştır'),
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: FilledButton.icon(
                          onPressed: _isSubmitting ? null : () => _submitDownload(),
                          icon: _isSubmitting
                              ? const SizedBox(
                                  width: 18,
                                  height: 18,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                )
                              : const Icon(Icons.download_rounded),
                          label: const Text('İndir'),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 20),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                'Aktif İndirmeler (${activeDownloads.length})',
                style: theme.textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.w700,
                ),
              ),
              if (activeDownloads.isNotEmpty)
                const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
            ],
          ),
          const SizedBox(height: 10),
          if (activeDownloads.isEmpty)
            Card(
              elevation: 0,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(16),
                side: BorderSide(
                  color: theme.colorScheme.outlineVariant.withValues(alpha: 0.6),
                ),
              ),
              child: Padding(
                padding: const EdgeInsets.all(20),
                child: Column(
                  children: [
                    Icon(
                      Icons.bolt_rounded,
                      size: 40,
                      color: theme.colorScheme.primary,
                    ),
                    const SizedBox(height: 10),
                    Text(
                      'Hızlı Paylaşım ile Arka Planda İndirme Hazır',
                      textAlign: TextAlign.center,
                      style: theme.textTheme.titleSmall?.copyWith(
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      'YouTube, Instagram, X, TikTok veya herhangi bir uygulamada "Paylaş" butonuna basıp "Medya İndirici"yi seçin. Bulunduğunuz uygulamadan çıkmadan medya galeriye kaydedilir.',
                      textAlign: TextAlign.center,
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ),
              ),
            )
          else
            ...activeDownloads.map(
              (item) => _ActiveDownloadCard(
                item: item,
                onCancel: () => ref
                    .read(downloadsProvider.notifier)
                    .cancelDownload(item.id),
              ),
            ),
        ],
      ),
    );
  }
}

class _SystemSetupBanner extends StatelessWidget {
  final bool notificationsGranted;
  final bool batteryIgnored;
  final VoidCallback onRequestNotifications;
  final VoidCallback onRequestBattery;

  const _SystemSetupBanner({
    required this.notificationsGranted,
    required this.batteryIgnored,
    required this.onRequestNotifications,
    required this.onRequestBattery,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      elevation: 0,
      margin: const EdgeInsets.only(bottom: 14),
      color: theme.colorScheme.tertiaryContainer,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  Icons.shield_outlined,
                  color: theme.colorScheme.onTertiaryContainer,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Arka Plan İndirme İzinleri',
                    style: theme.textTheme.titleSmall?.copyWith(
                      fontWeight: FontWeight.bold,
                      color: theme.colorScheme.onTertiaryContainer,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              'Xiaomi/Samsung gibi cihazlarda arka plan indirmelerinin kesilmemesi ve bildirimlerin görünmesi için izinleri tamamlayın.',
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onTertiaryContainer,
              ),
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                if (!notificationsGranted)
                  FilledButton.tonalIcon(
                    onPressed: onRequestNotifications,
                    icon: const Icon(Icons.notifications_active_outlined, size: 18),
                    label: const Text('Bildirim İzni Ver'),
                  ),
                if (!batteryIgnored)
                  FilledButton.tonalIcon(
                    onPressed: onRequestBattery,
                    icon: const Icon(Icons.battery_saver_rounded, size: 18),
                    label: const Text('Pil Kısıtlamasını Kaldır'),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _ActiveDownloadCard extends StatelessWidget {
  final DownloadItem item;
  final VoidCallback onCancel;

  const _ActiveDownloadCard({
    required this.item,
    required this.onCancel,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isIndeterminate =
        item.status == DownloadStatus.queued ||
        item.status == DownloadStatus.retrying ||
        item.progress <= 0;

    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    item.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.titleSmall?.copyWith(
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                IconButton(
                  tooltip: 'İptal Et',
                  onPressed: onCancel,
                  icon: const Icon(Icons.close_rounded),
                  visualDensity: VisualDensity.compact,
                ),
              ],
            ),
            Text(
              item.url,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 10),
            ClipRRect(
              borderRadius: BorderRadius.circular(6),
              child: LinearProgressIndicator(
                value: isIndeterminate ? null : (item.progress / 100.0),
                minHeight: 7,
              ),
            ),
            const SizedBox(height: 8),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  isIndeterminate
                      ? (item.errorMessage ?? item.statusLabel)
                      : '%${item.progress.toStringAsFixed(1)}'
                          '${item.etaInSeconds > 0 ? ' • ${item.etaInSeconds}sn kaldı' : ''}',
                  style: theme.textTheme.labelMedium?.copyWith(
                    color: theme.colorScheme.primary,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                Text(
                  item.qualityLabel,
                  style: theme.textTheme.labelSmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

