$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$project = Join-Path $root 'desktop\native\PoringMedia.csproj'
$output = Join-Path $root 'desktop\native\publish'
dotnet publish $project -c Release -r win-x64 --self-contained true -o $output
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Copy-Item -LiteralPath (Join-Path $output 'PoringMedia.exe') -Destination (Join-Path $root 'desktop\native\PoringMedia.exe') -Force
