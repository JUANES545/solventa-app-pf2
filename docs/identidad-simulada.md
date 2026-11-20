# Verificacion de identidad simulada en Android

Demostracion academica post-login: **Cuenta > Verificacion de identidad simulada**.
No es KYC ni una prueba de vida real, no valida documentos o personas y no otorga
privilegios. No solicita camara, archivos ni datos sensibles nuevos. Registro,
revision de fotos prealta y acceso biometrico conservan sus flujos independientes.

## Activacion del servicio de prueba

El backend debe incorporar `contracts/openapi/identidad-simulada-v1.yaml` y habilitar
`IDENTIDAD_SIMULADA_ENABLED=true` tanto en Identidad como en BFF Movil.
Antes debe aplicar sus migraciones de Identidad, incluida la 0009 de esta unidad.
Dependencia: [Migue765/backend-solventa#133](https://github.com/Migue765/backend-solventa/pull/133).
Esta unidad Android no despliega ni activa el servicio.
La bandera esta deshabilitada por defecto en el servidor; Android muestra la
indisponibilidad HTTP 503 honestamente, sin resultados locales alternativos.
No hay bandera Android que fuerce aprobaciones.

Solo Identidad configura `IDENTIDAD_SIMULADA_RESULTADOS`, por ejemplo
`documento_no_valido,aprobado`: de uno a tres resultados entre `aprobado`,
`documento_no_valido` y `prueba_vida_no_valida`. La secuencia queda congelada al
crear la sesion y el ultimo valor se repite hasta consumir el maximo de tres
intentos. Las referencias no seleccionan resultados.

Usar exclusivamente cuentas sinteticas para la demostracion. Una cuenta tiene
una unica sesion de verificacion persistida en el servidor: cerrar sesion,
reinstalar o cambiar la configuracion no restablece los intentos ni una
aprobacion terminal. Para otra demostracion se usa otra cuenta sintetica; no
existe una accion publica de reinicio.

## Contrato usado

Base: `BuildConfig.AUTH_BASE_URL`, que ya incluye `/movil/v1`.
Todas las solicitudes usan la sesion autenticada del actor esperado mediante
`AuthRepository.withSession`, renovacion acotada y `Cache-Control: no-store`.

| Operacion | Solicitud |
| --- | --- |
| Crear o recuperar la unica sesion del cliente | `POST /verificaciones-identidad` con `{}` |
| Consultar el estado vigente | `GET /verificaciones-identidad/{verificacionId}` |
| Procesar un intento sintetico | `POST /verificaciones-identidad/{verificacionId}/intentos` |

El intento contiene solamente `intentoId` (UUID minusculas),
`documentoRef: "fixture:documento-v1"` y `selfieRef: "fixture:selfie-v1"`.
Son etiquetas sinteticas, no fotos ni referencias a fotos del registro.

Respuesta: `verificacionId`, `estado` (`pendiente`, `aprobado`, `rechazado`),
`intentos`, `maxIntentos: 3`, `reintentoPermitido`, `simulada: true`, `motivo`
(`null`, `documento_no_valido`, `prueba_vida_no_valida`).
El estado inicial es pendiente con cero intentos y reintento no permitido;
la primera accion no es un reintento. Un rechazo permite reintentar solo antes
del tercer intento. Aprobado y rechazo con tres intentos son terminales.
La UI rechaza respuestas incoherentes, no simula resultados con temporizadores
y distingue errores 400, 401, 403, 404, 409 y 503 de rechazos de la demostracion.

## Recuperacion y aislamiento

Antes de enviar se guarda sincronicamente la pareja UUID de verificacion/intento
por actor en preferencias privadas. No se guardan fotos, tokens o aprobaciones.
Si falla el almacenamiento no se envia un intento nuevo. Tras una respuesta
ambigua, volver a consultar o reabrir la pantalla recupera la misma pareja y
reenvia exactamente las mismas referencias y clave; no consume un intento extra.

El replay del servidor devuelve un snapshot historico. Por eso se consulta
siempre GET antes de mostrar el resultado y de retirar la clave pendiente.
Un conflicto de transicion terminal se muestra como error y, tras confirmar GET,
permite actualizar sin seguir reenviando la operacion rechazada. Un conflicto de
idempotencia no cambia silenciosamente la clave ni las referencias.

Al pausar o abandonar se oculta el resultado y se invalidan respuestas tardias;
al reanudar se consulta al servidor. Logout y cambio de actor invalidan el estado
visible. Una clave pendiente solo puede recuperarse para su actor original y las
solicitudes verifican que la cuenta autenticada siga siendo la esperada.

## Comprobaciones locales

Con JDK 17 y SDK 35, sin emulador ni instalacion en un telefono:

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests 'co.solventa.mobile.feature.identity.*' --tests 'co.solventa.mobile.HomeNavigationTest' :app:lintDebug :app:assembleDebug
```

Incluye contrato HTTP, errores/disponibilidad, renovacion de token, fixtures
inmutables, respuestas invalidas, recuperacion con la misma clave, almacenamiento,
aislamiento por actor, respuestas tardias, navegacion desde Cuenta y UI con temas
claro/oscuro y texto ampliado. Son pruebas sinteticas JVM/Robolectric; no
demuestran integracion desplegada ni verificacion de identidad real.
