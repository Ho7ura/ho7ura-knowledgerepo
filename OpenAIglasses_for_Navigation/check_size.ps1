foreach ($parent in @("$env:USERPROFILE\AppData\Local", "$env:USERPROFILE\AppData\Roaming", "$env:USERPROFILE\Documents")) {
    Write-Output "--- $parent ---"
    Get-ChildItem $parent -Directory -ErrorAction SilentlyContinue | ForEach-Object {
        $size = (Get-ChildItem $_.FullName -Recurse -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum / 1MB
        if ($size -gt 10) {
            Write-Output ("  {0,8:N0} MB  {1}" -f $size, $_.Name)
        }
    }
}
