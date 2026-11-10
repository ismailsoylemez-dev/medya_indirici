class AppSettings {
  final String quality;
  final bool checkDuplicates;
  final bool downloadPlaylist;
  final String cookies;
  final String ytDlpChannel;
  final String videoFolder;
  final String imageFolder;
  final String audioFolder;

  const AppSettings({
    this.quality = 'best',
    this.checkDuplicates = true,
    this.downloadPlaylist = false,
    this.cookies = '',
    this.ytDlpChannel = 'STABLE',
    this.videoFolder = 'Movies/MedyaIndirici',
    this.imageFolder = 'Pictures/MedyaIndirici',
    this.audioFolder = 'Music/MedyaIndirici',
  });

  factory AppSettings.fromMap(Map<dynamic, dynamic> map) {
    return AppSettings(
      quality: (map['quality'] as String?) ?? 'best',
      checkDuplicates: (map['checkDuplicates'] as bool?) ?? true,
      downloadPlaylist: (map['downloadPlaylist'] as bool?) ?? false,
      cookies: (map['cookies'] as String?) ?? '',
      ytDlpChannel: (map['ytDlpChannel'] as String?) ?? 'STABLE',
      videoFolder: (map['videoFolder'] as String?) ?? 'Movies/MedyaIndirici',
      imageFolder: (map['imageFolder'] as String?) ?? 'Pictures/MedyaIndirici',
      audioFolder: (map['audioFolder'] as String?) ?? 'Music/MedyaIndirici',
    );
  }

  Map<String, dynamic> toMap() => {
        'quality': quality,
        'checkDuplicates': checkDuplicates,
        'downloadPlaylist': downloadPlaylist,
        'cookies': cookies,
        'ytDlpChannel': ytDlpChannel,
      };

  AppSettings copyWith({
    String? quality,
    bool? checkDuplicates,
    bool? downloadPlaylist,
    String? cookies,
    String? ytDlpChannel,
  }) {
    return AppSettings(
      quality: quality ?? this.quality,
      checkDuplicates: checkDuplicates ?? this.checkDuplicates,
      downloadPlaylist: downloadPlaylist ?? this.downloadPlaylist,
      cookies: cookies ?? this.cookies,
      ytDlpChannel: ytDlpChannel ?? this.ytDlpChannel,
      videoFolder: videoFolder,
      imageFolder: imageFolder,
      audioFolder: audioFolder,
    );
  }
}

class SystemStatus {
  final bool notificationsGranted;
  final bool batteryOptimizationIgnored;

  const SystemStatus({
    this.notificationsGranted = true,
    this.batteryOptimizationIgnored = true,
  });

  factory SystemStatus.fromMap(Map<dynamic, dynamic> map) {
    return SystemStatus(
      notificationsGranted: (map['notificationsGranted'] as bool?) ?? true,
      batteryOptimizationIgnored:
          (map['batteryOptimizationIgnored'] as bool?) ?? true,
    );
  }
}

