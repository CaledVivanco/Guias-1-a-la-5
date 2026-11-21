# Guía 4: migrar de Aiven a TiDB Cloud Starter

Esta guía migra `users-management-microservice` desde MySQL en Aiven hacia una
base compatible con MySQL en TiDB Cloud Starter. El resultado esperado es:

- Java 17 + Spring Boot 3.3.5 + MySQL Connector/J 9.3.0.
- Esquema `crud_usuarios` y tabla `users` creados en TiDB.
- Conexión cifrada con validación de certificado y hostname:
  `DB_SSL_MODE=VERIFY_IDENTITY`.
- Desarrollo local en `http://localhost:8080`.
- Producción en Render con `/health` en estado correcto.
- Login JWT, listado y actualización del administrador verificados.
- Contraseña inicial `Admin1234!` reemplazada antes de terminar.

> Las credenciales de este documento son placeholders. El archivo `.env` está
> ignorado por Git; el archivo versionado `.env.example` no contiene secretos.

## 0. Antes de empezar

Ten disponible:

1. Acceso a la consola de TiDB Cloud y al clúster Starter.
2. Acceso a Render con permiso para editar variables y desplegar.
3. JDK 17 y Docker Desktop para ejecutar las comprobaciones locales.
4. La URL pública del servicio Render. No la inventes; cópiala de
   **Settings > URL** o de la parte superior del servicio.

No reutilices la contraseña, usuario, host o puerto de Aiven. Los datos de TiDB
pertenecen a otro proveedor y a otro clúster.

## 1. Extraer los datos exactos del clúster

1. Inicia sesión en TiDB Cloud y abre **My TiDB**.
2. Entra al clúster **Starter** de este proyecto.
3. Pulsa **Connect**.
4. Mantén **Connection Type = Public** para Render y para tu equipo.
5. Mantén la rama **main**, salvo que la asignación indique otra rama.
6. Elige el método **MySQL CLI** o **General**. El panel mostrará el host, el
   puerto, el usuario y una cadena de conexión de referencia.
7. Si todavía no existe una contraseña, pulsa **Generate Password** y guárdala
   inmediatamente en tu gestor de secretos. TiDB no la vuelve a mostrar.
8. Revisa **Settings > Networking**. La conexión pública debe estar habilitada y
   la IP de origen debe estar permitida.

Registra estos valores:

| Parámetro | Variable del proyecto | Valor o formato |
|---|---|---|
| Host | `DB_HOST` | `gateway01.<region>.prod.aws.tidbcloud.com` |
| Puerto | `DB_PORT` | `4000` |
| Usuario | `DB_USERNAME` | `<PREFIJO_DEL_CLUSTER>.root` |
| Contraseña | `DB_PASSWORD` | valor secreto generado una sola vez |
| Base de datos | `DB_NAME` | `crud_usuarios` |
| TLS | `DB_SSL_MODE` | `VERIFY_IDENTITY` |

Detalles que suelen causar errores:

- El usuario debe incluir el prefijo del clúster. No uses solo `root`.
- No guardes `https://` dentro de `DB_HOST`; debe ser solo el hostname.
- El puerto de TiDB es `4000`, no `3306` y no el puerto de Aiven.
- Si perdiste la contraseña, usa **Reset Password** en el panel. La contraseña
  anterior dejará de funcionar en todos los clientes.
- Render Free usa direcciones de salida dinámicas. Si TiDB exige una IP fija y
  no tienes un proxy con IP estática, el host público debe permitir el tráfico
  de Render. Para un laboratorio puedes usar `0.0.0.0/0`; entiende que la
  protección principal pasa a ser una contraseña larga más TLS. Si no aceptas
  ese riesgo, usa un proxy/egress con IP estática.

La conexión pública de TiDB Starter usa certificados emitidos por Let's Encrypt.
Java 17 normalmente consulta el repositorio de CAs del sistema y puede validarlos
sin descargar un PEM adicional. No desactives `VERIFY_IDENTITY`.

## 2. Crear `crud_usuarios` y cargar el esquema

### Opción recomendada: SQL Editor de TiDB

1. En TiDB Cloud abre **SQL Editor**.
2. Selecciona el clúster y la rama `main`.
3. Conéctate al editor.
4. Abre el archivo local
   `src/main/resources/schema.sql`.
5. Copia su contenido completo y pégalo en el editor.
6. Ejecuta el script.
7. Si el editor no acepta varias sentencias a la vez, ejecuta por bloques:
   primero `CREATE DATABASE`, luego `USE`, luego `CREATE TABLE` y finalmente
   `INSERT IGNORE`.

El script crea:

- Base `crud_usuarios` con `utf8mb4`.
- Tabla `users`.
- Administrador `admin@example.com`, rol `ADMIN`, estado `ACTIVE`.

La sentencia `INSERT IGNORE` permite volver a ejecutar el script sin duplicar el
usuario. Si ya cambiaste la contraseña, volver a ejecutarlo tampoco la
sobrescribe.

### Opción PowerShell con Docker

Desde la raíz de la carpeta `4`:

```powershell
.\scripts\init-tidb-db.ps1 `
  -DbHost "<DB_HOST>" `
  -DbPort 4000 `
  -DbUsername "<PREFIJO_DEL_CLUSTER>.root"
```

El script solicita la contraseña sin mostrarla, ejecuta `schema.sql` con
`--ssl-mode=VERIFY_IDENTITY` y comprueba el administrador, el cifrado y la
versión TLS.

### Opción cliente `mysql`

```powershell
$env:MYSQL_PWD = Read-Host 'Contraseña de TiDB' -AsSecureString |
  ForEach-Object {
    [Network.Credential]::new('tidb', $_).GetNetworkCredential().Password
  }

$schemaPath = (Resolve-Path .\src\main\resources\schema.sql).Path.Replace('\', '/')

mysql `
  --host="<DB_HOST>" `
  --port=4000 `
  --user="<PREFIJO_DEL_CLUSTER>.root" `
  --ssl-mode=VERIFY_IDENTITY `
  --default-character-set=utf8mb4 `
  --execute="SOURCE `"$schemaPath`";"

Remove-Item Env:MYSQL_PWD
```

### IntelliJ IDEA

1. Abre **View > Tool Windows > Database**.
2. Pulsa **+ > Data Source > MySQL**.
3. Host: valor de `DB_HOST`.
4. Port: `4000`.
5. User: `<PREFIJO_DEL_CLUSTER>.root`.
6. Password: valor secreto de TiDB.
7. En **Advanced**, define `sslMode=VERIFY_IDENTITY`.
8. En **Test Connection**, selecciona MySQL Connector/J 9.x o descarga el driver
   recomendado por IntelliJ.
9. Abre una consola y ejecuta `schema.sql`.
10. Actualiza el árbol y confirma que existe
    `crud_usuarios > users`.

Verificación en TiDB o IntelliJ:

```sql
USE crud_usuarios;

SELECT id, email, role, status, created_at
FROM users
WHERE email = 'admin@example.com';

SHOW STATUS LIKE 'Ssl_cipher';
SHOW STATUS LIKE 'Ssl_version';
```

`Ssl_cipher` debe ser no vacío. El correo debe devolver el UUID
`00000000-0000-0000-0000-000000000001`.

## 3. Configuración local segura

Desde la raíz de la carpeta `4`:

```powershell
Copy-Item .env.example .env
notepad .env
```

Completa `.env` con:

```properties
DB_HOST=<DB_HOST>
DB_PORT=4000
DB_NAME=crud_usuarios
DB_USERNAME=<PREFIJO_DEL_CLUSTER>.root
DB_PASSWORD=<CONTRASEÑA_GENERADA_EN_TIDB>
DB_SSL_MODE=VERIFY_IDENTITY
PORT=8080
```

No pongas la contraseña en `application.properties`, `render.yaml`, README ni
en una captura. El archivo `application.properties` solo contiene referencias a
variables:

```properties
db.host=${DB_HOST:}
db.port=${DB_PORT:4000}
db.name=${DB_NAME:crud_usuarios}
db.username=${DB_USERNAME:}
db.password=${DB_PASSWORD:}
db.ssl-mode=${DB_SSL_MODE:VERIFY_IDENTITY}
```

Comprueba que `.env` no está versionado:

```powershell
git check-ignore -v .env
git ls-files | Select-String -Pattern '(^|/)\.env$'
```

El primer comando debe mostrar la regla `.env` de `.gitignore`; el segundo no
debe devolver resultados.

Compila con Java 17:

```powershell
java -version
.\mvnw.cmd -version
.\mvnw.cmd clean test
.\mvnw.cmd clean package
```

Inicia la aplicación:

```powershell
java -jar target\users-management-2.2.0.jar
```

Los logs correctos deben incluir:

```text
[DataSourceSpringConfig] DataSource inicializado. host=<DB_HOST> port=4000 sslMode=VERIFY_IDENTITY
HikariPool-1 - Starting...
HikariPool-1 - Start completed.
Tomcat started on port 8080 (http)
Started Main
```

Prueba básica:

```powershell
Invoke-RestMethod http://localhost:8080/health
Invoke-WebRequest http://localhost:8080/swagger-ui/index.html
```

`/health` es público, pero informa el estado de la API y no consulta TiDB. La
prueba real de la base es un login exitoso.

## 4. Actualizar Render

En Render abre el servicio, entra a **Environment > Edit** y deja estos valores:

| Variable | Valor |
|---|---|
| `DB_HOST` | host exacto de TiDB, sin esquema ni puerto |
| `DB_PORT` | `4000` |
| `DB_NAME` | `crud_usuarios` |
| `DB_USERNAME` | `<PREFIJO_DEL_CLUSTER>.root` |
| `DB_PASSWORD` | contraseña actual de TiDB |
| `DB_SSL_MODE` | `VERIFY_IDENTITY` |

Conserva las variables de SMTP y `JWT_SECRET`. Si cambias `JWT_SECRET`, todos los
tokens emitidos anteriormente dejan de ser válidos, lo cual puede ser deseable
como medida de seguridad.

Después pulsa **Save, rebuild, and deploy**. El archivo `render.yaml` ya declara
el puerto `4000`, el esquema y el modo TLS. Si el servicio está enlazado a un
Blueprint, no crees un segundo servicio: actualiza el servicio existente y
sincroniza los cambios del repositorio cuando corresponda.

La URL de Render es similar a:

```text
https://<NOMBRE_DEL_SERVICIO>.onrender.com
```

El valor exacto se consulta en el panel. El plan Free puede tardar en responder
tras un periodo inactivo.

## 5. Validar los logs y el flujo en producción

En Render abre **Logs** y busca:

```text
DataSource inicializado
HikariPool-1 - Start completed
Tomcat started on port 8080
Started Main
```

No debe aparecer `localhost`, `3306`, `avnadmin`, `REQUIRED` ni el hostname de
Aiven al iniciar esta versión.

Comprueba:

1. `GET /health` responde HTTP 200.
2. Swagger abre en `/swagger-ui/index.html`.
3. El login inicial devuelve un JWT.
4. El listado contiene al administrador.
5. La actualización usa una contraseña nueva.
6. El login con la contraseña anterior devuelve HTTP 401.
7. El login con la contraseña nueva devuelve HTTP 200.

### PowerShell

```powershell
$baseUrl = "<URL_DE_RENDER>"

$loginBody = @{
  email = 'admin@example.com'
  password = 'Admin1234!'
} | ConvertTo-Json

$login = Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/api/auth/login" `
  -ContentType 'application/json' `
  -Body $loginBody

$headers = @{ Authorization = "Bearer $($login.accessToken)" }
$users = Invoke-RestMethod `
  -Method Get `
  -Uri "$baseUrl/api/users" `
  -Headers $headers

$admin = $users | Where-Object { $_.email -eq 'admin@example.com' }
if (-not $admin) { throw 'No se encontró el administrador en TiDB.' }

$securePassword = Read-Host 'Nueva contraseña del administrador' -AsSecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
  $newPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
  $updateBody = @{
    name = $admin.name
    email = $admin.email
    password = $newPassword
    role = 'ADMIN'
    status = 'ACTIVE'
  } | ConvertTo-Json

  $updated = Invoke-RestMethod `
    -Method Put `
    -Uri "$baseUrl/api/users/$($admin.id)" `
    -Headers $headers `
    -ContentType 'application/json' `
    -Body $updateBody
  # Debe producir HTTP 401.
  try {
    Invoke-RestMethod -Method Post -Uri "$baseUrl/api/auth/login" `
      -ContentType 'application/json' -Body $loginBody
    throw 'La contraseña anterior todavía funciona.'
  }
  catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 401) { throw }
  }

  $newLoginBody = @{
    email = 'admin@example.com'
    password = $newPassword
  } | ConvertTo-Json

  Invoke-RestMethod -Method Post -Uri "$baseUrl/api/auth/login" `
    -ContentType 'application/json' -Body $newLoginBody
}
finally {
  [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
  Remove-Variable newPassword -ErrorAction SilentlyContinue
}
```

La misma variable introducida en el prompt se usa para actualizar y comprobar el
nuevo login. No guardes la contraseña en el historial del script.

### Boomerang o Postman

1. Crea `POST <URL>/api/auth/login` con `Content-Type: application/json`:

```json
{
  "email": "admin@example.com",
  "password": "Admin1234!"
}
```

2. Copia `accessToken` de la respuesta.
3. Crea `GET <URL>/api/users` con:

```text
Authorization: Bearer <accessToken>
```

4. Localiza el administrador y ejecuta
   `PUT <URL>/api/users/<ID_DEL_ADMIN>`:

```json
{
  "name": "Administrador",
  "email": "admin@example.com",
  "password": "<NUEVA_CONTRASEÑA_FUERTE>",
  "role": "ADMIN",
  "status": "ACTIVE"
}
```

5. Repite el login con la contraseña anterior. Debe fallar con HTTP 401.
6. Repite el login con la contraseña nueva. Debe devolver HTTP 200 y un JWT.

Rota la contraseña inicial incluso si este es un proyecto académico. No uses la
misma contraseña para TiDB, Gmail, Render o el administrador de la aplicación.

## 6. Diagnóstico de TLS, JDBC y conectividad

### El log muestra 3306, localhost o avnadmin

El proceso no recibió las variables de TiDB o está ejecutando un JAR/revisión
anteriores. Cierra la aplicación, revisa `.env`, exporta las variables de nuevo,
recompila y arranca el JAR actual:

```powershell
Get-Content .env
.\mvnw.cmd clean package
java -jar target\users-management-2.2.0.jar
```

No imprimas la contraseña al revisar la configuración.

### Timeout, `Communications link failure` o conexión rechazada

```powershell
Resolve-DnsName "<DB_HOST>"
Test-NetConnection -ComputerName "<DB_HOST>" -Port 4000
```

Comprueba que el hostname se copió completo, que el puerto es `4000`, que el
clúster está disponible y que la IP local está permitida en
**Settings > Networking**. En Render, si no hay una IP de salida estática, el
endpoint debe aceptar la salida dinámica de Render.

### `Access denied for user`

- Usa exactamente el usuario con prefijo del panel, por ejemplo
  `<PREFIJO>.root`.
- No uses `root` sin prefijo.
- Si se regeneró la contraseña, actualiza `.env` y Render al mismo tiempo.
- Busca espacios o comillas accidentales al final del valor.
- No uses la contraseña de administrador de la tabla `users` como
  `DB_PASSWORD`; esta última es la contraseña del clúster.

### `Unknown database 'crud_usuarios'`

El esquema todavía no se ejecutó o se ejecutó en otra rama/clúster. Vuelve al
SQL Editor, selecciona el mismo recurso y rama que usa la aplicación y carga
`schema.sql`.

### `PKIX path building failed` o `certificate_unknown`

`VERIFY_IDENTITY` exige una cadena de confianza válida y que el nombre del
certificado coincida con `DB_HOST`.

1. Confirma que usas Temurin u otro JDK 17 actualizado.
2. Revisa que la fecha y hora del equipo sean correctas.
3. Comprueba el certificado presentado:

```powershell
keytool -printcert -sslserver "<DB_HOST>:4000"
```

4. Confirma que `DB_HOST` es el hostname exacto de **Connect**, no una IP ni una
   URL con `https://`.
5. TiDB Starter usa Let's Encrypt; Java consulta el truststore del sistema. Si
   tu JRE es antiguo o tiene un truststore incompleto, importa la CA raíz
   asignada en un truststore propio o actualiza el runtime.
6. No cambies a `DISABLED`. Como diagnóstico temporal se puede usar
   `REQUIRED` para aislar si el fallo es de confianza del certificado, pero la
   entrega final y Render deben quedar con `VERIFY_IDENTITY`.

### `SSLHandshakeException` o protocolo TLS

- Verifica que el destino sea MySQL/TiDB en el puerto `4000`.
- No conectes al endpoint HTTPS del panel ni a un proxy HTTP.
- Actualiza Java 17 y MySQL Connector/J; el proyecto usa Connector/J `9.3.0`.
- Si aparece un error de hostname, el hostname de la URL JDBC no coincide con el
  CN/SAN del servidor. Vuelve a copiar el host desde **Connect**.
- Un firewall que intercepte TLS puede romper la cadena de certificados. Prueba
  desde otra red y revisa las políticas del equipo.

### Driver no encontrado o error de autenticación

La dependencia esperada es:

```xml
<groupId>com.mysql</groupId>
<artifactId>mysql-connector-j</artifactId>
```

La URL generada por el proyecto debe tener esta forma:

```text
jdbc:mysql://<DB_HOST>:4000/crud_usuarios?sslMode=VERIFY_IDENTITY&serverTimezone=UTC&allowPublicKeyRetrieval=true
```

Si aparece `Public Key Retrieval is not allowed`, confirma que estás ejecutando
la compilación actual, que conserva `allowPublicKeyRetrieval=true`. No uses el
driver antiguo `mysql:mysql-connector-java`.

## 7. Checklist final

- [ ] `DB_HOST`, `DB_USERNAME` y `DB_PASSWORD` provienen del mismo clúster TiDB.
- [ ] El usuario incluye el prefijo y termina en `.root`.
- [ ] El puerto es `4000`.
- [ ] `crud_usuarios.users` existe en la rama `main`.
- [ ] `admin@example.com` existe con rol `ADMIN` y estado `ACTIVE`.
- [ ] `.env` está ignorado y no aparece en `git diff`.
- [ ] Local usa `VERIFY_IDENTITY` y Hikari completa el arranque.
- [ ] Render tiene las seis variables correctas y terminó el deploy.
- [ ] `/health` y `/swagger-ui/index.html` responden.
- [ ] Login, listado y actualización funcionan contra TiDB.
- [ ] La contraseña inicial fue reemplazada y ya devuelve HTTP 401.

## Referencias oficiales

- [Conectar TiDB Cloud Starter mediante endpoint público](https://docs.pingcap.com/tidbcloud/connect-via-standard-connection-serverless/)
- [TLS para TiDB Cloud Starter y Essential](https://docs.pingcap.com/tidbcloud/secure-connections-to-serverless-clusters/)
- [Inicio rápido de TiDB Cloud Starter](https://docs.pingcap.com/tidbcloud/tidb-cloud-quickstart/)
