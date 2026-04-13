param(
    [string]$InputPath = "C:\Users\yutao\IdeaProjects\InfiniteChat\简历_V3.md",
    [string]$OutputPath,
    [string]$SectionHeading = "## 最终可投递版（后端 / 架构向）",
    [switch]$KeepHtml
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-EdgePath {
    $candidates = @(
        "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        "C:\Program Files\Microsoft\Edge\Application\msedge.exe",
        "C:\Program Files\Google\Chrome\Application\chrome.exe",
        "C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"
    )

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    throw "No supported browser found. Install Edge or Chrome first."
}

function Read-TextAuto {
    param([string]$Path)

    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        return [System.Text.Encoding]::UTF8.GetString($bytes, 3, $bytes.Length - 3)
    }

    $utf8Strict = New-Object System.Text.UTF8Encoding($false, $true)
    try {
        return $utf8Strict.GetString($bytes)
    } catch {
        $gbk = [System.Text.Encoding]::GetEncoding(936)
        return $gbk.GetString($bytes)
    }
}

function Convert-InlineMarkdown {
    param([string]$Text)

    $encoded = [System.Net.WebUtility]::HtmlEncode($Text)
    $codeTokens = New-Object System.Collections.Generic.List[string]

    $encoded = [System.Text.RegularExpressions.Regex]::Replace(
        $encoded,
        '`([^`]+)`',
        {
            param($match)
            $token = "%%CODE$($codeTokens.Count)%%"
            $codeTokens.Add("<code>$($match.Groups[1].Value)</code>")
            return $token
        }
    )

    $encoded = [System.Text.RegularExpressions.Regex]::Replace(
        $encoded,
        '\*\*(.+?)\*\*',
        '<strong>$1</strong>'
    )

    for ($i = 0; $i -lt $codeTokens.Count; $i++) {
        $encoded = $encoded.Replace("%%CODE$i%%", $codeTokens[$i])
    }

    return $encoded
}

function Get-MarkdownSlice {
    param(
        [string]$Content,
        [string]$Heading
    )

    if ([string]::IsNullOrWhiteSpace($Heading)) {
        return $Content
    }

    $lines = $Content -split "\r?\n"
    $start = -1
    for ($i = 0; $i -lt $lines.Length; $i++) {
        if ($lines[$i].Trim() -eq $Heading.Trim()) {
            $start = $i
            break
        }
    }

    if ($start -lt 0) {
        return $Content
    }

    $end = $lines.Length
    for ($i = $start + 1; $i -lt $lines.Length; $i++) {
        if ($lines[$i] -match '^##\s+') {
            $end = $i
            break
        }
    }

    if ($end -le ($start + 1)) {
        return ""
    }

    return (($lines[($start + 1)..($end - 1)]) -join "`r`n").Trim()
}

function Convert-MarkdownToHtmlBody {
    param([string]$Markdown)

    $sb = New-Object System.Text.StringBuilder
    $paragraph = New-Object System.Collections.Generic.List[string]
    $codeLines = New-Object System.Collections.Generic.List[string]
    $lines = $Markdown -split "\r?\n"

    $inUl = $false
    $inOl = $false
    $inCode = $false
    $seenFirstBlock = $false

    $flushParagraph = {
        if ($paragraph.Count -gt 0) {
            $text = ($paragraph -join " ").Trim()
            [void]$sb.AppendLine("<p>$(Convert-InlineMarkdown $text)</p>")
            $paragraph.Clear()
        }
    }

    $closeLists = {
        if ($inUl) {
            [void]$sb.AppendLine("</ul>")
            $inUl = $false
        }
        if ($inOl) {
            [void]$sb.AppendLine("</ol>")
            $inOl = $false
        }
    }

    $flushCode = {
        if ($codeLines.Count -gt 0) {
            $codeHtml = [System.Net.WebUtility]::HtmlEncode(($codeLines -join "`r`n"))
            [void]$sb.AppendLine("<pre><code>$codeHtml</code></pre>")
            $codeLines.Clear()
        }
    }

    foreach ($line in $lines) {
        $trimmed = $line.Trim()

        if ($trimmed -match '^```') {
            & $flushParagraph
            & $closeLists

            if ($inCode) {
                & $flushCode
                $inCode = $false
            } else {
                $inCode = $true
            }
            continue
        }

        if ($inCode) {
            $codeLines.Add($line)
            continue
        }

        if ([string]::IsNullOrWhiteSpace($trimmed)) {
            & $flushParagraph
            & $closeLists
            continue
        }

        if (-not $seenFirstBlock -and $trimmed -match '^\*\*(.+?)\*\*\s*\|\s*(.+)$') {
            & $flushParagraph
            & $closeLists
            $title = Convert-InlineMarkdown $matches[1]
            $meta = Convert-InlineMarkdown $matches[2]
            [void]$sb.AppendLine("<header class=""resume-header""><h1>$title</h1><p class=""meta"">$meta</p></header>")
            $seenFirstBlock = $true
            continue
        }

        if ($trimmed -match '^(#{1,3})\s+(.+)$') {
            & $flushParagraph
            & $closeLists
            $level = $matches[1].Length
            $text = Convert-InlineMarkdown $matches[2]
            [void]$sb.AppendLine("<h$level>$text</h$level>")
            $seenFirstBlock = $true
            continue
        }

        if ($trimmed -match '^[-*]\s+(.+)$') {
            & $flushParagraph
            if ($inOl) {
                [void]$sb.AppendLine("</ol>")
                $inOl = $false
            }
            if (-not $inUl) {
                [void]$sb.AppendLine("<ul>")
                $inUl = $true
            }
            [void]$sb.AppendLine("<li>$(Convert-InlineMarkdown $matches[1])</li>")
            $seenFirstBlock = $true
            continue
        }

        if ($trimmed -match '^\d+\.\s+(.+)$') {
            & $flushParagraph
            if ($inUl) {
                [void]$sb.AppendLine("</ul>")
                $inUl = $false
            }
            if (-not $inOl) {
                [void]$sb.AppendLine("<ol>")
                $inOl = $true
            }
            [void]$sb.AppendLine("<li>$(Convert-InlineMarkdown $matches[1])</li>")
            $seenFirstBlock = $true
            continue
        }

        $paragraph.Add($trimmed)
        $seenFirstBlock = $true
    }

    & $flushParagraph
    & $closeLists
    & $flushCode

    return $sb.ToString()
}

function Build-HtmlDocument {
    param(
        [string]$Title,
        [string]$BodyHtml
    )

    $safeTitle = [System.Net.WebUtility]::HtmlEncode($Title)
    return @"
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>$safeTitle</title>
<style>
@page {
  size: A4;
  margin: 12mm 14mm;
}

* {
  box-sizing: border-box;
}

body {
  margin: 0;
  font-family: "Microsoft YaHei", "PingFang SC", "Segoe UI", sans-serif;
  color: #1f2937;
  background: #ffffff;
  font-size: 11.5pt;
  line-height: 1.55;
}

main {
  width: 100%;
}

.resume-header {
  border-bottom: 2px solid #dbe4f0;
  margin-bottom: 12px;
  padding-bottom: 8px;
}

h1 {
  margin: 0;
  font-size: 20pt;
  line-height: 1.2;
  color: #0f172a;
}

.meta {
  margin: 6px 0 0;
  color: #475569;
  font-size: 10.5pt;
}

h2,
h3 {
  margin: 14px 0 8px;
  color: #0f172a;
}

h2 {
  font-size: 14pt;
}

h3 {
  font-size: 12.5pt;
}

p {
  margin: 0 0 10px;
}

ul,
ol {
  margin: 0 0 10px 1.1em;
  padding: 0;
}

li {
  margin-bottom: 6px;
}

strong {
  color: #0f172a;
}

code {
  font-family: Consolas, "Courier New", monospace;
  font-size: 0.92em;
  background: #f3f4f6;
  border-radius: 4px;
  padding: 0 4px;
}

pre {
  margin: 10px 0;
  padding: 10px 12px;
  background: #0f172a;
  color: #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
  white-space: pre-wrap;
  word-break: break-word;
}

pre code {
  background: transparent;
  padding: 0;
  color: inherit;
}
</style>
</head>
<body>
<main>
$BodyHtml
</main>
</body>
</html>
"@
}

$resolvedInput = [System.IO.Path]::GetFullPath($InputPath)
if (-not (Test-Path -LiteralPath $resolvedInput)) {
    throw "Input markdown file not found: $resolvedInput"
}

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $baseName = [System.IO.Path]::GetFileNameWithoutExtension($resolvedInput)
    $OutputPath = Join-Path ([System.IO.Path]::GetDirectoryName($resolvedInput)) ($baseName + ".pdf")
}

$resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)
$outputDir = Split-Path -Parent $resolvedOutput
if (-not (Test-Path -LiteralPath $outputDir)) {
    New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
}

$markdown = Read-TextAuto -Path $resolvedInput
$section = Get-MarkdownSlice -Content $markdown -Heading $SectionHeading
if ([string]::IsNullOrWhiteSpace($section)) {
    $section = $markdown
}

$bodyHtml = Convert-MarkdownToHtmlBody -Markdown $section
$documentTitle = [System.IO.Path]::GetFileNameWithoutExtension($resolvedInput)
$html = Build-HtmlDocument -Title $documentTitle -BodyHtml $bodyHtml

$tempHtml = Join-Path ([System.IO.Path]::GetTempPath()) ("resume-export-" + [Guid]::NewGuid().ToString("N") + ".html")
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($tempHtml, $html, $utf8NoBom)

$browserPath = Get-EdgePath
$htmlUri = ([System.Uri]$tempHtml).AbsoluteUri
$browserProfileDir = Join-Path $outputDir (".pdf-export-profile-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $browserProfileDir | Out-Null

try {
    $arguments = @(
        "--headless"
        "--disable-gpu"
        "--no-sandbox"
        "--disable-breakpad"
        "--disable-crash-reporter"
        "--allow-file-access-from-files"
        "--no-pdf-header-footer"
        "--user-data-dir=$browserProfileDir"
        "--print-to-pdf=$resolvedOutput"
        $htmlUri
    )

    $process = Start-Process -FilePath $browserPath -ArgumentList $arguments -Wait -PassThru -NoNewWindow
    if ($process.ExitCode -ne 0) {
        throw "Browser export failed with exit code $($process.ExitCode)."
    }

    if (-not (Test-Path -LiteralPath $resolvedOutput)) {
        throw "PDF was not created: $resolvedOutput"
    }

    Write-Output "PDF generated: $resolvedOutput"
} finally {
    if (-not $KeepHtml -and (Test-Path -LiteralPath $tempHtml)) {
        Remove-Item -LiteralPath $tempHtml -Force
    }
    if (Test-Path -LiteralPath $browserProfileDir) {
        Remove-Item -LiteralPath $browserProfileDir -Recurse -Force
    }
}
