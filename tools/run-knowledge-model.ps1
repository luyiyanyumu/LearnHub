param(
    [ValidateSet('Setup', 'Serve', 'Smoke')]
    [string]$Action = 'Serve',
    [int]$Port = 8766,
    [int]$Steps = 80,
    [string]$DataDirectory = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$modelRoot = Join-Path $projectRoot 'output/knowledge-model'
$pythonExe = Join-Path $modelRoot 'venv/Scripts/python.exe'
$modelPath = Join-Path $modelRoot 'models/qwen2.5-1.5b-instruct'
$entryPoint = Join-Path $PSScriptRoot 'knowledge-model/main.py'
$env:HF_HUB_DISABLE_IMPLICIT_TOKEN = '1'
$env:HF_HOME = Join-Path $modelRoot 'huggingface'
# Keep native Windows subprocess decoding in its system code page. Model/report
# files explicitly use UTF-8; Python's own redirected logs should use UTF-8 too.
$env:PYTHONUTF8 = '0'
$env:PYTHONIOENCODING = 'utf-8'

function Assert-ExitCode([string]$Operation) {
    if ($LASTEXITCODE -ne 0) { throw "$Operation failed (exit code $LASTEXITCODE)." }
}

if ($Action -eq 'Setup') {
    if (-not (Get-Command uv -ErrorAction SilentlyContinue)) {
        throw 'Install uv first, then run this setup again. See docs/knowledge-model.md.'
    }
    if (-not (Test-Path -LiteralPath $pythonExe)) {
        & uv venv (Join-Path $modelRoot 'venv') --python 3.12
        Assert-ExitCode 'Creating the isolated Python environment'
    }
    & uv pip install --python $pythonExe 'torch==2.8.0' --index-url 'https://download.pytorch.org/whl/cu128'
    Assert-ExitCode 'Installing CUDA PyTorch'
    & uv pip install --python $pythonExe -r (Join-Path $PSScriptRoot 'knowledge-model/requirements.txt')
    Assert-ExitCode 'Installing the training service'
    $download = @'
import sys
from huggingface_hub import snapshot_download
snapshot_download('Qwen/Qwen2.5-1.5B-Instruct', local_dir=sys.argv[1], token=False,
                  allow_patterns=['*.json', '*.safetensors', '*.txt', '*.model'])
'@
    & $pythonExe -c $download $modelPath
    Assert-ExitCode 'Downloading the public base model'
    & $pythonExe -c 'import torch; print("CUDA available:", torch.cuda.is_available()); print("GPU:", torch.cuda.get_device_name(0) if torch.cuda.is_available() else "none")'
    Assert-ExitCode 'Checking CUDA'
    Write-Host 'Setup complete. Run tools/run-knowledge-model.ps1 -Action Serve.'
    exit 0
}

if (-not (Test-Path -LiteralPath $pythonExe)) { throw 'Run with -Action Setup first.' }
if (-not (Test-Path -LiteralPath (Join-Path $modelPath 'config.json'))) { throw 'The local model is missing. Run with -Action Setup first.' }
if (-not $DataDirectory) {
    $DataDirectory = Join-Path $modelRoot $(if ($Action -eq 'Smoke') { 'smoke-run' } else { 'runtime' })
}

$command = if ($Action -eq 'Smoke') { 'smoke' } else { 'serve' }
& $pythonExe $entryPoint $command --model-path $modelPath --data-dir $DataDirectory --port $Port --steps $Steps
Assert-ExitCode 'Running the local knowledge model'
