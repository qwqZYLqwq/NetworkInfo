# Build NetworkInfo.apk (no Gradle needed, uses Android SDK build-tools directly)
# Native tools (aapt2/zipalign) cannot handle non-ASCII paths, so we stage in %TEMP%.
$ErrorActionPreference = "Stop"

$root  = $PSScriptRoot
$sdk   = $env:LOCALAPPDATA + "\Android\Sdk"
$bt    = "$sdk\build-tools\36.0.0"
$plat  = "$sdk\platforms\android-36\android.jar"
$work  = "$env:TEMP\netinfo-build"
$out   = "$work\build"
$apk   = "$root\NetworkInfo.apk"

foreach ($tool in @("$bt\aapt2.exe", "$bt\d8.bat", "$bt\zipalign.exe", "$bt\apksigner.bat", "$plat")) {
    if (-not (Test-Path $tool)) { throw "Missing tool: $tool" }
}

# Locate JDK tools (a JRE in PATH is enough: ecj + keytool)
function Find-JdkTool($name) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $name }
    $java = (Get-Command java -ErrorAction SilentlyContinue)
    if ($java) {
        $jdkHome = Split-Path (Split-Path $java.Source)
        $t = Join-Path $jdkHome "bin\$name.exe"
        if (Test-Path $t) { return $t }
    }
    throw "Cannot find $name; make sure a JRE/JDK is in PATH"
}

# Compile strategy: prefer javac; fall back to ecj.jar running on the JRE in PATH
$javac = $null
try { $javac = Find-JdkTool "javac" } catch { }
$ecj = "$root\tools\ecj.jar"
$useEcj = $null -eq $javac
if ($useEcj) {
    if (-not (Test-Path $ecj)) {
        New-Item -ItemType Directory -Force "$root\tools" | Out-Null
        Write-Host "==> Downloading Eclipse compiler (ecj)..."
        Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.33.0/ecj-3.33.0.jar" -OutFile $ecj
    }
}
$keytool = Find-JdkTool "keytool"

# Stage sources into an ASCII-only path
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force $work | Out-Null
Copy-Item -Recurse "$root\app\src\main" "$work\src"
$srcMain = "$work\src"

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force "$out\gen", "$out\classes", "$out\dex" | Out-Null

Write-Host "==> [1/6] aapt2 compile resources"
& "$bt\aapt2.exe" compile --dir "$srcMain\res" -o "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "==> [2/6] aapt2 link resources (base APK + R.java)"
& "$bt\aapt2.exe" link -o "$out\app.base.apk" -I "$plat" `
    --manifest "$srcMain\AndroidManifest.xml" `
    --java "$out\gen" `
    --min-sdk-version 26 --target-sdk-version 36 `
    --version-code 1 --version-name 1.0 `
    "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "==> [3/6] compile java sources"
$sources = @(Get-ChildItem -Recurse "$srcMain\java" -Filter *.java | ForEach-Object FullName)
$sources += @(Get-ChildItem -Recurse "$out\gen" -Filter *.java | ForEach-Object FullName)
if ($useEcj) {
    & java -jar "$ecj" -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath "$plat" -d "$out\classes" $sources
} else {
    & $javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$plat" -d "$out\classes" $sources
}
if ($LASTEXITCODE -ne 0) { throw "java compile failed" }

Write-Host "==> [4/6] d8 convert to dex"
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
# Package .class files into a jar using .NET (forward-slash entry names)
if (Test-Path "$out\classes.jar") { Remove-Item -Force "$out\classes.jar" }
$zip = [System.IO.Compression.ZipFile]::Open("$out\classes.jar", 'Create')
try {
    $base = "$out\classes"
    Get-ChildItem -Recurse "$base" -Filter *.class | ForEach-Object {
        $rel = $_.FullName.Substring($base.Length + 1).Replace('\', '/')
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, $_.FullName, $rel, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $zip.Dispose() }
& "$bt\d8.bat" --release --lib "$plat" --min-api 26 --output "$out\dex" "$out\classes.jar"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "==> [5/6] inject classes.dex and zipalign"
$zip = [System.IO.Compression.ZipFile]::Open("$out\app.base.apk", 'Update')
try {
    $old = $zip.GetEntry('classes.dex')
    if ($old) { $old.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, "$out\dex\classes.dex", 'classes.dex',
        [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally { $zip.Dispose() }
& "$bt\zipalign.exe" -f -p 4 "$out\app.base.apk" "$out\app.aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "==> [6/6] sign APK with debug key"
# Keystore persists in the project dir so the signature stays stable across builds
$ks = "$root\debug.keystore"
if (-not (Test-Path $ks)) {
    & $keytool -genkeypair -keystore $ks -alias androiddebugkey -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass android -keypass android `
        -dname "CN=Android Debug,O=Android,C=US" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
& "$bt\apksigner.bat" sign --ks $ks --ks-key-alias androiddebugkey `
    --ks-pass pass:android --key-pass pass:android `
    --out "$out\app.signed.apk" "$out\app.aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Copy-Item -Force "$out\app.signed.apk" $apk
& "$bt\apksigner.bat" verify --print-certs "$apk" | Select-Object -First 2
Write-Host ""
Write-Host "BUILD OK: $apk" -ForegroundColor Green
