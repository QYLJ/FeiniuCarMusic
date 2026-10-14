param(
    [string]$Keystore = "C:\Users\tysmd\.android\debug.keystore",
    [string]$KeystorePassword = "android",
    [string]$KeyPassword = "android",
    [string]$KeyAlias = ""
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$appGradle = Get-Content -LiteralPath (Join-Path $projectRoot "app\build.gradle") -Raw
$codeMatch = [regex]::Match($appGradle, '(?m)^\s*versionCode\s+(\d+)')
$nameMatch = [regex]::Match($appGradle, '(?m)^\s*versionName\s+"([^"]+)"')
if (-not $codeMatch.Success -or -not $nameMatch.Success) { throw "无法从 app/build.gradle 读取版本信息" }
$appVersionCode = $codeMatch.Groups[1].Value
$appVersionName = $nameMatch.Groups[1].Value
$sdkRoot = "D:\AndroidSdk"
$buildTools = Join-Path $sdkRoot "build-tools\35.0.0"
$androidJar = Join-Path $sdkRoot "platforms\android-35\android.jar"
$javaHome = "C:\Users\tysmd\AppData\Local\Programs\Microsoft\jdk-17.0.10.7-hotspot"
$outputRoot = Join-Path $projectRoot ".manual-build"
$generatedRoot = Join-Path $outputRoot "gen"
$classesRoot = Join-Path $outputRoot "classes"
$dexRoot = Join-Path $outputRoot "dex"
$dependencyRoot = Join-Path $outputRoot "dependencies"
$conscryptRoot = Join-Path $dependencyRoot "conscrypt-android-2.5.3"
$nativePackageRoot = Join-Path $outputRoot "native-package"
$conscryptAar = Join-Path $projectRoot "app\libs\conscrypt-android-2.5.3.aar"

@($generatedRoot, $classesRoot, $dexRoot, $conscryptRoot, $nativePackageRoot) | ForEach-Object {
    $checkedPath = [System.IO.Path]::GetFullPath($_)
    $allowedRoot = [System.IO.Path]::GetFullPath($outputRoot) + [System.IO.Path]::DirectorySeparatorChar
    if (-not $checkedPath.StartsWith($allowedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "拒绝清理构建输出目录之外的路径"
    }
    if (Test-Path -LiteralPath $checkedPath) { Remove-Item -LiteralPath $checkedPath -Recurse -Force }
    New-Item -ItemType Directory -Path $checkedPath | Out-Null
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($conscryptAar, $conscryptRoot)
$conscryptJar = Join-Path $conscryptRoot "classes.jar"

$resourceZip = Join-Path $outputRoot "resources.zip"
$baseApk = Join-Path $outputRoot "base-unsigned.apk"
$alignedApk = Join-Path $outputRoot "aligned-unsigned.apk"
$signedApk = Join-Path $outputRoot "app-debug.apk"
@($resourceZip, $baseApk, $alignedApk, $signedApk, "$signedApk.idsig") | ForEach-Object {
    if (Test-Path -LiteralPath $_) { Remove-Item -LiteralPath $_ -Force }
}

& (Join-Path $buildTools "aapt2.exe") compile --dir (Join-Path $projectRoot "app\src\main\res") -o $resourceZip
& (Join-Path $buildTools "aapt2.exe") link `
    -o $baseApk `
    -I $androidJar `
    --manifest (Join-Path $projectRoot "app\src\main\AndroidManifest.xml") `
    --java $generatedRoot `
    --min-sdk-version 14 `
    --target-sdk-version 35 `
    --version-code $appVersionCode `
    --version-name $appVersionName `
    $resourceZip

$sources = @(
    Get-ChildItem -Path (Join-Path $projectRoot "app\src\main\java") -Recurse -Filter *.java | ForEach-Object FullName
    Get-ChildItem -Path $generatedRoot -Recurse -Filter *.java | ForEach-Object FullName
)
& (Join-Path $javaHome "bin\javac.exe") -source 8 -target 8 -encoding UTF-8 `
    -classpath "$androidJar;$conscryptJar" -d $classesRoot $sources
& (Join-Path $javaHome "bin\jar.exe") cf (Join-Path $outputRoot "classes.jar") -C $classesRoot .
& (Join-Path $buildTools "d8.bat") (Join-Path $outputRoot "classes.jar") $conscryptJar `
    --lib $androidJar --min-api 14 --output $dexRoot

$withDexApk = Join-Path $outputRoot "with-dex.apk"
Copy-Item -LiteralPath $baseApk -Destination $withDexApk -Force
Push-Location $dexRoot
try {
    & (Join-Path $buildTools "aapt.exe") add $withDexApk "classes.dex"
} finally {
    Pop-Location
}

$nativeEntries = @()
@("armeabi-v7a", "arm64-v8a", "x86", "x86_64") | ForEach-Object {
    $abi = $_
    $destinationDir = Join-Path $nativePackageRoot "lib\$abi"
    New-Item -ItemType Directory -Force $destinationDir | Out-Null
    Copy-Item -LiteralPath (Join-Path $conscryptRoot "jni\$abi\libconscrypt_jni.so") `
        -Destination (Join-Path $destinationDir "libconscrypt_jni.so")
    $nativeEntries += "lib/$abi/libconscrypt_jni.so"
}
Push-Location $nativePackageRoot
try {
    & (Join-Path $buildTools "aapt.exe") add $withDexApk $nativeEntries
} finally {
    Pop-Location
}

& (Join-Path $buildTools "zipalign.exe") -f 4 $withDexApk $alignedApk
$signArguments = @(
    "sign",
    "--ks", $Keystore,
    "--ks-pass", "pass:$KeystorePassword",
    "--key-pass", "pass:$KeyPassword",
    "--out", $signedApk
)
if ($KeyAlias.Length -gt 0) { $signArguments += @("--ks-key-alias", $KeyAlias) }
$signArguments += $alignedApk
& (Join-Path $buildTools "apksigner.bat") $signArguments
& (Join-Path $buildTools "apksigner.bat") verify --verbose $signedApk
Write-Output $signedApk
