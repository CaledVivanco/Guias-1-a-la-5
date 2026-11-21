# Desarrollo local verificado

Esta guía describe el entorno usado para validar el microservicio en Windows con
PowerShell, Java 17, MySQL 8.4 y la API transaccional de Brevo.

## Requisitos

- JDK 17
- Git
- Docker Desktop con Docker Compose

El repositorio incluye el Maven Wrapper, por lo que no es necesario instalar
Maven globalmente.

## 1. Iniciar MySQL

Desde la raíz del repositorio:

```powershell
Copy-Item compose.env.example .env
notepad .env
docker compose up -d mysql
docker compose ps mysql
```

El contenedor ejecuta `src/main/resources/schema.sql` la primera vez que se crea
el volumen y queda disponible en `localhost:3306`. Reemplace los placeholders de
`.env`; Compose se detiene si faltan `DB_PASSWORD`, `MYSQL_ROOT_PASSWORD`,
`JWT_SECRET`, `BREVO_API_KEY` o `BREVO_FROM_ADDRESS`.

## 2. Compilar

```powershell
.\mvnw.cmd clean install -U
```

El build debe terminar en `BUILD SUCCESS`, con 204 pruebas y la validación de
JaCoCo. El ejecutable se genera como `target/users-management-2.2.0.jar`.

## 3. Configurar Brevo

Para ejecutar el JAR directamente, copie los secretos locales:

```powershell
Copy-Item application-secrets.properties.example .environments\application-secrets.properties
notepad .environments\application-secrets.properties
```

Use un remitente verificado en Brevo y una API key REST que empiece por
`xkeysib-`. Una clave SMTP (`xsmtpsib-`) no funciona en este endpoint.

Si usa `docker compose`, las mismas credenciales se leen desde `.env`. Spring
carga `.environments/application-secrets.properties` para ejecución directa;
Compose inyecta `.env` como variables de entorno.

## 4. Configurar el proceso

Ejecuta estos comandos en la misma ventana de PowerShell desde la raíz del
repositorio:

```powershell
$env:DB_HOST = "localhost"
$env:DB_PORT = "3306"
$env:DB_NAME = "crud_usuarios"
$env:DB_USERNAME = "users_app"
$env:DB_PASSWORD = "local_users_password"
$env:DB_SSL_MODE = "DISABLED"

$env:BREVO_API_KEY = "xkeysib-REPLACE_WITH_BREVO_API_KEY"
$env:BREVO_FROM_ADDRESS = "remitente-verificado@example.com"
$env:BREVO_FROM_NAME = "Gestion de Usuarios"

$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$jwtBytes = New-Object byte[] 64
$rng.GetBytes($jwtBytes)
$env:JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
$rng.Dispose()
Remove-Variable rng, jwtBytes
```

No copie la API key en capturas, commits, issues o mensajes. Para probar sin
emitir correos reales, deje el placeholder: la API arranca y el fallo del envío
aparece únicamente en el hilo asíncrono.

## 5. Iniciar la API

```powershell
java -jar target\users-management-2.2.0.jar
```

Comprobaciones:

```powershell
Invoke-RestMethod http://localhost:8080/health
Invoke-WebRequest http://localhost:8080/swagger-ui/index.html
```

Swagger UI está disponible en `http://localhost:8080/swagger-ui/index.html`.

## 6. Flujo de prueba

1. `POST /api/users` con los campos `id`, `name`, `email`, `password` y `role`.
2. `POST /api/auth/login` con `email` y `password`.
3. Enviar el token de `accessToken` como `Authorization: Bearer <token>`.
4. `GET /api/users`.
5. `PUT /api/users/{id}` con `name`, `email`, `password`, `role` y `status`.

La respuesta HTTP debe llegar sin esperar el envío. En los logs se espera:

```text
correo aceptado por Brevo para <DESTINATARIO>
```

El usuario administrador creado por el esquema usa el correo
`admin@example.com`; la contraseña inicial está documentada en
`src/main/resources/schema.sql`.

## Detener los servicios

```powershell
docker compose stop mysql
```

No se deben introducir contraseñas reales, tokens PAT, secretos JWT ni claves
privadas en archivos versionados.
