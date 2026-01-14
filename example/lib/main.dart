import 'package:flutter/material.dart';
import 'package:offline_media_player/offline_media_player.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  String _status = 'Not initialized';
  bool _isInitialized = false;

  @override
  void initState() {
    super.initState();
    _initializePlugin();
  }

  Future<void> _initializePlugin() async {
    try {
      final success = await OfflineMediaPlayer.instance.initialize(userId: 'test_user');
      if (!mounted) return;
      setState(() {
        _isInitialized = success;
        _status = success ? 'Initialized successfully' : 'Failed to initialize';
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _status = 'Error: $e';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Offline Media Player Example'),
        ),
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text('Status: $_status'),
              const SizedBox(height: 20),
              if (_isInitialized)
                const Text('Plugin ready for downloads!'),
            ],
          ),
        ),
      ),
    );
  }
}
