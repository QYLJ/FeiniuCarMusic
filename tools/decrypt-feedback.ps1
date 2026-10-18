param(
    [Parameter(Mandatory = $true)]
    [string]$InputFile,

    [string]$PrivateKeyPath = (Join-Path $PSScriptRoot '..\.feedback-keys\feedback-private-key.pem'),

    [string]$OutputFile
)

$ErrorActionPreference = 'Stop'

$inputPath = (Resolve-Path -LiteralPath $InputFile).Path
$keyPath = (Resolve-Path -LiteralPath $PrivateKeyPath).Path
if ([string]::IsNullOrWhiteSpace($OutputFile)) {
    $OutputFile = $inputPath + '.json'
} else {
    $OutputFile = [IO.Path]::GetFullPath($OutputFile)
}

$envelope = [IO.File]::ReadAllBytes($inputPath)
if ($envelope.Length -lt 6 + 2 + 16 + 256 + 16 + 32) {
    throw '反馈文件过短或格式无效。'
}

$magic = [Text.Encoding]::ASCII.GetString($envelope, 0, 6)
if ($magic -ne 'FNCFB1') {
    throw '不是受支持的飞牛车载音乐反馈文件。'
}

$wrappedLength = ([int]$envelope[6] -shl 8) -bor [int]$envelope[7]
$wrappedOffset = 24
$cipherOffset = $wrappedOffset + $wrappedLength
$macOffset = $envelope.Length - 32
if ($wrappedLength -lt 128 -or $cipherOffset -ge $macOffset) {
    throw '反馈文件头损坏。'
}

$iv = [byte[]]::new(16)
[Array]::Copy($envelope, 8, $iv, 0, 16)
$wrapped = [byte[]]::new($wrappedLength)
[Array]::Copy($envelope, $wrappedOffset, $wrapped, 0, $wrappedLength)
$cipherText = [byte[]]::new($macOffset - $cipherOffset)
[Array]::Copy($envelope, $cipherOffset, $cipherText, 0, $cipherText.Length)
$expectedMac = [byte[]]::new(32)
[Array]::Copy($envelope, $macOffset, $expectedMac, 0, 32)

$rsa = [Security.Cryptography.RSA]::Create()
try {
    $rsa.ImportFromPem([IO.File]::ReadAllText($keyPath))
    $keyMaterial = $rsa.Decrypt($wrapped, [Security.Cryptography.RSAEncryptionPadding]::Pkcs1)
} finally {
    $rsa.Dispose()
}
if ($keyMaterial.Length -ne 64) {
    throw '私钥与反馈文件不匹配。'
}

$hmacKey = [byte[]]::new(32)
[Array]::Copy($keyMaterial, 32, $hmacKey, 0, 32)
$authenticated = [byte[]]::new($macOffset)
[Array]::Copy($envelope, 0, $authenticated, 0, $macOffset)
$hmac = [Security.Cryptography.HMACSHA256]::new($hmacKey)
try {
    $actualMac = $hmac.ComputeHash($authenticated)
} finally {
    $hmac.Dispose()
}
if (-not [Security.Cryptography.CryptographicOperations]::FixedTimeEquals($expectedMac, $actualMac)) {
    throw '反馈文件完整性校验失败，文件可能已损坏或被改动。'
}

$aesKey = [byte[]]::new(32)
[Array]::Copy($keyMaterial, 0, $aesKey, 0, 32)
$aes = [Security.Cryptography.Aes]::Create()
try {
    $aes.Key = $aesKey
    $aes.IV = $iv
    $aes.Mode = [Security.Cryptography.CipherMode]::CBC
    $aes.Padding = [Security.Cryptography.PaddingMode]::PKCS7
    $decryptor = $aes.CreateDecryptor()
    try {
        $plain = $decryptor.TransformFinalBlock($cipherText, 0, $cipherText.Length)
    } finally {
        $decryptor.Dispose()
    }
} finally {
    $aes.Dispose()
    [Array]::Clear($keyMaterial, 0, $keyMaterial.Length)
}

$outputDirectory = [IO.Path]::GetDirectoryName($OutputFile)
if (-not [string]::IsNullOrWhiteSpace($outputDirectory)) {
    [IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
}
[IO.File]::WriteAllBytes($OutputFile, $plain)
Write-Output "反馈已解密：$OutputFile"
