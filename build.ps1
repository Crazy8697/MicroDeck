# SPDX-License-Identifier: MIT
# Copyright (c) 2026 DarkRelay
# Build MicroDeck's signed release APK and, with -Release, publish it, the Lazarus PDF / PhoneDeck way.
#   .\build.ps1                  clean signed release build (app\build is gitignored), privacy check on the tree,
#                                the public set and the APK, apksigner check against the release certificate,
#                                then MicroDeck-x.y.z.apk + MicroDeck.apk (the constant name the update check and
#                                download links use), each with a .sha256, into -OutDir
#                                (default: Downloads\MicroDeck\x.y.z). Refuses when vx.y.z is already tagged on
#                                Forgejo or released on GitHub, so a rebuild never replaces published files.
#   .\build.ps1 -PrivacyCheck    only tools\privacy_check.py --public (and --dist if an APK is built)
#   .\build.ps1 -Release -Version x.y.z [-DryRun]
#                                publish. Refuses unless x.y.z is the versionName in app\build.gradle.kts, the tree
#                                is clean in git, release-notes\vx.y.z.md exists, the tag and the GitHub release
#                                don't exist yet, and the privacy check finds nothing in the tree; then builds as
#                                above (refusing on any privacy finding in the public set or the APK, a certificate
#                                that isn't the release key, or an APK whose update check isn't GitHub). Then:
#                                  1. public source: the committed tree at HEAD (git archive, so export-ignore
#                                     applies), fenced and privacy checked, copied into the public clone in
#                                     -PublicDir as ONE commit "MicroDeck x.y.z" by the GitHub noreply identity,
#                                     plain push to Crazy8697/MicroDeck main (never forced),
#                                  2. release vx.y.z on that commit: a draft, the four assets uploaded in order
#                                     (MicroDeck.apk, MicroDeck.apk.sha256, MicroDeck-x.y.z.apk, its .sha256), checked,
#                                     then published as the latest release (not a pre-release), notes from the file,
#                                  3. tag vx.y.z on the private commit, pushed to Forgejo (origin) only.
#                                -DryRun does every check and the build, exports and checks the public tree in a temp
#                                folder, and prints exactly what steps 1 to 3 would publish; it touches neither GitHub
#                                nor the tags. Its build goes to <OutDir>\dryrun (wiped first), never into <OutDir>
#                                itself, so it may run for a version that is already out.
# Needs: JDK 17+, the Android SDK (local.properties or ANDROID_HOME), python on PATH, keystore.properties (the
# release key, never committed), and gh logged in for a real -Release.
param(
    [switch]$Release,
    [string]$Version = "",
    [switch]$DryRun,
    [switch]$PrivacyCheck,
    [string]$OutDir = "",
    [string]$PublicDir = (Join-Path $env:LOCALAPPDATA "MicroDeck-public\MicroDeck")
)
$ErrorActionPreference = "Stop"
$PublicRepo = "Crazy8697/MicroDeck"
$PublicUrl = "git@github.com:$PublicRepo.git"
$PublicName = "Crazy8697"
$PublicEmail = "261646346+Crazy8697@users.noreply.github.com"
# the release certificate (public): microdeck-release.p12, alias microdeck
$ReleaseCert = "baaba1f2e2b48e70a9b815c392acfb43d67a01b6786e9bf10b4f18279a9532b2"
$RealUpdateUrl = "https://api.github.com/repos/Crazy8697/MicroDeck/releases/latest"
# never published, even if one were committed by mistake
$Forbidden = @("tools/privacy-local.txt", "keystore.properties", "*.jks", "*.keystore", "*.p12", "*.pem",
               "*.apk", "*.aab", "local.properties", "build/*", "app/build/*", ".gradle/*", "*.log", ".env")

function Get-Sdk {
    $lp = Join-Path $PSScriptRoot "local.properties"
    if (Test-Path $lp) {
        $m = Select-String -Path $lp -Pattern '^sdk\.dir=(.+)$'
        if ($m) { return ($m.Matches[0].Groups[1].Value -replace '\\:', ':' -replace '\\\\', '\') }
    }
    if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
    throw "Android SDK not found (local.properties sdk.dir or ANDROID_HOME)."
}

function Get-BuildTool([string]$name) {
    $bt = (Select-String -Path (Join-Path $PSScriptRoot "app\build.gradle.kts") -Pattern 'buildToolsVersion = "([^"]+)"').Matches[0].Groups[1].Value
    $p = Join-Path (Get-Sdk) "build-tools\$bt\$name"
    if (-not (Test-Path $p)) { throw "$name not found at $p" }
    return $p
}

function Assert-Clean {
    $dirty = git -C $PSScriptRoot status --porcelain
    if ($LASTEXITCODE -ne 0) { throw "git status failed" }
    if ($dirty) { throw "The MicroDeck tree isn't clean in git; commit (to Forgejo) first." }
}

function Invoke-Privacy([string[]]$PrivacyArgs) {
    & python -B (Join-Path $PSScriptRoot "tools\privacy_check.py") @PrivacyArgs | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "The privacy check found something ($($PrivacyArgs -join ' ')); nothing was published." }
}

# The committed tree at HEAD, as the public repo would get it: git archive (export-ignore applies), the forbidden
# fence, and the privacy check on exactly those files. Returns the folder; the caller removes it.
# Why the release outputs for version $v must not be written: its tag on Forgejo (or here) or its GitHub release.
function Get-Published([string]$v) {
    $why = @()
    if (git -C $PSScriptRoot tag --list "v$v") { $why += "tag v$v exists in the private repo" }
    # (through cmd: PowerShell 5.1 turns a redirected native stderr into an error under "Stop")
    $t = (cmd /c "git -C `"$PSScriptRoot`" ls-remote --tags origin refs/tags/v$v 2>nul") -join ""
    if ($t) { $why += "tag v$v exists on Forgejo" }
    cmd /c "gh release view v$v --repo $PublicRepo >nul 2>&1"
    if ($LASTEXITCODE -eq 0) { $why += "release v$v exists on $PublicRepo" }
    return $why
}

function Export-Public {
    $stage = Join-Path $env:TEMP ("microdeck-public-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
    New-Item -ItemType Directory -Force $stage | Out-Null
    $tar = "$stage.tar"
    git -C $PSScriptRoot archive --format=tar -o $tar HEAD
    if ($LASTEXITCODE -ne 0) { throw "git archive failed" }
    tar -xf $tar -C $stage
    if ($LASTEXITCODE -ne 0) { throw "Unpacking the public tree failed" }
    Remove-Item $tar -Force
    $files = Get-ChildItem $stage -Recurse -File | ForEach-Object { $_.FullName.Substring($stage.Length + 1).Replace('\', '/') }
    foreach ($f in $files) {
        foreach ($pat in $Forbidden) {
            if ($f -like $pat) { throw "The public tree would contain $f (matches $pat); nothing was published." }
        }
    }
    Invoke-Privacy @("--root", $stage)
    return @{ Dir = $stage; Files = $files }
}

Push-Location $PSScriptRoot
try {
    $gradle = Join-Path $PSScriptRoot "app\build.gradle.kts"
    $ver = (Select-String -Path $gradle -Pattern 'versionName = "([^"]+)"').Matches[0].Groups[1].Value
    if (-not $ver) { throw "versionName not found in app\build.gradle.kts" }
    $apk = Join-Path $PSScriptRoot "app\build\outputs\apk\release\app-release.apk"

    if ($PrivacyCheck) {
        $pa = @("--public")
        if (Test-Path $apk) { $pa += "--dist" }
        Invoke-Privacy $pa
        return
    }

    $notes = Join-Path $PSScriptRoot "release-notes\v$ver.md"
    $problems = @()
    if ($Release) {
        if ($Version -ne $ver) { throw "-Version '$Version' doesn't match versionName ($ver)." }
        Assert-Clean
        if (-not (Test-Path $notes)) { $problems += "no release notes: release-notes\v$ver.md" }
        if (git -C $PSScriptRoot tag --list "v$ver") { $problems += "tag v$ver already exists in the private repo" }
        if (-not $DryRun) {
            # (through cmd: PowerShell 5.1 turns a redirected native stderr into an error under "Stop")
            cmd /c "gh release view v$ver --repo $PublicRepo >nul 2>&1"
            if ($LASTEXITCODE -eq 0) { $problems += "release v$ver already exists on $PublicRepo" }
        }
        if ($problems -and -not $DryRun) { throw ("Refusing to release: " + ($problems -join "; ")) }
        Invoke-Privacy @()
    } elseif ($DryRun) { throw "-DryRun goes with -Release." }

    # where the files go: a dry run only ever writes <OutDir>\dryrun; anything else refuses a version that is out
    if (-not $OutDir) { $OutDir = Join-Path ([Environment]::GetFolderPath("UserProfile")) "Downloads\MicroDeck\$ver" }
    if ($DryRun) {
        $OutDir = Join-Path $OutDir "dryrun"
    } else {
        $published = Get-Published $ver
        if ($published) { throw ("Refusing to write ${OutDir}: " + ($published -join "; ") + ". Use -Release -DryRun to rebuild it into a dryrun folder.") }
    }

    if (-not (Test-Path (Join-Path $PSScriptRoot "keystore.properties"))) {
        throw "keystore.properties is missing: a release build would be unsigned."
    }

    # 1. clean signed release build
    & (Join-Path $PSScriptRoot "gradlew.bat") clean assembleRelease --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed ($LASTEXITCODE)" }
    if (-not (Test-Path $apk)) { throw "No signed APK at $apk" }

    # 2. the APK must carry the release key, the right version, no debuggable flag and GitHub as its update URL
    $certs = & (Get-BuildTool "apksigner.bat") verify --print-certs $apk 2>&1
    if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed:`n$certs" }
    $digest = ($certs | Select-String 'certificate SHA-256 digest: ([0-9a-f]+)').Matches | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique
    if (@($digest).Count -ne 1 -or $digest -ne $ReleaseCert) { throw "The APK isn't signed with the release key (got '$digest')." }
    $badging = & (Get-BuildTool "aapt2.exe") dump badging $apk
    if (-not ($badging -match "versionName='$([regex]::Escape($ver))'")) { throw "The APK's versionName isn't $ver." }
    if ($badging -match "application-debuggable") { throw "The APK is debuggable." }
    $manifest = & (Get-BuildTool "aapt2.exe") dump xmltree --file AndroidManifest.xml $apk
    if (($manifest -join "`n") -match 'usesCleartextTraffic[^\n]*=\(type 0x12\)0xffffffff') { throw "The APK allows cleartext traffic (a test build?)." }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($apk)
    try {
        $dex = ""
        foreach ($e in $zip.Entries | Where-Object { $_.Name -like "classes*.dex" }) {
            $s = $e.Open(); $ms = New-Object System.IO.MemoryStream; $s.CopyTo($ms); $s.Close()
            $dex += [System.Text.Encoding]::GetEncoding(28591).GetString($ms.ToArray())
        }
    } finally { $zip.Dispose() }
    if (-not $dex.Contains($RealUpdateUrl)) { throw "The APK's update check isn't $RealUpdateUrl (a test build?)." }

    # 3. privacy: the public set and the APK
    Invoke-Privacy @("--public", "--dist")

    # 4. the files: versioned and constant names, each with a .sha256 ("<hash> *<name>")
    if ($DryRun -and (Test-Path $OutDir)) { Remove-Item $OutDir -Recurse -Force }
    New-Item -ItemType Directory -Force $OutDir | Out-Null
    $assets = @()
    foreach ($name in @("MicroDeck.apk", "MicroDeck-$ver.apk")) {
        $dst = Join-Path $OutDir $name
        Copy-Item $apk $dst -Force
        $hash = (Get-FileHash $dst -Algorithm SHA256).Hash.ToLower()
        Set-Content -Path "$dst.sha256" -Value "$hash *$name" -Encoding ascii -NoNewline
        $assets += $dst, "$dst.sha256"
    }
    $kb = [math]::Round((Get-Item $apk).Length / 1KB)
    Write-Host "Built: MicroDeck $ver, $kb KB, signed with the release key ($ReleaseCert)"
    Write-Host "SHA-256: $hash"
    Write-Host "Out: $OutDir"

    if (-not $Release) { return }

    # 5. publish (or, with -DryRun, say exactly what would be published)
    $exp = Export-Public
    try {
        $names = ($assets | ForEach-Object { Split-Path $_ -Leaf }) -join ", "
        if ($DryRun) {
            Write-Host ""
            Write-Host "DRY RUN: nothing below is done."
            if ($problems) { Write-Host ("A real release would refuse: " + ($problems -join "; ")) }
            Write-Host "1. public source: $($exp.Files.Count) files from HEAD $(git -C $PSScriptRoot rev-parse --short HEAD) (export-ignore applied, privacy check clean)"
            Write-Host "   into $PublicDir, one commit 'MicroDeck $ver' by $PublicName <$PublicEmail>, plain push to $PublicRepo main"
            $rm = (cmd /c "git ls-remote --heads $PublicUrl main 2>nul") -join ""
            if ($LASTEXITCODE -ne 0) { Write-Host "   (public repo not reachable: it doesn't exist yet or there is no access)" }
            elseif (-not $rm) { Write-Host "   (public repo is empty: this would be its first commit, main created, upstream set)" }
            else { Write-Host "   (public main is at $($rm.Substring(0, 7)))" }
            Write-Host "2. gh release create v$ver on that commit, title 'MicroDeck $ver', notes release-notes\v$ver.md, --draft"
            Write-Host "   assets in order: $names"
            foreach ($a in $assets) { Write-Host ("     {0}  {1} bytes" -f (Split-Path $a -Leaf), (Get-Item $a).Length) }
            Write-Host "   then gh release edit v$ver --draft=false --latest (not a pre-release)"
            Write-Host "3. git tag v$ver on $(git -C $PSScriptRoot rev-parse --short HEAD), pushed to origin (Forgejo) only"
            return
        }
        # 5.1 public source: the exported tree replaces the public clone's files, one commit, plain push.
        #     The very first release finds the public repo empty (created with no README or license, so its
        #     history starts with our first commit): the clone has no branch yet, so main is set up here and
        #     the push sets the upstream.
        # (through cmd: PowerShell 5.1 turns a redirected native stderr into an error under "Stop")
        $remoteMain = (cmd /c "git ls-remote --heads $PublicUrl main 2>nul") -join ""
        if ($LASTEXITCODE -ne 0) { throw "Couldn't reach $PublicRepo" }
        $firstPush = -not $remoteMain
        if (-not (Test-Path (Join-Path $PublicDir ".git"))) {
            New-Item -ItemType Directory -Force (Split-Path $PublicDir) | Out-Null
            cmd /c "git clone -q $PublicUrl `"$PublicDir`" 2>nul"
            if ($LASTEXITCODE -ne 0) { throw "Couldn't clone $PublicRepo" }
        } elseif (-not $firstPush) {
            git -C $PublicDir pull -q --ff-only origin main
            if ($LASTEXITCODE -ne 0) { throw "Couldn't update the public clone" }
        }
        if ($firstPush) {
            if (git -C $PublicDir rev-parse -q --verify HEAD) { throw "$PublicRepo has no main branch but the local public clone has commits; refusing." }
            git -C $PublicDir symbolic-ref HEAD refs/heads/main
            Write-Host "Public repo is empty: this release makes its first commit on main."
        }
        Get-ChildItem $PublicDir -Force | Where-Object { $_.Name -ne ".git" } | Remove-Item -Recurse -Force
        Copy-Item (Join-Path $exp.Dir "*") $PublicDir -Recurse -Force
        Invoke-Privacy @("--root", $PublicDir)
        git -C $PublicDir add -A
        if (git -C $PublicDir status --porcelain) {
            # (the private history's hashes stay private: the message names the version only)
            git -C $PublicDir -c "user.name=$PublicName" -c "user.email=$PublicEmail" commit -q -m "MicroDeck $ver"
            if ($LASTEXITCODE -ne 0) { throw "Couldn't commit in the public clone" }
            git -C $PublicDir push -q -u origin main
            if ($LASTEXITCODE -ne 0) { throw "Couldn't push the public source" }
        }
        $commit = (git -C $PublicDir rev-parse HEAD).Trim()
        Write-Host "Public source: $PublicRepo main = $commit"

        # 5.2 the release: draft, assets one at a time in a fixed order, checked, then published as latest
        gh release create "v$ver" $assets[0] --repo $PublicRepo --target $commit --title "MicroDeck $ver" --notes-file $notes --draft
        if ($LASTEXITCODE -ne 0) { throw "gh release create failed ($LASTEXITCODE)" }
        foreach ($a in $assets[1..($assets.Count - 1)]) {
            gh release upload "v$ver" $a --repo $PublicRepo
            if ($LASTEXITCODE -ne 0) { throw "Uploading $a failed; the release is still a draft on $PublicRepo." }
        }
        # GitHub lists assets by name, not upload order: compare the sets, then every asset's sha256 digest
        # (no double quotes in the jq: PowerShell 5.1 drops them on the way to a native program)
        $listed = gh release view "v$ver" --repo $PublicRepo --json assets --jq '.assets[] | [.name, .digest] | @tsv'
        $want = @{}
        foreach ($a in $assets) { $want[(Split-Path $a -Leaf)] = "sha256:" + (Get-FileHash $a -Algorithm SHA256).Hash.ToLower() }
        $seen = @{}
        foreach ($line in $listed) { $n, $d = $line -split "`t", 2; $seen[$n] = $d }
        $got = ($seen.Keys | Sort-Object) -join ", "
        if ($got -ne (($want.Keys | Sort-Object) -join ", ")) { throw "Assets are '$got', not '$names'; the release is still a draft." }
        foreach ($n in $want.Keys) {
            if ($seen[$n] -ne $want[$n]) { throw "$n on GitHub ($($seen[$n])) isn't the local file ($($want[$n])); the release is still a draft." }
        }
        gh release edit "v$ver" --repo $PublicRepo --draft=false --prerelease=false --latest
        if ($LASTEXITCODE -ne 0) { throw "Publishing the draft failed; it is still a draft on $PublicRepo." }
        Write-Host "Released v$ver on ${PublicRepo}: $got"

        # 5.3 the same tag on the private commit, Forgejo only
        git -C $PSScriptRoot tag "v$ver"
        git -C $PSScriptRoot push -q origin "refs/tags/v$ver"
        if ($LASTEXITCODE -ne 0) { throw "Pushing tag v$ver to Forgejo failed (the GitHub release is out)." }
        Write-Host "Tagged v$ver on the private commit (Forgejo)."
    } finally {
        if (Test-Path $exp.Dir) { Remove-Item $exp.Dir -Recurse -Force }
    }
} finally { Pop-Location }
