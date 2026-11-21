# Users Management — Spring Boot, Arquitectura Hexagonal y DDD

Aplicación de gestión de usuarios construida con Java 17 y Spring Boot. La API REST es el punto de entrada activo. El código de la antigua CLI se conserva como adaptador inactivo y no posee un contenedor de dependencias independiente.

Spring es el único *composition root*: `Main` inicia el contexto y las dependencias se resuelven mediante configuración y component scanning de Spring.

## Verificación

`GET /health` es publico y responde HTTP 200 con los metadatos generados desde
`pom.xml` durante el build:

```json
{"status":"UP","name":"users-management","version":"2.2.0"}
```

Indica que la API responde; no comprueba MySQL ni el proveedor de correo.

```bash
./mvnw clean test
./mvnw clean package
```

En Windows se puede utilizar `mvnw.cmd`.

## Base de datos MySQL compatible en TiDB Cloud Starter

La configuración usa variables de entorno, el puerto `4000` y TLS con validación
del certificado y del hostname mediante `DB_SSL_MODE=VERIFY_IDENTITY`. Consulta
la [Guía 4](docs/guia-4-tidb.md) para extraer los datos de conexión, crear el
esquema, iniciar la aplicación localmente, desplegar en Render y probar el flujo
JWT hasta reemplazar la contraseña inicial.

## Despliegue en Render

El archivo `render.yaml` define el servicio web, el build con Docker y el despliegue
automático de cada commit que llegue a la rama `main`.

1. Crear un Blueprint en Render y seleccionar este repositorio.
2. Completar en el panel los secretos marcados como requeridos: `DB_HOST`,
   `DB_USERNAME`, `DB_PASSWORD`, `BREVO_API_KEY` y `BREVO_FROM_ADDRESS`.
   Usar `DB_PORT=4000`, `DB_NAME=crud_usuarios`,
   `DB_SSL_MODE=VERIFY_IDENTITY` y `BREVO_FROM_NAME=Gestion de Usuarios`.
3. Usar el host público de TiDB Cloud Starter y ejecutar
   `src/main/resources/schema.sql` una vez para crear el esquema.
4. Desplegar el Blueprint. La API quedará disponible en el subdominio
   `onrender.com` asignado por Render y Swagger UI en `/swagger-ui.html`.

Las credenciales nunca deben guardarse en `application.properties` ni en
`render.yaml`. Para desarrollo local, deben proporcionarse como variables de entorno.

## Notificaciones transaccionales con Brevo

La aplicación usa `POST https://api.brevo.com/v3/smtp/email` mediante
`RestClient`. El envío se ejecuta en `notificationExecutor` con `@Async`, de modo
que crear o actualizar un usuario no espera a Brevo. El adaptador aplica timeout,
reintentos exponenciales para errores transitorios, circuit breaker e
`IdempotencyKey` estable durante los reintentos.

Consultar la [Guía 5](docs/guia-5-brevo.md) para:

- sincronizar la rama y fusionarla de forma segura;
- configurar secretos locales y de Render;
- ejecutar las pruebas y JaCoCo;
- validar Swagger/Postman y diagnosticar errores de Brevo.

Variables principales:

| Variable | Ejemplo seguro |
|---|---|
| `BREVO_API_KEY` | `xkeysib-REPLACE_WITH_BREVO_API_KEY` |
| `BREVO_FROM_ADDRESS` | `remitente-verificado@example.com` |
| `BREVO_FROM_NAME` | `Gestion de Usuarios` |

La clave debe ser una API key REST (`xkeysib-`), no una clave SMTP
(`xsmtpsib-`). El remitente debe estar verificado en Brevo.
