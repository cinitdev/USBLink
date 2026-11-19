$ErrorActionPreference = 'Stop'
$Cache = Join-Path (Split-Path $PSScriptRoot -Parent) 'build/test-libs'
New-Item -ItemType Directory -Force -Path $Cache | Out-Null
# Test-only dependencies, never copied into the module. Pin versions and hashes.
$Libraries = @(
  @{ Name='sqlite-jdbc-3.46.1.0.jar'; Url='https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.46.1.0/sqlite-jdbc-3.46.1.0.jar'; Sha='6DC7464E3803648D3FF18A7359BAB6ADF079FCD8495B18991F6F5EDCB8AC6E3B' },
  @{ Name='slf4j-api-1.7.36.jar'; Url='https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar'; Sha='D3EF575E3E4979678DC01BF1DCCE51021493B4D11FB7F1BE8AD982877C16A1C0' }
)
$Paths = foreach ($Library in $Libraries) {
  $Target = Join-Path $Cache $Library.Name
  if (-not (Test-Path -LiteralPath $Target)) { Invoke-WebRequest -Uri $Library.Url -OutFile $Target }
  if ((Get-FileHash -LiteralPath $Target -Algorithm SHA256).Hash -ne $Library.Sha) { throw 'History test dependency checksum mismatch.' }
  $Target
}
$Paths -join [IO.Path]::PathSeparator
