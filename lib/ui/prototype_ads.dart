import 'dart:async';

import 'package:flutter/material.dart';

import '../app/ad_service.dart';
import 'palette.dart';

/// Заглушка рекламы для прототипа: честно говорит, что рекламы ещё нет,
/// и выдаёт награду после короткого ожидания.
class PrototypeAdService implements AdService {
  PrototypeAdService(this.navigatorKey);

  final GlobalKey<NavigatorState> navigatorKey;

  @override
  Future<bool> showRewarded() async {
    final context = navigatorKey.currentContext;
    if (context == null) return false;
    final result = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (_) => const _PrototypeAdDialog(),
    );
    return result ?? false;
  }
}

class _PrototypeAdDialog extends StatefulWidget {
  const _PrototypeAdDialog();

  @override
  State<_PrototypeAdDialog> createState() => _PrototypeAdDialogState();
}

class _PrototypeAdDialogState extends State<_PrototypeAdDialog> {
  int _left = 2;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 1), (t) {
      setState(() => _left -= 1);
      if (_left <= 0) t.cancel();
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ready = _left <= 0;
    return AlertDialog(
      backgroundColor: Palette.paper,
      title: const Text('Здесь будет реклама'),
      content: Text(
        ready
            ? 'В тестовой версии рекламы нет. Награда твоя.'
            : 'В тестовой версии рекламы нет. Подожди $_left с — так будет ощущаться настоящая.',
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('Закрыть')),
        FilledButton(
          onPressed: ready ? () => Navigator.of(context).pop(true) : null,
          child: const Text('Получить награду'),
        ),
      ],
    );
  }
}
