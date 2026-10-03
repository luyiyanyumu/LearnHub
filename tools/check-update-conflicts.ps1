<#
    检查「从远端更新时会被本地文件挡住」的清单。

    为什么单独用 PowerShell 做：cmd 的 for /f 解析 `git status --porcelain` 输出
    会因为行首空格而切错 token，而 findstr /x 对重定向生成的 git 输出做整行精确
    匹配又会失效（子串匹配正常）。两个坑叠加导致冲突漏检，所以这里直接拿
    「不带状态码」的 git 命令输出做集合求交，稳得多。

    输出：每行一个文件路径（相对仓库根）
    退出码：0 = 无冲突；1 = 有冲突；2 = 不是 git 仓库 / 命令异常
#>
[CmdletBinding()]
param(
    [string]$Branch = 'master'
)

$ErrorActionPreference = 'Continue'

if (-not (git rev-parse --git-dir 2>$null)) { exit 2 }

# 远端这次要动的文件
$upstream = @(git diff --name-only HEAD ("origin/" + $Branch) 2>$null | Where-Object { $_ })

# 本地有动静的文件：未暂存改动 + 已暂存改动 + 未跟踪文件
$local = @()
$local += @(git diff --name-only 2>$null)
$local += @(git diff --name-only --cached 2>$null)
$local += @(git ls-files --others --exclude-standard 2>$null)
$local = @($local | Where-Object { $_ } | Select-Object -Unique)

$conflict = @($local | Where-Object { $upstream -contains $_ })

if ($conflict.Count -eq 0) { '__NO_CONFLICT__'; exit 0 }

foreach ($f in $conflict) { $f }
exit 1
