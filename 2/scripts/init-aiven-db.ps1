[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9.-]+$')]
    [string]$DbHost,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 65535)]
    [int]$DbPort,

    [ValidatePattern('^[A-Za-z0-9._-]+$')]
    [string]$DbUsername = 'avnadmin',

    [SecureString]$DbPassword
)

$ErrorActionPreference = 'Stop'
$schemaPath = (Resolve-Path (Join-Path $PSScriptRoot '..\src\main\resources\schema.sql')).Path

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker no está instalado o no está disponible en PATH.'
}

if ($null -eq $DbPassword) {
    $DbPassword = Read-Host 'Contraseña de MySQL en Aiven' -AsSecureString
}

$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($DbPassword)
try {
    $env:MYSQL_PWD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)

    & docker run --rm `
        --env MYSQL_PWD `
        --volume "${schemaPath}:/schema.sql:ro" `
        mysql:8.4 `
        mysql `
        --protocol=TCP `
        --connect-timeout=20 `
        --host=$DbHost `
        --port=$DbPort `
        --user=$DbUsername `
        --ssl-mode=REQUIRED `
        --default-character-set=utf8mb4 `
        --execute="SOURCE /schema.sql;"

    if ($LASTEXITCODE -ne 0) {
        throw "La ejecución de schema.sql falló con código $LASTEXITCODE."
    }

    & docker run --rm `
        --env MYSQL_PWD `
        mysql:8.4 `
        mysql `
        --protocol=TCP `
        --connect-timeout=20 `
        --host=$DbHost `
        --port=$DbPort `
        --user=$DbUsername `
        --ssl-mode=REQUIRED `
        --default-character-set=utf8mb4 `
        --database=crud_usuarios `
        --batch `
        --execute="SELECT id, email, role, status FROM users WHERE email = 'admin@example.com'; SHOW STATUS LIKE 'Ssl_cipher';"

    if ($LASTEXITCODE -ne 0) {
        throw "La verificación posterior a la carga falló con código $LASTEXITCODE."
    }

    Write-Host 'Esquema cargado y conexión TLS verificada correctamente.' -ForegroundColor Green
}
finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
}
