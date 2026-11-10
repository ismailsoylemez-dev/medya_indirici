enum DownloadStatus {
  queued,
  downloading,
  retrying,
  completed,
  failed,
  cancelled;

  static DownloadStatus fromString(String value) {
    switch (value.toLowerCase()) {
      case 'queued':
        return DownloadStatus.queued;
      case 'downloading':
        return DownloadStatus.downloading;
      case 'retrying':
        return DownloadStatus.retrying;
      case 'completed':
        return DownloadStatus.completed;
      case 'failed':
        return DownloadStatus.failed;
      case 'cancelled':
        return DownloadStatus.cancelled;
      default:
        return DownloadStatus.queued;
    }
  }

  bool get isActive =>
      this == DownloadStatus.queued ||
      this == DownloadStatus.downloading ||
      this == DownloadStatus.retrying;
}

class DownloadItem {
  final String id;
  final String url;
  final String title;
  final DownloadStatus status;
  final double progress;
  final int etaInSeconds;
  final String quality;
  final String mediaType;
  final String? filePath;
  final String? mediaUri;
  final String? mimeType;
  final String? errorMessage;
  final DateTime timestamp;

  const DownloadItem({
    required this.id,
    required this.url,
    required this.title,
    required this.status,
    required this.progress,
    required this.etaInSeconds,
    required this.quality,
    required this.mediaType,
    this.filePath,
    this.mediaUri,
    this.mimeType,
    this.errorMessage,
    required this.timestamp,
  });

  factory DownloadItem.fromMap(Map<dynamic, dynamic> map) {
    final tsMillis = (map['timestamp'] as num?)?.toInt() ??
        DateTime.now().millisecondsSinceEpoch;
    return DownloadItem(
      id: (map['id'] as String?) ?? '',
      url: (map['url'] as String?) ?? '',
      title: (map['title'] as String?) ?? 'Medya',
      status: DownloadStatus.fromString((map['status'] as String?) ?? 'queued'),
      progress: ((map['progress'] as num?)?.toDouble() ?? 0.0).clamp(0.0, 100.0),
      etaInSeconds: (map['etaInSeconds'] as num?)?.toInt() ?? -1,
      quality: (map['quality'] as String?) ?? 'best',
      mediaType: (map['mediaType'] as String?) ?? 'video',
      filePath: map['filePath'] as String?,
      mediaUri: map['mediaUri'] as String?,
      mimeType: map['mimeType'] as String?,
      errorMessage: map['errorMessage'] as String?,
      timestamp: DateTime.fromMillisecondsSinceEpoch(tsMillis),
    );
  }

  String get qualityLabel {
    switch (quality) {
      case 'audio':
        return 'Sadece Ses (MP3)';
      case '720p':
        return '720p HD';
      default:
        return 'En İyi Kalite';
    }
  }

  String get statusLabel {
    switch (status) {
      case DownloadStatus.queued:
        return 'Kuyrukta';
      case DownloadStatus.downloading:
        return 'İndiriliyor';
      case DownloadStatus.retrying:
        return 'Yeniden Deneniyor';
      case DownloadStatus.completed:
        return 'Tamamlandı';
      case DownloadStatus.failed:
        return 'Başarısız';
      case DownloadStatus.cancelled:
        return 'İptal Edildi';
    }
  }
}

