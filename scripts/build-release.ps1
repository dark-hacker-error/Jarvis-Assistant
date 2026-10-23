$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$signingRoot = Join-Path $env:LOCALAPPDATA "JARVIS\signing"
$keyStore = Join-Path $signingRoot "jarvis-release.p12"
$credentialFile = Join-Path $signingRoot "release-password.xml"
$keyAlias = "jarvis-release"

if (-not (Test-Path -LiteralPath $keyStore) -or -not (Test-Path -LiteralPath $credentialFile)) {
    throw "Release signing material is missing from the current Windows user's protected JARVIS signing folder."
}

$securePassword = Import-Clixml -LiteralPath $credentialFile
if ($securePassword -isnot [System.Security.SecureString]) {
    throw "The locally protected release password could not be read by this Windows account."
}

$passwordPointer = [IntPtr]::Zero
try {
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    $env:JARVIS_RELEASE_STORE_FILE = $keyStore
    $env:JARVIS_RELEASE_STORE_PASSWORD = $plainPassword
    $env:JARVIS_RELEASE_KEY_ALIAS = $keyAlias
    $env:JARVIS_RELEASE_KEY_PASSWORD = $plainPassword
    $env:JARVIS_DEVELOPER_NAME = "TechGpt X"
    $env:JARVIS_COPYRIGHT_OWNER = "TechGpt X"

    $keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\keytool.exe" } else { (Get-Command keytool -ErrorAction Stop).Source }
    $certificateInfo = (& $keytool -list -v -keystore $keyStore -alias $keyAlias -storepass:env JARVIS_RELEASE_STORE_PASSWORD | Out-String)
    if ($LASTEXITCODE -ne 0 -or $certificateInfo -notmatch "SHA256:\s*([0-9A-Fa-f:]+)") {
        throw "Could not read the release certificate fingerprint."
    }
    $env:JARVIS_RELEASE_CERT_SHA256 = $Matches[1] -replace ":", ""

    Push-Location $repoRoot
    try {
        & (Join-Path $repoRoot "gradlew.bat") :app:assembleRelease -x :app:lintVitalAnalyzeRelease
        if ($LASTEXITCODE -ne 0) { throw "Release APK build failed with exit code $LASTEXITCODE." }
    } finally {
        Pop-Location
    }
    Write-Host "Release APK: $(Join-Path $repoRoot 'app\build\outputs\apk\release\app-release.apk')"
} finally {
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
    $plainPassword = $null
    Remove-Item Env:JARVIS_RELEASE_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:JARVIS_RELEASE_KEY_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:JARVIS_RELEASE_STORE_FILE -ErrorAction SilentlyContinue
    Remove-Item Env:JARVIS_RELEASE_KEY_ALIAS -ErrorAction SilentlyContinue
    Remove-Item Env:JARVIS_RELEASE_CERT_SHA256 -ErrorAction SilentlyContinue
    Remove-Item Env:JARVIS_DEVELOPER_NAME -ErrorAction SilentlyContinue
    Remove-Item Env:JARVIS_COPYRIGHT_OWNER -ErrorAction SilentlyContinue
}
