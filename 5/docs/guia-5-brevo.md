# Guía 5: notificaciones transaccionales con Brevo

Esta guía migra `users-management-microservice` de SMTP a la API REST
transaccional de Brevo. El resultado esperado es:

- `POST https://api.brevo.com/v3/smtp/email` mediante Spring `RestClient`.
- Envío en el executor `notificationExecutor` con `@Async`.
- Reintentos exponenciales para fallos transitorios.
- Circuit breaker para dejar de llamar a Brevo cuando el servicio está degradado.
- `IdempotencyKey` en el campo `headers` del JSON de Brevo.
- Respuesta HTTP de creación/actualización sin esperar el correo.
- Pruebas y JaCoCo con cobertura global de instrucciones superior al 90 %.
- Secretos fuera de Git, de la imagen Docker y de `render.yaml`.

> Todos los valores de esta guía son placeholders. No escriba una API key real
> en código, documentación, capturas, historial de shell o variables
> versionadas.

## 1. Elegir la estrategia Git

El repositorio upstream tiene una rama
`feature/brevo-rest-email-resilience`, pero parte de una versión anterior de
`main` y no contiene la base consolidada de la Guía 4. No conviene fusionarla
completa sobre la versión TiDB actual.

La estrategia aplicada en esta carpeta es:

1. Crear una rama nueva desde `main` del fork.
2. Portar la base cerrada de la Guía 4.
3. Implementar y probar Brevo sobre esa base.
4. Comparar de nuevo con upstream antes de fusionar hacia `main`.

Comandos en PowerShell desde la carpeta `5`:

```powershell
git remote -v
git remote add upstream https://github.com/arrietajohn/users-management-microservice.git
git fetch origin --prune
git fetch upstream --prune
```

Si `upstream` ya existe, el comando `git remote add` falla sin modificar nada.
En ese caso se corrige la URL con:

```powershell
git remote set-url upstream https://github.com/arrietajohn/users-management-microservice.git
```

Inspeccione diferencias sin cambiar la rama:

```powershell
git branch -a
git log --oneline --graph --decorate --all -30
git diff --stat main...upstream/feature/brevo-rest-email-resilience
git diff --name-status main...upstream/feature/brevo-rest-email-resilience
```

### Actualizar una rama local antes de publicarla

```powershell
git status --short
git switch feature/brevo-rest-email-resilience
git fetch origin --prune
git rebase origin/main
.\mvnw.cmd clean install -U
```

El rebase solo debe ejecutarse sobre una rama local que ninguna otra persona
esté usando. Si hay cambios sin confirmar, primero confírmelos o guárdelos:

```powershell
git stash push -u -m "trabajo-brevo-antes-de-rebase"
git rebase origin/main
git stash pop
```

Si el `stash pop` produce conflictos, resuélvalos y confirme. No ejecute
`git reset --hard` ni `git checkout -- .` para ocultar conflictos.

### Publicar sin sobrescribir la rama remota existente

La rama remota con el mismo nombre contiene una implementación anterior. Para
publicar esta nueva base sin fuerza, use otro nombre:

```powershell
git switch -c feature/brevo-rest-email-resilience-guide5
git push -u origin feature/brevo-rest-email-resilience-guide5
```

Si el equipo decide reemplazar deliberadamente la rama anterior, primero debe
respaldarla y revisar el diff. Solo entonces:

```powershell
git push --force-with-lease origin feature/brevo-rest-email-resilience
```

`--force-with-lease` es preferible a `--force`, pero sigue reescribiendo
historia: no lo use sobre `main`.

### Fusionar hacia `main` del fork

Después del build y de la revisión:

```powershell
git switch main
git fetch origin --prune
git merge --ff-only origin/main
git switch feature/brevo-rest-email-resilience-guide5
git rebase main
.\mvnw.cmd clean install -U
git switch main
git merge --no-ff feature/brevo-rest-email-resilience-guide5 -m "feat: integrar Brevo en user-management"
git push origin main
```

Si hay varios colaboradores, abra primero un pull request desde la rama de
feature hacia `main` y espere CI. Para comparar antes del PR:

```powershell
git fetch origin --prune
git diff --stat origin/main...HEAD
git log --oneline origin/main..HEAD
```

## 2. Arquitectura de la integración

El flujo es:

```text
UserRestController
  -> CreateUserService / UpdateUserService
  -> EmailNotificationService (@Async)
  -> EmailSenderPort
  -> BrevoEmailSenderAdapter
  -> RestClient -> Brevo API
```

Responsabilidades:

| Componente | Responsabilidad |
|---|---|
| `UserEmail` | normaliza y valida correos del dominio |
| `EmailDestinationModel` | destinatario, asunto y HTML |
| `EmailSenderPort` | contrato de salida independiente de Brevo |
| `EmailNotificationService` | plantillas, escape HTML y `@Async` |
| `BrevoConfig` | valida URL, API key, remitente y parámetros |
| `BrevoSpringConfig` | crea `RestClient` y configura `api-key` |
| `BrevoEmailRequest` | contrato JSON de Brevo |
| `BrevoEmailSenderAdapter` | POST, retry, circuit breaker e idempotencia |
| `AsyncNotificationConfig` | pool, cola y manejo de rechazos |

Los casos de uso inyectan `EmailNotificationService` después de persistir la
creación o actualización. La llamada pública es `void` y regresa al controlador
sin esperar la red.

Dependencias Maven:

```xml
<dependency>
  <groupId>io.github.resilience4j</groupId>
  <artifactId>resilience4j-retry</artifactId>
  <version>2.2.0</version>
</dependency>
<dependency>
  <groupId>io.github.resilience4j</groupId>
  <artifactId>resilience4j-circuitbreaker</artifactId>
  <version>2.2.0</version>
</dependency>
```

## 3. Contrato Brevo

Endpoint:

```text
POST https://api.brevo.com/v3/smtp/email
```

Cabeceras HTTP:

```text
api-key: xkeysib-...
Content-Type: application/json
Accept: application/json
```

Payload simplificado:

```json
{
  "sender": {
    "email": "remitente-verificado@example.com",
    "name": "Gestion de Usuarios"
  },
  "to": [
    {
      "email": "usuario@example.com",
      "name": "Usuario de prueba"
    }
  ],
  "subject": "Tu cuenta ha sido creada",
  "htmlContent": "<html>...</html>",
  "headers": {
    "IdempotencyKey": "valor-uuid"
  }
}
```

`IdempotencyKey` pertenece al objeto `headers` del mensaje. Se genera una vez por
envío lógico y el mismo valor se reutiliza en todos los reintentos.

Política de resiliencia:

| Fallo | Reintento |
|---|---|
| timeout / error de red | sí |
| HTTP 408 | sí |
| HTTP 429 | sí |
| HTTP 5xx | sí |
| HTTP 400/401/403/404 | no |
| circuito abierto | no se llama a Brevo |

Los valores predeterminados son tres intentos, backoff exponencial desde 500 ms,
ventana de circuito de 10 llamadas y apertura de 30 s. Todos son configurables
por variables de entorno.

## 4. Secretos locales

### Ejecución directa del JAR

```powershell
Copy-Item application-secrets.properties.example .environments\application-secrets.properties
notepad .environments\application-secrets.properties
```

Contenido esperado, siempre con placeholders hasta reemplazarlos localmente:

```properties
db.host=localhost
db.port=3306
db.name=crud_usuarios
db.username=users_app
db.password=REPLACE_WITH_LOCAL_DB_PASSWORD

jwt.secret=REPLACE_WITH_RANDOM_BASE64_SECRET

brevo.api-key=xkeysib-REPLACE_WITH_BREVO_API_KEY
brevo.from.address=remitente-verificado@example.com
brevo.from.name=Gestion de Usuarios
```

`application.properties` importa ese archivo de forma opcional. En Render no se
incluye y los valores llegan únicamente como variables de entorno.

### Docker Compose

```powershell
Copy-Item compose.env.example .env
notepad .env
docker compose config --quiet
docker compose up --build
```

Valide las exclusiones:

```powershell
git check-ignore -v .env
git check-ignore -v .environments\application-secrets.properties
git ls-files | Select-String -Pattern '(^|/)\.env$|\.environments'
```

Los dos primeros comandos deben mostrar una regla de `.gitignore`; el último no
debe mostrar el `.env` real ni `.environments/application-secrets.properties`.
También están excluidos en `.dockerignore` para que no entren en la imagen.

## 5. Configuración de propiedades

| Propiedad Spring | Variable | Predeterminado |
|---|---|---|
| `brevo.base-url` | `BREVO_BASE_URL` | `https://api.brevo.com` |
| `brevo.api-key` | `BREVO_API_KEY` | sin valor |
| `brevo.from.address` | `BREVO_FROM_ADDRESS` | sin valor |
| `brevo.from.name` | `BREVO_FROM_NAME` | `Gestion de Usuarios` |
| `brevo.timeout.connect` | `BREVO_CONNECT_TIMEOUT` | `2s` |
| `brevo.timeout.read` | `BREVO_READ_TIMEOUT` | `5s` |
| `brevo.retry.max-attempts` | `BREVO_RETRY_MAX_ATTEMPTS` | `3` |
| `brevo.retry.initial-delay` | `BREVO_RETRY_INITIAL_DELAY` | `500ms` |
| `brevo.retry.multiplier` | `BREVO_RETRY_MULTIPLIER` | `2.0` |
| `brevo.circuit-breaker.failure-rate-threshold` | `BREVO_CIRCUIT_FAILURE_THRESHOLD` | `50` |
| `brevo.circuit-breaker.sliding-window-size` | `BREVO_CIRCUIT_WINDOW_SIZE` | `10` |
| `brevo.circuit-breaker.minimum-calls` | `BREVO_CIRCUIT_MINIMUM_CALLS` | `5` |
| `brevo.circuit-breaker.open-duration` | `BREVO_CIRCUIT_OPEN_DURATION` | `30s` |

No cambie el prefijo esperado `xkeysib-`. Una clave `xsmtpsib-` corresponde a
SMTP y será rechazada al iniciar la configuración.

## 6. Pruebas y JaCoCo

Windows:

```powershell
.\mvnw.cmd clean install -U
```

Linux/macOS:

```bash
./mvnw clean install -U
```

Verificaciones esperadas:

- 204 pruebas, cero fallos.
- `BUILD SUCCESS`.
- `JaCoCo ... All coverage checks have been met`.
- Reporte en `target/site/jacoco/index.html`.

El umbral global es 90 % de instrucciones y 80 % de ramas. La CLI de escritorio
se excluye porque el README la declara adaptador inactivo; la API REST y la
integración Brevo sí se miden.

Ejecutar solo las pruebas de Brevo:

```powershell
.\mvnw.cmd -Dtest=BrevoConfigTest,BrevoEmailSenderAdapterTest,BrevoSpringConfigTest,EmailNotificationServiceTest test
```

Las pruebas usan `MockRestServiceServer`; nunca consumen la cuota real de Brevo
ni necesitan una API key verdadera.

## 7. Configurar Brevo

1. Abra Brevo y cree una API key en **SMTP & API > API Keys**.
2. Elija el tipo de API key REST/transaccional; debe empezar por `xkeysib-`.
3. Verifique el dominio o remitente en **Senders & Domains**.
4. Use como `BREVO_FROM_ADDRESS` exactamente el remitente verificado.
5. No use la cuenta Gmail ni una contraseña de aplicación SMTP.

Si Brevo muestra una clave `xsmtpsib-`, esa clave no sirve para este adaptador.

## 8. Variables en Render

En Render abra el servicio y entre a **Environment**. Complete:

| Variable | Acción |
|---|---|
| `BREVO_API_KEY` | valor secreto `xkeysib-...`; marcar como secreto |
| `BREVO_FROM_ADDRESS` | remitente verificado, por ejemplo `notificaciones@example.com` |
| `BREVO_FROM_NAME` | `Gestion de Usuarios` |

Conserve además las variables de TiDB de la Guía 4:

```text
DB_HOST
DB_PORT=4000
DB_NAME=crud_usuarios
DB_USERNAME
DB_PASSWORD
DB_SSL_MODE=VERIFY_IDENTITY
JWT_SECRET
JWT_EXPIRATION_SECONDS=900
```

`render.yaml` declara `BREVO_API_KEY` y `BREVO_FROM_ADDRESS` con `sync: false`
para que Render solicite los valores y no los guarde en el repositorio. Después
de guardar las variables, elija **Save, rebuild, and deploy**.

No configure simultáneamente `SMTP_USERNAME`, `SMTP_PASSWORD` y
`SMTP_FROM_ADDRESS`; ya no son necesarias.

## 9. Validar el despliegue

### Health y Swagger

```powershell
$baseUrl = "https://<SERVICIO>.onrender.com"
Invoke-RestMethod "$baseUrl/health"
Start-Process "$baseUrl/swagger-ui/index.html"
```

En Swagger:

1. Ejecute `POST /api/auth/login` y copie `accessToken`.
2. Pulse **Authorize** y use el token Bearer.
3. Ejecute `POST /api/users` con un correo de prueba real.
4. Compruebe que la respuesta sea `201 Created`.
5. Ejecute `PUT /api/users/{id}`.
6. Compruebe que la respuesta HTTP llegue aunque Brevo tarde.
7. Revise la bandeja del destinatario y el panel **Transactional > Logs**.

Payload de ejemplo:

```json
{
  "id": "11111111-1111-1111-1111-111111111111",
  "name": "Prueba Brevo",
  "email": "destinatario-de-prueba@example.com",
  "password": "PruebaSegura123!",
  "role": "MEMBER"
}
```

PowerShell con medición del tiempo:

```powershell
$baseUrl = "https://<SERVICIO>.onrender.com"
$loginBody = @{
  email = "admin@example.com"
  password = "<CONTRASENA_ADMIN_ACTUAL>"
} | ConvertTo-Json

$login = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/auth/login" `
  -ContentType "application/json" -Body $loginBody
$headers = @{ Authorization = "Bearer $($login.accessToken)" }

$body = @{
  id = [guid]::NewGuid().ToString()
  name = "Prueba Brevo"
  email = "destinatario-de-prueba@example.com"
  password = "PruebaSegura123!"
  role = "MEMBER"
} | ConvertTo-Json

$elapsed = Measure-Command {
  $created = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/users" `
    -Headers $headers -ContentType "application/json" -Body $body
}

"HTTP create completado en {0:N2} s; id={1}" -f $elapsed.TotalSeconds, $created.id
```

El tiempo mide la respuesta HTTP, no la entrega del correo. Confirme el correo
en Brevo y en la bandeja del destinatario.

Para actualizar:

```powershell
$updateBody = @{
  name = "Prueba Brevo Actualizada"
  email = "destinatario-de-prueba@example.com"
  password = "PruebaSegura123!"
  role = "MEMBER"
  status = "ACTIVE"
} | ConvertTo-Json

$updated = Invoke-RestMethod -Method Put -Uri "$baseUrl/api/users/$($created.id)" `
  -Headers $headers -ContentType "application/json" -Body $updateBody
```

En Postman:

- `POST <baseUrl>/api/auth/login`
- `POST <baseUrl>/api/users`
- `PUT <baseUrl>/api/users/:id`
- Header común: `Authorization: Bearer <accessToken>`
- Content-Type: `application/json`

No configure un timeout artificialmente corto en el cliente HTTP para intentar
“probar” la asincronía. La prueba correcta es que la creación persiste y responde
antes de que Brevo termine; el correo se verifica después en logs y bandeja.

## 10. Logs esperados

```text
[BrevoSpringConfig] configuracion cargada. apiKeyFingerprint=...
[BrevoEmailSenderAdapter] reintento 1 por ServiceUnavailable.
[BrevoEmailSenderAdapter] correo aceptado por Brevo para usuario@example.com.
```

Nunca debe aparecer la API key completa. Solo se registra un fingerprint parcial.

## 11. Diagnóstico

### HTTP 401 o 403 desde Brevo

- Confirme que `BREVO_API_KEY` empieza por `xkeysib-`.
- Compruebe que la API key está activa y tiene permisos transaccionales.
- Elimine espacios o saltos de línea al copiarla en Render.
- Vuelva a desplegar después de cambiar la variable.

### HTTP 400 `sender` o remitente inválido

- Verifique el remitente exacto en Brevo.
- No use un alias no verificado.
- `BREVO_FROM_ADDRESS` debe ser solo el correo, sin nombre ni `<...>`.

### HTTP 429

Indica límite de tasa. El adaptador reintenta con backoff. Si persiste, revise el
plan y la cuota de Brevo; no aumente indefinidamente los intentos.

### Circuit breaker abierto

El circuito se abre por fallos transitorios repetidos. Espere
`BREVO_CIRCUIT_OPEN_DURATION` antes de reintentar y revise los logs de Brevo.
Una respuesta HTTP de usuario no debe convertirse en HTTP 500 por un fallo de
correo asíncrono.

### No llega el correo, pero la API responde 201

1. Busque `correo aceptado por Brevo` en Render Logs.
2. Consulte **Transactional > Logs** y eventos de entrega en Brevo.
3. Revise spam, rebotes y bloqueos del destinatario.
4. Confirme que el servicio Render está ejecutando la revisión nueva.

### El envío bloquea la respuesta

- Confirme que `EmailNotificationService` tiene `@Async("notificationExecutor")`.
- No llame directamente al adaptador desde el controlador.
- No convierta el método `void` en uno que espere un `Future`.
- Confirme que Spring creó el proxy del bean; no use `new EmailNotificationService(...)`
  en producción.

### El contenedor no arranca por configuración Brevo

Revise que existan `BREVO_API_KEY` y `BREVO_FROM_ADDRESS`. Se recomienda fallar
al iniciar cuando faltan credenciales: es preferible a descubrir el error al
crear el primer usuario.

### `Illegal base64 character` al arrancar

`JWT_SECRET` debe ser Base64 de al menos 32 bytes. Un placeholder como
`REPLACE_WITH_RANDOM_BASE64_SECRET` no es Base64 válido y bloquea el arranque.
Genere un valor aleatorio antes de iniciar la aplicación:

```powershell
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$bytes = New-Object byte[] 64
$rng.GetBytes($bytes)
$env:JWT_SECRET = [Convert]::ToBase64String($bytes)
$rng.Dispose()
Remove-Variable rng, bytes
```

No reutilice este valor de ejemplo como secreto de producción.

## 12. Checklist final

- [ ] `origin` apunta a mi fork y `upstream` al repositorio original.
- [ ] La rama de feature está rebasada sobre `main`.
- [ ] `pom.xml` contiene retry y circuitbreaker de Resilience4j.
- [ ] Ya no existen adaptadores SMTP activos en producción.
- [ ] Las plantillas creada/actualizada existen en `src/main/resources/templates`.
- [ ] Crear y actualizar usuarios invocan el servicio asíncrono.
- [ ] `.env` y `.environments` están ignorados por Git y Docker.
- [ ] `mvnw clean install -U` termina en `BUILD SUCCESS`.
- [ ] JaCoCo supera 90 % de instrucciones y 80 % de ramas.
- [ ] Render tiene `BREVO_API_KEY`, `BREVO_FROM_ADDRESS` y `BREVO_FROM_NAME`.
- [ ] Swagger y Postman muestran creación/actualización exitosas.
- [ ] Llegan ambos correos reales y la respuesta HTTP no espera el envío.
