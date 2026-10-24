# SpendSense AI - evaluation suite.
#
# Three suites (extraction, categorization, query), each scored on two case sets:
#
#   dev   - the cases that were written while the prompts were being built. Failures in this set
#           were used to change prompts, so its score is CONTAMINATED and is an upper bound.
#   test  - held out. Written after the prompts were frozen, from bank SMS templates and question
#           phrasings that do not appear in dev. No failure here has ever changed a prompt.
#           This is the honest number.
#
# Every suite is also run against a non-LLM baseline, so a score has something to beat. Every
# failure is classified rather than merely counted. Every suite is run -Runs times, because the
# model is not deterministic at temperature 0, and the report gives mean/min/max plus the list
# of cases whose verdict changed between runs - those matter more than the average.
#
# Each suite calls its component directly rather than pushing SMS through the pipeline. That
# costs one model call per case instead of four, which is what makes five runs possible at all,
# and it isolates the component: a missing row could be extraction, Kafka, storage or the
# embedding call, and an end-to-end eval cannot say which.
#
# Usage:
#   docker compose -f docker-compose.prod.yml -f docker-compose.eval.yml --env-file .env up -d --build
#   ./eval.ps1                                   # everything, both sets, 5 runs
#   ./eval.ps1 -Only query -Set test -Runs 1     # one suite, one set, one run
#   ./eval.ps1 -BaselinesOnly                    # no model calls at all, seconds not hours

[CmdletBinding()]
param(
    [string]$ExtractUrl    = "http://localhost:8081/api/extract",
    [string]$CategorizeUrl = "http://localhost:8082/api/categorize",
    [string]$QueryUrl      = "http://localhost:8085/api/query",

    [ValidateSet('all', 'extraction', 'categorization', 'query')]
    [string[]]$Only = @('all'),

    [ValidateSet('dev', 'test')]
    [string[]]$Set = @('dev', 'test'),

    # The model is not deterministic at temperature 0. One run is a sample, not a measurement.
    [int]$Runs = 5,

    [switch]$BaselinesOnly,

    [string]$PostgresContainer = "",
    [string]$PostgresUser = "spendsense",
    [string]$PostgresDb   = "spendsense",

    # Rate limiting. One extraction or categorization case is one Gemini call; one question is
    # three or four (router, embedding, SQL generation, summary), so it gets a longer gap.
    [int]$CallDelayMs  = 4500,
    [int]$QueryDelayMs = 18000,

    [string]$CaseDir = "eval/cases",
    [string]$OutDir  = "eval/results"
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$caseDirFull = Join-Path $root $CaseDir
$outDirFull  = Join-Path $root $OutDir
if (-not (Test-Path $outDirFull)) { New-Item -ItemType Directory -Force -Path $outDirFull | Out-Null }

$runStarted = Get-Date
$stamp      = $runStarted.ToString("yyyyMMdd-HHmmss")
$noBom      = New-Object System.Text.UTF8Encoding $false

$CATEGORIES = @('Food & Dining', 'Groceries', 'Transport', 'Shopping', 'Bills & Utilities',
                'Subscriptions', 'Entertainment', 'Health', 'Travel', 'Transfers', 'Income',
                'Needs Manual Categorization', 'Other')

# ============================================================================ helpers

function Get-Normalized([string]$s) {
    if ($null -eq $s) { return "" }
    return ($s.ToLowerInvariant() -replace '[^a-z0-9]', '')
}

function Get-Pct($n, $d) {
    if ($d -eq 0) { return 0.0 }
    return [math]::Round(100.0 * $n / $d, 1)
}

# Invoke-RestMethod is not usable here: Windows PowerShell 5.1 decodes a response body as
# ISO-8859-1 unless the Content-Type names a charset, and Spring sends `application/json` with
# none - so every rupee sign comes back as three mojibake characters.
function Invoke-JsonPost([string]$uri, $bodyObject) {
    $bytes = [Text.Encoding]::UTF8.GetBytes(($bodyObject | ConvertTo-Json -Compress))
    $resp  = Invoke-WebRequest -Method Post -Uri $uri -ContentType 'application/json; charset=utf-8' `
                               -Body $bytes -UseBasicParsing
    return ([Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray()) | ConvertFrom-Json)
}

$script:pgContainer = $null
function Invoke-Psql([string]$sql) {
    if (-not $script:pgContainer) {
        if ($PostgresContainer) { $script:pgContainer = $PostgresContainer }
        else {
            $names = @(docker ps --format "{{.Names}}" | Where-Object { $_ -like "*postgres*" })
            if ($names.Count -eq 0) { throw "No running container with 'postgres' in its name. Pass -PostgresContainer." }
            $script:pgContainer = $names[0]
        }
    }
    $out = docker exec $script:pgContainer psql -U $PostgresUser -d $PostgresDb -A -t -F "`t" -c $sql
    if ($LASTEXITCODE -ne 0) { throw "psql failed for: $sql" }
    # Callers wrap this in @(). A one-row result leaves a function as a bare string, and the
    # caller's $rows[0] would then index the string and return a [char].
    return @($out | Where-Object { $_ -ne "" })
}

function Get-SqlScalar([string]$sql) {
    $rows = @(Invoke-Psql $sql)
    if ($rows.Count -eq 0) { return $null }
    return [double]($rows[0].Split("`t")[0])
}

function Read-Cases([string]$suite, [string]$setName) {
    $path = Join-Path $caseDirFull "$setName/$suite.json"
    if (-not (Test-Path $path)) { throw "Case file not found: $path" }
    return (Get-Content $path -Raw -Encoding UTF8 | ConvertFrom-Json)
}

function Write-Section([string]$title) {
    Write-Host ""; Write-Host ("=" * 78); Write-Host $title; Write-Host ("=" * 78)
}

# Calls that fail for reasons that are nothing to do with the model's answer.
#
# The services retry Gemini three times internally and then return 500, so by the time the eval
# sees it, a free-tier quota exhaustion and a genuine server fault are the same HTTP status.
# Either way the case has not been measured, and the one thing that must never happen is
# recording it as a wrong answer: a run that scored 0% because the daily quota ran out would be
# averaged into the headline and make the system look broken when it was never asked.
#
# So: retry a few times with a pause, and if the failures keep coming, abort the run. A run that
# aborts is marked invalid and excluded from the aggregate rather than scored.
$script:consecutiveErrors = 0
function Invoke-WithRetry([scriptblock]$call) {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            $r = & $call
            $script:consecutiveErrors = 0
            return $r
        } catch {
            $msg = $_.Exception.Message
            if ($attempt -eq 3) {
                $script:consecutiveErrors++
                if ($script:consecutiveErrors -ge 4) {
                    throw "ABORT: four consecutive infrastructure failures ($msg). The free tier is " +
                          "500 requests per day per model; this is most likely the daily cap. " +
                          "Runs completed so far are kept."
                }
                throw $msg
            }
            Write-Warning "    call failed ($msg); retrying in 30s"
            Start-Sleep -Seconds 30
        }
    }
}

# ========================================================================== baselines

# A regex/heuristic extractor. Amount, account digits and direction are genuinely regex-able
# from these templates; merchant and date are where a rule-based reader is expected to struggle,
# which is the point of having it here.
function Get-BaselineExtraction([string]$sms) {
    $text = $sms

    $nonTx = $false
    foreach ($p in @('\bOTP\b', 'is your .*(code|password)', 'valid for', 'do not share',
                     'available balance is', 'balance in your', 'avail(able)?\.?\s*bal(ance)?\s+(is|in)',
                     'will be debited', 'e-mandate', 'pre-approved', 'cashback', 'T&C apply',
                     'out for delivery', 'has been delivered', 'statement for', 'RETURNED unpaid',
                     'Earn up to', 'Convert your', 'has fallen below')) {
        if ($text -match $p) { $nonTx = $true; break }
    }
    if ($nonTx) { return [pscustomobject]@{ isTransaction = $false } }

    # Strip the balance before looking for an amount, or "Avl Lmt Rs 52000" wins.
    $body = $text -replace '(?i)(avl\.?\s*(lmt|limit)|avail(able)?\.?\s*(bal|limit)[a-z.]*|total\s+bal|bal)[:\s]*(rs\.?|inr)?\s*[\d,]+(\.\d+)?', ' '

    $amount = $null
    if     ($body -match '(?i)(?:rs|inr)\.?\s*([\d,]+(?:\.\d{1,2})?)')    { $amount = [double]($Matches[1] -replace ',', '') }
    elseif ($body -match '(?i)debited by\s+([\d,]+(?:\.\d{1,2})?)')       { $amount = [double]($Matches[1] -replace ',', '') }

    $iDebit  = $text.ToLower().IndexOf('debit')
    $iCredit = $text.ToLower().IndexOf('credit')
    if ($text -match '(?i)^\s*(sent|spent|withdrawn)' -or $text -match '(?i)\b(spent|withdrawn)\b') { $type = 'DEBIT' }
    elseif ($text -match '(?i)^\s*received') { $type = 'CREDIT' }
    elseif ($iDebit -ge 0 -and ($iCredit -lt 0 -or $iDebit -lt $iCredit)) { $type = 'DEBIT' }
    elseif ($iCredit -ge 0) { $type = 'CREDIT' }
    else { $type = $null }

    $last4 = $null
    foreach ($p in @('(?i)ending\s+(\d{3,4})', '(?i)(?:a/?c|acct|account|card)\s*(?:no\.?)?\s*[*x\.]+\s*(\d{3,4})',
                     '(?i)\bAC\s*[*xX]+(\d{3,4})', '(?i)[*xX]{2,}\s*(\d{3,4})')) {
        if ($text -match $p) { $last4 = $Matches[1]; break }
    }

    $date = $null
    $months = @{ jan=1; feb=2; mar=3; apr=4; may=5; jun=6; jul=7; aug=8; sep=9; oct=10; nov=11; dec=12 }
    if ($text -match '(\d{4})-(\d{2})-(\d{2})') {
        $date = "{0}-{1}-{2}" -f $Matches[1], $Matches[2], $Matches[3]
    } elseif ($text -match '(?i)\b(\d{1,2})[-/](\d{1,2})[-/](\d{4})\b') {
        $date = "{0:0000}-{1:00}-{2:00}" -f [int]$Matches[3], [int]$Matches[2], [int]$Matches[1]
    } elseif ($text -match '(?i)\b(\d{1,2})[-/](\d{1,2})[-/](\d{2})\b') {
        $date = "{0:0000}-{1:00}-{2:00}" -f (2000 + [int]$Matches[3]), [int]$Matches[2], [int]$Matches[1]
    } elseif ($text -match '(?i)\b(\d{1,2})[-\s]?([A-Za-z]{3})[a-z]*[-\s]?(\d{2,4})\b') {
        $m = $months[$Matches[2].ToLower()]
        if ($m) {
            $y = [int]$Matches[3]; if ($y -lt 100) { $y += 2000 }
            $date = "{0:0000}-{1:00}-{2:00}" -f $y, $m, [int]$Matches[1]
        }
    }

    # Merchant: take the token run after the strongest "payee follows" marker, or the local part
    # of a VPA handle. This is the half a rule-based reader cannot really do.
    $merchant = $null
    if ($text -match '(?i)(?:vpa\s+)?([a-z0-9][a-z0-9._-]{2,})@[a-z]{2,}') {
        $merchant = ($Matches[1] -replace '[._-]', ' ')
    } else {
        foreach ($p in @('(?i)\b(?:trf to|toward[s]?|trf|to VPA)\s+([A-Z][A-Za-z0-9&''. ]{2,30})',
                         '(?i)\b(?:at|to|from)\s+([A-Z]{2,}(?:\s+[A-Z0-9&''.]{2,}){0,3})',
                         '(?i)\bA/c of\s+([A-Z][A-Za-z0-9&''. ]{2,30})')) {
            if ($text -match $p) { $merchant = $Matches[1]; break }
        }
    }
    if ($merchant) {
        $merchant = ($merchant -replace '(?i)\b(on|ref|refno|upi|bank|a/c|acct|is|has|been|avl|the)\b.*$', '').Trim(' .,;:-')
        if ($merchant.Length -lt 2) { $merchant = $null }
    }

    return [pscustomobject]@{
        isTransaction   = $true
        amount          = $amount
        merchant        = $merchant
        transactionType = $type
        transactionDate = $date
        accountLast4    = $last4
    }
}

# A keyword lookup table: the thing anyone would write before reaching for a model.
$BASELINE_CATEGORY_MAP = [ordered]@{
    'swiggy' = 'Food & Dining'; 'zomato' = 'Food & Dining'; 'dominos' = 'Food & Dining'
    'starbucks' = 'Food & Dining'; 'blue tokai' = 'Food & Dining'; 'coffee day' = 'Food & Dining'
    'bigbasket' = 'Groceries'; 'big basket' = 'Groceries'; 'blinkit' = 'Groceries'
    'zepto' = 'Groceries'; 'licious' = 'Groceries'; 'reliance smart' = 'Groceries'
    'instamart' = 'Groceries'
    'uber' = 'Transport'; 'ola' = 'Transport'; 'rapido' = 'Transport'
    'indian oil' = 'Transport'; 'petrol' = 'Transport'; 'hp ' = 'Transport'
    'amazon' = 'Shopping'; 'flipkart' = 'Shopping'; 'myntra' = 'Shopping'; 'croma' = 'Shopping'
    'nykaa' = 'Shopping'; 'decathlon' = 'Shopping'; 'lenskart' = 'Shopping'; 'ajio' = 'Shopping'
    'bescom' = 'Bills & Utilities'; 'airtel' = 'Bills & Utilities'; 'jio' = 'Bills & Utilities'
    'tata power' = 'Bills & Utilities'; 'gas' = 'Bills & Utilities'; 'life' = 'Bills & Utilities'
    'netflix' = 'Subscriptions'; 'spotify' = 'Subscriptions'; 'hotstar' = 'Subscriptions'
    'bookmyshow' = 'Entertainment'; 'pvr' = 'Entertainment'
    'apollo' = 'Health'; 'pharmeasy' = 'Health'; '1mg' = 'Health'; 'practo' = 'Health'
    'cult' = 'Health'
    'irctc' = 'Travel'; 'makemytrip' = 'Travel'; 'oyo' = 'Travel'; 'redbus' = 'Travel'
    'cleartrip' = 'Travel'
    'self account' = 'Transfers'
}

function Get-BaselineCategory([string]$merchant) {
    $m = $merchant.ToLowerInvariant()
    foreach ($k in $BASELINE_CATEGORY_MAP.Keys) {
        if ($m.Contains($k)) { return $BASELINE_CATEGORY_MAP[$k] }
    }
    return 'Other'
}

# Does the question contain arithmetic words? That is the whole heuristic.
function Get-BaselineRoute([string]$question) {
    if ($question -match '(?i)\b(how much|how many|total|sum|average|avg|count|largest|biggest|most|least|add up|altogether)\b') {
        return 'sql'
    }
    return 'rag'
}

# ====================================================================== scoring: extraction

$EXTRACTION_FIELDS = @('amount', 'merchant', 'transactionType', 'transactionDate', 'accountLast4')

function Get-ExtractionVerdict($case, $r) {
    $fields = @{}; $classes = @(); $problems = @()

    if ($null -eq $case.expected) {
        $said = [bool]$r.isTransaction
        if ($said) { $classes += 'false-transaction'; $problems += "stored as merchant='$($r.merchant)' amount=$($r.amount)" }
        return [pscustomobject]@{
            kind = 'non-transaction'; pass = (-not $said); fields = $null
            classes = $classes; detail = $(if ($said) { $problems -join '; ' } else { 'correctly rejected' })
        }
    }

    if (-not [bool]$r.isTransaction) {
        foreach ($f in $EXTRACTION_FIELDS) { $fields[$f] = $false }
        return [pscustomobject]@{
            kind = 'transaction'; pass = $false; fields = [pscustomobject]$fields
            classes = @('missed-transaction'); detail = 'classified as a non-transaction'
        }
    }

    $e = $case.expected

    $fields['amount'] = ($null -ne $r.amount) -and ([math]::Abs([double]$r.amount - [double]$e.amount) -lt 0.005)
    if (-not $fields['amount']) { $classes += 'amount'; $problems += "amount=$($r.amount) want $($e.amount)" }

    $accepted = @($e.merchant | ForEach-Object { Get-Normalized $_ })
    $got      = Get-Normalized $r.merchant
    $fields['merchant'] = $accepted -contains $got
    if (-not $fields['merchant']) {
        # Expecting "" means the template names no payee, so a name here is invented, not merely
        # mis-spelled. Those are different defects and the taxonomy keeps them apart.
        if (($accepted -contains "") -and $got -ne "") { $classes += 'merchant-hallucinated' }
        else { $classes += 'merchant' }
        $problems += "merchant='$($r.merchant)' want one of [$($e.merchant -join ', ')]"
    }

    $fields['transactionType'] = ("$($r.transactionType)" -eq "$($e.transactionType)")
    if (-not $fields['transactionType']) { $classes += 'type'; $problems += "type=$($r.transactionType) want $($e.transactionType)" }

    $wantDate = if ($null -eq $e.transactionDate) { "" } else { "$($e.transactionDate)" }
    $gotDate  = if ($null -eq $r.transactionDate) { "" } else { "$($r.transactionDate)" }
    $fields['transactionDate'] = ($gotDate -eq $wantDate)
    if (-not $fields['transactionDate']) {
        if ($wantDate -eq "" -and $gotDate -ne "") { $classes += 'date-invented' } else { $classes += 'date' }
        $problems += "date=$gotDate want '$wantDate'"
    }

    $fields['accountLast4'] = ("$($r.accountLast4)" -eq "$($e.accountLast4)")
    if (-not $fields['accountLast4']) { $classes += 'last4'; $problems += "last4=$($r.accountLast4) want $($e.accountLast4)" }

    $pass = ($classes.Count -eq 0)
    return [pscustomobject]@{
        kind = 'transaction'; pass = $pass; fields = [pscustomobject]$fields; classes = $classes
        detail = $(if ($pass) { "$($r.merchant) $($r.amount) $($r.transactionType) $($r.transactionDate)" } else { $problems -join '; ' })
    }
}

function Invoke-ExtractionRun($cases, [bool]$baseline) {
    $records = @()
    $i = 0
    foreach ($c in $cases) {
        $i++
        try {
            if ($baseline) { $r = Get-BaselineExtraction $c.sms }
            else { $r = Invoke-WithRetry { Invoke-JsonPost $ExtractUrl @{ rawText = $c.sms } } }
            $v = Get-ExtractionVerdict $c $r
            $records += [pscustomobject]@{
                id = $c.id; bank = $c.bank; kind = $v.kind; pass = $v.pass
                fields = $v.fields; classes = $v.classes; detail = $v.detail
            }
        } catch {
            if ($_.Exception.Message -like 'ABORT:*') { throw }
            $records += [pscustomobject]@{
                id = $c.id; bank = $c.bank; kind = $(if ($c.expected) { 'transaction' } else { 'non-transaction' })
                pass = $false; fields = $null; classes = @('service-error'); detail = $_.Exception.Message
            }
        }
        if (-not $baseline -and $i -lt $cases.Count) { Start-Sleep -Milliseconds $CallDelayMs }
    }
    return $records
}

function Get-ExtractionMetrics($records) {
    $tx   = @($records | Where-Object { $_.kind -eq 'transaction' })
    $nonTx = @($records | Where-Object { $_.kind -eq 'non-transaction' })
    $hits = 0; $total = 0
    $perField = [ordered]@{}
    foreach ($f in $EXTRACTION_FIELDS) {
        $ok = @($tx | Where-Object { $_.fields -and $_.fields.$f }).Count
        $perField[$f] = (Get-Pct $ok $tx.Count)
        $hits += $ok; $total += $tx.Count
    }
    return [ordered]@{
        fieldAccuracy        = (Get-Pct $hits $total)
        perField             = $perField
        exactRecords         = (Get-Pct @($tx | Where-Object { $_.pass }).Count $tx.Count)
        nonTransactionReject = (Get-Pct @($nonTx | Where-Object { $_.pass }).Count $nonTx.Count)
    }
}

# ================================================================= scoring: categorization

function Invoke-CategorizationRun($cases, [bool]$baseline) {
    $records = @()
    $i = 0
    foreach ($c in $cases) {
        $i++
        try {
            if ($baseline) {
                $cat = Get-BaselineCategory $c.merchant
                $conf = 1.0; $needsReview = $false; $reasoning = 'keyword lookup'
            } else {
                $r = Invoke-WithRetry { Invoke-JsonPost $CategorizeUrl @{ merchant = $c.merchant; amount = $c.amount; transactionType = $c.transactionType } }
                $cat = $r.category; $conf = [double]$r.confidence
                $needsReview = [bool]$r.needsReview; $reasoning = $r.reasoning
            }

            $pass = ($cat -eq $c.expected)
            $alts = @()
            if ($c.PSObject.Properties.Name -contains 'acceptableAlternatives' -and $c.acceptableAlternatives) {
                $alts = @($c.acceptableAlternatives)
            }
            $classes = @()
            if (-not $pass) {
                if ($CATEGORIES -notcontains $cat) { $classes += 'off-list' }
                elseif ($alts -contains $cat)       { $classes += 'ambiguous' }
                else                                { $classes += 'clearly-wrong' }
            }

            $records += [pscustomobject]@{
                id = $c.id; merchant = $c.merchant; expected = $c.expected; actual = $cat
                confidence = $conf; needsReview = $needsReview; reasoning = $reasoning
                pass = $pass; classes = $classes
                detail = $(if ($pass) { "$cat" } else { "said $cat, want $($c.expected)" })
            }
        } catch {
            if ($_.Exception.Message -like 'ABORT:*') { throw }
            $records += [pscustomobject]@{
                id = $c.id; merchant = $c.merchant; expected = $c.expected; actual = $null
                confidence = $null; needsReview = $null; reasoning = $null
                pass = $false; classes = @('service-error'); detail = $_.Exception.Message
            }
        }
        if (-not $baseline -and $i -lt $cases.Count) { Start-Sleep -Milliseconds $CallDelayMs }
    }
    return $records
}

function Get-CategorizationMetrics($records) {
    $scored     = @($records | Where-Object { $_.classes -notcontains 'service-error' })
    $flagged    = @($scored | Where-Object { $_.needsReview })
    $confident  = @($scored | Where-Object { -not $_.needsReview })
    return [ordered]@{
        accuracy       = (Get-Pct @($scored | Where-Object { $_.pass }).Count $scored.Count)
        clearlyWrong   = @($scored | Where-Object { $_.classes -contains 'clearly-wrong' }).Count
        ambiguous      = @($scored | Where-Object { $_.classes -contains 'ambiguous' }).Count
        flaggedWrongPct   = (Get-Pct @($flagged   | Where-Object { -not $_.pass }).Count $flagged.Count)
        confidentWrongPct = (Get-Pct @($confident | Where-Object { -not $_.pass }).Count $confident.Count)
        confidentCount    = $confident.Count
        confidentWrong    = @($confident | Where-Object { -not $_.pass }).Count
    }
}

function Get-ConfusionMatrix($records) {
    $m = @{}
    foreach ($r in $records) {
        if ($r.classes -contains 'service-error') { continue }
        if (-not $m.ContainsKey($r.expected)) { $m[$r.expected] = @{} }
        $k = "$($r.actual)"
        if (-not $m[$r.expected].ContainsKey($k)) { $m[$r.expected][$k] = 0 }
        $m[$r.expected][$k]++
    }
    $out = [ordered]@{}
    foreach ($e in ($m.Keys | Sort-Object)) {
        $inner = [ordered]@{}
        foreach ($a in ($m[$e].Keys | Sort-Object)) { $inner[$a] = $m[$e][$a] }
        $out[$e] = $inner
    }
    return $out
}

# ========================================================================= scoring: query

function Invoke-QueryRun($cases, [bool]$baseline) {
    $records = @()
    $i = 0
    foreach ($c in $cases) {
        $i++

        if ($baseline) {
            # The baseline only answers the routing question; it has no way to produce a figure.
            $routed = Get-BaselineRoute $c.question
            $records += [pscustomobject]@{
                id = $c.id; question = $c.question; expectedMethod = $c.expectedMethod
                routedTo = $routed; routingPass = ($routed -eq $c.expectedMethod)
                aggregation = $null; resolution = $null; classes = @(); detail = "heuristic -> $routed"
            }
            continue
        }

        try {
            $r = Invoke-WithRetry { Invoke-JsonPost $QueryUrl @{ question = $c.question } }
            # query-service answers a question it cannot handle with HTTP 200 and an `error`
            # field. That is a declined question, not an infrastructure failure, so it is scored
            # rather than retried.
            if (($r.PSObject.Properties.Name -contains 'error') -and $r.error) { throw "query-service declined: $($r.error)" }

            # The ROUTER's decision, not the path the answer came back on. A question routed to
            # SQL that found nothing is retried over the embeddings and returns method=rag with
            # fallbackFrom=sql; the router was right and the SQL path is what failed.
            $fellBack = (($r.PSObject.Properties.Name -contains 'fallbackFrom') -and $r.fallbackFrom)
            $routedTo = if ($fellBack) { $r.fallbackFrom } else { $r.method }
            $routingPass = ($routedTo -eq $c.expectedMethod)

            $classes = @()
            if (-not $routingPass) { $classes += 'routing-error' }
            if ($fellBack) { $classes += 'sql-no-rows' }

            $agg = $null; $want = $null; $got = $null
            if ($c.groundTruthSql) {
                $want = Get-SqlScalar $c.groundTruthSql
                if ($r.method -eq 'sql' -and $r.rows -and @($r.rows).Count -gt 0) {
                    foreach ($p in (@($r.rows)[0]).PSObject.Properties) {
                        $parsed = 0.0
                        if ([double]::TryParse([string]$p.Value, [ref]$parsed)) { $got = $parsed; break }
                    }
                }
                if ($null -ne $got -and [math]::Abs($got - $want) -lt 0.01) { $agg = $true }
                else {
                    $agg = $false
                    if     ($null -eq $got)  { $classes += 'sql-no-answer' }
                    elseif ($got -gt $want)  { $classes += 'sql-overmatch' }
                    else                     { $classes += 'sql-undermatch' }
                }
            }

            $res = $null; $surfaced = @()
            if ($c.expectedMerchants -and @($c.expectedMerchants).Count -gt 0) {
                if ($r.method -eq 'sql' -and $r.resolvedMerchants) { $surfaced = @($r.resolvedMerchants | ForEach-Object { $_.merchant_name }) }
                elseif ($r.retrievedTransactions)                  { $surfaced = @($r.retrievedTransactions | ForEach-Object { $_.merchant }) }
                $norm = @($surfaced | ForEach-Object { Get-Normalized $_ })
                $missing = @()
                foreach ($w in $c.expectedMerchants) {
                    $wn = Get-Normalized $w
                    if (-not ($norm | Where-Object { $_ -like "*$wn*" })) { $missing += $w }
                }
                $res = ($missing.Count -eq 0)
                if (-not $res) { $classes += 'resolution-miss' }
            }

            $records += [pscustomobject]@{
                id = $c.id; question = $c.question; expectedMethod = $c.expectedMethod
                routedTo = $routedTo; routingPass = $routingPass
                aggregation = $agg; expectedValue = $want; actualValue = $got
                resolution = $res; surfacedMerchants = $surfaced
                sql = $r.sql; answer = $r.answer; classes = $classes
                detail = "route $routedTo" + $(if ($null -ne $agg) { "; agg got $got want $want" } else { "" })
            }
        } catch {
            if ($_.Exception.Message -like 'ABORT:*') { throw }
            $records += [pscustomobject]@{
                id = $c.id; question = $c.question; expectedMethod = $c.expectedMethod
                routedTo = $null; routingPass = $false; aggregation = $null; resolution = $null
                classes = @('service-error'); detail = $_.Exception.Message
            }
        }
        if ($i -lt $cases.Count) { Start-Sleep -Milliseconds $QueryDelayMs }
    }
    return $records
}

function Get-QueryMetrics($records) {
    $routed = @($records | Where-Object { $_.classes -notcontains 'service-error' })
    $aggs   = @($records | Where-Object { $null -ne $_.aggregation })
    $ress   = @($records | Where-Object { $null -ne $_.resolution })
    return [ordered]@{
        routing     = (Get-Pct @($routed | Where-Object { $_.routingPass }).Count $routed.Count)
        aggregation = (Get-Pct @($aggs | Where-Object { $_.aggregation }).Count $aggs.Count)
        resolution  = (Get-Pct @($ress | Where-Object { $_.resolution }).Count $ress.Count)
    }
}

# =========================================================================== aggregation

function Get-Spread($values) {
    $v = @($values | Where-Object { $null -ne $_ })
    if ($v.Count -eq 0) { return $null }
    return [ordered]@{
        mean = [math]::Round(($v | Measure-Object -Average).Average, 1)
        min  = [math]::Round(($v | Measure-Object -Minimum).Minimum, 1)
        max  = [math]::Round(($v | Measure-Object -Maximum).Maximum, 1)
        runs = $v.Count
    }
}

# A case whose verdict changes between runs is more interesting than the average, because it is
# the one that will behave differently in front of whoever is watching.
function Get-UnstableCases($runsRecords, [string]$verdictProperty) {
    $byId = @{}
    foreach ($run in $runsRecords) {
        foreach ($rec in $run) {
            if (-not $byId.ContainsKey($rec.id)) { $byId[$rec.id] = @() }
            $byId[$rec.id] += ,@($rec.$verdictProperty, $rec.detail)
        }
    }
    $unstable = @()
    foreach ($id in ($byId.Keys | Sort-Object)) {
        $verdicts = @($byId[$id] | ForEach-Object { [string]$_[0] })
        if (($verdicts | Select-Object -Unique).Count -gt 1) {
            $unstable += [ordered]@{
                id = $id
                verdicts = $verdicts
                distinctOutcomes = @($byId[$id] | ForEach-Object { [string]$_[1] } | Select-Object -Unique)
            }
        }
    }
    return $unstable
}

function Get-Taxonomy($runsRecords) {
    $counts = @{}
    foreach ($run in $runsRecords) {
        foreach ($rec in $run) {
            foreach ($cls in @($rec.classes)) {
                if (-not $cls) { continue }
                if (-not $counts.ContainsKey($cls)) { $counts[$cls] = @{ total = 0; examples = @() } }
                $counts[$cls].total++
                if ($counts[$cls].examples.Count -lt 3 -and $counts[$cls].examples -notcontains "$($rec.id): $($rec.detail)") {
                    $counts[$cls].examples += "$($rec.id): $($rec.detail)"
                }
            }
        }
    }
    $out = [ordered]@{}
    foreach ($k in ($counts.Keys | Sort-Object { -$counts[$_].total })) {
        $out[$k] = [ordered]@{
            occurrences   = $counts[$k].total
            perRunAverage = [math]::Round($counts[$k].total / [double]$runsRecords.Count, 1)
            examples      = $counts[$k].examples
        }
    }
    return $out
}

# ================================================================================== run

$results = [ordered]@{
    runStarted = $runStarted.ToString("o")
    model      = $env:GEMINI_MODEL
    runs       = $(if ($BaselinesOnly) { 0 } else { $Runs })
    sets       = ($Set -join ',')
    suites     = ($Only -join ',')
    endpoints  = [ordered]@{ extract = $ExtractUrl; categorize = $CategorizeUrl; query = $QueryUrl }
    results    = [ordered]@{}
}

$jsonPath = Join-Path $outDirFull "eval-$stamp.json"
$latest   = Join-Path $outDirFull "latest.json"
function Save-Results {
    $json = $results | ConvertTo-Json -Depth 14
    [IO.File]::WriteAllText($jsonPath, $json, $noBom)
    [IO.File]::WriteAllText($latest,   $json, $noBom)
}

function Test-Requested([string]$name) { return ($Only -contains 'all') -or ($Only -contains $name) }

$suiteConfig = @(
    @{ name = 'extraction';     runner = { param($c, $b) Invoke-ExtractionRun     $c $b }; metrics = { param($r) Get-ExtractionMetrics     $r }; verdict = 'pass' }
    @{ name = 'categorization'; runner = { param($c, $b) Invoke-CategorizationRun $c $b }; metrics = { param($r) Get-CategorizationMetrics $r }; verdict = 'pass' }
    @{ name = 'query';          runner = { param($c, $b) Invoke-QueryRun          $c $b }; metrics = { param($r) Get-QueryMetrics          $r }; verdict = 'routingPass' }
)

foreach ($suite in $suiteConfig) {
    if (-not (Test-Requested $suite.name)) { continue }
    $results.results[$suite.name] = [ordered]@{}

    foreach ($setName in $Set) {
        Write-Section "$($suite.name.ToUpper())  -  $($setName.ToUpper()) set"
        $spec  = Read-Cases $suite.name $setName
        $cases = $spec.cases
        Write-Host "$($cases.Count) cases"

        $entry = [ordered]@{
            caseCount  = $cases.Count
            provenance = $(if ($spec.PSObject.Properties.Name -contains 'provenance') { $spec.provenance } else { $spec.description })
        }

        # Baseline first: deterministic, free, and it tells you what the score has to beat.
        try {
            $baseRecords = & $suite.runner $cases $true
            $entry.baseline = [ordered]@{
                metrics = (& $suite.metrics $baseRecords)
                detail  = $baseRecords
            }
            Write-Host "  baseline: $(($entry.baseline.metrics.GetEnumerator() | Where-Object { $_.Value -is [double] -or $_.Value -is [int] } | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join '  ')"
        } catch {
            Write-Warning "  baseline failed: $($_.Exception.Message)"
            $entry.baseline = [ordered]@{ failed = $true; error = $_.Exception.Message }
        }

        if (-not $BaselinesOnly) {
            $allRuns = @()
            $runMetrics = @()
            for ($n = 1; $n -le $Runs; $n++) {
                Write-Host "  run $n/$Runs ..." -NoNewline
                try {
                    $script:consecutiveErrors = 0
                    $recs = & $suite.runner $cases $false

                    # A case that errored was never asked, so it is not a wrong answer. Scoring
                    # it as one is how a quota outage turns into a 0% headline. Above a small
                    # tolerance the whole run is unmeasured and is thrown away, not averaged in.
                    $errs = @($recs | Where-Object { $_.classes -contains 'service-error' }).Count
                    $errPct = Get-Pct $errs $recs.Count
                    if ($errPct -gt 5) {
                        Write-Warning (" DISCARDED: {0}/{1} cases failed for infrastructure reasons ({2}%). Not scored." -f $errs, $recs.Count, $errPct)
                        if (-not $entry.discardedRuns) { $entry.discardedRuns = @() }
                        $entry.discardedRuns += [ordered]@{
                            run = $n; serviceErrors = $errs; of = $recs.Count
                            firstError = @($recs | Where-Object { $_.classes -contains 'service-error' } | Select-Object -First 1).detail
                        }
                        break
                    }

                    $m = & $suite.metrics $recs
                    $allRuns += ,$recs
                    $runMetrics += $m
                    Write-Host (" " + (($m.GetEnumerator() | Where-Object { $_.Value -is [double] -or $_.Value -is [int] } | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join '  '))
                } catch {
                    Write-Warning " aborted: $($_.Exception.Message)"
                    if (-not $entry.abortedAt) { $entry.abortedAt = [ordered]@{ run = $n; reason = $_.Exception.Message } }
                    break
                }
                # Persist after every run, so a stopped shell costs one run and not all of them.
                $entry.runsCompleted = $allRuns.Count
                $entry.perRunMetrics = $runMetrics
                $entry.detail = $allRuns
                $results.results[$suite.name][$setName] = $entry
                Save-Results
            }

            if ($allRuns.Count -gt 0) {
                $spread = [ordered]@{}
                foreach ($k in $runMetrics[0].Keys) {
                    $vals = @($runMetrics | ForEach-Object { $_.$k })
                    if ($vals[0] -is [double] -or $vals[0] -is [int]) { $spread[$k] = (Get-Spread $vals) }
                }
                $entry.aggregate     = $spread
                $entry.unstableCases = (Get-UnstableCases $allRuns $suite.verdict)
                $entry.taxonomy      = (Get-Taxonomy $allRuns)
                if ($suite.name -eq 'categorization') {
                    $entry.confusionMatrix = (Get-ConfusionMatrix $allRuns[0])
                }
            }
        }

        $results.results[$suite.name][$setName] = $entry
        Save-Results
    }
}

$results.runFinished     = (Get-Date).ToString("o")
$results.durationMinutes = [math]::Round(((Get-Date) - $runStarted).TotalMinutes, 1)
Save-Results

# ============================================================================== report

Write-Section "SUMMARY   (dev is contaminated and is an upper bound; test is the honest number)"
foreach ($suiteName in $results.results.Keys) {
    Write-Host ""
    Write-Host $suiteName.ToUpper()
    foreach ($setName in $results.results[$suiteName].Keys) {
        $e = $results.results[$suiteName][$setName]
        if ($e.aggregate) {
            foreach ($k in $e.aggregate.Keys) {
                $s = $e.aggregate[$k]
                $b = if ($e.baseline.metrics -and ($e.baseline.metrics.Keys -contains $k)) { $e.baseline.metrics[$k] } else { $null }
                $bText = if ($null -ne $b) { "   baseline $b" } else { "" }
                Write-Host ("  {0,-5} {1,-22} mean {2,6}  min {3,6}  max {4,6}{5}" -f $setName, $k, $s.mean, $s.min, $s.max, $bText)
            }
            if ($e.unstableCases.Count) {
                Write-Host ("  {0,-5} unstable across runs: {1}" -f $setName, (($e.unstableCases | ForEach-Object { $_.id }) -join ', '))
            }
        } elseif ($e.baseline -and $e.baseline.metrics) {
            foreach ($k in $e.baseline.metrics.Keys) {
                if ($e.baseline.metrics[$k] -is [double] -or $e.baseline.metrics[$k] -is [int]) {
                    Write-Host ("  {0,-5} {1,-22} baseline only: {2}" -f $setName, $k, $e.baseline.metrics[$k])
                }
            }
        }
        if ($e.taxonomy -and $e.taxonomy.Keys.Count) {
            Write-Host ("  {0,-5} failures: {1}" -f $setName,
                (($e.taxonomy.Keys | ForEach-Object { "$_ x$($e.taxonomy[$_].occurrences)" }) -join ', '))
        }
    }
}

Write-Host ""
Write-Host "Results: $jsonPath"
Write-Host "         $latest"
