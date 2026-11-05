# Pruebas Automatizadas

Este documento describe cómo se demuestra, con pruebas automatizadas, que las reglas de negocio funcionan correctamente. No hay un porcentaje de cobertura exigido; las pruebas se enfocan en los comportamientos relevantes de cada requerimiento.

## Estrategia

Las pruebas siguen una pirámide: muchas pruebas rápidas sobre las reglas puras y menos pruebas, más completas, sobre el sistema integrado. El backend tiene **118 pruebas: 96 unitarias** (dominio, casos de uso, API y adaptadores, sin base de datos ni broker) **y 22 de integración**.

| Nivel | Qué demuestra | Herramientas | Cantidad |
|-------|---------------|--------------|----------|
| **Dominio** | Cada regla de negocio aislada, sin base de datos ni framework | JUnit 5, AssertJ | 28 |
| **Casos de uso** | La orquestación: idempotencia, rechazos con razón, bloqueo de cuentas antes de procesar, operaciones de cuentas, consultas, publicación de eventos | JUnit 5, Mockito, StepVerifier | 36 |
| **API** | El contrato HTTP: códigos 201, 200, 204, 422, 400 y 404, validaciones, paginación, cuentas y administración de eventos | `@WebFluxTest`, WebTestClient | 23 |
| **Adaptadores** | La traducción en la frontera: referencia duplicada a error del dominio, rango del día para el límite, orden de las recientes, mensaje y confirmación de RabbitMQ, scheduler que no se detiene ante un error | JUnit 5, Mockito, StepVerifier | 9 |
| **Integración** | Las reglas de punta a punta contra **MySQL y RabbitMQ reales**, incluida la concurrencia, el día limpio al iniciar y la publicación de eventos | Testcontainers, MySQL 8, RabbitMQ 3.13 | 22 |
| **Frontend** | Validaciones del formulario, formato del monto, traducción de motivos, integración HTTP, reintento seguro, búsqueda, detalle y vista de eventos | Vitest | 48 |
| **E2E** | La aplicación completa en Chrome contra el backend real, en escritorio y celular | Playwright Test | 30 |

**¿Por qué una base de datos real?** La concurrencia (RF04) se resuelve con bloqueos en MySQL y la idempotencia (RF05) con la restricción única de la referencia. Esos comportamientos solo existen en la base de datos real: una prueba con objetos simulados no podría demostrarlos.

## Pruebas unitarias

### Qué se prueba en cada capa

Las 96 pruebas unitarias corren sin base de datos, RabbitMQ ni Docker, en unos 4 segundos.

| Capa | Qué se aísla | Qué se simula | Pruebas |
|------|--------------|---------------|---------|
| **Dominio** | Las reglas de negocio puras (`Transfer`, `Account`, `OutboxEvent`) | Nada: son objetos sin dependencias | 28 |
| **Casos de uso** | La orquestación de cada operación (`TransferUseCaseImpl`, `AccountUseCaseImpl`, `EventUseCaseImpl`) | Todos los puertos de salida | 36 |
| **API** | El controller y el manejo de errores (`GlobalExceptionHandler`) | El caso de uso | 23 |
| **Adaptadores** | La traducción entre el dominio y la tecnología (repositorio, publicador, scheduler) | El repositorio de Spring Data, el cliente de RabbitMQ o el caso de uso | 9 |

### Detalle por clase

**Dominio**

| Clase de prueba | Comportamientos verificados |
|-----------------|------------------------------|
| `TransferTest` | El monto debe ser mayor que cero (0, -1 y -600000 se rechazan) · origen y destino diferentes · `authorize()` y `reject()` registran estado, motivo y fecha sin modificar la solicitud original · dos solicitudes son iguales si coinciden cuentas y monto, aunque el monto tenga otra escala (100000 y 100000.00) |
| `AccountTest` | Límite diario en sus bordes: alcanzarlo exactamente es válido y superarlo por 1 peso se rechaza · el límite configurable se aplica a las siguientes validaciones, y un límite cero o negativo se rechaza sin cambiar el actual · retiro y depósito mueven el saldo · un retiro mayor al saldo se rechaza sin modificarlo · los montos de depósito y retiro deben ser positivos |
| `OutboxEventTest` | Una transferencia autorizada genera un evento `PENDING` con su información · al publicarse queda `PUBLISHED` · cada falla programa un reintento a los 5, 10 y 20 segundos, y tras 3 reintentos queda `FAILED` · un evento publicado en un reintento conserva cuántos reintentos necesitó |

**Casos de uso**

| Clase de prueba | Comportamientos verificados |
|-----------------|------------------------------|
| `TransferUseCaseImplTest` | **RF01:** autoriza y mueve los saldos · **RF02/RF03:** cada regla incumplida (monto, misma cuenta, cuenta inexistente, límite superado, saldo insuficiente) produce una transferencia rechazada y guardada con su motivo · alcanzar exactamente el límite se autoriza · **RF04:** las dos cuentas se bloquean antes de revisar la solicitud · **RF05:** la misma referencia con los mismos datos devuelve la original, con datos distintos produce conflicto, y también en el caso simultáneo · **RF06:** página de recientes con sus metadatos, página vacía y búsqueda por referencia · **eventos:** solo una transferencia autorizada nueva registra evento, nunca una rechazada ni una repetida |
| `AccountUseCaseImplTest` | Crear una cuenta, y rechazar un número que ya existe sin guardar · depositar y retirar actualizan el saldo · un retiro mayor al saldo se rechaza sin guardar · operar o cambiar el límite de una cuenta inexistente se rechaza · el cambio de límite bloquea la cuenta antes de leerla y guardarla · un límite inválido no se guarda · eliminar una cuenta existente, y rechazar una inexistente |
| `EventUseCaseImplTest` | Un evento confirmado por RabbitMQ queda publicado · una falla programa un reintento · la falla del tercer reintento deja el evento `FAILED` · la hora actual se toma en cada ejecución del scheduler (regresión del error en que los eventos quedaban pendientes) · el resumen por estado funciona aunque RabbitMQ no esté disponible · paginación de eventos |

**API**

| Clase de prueba | Comportamientos verificados |
|-----------------|------------------------------|
| `TransferControllerTest` | Una transferencia nueva responde 201, también si fue rechazada (con su motivo) · una repetida responde 200 y un conflicto 422 · campos faltantes responden 400 por campo sin procesar nada · un monto no positivo no es un error de formato, sino que llega a las reglas de negocio · consulta por referencia 200/404 · paginación por defecto (página 0 de 20) y parámetros fuera de rango con 400 |
| `AccountControllerTest` | Crear responde 201, y un número existente 400 con el motivo · campos faltantes responden 400 por campo · un depósito responde el nuevo saldo y un retiro mayor al saldo responde 400 · montos faltantes, cero o negativos responden 400 sin mover saldos · cambio de límite y sus validaciones · cuenta inexistente 404 · eliminar 204 |
| `EventControllerTest` | Listado de eventos filtrado por estado, estado inválido con 400 y resumen con los mensajes en la cola |

**Adaptadores**

| Clase de prueba | Comportamientos verificados |
|-----------------|------------------------------|
| `TransferRepositoryAdapterTest` | **RF05:** cuando la base de datos rechaza una referencia duplicada, el adaptador lo traduce a un error del dominio, que el caso de uso usa para devolver la transferencia original · **RF02:** el acumulado diario se pide desde las 00:00 del día hasta antes de las 00:00 del siguiente · **RF03:** estado, motivo y fecha se conservan al guardar y leer · **RF06:** las recientes se piden por página, de la más nueva a la más antigua |
| `RabbitMqEventPublisherTest` | El mensaje se envía al exchange con su routing key, en JSON, persistente y con el id del evento (para que un consumidor pueda descartar duplicados) · se espera la confirmación de RabbitMQ, y si no llega la publicación falla para que el evento se reintente · el conteo de la cola, y que no falla si RabbitMQ no está disponible |
| `EventPublicationSchedulerTest` | Una ejecución que falla termina sin error, para que la publicación periódica no se detenga |

### Qué queda para las pruebas de integración

Estos comportamientos dependen de MySQL o RabbitMQ reales, por eso se prueban en `TransferIntegrationTest`:

| Comportamiento | Dónde vive | Prueba que lo demuestra |
|----------------|------------|-------------------------|
| El bloqueo de cuentas ante solicitudes simultáneas (RF04) | `SELECT … FOR UPDATE` de MySQL | 20 solicitudes simultáneas autorizan exactamente el límite |
| La restricción única de la referencia (RF05) | Índice `UNIQUE` de MySQL | La misma referencia enviada 10 veces a la vez deja un solo registro |
| Las consultas SQL (acumulado diario, eventos pendientes) | Consultas del repositorio | Reglas de punta a punta contra MySQL |
| Que el mensaje llegue realmente a la cola | RabbitMQ | Publicación de eventos con RabbitMQ real |

### Convenciones

- **Una clase de prueba por clase probada**, en el mismo paquete que la clase de producción.
- **Nombres que describen el comportamiento**, no el método: `rejectsAWithdrawalAboveTheBalanceWithoutSaving`. En dominio, API y adaptadores, además, `@DisplayName` en español, para que el reporte se lea como una especificación.
- **Estructura preparar → ejecutar → verificar** en cada prueba.
- **Pruebas parametrizadas para los bordes** de cada regla: el límite exacto, un peso por encima, cero y negativos.
- **`StepVerifier`** para los flujos reactivos: verifica el valor emitido o el error, y que el flujo termine.
- **Ante un rechazo se verifica también lo que no debe pasar**: que no se guarde nada, que no se mueva el saldo o que no se registre un evento.

## Pruebas E2E

Las 30 pruebas usan la aplicación completa en Chrome con [Playwright Test](https://github.com/microsoft/playwright). Cada archivo crea sus propias cuentas por la API y las elimina al terminar, así que no dependen del estado de las cuentas semilla. Los números de esas cuentas tienen 20 caracteres, el máximo permitido, para que las verificaciones de diseño cubran el caso más ancho.

| Archivo | Comportamientos verificados |
|---------|------------------------------|
| `layout.spec.ts` | La raíz abre *Nueva transferencia* · el menú muestra las funcionalidades y marca solo la vista activa · el modo oscuro tiene fondo negro y acentos violeta, y se conserva al recargar |
| `new-transfer.spec.ts` | Estado inicial del formulario · límite diario de la cuenta origen · **RF02:** misma cuenta no permitida · monto con puntos de miles y millones · **RF01/RF03:** una válida se autoriza, se muestra el resultado y el backend recibe el monto sin puntos · **RF02/RF03:** una que supera el límite se rechaza con su motivo · **RF05:** ante una falla de red se reintenta con la misma referencia y el backend no duplica |
| `transfer-history.spec.ts` | **RF06:** de la más reciente a la más antigua, columnas, sin scroll horizontal · *Ver detalle* consulta el backend y muestra toda la información · paginación y tamaño de página · búsqueda por referencia en la misma tabla sin recargar la página · referencia inexistente · *Limpiar* · la ruta anterior redirige |
| `accounts.spec.ts` | Crear cuenta y rechazar un número repetido · depósito, retiro y retiro mayor al saldo · editar el límite diario (verificado en el backend) · eliminar con confirmación · cerrar con Escape · sin scroll horizontal |
| `events.spec.ts` | El resumen coincide con el backend · una autorizada aparece como evento publicado y una rechazada no genera evento · filtro por estado · *Actualizar* |
| `mobile.spec.ts` | En 390 px: sin scroll horizontal · el menú se oculta, se abre con el botón y se cierra al navegar · el detalle muestra completos los números de cuenta de 20 caracteres sin salirse de la pantalla |

Toda prueba falla también si el navegador registra un error de consola que la prueba no provocó a propósito.

## Requerimientos y pruebas que los demuestran

| Requerimiento | Comportamiento verificado | Prueba |
|---------------|---------------------------|--------|
| **RF01** Procesar una transferencia | Se autoriza, se persiste con fecha de procesamiento y mueve los saldos | `TransferIntegrationTest` › *una transferencia válida se autoriza…* |
| | Responde 201 con el resultado | `TransferControllerTest` › *RF01* |
| **RF02** Reglas de negocio | Monto mayor que cero (0, -1, -600000) | `TransferTest` › *el monto debe ser mayor que cero* |
| | Cuentas origen y destino diferentes | `TransferTest`, `different-accounts.validator.spec` |
| | Cuenta inexistente | `TransferUseCaseImplTest`, `TransferIntegrationTest` |
| | El acumulado del día se calcula desde las 00:00 hasta antes de las 00:00 del día siguiente | `TransferRepositoryAdapterTest` |
| | Límite diario: alcanzarlo exactamente es válido, superarlo por 1 peso se rechaza | `AccountTest` › *límite diario*, `TransferIntegrationTest` › *se autoriza hasta alcanzar exactamente el límite…* |
| | El límite es por cuenta origen | `TransferIntegrationTest` › *el límite es por cuenta origen…* |
| | Campos requeridos faltantes responden 400 por campo | `TransferControllerTest` › *los campos requeridos faltantes…* |
| **RF03** Conservar el resultado | Estado, razón y fecha quedan registrados (autorizada y rechazada) | `TransferTest` › *resultado*, `TransferRepositoryAdapterTest`, `TransferIntegrationTest` › *rechazada se persiste con su razón…* |
| | Una rechazada no incrementa el acumulado diario | `TransferIntegrationTest` › *una solicitud rechazada no incrementa el acumulado diario* |
| | La interfaz muestra el motivo en español | `rejection-reason.pipe.spec`, `new-transfer-page.component.spec` |
| **RF04** Solicitudes simultáneas | 20 solicitudes simultáneas de 1.000.000 autorizan exactamente 5.000.000 | `TransferIntegrationTest` › *20 transferencias simultáneas…* |
| | Transferencias cruzadas simultáneas sin bloqueos mutuos y sin perder dinero | `TransferIntegrationTest` › *transferencias cruzadas simultáneas…* |
| | Las cuentas se bloquean antes de verificar la solicitud | `TransferUseCaseImplTest` › *locksBothAccountsBeforeCheckingTheRequest* |
| **RF05** Referencias repetidas | Mismos datos: se devuelve el original (200), una sola fila, saldo y acumulado afectados una sola vez | `TransferIntegrationTest`, `TransferControllerTest`, `TransferUseCaseImplTest` |
| | Datos diferentes: 422 y la operación original intacta | `TransferIntegrationTest` › *…datos diferentes responde 422…* |
| | La misma referencia 10 veces a la vez: un solo registro | `TransferIntegrationTest` › *la misma referencia enviada 10 veces a la vez…* |
| | La restricción única de la base de datos se traduce a un error del dominio | `TransferRepositoryAdapterTest` |
| | Montos equivalentes con distinta escala (100000 y 100000.00) son la misma solicitud | `TransferTest` › *comparación de solicitudes repetidas* |
| | La interfaz reintenta con la misma referencia ante una falla de red | `new-transfer-page.component.spec`, E2E |
| **RF06** Consultar transferencias | Por referencia (200 / 404) | `TransferControllerTest`, `TransferIntegrationTest` |
| | Recientes paginadas, de la más reciente a la más antigua | `TransferIntegrationTest` › *las recientes se paginan…*, `TransferUseCaseImplTest`, `TransferRepositoryAdapterTest` |
| | Parámetros de paginación inválidos responden 400 | `TransferControllerTest` › *tamaño de página fuera de 1..100…* |
| **Datos semilla** y regla de saldo | Al iniciar no hay transferencias y las cuentas semilla recuperan saldo y límite | `TransferIntegrationTest` › *al iniciar no hay transferencias…* |
| | En un día, el límite diario se agota antes que el saldo | `TransferIntegrationTest` › *en un día, una cuenta no puede gastar más que su saldo inicial…* |
| **Cuentas** | Crear (número duplicado rechazado), depositar, retirar (saldo insuficiente rechazado sin guardar), eliminar, cuenta inexistente | `AccountTest`, `AccountUseCaseImplTest`, `AccountControllerTest` |
| **Límite diario configurable** | El nuevo límite aplica a las transferencias siguientes; un límite no positivo se rechaza | `AccountTest`, `AccountUseCaseImplTest`, `AccountControllerTest`, `TransferIntegrationTest` › *Límite diario configurable* |
| | El cambio bloquea la cuenta antes de modificarla | `AccountUseCaseImplTest` › *updatesTheDailyLimitLockingTheAccountFirst* |
| | El límite configurado se conserva al reiniciar | `TransferIntegrationTest` › *Datos semilla* |
| | Edición desde la interfaz | `daily-limit-form.component.spec`, E2E |
| **Punto opcional** RabbitMQ | Solo las autorizadas publican; el mensaje llega a la cola con su contenido y `messageId` | `TransferIntegrationTest` › *publicación de eventos en RabbitMQ*, `TransferUseCaseImplTest` |
| | 20 solicitudes simultáneas generan exactamente 5 eventos | `TransferIntegrationTest` › *con 20 solicitudes simultáneas se publican exactamente 5 eventos* |
| | El mensaje es JSON persistente con el id del evento; sin confirmación de RabbitMQ la publicación falla y se reintenta | `RabbitMqEventPublisherTest` |
| | Reintentos con espera creciente y `FAILED` tras 3 reintentos | `OutboxEventTest`, `EventUseCaseImplTest` |
| | La hora de publicación se evalúa en cada ejecución del scheduler | `EventUseCaseImplTest` › *takesTheCurrentTimeOnEverySubscriptionOfTheSameMono* |
| | Una ejecución fallida no detiene las siguientes | `EventPublicationSchedulerTest` |
| | Vista de administración: resumen, filtro por estado | `event-page.component.spec`, `event.service.spec`, E2E |
| **RF07** Interfaz Angular | Registrar, ver el resultado, consultar, buscar y ver el detalle desde el navegador | E2E (`e2e/*.spec.ts` de iasbank-front) |
| | El monto muestra puntos de miles y millones mientras se escribe, y al backend llega el número sin puntos | `amount-input.directive.spec`, E2E |

## Verificación de las propias pruebas

Para comprobar que las pruebas de concurrencia realmente detectan errores, se eliminó temporalmente el bloqueo de cuentas (RF04) y se ejecutaron de nuevo: las pruebas de límite diario simultáneo y de transferencias cruzadas **fallaron**, demostrando que protegen esa regla. Luego se restauró el código.

## Cómo ejecutarlas

**Backend** (unitarias, API e integración). Las pruebas de integración requieren Docker; si no está disponible se omiten automáticamente.

```bash
cd IASBank
./gradlew test                                                        # todas (118)
./gradlew test --tests 'com.iasbank.core.*' --tests 'com.iasbank.adapters.*'  # solo unitarias (96, ~4 s, sin Docker)
./gradlew test --tests '*AccountUseCaseImplTest'                       # una clase
```

El reporte HTML queda en `build/reports/tests/test/index.html`, con los nombres en español de cada prueba.

**Frontend** (unitarias):

```bash
cd IASBankFront
npm test
```

**E2E** con Playwright Test (requiere la aplicación corriendo con Docker y Google Chrome instalado):

```bash
docker compose up --build -d  # desde iasbank-back
cd iasbank-front
npm run e2e                   # todas
npm run e2e:report            # reporte HTML de la última ejecución
```

Cómo están organizadas y cómo manejan los datos: [README del frontend](https://github.com/JEdwardGs17/iasbank-front#pruebas).
