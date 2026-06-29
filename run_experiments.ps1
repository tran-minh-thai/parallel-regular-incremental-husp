<#
.SYNOPSIS
    Compile and run a P-RIncHUSP experiment (Windows wrapper around Maven).

.DESCRIPTION
    Default (no args) runs the full official benchmark suite (S1-S4) on every
    dataset declared in DatasetCatalog.officialSuite().

    Pipeline:
      1. mvn -q compile                                       (skipped with -SkipBuild)
      2. mvn -q exec:exec -Dexec.mainClass=<class>
                          -DheapSize=<HeapGb>g
                          -DstackSize=<StackMb>m
      3. Tee stdout/stderr to results/run_log_<experiment>_<timestamp>.txt
      4. Print elapsed time + any new file(s) in results/.

.PARAMETER Experiment
    Which experiment to run. Accepted values (case-insensitive):
      all       - test.ExperimentOfficial   (full suite, DEFAULT)
      official  - alias of 'all'
      test      - test.ExperimentTest       (writes pattern .txt files)
      perf      - test.PerfProbe            (single-run perf probe)
      recall    - test.RecallProbe          (recall vs RHusp oracle)
      mu        - test.MuProbe              (buffer-factor sensitivity)
      oracle    - test.OracleValidation     (oracle vs reference miner)

.PARAMETER HeapGb
    Max JVM heap in GB. Default 16. Lower if host has less RAM
    (BIBLE/SIGN/LEVIATHAN fit in 8 GB; KOSARAK needs >=16 GB).

.PARAMETER StackMb
    Per-thread stack size in MB. Default 4 MB (JVM default ~1 MB can
    StackOverflow on FIFA-class datasets).

.PARAMETER SkipBuild
    Skip `mvn compile`. Use for back-to-back runs with unchanged src/.

.PARAMETER DryRun
    Print the commands that would run but do not execute them.

.EXAMPLE
    .\run_experiments.ps1
    Full suite, default heap (16 GB) and stack (4 MB).

.EXAMPLE
    .\run_experiments.ps1 -Experiment recall -HeapGb 8
    RecallProbe with 8 GB heap.

.EXAMPLE
    .\run_experiments.ps1 -Experiment mu -SkipBuild -DryRun
    Show the command for MuProbe without compiling or executing.
#>

[CmdletBinding()]
param(
    [ValidateSet('all','official','test','perf','recall','mu','oracle',
                 IgnoreCase = $true)]
    [string] $Experiment = 'all',

    [int]    $HeapGb    = 16,
    [int]    $StackMb   = 4,
    [switch] $SkipBuild,
    [switch] $DryRun
)

$ErrorActionPreference = 'Stop'
$projRoot = $PSScriptRoot
Set-Location $projRoot

# Map experiment alias -> Maven main class
$mainClassMap = @{
    'all'      = 'test.ExperimentOfficial'
    'official' = 'test.ExperimentOfficial'
    'test'     = 'test.ExperimentTest'
    'perf'     = 'test.PerfProbe'
    'recall'   = 'test.RecallProbe'
    'mu'       = 'test.MuProbe'
    'oracle'   = 'test.OracleValidation'
}
$expKey    = $Experiment.ToLower()
$mainClass = $mainClassMap[$expKey]

$resultsDir = Join-Path $projRoot 'results'
$stamp      = Get-Date -Format 'yyyyMMdd_HHmmss'
$logFile    = Join-Path $resultsDir "run_log_${expKey}_${stamp}.txt"

if (-not (Test-Path $resultsDir)) { New-Item -ItemType Directory -Path $resultsDir | Out-Null }

function Write-Section([string]$title) {
    $line = '=' * 72
    Write-Host ''
    Write-Host $line -ForegroundColor Cyan
    Write-Host "  $title" -ForegroundColor Cyan
    Write-Host $line -ForegroundColor Cyan
}

# ---------- Sanity ----------
$mvn  = (Get-Command mvn  -ErrorAction SilentlyContinue).Source
$java = (Get-Command java -ErrorAction SilentlyContinue).Source
if (-not $mvn)  { throw "mvn not found on PATH" }
if (-not $java) { throw "java not found on PATH" }

Write-Section "P-RIncHUSP experiment runner (Maven)"
Write-Host "Project root  : $projRoot"
Write-Host "Experiment    : $Experiment  ->  $mainClass"
Write-Host "Heap / Stack  : -Xmx${HeapGb}g  /  -Xss${StackMb}m"
Write-Host "mvn / java    : $mvn  /  $java"
Write-Host "Log file      : $logFile"
Write-Host "Cores         : $([Environment]::ProcessorCount)"

# ---------- Step 1: compile ----------
if (-not $SkipBuild) {
    Write-Section "Step 1/2  mvn compile"
    Write-Host "mvn -q compile"
    if (-not $DryRun) {
        & mvn -q compile
        if ($LASTEXITCODE -ne 0) { throw "mvn compile failed (exit $LASTEXITCODE)" }
    }
    Write-Host "Compile OK" -ForegroundColor Green
} else {
    Write-Section "Step 1/2  mvn compile  [SKIPPED]"
    if (-not (Test-Path (Join-Path $projRoot 'target\classes\test\ExperimentOfficial.class'))) {
        throw "target/classes does not contain compiled classes - rerun without -SkipBuild"
    }
}

# ---------- Step 2: run ----------
Write-Section "Step 2/2  mvn exec:exec  ($mainClass)"

# Snapshot results/ before run (recursive: covers results/test_patterns/ too)
$filesBefore = @(Get-ChildItem $resultsDir -Recurse -File -ErrorAction SilentlyContinue |
                 Select-Object -ExpandProperty FullName)

$mvnArgs = @(
    '-q', 'exec:exec',
    "-Dexec.mainClass=$mainClass",
    "-DheapSize=${HeapGb}g",
    "-DstackSize=${StackMb}m"
)
Write-Host "mvn $($mvnArgs -join ' ')"
Write-Host "Streaming output to console AND $logFile ..."
Write-Host ""

$startTime = Get-Date
if (-not $DryRun) {
    # 2>&1 merges stderr; Tee-Object flushes per line for live streaming.
    & mvn @mvnArgs 2>&1 | Tee-Object -FilePath $logFile
    $exit = $LASTEXITCODE
} else {
    $exit = 0
}
$elapsed = (Get-Date) - $startTime

# ---------- Summary ----------
Write-Section "Done"
Write-Host ("Elapsed      : {0:hh\:mm\:ss}" -f $elapsed)
Write-Host  "Exit code    : $exit"
Write-Host  "Log file     : $logFile"

if ($exit -eq 0 -and -not $DryRun) {
    $filesAfter = @(Get-ChildItem $resultsDir -Recurse -File |
                    Select-Object -ExpandProperty FullName)
    # Exclude the log file itself from "new" detection
    $new = @($filesAfter | Where-Object { $filesBefore -notcontains $_ -and $_ -ne $logFile })
    if ($new.Count -gt 0) {
        Write-Host "New file(s)  :"
        $new | ForEach-Object { Write-Host "               $_" }
    } else {
        Write-Host "No new file in results/ (probe-only experiments may print to console only)"
    }
} elseif ($exit -ne 0) {
    Write-Host "Run FAILED (exit $exit) - inspect $logFile" -ForegroundColor Red
    exit $exit
}
