import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../providers/download_providers.dart';

class SettingsScreen extends ConsumerStatefulWidget {
  const SettingsScreen({super.key});

  @override
  ConsumerState<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends ConsumerState<SettingsScreen> {
  bool _isUpdatingYtDlp = false;

  Future<void> _handleYtDlpUpdate() async {
    setState(() => _isUpdatingYtDlp = true);
    try {
      final channel = ref.read(settingsProvider).ytDlpChannel;
      final res = await ref
          .read(ytDlpVersionProvider.notifier)
          .updateEngine(channel);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            res.message.isNotEmpty
                ? res.message
                : 'yt-dlp durumu: ${res.status}',
          ),
          behavior: SnackBarBehavior.floating,
        ),
      );
    } finally {
      if (mounted) {
        setState(() => _isUpdatingYtDlp = false);
      }
    }
  }

  Future<void> _showCookiesDialog(String currentCookies) async {
    final controller = TextEditingController(text: currentCookies);
    final saved = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Cookie Desteği (Netscape Formatı)'),
        content: SizedBox(
          width: double.maxFinite,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Instagram, X (Twitter) veya giriş gerektiren içerikler için Netscape formatındaki cookies.txt içeriğini buraya yapıştırabilirsiniz.',
                style: TextStyle(fontSize: 12),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: controller,
                maxLines: 7,
                decoration: InputDecoration(
                  hintText: '# Netscape HTTP Cookie File\n.instagram.com\tTRUE...',
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => controller.clear(),
            child: const Text('Temizle'),
          ),
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('Vazgeç'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(ctx).pop(controller.text.trim()),
            child: const Text('Kaydet'),
          ),
        ],
      ),
    );
    controller.dispose();

    if (saved != null) {
      final current = ref.read(settingsProvider);
      await ref
          .read(settingsProvider.notifier)
          .updateSettings(current.copyWith(cookies: saved));
    }
  }

  @override
  Widget build(BuildContext context) {
    final settings = ref.watch(settingsProvider);
    final systemStatus = ref.watch(systemStatusProvider);
    final ytDlpVersion = ref.watch(ytDlpVersionProvider);
    final theme = Theme.of(context);

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
      children: [
        Text(
          'İndirme Tercihleri',
          style: theme.textTheme.titleSmall?.copyWith(
            color: theme.colorScheme.primary,
            fontWeight: FontWeight.bold,
          ),
        ),
        const SizedBox(height: 8),
        Card(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
          child: Column(
            children: [
              ListTile(
                leading: const Icon(Icons.high_quality_rounded),
                title: const Text('Varsayılan Kalite'),
                subtitle: Text(
                  settings.quality == 'audio'
                      ? 'Sadece Ses (MP3)'
                      : settings.quality == '720p'
                          ? '720p HD (Video + Ses)'
                          : 'En İyi Kalite (bv*+ba/b)',
                ),
                trailing: DropdownButton<String>(
                  value: settings.quality,
                  underline: const SizedBox.shrink(),
                  onChanged: (val) {
                    if (val != null) {
                      ref
                          .read(settingsProvider.notifier)
                          .updateSettings(settings.copyWith(quality: val));
                    }
                  },
                  items: const [
                    DropdownMenuItem(
                      value: 'best',
                      child: Text('En İyi'),
                    ),
                    DropdownMenuItem(
                      value: '720p',
                      child: Text('720p HD'),
                    ),
                    DropdownMenuItem(
                      value: 'audio',
                      child: Text('Sadece Ses'),
                    ),
                  ],
                ),
              ),
              const Divider(height: 1),
              SwitchListTile(
                secondary: const Icon(Icons.copy_all_rounded),
                title: const Text('Tekrarlanan URL Kontrolü'),
                subtitle: const Text(
                  'Aynı bağlantı tekrar indirildiğinde uyar',
                ),
                value: settings.checkDuplicates,
                onChanged: (val) {
                  ref
                      .read(settingsProvider.notifier)
                      .updateSettings(settings.copyWith(checkDuplicates: val));
                },
              ),
              const Divider(height: 1),
              SwitchListTile(
                secondary: const Icon(Icons.playlist_play_rounded),
                title: const Text('Toplu / Playlist İndirme'),
                subtitle: const Text(
                  'Bağlantı bir oynatma listesi içeriyorsa tümünü indir',
                ),
                value: settings.downloadPlaylist,
                onChanged: (val) {
                  ref
                      .read(settingsProvider.notifier)
                      .updateSettings(settings.copyWith(downloadPlaylist: val));
                },
              ),
              const Divider(height: 1),
              ListTile(
                leading: const Icon(Icons.cookie_outlined),
                title: const Text('Giriş / Cookie Desteği'),
                subtitle: Text(
                  settings.cookies.isEmpty
                      ? 'Tanımlı cookie yok (Instagram/X için isteğe bağlı)'
                      : 'Aktif (${settings.cookies.split('\n').length} satır)',
                ),
                trailing: const Icon(Icons.chevron_right_rounded),
                onTap: () => _showCookiesDialog(settings.cookies),
              ),
            ],
          ),
        ),
        const SizedBox(height: 20),
        Text(
          'yt-dlp Çekirdek Motoru',
          style: theme.textTheme.titleSmall?.copyWith(
            color: theme.colorScheme.primary,
            fontWeight: FontWeight.bold,
          ),
        ),
        const SizedBox(height: 8),
        Card(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    const Icon(Icons.terminal_rounded),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text(
                            'yt-dlp Sürümü',
                            style: TextStyle(fontWeight: FontWeight.w600),
                          ),
                          Text(
                            ytDlpVersion,
                            style: theme.textTheme.bodySmall?.copyWith(
                              color: theme.colorScheme.onSurfaceVariant,
                            ),
                          ),
                        ],
                      ),
                    ),
                    SegmentedButton<String>(
                      segments: const [
                        ButtonSegment(value: 'STABLE', label: Text('Stable')),
                        ButtonSegment(value: 'NIGHTLY', label: Text('Nightly')),
                      ],
                      selected: {settings.ytDlpChannel},
                      onSelectionChanged: (sel) {
                        ref.read(settingsProvider.notifier).updateSettings(
                              settings.copyWith(ytDlpChannel: sel.first),
                            );
                      },
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                FilledButton.tonalIcon(
                  onPressed: _isUpdatingYtDlp ? null : _handleYtDlpUpdate,
                  icon: _isUpdatingYtDlp
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.system_update_alt_rounded),
                  label: Text(
                    _isUpdatingYtDlp
                        ? 'yt-dlp Güncelleniyor...'
                        : 'yt-dlp Motorunu Güncelle',
                  ),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 20),
        Text(
          'Kayıt Klasörleri (Galeri / MediaStore)',
          style: theme.textTheme.titleSmall?.copyWith(
            color: theme.colorScheme.primary,
            fontWeight: FontWeight.bold,
          ),
        ),
        const SizedBox(height: 8),
        Card(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
          child: Column(
            children: [
              ListTile(
                leading: const Icon(Icons.video_library_outlined),
                title: const Text('Video Kayıt Dizini'),
                subtitle: Text(settings.videoFolder),
              ),
              const Divider(height: 1),
              ListTile(
                leading: const Icon(Icons.photo_library_outlined),
                title: const Text('Görsel Kayıt Dizini'),
                subtitle: Text(settings.imageFolder),
              ),
              const Divider(height: 1),
              ListTile(
                leading: const Icon(Icons.library_music_outlined),
                title: const Text('Ses Kayıt Dizini'),
                subtitle: Text(settings.audioFolder),
              ),
            ],
          ),
        ),
        const SizedBox(height: 20),
        Text(
          'Sistem ve Arka Plan İzinleri',
          style: theme.textTheme.titleSmall?.copyWith(
            color: theme.colorScheme.primary,
            fontWeight: FontWeight.bold,
          ),
        ),
        const SizedBox(height: 8),
        Card(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
          child: Column(
            children: [
              ListTile(
                leading: Icon(
                  systemStatus.batteryOptimizationIgnored
                      ? Icons.check_circle_rounded
                      : Icons.warning_amber_rounded,
                  color: systemStatus.batteryOptimizationIgnored
                      ? Colors.green
                      : theme.colorScheme.error,
                ),
                title: const Text('Pil Optimizasyonu Muafiyeti'),
                subtitle: Text(
                  systemStatus.batteryOptimizationIgnored
                      ? 'Aktif (Arka plan indirmeleri korunuyor)'
                      : 'Kısıtlı (Arka planda indirme kesilebilir)',
                ),
                trailing: systemStatus.batteryOptimizationIgnored
                    ? null
                    : FilledButton.tonal(
                        onPressed: () => ref
                            .read(systemStatusProvider.notifier)
                            .requestBatteryExemption(),
                        child: const Text('Muaf Tut'),
                      ),
              ),
              const Divider(height: 1),
              ListTile(
                leading: Icon(
                  systemStatus.notificationsGranted
                      ? Icons.check_circle_rounded
                      : Icons.notifications_off_outlined,
                  color: systemStatus.notificationsGranted
                      ? Colors.green
                      : theme.colorScheme.error,
                ),
                title: const Text('Bildirim İzni (Android 13+)'),
                subtitle: Text(
                  systemStatus.notificationsGranted
                      ? 'İzin verildi'
                      : 'İlerleme bildirimleri için izin gerekli',
                ),
                trailing: systemStatus.notificationsGranted
                    ? null
                    : FilledButton.tonal(
                        onPressed: () => ref
                            .read(systemStatusProvider.notifier)
                            .requestNotifications(),
                        child: const Text('İzin Ver'),
                      ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

