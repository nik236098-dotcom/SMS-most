#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/core-tests
if command -v javac >/dev/null; then
  javac -encoding UTF-8 -d build/core-tests app/src/main/java/ru/smsbridge/app/Rules.java app/src/main/java/ru/smsbridge/app/SetupCode.java tests/RulesTest.java tools/ParseJava.java
else
  java tools/CompileCore.java
fi
java -cp build/core-tests ru.smsbridge.app.RulesTest
java -cp build/core-tests ParseJava app/src/main/java
java -cp build/core-tests ParseJava tools
python3 -m py_compile tools/prepare_signer.py tools/build_companion.py tools/check_manifest.py
python3 tools/check_manifest.py
python3 tools/test_queue.py
