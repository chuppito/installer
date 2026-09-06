import 'package:flutter_test/flutter_test.dart';
import 'package:installer/main.dart';

void main() {
  testWidgets('App launches', (tester) async {
    await tester.pumpWidget(const InstallerApp());
  });
}
