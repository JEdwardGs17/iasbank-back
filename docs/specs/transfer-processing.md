# Procesamiento de Transferencias - Especificación de Negocio

## Contexto

IAS Bank es una entidad financiera que ofrece productos y servicios transaccionales a clientes individuales y corporativos.

Dentro de su plataforma tecnológica, el **core de transferencias** participa en el procesamiento, validación y seguimiento de movimientos de dinero entre cuentas. La entidad requiere una funcionalidad para registrar transferencias y mantener trazabilidad de sus resultados.

## Objetivo

Construir una solución Full Stack mínima que permita:

1. **Procesar** transferencias entre cuentas
2. **Persistir** registros de transferencias con trazabilidad completa
3. **Consultar** transferencias procesadas
4. **Presentar** desde Angular los resultados de operaciones y transferencias recientes

## Datos Semilla

| Cuenta   | Condición | Límite Diario (COP) |
|----------|-----------|----------------------|
| CTA-1001 | Válida    | 5.000.000            |
| CTA-1002 | Válida    | 5.000.000            |
| CTA-2001 | Válida    | 5.000.000            |

Al iniciar la aplicación no existen transferencias autorizadas durante el día.

## Solicitud de Transferencia

| Campo                | Ejemplo   | Significado                          |
|----------------------|-----------|--------------------------------------|
| requestReference     | REF-001   | Referencia única de la solicitud     |
| sourceAccountId      | CTA-1001  | Cuenta origen                        |
| destinationAccountId | CTA-2001  | Cuenta destino                       |
| amount               | 600000    | Valor de la transferencia en COP     |

## Requerimientos Funcionales

### RF01 - Procesar una transferencia

- El sistema permite solicitar una transferencia con los datos de la solicitud.
- La **fecha y hora de procesamiento** es registrada por el sistema.
- **Idempotencia:** si el `requestReference` ya fue procesado, se retorna el resultado original sin volver a procesarlo (ver RF05).
- Una transferencia autorizada debita la cuenta origen y acredita la cuenta destino (requiere **saldo suficiente**).

### RF02 - Validar las reglas de negocio

Una transferencia solo se **autoriza** cuando se cumplen todas las reglas; si alguna falla, queda registrada como **REJECTED**:

| # | Regla | Dónde se valida |
|---|-------|-----------------|
| 1 | El valor es **mayor que cero** | `Transfer.validate()` |
| 2 | La cuenta origen y la cuenta destino son **diferentes** | `Transfer.validate()` |
| 3 | Los datos requeridos son válidos: las cuentas **existen** | `TransferUseCaseImpl.processTransfer()` |
| 4 | La operación **no supera el límite diario** de la cuenta origen | `Account.validateDailyLimit()` |

**Límite diario:**

- Consumo del día = suma de las transferencias **AUTHORIZED** de la cuenta origen en el día de procesamiento.
- Se rechaza si `consumo del día + monto > límite diario`. Alcanzar exactamente el límite está permitido.
- Las transferencias rechazadas **no** consumen límite.

**Solicitud incompleta:** si falta `requestReference`, `sourceAccountId`, `destinationAccountId` o `amount`, la API responde **400 Bad Request** con el detalle por campo y la transferencia no se registra (no existe una referencia con la cual trazarla).

### RF03 - Conservar el resultado de la operación

Toda solicitud procesada (autorizada o rechazada) queda registrada con:

- Los **datos principales**: referencia, cuenta origen, cuenta destino y monto.
- El **estado**: `AUTHORIZED` o `REJECTED`.
- La **fecha y hora de procesamiento**.
- La **razón del rechazo** (`rejectionReason`) cuando la transferencia es rechazada; es `null` si fue autorizada.

Una solicitud rechazada **no incrementa** el valor acumulado diario de la cuenta: el consumo del día solo suma transferencias `AUTHORIZED`, y el rechazo ocurre antes de modificar cualquier saldo.

#### Razones de rechazo

| Código | Significado |
|--------|-------------|
| INVALID_AMOUNT | El monto no es mayor que cero |
| SAME_ACCOUNT | La cuenta origen y la cuenta destino son iguales |
| ACCOUNT_NOT_FOUND | La cuenta origen o destino no existe |
| DAILY_LIMIT_EXCEEDED | La operación supera el límite diario de la cuenta origen |
| INSUFFICIENT_BALANCE | La cuenta origen no tiene saldo suficiente |

### RF04 - Manejar solicitudes simultáneas

El core puede recibir solicitudes prácticamente al mismo tiempo para una misma cuenta origen; las reglas del límite diario deben preservarse.

**Solución: bloqueo pesimista en base de datos.**

1. Al iniciar la transacción se bloquean la cuenta origen y la cuenta destino (`SELECT ... FOR UPDATE`), **en orden de número de cuenta**.
2. Con las cuentas bloqueadas se verifica la idempotencia, se calcula el consumo del día, se validan las reglas y se registra el resultado.
3. Al confirmar la transacción se liberan los bloqueos y la siguiente solicitud sobre esas cuentas continúa, viendo ya el resultado anterior.

| Decisión | Motivo |
|----------|--------|
| Bloqueo en la base de datos y no en memoria | Funciona con varias instancias del backend (escalado horizontal) |
| Solo se bloquean las cuentas involucradas | Las transferencias de cuentas distintas se procesan en paralelo |
| Orden determinista del bloqueo | Transferencias cruzadas (A→B y B→A) no generan deadlocks |
| Se bloquean origen y destino | Evita actualizaciones perdidas del saldo destino |
| Aislamiento `READ COMMITTED` | Toda lectura posterior al bloqueo ve los datos confirmados más recientes |
| `UNIQUE (request_reference)` como respaldo | Dos solicitudes con la misma referencia simultáneas retornan el mismo resultado, sin error |
| Índice `(source_account_id, status, processed_at)` | El consumo del día se calcula con una búsqueda por rango, no recorriendo la tabla |

Alternativas descartadas: bloqueo en memoria (no escala a varias instancias), bloqueo distribuido con Redis (infraestructura adicional innecesaria), bloqueo optimista con reintentos (degrada con alta contención sobre una misma cuenta) y contador diario separado (duplica estado derivable de las transferencias).

### RF05 - Manejar referencias repetidas

Una misma `requestReference` puede llegar más de una vez. La referencia funciona como **clave de idempotencia**, siguiendo la práctica estándar de la industria (borrador IETF *Idempotency-Key HTTP Header Field*):

| Caso | Respuesta | Efecto |
|------|-----------|--------|
| Referencia nueva | **201 Created** con el resultado | Se procesa la transferencia |
| Referencia ya procesada con **los mismos datos** | **200 OK** con el resultado original | No se crea otra transferencia ni se incrementa el acumulado diario |
| Referencia ya procesada con **datos diferentes** | **422 Unprocessable Content** | No se procesa nada; la operación original se conserva intacta |

- **Mismos datos** significa misma cuenta origen, misma cuenta destino y mismo monto (comparado por valor: `100000` y `100000.00` son iguales).
- El resultado original se conserva tal cual, sea `AUTHORIZED` o `REJECTED`, con su razón y fecha de procesamiento.
- La regla también aplica cuando las solicitudes repetidas llegan **al mismo tiempo**: una sola se registra; las que traen los mismos datos reciben el resultado original y las que traen datos diferentes reciben 422.
- Cada conflicto queda registrado en el log del backend como advertencia, para trazabilidad.

### RF06 - Consultar transferencias

El sistema permite:

1. **Consultar una transferencia por su `requestReference`.** Retorna la transferencia con todos sus datos (incluido estado, razón de rechazo y fecha de procesamiento) o indica que no existe.
2. **Consultar las transferencias recientes**, de la más reciente a la más antigua, **paginadas**.

**Paginación** (no exigida por el requerimiento; se incluye para que la consulta escale con el volumen de datos):

- Se consulta por número de página (`page`, desde 0) y tamaño (`size`, entre 1 y 100; por defecto 20).
- La respuesta incluye el contenido de la página, el total de transferencias y el total de páginas.
- El orden es estable: por fecha de procesamiento descendente y, ante la misma fecha, por identificador descendente; así una transferencia nunca aparece en dos páginas ni se pierde entre ellas.
- Una página fuera de rango retorna una lista vacía; valores de `page` o `size` inválidos se rechazan.

### RF07 - Interfaz Angular

La interfaz permite registrar una nueva solicitud de transferencia, visualizar claramente su resultado y consultar las transferencias procesadas recientemente, integrada con el backend real.

| Necesidad | Cómo se cubre |
|-----------|---------------|
| Registrar una transferencia | Pantalla *Nueva transferencia* con formulario validado; la referencia se genera automáticamente y el monto se muestra con separadores de miles mientras se escribe |
| Visualizar el resultado | Tarjeta de resultado con estado, monto, cuentas, motivo del rechazo, referencia y fecha de procesamiento, más una alerta |
| Consultar transferencias recientes | Pantalla *Transferencias* con historial paginado |
| Consultar por referencia | Búsqueda en la misma pantalla *Transferencias*: el resultado se muestra en la tabla |
| Ver toda la información de una transferencia | Botón *Ver detalle* en cada fila, que consulta la transferencia por su referencia |

Adicionalmente (no exigido): menú lateral, modo claro y oscuro, alertas para cada acción, diseño adaptable a móviles y administración de cuentas. Detalle en el [README del frontend](../../../IASBankFront/README.md).

### Límite diario configurable

El límite diario de cada cuenta se puede configurar (por defecto, 5.000.000 COP para las cuentas semilla).

- Debe ser **mayor que cero**.
- Aplica a las transferencias **siguientes**: si se reduce por debajo de lo ya transferido en el día, las nuevas transferencias de esa cuenta se rechazan por `DAILY_LIMIT_EXCEEDED`.
- Se conserva al reiniciar la aplicación.

### Regla adicional: saldo suficiente

Los requerimientos no mencionan el saldo de las cuentas. El sistema, además de las reglas de RF02, **no autoriza una transferencia si la cuenta origen no tiene saldo suficiente** (razón `INSUFFICIENT_BALANCE`).

| Aspecto | Detalle |
|---------|---------|
| **Contexto** | Un core de transferencias mueve dinero entre cuentas; sin esta regla, una cuenta podría quedar con saldo negativo. |
| **Alternativas** | (1) Quitar el concepto de saldo: obliga a eliminar la administración de cuentas (saldos, depósitos, retiros). (2) Mover saldos sin validarlos: permite saldos negativos. (3) Mantener la regla. |
| **Decisión** | Mantener la regla como salvaguarda. RF02 indica que una transferencia *solo* puede autorizarse cuando se cumplen sus condiciones (condiciones necesarias); una validación adicional no las contradice. |
| **Riesgo** | Que una transferencia válida según el enunciado se rechace por saldo. Esto ocurría porque al reiniciar se borraban las transferencias pero los saldos conservaban su efecto, quedando incoherentes con el historial. |
| **Mitigación** | Al iniciar, las cuentas semilla recuperan su saldo inicial (10.000.000) junto con la limpieza de transferencias. Con el límite por defecto (5.000.000), el saldo inicial lo duplica y una cuenta agota su límite antes que su saldo. Además, el límite diario se valida **antes** que el saldo. Si se configura un límite mayor que el saldo, la regla de saldo puede aplicarse primero: es el comportamiento esperado. |
| **Validación** | `TransferIntegrationTest` › *Datos semilla*: el arranque restaura saldos y límites coherentes con un historial vacío, y una cuenta agota su límite diario quedándole saldo. |

### Punto opcional - Eventos en RabbitMQ

Cuando una transferencia es **autorizada**, el sistema publica el hecho de negocio `TRANSFER_AUTHORIZED` en RabbitMQ para que otros sistemas puedan reaccionar.

- Solo las transferencias autorizadas generan evento; las rechazadas y las referencias repetidas no.
- El evento se registra en la misma transacción que la transferencia (outbox) y se publica de forma asíncrona.
- Máximo **3 reintentos** de publicación con espera creciente; después, el evento queda fallido.
- Una vista de administración informa los eventos pendientes, en reintento, publicados (en la cola) y fallidos.

Detalle técnico en [ADR-14](../decisiones-tecnicas.md#adr-14).

### Estados de Transferencia

| Estado     | Descripción                                                |
|------------|------------------------------------------------------------|
| AUTHORIZED | Transferencia procesada exitosamente, saldos actualizados  |
| REJECTED   | Transferencia rechazada por fallo en validación            |

### Trazabilidad

Cada transferencia registra:
- **Referencia de solicitud** (provista por el cliente, única)
- **Número de cuenta origen**
- **Número de cuenta destino**
- **Monto**
- **Estado** (AUTHORIZED / REJECTED)
- **Razón del rechazo** (cuando aplica)
- **Fecha y hora de procesamiento** (registrada por el sistema)

## Requerimientos No Funcionales

- Stack reactivo con **WebFlux** (Netty, R2DBC)
- Arquitectura hexagonal con clara separación de responsabilidades
- Principios SOLID y clean code
- Patrones de diseño aplicados donde aporten valor
- API RESTful siguiendo convenciones HTTP
- Manejo adecuado de errores con mensajes significativos
