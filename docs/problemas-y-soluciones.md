# Problemas y Soluciones

Este documento explica los problemas encontrados durante el desarrollo de IAS Bank, por qué ocurrían y cómo se resolvieron. Está escrito para entender el razonamiento detrás de cada decisión, sin entrar en detalles de código.

---

## RF04 - Solicitudes simultáneas sobre una misma cuenta

### Contexto

El core de transferencias puede recibir varias solicitudes prácticamente al mismo tiempo para una misma cuenta. Cada solicitud, por sí sola, se procesaba correctamente. El problema aparecía cuando varias llegaban juntas.

Para comprobarlo se hizo una prueba real enviando solicitudes en paralelo contra el sistema. Se encontraron tres problemas, todos con la misma causa de fondo: **varias solicitudes trabajaban sobre las mismas cuentas al mismo tiempo, sin ningún tipo de coordinación entre ellas.**

---

### Problema 1: se superaba el límite diario

**Qué pasaba**

Se enviaron 20 transferencias de 1.000.000 al mismo tiempo desde una cuenta con límite diario de 5.000.000. Lo correcto era autorizar 5 y rechazar 15, pero el sistema autorizó **14.000.000**, casi el triple del límite permitido.

**Por qué pasaba**

Para decidir si una transferencia se autoriza, el sistema sigue tres pasos: consulta cuánto ha transferido la cuenta en el día, verifica que la nueva transferencia no supere el límite y, si todo está bien, la registra.

El problema es que entre el primer paso y el último pasa un pequeño intervalo de tiempo. Cuando las solicitudes llegan juntas, todas hacen la consulta en ese intervalo, antes de que alguna alcance a registrarse. Todas ven "la cuenta no ha transferido nada hoy", todas concluyen que hay cupo y todas se autorizan.

Es como si varias personas consultaran al mismo tiempo el cupo disponible de una tarjeta, todas vieran que hay cupo y todas hicieran la compra: cada una tomó su decisión con información que ya estaba desactualizada. A esta situación se le conoce como **condición de carrera**.

**Cómo se resolvió**

Se hizo que las solicitudes sobre una misma cuenta **se atiendan una a la vez**, como una fila en la ventanilla de un banco.

Cuando llega una solicitud, lo primero que hace es reservar las cuentas involucradas. Mientras las tiene reservadas, cualquier otra solicitud sobre esas mismas cuentas debe esperar su turno. Cuando la primera termina y su resultado queda guardado, libera las cuentas y la siguiente entra; esta ya ve el consumo actualizado y decide con información correcta.

La reserva se hace en la base de datos y no dentro de la aplicación. La razón es que el sistema puede ejecutarse en varias instancias al mismo tiempo para atender más carga. Si cada instancia tuviera su propia fila, dos solicitudes que caen en instancias distintas no se verían entre sí y el problema volvería. La base de datos es el único punto que todas las instancias comparten, así que la fila es una sola para todas.

**Resultado**

Las mismas 20 solicitudes simultáneas dieron exactamente **5 autorizadas (5.000.000) y 15 rechazadas** por superar el límite diario.

---

### Problema 2: bloqueo mutuo en transferencias cruzadas

**Qué pasaba**

Se enviaron al mismo tiempo transferencias en ambos sentidos entre dos cuentas (de A hacia B y de B hacia A). La mitad de las solicitudes terminó en error.

**Por qué pasaba**

Una transferencia necesita trabajar con dos cuentas a la vez. Si una solicitud toma primero la cuenta A y luego quiere la B, mientras otra toma primero la B y luego quiere la A, cada una se queda esperando la cuenta que tiene la otra. Ninguna puede avanzar.

Es como dos personas en un pasillo angosto que se encuentran de frente y cada una espera que la otra se mueva primero. A esta situación se le conoce como **deadlock** o bloqueo mutuo; la base de datos la detecta y cancela una de las operaciones, que es el error que se observaba.

**Cómo se resolvió**

Las cuentas se reservan **siempre en el mismo orden** (por número de cuenta), sin importar cuál es la de origen y cuál la de destino. Así, las dos solicitudes intentan tomar primero la misma cuenta: una lo logra y la otra simplemente espera su turno. Como nadie queda sosteniendo una cuenta mientras espera la otra, el bloqueo mutuo no puede ocurrir.

Además, se reservan **las dos cuentas** y no solo la de origen. Si solo se reservara el origen, dos transferencias podrían modificar al mismo tiempo el saldo de la cuenta destino y una borraría el cambio de la otra.

**Resultado**

Las 20 transferencias cruzadas se procesaron correctamente, sin ningún error.

---

### Problema 3: la misma solicitud repetida al mismo tiempo generaba errores

**Qué pasaba**

Se envió la misma solicitud (con la misma referencia) 10 veces al mismo tiempo, simulando por ejemplo un cliente que reintenta. El sistema debía responder siempre con el mismo resultado, pero 8 de las 10 respuestas fueron error.

**Por qué pasaba**

Para no procesar dos veces la misma solicitud, el sistema primero verifica si esa referencia ya existe. Cuando las 10 llegaban juntas, todas verificaban al mismo tiempo, ninguna la encontraba (aún no se había guardado) y todas intentaban registrarla. La base de datos, que no permite referencias repetidas, aceptaba solo una y rechazaba las demás con un error.

**Cómo se resolvió**

En dos niveles:

1. La fila por cuenta del Problema 1 ya hace que la mayoría de estas solicitudes esperen su turno y, al entrar, encuentren la referencia ya registrada, devolviendo el resultado original.
2. Para los casos en que aun así dos solicitudes choquen, el sistema ya no lo trata como un error: entiende que otra solicitud con la misma referencia ganó, deshace su propio intento y responde con el resultado de la ganadora.

La regla de la base de datos que impide referencias repetidas funciona como la última garantía: pase lo que pase, una misma solicitud nunca queda registrada dos veces.

**Resultado**

Las 10 solicitudes recibieron exactamente la misma respuesta y en la base de datos quedó un único registro.

---

### Ajustes complementarios

- **Lectura de información siempre actualizada.** Por defecto, la base de datos puede mostrarle a una operación una "fotografía" de los datos tomada cuando la operación empezó. Se configuró para que cada lectura vea siempre lo último que ha sido confirmado. Así, cuando una solicitud obtiene su turno, el consumo diario que consulta incluye lo que hicieron las solicitudes anteriores.
- **Consulta rápida del consumo diario.** Se agregó un índice a la tabla de transferencias para que el cálculo de cuánto ha transferido una cuenta en el día se haga de forma directa, sin recorrer todas las transferencias. Esto mantiene corto el tiempo de cada turno y, por lo tanto, corta la espera de la fila.

---

### Alternativas evaluadas

| Alternativa | Por qué se descartó |
|-------------|---------------------|
| Controlar la fila dentro de la aplicación | Solo funciona si existe una única instancia del sistema; al escalar a varias, cada una tendría su propia fila y el problema volvería. |
| Usar un servicio externo de coordinación (por ejemplo Redis) | Resuelve el problema, pero agrega infraestructura adicional y un nuevo punto de falla que no se justifica para este caso. |
| Dejar que todas avancen y reintentar las que choquen | Cuando muchas solicitudes apuntan a la misma cuenta, que es justo el escenario de este requerimiento, los reintentos se acumulan y el rendimiento empeora. |
| Llevar un contador separado del consumo diario | Duplica información que ya se obtiene de las transferencias registradas, con el riesgo de que ambas versiones dejen de coincidir. |
| Usar más hilos o procesamiento asíncrono | El sistema ya es asíncrono. Más paralelismo hace que más solicitudes se crucen, por lo que agrava la condición de carrera en vez de resolverla. |

---

### Resumen

| Escenario | Antes | Después |
|-----------|-------|---------|
| 20 solicitudes de 1.000.000 desde la misma cuenta (límite 5.000.000) | 14.000.000 autorizados | 5.000.000 autorizados, 15 rechazadas |
| 20 transferencias cruzadas entre dos cuentas | 10 errores | 20 procesadas sin errores |
| Misma solicitud enviada 10 veces al mismo tiempo | 8 errores | 10 respuestas idénticas, un solo registro |

En todos los casos los saldos finales cuadraron exactamente: el dinero total entre las cuentas se mantuvo igual.

**Costo de la solución:** solo esperan su turno las solicitudes que involucran las mismas cuentas. Las transferencias entre cuentas distintas se siguen procesando en paralelo, y la espera no detiene al servidor, que continúa atendiendo otras peticiones mientras tanto.

---

## RF05 - Referencias repetidas

### Contexto

Cada solicitud de transferencia trae una referencia única que la identifica. Esa misma referencia puede llegar más de una vez: por ejemplo, cuando el cliente no recibe respuesta por un problema de red y vuelve a enviar la solicitud. El sistema debe reconocerla para no ejecutar dos veces la misma transferencia.

Desde RF01 el sistema ya evitaba procesar dos veces una referencia, y desde RF04 también lo hacía cuando las repeticiones llegaban al mismo tiempo. Al revisar este requerimiento se encontraron dos problemas en cómo se respondía.

---

### Problema 1: una referencia repetida con datos diferentes se aceptaba en silencio

**Qué pasaba**

Se envió una transferencia de 100.000 hacia la cuenta CTA-1002 con la referencia RF05-X. Luego se envió la misma referencia, pero esta vez por 900.000 hacia la cuenta CTA-1001. El sistema respondió "transferencia creada y autorizada", devolviendo en realidad la primera operación.

**Por qué era un problema**

El cliente pidió una cosa y el sistema le respondió como si la hubiera hecho, cuando en realidad le estaba mostrando otra operación distinta. Quien no revise el detalle de la respuesta creería que sus 900.000 fueron transferidos a CTA-1001, y no es así. En un sistema financiero, una respuesta ambigua sobre el dinero es tan grave como un error.

Lo más probable en esta situación es que se trate de un error del cliente (por ejemplo, reutilizar una referencia por equivocación), y lo correcto es avisarle, no ocultarlo.

**Cómo se resolvió**

Cuando llega una referencia que ya existe, el sistema compara la nueva solicitud con la original: cuenta origen, cuenta destino y monto.

- Si son iguales, es un reintento legítimo y se responde con el resultado original.
- Si son diferentes, el sistema **rechaza la nueva solicitud con un error claro** indicando que esa referencia ya fue usada con otros datos. No se procesa nada y la operación original queda exactamente como estaba.

Este comportamiento sigue la práctica estándar de la industria para este tipo de referencias (conocidas como *claves de idempotencia*), la misma que aplican plataformas de pago reconocidas.

**Resultado**

La misma referencia con otro monto o con otra cuenta destino ahora recibe un rechazo explícito, y la transferencia original no se modifica.

---

### Problema 2: no se distinguía una solicitud nueva de una repetida

**Qué pasaba**

Tanto una transferencia procesada por primera vez como una repetición recibían exactamente la misma respuesta: "transferencia creada".

**Por qué era un problema**

El cliente no tenía forma de saber si su solicitud acababa de ejecutarse o si ya se había ejecutado antes. Esto es importante justamente en el caso de un reintento: el cliente necesita confirmar que la transferencia ocurrió una sola vez.

**Cómo se resolvió**

El sistema ahora responde de forma distinta en cada caso:

- **Solicitud nueva:** "transferencia creada", con el resultado del procesamiento.
- **Solicitud repetida:** "solicitud ya procesada", con el resultado original. No se crea nada nuevo.

En la aplicación web esto se refleja con el mensaje *"Esta solicitud ya había sido procesada"* junto al resultado original.

---

### Reintento seguro desde la aplicación web

Si la aplicación envía una transferencia y no recibe respuesta (por ejemplo, por una caída momentánea de la red), no puede saber si la transferencia se procesó o no. Si el usuario la vuelve a llenar, se generaría una referencia nueva y, si la primera sí se había procesado, el dinero se transferiría dos veces.

Para evitarlo, en ese caso la aplicación ofrece un botón **Reintentar**, que vuelve a enviar exactamente la misma solicitud con la misma referencia. Si la primera se había procesado, el sistema responde con el resultado original; si no, la procesa por primera vez. En ningún caso se duplica.

---

### Resumen

| Escenario | Antes | Después |
|-----------|-------|---------|
| Misma referencia, mismos datos | Respuesta de "creada" sin indicar que era repetida | Se indica que ya fue procesada y se entrega el resultado original |
| Misma referencia, datos diferentes | Se devolvía la original como si se hubiera procesado lo pedido | Rechazo explícito; la operación original se conserva intacta |
| Misma referencia con datos distintos enviada al mismo tiempo | — | Una sola se registra; las que coinciden reciben el resultado original y las que no, el rechazo |
| Reintento tras una falla de red en la aplicación | El usuario debía volver a llenar el formulario (nueva referencia, riesgo de duplicar) | Botón Reintentar con la misma referencia, sin riesgo de duplicar |

En todos los casos se verificó que las repeticiones no incrementan el acumulado diario: una transferencia enviada tres veces cuenta una sola vez para el límite.

---

## RF06 - Consulta de transferencias

### Problema: la consulta devolvía todas las transferencias de una sola vez

**Qué pasaba**

La consulta de transferencias recientes que existía desde RF01 devolvía la tabla completa en cada llamada, y la aplicación web mostraba todas las filas en pantalla.

**Por qué era un problema**

Con pocas transferencias no se nota, pero un core de transferencias procesa miles por día. Cada consulta tendría que leer, enviar por la red y dibujar en pantalla todo el historial, y se volvería más lenta cada día que pasa. Además, cualquiera podría pedir un volumen enorme de información en una sola llamada y afectar al resto de usuarios.

**Cómo se resolvió**

Aunque el requerimiento no exige paginación, se incorporó porque es lo que permite que esta consulta funcione igual con cien o con un millón de transferencias:

- **Consulta por páginas.** En lugar de traer todo, se pide una página a la vez (por ejemplo, las 10 más recientes, luego las 10 siguientes). La respuesta indica cuántas transferencias y cuántas páginas hay en total.
- **Tamaño máximo por página.** Nadie puede pedir más de 100 transferencias en una sola consulta.
- **Orden estable.** Las transferencias se ordenan de la más reciente a la más antigua y, si dos tienen exactamente la misma fecha, se desempata por su número de registro. Sin este desempate, al pasar de página una transferencia podría aparecer dos veces o no aparecer nunca.
- **Índice por fecha de procesamiento.** La base de datos mantiene las transferencias organizadas por fecha, como un libro con índice. Para mostrar las más recientes va directo al final del índice, en vez de revisar y ordenar toda la tabla en cada consulta. Se verificó que la base de datos efectivamente usa este índice.

En la aplicación web, el historial ahora se muestra por páginas con botones *Anterior* y *Siguiente*, y se agregó un buscador para consultar una transferencia por su referencia y ver su detalle completo.

**Resultado**

| Escenario | Antes | Después |
|-----------|-------|---------|
| Consultar transferencias recientes | Se devolvía la tabla completa | Se devuelve una página (por defecto 20, máximo 100) con el total disponible |
| Ordenar las recientes | La base de datos ordenaba toda la tabla en cada consulta | Se recorre el índice por fecha, sin ordenar la tabla |
| Consultar una transferencia específica | No era posible | Búsqueda directa por referencia, con su detalle completo |

---

## RF07 - Interfaz Angular

Al validar la interfaz en un navegador real, recorriendo cada pantalla como lo haría un usuario, se encontraron problemas que no aparecían al compilar ni al probar el backend por separado.

### Problema 1: el buscador por referencia recargaba la página

**Qué pasaba**

Al escribir una referencia y presionar *Buscar*, la página se recargaba por completo y no mostraba ningún resultado.

**Por qué pasaba**

En una página web, un formulario tiene un comportamiento por defecto: al enviarse, recarga la página. Angular evita esto y toma el control del envío, pero solo cuando el formulario está correctamente asociado a él. El buscador no lo estaba, así que el navegador aplicaba su comportamiento por defecto antes de que la búsqueda pudiera ejecutarse.

**Cómo se resolvió**

Se asoció correctamente el formulario a Angular, de forma que el envío ejecuta la búsqueda sin recargar la página. Además, el botón ahora se deshabilita si la referencia está vacía o solo contiene espacios.

---

### Problema 2: la pantalla de cuentas aparecía vacía

**Qué pasaba**

Al entrar a *Cuentas*, la tabla mostraba "No hay cuentas registradas", aunque sí existían. Las cuentas aparecían solo después de hacer clic en cualquier parte de la pantalla.

**Por qué pasaba**

La aplicación usa la forma más moderna y eficiente de Angular para actualizar la pantalla: solo redibuja cuando se le avisa explícitamente que un dato cambió. La pantalla de transferencias ya trabajaba así, pero la de cuentas guardaba sus datos de la forma tradicional, que no genera ese aviso. Los datos llegaban del servidor, pero la pantalla no se enteraba hasta que un clic la obligaba a redibujarse.

**Cómo se resolvió**

La pantalla de cuentas se actualizó para manejar sus datos de la misma forma que el resto de la aplicación, de modo que cualquier cambio se refleja de inmediato.

---

### Problema 3: crear una cuenta desde la interfaz siempre fallaba

**Qué pasaba**

Al completar el formulario de nueva cuenta, el servidor siempre rechazaba la solicitud.

**Por qué pasaba**

Toda cuenta necesita un límite diario, y el servidor lo exige. El formulario de la interfaz no tenía ese campo, por lo que la solicitud llegaba incompleta.

**Cómo se resolvió**

Se agregó el campo *Límite diario* al formulario, con 5.000.000 COP como valor sugerido, y la tabla de cuentas ahora muestra el límite de cada una.

---

### Problema 4: los números de cuenta largos se salían de la pantalla

**Qué pasaba**

Un número de cuenta puede tener hasta 20 caracteres, pero las cuentas de ejemplo tienen 8 (`CTA-1001`). Con números de 12 caracteres o más, las tablas de cuentas y de transferencias quedaban más anchas que la pantalla y aparecía una barra de desplazamiento horizontal. En el celular, el detalle de una transferencia también se salía de la pantalla.

**Cómo se detectó**

Las pruebas automáticas en el navegador crean sus propias cuentas, con números más largos que los de ejemplo, y verifican que nada se salga de la pantalla. Esa verificación falló.

**Cómo se resolvió**

En las tablas, un número que no cabe se recorta con "…" y el número completo aparece al pasar el mouse. En el resultado y en el detalle de una transferencia, que deben mostrar toda la información, el número se muestra completo y pasa a una segunda línea si no cabe.

**Resultado**

Con números de 20 caracteres, el máximo permitido, las tablas caben en la pantalla y el detalle se ve completo en el celular. Las pruebas automáticas ahora siempre usan números de 20 caracteres, para que este problema no vuelva a aparecer.

### Mejora de la interfaz

Más allá de corregir estos problemas, la interfaz se rediseñó para que cada funcionalidad sea fácil de encontrar y de entender:

- **Menú lateral** con una pantalla para cada funcionalidad: nueva transferencia, transferencias y cuentas.
- **Consulta en un solo lugar:** en la pantalla de transferencias se navega el historial, se busca por referencia dentro de la misma tabla y cada fila tiene un botón *Ver detalle* con toda la información de la transferencia.
- **Resultado claro** de cada transferencia: un recuadro verde o rojo indica si fue autorizada o rechazada, junto con el motivo, el monto, las cuentas, la referencia y la fecha.
- **Alertas** que confirman cada acción (crear, editar, eliminar o consultar) y avisan de cualquier error en lenguaje claro.
- **Modo claro y oscuro**, que se recuerda entre visitas.
- **Tabla legible** que cabe completa en pantalla, con estados de color y montos alineados.
- **Adaptable a celulares**, con el menú desplegable.

### Cómo se verifica

Cada cambio se valida con una prueba automática que abre la aplicación en un navegador real y la recorre completa: navega por todas las pantallas, cambia el tema, registra transferencias autorizadas y rechazadas, simula una caída de red para comprobar el reintento seguro, pagina el historial, consulta referencias existentes e inexistentes, administra cuentas y revisa la vista en celular. La versión final superó las 69 verificaciones de esta prueba.

---

## Pruebas automatizadas

### Problema: la concurrencia no se puede demostrar con pruebas simuladas

**Qué pasaba**

Las pruebas existentes de los casos de uso reemplazaban la base de datos por objetos simulados. Eso sirve para verificar la lógica, pero las protecciones más importantes del sistema viven en la base de datos: el bloqueo de cuentas que ordena las solicitudes simultáneas (RF04) y la restricción que impide registrar dos veces la misma referencia (RF05). Con una base de datos simulada, esas pruebas pasarían aunque las protecciones no existieran.

**Cómo se resolvió**

Se agregaron pruebas de integración que levantan automáticamente una base de datos MySQL real en un contenedor de Docker, arrancan el sistema completo y le envían solicitudes reales, incluidas ráfagas de solicitudes simultáneas. Luego revisan directamente en la base de datos que el resultado sea el correcto: cuánto se autorizó, cuántos registros existen y cuánto dinero tiene cada cuenta.

Para confirmar que estas pruebas son útiles, se eliminó a propósito el bloqueo de cuentas y se ejecutaron de nuevo: fallaron, demostrando que detectan si alguien rompe esa protección en el futuro.

### Problema: la herramienta de pruebas no era compatible con la versión de Docker

**Qué pasaba**

La versión de la herramienta que crea la base de datos de prueba (Testcontainers), incluida por defecto con Spring Boot 3.2, usa una forma de comunicarse con Docker que las versiones recientes de Docker ya no aceptan.

**Cómo se resolvió**

Se fijó una versión más reciente de la herramienta, compatible con Docker actual. Además, si las pruebas se ejecutan en un equipo sin Docker, las de integración se omiten automáticamente en lugar de hacer fallar todo el proceso.

---

## Saldo suficiente y reinicio de la aplicación

### Problema: los saldos no coincidían con el historial después de reiniciar

**Qué pasaba**

El enunciado asume que al iniciar la aplicación no hay transferencias autorizadas en el día, por eso cada inicio borraba las transferencias. Pero los saldos de las cuentas no se restauraban: conservaban el efecto de transferencias que ya no existían. Por ejemplo, después de un reinicio una cuenta tenía la mitad de su saldo aunque su historial estaba vacío.

**Por qué era un problema**

Además de la incoherencia en sí, el sistema valida que la cuenta origen tenga saldo suficiente. Tras varios reinicios, una cuenta podía quedarse sin saldo y empezar a rechazar transferencias que, según el enunciado, debían autorizarse.

**Cómo se resolvió**

Cada inicio ahora es un día limpio completo: se borran las transferencias **y** las cuentas semilla recuperan su saldo y su límite iniciales. Así, el saldo de cada cuenta siempre corresponde a su historial.

Se decidió mantener la validación de saldo suficiente porque un sistema que mueve dinero no debe dejar cuentas en negativo. Con un saldo inicial de 10.000.000 y un límite diario de 5.000.000, una cuenta agota su límite mucho antes que su saldo, por lo que esta regla nunca interfiere con los escenarios del enunciado. Además, el límite diario se revisa antes que el saldo.

**Resultado**

Al reiniciar, las cuentas vuelven a 10.000.000 y no hay transferencias. Las pruebas automáticas verifican tanto el arranque limpio como que el límite diario siempre se alcanza antes que el saldo.

---

## Fecha y hora de las transferencias

### Problema: las transferencias quedaban registradas con 5 horas de adelanto

**Qué pasaba**

Una transferencia hecha a las 8:14 p. m. del 4 de noviembre aparecía registrada a la 1:14 a. m. del 5 de noviembre.

**Por qué pasaba**

El backend es quien pone la fecha y la hora a cada transferencia y a cada evento. Dentro de Docker, el backend no tenía configurada la zona horaria de Colombia y usaba la hora universal (UTC), que va 5 horas por delante.

**Por qué era un problema**

Además de mostrar una hora equivocada, el límite diario se calcula sobre las transferencias "del día". Con la hora universal, el día cambiaba a las 7:00 p. m. hora de Colombia: lo transferido desde esa hora contaba para el día siguiente, y el límite se reiniciaba antes de tiempo.

**Cómo se resolvió**

Se configuró el backend con la zona horaria de Colombia. Como todas las fechas de la aplicación las pone el backend, con este único ajuste quedan correctas la fecha de las transferencias, la de los eventos y el corte del día para el límite diario.

**Resultado**

Las transferencias y los eventos quedan registrados con la hora actual de Colombia, y el límite diario se reinicia a la medianoche.

---

## Punto opcional - Eventos en RabbitMQ

### Problema 1: guardar y publicar no se pueden hacer "a la vez"

**Qué pasaba**

Al autorizar una transferencia hay que hacer dos cosas en dos sistemas distintos: guardarla en la base de datos y avisar a RabbitMQ. No existe una forma práctica de que ambas ocurran juntas o no ocurran: si se avisa después de guardar y RabbitMQ está caído, el aviso se pierde; si se avisa antes y el guardado falla, se anuncia una transferencia que no existe.

**Cómo se resolvió**

El aviso se escribe primero en una tabla de la base de datos (llamada *outbox*), en la misma operación que guarda la transferencia: o quedan ambos, o ninguno. Luego, un proceso separado lee esa tabla y envía los avisos a RabbitMQ, y solo los marca como enviados cuando RabbitMQ confirma que los recibió. Si RabbitMQ no responde, el aviso espera y se reintenta hasta 3 veces.

**Resultado**

Al detener RabbitMQ, los avisos no se pierden: quedan en reintento y, si se agotan los 3 reintentos, quedan marcados como fallidos con el error, visibles en la vista de administración.

### Problema 2: el publicador nunca encontraba los eventos nuevos

**Qué pasaba**

En las primeras pruebas, los eventos se guardaban pero se quedaban siempre como *pendientes*, aunque el proceso de publicación se ejecutaba cada pocos segundos sin errores.

**Por qué pasaba**

El proceso programado preparaba su búsqueda una sola vez, al arrancar la aplicación, y la repetía cada vez sin actualizarla. La búsqueda decía "eventos pendientes cuya hora de envío ya llegó", pero "ya" había quedado congelado en el momento del arranque. Cualquier evento creado después parecía estar programado para el futuro y nunca se enviaba.

**Cómo se resolvió**

La búsqueda ahora toma la hora actual en cada ejecución. Se agregó una prueba automática que ejecuta dos veces el mismo proceso y verifica que cada ejecución consulte con su propia hora, para que este error no vuelva a ocurrir.

**Cómo se detectó**

Las pruebas de integración con un RabbitMQ real fallaron: el evento nunca pasaba a *publicado*. Se aisló el problema probando por separado cada paso (la búsqueda, la publicación y el proceso programado) hasta encontrar el que fallaba.

