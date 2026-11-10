# Presentacion Android

## Alcance

Version preparada: 3.2.0, codigo 11. Publicacion solicitada hacia `develop`,
sin integracion ni despliegue.

Las variantes `debug`, pruebas y `preview` permiten capturas y visualizacion
remota. `release` conserva `FLAG_SECURE` y la proteccion de los dialogos de fotos.
En revision local pueden verse datos sensibles al compartir pantalla: usa datos
de prueba y evita difundir fotos, ubicacion o informacion de siniestros reales.

Mejora de presentacion sobre `develop`, sin incorporar funcionalidades de negocio
ni las ramas pendientes de polizas. Se conservan contratos, sesion, actor,
biometria, borradores, UUID, cola offline, sincronizacion, reintentos e historial.

La identidad mantiene Geologica y Afacad Flux, azul petroleo/cian, superficies
claras y azul noche. Los colores proceden del tema existente.

## Navegacion y componentes

- Inicio agrupa reporte, estado y evidencias; no muestra contadores inventados.
- Notificaciones reutiliza su flujo y permisos actuales.
- Mi cuenta conserva datos, seguridad, credenciales biometricas y cierre de sesion.
- La barra inferior pasa a rail desde 600 dp. Los flujos operativos mantienen su
  host y manejo de retroceso; no se descarta un borrador al cambiar una pestaña.
- Con texto ampliado desde 150%, la etiqueta visual Notificaciones se abrevia
  como Avisos para evitar cortes de palabra; conserva su nombre accesible completo.
- Las paginas comparten margenes, ancho de lectura, desplazamiento y tratamiento
  de teclado/insets. Secciones y acciones usan componentes comunes.

## Movimiento

El acceso y registro usan tres circulos decorativos nativos de Compose, sin
WebView ni dependencias nuevas. Su fase y pausa se comparten al navegar.
El movimiento se detiene al escribir, mostrar errores, procesar, abrir el dialogo
informativo biometrico o salir del estado RESUMED. Respeta la escala de animacion
del sistema. No tiene boton ni estado de pausa manual. No se aplica a camara ni gestiones.

## Refinamiento de distribucion y carga

Inicio conserva sus tres accesos, con menos instrucciones repetidas e iconos
lineales. Una curva inspirada en la S acompaña la marca solo cuando hay espacio;
se retira en anchos estrechos y con texto ampliado. Los encabezados operativos
comparten un eje de lectura al inicio. Las etiquetas de acciones, incluidas las
multilinea, mantienen el texto centrado sin limitar lineas ni reducir la fuente.

Las consultas iniciales de notificaciones, siniestros, polizas, evidencias y
prestadores usan skeletons tonales del tema. Son estaticos y anuncian una sola
carga, sin controles ficticios ni espera minima. Una actualizacion mantiene datos
validos del mismo actor; los cambios de actor no reutilizan esa informacion.
Enviar, guardar, sincronizar archivos y obtener ubicacion conservan sus propios
estados de progreso, errores y reintentos.

Los vacios usan ilustraciones vectoriales decorativas de documento o campana.
Las referencias cortas siguen identificando cada registro, sin colisiones entre
los registros de la lista; los detalles conservan el identificador completo.
Las fechas legibles indican su origen y hora local, separando lo observado por
el telefono del resultado del servidor. Expandir metadatos usa una transicion
corta; la marca de confirmacion solo acompaña un resultado confirmado y no se
reproduce por recomponer la pantalla. Respeta las animaciones deshabilitadas y
termina al salir de RESUMED.

El arranque conserva la splash nativa ya existente, su marca y sus colores de
dia/noche. No se añade una segunda pantalla, una plantilla descargada ni demoras
artificiales. Tampoco se incorporan dependencias de animacion, desenfoques o
colores dinamicos que sustituyan la paleta aprobada.

## Inventario revisado

| Superficie | Tratamiento |
|---|---|
| Ingreso y registro | Jerarquia, campos opacos y fondo con movimiento automatico |
| Terminos, confirmacion y resultado del registro | Coherencia con acceso |
| Revision de fotos | Presentacion; validaciones y archivos conservados |
| Inicio y cuenta | Destinos separados y acciones operativas agrupadas |
| Biometria | Informacion, consentimiento y credenciales |
| Reporte de siniestro | Formulario, selector, historial, cola y correccion |
| Estado de siniestros | Lista, detalle, carga y errores |
| Evidencias | Seleccion, archivos, envios, ubicacion, revision, recorte y descarte |
| Camara | Preparacion y controles propios; permisos del sistema sin rediseñar |
| Asistencia | Ubicacion/manual, prestadores, confirmacion y resultado |
| Notificaciones | Lista, permiso, vacio, carga y errores |
| Dialogos del sistema | Conservados; no se cambia su apariencia |

## Verificacion

Las pruebas unitarias cubren navegacion, sesion/expiracion, biometria, estados
operativos y politicas de movimiento. Las pruebas instrumentadas de presentacion
usan datos sinteticos y una variante aislada: no sustituyen la aplicacion oficial
ni envian operaciones al backend.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug --console=plain
.\gradlew.bat :app:connectedClaimTestAndroidTest -PclaimDeviceTests=true "-Pandroid.testInstrumentationRunnerArguments.class=co.solventa.mobile.HomePresentationDeviceTest,co.solventa.mobile.AccessPresentationDeviceTest,co.solventa.mobile.OperationalPresentationDeviceTest" --console=plain
```

La revision visual fisica, lectura con TalkBack, camara/video y dialogos reales
del sistema deben distinguirse de las aserciones semanticas y capturas sinteticas.
Estas ultimas no certifican conformidad de accesibilidad ni rendimiento/bateria.

### Resultado local de la primera ronda

- Suite unitaria: 387 pruebas enumeradas, 386 correctas y una omitida, sin fallos.
- Lint debug: sin errores; conserva 43 advertencias que no se resolvieron en este
  trabajo visual. Compilacion Kotlin debug/release y APK de pruebas correctos.
- Dispositivo fisico SM-A217M: 43 pruebas correctas (42 de presentacion, acceso,
  registro, HTTP controlado y almacenamiento; una adicional de capturas de acceso).
- Capturas sinteticas revisadas en claro/oscuro y texto ampliado. Se corrigieron
  un objetivo tactil de 40 dp, una etiqueta partida y textos de retorno obsoletos.
- Login y registro se ejercitaron con teclado, viewport 320 x 480 dp y fuente
  al 200%, sin modificar las preferencias del sistema.
- La aplicacion oficial permanece en 3.1.0/code 10; se uso exclusivamente
  `co.solventa.mobile.pf2.claimtest` para esta validacion.

Posteriormente, por solicitud expresa, se retiraron las variantes de pruebas y
se reemplazo la instalacion oficial por `preview`. La revision local actual es
3.2.0/code 11, actualizada sin borrar los datos de la revision local anterior.
Las capturas estan permitidas en ella; no es una version publicada.

Tras habilitar capturas, la suite debug y lint pasan, y las pruebas de
configuracion de release confirman que su proteccion sigue activa.
Los tests Compose de hosts no se ejecutan en release: esa variante no incluye
la actividad ComponentActivity del manifiesto de pruebas (solo debug).
No se modifico el manifiesto publicado para habilitar dichos tests.

No se ejecutaron TalkBack manual, captura real de camara/video, autenticacion
biometrica fisica ni recorridos contra un backend real. El movimiento se verifico
con pruebas deterministas de fase, pausa y ciclo de vida; las capturas estaticas
no prueban fluidez ni consumo. No se publicaron commits, PR, versiones ni cambios
en Jira como parte de esta implementacion local.

### Resultado local de la segunda ronda

- Suite debug completa: 412 pruebas enumeradas, 411 correctas y una omitida,
  sin fallos. La omitida requiere el backend local explicito.
- Lint debug: cero errores y 49 advertencias pendientes. APK `preview` y de
  pruebas compilados; compilacion Kotlin release y cuatro pruebas de su
  configuracion correctas.
- SM-A217M fisico: 39 pruebas correctas de presentacion, registro, autenticacion
  HTTP controlada y cola offline, con datos sinteticos.
- Revision de 312 capturas en claro/oscuro, incluidos viewport 320 x 480 dp y
  fuente al 200%; los componentes compartidos tambien cubren 150%. Se ajustaron
  referencias largas, fechas y densidad tras revisar las primeras capturas.
- La accion de notificaciones dice "Abrir aviso" para distinguirla de
  "Ver detalles", que despliega metadatos. Tras este ultimo cambio pasaron las
  pruebas unitarias de notificaciones y cinco pruebas fisicas operativas.
- Se actualizo `preview` 3.2.0/code 11 mediante instalacion sobre la version
  anterior, sin borrar datos. Se retiraron los dos paquetes temporales de
  pruebas; solo queda `co.solventa.mobile.pf2`, con capturas permitidas.
  Se abrio la aplicacion y se comprobo que el APK instalado coincide con el
  compilado mediante SHA256:
  `72092259f46ad929604dcceccd167246af708782e9f2ead8df28ef5f5220cebb`.

Las escalas de fuente y viewport de las capturas son fixtures de Compose; no
demuestran que todos los dialogos de Android se hayan recorrido con la escala
global real al 200%. No se ejecutaron TalkBack manual, camara/video real,
autenticacion biometrica fisica ni operaciones contra el backend real. Tampoco
se midieron fluidez o bateria. La prueba E2E de siniestro que requiere el fixture
backend completo compilo, pero no se ejecuto en esta ronda.

La revision permanece local, sin commits, push, PR ni despliegue.

### Continuidad entre carga y error

Las consultas iniciales sin datos de avisos, estado de siniestros, polizas,
siniestros para evidencias y prestadores usan `SolventaLoadError`: superficie
tonal del tema, icono discreto, titulo y causa original. El bloque reserva el
alto del skeleton correspondiente, incluido el redondeo de cada medida a pixeles.
Asi conserva la posicion de los controles inferiores cuando la consulta falla.
La altura es minima: el texto puede crecer y la pagina permite desplazarse.
No se añaden esperas ni animaciones para ocultar el error.

Los errores de actualizacion con datos, validacion de campos, camara, guardado
y envios conservan su tratamiento y sus acciones actuales. Este cambio no
modifica permisos, sesiones ni la causa de los errores; tampoco añade un flujo
de reingreso ni convierte fallos en resultados vacios.

Validacion de este ajuste: 17 pruebas unitarias focales correctas, lint debug
correcto, compilacion Kotlin release y APK preview/pruebas correctos. En el
telefono pasaron cinco pruebas operativas y tres compartidas. La prueba nueva
compara la altura y la posicion de la accion antes y despues del error en ambos
temas, con fuente al 100%, 150% y 200%; se revisaron sus 12 capturas sinteticas.
Los limites de validacion real y accesibilidad descritos arriba siguen vigentes.

Se actualizo la unica instalacion local 3.2.0/code 11 sin borrar datos y se
retiraron los paquetes temporales. El APK instalado coincide con el compilado:
`0fff39255dc623fb901b3c38ae94226d1c214209cbedb78582427833235f284b`.

### Correccion de autorizacion de avisos

`MobileNotificationApi` enviaba un literal de asteriscos en `Authorization`.
Ahora envia `Bearer` con el access token de la sesion, tanto al consultar avisos
como al consultar o actualizar preferencias. Se conserva la renovacion unica
ante un 401 y el error explicito ante un segundo rechazo o un 403.

Las aserciones nuevas reprodujeron el fallo antes de corregirlo. Despues pasaron
las 15 pruebas focales de API, pantalla, almacenamiento y ViewModel de avisos,
ademas de lint y la compilacion release. Una prueba HTTP instrumentada en el
SM-A217M verifico las tres operaciones, el token renovado y el rechazo persistente
con credenciales sinteticas y servidor loopback. No se extrajeron credenciales
del usuario ni se consultaron sus avisos reales durante esta comprobacion.

Se actualizo la unica instalacion 3.2.0/code 11 sin borrar datos; el APK instalado
coincide con el compilado:
`be04c38234539648b2886c80a3c95484f8d109cb69cb1df83f9577d32a4280d6`.
La consulta autenticada contra el backend desplegado con la cuenta del usuario
queda pendiente de comprobar en la aplicacion. No se modifico backend ni Jira,
ni se crearon commits o PR.

### Consultas vacias y errores de evidencias

Se contrastaron los contratos de siniestros y evidencias: una cuenta sin
registros recibe 200 con una coleccion vacia. Un 403, un 404 o un fallo del
servicio no demuestran ausencia de datos y no se convierten en estados vacios.
Los 404 con los codigos de recurso no encontrado tienen un mensaje neutro de
recurso no disponible, sin revelar si pertenece a otra cuenta; los 404
desconocidos siguen siendo fallos de consulta.

La lista consulta primero los siniestros y solo solicita la configuracion de
captura cuando existen registros. Las consultas iniciales y los refrescos ya
no muestran el mensaje de envio interrumpido: distinguen conexion, respuesta
invalida, recurso no disponible y servicio con respuesta 5xx. Se conserva la
superficie estable entre skeleton y error, asi como los datos previos al
refrescar para el mismo actor. Si hay evidencias registradas en el servidor
pero ningun archivo local, se muestra su cantidad en lugar de afirmar que no
hay evidencias. Esto no incorpora descarga ni galeria remota.

En el telefono, usando la sesion que ya conserva la aplicacion, la consulta
real de siniestros respondio HTTP 503 antes y despues de la instalacion final.
El aviso muestra ahora que el servicio no esta disponible; no atribuye el
fallo a una sesion invalida, a una cuenta antigua ni a ausencia de registros.
El diagnostico solo registra categoria de recurso y estado HTTP, sin tokens,
identificadores ni cuerpos. La causa interna de ese 503 sigue pendiente de
investigar en el servicio desplegado; esta correccion Android no lo resuelve.

Pasaron 64 pruebas focales, la compilacion preview y release Kotlin, y lint
(sin errores; 50 advertencias). Una prueba HTTP en el dispositivo fisico
verifico cuenta sin siniestros sin pedir configuracion, 503, recuperacion,
404 de un recurso y un siniestro sin evidencias. Las pruebas de presentacion
cubren el mensaje de servicio tanto inicial como con datos previos y el
recuento remoto sin archivos locales.

Se instalo 3.2.0/code 11 sin borrar datos y se retiraron los dos paquetes
temporales de pruebas. El APK instalado coincide con el compilado:
`3c875a909a8cfc06c7a8c4dfb00ddff9b0b66077ff5a53b0fa83ef7f2c8d98aa`.
No se modifico backend ni Jira, ni se crearon commits o PR.
