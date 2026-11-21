# Guía 2: MySQL remoto en Aiven con TLS obligatorio

Esta guía conecta `users-management-microservice` con MySQL en Aiven, crea el
esquema remoto y valida el flujo de autenticación JWT. Las credenciales reales
permanecen en `.env` o en variables de entorno y nunca se versionan.

## 1. Crear el servicio y extraer los datos de conexión

1. En [Aiven Console](https://console.aiven.io/) crea un servicio **MySQL**.
2. Elige la nube y una región cercana. Para este laboratorio sirve el plan de
   prueba o el plan `developer-1` disponible en la cuenta.
3. Espera hasta que el servicio esté en estado **Running**.
4. Abre **Overview > Connection information**. No uses los datos de una URL
   antigua de una guía: cada servicio tiene valores propios.

Registra en un lugar seguro:

| Dato | Variable del proyecto | Ejemplo de forma |
|---|---|---|
| Host | `DB_HOST` | `mysql-xxxxx-project.a.aivencloud.com` |
| Port | `DB_PORT` | `24590` |
| User | `DB_USERNAME` | `avnadmin` |
| Password | `DB_PASSWORD` | valor secreto generado por Aiven |
| Default database | `DB_NAME` | `crud_usuarios` después de crear el esquema |

Aiven exige cifrado TLS. En este proyecto se configura como
`DB_SSL_MODE=REQUIRED`. Si el panel ofrece el certificado CA, descárgalo para
usar `VERIFY_IDENTITY` en una configuración de producción; para esta guía,
`REQUIRED` cifra la conexión y hace fallar el arranque si TLS no se negocia.

## 2. Crear `.env` sin versionar secretos

Desde la raíz del repositorio:

```powershell
Copy-Item .env.example .env
notepad .env
```

Completa `DB_HOST`, `DB_PORT` y `DB_PASSWORD`. Mantén:

```properties
DB_NAME=crud_usuarios
DB_USERNAME=avnadmin
DB_SSL_MODE=REQUIRED
```

Genera un secreto JWT local real en lugar de conservar el marcador:

```powershell
$bytes = New-Object byte[] 64
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
$jwt = [Convert]::ToBase64String($bytes)
(Get-Content .env) -replace '^JWT_SECRET=.*$', "JWT_SECRET=$jwt" |
  Set-Content .env -Encoding utf8
Remove-Variable bytes, jwt
```

Verifica que Git ignora el archivo:

```powershell
git check-ignore -v .env
```

Debe mostrar la regla `.env` de `.gitignore`. El archivo `.env.example` sí se
versiona porque contiene únicamente marcadores.

## 3. Ejecutar `schema.sql` en Aiven

El script crea `crud_usuarios`, la tabla `users` y el administrador inicial. El
método más simple no requiere instalar el cliente `mysql`, porque usa el cliente
incluido en una imagen Docker:

```powershell
.\scripts\init-aiven-db.ps1 `
  -DbHost "<DB_HOST_DE_AIVEN>" `
  -DbPort <DB_PORT_DE_AIVEN> `
  -DbUsername avnadmin
```

El script solicita la contraseña sin mostrarla en pantalla. Si aparece
`Esquema cargado y conexión TLS verificada correctamente`, se crearon el
esquema y el administrador. `INSERT IGNORE` permite volver a ejecutar el script
sin duplicar el administrador.

### Alternativa con cliente `mysql`

```powershell
$env:MYSQL_PWD = Read-Host 'Contraseña de Aiven' -AsSecureString |
  ForEach-Object { [Network.Credential]::new('aiven', $_).GetNetworkCredential().Password }

mysql `
  --host="<DB_HOST_DE_AIVEN>" `
  --port=<DB_PORT_DE_AIVEN> `
  --user=avnadmin `
  --ssl-mode=REQUIRED `
  --execute="SOURCE C:/ruta/completa/users-management-microservice/src/main/resources/schema.sql"

Remove-Item Env:MYSQL_PWD
```

### IntelliJ IDEA

1. Abre **View > Tool Windows > Database**.
2. Pulsa **+ > Data Source > MySQL**.
3. Ingresa host, puerto, usuario y contraseña.
4. En **Advanced**, agrega `sslMode=REQUIRED`; alternativamente usa la URL
   `jdbc:mysql://HOST:PORT/defaultdb?sslMode=REQUIRED`.
5. Prueba la conexión y abre la consola.
6. Ejecuta el contenido de `src/main/resources/schema.sql`.
7. Actualiza el esquema y confirma que aparece `crud_usuarios.users`.

### MySQL Workbench

1. Crea una conexión con host, puerto y `avnadmin`.
2. En **SSL**, selecciona **Required** para TLS sin validación de CA.
3. Conéctate, abre `schema.sql` y usa **Execute**.
4. Confirma en el navegador de esquemas que existe
   `crud_usuarios > Tables > users`.

## 4. Compilar e iniciar con JDK 17

Comprueba el runtime:

```powershell
java -version
.\mvnw.cmd -version
```

Ambos deben informar Java 17. Después compila:

```powershell
.\mvnw.cmd clean package
```

Inicia la clase `com.jcaa.usersmanagement.Main` desde IntelliJ con el JDK 17 del
proyecto, o desde PowerShell:

```powershell
java -jar target\users-management-2.2.0.jar
```

Spring importa `.env` y crea el pool Hikari. En los logs deben aparecer líneas
equivalentes a:

```text
[DataSourceSpringConfig] DataSource inicializado. host=... port=... sslMode=REQUIRED
HikariPool-1 - Starting...
HikariPool-1 - Start completed.
Tomcat started on port 8080 (http)
Started Main
```

Si Hikari muestra `Start completed`, abrió al menos una conexión con la URL que
incluye `sslMode=REQUIRED`. La contraseña no aparece en estos logs.

Para buscar las líneas directamente:

```powershell
java -jar target\users-management-2.2.0.jar 2>&1 |
  Tee-Object -FilePath target\aiven-startup.log
```

## 5. Validar TLS y el administrador

Con el cliente MySQL o la consola de IntelliJ ejecuta:

```sql
SHOW STATUS LIKE 'Ssl_cipher';
SELECT id, email, role, status
FROM users
WHERE email = 'admin@example.com';
```

`Ssl_cipher` debe tener un valor no vacío y la consulta debe devolver
`admin@example.com` con rol `ADMIN` y estado `ACTIVE`.

## 6. Probar Swagger UI

Abre:

```text
http://localhost:8080/swagger-ui/index.html
```

Primero invoca `POST /api/auth/login`:

```json
{
  "email": "admin@example.com",
  "password": "Admin1234!"
}
```

Copia `accessToken` de la respuesta. Pulsa **Authorize**, pega únicamente el
token y confirma. Luego ejecuta `GET /api/users`; debe responder HTTP 200 con la
lista de usuarios.

## 7. Probar con PowerShell

```powershell
$body = @{
  email = 'admin@example.com'
  password = 'Admin1234!'
} | ConvertTo-Json

$login = Invoke-RestMethod `
  -Method Post `
  -Uri 'http://localhost:8080/api/auth/login' `
  -ContentType 'application/json' `
  -Body $body

$headers = @{ Authorization = "Bearer $($login.accessToken)" }
Invoke-RestMethod `
  -Method Get `
  -Uri 'http://localhost:8080/api/users' `
  -Headers $headers
```

En Postman o Boomerang usa `POST` con cuerpo **raw JSON**; después agrega la
cabecera `Authorization: Bearer <accessToken>` a `GET /api/users`.

## Problemas frecuentes

| Síntoma | Comprobación |
|---|---|
| `Access denied for user` | Host, puerto, usuario y contraseña deben copiarse del mismo servicio. Evita espacios, comillas accidentales y saltos de línea en `.env`. |
| `Communications link failure` o timeout | Confirma que Aiven muestra **Running**, que el puerto es el del panel y que la red permite TCP saliente. |
| `SSLConnectionSocketFactory`/`SSLHandshakeException` | Mantén `DB_SSL_MODE=REQUIRED`, usa MySQL Connector/J 9.3.0 y no desactives TLS. Revisa que sean host y puerto del servicio MySQL. |
| `Unknown database 'crud_usuarios'` | Ejecuta `schema.sql` antes de arrancar la aplicación. Para ejecutarlo, conecta inicialmente a la base `defaultdb`. |
| `Table 'crud_usuarios.users' doesn't exist` | La carga del script no terminó. Ejecuta de nuevo el script idempotente y revisa su código de salida. |
| `CREATE DATABASE command denied` | Ejecuta con `avnadmin` y comprueba permisos del servicio. Si la cuenta no permite crear bases, usa el usuario administrador mostrado por Aiven. |
| Puerto 8080 ocupado | `Get-NetTCPConnection -LocalPort 8080 -State Listen`; termina el proceso correcto o define `PORT=8081`. |

## 8. Revisar y publicar cambios seguros

Antes de confirmar:

```powershell
git status
git diff
git check-ignore -v .env
git ls-files | Select-String -Pattern '(^|/)\.env$'
```

El último comando no debe listar `.env`. Tampoco debe aparecer la contraseña de
Aiven en `git diff`. Después:

```powershell
git add .gitignore .env.example README.md docs/guia-2-aiven.md `
  scripts/init-aiven-db.ps1 `
  src/main/resources/application.properties `
  src/main/resources/schema.sql `
  src/main/java/com/jcaa/usersmanagement/infrastructure/config/DataSourceSpringConfig.java

git diff --cached
git commit -m "config: document and secure Aiven MySQL connection"
git push origin main
```

Nunca ejecutes `git add .` si `.env` contiene credenciales reales y antes no
confirmaste que está ignorado.
