$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$libsDir = Join-Path $root "app\libs"
$whisperDir = Join-Path $root "app\src\main\assets\models\whisper-small.en"
$vadDir = Join-Path $root "app\src\main\assets\models\vad"

New-Item -ItemType Directory -Force -Path $libsDir, $whisperDir, $vadDir | Out-Null

Write-Host "1/5 Downloading sherpa-onnx-2.0.1.aar..."
curl.exe -L --retry 3 "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar" -o (Join-Path $libsDir "sherpa-onnx-2.0.1.aar")

Write-Host "2/5 Downloading silero_vad.int8.onnx..."
Invoke-WebRequest -Uri "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.int8.onnx" -OutFile (Join-Path $vadDir "silero_vad.int8.onnx")

Write-Host "3/5 Downloading small.en-tokens.txt..."
curl.exe --http1.1 -L --retry 3 "https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-small.en/resolve/main/small.en-tokens.txt" -o (Join-Path $whisperDir "small.en-tokens.txt")

Write-Host "4/5 Downloading small.en-encoder.int8.onnx..."
curl.exe --http1.1 -L --retry 3 "https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-small.en/resolve/main/small.en-encoder.int8.onnx" -o (Join-Path $whisperDir "small.en-encoder.int8.onnx")

Write-Host "5/5 Downloading small.en-decoder.int8.onnx..."
curl.exe --http1.1 -L --retry 3 "https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-small.en/resolve/main/small.en-decoder.int8.onnx" -o (Join-Path $whisperDir "small.en-decoder.int8.onnx")

Write-Host "Done! You can now run: .\gradlew.bat :app:assembleGithubDebug"
