import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'core/models/download_item.dart';
import 'features/history/history_screen.dart';
import 'features/home/home_screen.dart';
import 'features/settings/settings_screen.dart';
import 'providers/download_providers.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(
    const ProviderScope(
      child: MedyaIndiriciApp(),
    ),
  );
}

class MedyaIndiriciApp extends StatelessWidget {
  const MedyaIndiriciApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Medya İndirici',
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.system,
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: const Color(0xFF006A6A),
        brightness: Brightness.light,
      ),
      darkTheme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: const Color(0xFF4FD8D8),
        brightness: Brightness.dark,
      ),
      home: const MainShellScreen(),
    );
  }
}

class MainShellScreen extends ConsumerStatefulWidget {
  const MainShellScreen({super.key});

  @override
  ConsumerState<MainShellScreen> createState() => _MainShellScreenState();
}

class _MainShellScreenState extends ConsumerState<MainShellScreen>
    with WidgetsBindingObserver {
  int _selectedIndex = 0;
  StreamSubscription<DownloadItem>? _statusPopupSub;
  final Map<String, DownloadStatus> _lastSeenStatus = {};

  static const List<Widget> _screens = [
    HomeScreen(),
    HistoryScreen(),
    SettingsScreen(),
  ];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      _subscribeToStatusPopups();
      await ref.read(systemStatusProvider.notifier).refresh();
      final status = ref.read(systemStatusProvider);
      if (!status.notificationsGranted) {
        await ref.read(systemStatusProvider.notifier).requestNotifications();
      }
    });
  }

  void _subscribeToStatusPopups() {
    final channel = ref.read(downloadChannelProvider);
    _statusPopupSub = channel.progressStream.listen((item) {
      final previous = _lastSeenStatus[item.id];
      _lastSeenStatus[item.id] = item.status;

      if (!mounted || previous == item.status) return;

      final messenger = ScaffoldMessenger.maybeOf(context);
      if (messenger == null) return;

      if (item.status == DownloadStatus.completed) {
        messenger
          ..hideCurrentSnackBar()
          ..showSnackBar(
            SnackBar(
              behavior: SnackBarBehavior.floating,
              content: Text('İndirildi: ${item.title}'),
              action: SnackBarAction(
                label: 'AÇ',
                onPressed: () {
                  ref.read(downloadsProvider.notifier).openMedia(item);
                },
              ),
            ),
          );
      } else if (item.status == DownloadStatus.failed) {
        final reason = item.errorMessage ?? 'Bilinmeyen hata';
        messenger
          ..hideCurrentSnackBar()
          ..showSnackBar(
            SnackBar(
              behavior: SnackBarBehavior.floating,
              backgroundColor: Theme.of(context).colorScheme.errorContainer,
              content: Text(
                'İndirilemedi: $reason',
                style: TextStyle(
                  color: Theme.of(context).colorScheme.onErrorContainer,
                ),
              ),
              action: SnackBarAction(
                label: 'TEKRAR DENE',
                textColor: Theme.of(context).colorScheme.onErrorContainer,
                onPressed: () {
                  ref.read(downloadsProvider.notifier).retryDownload(item.id);
                },
              ),
            ),
          );
      } else if (item.status == DownloadStatus.retrying) {
        messenger
          ..hideCurrentSnackBar()
          ..showSnackBar(
            SnackBar(
              behavior: SnackBarBehavior.floating,
              content: Text(
                item.errorMessage ?? 'Ağ hatası, yeniden deneniyor...',
              ),
            ),
          );
      }
    });
  }

  @override
  void dispose() {
    _statusPopupSub?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      ref.read(downloadsProvider.notifier).refresh();
      ref.read(systemStatusProvider.notifier).refresh();
    }
  }

  @override
  Widget build(BuildContext context) {
    final activeCount = ref.watch(activeDownloadsProvider).length;

    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'Medya İndirici',
          style: TextStyle(fontWeight: FontWeight.w700),
        ),
        centerTitle: false,
        actions: [
          IconButton(
            tooltip: 'Yenile',
            onPressed: () {
              ref.read(downloadsProvider.notifier).refresh();
              ref.read(systemStatusProvider.notifier).refresh();
            },
            icon: const Icon(Icons.sync_rounded),
          ),
        ],
      ),
      body: IndexedStack(
        index: _selectedIndex,
        children: _screens,
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _selectedIndex,
        onDestinationSelected: (idx) => setState(() => _selectedIndex = idx),
        destinations: [
          NavigationDestination(
            icon: Badge(
              isLabelVisible: activeCount > 0,
              label: Text('$activeCount'),
              child: const Icon(Icons.download_outlined),
            ),
            selectedIcon: Badge(
              isLabelVisible: activeCount > 0,
              label: Text('$activeCount'),
              child: const Icon(Icons.download_rounded),
            ),
            label: 'İndir',
          ),
          const NavigationDestination(
            icon: Icon(Icons.history_outlined),
            selectedIcon: Icon(Icons.history_rounded),
            label: 'Geçmiş',
          ),
          const NavigationDestination(
            icon: Icon(Icons.settings_outlined),
            selectedIcon: Icon(Icons.settings_rounded),
            label: 'Ayarlar',
          ),
        ],
      ),
    );
  }
}
