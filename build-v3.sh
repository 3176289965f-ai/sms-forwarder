#!/bin/bash
# 短信转发 v3.0 手动打包流水线（aapt2 + kotlinc + d8 + zipalign + apksigner）
# 与 v1/v2 同一签名证书（workspace/sms-forwarder-v2/debug.keystore），可覆盖安装。
set -e
export JAVA_HOME=~/android-dev/jdk-17
export PATH=$JAVA_HOME/bin:$PATH

PROJ=~/workspace/sms-forwarder
SDK=~/android-dev
BT=$SDK/build-tools/34.0.0
AJAR=$SDK/platforms/android-34/android.jar
KC=$SDK/kotlin-compiler-2.0.20/kotlinc
OUT=$PROJ/build-v3
VCODE=3
VNAME="3.0"
KEYSTORE=~/workspace/sms-forwarder-v2/debug.keystore

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== 1/6 aapt2 compile =="
"$BT/aapt2" compile --dir "$PROJ/app/src/main/res" -o "$OUT/compiled_res.zip"

echo "== 2/6 aapt2 link =="
"$BT/aapt2" link -o "$OUT/app-unsigned.apk" \
  -I "$AJAR" \
  --manifest "$PROJ/app/src/main/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VCODE" --version-name "$VNAME" \
  "$OUT/compiled_res.zip"

echo "== 3/6 kotlinc =="
# shellcheck disable=SC2046
"$KC/bin/kotlinc" $(find "$PROJ/app/src/main/java" -name "*.kt") $(find "$OUT/gen" -name "*.java") \
  -cp "$AJAR" -d "$OUT/classes" -jvm-target 17 -no-reflect 2>&1 | grep -v "^warning:" || true
echo "   classes: $(find "$OUT/classes" -name '*.class' | wc -l)"

echo "== 4/6 d8 =="
# shellcheck disable=SC2046
"$BT/d8" --lib "$AJAR" --min-api 26 --output "$OUT/dex" \
  $(find "$OUT/classes" -name "*.class") "$KC/lib/kotlin-stdlib.jar"
ls -la "$OUT/dex/classes.dex"

echo "== 5/6 zip =="
cp "$OUT/app-unsigned.apk" "$OUT/app.apk"
(cd "$OUT/dex" && zip -q -j ../app.apk classes.dex)

echo "== 6/6 zipalign + sign =="
"$BT/zipalign" -f 4 "$OUT/app.apk" "$OUT/app-aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$PROJ/sms-forwarder.apk" "$OUT/app-aligned.apk"

echo "== verify =="
"$BT/apksigner" verify --print-certs "$PROJ/sms-forwarder.apk" | grep -E "Signer #1|SHA-256" | head -3
"$BT/zipalign" -c 4 "$PROJ/sms-forwarder.apk" && echo "zipalign: OK"
"$BT/aapt" dump badging "$PROJ/sms-forwarder.apk" | head -2
ls -la "$PROJ/sms-forwarder.apk"
