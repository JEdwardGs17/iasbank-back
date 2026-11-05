# ADR — Decisiones Técnicas

| Proyecto | Estado | Fecha |
|----------|--------|-------|
| IAS Bank — Core de transferencias | Aceptado | 2026-11-04 |

Este documento registra las decisiones técnicas relevantes tomadas durante el desarrollo de IAS Bank (*Architecture Decision Record*). Para cada decisión se documenta:

| Sección | Contenido |
|---------|-----------|
| **Contexto** | Problema o necesidad que motivó la decisión |
| **Alternativas consideradas** | Opciones evaluadas, con sus ventajas y desventajas |
| **Decisión** | Qué se adoptó |
| **Justificación** | Por qué se eligió esa opción |
| **Consecuencias** | Trade-offs y riesgos identificados, con su mitigación |
| **Validación** | Cómo se comprobó que la decisión funciona: pruebas, mediciones y resultados |
| **Referencias** | Código y pruebas relacionadas |

## Índice

| # | Decisión | Requerimientos |
|---|----------|----------------|
| [ADR-01](#adr-01) | Arquitectura hexagonal con núcleo y adaptadores | Todos |
| [ADR-02](#adr-02) | Stack reactivo con Spring WebFlux y R2DBC | Requerimiento no funcional, RF04 |
| [ADR-03](#adr-03) | Transacciones mediante un puerto de salida | RF01, RF04 |
| [ADR-04](#adr-04) | Rechazos de negocio registrados y errores de formato con 400 | RF02, RF03 |
| [ADR-05](#adr-05) | Razón de rechazo como código de dominio | RF03 |
| [ADR-06](#adr-06) | Acumulado diario calculado a partir de las transferencias autorizadas | RF02, RF03 |
| [ADR-07](#adr-07) | Regla adicional de saldo suficiente, día limpio al iniciar y límite configurable | RF02, datos iniciales |
| [ADR-08](#adr-08) | Concurrencia con bloqueo pesimista en la base de datos | RF04 |
| [ADR-09](#adr-09) | La referencia de la solicitud como clave de idempotencia | RF05, RF04 |
| [ADR-10](#adr-10) | Consulta de transferencias paginada con orden estable | RF06 |
| [ADR-11](#adr-11) | Frontend Angular sin Zone.js, con signals y sistema de diseño propio | RF07 |
| [ADR-12](#adr-12) | Estrategia de pruebas: pirámide con base de datos real | Todos |
| [ADR-13](#adr-13) | Despliegue con Docker Compose y esquema inicializado por la aplicación | Todos |
| [ADR-14](#adr-14) | Publicación de eventos en RabbitMQ con Transactional Outbox | Punto opcional |

---

<a id="adr-01"></a>

## ADR-01. Arquitectura hexagonal con núcleo y adaptadores

**Requerimientos relacionados:** Todos · **Estado:** Aceptada

### Contexto

El core de transferencias concentra reglas de negocio sensibles (límite diario, idempotencia, concurrencia) que deben poder evolucionar, probarse y explicarse sin depender de detalles técnicos como el framework web o la base de datos. El enunciado no impone una arquitectura, pero exige que las decisiones puedan defenderse técnicamente.

### Alternativas consideradas

| Alternativa | Ventajas | Desventajas |
|-------------|----------|-------------|
| Capas tradicionales (controller → service → repository) | Simple y conocida | La lógica de negocio termina acoplada a Spring y a la persistencia; difícil de probar sin infraestructura |
| Clean Architecture con un caso de uso por operación | Responsabilidad única muy estricta | Muchas clases pequeñas (16 entre interfaces e implementaciones para 10 operaciones); fragmenta operaciones de un mismo tipo |
| **Hexagonal con `core` / `adapters` y un caso de uso por tipo** | Núcleo aislado, puertos explícitos, organización alineada con la convención del equipo | Más estructura inicial que una arquitectura en capas |

### Decisión

Arquitectura hexagonal organizada en dos grandes zonas:

- **`core`** (el negocio, sin dependencias de frameworks):
  - `domain/model`: modelos con sus reglas (`Transfer.validate()`, `Account.validateDailyLimit()`, `Account.withdraw()`).
  - `domain/dto`, `domain/errors`, `domain/constants`.
  - `ports/input`: un puerto de entrada **por tipo** (`TransferUseCase`, `AccountUseCase`) con métodos descriptivos.
  - `ports/output`: puertos de salida (`TransferGateway`, `AccountGateway`, `TransactionGateway`).
  - `usecase/<tipo>`: implementaciones (`TransferUseCaseImpl`, `AccountUseCaseImpl`).
- **`adapters`** (la tecnología):
  - `entrypoint/http/<tipo>`: controllers y mensajes HTTP agrupados por tipo (`TransferDto`, `AccountDto`).
  - `repository/<tipo>`: persistencia R2DBC; cada entidad conoce su conversión al dominio.

Las implementaciones de los casos de uso no llevan anotaciones de Spring; `UseCaseConfig` las registra por convención de nombre (`*UseCaseImpl`).

### Justificación

- Las reglas viven en el dominio y se prueban sin base de datos ni servidor (21 pruebas de dominio puras).
- Los controllers dependen de interfaces (`ports/input`), y el núcleo depende solo de puertos de salida: la base de datos o el framework web pueden cambiarse sin tocar las reglas.
- Agrupar por tipo (transferencias, cuentas) reduce la fragmentación: un caso de uso, un controller, un archivo de mensajes y un adaptador por tipo. La estructura replica la convención hexagonal que el equipo ya usa en otros proyectos, lo que facilita su mantenimiento.

### Consecuencias (trade-offs y riesgos)

- **Más archivos y paquetes** que una arquitectura en capas para un dominio pequeño. Se acepta porque el costo es bajo y el beneficio crece con cada requerimiento.
- **Riesgo:** que un caso de uso por tipo crezca demasiado. Mitigación: si `TransferUseCaseImpl` supera una responsabilidad clara, se puede dividir manteniendo la interfaz.
- La arquitectura evolucionó durante el desarrollo (servicios → casos de uso por operación → casos de uso por tipo); cada cambio se validó con la suite completa sin alterar el comportamiento.

### Validación

- `./gradlew build` compila sin dependencias del núcleo hacia `adapters` (el núcleo no importa Spring Web ni R2DBC).
- Pruebas de dominio (`TransferTest`, `AccountTest`) ejecutadas sin contexto de Spring.
- Tras cada reestructuración: 61 pruebas de backend, 29 pruebas funcionales de endpoints y 69 verificaciones E2E sin cambios de comportamiento.

### Referencias

- [`core/`](../src/main/java/com/iasbank/core), [`adapters/`](../src/main/java/com/iasbank/adapters), [`UseCaseConfig`](../src/main/java/com/iasbank/config/UseCaseConfig.java)
- README del backend, sección *Arquitectura*

---

<a id="adr-02"></a>

## ADR-02. Stack reactivo con Spring WebFlux y R2DBC

**Requerimientos relacionados:** Requerimiento no funcional, RF04 · **Estado:** Aceptada

### Contexto

El core puede recibir muchas solicitudes prácticamente al mismo tiempo (RF04). Además, se estableció como requerimiento no funcional que el backend funcione completamente con WebFlux.

### Alternativas consideradas

| Alternativa | Ventajas | Desventajas |
|-------------|----------|-------------|
| Spring MVC + JPA/Hibernate (bloqueante) | Ecosistema maduro, depuración simple | Un hilo por solicitud; mezclarlo con WebFlux bloquearía el event loop |
| WebFlux + JDBC envuelto en un pool de hilos | Reutiliza JPA | Sigue siendo bloqueante por debajo; complejidad sin el beneficio reactivo |
| **WebFlux + R2DBC (end to end reactivo)** | E/S no bloqueante de punta a punta; pocos hilos atienden muchas solicitudes | Sin las facilidades de JPA (relaciones, carga perezosa); curva de aprendizaje |

### Decisión

Spring Boot 3.2.5 con **WebFlux (Netty)** y **Spring Data R2DBC** con el driver `io.asyncer:r2dbc-mysql`. Todos los puertos y casos de uso devuelven `Mono` / `Flux`.

### Justificación

- Mientras una solicitud espera a la base de datos (por ejemplo, un bloqueo de RF04), el hilo queda libre para atender otras: la espera no consume hilos.
- Al ser reactivo en todas las capas, no hay puntos bloqueantes ocultos que anulen el beneficio.

### Consecuencias (trade-offs y riesgos)

- **Sin JPA:** las entidades son planas y la conversión al dominio es explícita. Se acepta: el dominio es pequeño y sin relaciones complejas.
- **Depuración más compleja** de flujos reactivos. Mitigación: flujos cortos, con métodos privados con nombre por paso (`lockAccounts`, `findOriginal`, `process`, `authorize`).
- **Pool de conexiones R2DBC** (10 por defecto): ver riesgo de contención en [ADR-0008](#adr-08).
- El único `block()` está en `DataSeeder`, al arrancar, antes de recibir tráfico.

### Validación

- 20 solicitudes simultáneas procesadas correctamente sin bloquear el servidor (`TransferIntegrationTest` › *RF04*).
- La aplicación arranca en ~2,5 s en Docker y responde todas las pruebas de endpoints.

### Referencias

- [`build.gradle`](../build.gradle), [`application.yml`](../src/main/resources/application.yml)

---

<a id="adr-03"></a>

## ADR-03. Transacciones mediante un puerto de salida

**Requerimientos relacionados:** RF01, RF04 · **Estado:** Aceptada

### Contexto

Procesar una transferencia modifica dos saldos y registra la transferencia: debe ser atómico. La primera versión ponía `@Transactional` en el controller, acoplando la transacción a la capa web.

### Alternativas consideradas

| Alternativa | Ventajas | Desventajas |
|-------------|----------|-------------|
| `@Transactional` en el controller | Rápido | La capa web decide reglas de consistencia; mezcla responsabilidades |
| `@Transactional` en el caso de uso | Habitual en Spring | El núcleo pasa a depender de Spring |
| Decorador transaccional alrededor del caso de uso | Núcleo limpio | La transacción queda fuera del caso de uso: no puede decidir qué parte es atómica (por ejemplo, recuperar una referencia duplicada después del *rollback*) |
| **Puerto `TransactionGateway` implementado con `TransactionalOperator`** | El caso de uso decide qué es atómico sin conocer Spring | Una interfaz y un adaptador adicionales |

### Decisión

El núcleo define `TransactionGateway.inTransaction(Mono<T>)`; `TransactionAdapter` lo implementa con `TransactionalOperator` en aislamiento `READ COMMITTED` (ver [ADR-0008](#adr-08)).

### Justificación

- El caso de uso controla el límite transaccional: el procesamiento ocurre dentro de la transacción, y la recuperación ante una referencia duplicada ocurre **fuera**, después del *rollback* ([ADR-0009](#adr-09)).
- El núcleo sigue sin dependencias de Spring.

### Consecuencias (trade-offs y riesgos)

- Un puerto más que mantener.
- **Riesgo:** olvidar envolver una operación de escritura. Mitigación: solo `processTransfer` escribe varias tablas, y está cubierto por pruebas de integración.

### Validación

- `TransferIntegrationTest`: los saldos y el registro de la transferencia siempre quedan consistentes (el dinero total se conserva en transferencias cruzadas simultáneas).
- `TransferUseCaseImplTest` verifica que el procesamiento se ejecuta a través del puerto.

### Referencias

- [`TransactionGateway`](../src/main/java/com/iasbank/core/ports/output/TransactionGateway.java), [`TransactionAdapter`](../src/main/java/com/iasbank/adapters/repository/TransactionAdapter.java)

---

<a id="adr-04"></a>

## ADR-04. Rechazos de negocio registrados y errores de formato con 400

**Requerimientos relacionados:** RF02, RF03 · **Estado:** Aceptada

### Contexto

Una transferencia puede no autorizarse por dos tipos de causas: datos incompletos (no se puede ni identificar la solicitud) o incumplimiento de una regla de negocio (monto no positivo, misma cuenta, cuenta inexistente, límite diario). RF03 exige conservar el resultado de toda solicitud procesada.

### Alternativas consideradas

| Alternativa | Ventajas | Desventajas |
|-------------|----------|-------------|
| Responder 4xx a todo rechazo, sin registrarlo | Semántica HTTP de error | Se pierde la trazabilidad que pide RF03; una repetición no podría devolver el rechazo original |
| Registrar todo, incluso solicitudes incompletas | Trazabilidad total | Sin referencia ni cuentas no hay forma de identificar ni consultar el registro (columnas obligatorias) |
| **Rechazos de negocio registrados como `REJECTED` (201); datos incompletos con 400** | Cada solicitud identificable queda trazada con su razón; el formato inválido se informa por campo | `201` para una transferencia rechazada puede sorprender a primera vista |

### Decisión

- **Datos requeridos faltantes** (`requestReference`, cuentas o monto): `400 Bad Request` con el detalle por campo; no se registra.
- **Regla de negocio incumplida:** la transferencia se registra con estado `REJECTED`, su razón y fecha de procesamiento; responde `201 Created`.
- El **monto mayor que cero** es regla de negocio (RF02 lo lista como condición de autorización), no validación de formato: un monto `0` o negativo queda registrado como `REJECTED / INVALID_AMOUNT`.

### Justificación

- `201` indica que la solicitud fue procesada y su resultado registrado; el estado (`AUTHORIZED` / `REJECTED`) está en el cuerpo, que es la información de negocio.
- Registrar los rechazos permite que una referencia repetida devuelva el resultado original, incluido un rechazo ([ADR-0009](#adr-09)).

### Consecuencias (trade-offs y riesgos)

- Un cliente que solo mire el código HTTP no sabrá si fue autorizada: debe leer `status`. Se documentó en el README con una tabla de resultados.

### Validación

- `TransferControllerTest`: faltantes → 400 por campo sin invocar el caso de uso; monto 0 → 201 con `INVALID_AMOUNT`.
- `TransferIntegrationTest`: el rechazo queda persistido con razón y fecha, y sin modificar saldos.

### Referencias

- [`TransferDto`](../src/main/java/com/iasbank/adapters/entrypoint/http/transfer/TransferDto.java), [`GlobalExceptionHandler`](../src/main/java/com/iasbank/adapters/entrypoint/http/GlobalExceptionHandler.java)

---

<a id="adr-05"></a>

## ADR-05. Razón de rechazo como código de dominio

**Requerimientos relacionados:** RF03 · **Estado:** Aceptada

### Contexto

RF03 pide conservar, cuando corresponda, una razón general que permita comprender el resultado.

### Alternativas consideradas

| Alternativa | Ventajas | Desventajas |
|-------------|----------|-------------|
| Texto libre con el mensaje de la excepción | Inmediato | No se puede consultar ni traducir de forma confiable; cambia si cambia un mensaje |
| Tabla de catálogo de razones | Extensible en caliente | Infraestructura adicional sin necesidad actual |
| **Enum de dominio `RejectionReason`** | Códigos estables, consultables y traducibles; validado por el compilador | Agregar una razón requiere desplegar |

### Decisión

`RejectionReason` (`INVALID_AMOUNT`, `SAME_ACCOUNT`, `ACCOUNT_NOT_FOUND`, `DAILY_LIMIT_EXCEEDED`, `INSUFFICIENT_BALANCE`). Cada regla lanza `BusinessRuleException(reason)`; el caso de uso registra la transferencia con `transfer.reject(reason)`. Se persiste en `rejection_reason` (nulo si se autoriza) y viaja en la API. El frontend lo traduce al español.

### Justificación

- El contrato de la API es estable aunque cambien los textos.
- El caso de uso captura **solo** `BusinessRuleException`: un error técnico inesperado no se confunde con un rechazo de negocio.

### Consecuencias (trade-offs y riesgos)

- Nuevas razones requieren desplegar backend y frontend. Aceptable para reglas que también requieren código.

### Validación

- `TransferTest` y `AccountTest` verifican la razón exacta de cada regla.
- `TransferIntegrationTest` verifica la razón persistida en MySQL; `rejection-reason.pipe.spec` verifica la traducción de las cinco razones.

### Referencias

- [`RejectionReason`](../src/main/java/com/iasbank/core/domain/model/RejectionReason.java), [`BusinessRuleException`](../src/main/java/com/iasbank/core/domain/errors/BusinessRuleException.java)

---

<a id="adr-06"></a>

## ADR-06. Acumulado diario calculado a partir de las transferencias autorizadas

**Requerimientos relacionados:** RF02, RF03 · **Estado:** Aceptada

### Contexto

El enunciado indica que el consumo diario considera únicamente las transferencias autorizadas de la cuenta origen durante el día. Una solicitud rechazada no debe incrementarlo (RF03).

### Alternativas consideradas

| Alternativa | Ventajas | Desventajas |
|-------------|----------|-------------|
| Contador diario por cuenta en una tabla | Lectura O(1) | Duplica información derivable; puede desincronizarse; requiere reinicio diario |
| Acumulado en memoria | Muy rápido | Se pierde al reiniciar; no funciona con varias instancias |
| **Suma de transferencias `AUTHORIZED` del día, con índice** | Una sola fuente de verdad; refleja exactamente la regla del enunciado | Consulta por cada transferencia |

### Decisión

`TransferGateway.sumAuthorizedAmountOfDay(cuenta, día)`: suma `amount` de las transferencias `AUTHORIZED` de la cuenta origen con `processed_at` dentro del día. Apoyada en el índice `idx_transfers_daily_total (source_account_id, status, processed_at)`. Se rechaza si `consumo + monto > límite` (alcanzar el límite exacto es válido).

### Justificación

- La regla se lee igual que el enunciado; los rechazados nunca suman porque se filtran por estado.
- El índice convierte la suma en una búsqueda por rango sobre las transferencias de esa cuenta en ese día.

### Consecuencias (trade-offs y riesgos)

- **Costo por transferencia:** una consulta de agregación. Con el índice, su costo depende de las transferencias del día de una cuenta, no del tamaño de la tabla.
- **Zona horaria (resuelto):** el "día" se calcula con la zona horaria del backend. El contenedor corría en UTC, por lo que el día se cortaba a las 19:00 hora de Colombia y las fechas se registraban con 5 horas de adelanto. Se fijó `TZ=America/Bogota` en el contenedor del backend ([ADR-13](#adr-13)).

### Validación

- `AccountTest`: bordes del límite (4.999.999 + 1 permitido; 5.000.000 + 1 rechazado).
- `TransferIntegrationTest`: 3.000.000 + 2.000.000 autorizados y 1 peso más rechazado; un rechazo de 6.000.000 no impide autorizar 5.000.000 después; el límite es por cuenta origen.

### Referencias

- [`TransferRepository`](../src/main/java/com/iasbank/adapters/repository/transfer/TransferRepository.java), [`schema.sql`](../src/main/resources/schema.sql)

---

<a id="adr-07"></a>

## ADR-07. Regla adicional de saldo suficiente, día limpio al iniciar y límite configurable

**Requerimientos relacionados:** RF02, datos iniciales · **Estado:** Aceptada

### Contexto

Los requerimientos no mencionan el saldo de las cuentas, pero el sistema mueve dinero entre ellas. El enunciado permite asumir que al iniciar no existen transferencias autorizadas en el día. La primera implementación borraba las transferencias al arrancar pero **no restauraba los saldos**: quedaban incoherentes con un historial vacío y, tras varios reinicios, una cuenta podía rechazar por saldo una transferencia válida según el enunciado.

### Alternativas consideradas

| Alternativa | Ventajas | Desventajas |
|-------------|----------|-------------|
| Eliminar el concepto de saldo | Ajuste literal a los requerimientos | Obliga a eliminar la administración de cuentas (saldos, depósitos, retiros) |
| Mover saldos sin validarlos | Nunca interfiere con el enunciado | Permite saldos negativos en un sistema financiero |
| **Validar saldo suficiente y restaurar saldos al iniciar** | Salvaguarda financiera sin afectar los escenarios del enunciado | Una regla más que documentar |

### Decisión

- Se mantiene la regla `INSUFFICIENT_BALANCE`, evaluada **después** del límite diario.
- Cada arranque es un día limpio completo: `DataSeeder` elimina las transferencias y sus eventos, y restaura el saldo de las cuentas semilla (CTA-1001, CTA-1002, CTA-2001) a 10.000.000.
- El **límite diario es configurable** por cuenta (`PUT /api/accounts/{number}/daily-limit`) y se conserva al reiniciar; las cuentas semilla se crean con 5.000.000. El cambio bloquea la cuenta como una transferencia (ver [ADR-08](#adr-08)) para no sobrescribir un saldo en proceso.

### Justificación

- RF02 indica que una transferencia *solo* puede autorizarse cuando se cumplen sus condiciones (condiciones necesarias); una salvaguarda adicional no las contradice.
- Como el saldo inicial duplica el límite diario, en un día una cuenta agota su límite antes que su saldo: la regla nunca altera los escenarios del enunciado. Y si coinciden, se informa el motivo del enunciado (el límite se valida primero).

### Consecuencias (trade-offs y riesgos)

- Reiniciar la aplicación descarta el historial y los saldos acumulados. Es intencional para el ejercicio; en producción, el reinicio diario sería un proceso de cierre, no un efecto del arranque.
- Los depósitos y retiros manuales pueden reducir el saldo de una cuenta semilla por debajo del límite; es una acción explícita del usuario.
- Si se configura un límite mayor que el saldo inicial, la regla de saldo puede rechazar antes que el límite. Es el comportamiento esperado de un límite configurado por el usuario.

### Validación

- `TransferIntegrationTest` › *Datos semilla*: tras arrancar no hay transferencias, los saldos vuelven a su valor inicial y el límite configurado se conserva; una cuenta agota 5.000.000 de límite y conserva saldo.
- `TransferIntegrationTest` › *Límite diario configurable*: tras configurar 1.000.000, una transferencia de 1.000.001 se rechaza y una de 1.000.000 se autoriza; un límite de 0 responde 400.
- En Docker: antes del reinicio 29 transferencias y saldos de 5M/12M/13M; después, 0 transferencias y 10M en las tres cuentas.

### Referencias

- [`DataSeeder`](../src/main/java/com/iasbank/config/DataSeeder.java), [`Account`](../src/main/java/com/iasbank/core/domain/model/Account.java)

---

<a id="adr-08"></a>

## ADR-08. Concurrencia con bloqueo pesimista en la base de datos

**Requerimientos relacionados:** RF04 · **Estado:** Aceptada

### Contexto

Varias solicitudes pueden llegar prácticamente al mismo tiempo para una misma cuenta origen. Una prueba real sobre la implementación inicial demostró tres fallas:

| Escenario | Resultado sin control de concurrencia |
|-----------|---------------------------------------|
| 20 solicitudes simultáneas de 1.000.000 (límite 5.000.000) | **14.000.000 autorizados** (condición de carrera) |
| 20 transferencias cruzadas simultáneas A↔B | 10 errores 500 (*deadlocks*) |
| Misma referencia 10 veces a la vez | 8 errores 500 (choque con `UNIQUE`) |

### Alternativas consideradas

| Alternativa | Ventajas | Desventajas |
|-------------|----------|-------------|
| Bloqueo en memoria (mutex por cuenta) | Simple | Solo protege una instancia; no escala horizontalmente; bloquea hilos en WebFlux |
| Bloqueo distribuido (Redis) | Escala horizontalmente | Infraestructura adicional y nuevo punto de falla |
| Bloqueo optimista (`@Version` + reintentos) | Sin esperas | Con alta contención sobre una misma cuenta (justo el escenario de RF04) los reintentos se acumulan |
| Contador con `UPDATE` condicional | Muy eficiente | Duplica estado derivable ([ADR-0006](#adr-06)) |
| Más hilos / procesamiento asíncrono | — | No resuelve la carrera: más paralelismo la agrava |
| **Bloqueo pesimista (`SELECT … FOR UPDATE`) en MySQL** | Correcto con varias instancias; solo serializa las cuentas involucradas | Las solicitudes de una misma cuenta esperan en fila |

### Decisión

1. Lo primero que hace la transacción es bloquear **origen y destino** con `SELECT … FOR UPDATE`, **ordenadas por número de cuenta**.
2. Con las cuentas bloqueadas: verificación de idempotencia, cálculo del acumulado, reglas y registro.
3. Aislamiento **`READ COMMITTED`** (MySQL usa `REPEATABLE READ` por defecto) para que toda lectura posterior al bloqueo vea lo último confirmado.

### Justificación

- El estado compartido vive en la base de datos: coordinar ahí funciona igual con una o varias instancias.
- El orden determinista elimina los *deadlocks* en transferencias cruzadas.
- Bloquear también el destino evita actualizaciones perdidas de su saldo.
- La espera del bloqueo no consume hilos en WebFlux ([ADR-0002](#adr-02)).

### Consecuencias (trade-offs y riesgos)

- **Throughput por cuenta limitado:** las solicitudes de una misma cuenta se procesan una a la vez. Las de cuentas distintas siguen en paralelo.
- **Riesgo — agotamiento del pool:** las solicitudes en espera retienen una conexión R2DBC (10 por defecto). Con contención muy alta sobre una sola cuenta, otras cuentas podrían esperar conexión. Mitigación: dimensionar el pool y mantener corta la sección crítica (índices de [ADR-0006](#adr-06) y [ADR-0010](#adr-10)).
- **Espera máxima de bloqueo:** `innodb_lock_wait_timeout` = 50 s (valor por defecto de MySQL).

### Validación

| Escenario | Antes | Después |
|-----------|-------|---------|
| 20 × 1.000.000 simultáneas | 14.000.000 autorizados | **5.000.000 exactos**, 15 rechazadas |
| 20 cruzadas simultáneas | 10 errores | 0 errores, dinero total conservado |
| Misma referencia × 10 | 8 errores | 1 registro, 10 respuestas idénticas |

- Automatizado en `TransferIntegrationTest` › *RF04* contra MySQL real.
- **Prueba de mutación:** al eliminar el bloqueo, las pruebas de concurrencia fallan; al restaurarlo, pasan.

### Referencias

- [`AccountRepository.lockByAccountNumbers`](../src/main/java/com/iasbank/adapters/repository/account/AccountRepository.java), [`TransactionAdapter`](../src/main/java/com/iasbank/adapters/repository/TransactionAdapter.java)
- [problemas-y-soluciones.md](problemas-y-soluciones.md) (versión explicativa)

---

<a id="adr-09"></a>

## ADR-09. La referencia de la solicitud como clave de idempotencia

**Requerimientos relacionados:** RF05, RF04 · **Estado:** Aceptada

### Contexto

Una misma `requestReference` puede llegar más de una vez (reintentos de red, duplicados). No debe generarse una segunda transferencia ni incrementarse el acumulado, y debe conservarse el resultado original. Si llega con información diferente, debe manejarse de forma controlada. La versión inicial devolvía el original con `201` incluso con datos distintos: un cliente que pidió 900.000 recibía "creada" sobre una transferencia de 100.000.

### Alternativas consideradas

| Alternativa | Ventajas | Desventajas |
|-------------|----------|-------------|
| Devolver siempre el original, sin comparar | Simple | Respuesta engañosa ante datos diferentes |
| Sobrescribir con los datos nuevos | — | Destruye la operación original (viola RF05) |
| Responder 409 ante cualquier repetición | Explícito | Rompe los reintentos legítimos |
| **Semántica de *Idempotency-Key* (Stripe, borrador IETF)** | Estándar de la industria; distingue repetición de conflicto | El cliente debe interpretar 200 y 422 |

### Decisión

| Caso | Respuesta |
|------|-----------|
| Referencia nueva | `201 Created`, se procesa |
| Repetida con los mismos datos (origen, destino, monto por valor) | `200 OK` con el resultado original |
| Repetida con datos diferentes | `422 Unprocessable Content`; no se procesa nada |

- La comparación es del dominio: `Transfer.isSameRequestAs()` (montos por valor: `100000` = `100000.00`).
- **Respaldo ante concurrencia:** la restricción `UNIQUE (request_reference)`. Si dos solicitudes chocan, el adaptador traduce `DuplicateKeyException` a `DuplicateTransferException`; el caso de uso la captura **fuera** de la transacción revertida y aplica la misma comparación.
- El frontend genera un UUID por solicitud y, ante una falla de red, ofrece **reintentar con la misma referencia**.

### Justificación

- Sigue el borrador IETF *Idempotency-Key HTTP Header Field*, que recomienda `422` cuando se reutiliza una clave con otro contenido.
- El `UNIQUE` garantiza que nunca existan dos registros con la misma referencia, aun ante condiciones de carrera.

### Consecuencias (trade-offs y riesgos)

- Las referencias no expiran: una referencia se reserva para siempre. Aceptable para trazabilidad.
- **Riesgo:** un cliente que genere referencias no únicas recibirá 422. Es el comportamiento deseado.

### Validación

- `TransferIntegrationTest`: repetida con mismos datos → 200, una fila, saldo y acumulado afectados una vez; con datos diferentes → 422 y original intacta; 10 simultáneas → 1 registro (1×201 + 9×200).
- `TransferTest`: comparación con distinta escala de monto.
- E2E: falla de red simulada en la que la primera solicitud sí llega al servidor → el reintento responde "ya había sido procesada" y no duplica.

### Referencias

- [`TransferUseCaseImpl`](../src/main/java/com/iasbank/core/usecase/transfer/TransferUseCaseImpl.java), [`TransferRepositoryAdapter`](../src/main/java/com/iasbank/adapters/repository/transfer/TransferRepositoryAdapter.java)

---

<a id="adr-10"></a>

## ADR-10. Consulta de transferencias paginada con orden estable

**Requerimientos relacionados:** RF06 · **Estado:** Aceptada

### Contexto

RF06 pide consultar una transferencia por su referencia y las transferencias recientes. No exige paginación avanzada, pero la consulta inicial devolvía la tabla completa en cada llamada.

### Alternativas consideradas

| Alternativa | Ventajas | Desventajas |
|-------------|----------|-------------|
| Devolver todo | Trivial | No escala; permite respuestas enormes |
| "Últimas N" fijas | Simple | No permite navegar el historial |
| Paginación por cursor (*keyset*) | Rendimiento constante en páginas profundas | Más compleja; el requerimiento indica que no es necesaria |
| **Paginación por página y tamaño (*offset*) con orden estable** | Simple, conocida, suficiente para el alcance | Páginas muy profundas son más costosas |

### Decisión

- `GET /api/transfers?page=0&size=20`: `page` ≥ 0, `size` entre 1 y 100 (por defecto 20; constantes en `PaginationConstants`). Respuesta con `content`, `page`, `size`, `totalElements`, `totalPages`.
- Orden `processed_at DESC, id DESC` (desempate estable) e índice `idx_transfers_recent (processed_at)`.
- `GET /api/transfers/{requestReference}`: 200 o 404.
- El dominio usa su propio `PageResult`, sin las clases de paginación de Spring.

### Justificación

- El tamaño máximo protege al servidor de consultas abusivas.
- Sin el desempate por `id`, dos transferencias con la misma fecha podrían repetirse o perderse entre páginas.

### Consecuencias (trade-offs y riesgos)

- **Páginas profundas** (*offset* alto) y **`COUNT(*)`** crecen con el volumen. Si el historial creciera mucho, la evolución natural es paginación por cursor.

### Validación

- `EXPLAIN` de la consulta: usa `idx_transfers_recent` con *Backward index scan* (sin ordenar la tabla).
- `TransferIntegrationTest`: orden de la más reciente a la más antigua y metadatos correctos; `TransferControllerTest`: parámetros fuera de rango → 400.

### Referencias

- [`TransferController`](../src/main/java/com/iasbank/adapters/entrypoint/http/transfer/TransferController.java), [`PaginationConstants`](../src/main/java/com/iasbank/core/domain/constants/PaginationConstants.java)

---

<a id="adr-11"></a>

## ADR-11. Frontend Angular sin Zone.js, con signals y sistema de diseño propio

**Requerimientos relacionados:** RF07 · **Estado:** Aceptada

### Contexto

RF07 pide una interfaz funcional que permita registrar transferencias, visualizar claramente el resultado y consultar las recientes, evidenciando integración real con el backend. Adicionalmente se buscó una interfaz limpia: menú lateral, modo claro y oscuro, alertas y diseño adaptable.

### Alternativas consideradas

| Alternativa | Ventajas | Desventajas |
|-------------|----------|-------------|
| Angular con Zone.js y estado en propiedades | Modelo tradicional | Detección de cambios global y más costosa |
| Librería de componentes (Angular Material, PrimeNG) | Componentes listos | Dependencia pesada; diseño genérico; más difícil personalizar el tema oscuro |
| **Angular 21 *zoneless* + *signals* + sistema de diseño propio (variables CSS)** | Rendimiento y estado explícito; control total del diseño | Todo estado asíncrono debe vivir en *signals* |

### Decisión

- Componentes *standalone*, carga diferida por feature y estado en *signals*.
- `core/` contiene los datos por tipo (modelos y servicios de cuentas y transferencias) y servicios transversales (tema, alertas); `features/` solo pantallas y componentes visuales; `shared/` componentes y directivas reutilizables (ícono, modal, paginación, campo de monto con separadores de miles).
- Sistema de diseño con variables CSS: el modo oscuro solo redefine variables (fondo negro con acentos violeta). El tema respeta la preferencia del sistema, se recuerda y se aplica antes de cargar Angular para evitar parpadeos.
- Alertas para cada acción; reintento seguro con la misma referencia ([ADR-0009](#adr-09)); formato regional `es-CO`.

### Justificación

- *Zoneless* + *signals* es la dirección actual de Angular: solo se redibuja lo que cambia.
- Un sistema de diseño propio con variables CSS permite el tema oscuro sin duplicar estilos.

### Consecuencias (trade-offs y riesgos)

- **Riesgo materializado y corregido:** la pantalla de cuentas guardaba estado en propiedades y no se redibujaba al llegar los datos. Se corrigió pasando a *signals*, y la regla quedó establecida.
- **Riesgo materializado y corregido:** un formulario sin `[formGroup]` recargaba la página al enviar. Se agregó cobertura E2E que verifica que buscar no recarga la página.

### Validación

- 32 pruebas unitarias (Vitest): validaciones, servicio HTTP (201 vs 200), reintento, 422, búsqueda y detalle.
- 69 verificaciones E2E en Chrome real: navegación, tema (fondo negro verificado), transferencias, reintento ante falla de red, paginación, búsqueda, detalle, cuentas, alertas y vista móvil sin desplazamiento horizontal.
- Revisión visual con capturas en ambos temas y en móvil.

### Referencias

- [README del frontend](https://github.com/JEdwardGs17/iasbank-front#readme)

---

<a id="adr-12"></a>

## ADR-12. Estrategia de pruebas: pirámide con base de datos real

**Requerimientos relacionados:** Todos · **Estado:** Aceptada

### Contexto

Se deben incluir pruebas automatizadas que demuestren que las reglas funcionan. Las protecciones más críticas (bloqueos de RF04, `UNIQUE` de RF05) viven en la base de datos.

### Alternativas consideradas

| Alternativa | Ventajas | Desventajas |
|-------------|----------|-------------|
| Solo pruebas unitarias con *mocks* | Rápidas | No pueden demostrar concurrencia ni restricciones de la base de datos: pasarían aunque las protecciones no existieran |
| Base de datos en memoria (H2) | Rápida, sin Docker | Su comportamiento de bloqueos difiere de MySQL; la prueba no sería representativa |
| **Pirámide: dominio, casos de uso, API, adaptadores, integración con MySQL real (Testcontainers), frontend y E2E** | Cada nivel prueba lo que le corresponde; la concurrencia se demuestra en el motor real | La integración requiere Docker y es más lenta |

### Decisión

| Nivel | Herramientas | Cantidad |
|-------|--------------|----------|
| Dominio | JUnit 5, AssertJ | 28 |
| Casos de uso | Mockito, StepVerifier | 36 |
| API | `@WebFluxTest` | 23 |
| Adaptadores | Mockito, StepVerifier | 9 |
| Integración | Testcontainers 1.21.4 + MySQL 8 + RabbitMQ 3.13 | 22 |
| Frontend | Vitest | 48 |
| E2E | Playwright Test + Chrome | 30 |

Las cuatro primeras capas son **pruebas unitarias** (96): cada una aísla una clase y simula sus puertos, sin base de datos ni broker. Las de integración (22) cubren lo que solo existe en el motor real y se omiten automáticamente si no hay Docker (`disabledWithoutDocker`).

### Justificación

- Las pruebas de dominio documentan cada regla en sus bordes; las de integración prueban el sistema real, incluida la concurrencia.
- La arquitectura hexagonal hace que cada capa se pruebe por separado: el caso de uso con sus puertos simulados y cada adaptador con su tecnología simulada. Así una falla señala exactamente la capa responsable.
- Una matriz relaciona cada requerimiento con las pruebas que lo demuestran.

### Consecuencias (trade-offs y riesgos)

- La suite de backend tarda ~30 s por los contenedores de MySQL y RabbitMQ; las unitarias solas tardan ~4 s y no requieren Docker.
- Testcontainers se fijó en 1.21.4: la versión incluida con Spring Boot 3.2 no es compatible con Docker Engine 29.
- El E2E requiere la aplicación corriendo y Chrome instalado. Cada archivo E2E crea y elimina sus propias cuentas, por lo que no depende del límite diario de las cuentas semilla y puede repetirse el mismo día.

### Validación

- **Prueba de mutación:** se eliminó el bloqueo de cuentas y las pruebas de concurrencia fallaron; restaurado, pasan. Demuestra que las pruebas protegen la regla.
- Todas las suites en verde: 118 backend (96 unitarias y 22 de integración), 48 frontend, 30 E2E con Playwright Test.

### Referencias

- [pruebas.md](pruebas.md) (matriz de trazabilidad requerimiento → prueba)

---

<a id="adr-13"></a>

## ADR-13. Despliegue con Docker Compose y esquema inicializado por la aplicación

**Requerimientos relacionados:** Todos · **Estado:** Aceptada

### Contexto

El sistema debe poder ejecutarse completo (base de datos, backend y frontend) de forma reproducible para su evaluación.

### Alternativas consideradas

| Alternativa | Ventajas | Desventajas |
|-------------|----------|-------------|
| Instalación local de cada componente | Sin Docker | Difícil de reproducir; depende del entorno |
| **Docker Compose con MySQL, backend y frontend (nginx)** | Un comando levanta todo; mismo entorno para todos | Requiere Docker |
| Migraciones con Flyway/Liquibase | Historial versionado del esquema | Flyway requiere un driver JDBC adicional en un stack R2DBC; mayor complejidad para el alcance |
| **`schema.sql` con `CREATE TABLE IF NOT EXISTS`** | Simple; crea el esquema al arrancar | No aplica cambios a tablas ya existentes |

### Decisión

- `docker compose up --build -d` levanta MySQL 8 (puerto 3309), RabbitMQ 3.13 con consola de administración (5672 / 15672), backend (8080) y frontend en nginx (4200). Nginx sirve la aplicación y actúa como *proxy* de `/api` hacia el backend, evitando problemas de CORS.
- El `docker-compose.yml` vive en el repositorio del backend y construye el frontend directamente desde su repositorio de GitHub, así que basta clonar el backend y ejecutar un comando. La variable `FRONTEND_PATH` permite usar una copia local del frontend.
- El esquema se crea con `schema.sql` al iniciar el backend.
- El backend corre con la zona horaria de Colombia (`TZ=America/Bogota`). Toda fecha de la aplicación (procesamiento de transferencias, eventos y el día del límite diario) la toma el backend, así que con esto quedan en hora de Colombia. MySQL guarda la fecha tal como la envía el backend.

### Justificación

- Reproducibilidad con un solo comando.
- Para el alcance del ejercicio, un esquema declarativo es suficiente.

### Consecuencias (trade-offs y riesgos)

- **Riesgo — evolución del esquema:** si el volumen de MySQL ya existía, las columnas o índices nuevos no se aplican solos. Se documentó en el README el comando `ALTER TABLE` / `CREATE INDEX` correspondiente. En un entorno productivo se recomienda adoptar migraciones versionadas (Flyway con driver JDBC solo para migrar).
- Credenciales de base de datos de desarrollo (`root/root`) en la configuración; en producción deben venir de variables de entorno o un gestor de secretos.

### Validación

- Imágenes reconstruidas y stack levantado tras cada cambio; pruebas de endpoints y E2E ejecutadas contra los contenedores.
- Zona horaria: con el reloj de Colombia en 20:17, una transferencia quedó registrada a las 20:17 del mismo día (antes quedaba a la 01:17 del día siguiente).

### Referencias

- [`docker-compose.yml`](../docker-compose.yml), [`schema.sql`](../src/main/resources/schema.sql), [`nginx.conf`](https://github.com/JEdwardGs17/iasbank-front/blob/master/nginx.conf)

---

<a id="adr-14"></a>

## ADR-14. Publicación de eventos en RabbitMQ con Transactional Outbox

**Requerimientos relacionados:** Punto opcional (RabbitMQ) · **Estado:** Aceptada

### Contexto

Cuando una transferencia es autorizada, otros sistemas deben poder reaccionar a ese hecho de negocio. Se decidió publicar un evento en RabbitMQ (solo publicador, sin consumidor) y ofrecer una vista de administración con el estado de cada evento, con un máximo de 3 reintentos de publicación. El reto central es que guardar la transferencia en MySQL y publicar en RabbitMQ son dos sistemas distintos, sin una transacción común.

### Alternativas consideradas

| Alternativa | Ventajas | Desventajas |
|-------------|----------|-------------|
| Publicar directamente después de guardar | Simple | Si RabbitMQ está caído o la aplicación se detiene en medio, **el evento se pierde** |
| Publicar antes de confirmar la transacción | Simple | Si la transacción se revierte, se anuncia **una transferencia que no existe** |
| Eventos en memoria con reintentos | Sin tablas adicionales | Se pierden al reiniciar; sin trazabilidad para la vista de administración |
| Transacción distribuida (XA) | Atomicidad formal | Complejidad alta; RabbitMQ no participa de XA de forma práctica |
| **Transactional Outbox + publicador con confirmaciones** | Ningún evento se pierde ni se inventa; cada intento queda registrado | Una tabla y un proceso adicionales; entrega "al menos una vez" |

### Decisión

1. **Outbox:** al autorizar, `TransferUseCaseImpl` guarda un `OutboxEvent` (`TRANSFER_AUTHORIZED`) en la tabla `outbox_events` **en la misma transacción** que la transferencia. Las rechazadas y las referencias repetidas no generan eventos.
2. **Publicación:** `EventPublicationScheduler` (adaptador de entrada) ejecuta `EventUseCase.publishPendingEvents()` cada 2 s. El adaptador `RabbitMqEventPublisher` publica con **confirmaciones del broker**: un evento solo se marca `PUBLISHED` cuando RabbitMQ lo confirma.
3. **Reintentos:** cada falla se registra con su error y programa un reintento con espera creciente (5 s, 10 s, 20 s). Tras el primer intento y **3 reintentos**, el evento queda `FAILED`. Las reglas viven en el modelo (`OutboxEvent.markPublished()`, `registerFailedAttempt()`).
4. **Topología:** exchange `iasbank.transfers` (topic, durable), cola `iasbank.transfers.authorized` (durable), clave `transfer.authorized`. Mensajes persistentes en JSON, con el id del evento como `messageId` y el tipo como `type`.
5. **Duplicados:** la entrega es "al menos una vez" (si la aplicación se detiene entre la confirmación y el registro, el evento se publica de nuevo). Cada mensaje lleva un `messageId` único para que los consumidores descarten repetidos; en la base de datos, `UNIQUE (event_type, aggregate_id)` garantiza un solo evento por transferencia.
6. **Administración:** `GET /api/events` (filtro por estado, paginado) y `GET /api/events/summary` (conteo por estado y mensajes en la cola). El frontend muestra la vista *Eventos*, con actualización automática cada 5 s.
7. **Arquitectura hexagonal:** puertos `EventUseCase` (entrada), `EventGateway` y `EventPublisherGateway` (salida); adaptadores en `entrypoint/http/event`, `entrypoint/scheduler`, `repository/event` y `messaging/rabbitmq`. El núcleo no conoce RabbitMQ.

### Justificación

- El outbox resuelve el problema de la doble escritura con la base de datos que ya existe, sin infraestructura adicional.
- Las confirmaciones del broker evitan marcar como publicado algo que RabbitMQ no recibió.
- Registrar cada intento da trazabilidad completa para la vista de administración.

### Consecuencias (trade-offs y riesgos)

- **Latencia:** un evento se publica hasta ~2 s después de autorizada la transferencia (intervalo del publicador).
- **Eventos fallidos:** tras 3 reintentos quedan `FAILED` y requieren intervención (no se reintentan solos). Mitigación futura: una acción de reintento manual desde la vista de administración.
- **Varias instancias:** dos publicadores podrían tomar el mismo evento y publicarlo dos veces. Es aceptable con entrega "al menos una vez" y `messageId`; mitigación futura: `SELECT … FOR UPDATE SKIP LOCKED` al tomar el lote.
- **Sin consumidor:** los mensajes se acumulan en la cola. Al iniciar (día limpio) se vacían la tabla del outbox y la cola.
- **Spring AMQP es bloqueante:** las llamadas se ejecutan en `Schedulers.boundedElastic()`, fuera del camino de las solicitudes HTTP.
- **Problema encontrado:** Spring invoca una sola vez los métodos `@Scheduled` reactivos y se vuelve a suscribir al mismo `Mono`; la hora de la consulta quedaba congelada en el arranque. Se corrigió con `Flux.defer` y una prueba de regresión.

### Validación

- **Pruebas de dominio** (`OutboxEventTest`): estados, espera de 5/10/20 s, `FAILED` después de 3 reintentos y reintentos contados correctamente.
- **Pruebas del caso de uso** (`EventUseCaseImplTest`): publicado, reintento, fallido, resumen con broker caído y la regresión de la hora congelada.
- **Integración con RabbitMQ real** (Testcontainers): el mensaje llega a la cola con su contenido y `messageId`; las rechazadas y las repeticiones no publican; 20 solicitudes simultáneas generan exactamente 5 eventos.
- **Prueba en Docker deteniendo RabbitMQ:** el evento pasó por `PENDIENTE → REINTENTANDO (0, 1, 2) → FALLIDO (3 reintentos)` con el error `UnknownHostException: rabbitmq`; al levantar RabbitMQ, un evento nuevo falló su primer intento mientras se restablecía la conexión y **se publicó en el primer reintento**.
- **E2E en Chrome:** la vista *Eventos* coincide con la API, filtra por estado y muestra el evento de la transferencia autorizada.

### Referencias

- [`OutboxEvent`](../src/main/java/com/iasbank/core/domain/model/OutboxEvent.java), [`EventUseCaseImpl`](../src/main/java/com/iasbank/core/usecase/event/EventUseCaseImpl.java), [`RabbitMqEventPublisher`](../src/main/java/com/iasbank/adapters/messaging/rabbitmq/RabbitMqEventPublisher.java), [`EventPublicationScheduler`](../src/main/java/com/iasbank/adapters/entrypoint/scheduler/EventPublicationScheduler.java)

---

## Riesgos abiertos

| Riesgo | ADR | Mitigación propuesta |
|--------|-----|----------------------|
| Contención muy alta sobre una cuenta puede retener conexiones del pool | [ADR-08](#adr-08) | Dimensionar el pool R2DBC según la carga esperada |
| Cambios de esquema no se aplican a volúmenes existentes | [ADR-13](#adr-13) | Migraciones versionadas en un entorno productivo |
| Paginación *offset* en páginas muy profundas | [ADR-10](#adr-10) | Paginación por cursor si el volumen lo exige |
| Eventos `FAILED` requieren intervención manual | [ADR-14](#adr-14) | Acción de reintento manual en la vista de administración |
| Publicación duplicada con varias instancias del publicador | [ADR-14](#adr-14) | `SELECT … FOR UPDATE SKIP LOCKED` al tomar el lote; consumidores idempotentes por `messageId` |

## Documentos relacionados

- [Especificación de negocio](specs/transfer-processing.md)
- [Problemas y soluciones](problemas-y-soluciones.md): versión explicativa, sin código, de los problemas encontrados
- [Pruebas automatizadas](pruebas.md): matriz requerimiento → prueba
