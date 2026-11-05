# IAS Bank - Backend

Backend reactivo para procesamiento de transferencias construido con **Spring Boot WebFlux** y **R2DBC**.

La interfaz web está documentada en el [README del frontend](../IASBankFront/README.md).

## Stack Tecnológico

| Tecnología           | Propósito                        |
|----------------------|----------------------------------|
| Spring Boot 3.2.5    | Framework                        |
| Spring WebFlux       | Web reactivo (Netty)             |
| Spring Data R2DBC    | Acceso reactivo a base de datos  |
| R2DBC MySQL          | Driver reactivo para MySQL       |
| Lombok               | Reducción de boilerplate         |
| Jakarta Validation   | Validación de requests           |
| Java 17              | Lenguaje                         |
| Gradle               | Herramienta de build             |

## Arquitectura

Arquitectura hexagonal (Puertos y Adaptadores), organizada en `core` (el negocio) y `adapters` (la tecnología):

```
com.iasbank/
  IasBankApplication.java          Punto de arranque de la aplicación
  config/                          Configuración Spring (registro de casos de uso, CORS, BD, datos semilla)

  adapters/                        Adaptadores: conectan el núcleo con el exterior
    entrypoint/http/               Entrada - API REST
      GlobalExceptionHandler.java  Traducción de errores a respuestas HTTP (común a todos los endpoints)
      PageResponse.java            Respuesta paginada
      transfer/                    TransferController + TransferDto (Request, Response)
      account/                     AccountController + AccountDto (CreateRequest, TransactionRequest, Response)
      event/                       EventController + EventDto (administración de eventos)
    entrypoint/scheduler/          EventPublicationScheduler (dispara la publicación de eventos)
    messaging/rabbitmq/            Publicador con confirmaciones y topología (implementa EventPublisherGateway)
    repository/                    Salida - Persistencia R2DBC (MySQL)
      TransactionAdapter.java      Transacciones (implementa TransactionGateway)
      transfer/                    Repositorio R2DBC, entidad (con su conversión al dominio) y adaptador (implementa TransferGateway)
      account/                     Repositorio R2DBC, entidad (con su conversión al dominio) y adaptador (implementa AccountGateway)
      event/                       Tabla outbox: repositorio, entidad y adaptador (implementa EventGateway)

  core/                            Núcleo del negocio, sin dependencias de frameworks
    domain/
      constants/                   Constantes del negocio (PaginationConstants)
      dto/                         Objetos de transporte del núcleo (TransferResult, PageResult)
      errors/                      Errores de negocio (BusinessRuleException, DuplicateTransferException,
                                   RequestReferenceConflictException)
      model/                       Modelos con sus reglas (Transfer, Account, TransferStatus, RejectionReason)
    ports/
      input/                       Puertos de entrada: un caso de uso por tipo, con sus operaciones
        TransferUseCase.java       processTransfer (RF01-RF05), getRecentTransfers, getTransferByReference (RF06)
        AccountUseCase.java        createAccount, getAllAccounts, getAccountById, getAccountByNumber,
                                   deposit, withdraw, deleteAccount
        EventUseCase.java          publishPendingEvents, getEvents, getEventSummary
      output/                      Puertos de salida (TransferGateway, AccountGateway, TransactionGateway,
                                   EventGateway, EventPublisherGateway)
    usecase/                       Implementaciones de los casos de uso
      transfer/                    TransferUseCaseImpl
      account/                     AccountUseCaseImpl
      event/                       EventUseCaseImpl
```

**Flujo de dependencias:** `adapters/entrypoint → core/ports/input ← core/usecase → core/ports/output ← adapters/repository`.
El núcleo (`core`) no conoce Spring, HTTP ni la base de datos: los adaptadores dependen del núcleo, nunca al revés.

- Las **reglas de negocio** viven en los modelos (`Transfer.validate()`, `Account.validateDailyLimit()`, `Account.withdraw()`).
- Cada **caso de uso** agrupa las operaciones de un mismo tipo (transferencias o cuentas) y las orquesta: consulta los puertos de salida, aplica las reglas del modelo y persiste.
- Los controllers dependen de las **interfaces** de `ports/input`, no de las implementaciones.
- **Todo se agrupa por tipo** (transferencias o cuentas): un caso de uso, un controller, un archivo de mensajes HTTP (`TransferDto`, `AccountDto`) y un adaptador por tipo. Cada mensaje y cada entidad conoce su propia conversión al dominio (`toDomain()` / `fromDomain()`).
- Las implementaciones no llevan anotaciones de Spring; `UseCaseConfig` las registra como beans por convención de nombre (`*UseCaseImpl`).
- La transacción se abre mediante el puerto `TransactionGateway`, implementado con `TransactionalOperator` en aislamiento `READ COMMITTED`.

### Concurrencia (RF04)

Las solicitudes simultáneas se serializan **por cuenta** con un bloqueo pesimista en MySQL (`SELECT ... FOR UPDATE`) sobre las cuentas origen y destino, tomado en orden de número de cuenta al inicio de la transacción. Funciona con varias instancias del backend, no genera deadlocks en transferencias cruzadas y las cuentas no relacionadas se procesan en paralelo. La restricción `UNIQUE` de `request_reference` respalda la idempotencia ante referencias repetidas simultáneas. Detalle y alternativas evaluadas en [docs/specs/transfer-processing.md](docs/specs/transfer-processing.md).

## Endpoints de la API

### Transferencias

| Método | Ruta              | Descripción               | Código |
|--------|-------------------|---------------------------|--------|
| POST   | /api/transfers    | Procesar una transferencia | 201 / 200 / 422 |
| GET    | /api/transfers?page=0&size=20 | Transferencias recientes, paginadas (más recientes primero) | 200 / 400 |
| GET    | /api/transfers/{requestReference} | Consultar una transferencia por su referencia | 200 / 404 |

**POST /api/transfers**

```json
{
  "requestReference": "REF-001",
  "sourceAccountId": "CTA-1001",
  "destinationAccountId": "CTA-2001",
  "amount": 600000
}
```

Respuesta (autorizada):

```json
{
  "id": 1,
  "requestReference": "REF-001",
  "sourceAccountId": "CTA-1001",
  "destinationAccountId": "CTA-2001",
  "amount": 600000,
  "status": "AUTHORIZED",
  "rejectionReason": null,
  "processedAt": "2026-11-04T14:30:46.765381"
}
```

Respuesta (rechazada):

```json
{
  "id": 2,
  "requestReference": "REF-002",
  "sourceAccountId": "CTA-1001",
  "destinationAccountId": "CTA-2001",
  "amount": 6000000,
  "status": "REJECTED",
  "rejectionReason": "DAILY_LIMIT_EXCEEDED",
  "processedAt": "2026-11-04T15:31:16.620268"
}
```

Razones de rechazo: `INVALID_AMOUNT`, `SAME_ACCOUNT`, `ACCOUNT_NOT_FOUND`, `DAILY_LIMIT_EXCEEDED`, `INSUFFICIENT_BALANCE`.

**GET /api/transfers?page=0&size=2**

Parámetros: `page` (desde 0, por defecto 0) y `size` (1 a 100, por defecto 20).

```json
{
  "content": [
    { "id": 25, "requestReference": "REF-025", "sourceAccountId": "CTA-2001", "destinationAccountId": "CTA-1001", "amount": 25000.00, "status": "AUTHORIZED", "rejectionReason": null, "processedAt": "2026-11-04T16:01:37.1" },
    { "id": 24, "requestReference": "REF-024", "sourceAccountId": "CTA-2001", "destinationAccountId": "CTA-1001", "amount": 24000.00, "status": "AUTHORIZED", "rejectionReason": null, "processedAt": "2026-11-04T16:01:37.0" }
  ],
  "page": 0,
  "size": 2,
  "totalElements": 25,
  "totalPages": 13
}
```

**GET /api/transfers/{requestReference}** retorna la transferencia (mismo formato que la respuesta del POST) o `404 Not Found` si la referencia no existe.

**Resultado de una solicitud**

| Situación | HTTP | Estado |
|-----------|------|--------|
| Cumple todas las reglas de negocio | 201 | `AUTHORIZED` |
| Incumple una regla (monto ≤ 0, misma cuenta, cuenta inexistente, límite diario superado, saldo insuficiente) | 201 | `REJECTED` |
| `requestReference` ya procesado con los mismos datos | 200 | Resultado original (no se crea otra transferencia) |
| `requestReference` ya procesado con datos diferentes | 422 | No se procesa; se conserva la operación original |
| Falta un campo requerido | 400 | No se registra |

Reglas de negocio detalladas en [docs/specs/transfer-processing.md](docs/specs/transfer-processing.md).

### Cuentas

| Método | Ruta                              | Descripción                  | Código |
|--------|-----------------------------------|------------------------------|--------|
| GET    | /api/accounts                     | Listar todas las cuentas     | 200    |
| GET    | /api/accounts/{id}                | Obtener cuenta por ID        | 200    |
| GET    | /api/accounts/number/{number}     | Obtener cuenta por número    | 200    |
| POST   | /api/accounts                     | Crear cuenta                 | 201    |
| POST   | /api/accounts/deposit             | Depositar en cuenta          | 200    |
| POST   | /api/accounts/withdraw            | Retirar de cuenta            | 200    |
| PUT    | /api/accounts/{number}/daily-limit | Configurar el límite diario (`{ "dailyLimit": 3000000 }`) | 200 / 400 |
| DELETE | /api/accounts/{id}                | Eliminar cuenta              | 204    |

## Datos Semilla

Se cargan automáticamente al iniciar la aplicación:

| Cuenta   | Saldo         | Límite Diario  | Tipo    |
|----------|---------------|----------------|---------|
| CTA-1001 | 10.000.000    | 5.000.000      | SAVINGS |
| CTA-1002 | 10.000.000    | 5.000.000      | SAVINGS |
| CTA-2001 | 10.000.000    | 5.000.000      | SAVINGS |

Cada inicio de la aplicación es un **día limpio**, como asume el enunciado: se eliminan las transferencias (y sus eventos) y las cuentas semilla recuperan su saldo inicial, de modo que los saldos siempre son coherentes con el historial de transferencias.

**Límite diario configurable:** el límite de cada cuenta se puede cambiar (`PUT /api/accounts/{number}/daily-limit` o desde la pantalla *Cuentas*). Debe ser mayor que cero, aplica a las transferencias siguientes y **se conserva al reiniciar**. Las cuentas semilla se crean con 5.000.000. El cambio bloquea la cuenta igual que una transferencia (RF04), para no sobrescribir un saldo que se esté modificando al mismo tiempo.

> **Regla adicional — saldo suficiente:** además de las reglas de RF02, no se autoriza una transferencia sin saldo suficiente en la cuenta origen. Como el saldo inicial (10.000.000) duplica el límite diario (5.000.000), en ningún escenario del enunciado una cuenta se queda sin saldo antes de agotar su límite. Decisión y riesgos detallados en [docs/specs/transfer-processing.md](docs/specs/transfer-processing.md).

## Esquema de Base de Datos

**accounts**

| Columna        | Tipo           | Restricciones      |
|----------------|----------------|--------------------|
| id             | BIGINT         | PK, AUTO_INCREMENT |
| account_number | VARCHAR(20)    | UNIQUE, NOT NULL   |
| holder         | VARCHAR(150)   | NOT NULL           |
| balance        | DECIMAL(19,2)  | NOT NULL           |
| account_type   | VARCHAR(20)    | NOT NULL           |
| daily_limit    | DECIMAL(19,2)  | NOT NULL           |

**transfers**

| Columna                | Tipo           | Restricciones      |
|------------------------|----------------|--------------------|
| id                     | BIGINT         | PK, AUTO_INCREMENT |
| request_reference      | VARCHAR(50)    | UNIQUE, NOT NULL   |
| source_account_id      | VARCHAR(20)    | NOT NULL           |
| destination_account_id | VARCHAR(20)    | NOT NULL           |
| amount                 | DECIMAL(19,2)  | NOT NULL           |
| status                 | VARCHAR(20)    | NOT NULL           |
| rejection_reason       | VARCHAR(30)    | NULL               |

Índices:

- `idx_transfers_daily_total (source_account_id, status, processed_at)`: cálculo del consumo diario.
- `idx_transfers_recent (processed_at)`: consulta paginada de transferencias recientes.
| processed_at           | DATETIME(6)    | NOT NULL           |

## Ejecución

### Con Docker (recomendado)

```bash
cd PlataformaBancaria
docker compose up --build -d
```

Backend disponible en `http://localhost:8080`.

> Si el volumen de MySQL fue creado antes de RF03/RF04/RF06, aplicar los cambios de esquema:
> `docker exec ias_bank_mysql mysql -uroot -proot ias_bank -e "ALTER TABLE transfers ADD COLUMN rejection_reason VARCHAR(30) NULL AFTER status; CREATE INDEX idx_transfers_daily_total ON transfers (source_account_id, status, processed_at); CREATE INDEX idx_transfers_recent ON transfers (processed_at)"`

### Desarrollo local

Requiere MySQL en puerto 3309 con base de datos `ias_bank`.

```bash
./gradlew bootRun
```

## Eventos en RabbitMQ (punto opcional)

Cuando una transferencia es **autorizada**, se publica un evento en RabbitMQ para que otros sistemas puedan reaccionar. Las rechazadas y las referencias repetidas no generan eventos.

**Cómo funciona (Transactional Outbox):**

1. El evento se guarda en la tabla `outbox_events` **en la misma transacción** que la transferencia: nunca se pierde ni se anuncia algo que no ocurrió.
2. Cada 2 s, un publicador toma los eventos pendientes y los publica con **confirmación de RabbitMQ**.
3. Si falla, se reintenta con espera creciente (5 s, 10 s, 20 s). Después de **3 reintentos**, el evento queda `FAILED`.

| Estado | Significado |
|--------|-------------|
| `PENDING` | Guardado, aún no se intenta publicar |
| `RETRYING` | Falló un intento; se reintentará |
| `PUBLISHED` | RabbitMQ confirmó el mensaje: está en la cola |
| `FAILED` | Agotó los 3 reintentos |

**Topología:** exchange `iasbank.transfers` (topic) → cola `iasbank.transfers.authorized`, clave `transfer.authorized`. Consola de RabbitMQ: `http://localhost:15672` (guest / guest).

**Mensaje** (JSON persistente; `messageId` = id del evento, `type` = `TRANSFER_AUTHORIZED`):

```json
{
  "transferId": 1,
  "requestReference": "REF-001",
  "sourceAccountId": "CTA-1001",
  "destinationAccountId": "CTA-2001",
  "amount": 600000.00,
  "processedAt": "2026-11-04T22:29:39.123"
}
```

La entrega es **al menos una vez**: un consumidor debe descartar mensajes repetidos usando el `messageId`.

| Método | Ruta | Descripción |
|--------|------|-------------|
| GET | /api/events?status=FAILED&page=0&size=20 | Eventos, del más reciente al más antiguo, filtrables por estado |
| GET | /api/events/summary | Eventos por estado y mensajes actualmente en la cola |

**Ver los reintentos en acción:** `docker stop ias_bank_rabbitmq`, registrar una transferencia y observar la vista *Eventos* del frontend: el evento pasa a *Reintentando* y, tras 3 reintentos (~35 s), a *Fallido*. Con `docker start ias_bank_rabbitmq`, los nuevos eventos se publican normalmente.

Decisión detallada en [ADR-14](docs/decisiones-tecnicas.md#adr-14).

## Decisiones Técnicas

Las decisiones técnicas relevantes están registradas en un único documento ADR (*Architecture Decision Record*): [docs/decisiones-tecnicas.md](docs/decisiones-tecnicas.md). Para cada decisión documenta contexto, alternativas consideradas, decisión, justificación, trade-offs y riesgos, y cómo se validó.

| # | Decisión |
|---|----------|
| [ADR-01](docs/decisiones-tecnicas.md#adr-01) | Arquitectura hexagonal con núcleo y adaptadores |
| [ADR-02](docs/decisiones-tecnicas.md#adr-02) | Stack reactivo con Spring WebFlux y R2DBC |
| [ADR-03](docs/decisiones-tecnicas.md#adr-03) | Transacciones mediante un puerto de salida |
| [ADR-04](docs/decisiones-tecnicas.md#adr-04) | Rechazos de negocio registrados y errores de formato con 400 |
| [ADR-05](docs/decisiones-tecnicas.md#adr-05) | Razón de rechazo como código de dominio |
| [ADR-06](docs/decisiones-tecnicas.md#adr-06) | Acumulado diario calculado a partir de las transferencias autorizadas |
| [ADR-07](docs/decisiones-tecnicas.md#adr-07) | Regla adicional de saldo suficiente, día limpio al iniciar y límite configurable |
| [ADR-08](docs/decisiones-tecnicas.md#adr-08) | Concurrencia con bloqueo pesimista en la base de datos |
| [ADR-09](docs/decisiones-tecnicas.md#adr-09) | La referencia de la solicitud como clave de idempotencia |
| [ADR-10](docs/decisiones-tecnicas.md#adr-10) | Consulta de transferencias paginada con orden estable |
| [ADR-11](docs/decisiones-tecnicas.md#adr-11) | Frontend Angular sin Zone.js, con signals y sistema de diseño propio |
| [ADR-12](docs/decisiones-tecnicas.md#adr-12) | Estrategia de pruebas: pirámide con base de datos real |
| [ADR-13](docs/decisiones-tecnicas.md#adr-13) | Despliegue con Docker Compose y esquema inicializado por la aplicación |
| [ADR-14](docs/decisiones-tecnicas.md#adr-14) | Publicación de eventos en RabbitMQ con Transactional Outbox |

## Uso de Inteligencia Artificial

El desarrollo contó con apoyo de IA (Claude Code). En qué se usó, qué se aprovechó, cómo se validó, qué se corrigió o descartó y cómo se protegió la información sensible está documentado en [docs/uso-de-ia.md](docs/uso-de-ia.md).

## Problemas y Soluciones

Los problemas encontrados durante el desarrollo, por qué ocurrían y cómo se resolvieron están explicados en [docs/problemas-y-soluciones.md](docs/problemas-y-soluciones.md).

| Requerimiento | Problema | Solución |
|---------------|----------|----------|
| RF04 | Solicitudes simultáneas superaban el límite diario | Las solicitudes sobre una misma cuenta se atienden una a la vez |
| RF04 | Transferencias cruzadas se bloqueaban mutuamente | Las cuentas se reservan siempre en el mismo orden |
| RF04 | La misma solicitud repetida al mismo tiempo generaba errores | Se responde con el resultado de la solicitud que se registró primero |
| RF05 | Una referencia repetida con datos diferentes devolvía la operación original como si fuera nueva | Se distingue la repetición (200) del conflicto (422), conservando siempre la original |
| RF06 | La consulta de transferencias devolvía la tabla completa en cada llamada | Consulta paginada, ordenada de forma estable y apoyada en un índice |
| RF06 / RF07 | El buscador por referencia recargaba la página sin buscar | El formulario de búsqueda quedó correctamente asociado a Angular |
| RF07 | La pantalla de cuentas no mostraba los datos hasta interactuar con ella | El estado de la pantalla se maneja con *signals* |
| RF07 | Crear una cuenta desde la interfaz siempre fallaba | El formulario envía el límite diario requerido |
| RF07 | Los números de cuenta largos sacaban las tablas y el detalle del ancho de la pantalla | Las tablas recortan el número con "…"; el resultado y el detalle lo muestran completo |
| RF02 / RF03 | Las transferencias quedaban con 5 horas de adelanto y el día del límite diario cambiaba a las 7:00 p. m. | El backend usa la zona horaria de Colombia |

## Pruebas

```bash
./gradlew test                                                        # todas (118)
./gradlew test --tests 'com.iasbank.core.*' --tests 'com.iasbank.adapters.*'  # solo unitarias (96, ~4 s, sin Docker)
```

El backend tiene **118 pruebas: 96 unitarias y 22 de integración**.

| Nivel | Qué demuestra | Cantidad |
|-------|---------------|----------|
| Dominio | Cada regla aislada: monto, cuentas, límite diario (incluido el borde exacto y su configuración), saldo, solicitudes repetidas, ciclo de vida de los eventos | 28 |
| Casos de uso | Orquestación: idempotencia, rechazos con razón, bloqueo antes de procesar, consultas, publicación y reintentos de eventos, operaciones de cuentas y cambio de límite | 36 |
| API (`@WebFluxTest`) | Contrato HTTP: 201, 200, 204, 422, 400, 404, paginación, cuentas, administración de eventos y límite diario | 23 |
| Adaptadores | Traducción en la frontera: referencia duplicada a error del dominio, rango del día, orden de las recientes, mensaje y confirmación de RabbitMQ, scheduler resiliente | 9 |
| Integración (Testcontainers + MySQL y RabbitMQ reales) | Reglas de punta a punta, concurrencia de RF04, día limpio al iniciar, publicación de eventos y límite configurable | 22 |

Las pruebas de integración levantan MySQL y RabbitMQ reales en Docker; si Docker no está disponible se omiten automáticamente. El detalle de cada prueba y de qué prueba demuestra cada requerimiento está en [docs/pruebas.md](docs/pruebas.md).

## Principios de Diseño

- **Arquitectura Hexagonal + Casos de Uso**: dominio aislado de la infraestructura; cada caso de uso tiene una sola responsabilidad
- **SOLID**: responsabilidad única por clase, inversión de dependencias mediante puertos (`*Gateway`)
- **Clean Code**: nombrado descriptivo, métodos pequeños y enfocados
- **Reactivo**: I/O no bloqueante con Project Reactor (Mono/Flux)
- **Patrón Adapter**: los adaptadores de repositorio conectan el dominio con R2DBC
- **Patrón Builder**: construcción del modelo `Transfer` (inmutable) y sus entidades
