# Uso de Inteligencia Artificial

**Herramienta utilizada:** Claude Code (asistente de programación de Anthropic, modelos Claude), en la terminal y en el editor VS Code.

## Distribución del trabajo

**El desarrollador definió y decidió; la IA ejecutó bajo su dirección.**

| Responsabilidad del desarrollador | Apoyo de la IA |
|-----------------------------------|----------------|
| **Definición de la arquitectura**: arquitectura hexagonal con `core` y `adapters`, puertos de entrada y salida, y estructura de paquetes basada en su propia convención de proyectos hexagonales | Generar y reorganizar el código según esa estructura |
| **Definición del stack y del scaffolding**: Spring Boot con Gradle, MySQL, WebFlux, Angular con arquitectura modular, Docker, nombres de los proyectos | Crear los proyectos y la configuración con esas especificaciones |
| **Convenciones de diseño**: un caso de uso por tipo (cuentas, transferencias), mensajes HTTP agrupados por tipo, nombres en inglés en el código y documentación en español, inyección por constructor | Aplicarlas en todo el código |
| **Alcance**: entrega de los requerimientos uno a uno, implementando solo lo que cada uno pide, en backend y frontend | Implementar cada requerimiento |
| **Decisiones de negocio y producto**: mantener la regla de saldo suficiente, paginación como valor agregado, RabbitMQ solo como publicador con vista de administración y máximo 3 reintentos, límite diario configurable por cuenta, diseño de la interfaz (menú lateral, modo oscuro negro con acentos violeta, alertas), consulta y detalle en una misma vista, formato del monto con separadores de miles | Proponer alternativas y realizar los cambios |
| **Herramientas y entrega**: pruebas de extremo a extremo con Playwright Test, repositorios separados para backend y frontend, historial de commits por requerimiento | Configurar las herramientas y organizar el historial |
| **Revisión y aceptación** de cada cambio, y detección de errores en la interfaz | Ejecutar pruebas y corregir lo encontrado |

## Actividades en las que se utilizó

| Actividad | Uso |
|-----------|-----|
| Estructura inicial | Creación de los proyectos Spring Boot (Gradle) y Angular, y de la configuración con Docker Compose |
| Implementación | Desarrollo de RF01 a RF07 en backend y frontend, siguiendo los requerimientos entregados uno a uno |
| Punto opcional y ajustes | Publicación de eventos en RabbitMQ con su vista de administración, límite diario configurable, formato del monto, zona horaria de Colombia y números de cuenta largos en la interfaz |
| Arquitectura y refactorización | Reorganización hacia la arquitectura hexagonal definida por el desarrollador |
| Análisis técnico | Evaluación de alternativas para concurrencia (RF04), idempotencia (RF05) y paginación (RF06) |
| Pruebas | Pruebas unitarias, de API, de integración con MySQL y RabbitMQ reales, del frontend y de extremo a extremo con Playwright Test |
| Verificación | Ejecución de pruebas funcionales de los endpoints y recorridos en navegador después de cada cambio |
| Documentación | README con las instrucciones para ejecutar la solución, especificación de negocio, decisiones técnicas (ADR), problemas y soluciones, estrategia de pruebas y este documento |
| Entrega | Docker Compose para levantar la solución con un solo comando, organización del historial de commits por requerimiento y publicación en los repositorios de GitHub |

## Resultados aprovechados

- El código de backend y frontend que forma parte de la entrega, después de la revisión y los ajustes del desarrollador.
- El análisis de alternativas para concurrencia, idempotencia y publicación de eventos, y las decisiones que el desarrollador aceptó (ADR).
- La prueba que evidenció la condición de carrera de RF04 y su solución con bloqueo pesimista en la base de datos.
- Las pruebas automatizadas de backend, frontend y extremo a extremo.
- La configuración de Docker Compose y las instrucciones para ejecutar la solución.
- La documentación técnica y funcional.

## Cómo se validaron

- **Revisión del desarrollador** de cada cambio antes de continuar con el siguiente requerimiento.
- **Pruebas automatizadas:** 118 de backend (96 unitarias y 22 de integración contra MySQL y RabbitMQ reales), 48 de frontend y 30 de extremo a extremo con Playwright Test en Chrome.
- **Prueba de mutación:** se eliminó a propósito el bloqueo de cuentas para confirmar que las pruebas de concurrencia fallan sin él.
- **Verificación directa** de resultados en la base de datos (saldos, acumulados, registros) y del plan de ejecución de las consultas (`EXPLAIN`).
- **Revisión visual** de la interfaz con capturas en modo claro, oscuro y móvil.
- **Instrucciones de ejecución probadas** antes de documentarlas: backend con Gradle, frontend con `npm start` y la solución completa con Docker Compose.
- **Repositorios verificados desde una copia limpia**: compilación y todas las pruebas, y la solución levantada con un solo comando sin datos previos.

## Resultados corregidos o descartados

| Situación | Corrección |
|-----------|------------|
| Se agregaron elementos no solicitados (campos fuera de los requerimientos, un reloj inyectable para pruebas) | Descartados por el desarrollador; se estableció implementar solo lo que pide cada requerimiento |
| La primera arquitectura usaba servicios; luego un caso de uso por operación | Reorganizada por el desarrollador: un caso de uso por tipo (cuentas, transferencias), con su estructura hexagonal habitual |
| Nombres de paquetes poco naturales y DTOs separados por operación | Renombrados y agrupados por tipo |
| La documentación se generó inicialmente en inglés | Reescrita en español |
| La implementación inicial de transferencias permitía superar el límite diario con solicitudes simultáneas | Detectado con una prueba real de concurrencia y corregido (ADR-08) |
| El buscador de transferencias recargaba la página; la pantalla de cuentas no mostraba datos; crear cuentas fallaba | Detectados al probar en el navegador (el primero, por el desarrollador). A partir de ahí se incorporó la prueba en navegador real a cada cambio |
| Los saldos quedaban incoherentes con el historial al reiniciar | Corregido: cada inicio restaura un día limpio completo (ADR-07) |
| El publicador de eventos de RabbitMQ tomaba la hora una sola vez y nunca encontraba eventos nuevos | Detectado por las pruebas de integración con RabbitMQ real y corregido, con prueba de regresión |
| Registro de decisiones en múltiples archivos | Consolidado en un único documento, a pedido del desarrollador |
| Las transferencias quedaban con 5 horas de adelanto y el límite diario se reiniciaba a las 7:00 p. m. (el backend usaba la hora UTC) | Detectado por el desarrollador en la interfaz; se configuró la zona horaria de Colombia |
| Las pruebas de extremo a extremo eran un script propio | Reemplazadas por Playwright Test, a pedido del desarrollador |
| Los números de cuenta largos sacaban las tablas y el detalle del ancho de la pantalla | Detectado por las nuevas pruebas de Playwright y corregido con aprobación del desarrollador: el número se recorta en las tablas y se muestra completo en el detalle |
| La documentación de pruebas incluía explicaciones genéricas (qué es una prueba unitaria) | Eliminadas a pedido del desarrollador: solo se describe cómo funcionan las pruebas en esta aplicación |
| Las instrucciones para levantar MySQL omitían un parámetro de autenticación necesario | Detectado al ejecutar las instrucciones antes de documentarlas; se agregó el parámetro |
| La ejecución con Docker Compose dependía de un archivo que no estaba en los repositorios | Corregido: el `docker-compose.yml` quedó en el repositorio del backend |
| Mensajes de commit en español | Reescritos en inglés, a pedido del desarrollador |

## Consideraciones para no exponer información sensible

- **Datos:** solo se usaron los datos ficticios del enunciado (cuentas CTA-1001, CTA-1002 y CTA-2001) y datos de prueba generados por las pruebas automáticas (cuentas `E2E-…`). No se utilizaron datos reales de clientes ni de la entidad.
- **Credenciales:** no se compartieron con la herramienta credenciales productivas, llaves de API ni secretos, y ninguno está en el código ni en los repositorios. Las únicas credenciales del proyecto son las de los contenedores locales de desarrollo: MySQL (`root` / `root`) y RabbitMQ (`guest` / `guest`, la predeterminada de su imagen). No dan acceso a ningún sistema real; en un entorno productivo deben provenir de variables de entorno o de un gestor de secretos (ADR-13).
- **Información compartida con la herramienta:** el enunciado de la prueba, capturas de la aplicación local con datos ficticios y, como referencia de arquitectura, una imagen con los nombres de carpetas de otro proyecto propio del desarrollador, sin código ni datos.
- La herramienta trabajó sobre el código local del proyecto y sobre contenedores de desarrollo en Docker, sin acceso a sistemas productivos. Fuera del equipo local, solo accedió a los repositorios de GitHub del proyecto para publicar la entrega, con autorización del desarrollador.
- Las ejecuciones de comandos quedaron sujetas a la aprobación de permisos configurada en la herramienta.
