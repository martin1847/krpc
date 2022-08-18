import 'dart:convert';
import 'dart:io';

// 右键点击文件，debug
// https://github.com/jhomlala/betterplayer/blob/master/lib/src/subtitles/better_player_subtitles_factory.dart

void main(List<String> arguments) async {
  // parseSubtitlesFromNetwork(
  //     "https://v0.zhijisx.net/subtitle/0670C59462E64B008B19D813FC235A3B-3-3.vtt");
  parseSubtitlesFromFile("/Users/yyc/Downloads/3line.utfbom.vtt");
  // parseSubtitlesFromFile("/Users/yyc/Downloads/4line.utf8.vtt");
}

void parseSubtitlesFromFile(String? url) async {
  try {
    final file = File(url!);
    if (file.existsSync()) {
      final String fileContent = await file.readAsString();
      _parseString(fileContent);
    }
  } catch (exception) {
    print("Failed to read subtitles from file: $exception");
  }
}

void parseSubtitlesFromNetwork(String? url) async {
  try {
    final client = HttpClient();
    // final List<BetterPlayerSubtitle> subtitles = [];
    // for (final String? url in source.urls!) {
    final request = await client.getUrl(Uri.parse(url!));
    // source.headers?.keys.forEach((key) {
    //   final value = source.headers![key];
    //   if (value != null) {
    //     request.headers.add(key, value);
    //   }
    // });
    final response = await request.close();
    final data = await response.transform(const Utf8Decoder()).join();
    _parseString(data);
    // subtitles.addAll(cacheList);

    client.close();
  } catch (exception) {
    print("Failed to read subtitles from network: $exception");
  }
}

void _parseString(String value) {
  List<String> components = value.split('\r\n\r\n');
  if (components.length == 1) {
    components = value.split('\n\n');
  }

  // Skip parsing files with no cues
  if (components.length == 1) {
    return;
  }

  final bool isWebVTT = components.contains("WEBVTT");
  for (final component in components) {
    if (component.isEmpty) {
      continue;
    }
    //final subtitle = BetterPlayerSubtitle(component, isWebVTT);
    print("=== $isWebVTT === $component");
  }
}
