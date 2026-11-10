import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/models/download_item.dart';
import '../../providers/download_providers.dart';

class HistoryScreen extends ConsumerStatefulWidget {
  const HistoryScreen({super.key});

  @override
  ConsumerState<HistoryScreen> createState() => _HistoryScreenState();
}

class _HistoryScreenState extends ConsumerState<HistoryScreen> {
  String _filter = 'all';

  @override
  Widget build(BuildContext context) {
    final historyItems = ref.watch(historyDownloadsProvider);
    final filtered = historyItems.where((item) {
      if (_filter == 'completed') {
        return item.status == DownloadStatus.completed;
      }
      if (_filter == 'failed') {
        return item.status == DownloadStatus.failed ||
            item.status == DownloadStatus.cancelled;
      }
      return true;
    }).toList();

    final theme = Theme.of(context);

    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
          child: Row(
            children: [
              Expanded(
                child: SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  child: Row(
                    children: [
                      FilterChip(
                        label: const Text('Tümü'),
                        selected: _filter == 'all',
                        onSelected: (_) => setState(() => _filter = 'all'),
                      ),
                      const SizedBox(width: 8),
                      FilterChip(
                        label: const Text('Tamamlanan'),
                        selected: _filter == 'completed',
                        onSelected: (_) =>
                            setState(() => _filter = 'completed'),
                      ),
                      const SizedBox(width: 8),
                      FilterChip(
                        label: const Text('Hatalı / İptal'),
                        selected: _filter == 'failed',
                        onSelected: (_) => setState(() => _filter = 'failed'),
                      ),
                    ],
                  ),
                ),
              ),
              if (historyItems.isNotEmpty)
                IconButton(
                  tooltip: 'Geçmişi Temizle',
                  onPressed: () async {
                    final confirm = await showDialog<bool>(
                      context: context,
                      builder: (ctx) => AlertDialog(
                        title: const Text('Geçmişi Temizle'),
                        content: const Text(
                          'Tüm indirme geçmişi kayıtları silinsin mi? (Galerideki dosyalarınız silinmez)',
                        ),
                        actions: [
                          TextButton(
                            onPressed: () => Navigator.of(ctx).pop(false),
                            child: const Text('Vazgeç'),
                          ),
                          FilledButton(
                            onPressed: () => Navigator.of(ctx).pop(true),
                            child: const Text('Temizle'),
                          ),
                        ],
                      ),
                    );
                    if (confirm == true) {
                      await ref
                          .read(downloadsProvider.notifier)
                          .clearCompletedAndFailed();
                    }
                  },
                  icon: const Icon(Icons.delete_sweep_outlined),
                ),
            ],
          ),
        ),
        Expanded(
          child: RefreshIndicator(
            onRefresh: () => ref.read(downloadsProvider.notifier).refresh(),
            child: filtered.isEmpty
                ? ListView(
                    children: [
                      const SizedBox(height: 100),
                      Icon(
                        Icons.history_rounded,
                        size: 56,
                        color: theme.colorScheme.outline,
                      ),
                      const SizedBox(height: 12),
                      Center(
                        child: Text(
                          'Henüz indirme geçmişi bulunmuyor',
                          style: theme.textTheme.bodyMedium?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ),
                    ],
                  )
                : ListView.builder(
                    padding: const EdgeInsets.fromLTRB(16, 4, 16, 24),
                    itemCount: filtered.length,
                    itemBuilder: (context, index) {
                      final item = filtered[index];
                      return _HistoryCard(item: item);
                    },
                  ),
          ),
        ),
      ],
    );
  }
}

class _HistoryCard extends ConsumerWidget {
  final DownloadItem item;

  const _HistoryCard({required this.item});

  IconData _mediaIcon() {
    switch (item.mediaType) {
      case 'image':
        return Icons.image_rounded;
      case 'audio':
        return Icons.audiotrack_rounded;
      default:
        return Icons.movie_rounded;
    }
  }

  String _formatDate(DateTime dt) {
    final d = dt.day.toString().padLeft(2, '0');
    final m = dt.month.toString().padLeft(2, '0');
    final h = dt.hour.toString().padLeft(2, '0');
    final min = dt.minute.toString().padLeft(2, '0');
    return '$d.$m.${dt.year} $h:$min';
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final isCompleted = item.status == DownloadStatus.completed;

    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: isCompleted
            ? () async {
                final opened = await ref
                    .read(downloadsProvider.notifier)
                    .openMedia(item);
                if (!opened && context.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(
                      content: Text('Medya dosyası açılamadı veya taşınmış.'),
                    ),
                  );
                }
              }
            : null,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  CircleAvatar(
                    backgroundColor: isCompleted
                        ? theme.colorScheme.primaryContainer
                        : theme.colorScheme.errorContainer,
                    foregroundColor: isCompleted
                        ? theme.colorScheme.onPrimaryContainer
                        : theme.colorScheme.onErrorContainer,
                    child: Icon(
                      isCompleted ? _mediaIcon() : Icons.error_outline_rounded,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          item.title,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.titleSmall?.copyWith(
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          item.url,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ],
                    ),
                  ),
                  PopupMenuButton<String>(
                    onSelected: (action) async {
                      switch (action) {
                        case 'open':
                          await ref
                              .read(downloadsProvider.notifier)
                              .openMedia(item);
                          break;
                        case 'retry':
                          await ref
                              .read(downloadsProvider.notifier)
                              .retryDownload(item.id);
                          break;
                        case 'copy':
                          await Clipboard.setData(
                            ClipboardData(text: item.url),
                          );
                          if (context.mounted) {
                            ScaffoldMessenger.of(context).showSnackBar(
                              const SnackBar(
                                content: Text('Bağlantı panoya kopyalandı'),
                              ),
                            );
                          }
                          break;
                        case 'delete':
                          await ref
                              .read(downloadsProvider.notifier)
                              .deleteItem(item.id);
                          break;
                      }
                    },
                    itemBuilder: (ctx) => [
                      if (isCompleted)
                        const PopupMenuItem(
                          value: 'open',
                          child: ListTile(
                            leading: Icon(Icons.open_in_new_rounded),
                            title: Text('Medyayı Aç'),
                            contentPadding: EdgeInsets.zero,
                          ),
                        ),
                      const PopupMenuItem(
                        value: 'retry',
                        child: ListTile(
                          leading: Icon(Icons.refresh_rounded),
                          title: Text('Tekrar İndir'),
                          contentPadding: EdgeInsets.zero,
                        ),
                      ),
                      const PopupMenuItem(
                        value: 'copy',
                        child: ListTile(
                          leading: Icon(Icons.copy_rounded),
                          title: Text('Bağlantıyı Kopyala'),
                          contentPadding: EdgeInsets.zero,
                        ),
                      ),
                      const PopupMenuItem(
                        value: 'delete',
                        child: ListTile(
                          leading: Icon(Icons.delete_outline_rounded),
                          title: Text('Kaydı Sil'),
                          contentPadding: EdgeInsets.zero,
                        ),
                      ),
                    ],
                  ),
                ],
              ),
              if (item.filePath != null && item.filePath!.isNotEmpty) ...[
                const SizedBox(height: 8),
                Text(
                  item.filePath!,
                  style: theme.textTheme.labelSmall?.copyWith(
                    color: theme.colorScheme.primary,
                  ),
                ),
              ],
              if (!isCompleted &&
                  item.errorMessage != null &&
                  item.errorMessage!.isNotEmpty) ...[
                const SizedBox(height: 8),
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 6,
                  ),
                  decoration: BoxDecoration(
                    color: theme.colorScheme.errorContainer.withValues(
                      alpha: 0.5,
                    ),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Row(
                    children: [
                      Expanded(
                        child: Text(
                          item.errorMessage!,
                          style: theme.textTheme.labelSmall?.copyWith(
                            color: theme.colorScheme.onErrorContainer,
                          ),
                        ),
                      ),
                      TextButton.icon(
                        onPressed: () => ref
                            .read(downloadsProvider.notifier)
                            .retryDownload(item.id),
                        icon: const Icon(Icons.refresh_rounded, size: 16),
                        label: const Text('Tekrar Dene'),
                      ),
                    ],
                  ),
                ),
              ],
              const SizedBox(height: 8),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    '${item.statusLabel} • ${item.qualityLabel}',
                    style: theme.textTheme.labelSmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                  Text(
                    _formatDate(item.timestamp),
                    style: theme.textTheme.labelSmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

