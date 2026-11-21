# Desarrollo local verificado

Esta guía describe el entorno usado para validar el microservicio en Windows con
PowerShell, Java 17, MySQL 8.4 y un servidor SMTP local.

## Requisitos

- JDK 17
- Git
- Docker Desktop con Docker Compose

El repositorio incluye el Maven Wrapper, por lo que no es necesario instalar
Maven globalmente.

## 1. Iniciar MySQL

Desde la raíz del repositorio:

```powershell
docker compose up -d mysql
docker compose ps mysql
```

El contenedor ejecuta `src/main/resources/schema.sql` la primera vez que se crea
el volumen y queda disponible en `localhost:3306`. Las credenciales son valores
locales y desechables definidos en `compose.yaml`.

## 2. Compilar

```powershell
.\mvnw.cmd clean install -U
```

El build debe terminar en `BUILD SUCCESS`. El ejecutable se genera como
`target/users-management-2.2.0.jar`.

## 3. Iniciar un SMTP local

Mailpit permite probar los correos sin usar una cuenta real:

```powershell
docker run -d --name users-mailpit `
  -e MP_SMTP_AUTH_ACCEPT_ANY=1 `
  -e MP_SMTP_AUTH_ALLOW_INSECURE=1 `
  -p 1025:1025 `
  -p 8025:8025 `
  axllent/mailpit
```

La interfaz de Mailpit queda en `http://localhost:8025`.

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

$env:SMTP_HOST = "127.0.0.1"
$env:SMTP_PORT = "1025"
$env:SMTP_USERNAME = "local@example.test"
$env:SMTP_PASSWORD = "local-test"
$env:SMTP_FROM_ADDRESS = "local@example.test"
$env:SMTP_FROM_NAME = "Gestion de Usuarios"

$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$jwtBytes = New-Object byte[] 64
$rng.GetBytes($jwtBytes)
$env:JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
$rng.Dispose()
Remove-Variable rng, jwtBytes
```

Las credenciales anteriores son exclusivamente para el entorno Docker local.
Para Gmail se debe usar una contraseña de aplicación y no una contraseña normal
de la cuenta.

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

El usuario administrador creado por el esquema usa el correo
`admin@example.com`; la contraseña inicial está documentada en
`src/main/resources/schema.sql`.

## Detener los servicios

```powershell
docker compose stop mysql
docker stop users-mailpit
```

No se deben introducir contraseñas reales, tokens PAT, secretos JWT ni claves
privadas en archivos versionados.
