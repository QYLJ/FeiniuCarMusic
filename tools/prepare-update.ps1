param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$OutputDirectory = "",
    [string]$ApkUrl = "",
    [string]$Changelog = "",
    [string]$ChangelogFile = "",
    [string]$PreviousApk = "",
    [string]$BuildToolsDirectory = "D:\AndroidSdk\build-tools\35.0.0"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$aaptExecutable = Join-Path $BuildToolsDirectory "aapt.exe"
$signerExecutable = Join-Path $BuildToolsDirectory "apksigner.bat"

function Read-ApkMetadata([string]$FilePath) {
    $resolvedApk = (Resolve-Path -LiteralPath $FilePath).Path
    $badging = (& $aaptExecutable dump badging $resolvedApk | Out-String)
    if ($LASTEXITCODE -ne 0) { throw "无法读取 APK 元信息" }
    $packageMatch = [regex]::Match($badging, "package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'")
    $sdkMatch = [regex]::Match($badging, "(?m)^sdkVersion:'(\d+)'")
    if (-not $packageMatch.Success -or -not $sdkMatch.Success) { throw "APK 版本信息不完整" }
    $signing = (& $signerExecutable verify --print-certs $resolvedApk 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { throw "APK 签名校验失败" }
    $signerMatch = [regex]::Match($signing, 'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)')
    if (-not $signerMatch.Success) { throw "无法读取 APK 签名证书摘要" }
    return [pscustomobject]@{
        Path = $resolvedApk
        Package = $packageMatch.Groups[1].Value
        VersionCode = [int]$packageMatch.Groups[2].Value
        VersionName = $packageMatch.Groups[3].Value
        MinSdk = [int]$sdkMatch.Groups[1].Value
        Signer = $signerMatch.Groups[1].Value.ToUpperInvariant()
    }
}

$metadata = Read-ApkMetadata $Apk
if ($metadata.Package -ne "com.feiniu.carmusic") { throw "APK 包名与飞牛车载音乐不一致" }
if ($PreviousApk.Length -gt 0) {
    $previous = Read-ApkMetadata $PreviousApk
    if ($previous.Package -ne $metadata.Package -or $previous.Signer -ne $metadata.Signer) {
        throw "新旧 APK 签名或包名不一致，已有用户无法覆盖更新"
    }
    if ($metadata.VersionCode -le $previous.VersionCode) { throw "新 APK 的 versionCode 必须高于旧版" }
}
if ($ChangelogFile.Length -gt 0) {
    if ($Changelog.Length -gt 0) { throw "Changelog 与 ChangelogFile 只能指定一个" }
    $Changelog = Get-Content -LiteralPath $ChangelogFile -Raw -Encoding UTF8
}
if ($OutputDirectory.Length -eq 0) { $OutputDirectory = Join-Path $projectRoot "release\update-upload" }
$outputPath = [System.IO.Path]::GetFullPath($OutputDirectory)
[System.IO.Directory]::CreateDirectory($outputPath) | Out-Null
$safeVersionName = $metadata.VersionName -replace '[^a-zA-Z0-9._-]', '-'
$fileName = "FeiniuCarMusic-$safeVersionName-$($metadata.VersionCode).apk"
$destinationApk = Join-Path $outputPath $fileName
$hash = (Get-FileHash -LiteralPath $metadata.Path -Algorithm SHA256).Hash.ToUpperInvariant()
if (Test-Path -LiteralPath $destinationApk) {
    if ((Get-FileHash -LiteralPath $destinationApk -Algorithm SHA256).Hash -ne $hash) {
        throw "同版本文件名已存在不同 APK，请增加版本号后再发布"
    }
} elseif ($metadata.Path -ne $destinationApk) {
    Copy-Item -LiteralPath $metadata.Path -Destination $destinationApk
}
if ($ApkUrl.Length -eq 0) { $ApkUrl = $fileName }
$parsedUrl = $null
if (-not [Uri]::TryCreate($ApkUrl, [UriKind]::RelativeOrAbsolute, [ref]$parsedUrl)) { throw "APK 下载地址无效" }
if ($parsedUrl.IsAbsoluteUri -and $parsedUrl.Scheme -notin @("http", "https")) { throw "APK 下载地址必须使用 HTTP 或 HTTPS" }
$manifest = [ordered]@{
    versionCode = $metadata.VersionCode
    versionName = $metadata.VersionName
    apkUrl = $ApkUrl
    sha256 = $hash
    minSdk = $metadata.MinSdk
    changelog = $Changelog.Trim()
}
$manifestPath = Join-Path $outputPath "update.json"
$json = $manifest | ConvertTo-Json -Depth 3
[System.IO.File]::WriteAllText($manifestPath, $json, [System.Text.UTF8Encoding]::new($false))
Write-Output "发布文件已生成（尚未上传）："
Write-Output $destinationApk
Write-Output $manifestPath
Write-Output "先上传 APK，确认成功后最后覆盖 update.json。"
if ($PreviousApk.Length -eq 0) { Write-Output "覆盖旧用户前，请用 -PreviousApk 核对已分发旧包的签名和版本。" }
