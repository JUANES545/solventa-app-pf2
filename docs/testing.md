# Pruebas y cobertura Android

## Candidato normal con fotos y contrato biométrico público

Base de trabajo: `develop@806679151feff01510dd475be18d5b570ee59418`.
La app normal comparte ahora el código CameraX/ML Kit, recursos y pruebas de
fotos con la variante aislada. CAMERA y ambas cámaras opcionales se declaran
en el manifiesto común; no se añaden permisos de micrófono, almacenamiento
o galería. Se elimina el host vacío `noPhoto`.

`debug`, `preview` y `release` compilan sin propiedades privadas con:

| Campo | Valor |
| --- | --- |
| applicationId | `co.solventa.mobile.pf2` |
| AUTH_BASE_URL | `https://65qgzhody9.execute-api.us-east-2.amazonaws.com/movil/v1` |
| AUTH_ISSUER | `https://65qgzhody9.execute-api.us-east-2.amazonaws.com` |
| BIOMETRIC_LOGIN_ENABLED / PHOTO_REVIEW_ENABLED | `true` / `true` |

El issuer exacto, sin barra final ni `/movil/v1`, fue confirmado por el
coordinador a partir del OIDC público revisado por backend. `biometricTest`
conserva su applicationId separado, URL loopback e issuer `.test` explícitos.
No existe fallback de la app normal hacia ese entorno.

Orden de salida: revisar y aplicar las rutas biométricas autorizadas, desplegar
Identidad con la migración `0006` y BFF móvil, comprobar Redis e issuer, y activar
primero Identidad y después BFF móvil antes de distribuir la app normal. En esta
revisión backend informó 404 en esas rutas públicas. Este corte prepara el
cliente; no demuestra un servicio público operativo ni cierra los criterios
de prueba de vida/identidad de SOL-169. Las fotos siguen siendo revisión local
y las casillas siguen siendo provisionales, no consentimiento legal persistido.

Validación del candidato:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:testBiometricTestUnitTest `
  :app:compileDebugAndroidTestKotlin :app:lintDebug :app:lintPreview `
  :app:assemblePreview :app:assembleDebugAndroidTest
```

Resultado: 205 casos por variante, 204 ejecutados y uno optativo de backend
omitido en cada una; cero fallos o errores. Lint: cero errores, 40 advertencias
debug y 32 preview. Compilación y empaquetado terminaron con código 0.
Las pruebas de fotos antes exclusivas de `testBiometricTest` están en `src/test`
y quedan incluidas en los checks debug existentes, sin excluir sus aserciones.

Se añaden regresiones de issuer público exacto (rechazo de barra, ruta,
issuer local y HTTP), 404 HTML/503 sin redirects ni grant biométrico,
enrolamiento fallido que elimina solo la clave candidata, login convencional
recuperable y revocación fallida que conserva el vínculo hasta confirmación.
Las pruebas HTTP instrumentadas usan el PhotoReviewHost real con JPEG sintético:
recorren las dos revisiones y confirmación antes del POST. Se compilaron,
no se ejecutaron en este corte. El doble no acredita una cámara física.

APK `app\build\outputs\apk\preview\app-preview.apk`:
46 872 930 bytes, SHA-256
`8FC4892C7D170C80A9225992E6D5DFF235FF98D72665B17014E6BED55CDCD53C`.
`aapt2` comprobó package normal, versión 0.3.1/code 6, min26/target35 y
cámaras opcionales. No se modificó versionado ni se instaló ese APK.
La firma V2 y `zipalign -c -P 16 4` pasan. El manifiesto preview combinado
mantiene `usesCleartextTraffic=false` y no incluye la excepción de red local
de debug/biometricTest. Esto no acredita ejecución en páginas de 16 KB.
El worktree anterior y su APK aislado `CEE030…DDD15B` se conservan.
Sin ADB, emuladores, nuevas cuentas, despliegue, merge o publicación en esta
primera etapa; el diff se entrega al coordinador para revisión.

## Batería JVM

JUnit 4 ejecuta las pruebas del repositorio de autenticación y configuración.
Las pruebas del ViewModel usan `kotlinx-coroutines-test` para controlar el hilo principal.
Robolectric permite comprobar formularios Compose, navegación y contratos Android en la JVM,
sin sustituir la batería instrumentada existente.

`MobileAuthApiTest` utiliza MockWebServer en loopback para verificar métodos, rutas, payloads,
cabeceras, respuestas inválidas y errores HTTP. La base inyectada de prueba no cambia el
constructor de producción ni su URL HTTPS. Estas pruebas no consumen AWS ni crean cuentas.

## Medición

```sh
./gradlew --no-daemon jacocoDebugUnitTestReport
```

- XML: `app/build/reports/coverage/unit/coverage.xml`.
- HTML: `app/build/reports/coverage/unit/html/index.html`.
- JUnit: `app/build/test-results/testDebugUnitTest/`.

JaCoCo incluye las clases cargadas por el sandbox de Robolectric. Se mantienen las exclusiones
existentes de código generado; no se excluyen pantallas, ViewModel, cliente HTTP o almacenamiento.
El objetivo es alcanzar al menos el 80 % mediante pruebas de comportamiento, no reduciendo
el código medido. Jenkins publica los contadores y la revisión compilada en el check Cobertura.

## Dispositivo y compilación

```sh
./gradlew --no-daemon lintDebug assembleDebug assembleDebugAndroidTest assemblePreview
./gradlew --no-daemon connectedDebugAndroidTest
```

La segunda orden requiere un **emulador o dispositivo dedicado a pruebas**, sin cuentas reales.
La batería borra el almacenamiento de sesión de `co.solventa.mobile.pf2` antes y después de los
recorridos. No ejecutarla sobre la instalación personal del usuario. Compilar `androidTest`
no equivale a ejecutar la instrumentación.

## Suite Espresso integrada

La carpeta `app/src/androidTest/java/co/solventa/mobile/espresso/` amplía el source set
convencional de Gradle, no es una aplicación demo. `AuthHttpFlowTest` abre `MainActivity`
y utiliza las pantallas, `AuthViewModel`, `AuthRepository`, `MobileAuthApi` y
`EncryptedAuthStore` reales. Hilt reemplaza **solo en androidTest** el proveedor del
repositorio para dirigir el cliente HTTP a MockWebServer en `127.0.0.1:18080/movil/v1`.
No se reemplazan el repositorio ni el almacenamiento por implementaciones ficticias.
La URL de producción y su contrato no cambian.

La interfaz es Jetpack Compose: los campos no son Views Android individuales. Espresso
comprueba la raíz de la actividad, controla el teclado y prueba el botón Atrás del sistema;
Compose Test consulta el árbol semántico, edita los formularios y sincroniza recomposiciones.
Las respuestas asíncronas se esperan mediante `waitUntil` con una condición observable y
un límite de tiempo; la prueba de solicitud en curso usa un `CountDownLatch` liberado por
el test, no pausas arbitrarias. No hay `sleep`, `Assume` ni éxitos simulados.

`SolventaTestRunner` usa `HiltTestApplication`. Se conservan `AppLaunchTest`,
`LoginLayoutTest`, `RegistrationFlowTest`, `AuthStorageTest` y las pruebas JVM.
`AuthStorageTest` usa un nombre de preferencias y un alias de Android Keystore únicos
por ejecución, y elimina únicamente esos recursos al terminar. Sus fixtures y fechas
son deterministas; no escribe ni borra `mobile-auth/grant` ni usa la clave de sesión
productiva. Cambiar solo el `Context` no aislaría el Keystore del proceso instrumentado.
Esta separación corresponde a ese test de almacenamiento, no a toda la batería:
las pruebas de actividad siguen requiriendo un emulador dedicado sin sesiones personales.
La configuración de red **solo debug** autoriza HTTP únicamente para `127.0.0.1` y
`10.0.2.2`; preview/release mantienen HTTPS obligatorio. El módulo de pruebas rechaza
otros hosts, credenciales en URL, query strings y rutas distintas de `/movil/v1`.

| Funcionalidad disponible | Casos nuevos sobre la actividad real (`AuthHttpFlowTest`) |
| --- | --- |
| Registro → confirmación → login → cuenta → cierre | `registrationLoginColdActivityRestoreAndLogoutUseHttpAndEncryptedStorage`: métodos/rutas/payloads, cabecera Bearer, registro sin login implícito, correo conservado, contraseña vacía, cifrado real y nueva actividad sin ViewModel anterior |
| Validación y navegación | `invalidFormsNeverReachTheNetworkAndSystemBackReturnsToLogin`: correo, contraseña corta, confirmación distinta, cero HTTP y Atrás del sistema |
| Errores de registro | `duplicateRegistrationStaysOnFormWithoutPersistingSession` (409), `failedRegistrationDoesNotClaimTheAccountWasCreated` (503) |
| Errores y recuperación de acceso | `invalidCredentialsCanBeCorrectedWithoutLeavingLogin` (401 y reintento manual), `rateLimitIsShownWithoutAutomaticallyRetrying` (429), `unavailableServiceDoesNotCreateALocalSession` (503) |
| Contrato HTTP y pérdida de conexión | `malformedSuccessfulResponseIsNotAnAuthenticatedSession`, `disconnectedResponseSurfacesConnectionError` |
| Solicitud en curso | `loginInFlightDisablesInputsAndPreventsDuplicateSubmission`: campos y acciones deshabilitados, un único POST |
| Restauración y rotación | `expiredAccessRotatesTokensBeforeProtectedRequestAndPersistsThem`, `unauthorizedCurrentSessionRenewsOnceAndRetriesWithRotatedAccess` |
| Expiración/revocación | `revokedRefreshClearsStorageAndShowsSessionExpired`, `expiredRefreshIsRemovedWithoutNetworkTraffic` |
| Cierre remoto fallido | `logoutFailureStillRemovesLocalSessionAndReportsRemoteUncertainty`: advertencia explícita, borrado local y sin restauración posterior |
| Almacenamiento corrupto | `corruptedStoredGrantShowsStorageErrorInsteadOfRestoringIdentity`: error visible, borrado y cero HTTP |
| Privacidad en formulario | `registrationPasswordVisibilityTogglesAndBackDoesNotRetainPassword` |

Estas 17 pruebas son **integración instrumentada con doble HTTP**, no evidencia de
funcionamiento del backend. Las pruebas previas también cubren tamaño de pantalla,
fuente grande, foco/IME, límites de validación, errores del repositorio y Keystore.

```powershell
# Ejecutar en un emulador dedicado; ANDROID_SERIAL evita elegir otro dispositivo.
$env:ANDROID_SERIAL = "emulator-5580"
.\gradlew.bat --no-daemon connectedDebugAndroidTest -PbackendE2e=false
# Solo los nuevos recorridos HTTP (diagnóstico):
.\gradlew.bat --no-daemon connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=co.solventa.mobile.espresso.AuthHttpFlowTest"
```

En Linux/macOS usar `./gradlew` y `ANDROID_SERIAL=emulator-...` antes del comando.
El job existente `Android UI tests` ejecuta la suite predeterminada en API 35, no solo
compila el APK. Publica los XML/resultados y reportes HTML incluso si hay fallos.

## E2E con backend real y persistencia temporal

`AuthBackendE2eTest` está separado mediante la anotación `BackendE2e`. Por defecto **no
se selecciona**, y por tanto no se reporta como prueba aprobada ni ignorada. Activarlo
requiere `-PbackendE2e=true` y `backendUrl` explícito; si falta el backend, falla.
No existe fallback a producción ni a MockWebServer.

En un checkout de `Migue765/backend-solventa` que incluya `tests/jmeter/local_stack.py`,
seguir su README para instalar dependencias y arrancar el stack temporal:

```powershell
# Desde el repositorio backend, en otra terminal:
python -m tests.jmeter.local_stack --web-port 5000 --mobile-port 5001
```

El stack debe usar los servicios reales de identidad/BFF móvil, JWT y almacenamiento
SQLite temporal, no AWS ni usuarios reales. No se requiere semilla: la prueba registra
un correo sintético único `espresso-<UUID>@example.com` con contraseña exclusiva de test.

```powershell
# Desde este repositorio, con el emulador dedicado:
$env:ANDROID_SERIAL = "emulator-5580"
adb -s emulator-5580 reverse tcp:5001 tcp:5001
try {
    .\gradlew.bat --no-daemon connectedDebugAndroidTest -PbackendE2e=true "-Pandroid.testInstrumentationRunnerArguments.backendUrl=http://127.0.0.1:5001/movil/v1"
} finally {
    adb -s emulator-5580 reverse --remove tcp:5001
}
```

Este caso atraviesa UI → cliente HTTP → BFF → identidad/persistencia real: registra,
inicia sesión, comprueba la identidad con un GET real, abre una nueva actividad,
renueva tokens, cierra sesión y exige 401 al intentar renovar el refresh revocado.
Para forzar la renovación sin esperar, cambia únicamente la fecha de expiración local
del access token en el almacén de prueba; no simula la respuesta de renovación.
No representa una prueba de expiración natural del JWT ni de muerte del proceso Android.
La base temporal se elimina al detener el stack según su procedimiento; no se envían
solicitudes destructivas a un backend compartido para limpiar usuarios.

Conservar por separado los reportes de cada ejecución, ya que Gradle reutiliza
`app/build/reports/androidTests/connected/` y
`app/build/outputs/androidTest-results/connected/`.
CI ejecuta automáticamente los casos controlados; el E2E requiere aprovisionar el
stack real anterior y ejecutar el comando explícito. Un CI controlado verde **no**
demuestra que el E2E real haya corrido.

## Trazabilidad y brechas del alcance móvil

Fuentes de verdad: **Documento de arquitectura v7**, pp. 17, 19, 21 y 23, y
**enunciados-proyecto**, pp. 8–9. El inventario se basa en `SolventaApp`, las pantallas
de `feature/auth`, `MainActivity` y el manifiesto (INTERNET y permisos normales de
biometría aportados por AndroidX; sin permiso de cámara).

| Requisito de la fuente | Estado en Android / evidencia |
| --- | --- |
| Acceso del cliente móvil (enunciado p. 9) | Registro, login, cuenta, sesión cifrada y logout disponibles; suites anteriores y nuevos recorridos HTTP/E2E |
| Cámara, video, evidencia geoetiquetada y escaneo (p. 9) | Ausentes: sin pantalla, permiso ni integración. No cubiertos |
| Biometría y selfie/prueba de vida (p. 9) | Consulta informativa y flujo de clave protegida por biometría fuerte con firma validada en backend, habilitado solo en variante local aislada. Alta, acceso, cancelación, revocación y alternativa convencional comprobados en teléfono físico; sin captura, selfie, prueba de vida ni verificación de identidad |
| Push, geolocalización y asistencia (p. 9) | Ausentes. No se inventan pruebas de sensores, permisos o entrega de notificaciones |
| Billetera de pólizas offline/sincronización (p. 9) | Ausente. Persistencia de credenciales no equivale a una billetera offline |
| Recuperación de contraseña | Botón deshabilitado, no flujo implementado |
| ASR-PRV-01 (arquitectura p. 23) | Sin gestión de consentimiento/Open Finance en la app. Logout/revocación de refresh **no** prueban la revocación de consentimiento, su propagación en ≤ 5 min ni el bloqueo de divulgación |
| ASR-SIN-01 (p. 19) | Sin reporte de siniestro. No cubiertos: persistir aviso/evidencias antes del acuse, RPO=0 y mismo expediente al reintentar |
| ASR-FIN-01 / ASR-EMI-01 (pp. 17/21) | Sin pagos, firma ni emisión en Android. No cubiertos: efecto financiero único, conciliación/saga ≤ 60 s, guardia de emisión e idempotencia |

No se agregan funcionalidades de negocio ausentes para aparentar cobertura. Tampoco
se presenta un porcentaje de líneas JVM como porcentaje de requisitos satisfechos.

## Evidencia local de esta ampliación

Corte del 21 de octubre de 2026 (UTC), Windows/JDK 17, emulador dedicado
`Solventa_API_35` API 35 x86_64, iniciado con `-read-only -no-snapshot`.
No se usaron dispositivos personales, datos reales ni servicios de producción.

| Comando ejecutado | Resultado |
| --- | --- |
| `.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assemblePreview` | Correcto; 37 pruebas JVM, 0 errores lint (19 advertencias), APK debug/test/preview compilados |
| `.\gradlew.bat --no-daemon connectedDebugAndroidTest -PbackendE2e=false` | 32/32 aprobadas, 0 omitidas, 0 fallos; 17 nuevos recorridos HTTP y 15 pruebas existentes. Repetición final: 86,624 s en JUnit |
| `.\gradlew.bat --no-daemon lintDebug connectedDebugAndroidTest -PbackendE2e=true "-Pandroid.testInstrumentationRunnerArguments.backendUrl=http://127.0.0.1:5001/movil/v1"` | 1/1 E2E aprobado, 0 omitidos, 0 fallos; 15,496 s en JUnit. BFF/identidad y SQLite temporal reales del stack local de backend |

Los tiempos son de la ejecución de pruebas, no umbrales de rendimiento ni un SLA.
La primera ejecución de lint detectó atributos `includeSubdomains` faltantes en la
configuración debug; se corrigieron a `false` y las validaciones finales anteriores
pasaron. Los XML de HTTP y E2E se conservaron por separado fuera del repositorio.
Este corte es evidencia **local**, no un resultado del CI remoto del PR.

## Gráfico consolidado

[Cobertura de código](https://github.com/Migue765/backend-solventa/wiki/Cobertura-de-codigo)
identifica la revisión medida y la fecha de cada corte. Una medición de PR debe señalarse como
tal hasta integrarse; no se presenta como perteneciente a una versión estable diferente.

## Biometría SOL-169

`AndroidBiometricKeysTest` comprueba la política auth-per-use en API 26/29/30/35,
invalidación por cambios de enrolamiento y separación de aliases.
`AndroidBiometricSignerTest` rechaza PIN, tipo desconocido y CryptoObject ausente
o diferente; cubre cancelación, bloqueo y errores de capacidad.
`BiometricProtocolTest` usa el vector público compartido con Python: Java verifica
su firma DER/SPKI, bytes exactos y mutaciones negativas, incluido issuer con otra barra final.
La regresión de reloj reproduce el rechazo previo con dos segundos de atraso y comprueba
el límite cliente de cinco segundos: acepta la frontera y rechaza un milisegundo más.
Conserva la fecha original, rechaza expiración igual a ahora y marcas de tiempo que
no coincidan con los bytes del mensaje; el cálculo del límite evita overflow.

`BiometricFlowUiTest` ejercita alta, logout, acceso firmado y revocación en
Robolectric/Compose con claves JVM sintéticas. Cubre contraseña de alta incorrecta,
cancelación, clave invalidada, publicación local fallida, fecha malformada y acceso
convencional posterior. Comprueba cuenta A → logout → cuenta B sin lista ni revocación
ajenas, y una respuesta HTTP biométrica tardía después de logout/cambio de cuenta.
`AuthRepositoryTest` comprueba la generación de sesión y renovación del mismo actor.
Los recorridos de cuenta usan 320×480 dp y fuente al 200 %; no sustituyen TalkBack,
prueba de sensor físico ni revisión visual en el dispositivo.

```powershell
.\gradlew.bat --no-daemon --max-workers=2 :app:testDebugUnitTest --tests 'co.solventa.mobile.feature.auth.*' --tests 'co.solventa.mobile.AccessScreensTest' --tests 'co.solventa.mobile.BiometricFlowUiTest' --tests 'co.solventa.mobile.BuildConfigurationTest' :app:testBiometricTestUnitTest --tests 'co.solventa.mobile.BuildConfigurationTest' :app:lintDebug :app:lintBiometricTest :app:assembleDebugAndroidTest :app:assembleBiometricTest
```

`BiometricLocalBackendTest` omite el único caso E2E salvo autorización explícita
mediante `SOLVENTA_BIOMETRIC_LOCAL_E2E=1` y credenciales sintéticas transitorias
en `SOLVENTA_LOCAL_TEST_EMAIL` / `SOLVENTA_LOCAL_TEST_PASSWORD`.
Apunta solo a `127.0.0.1:18717`; si no responde, falla, sin fallback. Usa JCA en JVM,
servicios de BFF/Identidad y persistencia del stack local. Comprueba alta, bytes
firmados, login, replay, firma de bytes distintos, revocación y refresh rechazado.
Revoca la credencial sintética y cierra su sesión al terminar. No prueba AndroidKeyStore.

```powershell
# Con el stack local y la cuenta sintética autorizados; no usar cuentas reales.
# Definir las tres variables anteriores en la sesión, sin guardarlas en archivos.
.\gradlew.bat --no-daemon --max-workers=2 :app:testDebugUnitTest --tests 'co.solventa.mobile.feature.auth.BiometricLocalBackendTest'
```

La primera ejecución aprobó 1/1 sin omisiones contra SQL real y Redis explícitamente
doble (`fakeredis` + Lua). La repetición posterior aprobó 1/1 con Redis real 7.4.11
del stack local coordinado, con BFF/Identidad en loopback y SQL temporal.
Se conservan resultados separados para ambos casos. No demuestran TLS,
infraestructura productiva ni autenticación con huella física.

El APK aislado queda en `app\build\outputs\apk\biometricTest\app-biometricTest.apk`.
Para el corte de huella anterior se comprobó package exacto, URL/issuer y manifest sin CAMERA.
La ampliación de foto descrita abajo añade CAMERA solo a `biometricTest`.
Antes de una nueva instalación, comprobar package, permisos previstos,
salud del stack y autorización de la instalación separada. La conexión usa
`adb reverse tcp:18717 tcp:18717` en el dispositivo autorizado. No reemplazar la app
personal ni ejecutar sobre ella `AuthJourney`/`connectedDebugAndroidTest`.
La huella real exige participación del usuario; compilar o firmar en JVM no la sustituye.

### Comprobación física de SOL-169, 25 de octubre de 2026 (UTC)

Teléfono SM-A217M, API 36; APK `biometricTest` con package
`co.solventa.mobile.pf2.biometrictest`. Se realizó una instalación separada y,
con nueva autorización, una actualización que conservó la sesión convencional.
La ruta del APK de la aplicación habitual no cambió antes/después de cada operación;
no se leyó su almacenamiento ni se ejecutó instrumentación sobre ella.

El stack local coordinado usó BFF/Identidad, SQL temporal y Redis real 7.4.11.
El teléfono accedió al BFF por el reverse propio `tcp:18717`. Solo se utilizó una
cuenta sintética; no se capturaron plantillas, selfies ni credenciales de producción.

El primer intento detectó un rechazo por desfase: el teléfono estaba unos dos
segundos detrás del servidor. La prueba JVM reprodujo el fallo antes de corregir
el límite de futuro del cliente a 60 + 5 segundos. La última ejecución aprobó
8 casos de protocolo, 10 de UI/actores/cancelación y 1 de interoperabilidad JCA,
sin omisiones. Ambos lints terminaron sin errores: 21 advertencias en debug y
16 en la variante aislada. El APK físico corregido tiene SHA-256
`29F34566080308F6DAD061419F0389A7542B63052CF2DF6FBCBD75C1F0B805CE`.

| Recorrido real | Evidencia observada |
| --- | --- |
| Login convencional y actualización del APK aislado | Cuenta sintética autorizada; la actualización recuperó su sesión sin borrar datos |
| Alta opt-in | El usuario confirmó el consentimiento y aportó su huella. La UI mostró el vínculo y la confirmación de alta después de la respuesta del backend |
| Logout y conservación del vínculo | El formulario volvió a mostrar «Ingresar con biometría», sin pantalla de cuenta |
| Login con huella | El usuario confirmó dos ingresos con huella; la UI mostró Mi cuenta y el vínculo local |
| Cancelación deliberada | El usuario dejó de tocar el sensor y pulsó la alternativa del cuadro nativo. La UI mostró «Cancelaste la biometría. Puedes ingresar con correo y contraseña.», sin abrir la cuenta |
| Revocación desde sesión biométrica | El usuario desactivó su credencial. La UI mostró la fila revocada, retiró el vínculo y aclaró que las sesiones de acceso ya abiertas pueden continuar hasta su vencimiento |
| Alternativa después de revocar | Tras logout desapareció el botón de ingreso biométrico. El login convencional volvió a autorizar la cuenta sintética |
| Cierre de la prueba | Logout final, formulario sin vínculo ni botón de ingreso biométrico; ambas aplicaciones siguen instaladas |

El coordinador corroboró el preestado SQL a las 02:35:09 UTC: credencial y segunda
familia biométrica activas. El postestado y la auditoría de revocación coincidieron
a las 02:36:19.987837 UTC: credencial revocada y cero refresh activos en esa familia.
La primera familia conservó su revocación anterior por logout. Esta evidencia distingue
la revocación por DELETE del cierre de sesión posterior.

Se retiró solo el reverse propio y el XML temporal de inspección al terminar el
último HTTP. La app de pruebas quedó instalada y sin sesión. La limpieza no borró
datos de aplicaciones ni modificó ajustes o huellas del sistema.

No se probaron en hardware bloqueo por intentos, invalidación al cambiar biometrías,
restauración sin clave, otros modelos/API ni TalkBack. Esos casos conservan pruebas
JVM cuando corresponden y requieren su propia validación física. Esta prueba no
acredita atestación remota, rostro del sistema, selfie/prueba de vida, KYC, TLS o
infraestructura de producción, y no cierra HU17 completa.

## Documento y selfie locales, variante aislada

El recorrido actual usa CameraX dentro de `biometricTest`: borrador de alta →
documento → selfie → resumen local → confirmación explícita → POST de alta →
cuenta creada sin sesión → login explícito con correo preparado.
Login y Mi cuenta ya no ofrecen una entrada suelta a las fotos.
No solicita modelos PAD,
credenciales de proveedor, servidor, túnel, Redis o sesión para abrir ese paso.
La finalización solo corresponde al recorrido visual local; no acredita PAD,
identidad, onboarding autorizado ni cumplimiento completo de HU17.

`PhotoReviewTest` usa JPEG sintético generado en JVM: permiso denegado,
archivo vacío/corrupto/sobredimensionado, EXIF y muestreo, revisión/aceptación,
repetición/cancelación, error de cámara/almacenamiento, proceso nuevo, fondo,
cambio de actor/logout y callbacks tardíos. `PhotoFlowUiTest` sustituye únicamente
la cámara por un doble para recorrer Compose, también con fuente al 200 %:
borrador → documento/revisión → selfie/revisión → resumen sin usuario,
grant ni llamadas a AuthApi. Cubre repetición, cancelación, denegación/error,
fallo de limpieza al salir del resumen y cambio de contexto durante una revisión.
Comprueba que la confirmación explícita posterior envía un único POST de alta,
sin grant hasta el login explícito posterior.
Cada aceptación exige JPEG válido y limpieza antes del estado recibido.
Reiniciar documento, fondo, proceso nuevo y cambio de actor invalidan los avances.

`PhotoFlowUiTest` usa `ActivityScenario.recreate()` con cámara sintética y conserva
AuthViewModel en ViewModelStore: documento aceptado y selfie en revisión se
descartan al recrear la actividad junto con el borrador de registro, sin POST.
No intenta abrir CameraX real en Robolectric para simular ese caso.
Los recorridos UI miden el viewport del diálogo y recorren ambos temas, fuente
al 200 %, 320×480 dp y 640×320 dp. No representan una revisión visual humana.

`AdaptiveAccessTest` recorre login, registro, confirmación y cuenta con las
mismas combinaciones de tema/tamaño/fuente. Inyecta un inset IME sintético de
120 px, comprueba que Compose lo recibe y desplaza formularios/acciones hasta
hacerlos operables. Eso cubre layout y acciones IME, no un teclado físico ni
todos los teclados del sistema. `SolventaThemeTest` verifica cambio de tema
desde configuración de sistema y contraste calculado: texto ≥ 4.5:1 y contorno
de controles ≥ 3:1 para los pares semánticos medidos.

`MainActivityResumeTest` ejecuta el `onResume` real de MainActivity en Robolectric,
con AuthViewModel/API sintéticos inyectados en el ViewModelStore: una sesión
rechazada al reanudar retira actor, recorrido y foto; un invitado con almacén vacío
conserva cero llamadas a AuthApi. Otro caso interrumpe un alta antes del POST:
elimina archivos y borrador y rechaza su confirmación posterior. El diálogo
de foto no omite la restauración de sesión existente.

El corte anterior de una sola foto aprobó 55 pruebas debug y 22 de la variante
aislada. La primera sección de evidencia física corresponde solo a ese APK anterior,
no al tema nuevo ni al recorrido documento/selfie. La nueva etapa requiere
revisión y autorización de actualización propias; no se instaló en el teléfono
durante la implementación.

El nuevo corte local aprobó **61 pruebas debug y 50 aisladas**, con cero errores,
fallos u omisiones. Incluye acceso/registro, repositorio de autenticación,
protocolo/recorridos de huella, temas/layout, 14 casos del motor local, 24
recorridos UI de foto y 2 regresiones del onResume real. Ambos lints terminaron
sin errores, con 33 advertencias debug y 25 aisladas. Los dos APK y el manifiesto
release se generaron. El corte conserva las advertencias de dependencias y
ExifInterface; no actualiza AGP ni cambia el contrato por motivos visuales.

El primer caso nuevo de recreación mezclaba la cámara real de MainActivity con
el JPEG sintético: el callback de CameraX interfería con el estado esperado.
La prueba usa ahora la cámara-fixture existente y recreación real de la Activity,
sin retirar la regresión original de restauración/revocación. Lint también
detectó un atributo de navegación de API 27 en recursos base para minSdk 26;
se retiró y se conserva `enableEdgeToEdge()` para las barras en ejecución.

Artefactos de este corte, ambos con firma APK v2 y `zipalign -c -P 16 4` válidos:

| Variante | Tamaño | SHA-256 |
| --- | ---: | --- |
| `debug` | 11661508 bytes | `BD61CF2CDEDBD8C66F6862E42A4F6E90AE2F5921300F35427E8937B04F094AD0` |
| `biometricTest` | 12024615 bytes | `657F38CCA0ABF5846AA0DDE2B487318081619251D5527D3592339DF52B1AC817` |

La inspección del APK con aapt2 y lectura de DEX confirmó package aislado exacto
`co.solventa.mobile.pf2.biometrictest`, CAMERA/CameraX/PhotoReviewViewModel solo
en aislado y ausencia de FileProvider en ambos. Al cerrar este corte JVM todavía
faltaba revisar la apariencia y las dos capturas en el teléfono; el ensayo
nuevo documentado al final cubre parte de esa revisión. TalkBack y teclado
real visible siguen pendientes. Estos resultados no acreditan PAD, OCR,
identidad, subida al servidor ni consentimiento persistido.

`PhotoVariantConfigurationTest` comprueba flag, CAMERA, ausencia de FileProvider
y presencia/ausencia de clases CameraX en el classpath de cada variante.
`BuildConfigurationTest` conserva las comprobaciones de package, URL, issuer y
flags. Las regresiones previas de acceso/actores/huella no usan el flujo de foto.

```powershell
.\gradlew.bat --no-daemon --max-workers=2 :app:testDebugUnitTest --tests 'co.solventa.mobile.BuildConfigurationTest' --tests 'co.solventa.mobile.PhotoVariantConfigurationTest' --tests 'co.solventa.mobile.AccessScreensTest' --tests 'co.solventa.mobile.BiometricFlowUiTest' --tests 'co.solventa.mobile.feature.auth.AuthRepositoryTest' --tests 'co.solventa.mobile.feature.auth.BiometricProtocolTest' --tests 'co.solventa.mobile.AdaptiveAccessTest' --tests 'co.solventa.mobile.SolventaThemeTest' :app:testBiometricTestUnitTest --tests 'co.solventa.mobile.feature.photo.*' --tests 'co.solventa.mobile.BuildConfigurationTest' --tests 'co.solventa.mobile.PhotoVariantConfigurationTest' --tests 'co.solventa.mobile.AdaptiveAccessTest' --tests 'co.solventa.mobile.SolventaThemeTest'
.\gradlew.bat --no-daemon --max-workers=2 :app:lintDebug :app:lintBiometricTest :app:assembleDebug :app:assembleBiometricTest :app:processReleaseMainManifest
```

Los dobles y archivos generados no prueban una captura física ni sustituyen la
comprobación siguiente. Compilar el APK no autoriza una instalación: requiere
revisión y autorización específicas.

### Comprobación física de foto y revisión, 25 de octubre de 2026 (UTC)

Con autorización expresa y revisión del APK final, se realizó una única
actualización `adb install -r` de `co.solventa.mobile.pf2.biometrictest`, sin borrar
datos. Equipo SM-A217M, API 36. El SHA-256 del APK instalado, calculado también
en el dispositivo sin extraerlo, coincidió con el archivo aprobado:
`6410F971C6FDE639B9F52363A9C34B04112AAD6F08B824FB21E1754784407957`.

No se levantaron backend, Redis, helpers o túneles ni se usó autenticación o
huella. La ruta instalada, versión y fecha de actualización de la app habitual
permanecieron iguales antes y después. No se modificaron ajustes del sistema.

| Caso | Evidencia y responsable |
| --- | --- |
| Entrada sin sesión | El usuario abrió Foto y revisión desde el formulario de ingreso |
| Denegación y salida | El usuario denegó CAMERA una vez; confirmó el mensaje de permiso, la opción de reintentar/cancelar y el regreso al formulario sin completar ni iniciar sesión |
| Permiso y captura real | El usuario concedió el permiso en el cuadro normal de Android, confirmó cámara frontal en directo y tomó las fotos; confirmó una previsualización de su captura con orientación correcta |
| Repetición | ADB comprobó Revisar foto y un archivo temporal, pulsó Repetir foto y comprobó vuelta a cámara y cero archivos; no se accionó el disparador por ADB |
| Cancelación | ADB canceló desde la cámara tras repetir y comprobó formulario de ingreso, sin Proceso completado ni Mi cuenta, con cero archivos |
| Fondo y reanudación | ADB envió la cámara en directo al fondo y reanudó la misma actividad; volvió al inicio del recorrido, sin completar y con cero archivos. Se pudo abrir la cámara de nuevo |
| Aceptación y final | Tras otra captura del usuario, ADB comprobó revisión y un archivo, pulsó Usar esta foto y comprobó Proceso completado y cero archivos; Volver al acceso terminó en login sin Mi cuenta |
| Estado final | ADB comprobó caché de foto vacía y operación CAMERA no activa mediante metadatos de AppOps. La app aislada quedó instalada, en login y con la cámara cerrada |

La inspección automatizada usó únicamente etiquetas y límites de controles de
la jerarquía accesible, y conteos de archivos en la caché privada. No leyó fotos,
tokens ni preferencias. No hubo capturas de pantalla, grabaciones, extracción de
imágenes o instrumentación. Se eliminó el único XML temporal propio del teléfono.
La orientación y correspondencia de la imagen fueron observaciones del usuario,
no una inspección visual automatizada.

Una inspección inicial encontró ya el formulario y caché vacía antes de poder
ejecutar Repetir foto; no se contó como prueba de repetición ni se atribuyó una
causa. El recorrido posterior sí conservó la revisión y acreditó la transición
de un archivo a cero al repetir.

El estado final se comprobó a las 03:55:35 UTC. No se verificó físicamente la
limpieza al enviar una previsualización al fondo: el caso de fondo se ejecutó
con cámara en directo y sin archivo. Quedan pendientes otros equipos y
orientaciones, cámara ocupada/ausente, permiso revocado desde ajustes y TalkBack.
El fotograma transitorio de procesamiento se cubrió en JVM, no se midió en el
teléfono. Esta evidencia prueba el recorrido local, no PAD, identidad verificada,
confirmación backend ni cumplimiento completo de HU17.

### Comprobación física de temas, documento y selfie, 25 de octubre de 2026 (UTC)

Esta sección registra el ensayo nuevo comunicado por el coordinador después
de la entrega estable. Corresponde a `biometricTest` de 12024615 bytes,
SHA-256 `657F38CCA0ABF5846AA0DDE2B487318081619251D5527D3592339DF52B1AC817`;
no amplía la evidencia del APK anterior de una sola foto.

Con una nueva autorización expresa, el coordinador realizó una única
actualización `adb install -r` de `co.solventa.mobile.pf2.biometrictest`, sin
borrar datos. El hash calculado en el teléfono coincidió con el aprobado.
La app habitual conservó ruta instalada, versionCode 4, versión 0.2.2 y fecha
de actualización `2026-10-18 03:45:21`.

| Caso | Evidencia y responsable |
| --- | --- |
| Login vacío | ADB comprobó dos campos vacíos; enviar sin datos mostró un error, sin abrir Mi cuenta |
| Registro vacío y regreso | ADB comprobó cuatro campos vacíos; enviar mostró el error de nombre. El primer Atrás cerró la entrada y el segundo volvió al login |
| Cancelar desde el inicio | ADB pulsó Cancelar en la introducción del documento, sin archivo, y comprobó vuelta al login sin cuenta. No fue una cancelación desde cámara ni desde revisión |
| Cámara frontal y fondo | ADB abrió la cámara del documento con caché vacía y envió la app al fondo. AppOps indicó CAMERA no activa y el conteo siguió en cero; reanudar mostró documento y selfie pendientes, sin completar |
| Documento real sin datos | El usuario tomó manualmente una foto de un papel sin datos y confirmó revisión y orientación correctas. ADB comprobó revisión y un archivo; Usar esta foto dejó documento recibido, selfie pendiente y caché vacía, sin resumen anticipado |
| Selfie real | ADB abrió la cámara del segundo paso; el usuario tomó la foto manualmente y confirmó su orientación. ADB comprobó revisión y un archivo; aceptar dejó ambos pasos recibidos, caché vacía y cámara cerrada |
| Regreso final | ADB pulsó Volver al acceso y comprobó login con dos campos vacíos, sin Mi cuenta |
| Tema y apariencia | El coordinador revisó login vacío oscuro, registro vacío oscuro y login vacío claro mediante tres capturas locales de esas pantallas. Observó consistencia visual y no observó recortes en esas vistas; restauró el modo oscuro |

ADB automatizó navegación y comprobaciones de etiquetas, conteos de caché y
metadatos de cámara. El usuario accionó ambos disparadores y observó las fotos.
No hubo disparos por ADB, capturas de cámara/previsualización, grabaciones,
extracción o lectura de JPEG, tokens o preferencias. Las tres capturas de
formularios vacíos no se incorporaron al repositorio.

Al alternar el tema, la primera lectura inmediata no superó la comprobación
de foco durante la transición: el coordinador no accionó controles y restauró
oscuro en `finally`. Repitió la comprobación después de esperar el foco real.
El teléfono terminó en modo oscuro y la app aislada en login, con caché vacía
y cámara cerrada.

Al enfocar Nombre, los metadatos mostraron `mInputShown=true` pero
`mIsInputViewShown=false`. El formulario seguía completo y no necesitó
desplazamiento. Esto no acredita teclado ni sugerencias visibles; no se
cambiaron ajustes para forzarlos. El inset IME de 120 px corresponde solo a JVM.

No se repitieron físicamente Repetir foto, cancelar desde revisión ni fondo
con una previsualización retenida. Esos casos conservan únicamente su
evidencia JVM o histórica cuando corresponde. Tampoco se probaron TalkBack,
otros equipos u orientaciones nuevas.

El ensayo no usó backend, reverse, Redis, huella ni cuentas; no creó usuarios
mediante HTTP. La continuación física después de un POST de alta no se
ejercitó: POST único y login explícito posterior conservan la evidencia JVM.
El recorrido local no acredita PAD, OCR, identidad verificada, envío al
servidor, consentimiento persistido ni aceptación completa de HU17.

### Autocaptura guiada, presentación y arranque: comprobación nueva

Este cambio es posterior al APK `657F38…AC817` del ensayo anterior.
La toma manual de aquellas dos fotos no acredita la inferencia nativa ni
la autocaptura nueva. El refinamiento de textos, jerarquía, icono y splash
tampoco hereda la inspección visual del APK anterior.
En esta versión el documento cambia a cámara trasera; la selfie conserva
frontal. La selección está cubierta sin fallback y el error identifica la
lente requerida, pero el cambio de cámara necesita la comprobación física nueva.

La cobertura nueva separa responsabilidades:

| Prueba | Alcance |
| --- | --- |
| `SelfieGuidanceTest` | Cero, uno o múltiples rostros; centro/tamaño/pose/resolución, valores no finitos, ventana estable de 900 ms, reloj, obsolescencia, resets y un solo evento incluso con callbacks concurrentes |
| `SelfieGeometryTest` | Transformación real CameraX 1.4.2 con recorte, rotaciones 0/90/180/270, espejo y dos relaciones de aspecto; óvalo dentro del contenido, no de las barras; la ampliación de vista no sustituye resolución facial |
| `SelfieAnalyzerTest` | Detector simulado para lifecycle: un fotograma pendiente, cierre, callbacks tardíos, error y timeout explícitos, imagen ausente e inactividad. No ejecuta reconocimiento nativo |
| `DocumentFrameOverlayTest` | Siete casos del marco rectangular: tamaños/relaciones de aspecto, barras horizontales/verticales, recortes, intersección y geometría inválida. No detectan documentos |
| `DocumentFrameAnalyzerTest` | Área real mediante la transformación CameraX compartida, solo metadatos y cierre de cada proxy; intento inactivo/cerrado, transform ausente/timeout y error explícito |
| `PhotoReviewTest` | Evento estable conectado al motor: un archivo, revisión obligatoria, aceptación explícita y borrado; duplicados/generación anterior no crean otro archivo |
| `PhotoFlowUiTest` | Fotos antes del POST, confirmación explícita y POST único sin sesión; permiso/error/cancelación/limpieza, privacidad accesible antes de selfie y parámetros de layout existentes |
| `MainActivityResumeTest` | Recreación y revalidación auténtica al reanudar: revocación retira la cuenta y elimina el archivo; el fixture comprueba usar la misma Application que la actividad |
| `LaunchConfigurationTest` | API 26/31/35: orientación solicitada, iconos, paletas, tema de contenido y arranque frío/restaurado sin HTTP; fundido con animaciones activas y retirada inmediata con escala cero |

Los tests de UI usan una cámara fixture que genera JPEG sintético; su botón
no representa un disparador manual de selfie en el motor real. La detección
FAST del SDK incluido debe comprobarse por separado con el teléfono controlado
por el coordinador. No se usan emuladores en esta torre.

Quedan pendientes en el SDK real: cero/múltiples rostros, centrado, distancia,
pose, continuidad y movimiento, una única foto por intento, repetición,
cancelación, fondo/reanudación y limpieza. También debe probarse inferencia
con el modelo incluido sin red; esto no demuestra ausencia de telemetría.
No se acreditan TalkBack, teclado en pantalla visible en el teléfono, fidelidad píxel a píxel,
PAD, reconocimiento de identidad ni comparación entre documento y selfie
mediante los fixtures JVM.

Selectores de comprobación sin teléfono, desde la raíz Android y con JDK 17:

```powershell
.\gradlew.bat --no-daemon --max-workers=2 :app:testBiometricTestUnitTest `
  --tests "co.solventa.mobile.feature.photo.Selfie*Test" `
  --tests "co.solventa.mobile.feature.photo.DocumentFrame*Test" `
  --tests "co.solventa.mobile.feature.photo.PhotoDialogLayoutTest" `
  --tests "co.solventa.mobile.feature.photo.PhotoReviewTest" `
  --tests "co.solventa.mobile.feature.photo.PhotoFlowUiTest" `
  --tests "co.solventa.mobile.feature.photo.MainActivityResumeTest"
.\gradlew.bat --no-daemon --max-workers=2 :app:testDebugUnitTest `
  --tests "co.solventa.mobile.LaunchConfigurationTest"
.\gradlew.bat --no-daemon --max-workers=2 :app:lintDebug :app:lintBiometricTest `
  :app:assembleDebugAndroidTest :app:processReleaseMainManifest
```

Compilar el APK instrumentado no equivale a ejecutarlo. Este editor no usa
ADB ni instala la app; la inferencia nativa y revisión física corresponden
al coordinador, sin tocar la app habitual ni cuentas o backend.

#### Corte DE389 anterior a la corrección del viewport, 25 de octubre de 2026 (UTC)

Con la decisión confirmada «Marco guía y captura manual», el documento usa
CameraX trasera con marco estático sobre su imagen real y botón explícito;
la selfie usa frontal y autocaptura. El marco no indica detección ni aprobación.
No se incorporó OCR, Document Scanner u otro SDK documental.

El comando integrado terminó con código 0. XML: **79 pruebas debug y 99
biometricTest, 178 en total; cero fallos, errores o pruebas omitidas**.
Incluye las siete pruebas del marco entregado y cuatro del analizador de
metadatos/cierre, además de regresiones de alta, huella, sesión, recreación,
ambos temas, fuente al 200 %, layouts pequeños/horizontales e IME sintético.
Lint: cero errores, 37 advertencias debug y 30 biometricTest. Persisten avisos
de dependencias, EXIF y otros anteriores; la orientación fija pedida también
genera avisos de adaptabilidad. No se deshabilitaron reglas ni se elevó minSdk.

Se ejecutaron `:app:testDebugUnitTest` con los selectores
`BuildConfigurationTest`, `PhotoVariantConfigurationTest`, `AccessScreensTest`,
`BiometricFlowUiTest`, `feature.auth.AuthRepositoryTest`,
`feature.auth.BiometricProtocolTest`, `AdaptiveAccessTest`, `SolventaThemeTest`
y `LaunchConfigurationTest`, bajo `co.solventa.mobile`.
Para `:app:testBiometricTestUnitTest` se seleccionaron `feature.photo.*`,
`BuildConfigurationTest`, `PhotoVariantConfigurationTest`, `AdaptiveAccessTest`,
`SolventaThemeTest` y `LaunchConfigurationTest`. El mismo comando incluyó
`:app:lintDebug :app:lintBiometricTest :app:assembleDebug :app:assembleBiometricTest
:app:assembleDebugAndroidTest :app:processReleaseMainManifest
:app:processPreviewMainManifest`, con `--no-daemon --max-workers=2 --quiet`.
Los XML están en `app\build\test-results\testDebugUnitTest` y
`app\build\test-results\testBiometricTestUnitTest`; los informes de lint,
en `app\build\reports\lint-results-debug.xml` y
`app\build\reports\lint-results-biometricTest.xml`.

| Artefacto | Bytes | SHA-256 |
| --- | --- | --- |
| `app\build\outputs\apk\biometricTest\app-biometricTest.apk` | 50676008 | `DE389B0F4E6B55B65E697F3FEBFC7994F067B146D8AE69A1BF9D931BC919045B` |
| `app\build\outputs\apk\debug\app-debug.apk` | 12064973 | `6606811BC495D334930D978D1DA750EA91672F198D050AC438262C4A6500CB7C` |

El aislado es `co.solventa.mobile.pf2.biometrictest`, versión 0.3.1/code 6,
minSdk 26/target 35; mtime UTC `2026-10-25T06:03:49Z`. Ambos APK verifican
firma V2 y `zipalign -c -P 16 4`. Certificado SHA-256:
`46650A68B4EF84A64511635598FC412917D8F80717653EA367440CBC3E70AE9A`.
El APK normal se generó para comprobar aislamiento, no para reemplazar la app
habitual instalada.

El DEX normal no incluye CameraX, ML Kit ni el analizador documental;
el aislado sí e incluye 14 archivos de modelo bundled. Los classpaths runtime
release/preview no incorporan CameraX/ML Kit y sus manifiestos finales no
incluyen CAMERA ni componentes de esas bibliotecas. El debug normal tampoco
declara CAMERA.

Comprobación ELF del aislado: 16 bibliotecas/44 segmentos LOAD; las ocho ELF64
arm64-v8a/x86_64 tienen 22 LOAD con alineación >= 16384 y congruencia
offset/dirección. El redondeo RELRO no invade otros datos escribibles.
El JNI ML Kit de ABI32 conserva alineación 4096: no se afirma que todos los
ELF sean de 16 KB. La [guía Android](https://developer.android.com/guide/practices/page-sizes)
centra el requisito en arm64-v8a/x86_64. Son comprobaciones de empaquetado,
no una ejecución en un sistema de páginas de 16 KB.

Ese artefacto se entregó para comprobación física y posteriormente mostró
el recorte descrito abajo; no debe considerarse el APK corregido.
El corte automatizado no acredita inferencia nativa, ajuste de
umbrales, encuadre trasero real, autocaptura física u orientación del JPEG nuevo.
La evidencia anterior sigue vinculada a sus APK, no a este hash.

#### Hallazgo físico de recorte en DE389 y corrección de la raíz del diálogo

El coordinador instaló una vez `DE389B0F…919045B` mediante actualización,
sin borrar datos; verificó el hash remoto y que la app habitual permaneciera
intacta. En el teléfono API 36, modo oscuro y páginas de 4 KB, comprobó login
vacío correcto y abrió la introducción de documento. **Antes de abrir cámara,
«Abrir cámara» apareció recortado y sin posibilidad de scroll**.
El viewport era `[0,68][719,1600]` (1532 px de alto); el botón solo mostraba
51 px y su texto 23 px, frente a los 91/35 px esperados en ese equipo.
Cancelar conservaba su tamaño. No se abrió cámara ni se capturaron imágenes;
caché cero y cámara no activa. No fue un ensayo completo de fotos o del SDK.

La reproducción aisló la diferencia entre medida nativa y configuración de
pantalla. En Compose UI 1.7.8, `DialogLayout.internalOnMeasure`, al usar
`usePlatformDefaultWidth=false`, reemplaza el límite de altura recibido por
`configuration.screenHeightDp`. El test nuevo impuso viewport nativo de
1532 px frente a configuración de 1600 px y falló antes del cambio:
`expected 1532 but was 1600`. El pie se ubicaba al fondo de ese alto excesivo
y el scroll calculaba rango cero.

La corrección queda en `PhotoReviewHost`: el diálogo honra las restricciones
nativas y solicita ancho/alto `MATCH_PARENT` mediante la API pública de su
ventana. Conserva `SecureOn`, fondo edge-to-edge y cierre; no cambia
`SolventaPage`, sus insets, márgenes, tipografía o el mínimo de 52 dp.
Tampoco cambia MainActivity, launcher, cámara, restore, huella o contratos.

`PhotoDialogLayoutTest` agrega tres comprobaciones en Robolectric API 35:
inset de estado real de 68 px con botón y texto íntegros sin scroll si caben;
viewport nativo 1532 frente a pantalla configurada de 1600; y selfie al
200 % en ventana de 640 px que exige rango positivo y desplazamiento real
hasta mostrar todo el CTA. Compara tamaño de layout y bounds visibles del
control/texto, verifica que el propio viewport quepa en la ventana y conserva
`MATCH_PARENT`/`FLAG_SECURE`. No se limita a `assertIsDisplayed`, que admite
visibilidad parcial. La medición corregida aún requiere repetición física
por el coordinador; el hallazgo DE389 no se convierte en éxito por estos tests.

#### Entrega corregida del viewport, 25 de octubre de 2026 (UTC)

El corte `photo-dialog-fix-final` terminó con código 0. Ejecutó el mismo
comando integrado y los selectores del corte anterior; `feature.photo.*`
incluye ahora `PhotoDialogLayoutTest`. Resultado de los 23 XML:
**79 pruebas debug + 102 biometricTest = 181; cero fallos, errores y omitidas**.
Lint conserva cero errores y 37/30 advertencias, respectivamente.
Se generaron ambos APK, el APK instrumentado debug y manifiestos
release/preview. No se ejecutó instrumentación en dispositivo.

| Artefacto | Bytes | SHA-256 |
| --- | --- | --- |
| `app\build\outputs\apk\biometricTest\app-biometricTest.apk` | 50676008 | `7A5D207F04BB597960B1FF135DFDBA0385837DA4F5864D063475669BEA0C9832` |
| `app\build\outputs\apk\debug\app-debug.apk` | 12064973 | `6606811BC495D334930D978D1DA750EA91672F198D050AC438262C4A6500CB7C` |

El aislado conserva paquete, versión/code y certificado del corte anterior;
mtime UTC `2026-10-25T06:21:51.4003784Z`. Se verificaron nuevamente firma V2,
`zipalign -c -P 16 4` y alineación/congruencia de sus 22 LOAD ELF64
(mínimo 16384). El APK normal es idéntico byte a byte al anterior;
el DEX sigue sin CameraX/ML Kit y el aislado sí contiene esas bibliotecas.
No se modificaron dependencias, modelos ni recursos en esta corrección.
Los XML permanecen en las rutas indicadas arriba.

Este corte se congeló para que el coordinador
repita la introducción y el flujo en el teléfono. Este editor no hizo ADB
ni instalación. **Pendientes:** confirmar el arreglo en API 36 físico,
inferencia/calibración nativa de selfie y el recorrido nuevo de fotos.
Ni estos tests JVM API 35 ni el empaquetado acreditan runtime de páginas
16 KB, TalkBack o verificación de identidad.

#### Corrección funcional de encuadre posterior al corte de 181 pruebas

Se detectó que centro y tamaño aceptables por separado admitían una caja
facial fuera de la guía: por ejemplo, centro X 0.6 y ancho 0.96 dan borde
derecho 1.08. `SelfieGuidance` exige ahora que los cuatro bordes normalizados
permanezcan entre 0 y 1. El desborde por posición devuelve `CENTER` y rompe
la ventana estable; el exceso de tamaño conserva `FARTHER`.
No se exige contención de esquinas en la elipse ni se añade reconocimiento.

Dos regresiones cubren los cuatro lados desbordados durante 1800 ms,
recuperación válida de 900 ms con un único disparo, reinicio de una ventana
ya iniciada y prioridad de exceso de tamaño. Los bordes exactos válidos
conservan cobertura. Las nueve pruebas reales de `SelfieGuidanceTest`
pasaron con JUnit 4.13.2, compilando sus fuentes y el motor mediante el
compilador Kotlin 2.1.20 ya disponible, sin Gradle ni Android.

Este ensayo puro no es un corte de la aplicación: no genera APK ni sustituye
la regresión integrada. El hash `7A5D207F…0C9832` no contiene esta corrección
ni el flujo de registro solicitado posteriormente. El corte combinado
que sigue incorpora ambos cambios.

#### Corte funcional del flujo de alta y fotos, 25 de octubre de 2026 (UTC)

El flujo aislado queda: referencia provisional de términos → formulario →
borrador solo en RAM → documento/revisión → selfie/revisión → resumen →
confirmación explícita → un POST de alta → `REGISTERED` sin sesión →
login separado. `PhotoReviewHost` conserva la apertura en `REGISTER_PHOTOS`;
solo permite continuar con ambos recibos, preview ausente, sin error y
limpieza confirmada. Cancelar, dismiss, fondo o recreación descartan el
borrador. Un fallo de limpieza al salir del resumen impide confirmar el alta.
Login y Mi cuenta no ofrecen la entrada suelta a fotos.

La referencia legal carece de casilla, aceptación persistida o enlace externo.
El coordinador contrastó la app original `solventa-app@6a3521af`, cuyas casillas
no incluyen texto/versionado/contrato; el contenido académico web/i18n no
acredita términos aprobados y las rutas candidatas `/legal/terms` y
`/legal/privacy` de `juanes545.github.io/solventa-web` devolvieron 404.
El usuario autorizó referencias provisionales. La pantalla advierte
«Continuar no registra una aceptación legal». Siguen pendientes contenido
propio aprobado, publicación y contrato de consentimiento.

**Resultado funcional: 87 pruebas debug + 103 biometricTest = 190,
cero fallos, errores u omitidas en los 20 XML finales.** El conjunto es
dirigido, no una repetición de la matriz visual anterior.
Debug selecciona `AuthViewModelTest`, `AuthRepositoryTest` y
`BiometricProtocolTest` en `co.solventa.mobile.feature.auth`, y
`AccessScreensTest`, `BiometricFlowUiTest`, `BuildConfigurationTest`,
`PhotoVariantConfigurationTest` y `LaunchConfigurationTest` en
`co.solventa.mobile`. La aislada selecciona `co.solventa.mobile.feature.photo.*`
y esos tres últimos tests de configuración/arranque.

La cobertura conserva la recaptura y prueba POST único, ausencia de grants
antes del login, recreación/interrupción del borrador, cambio de actor,
limpieza fallida, contención facial y viewport nativo del diálogo.
Se corrigió un selector que aún usaba el texto largo de recaptura, sin
retirar la acción ni sus assertions. `RegistrationFlowTest`, `AppLaunchTest`
y `LoginLayoutTest` pertenecen a `androidTest`: se actualizaron y compilaron,
pero no se ejecutaron en dispositivo. No se hicieron capturas, inspección
visual nueva, calibración, ADB ni pruebas contra backend real.

Lint: cero errores, 37 advertencias debug y 30 biometricTest.
El comando de cierre `registration-photo-artifacts-final` terminó con código 0:

```powershell
.\gradlew.bat --no-daemon --max-workers=2 --quiet `
  :app:lintDebug :app:lintBiometricTest `
  :app:assembleDebug :app:assembleBiometricTest :app:assembleDebugAndroidTest `
  :app:processReleaseMainManifest :app:processPreviewMainManifest
```

Las pruebas debug quedaron verdes en `registration-photo-final`; las de
la aislada, en `registration-photo-final-retry`, antes de corregir dos imports
de los tests instrumentados. El cierre compiló esos tests sin repetir
las suites ya verdes. Los XML siguen en las rutas indicadas en el corte anterior.

| Artefacto | Bytes | SHA-256 |
| --- | --- | --- |
| `app\build\outputs\apk\biometricTest\app-biometricTest.apk` | 50677832 | `281EAD2FBEE8F256F79C6A741AAA305CE187273E16A664E8DEC4CE327EF82A5E` |
| `app\build\outputs\apk\debug\app-debug.apk` | 12067265 | `BDCD4278325A612FF1F92C1BAE4B49DF406A5598C9C82E9FA4A34151A51B0042` |

El aislado conserva `co.solventa.mobile.pf2.biometrictest`, versión 0.3.1/code 6,
minSdk 26/target 35; mtime UTC `2026-10-25T06:41:29.3839654Z`.
Ambos APK verifican firma V2, certificado `46650A68…70AE9A` y ZIP16KB.
El normal no declara CAMERA ni contiene CameraX, ML Kit, los motores de
encuadre o modelos; el aislado sí y conserva 14 assets bundled.
Los 22 LOAD de sus ocho ELF64 arm64-v8a/x86_64 tienen alineación mínima
16384 y congruencia offset/dirección. El normal conserva dos ELF64/seis LOAD
con esas propiedades. Esto no acredita ejecución con páginas de 16 KB.

Este artefacto sustituye a DE389 y 7A5D para el nuevo flujo; no hereda sus
ensayos físicos. Quedan pendientes la comprobación física del viewport/flujo,
inferencia y ajuste nativo de selfie, runtime16KB y TalkBack.
Las fotos locales y la referencia legal no acreditan PAD, identidad, KYC,
consentimiento legal registrado ni aceptación completa de SOL-169.

#### Actualización aislada y arranque, 25 de octubre de 2026 (UTC)

A las 06:45:13 UTC se actualizó únicamente
`co.solventa.mobile.pf2.biometrictest` mediante `adb install -r`, sin
desinstalar ni borrar datos. El APK instalado tiene SHA-256
`281EAD2FBEE8F256F79C6A741AAA305CE187273E16A664E8DEC4CE327EF82A5E`,
versión 0.3.1/code 6; el hash se calculó también en el teléfono.

La app habitual conservó ruta, versión 0.2.2/code 4, fechas de instalación
y actualización, y SHA-256
`CD08ABAEAEF63224DC649B5619106D036D08F2FC19D67269A52851BA9ABEF835`.
No se instaló el APK normal.

A las 06:45:51 UTC, el arranque de `MainActivity` devolvió `Status: ok`,
el proceso siguió activo y `cache/photo-review` estaba vacío. No se tomaron
fotos ni capturas de pantalla, no se enviaron formularios y no se crearon
cuentas. La revisión visual adicional se omitió por el alcance solicitado.

Este chequeo acredita actualización y arranque, no el recorrido físico de
captura, el viewport en API 36 ni la inferencia o calibración nativa.
El teléfono usa páginas de 4096 bytes: no acredita runtime de 16 KB.

#### Reapertura: formulario, casillas y foco del teclado

Por petición del usuario, el formulario precede a términos y privacidad.
Los cuatro campos se validan antes de mostrar las dos casillas de referencia.
Solo la de términos habilita «Continuar»; Open Finance sigue siendo opcional,
como en `solventa-app@6a3521af`, `AuthScreens.kt:340-358`. Las selecciones
no se transmiten, no registran consentimiento legal ni autorizan consultas
financieras. El borrador conserva las credenciales solo en RAM; regresar al
formulario conserva los valores y volver a términos reinicia las casillas.
La app mantiene fotos antes del POST aislado, envío único y registro sin sesión.

Los campos usan Material 3, como la referencia original. El correo vacío
muestra `nombre@ejemplo.com`; cada contraseña tiene un ojo con nombre accesible
y estado independiente, sin el botón de texto. No se añadieron dependencias.

El primer corte combinado de esta revisión produjo 88 casos debug y 169
aislados, sin fallos, errores ni omisiones (257 en total), y compiló los
instrumentados sin ejecutarlos. Lint dio 0 errores y 37/30 advertencias.
El corte incluye alta, repositorio, acceso, adaptación, insets/foco, coherencia
de variantes, arranque, huella y las regresiones de fotos. Estos resultados no
acreditan resolución del teclado físico ni fidelidad visual de la nueva pantalla.

En el APK histórico `281EAD…F82A5E`, el coordinador enfocó campos vacíos,
sin escribir ni enviar. El proceso y la ventana permanecieron estables,
sin crash o recreación observados. Con IME visible, Android declaró la fuente
IME `[0,68][720,1600]`: 1532 px, además de 68 px superiores. Los formularios
dejaron de exponer sus hijos; cerrar el IME los recuperó vacíos.
No hubo capturas de pantalla, fotos o cambios de ajustes.

La lectura del dispositivo mostró LatinIME, `keyboard=QWERTY`,
`keyboardHidden=NO`, `hardKeyboardHidden=NO` y
`show_ime_with_hard_keyboard=0`. El IME declaró ventana visible pero
`mIsInputViewShown=false` y modo fullscreen falso. La rama oficial
`InputMethodService.onEvaluateInputViewShown`, Android 16, líneas 2475-2485,
devuelve falso con esa configuración. Compose UI 1.7.8 ya añade
`IME_FLAG_NO_FULLSCREEN` en `TextInputServiceAndroid.android.kt:565`.

La reproducción JVM, a 280 dpi y con esos insets, confirmó viewport
`Rect(0,0,0,0)` en login y registro. Su XML diagnóstico se conservó fuera
del repositorio en `input-ime-all-window-reproduction.xml`.
`InputViewportTest` comprueba por separado campos completos y foco con un
IME simulado de 650 px, y conservación de valores/sin envío al cerrar una
fuente que ocupa toda la ventana. No afirma que un campo sea visible en un
viewport de cero ni que esos 650 px se hayan medido en el teléfono.
La reproducción de viewport cero no se presenta como una regresión solucionada.

La referencia remota `solventa-app@6a3521af` es v0.3.0/code 3, distinta de
la habitual instalada v0.2.2/code 4. La versión fuente PF2 `65a0686` sí
declara 0.2.2/code 4 y ya usa `safeDrawingPadding().imePadding()` y
`adjustResize`; no acredita por sí sola correspondencia binaria con el APK
habitual. El coordinador comprobó `ADJUST_RESIZE` en ambos APK instalados.
No se cambió el SO, no se eliminó consumo legítimo de insets ni se añadió un
clamp o un fallback por tamaño. El contraste posterior descrito abajo también
reprodujo el fenómeno en la habitual. No demuestra una regresión causada por
la nueva distribución, el splash o sus temas.

**Candidato de interfaz, no resolución acreditada del IME.** Tras ajustar
el título nativo y extender el caso funcional adaptativo a las casillas,
se ejecutó solo el subconjunto afectado, 23 casos por variante (46),
sin fallos, errores u omisiones:

```powershell
.\gradlew.bat :app:testDebugUnitTest `
  --tests co.solventa.mobile.AccessScreensTest `
  --tests co.solventa.mobile.AdaptiveAccessTest `
  --tests co.solventa.mobile.InputViewportTest `
  :app:testBiometricTestUnitTest `
  --tests co.solventa.mobile.AccessScreensTest `
  --tests co.solventa.mobile.AdaptiveAccessTest `
  --tests co.solventa.mobile.InputViewportTest `
  :app:compileDebugAndroidTestKotlin :app:lintDebug :app:lintBiometricTest `
  :app:assembleDebug :app:assembleBiometricTest --console=plain --quiet
```

El comando terminó con código 0. El corte combinado anterior también procesó
los manifiestos release/preview. Los XML se conservaron fuera del repositorio:
`registration-checks-functional-257.zip` (88+169) y
`registration-checks-ui-final-46.zip` (23+23). Los 46 son un subconjunto
repetido tras el último ajuste de UI, no 46 casos únicos adicionales.
Lint mantuvo 0 errores y 37/30 advertencias.

| APK candidato, no instalado por este editor | Bytes | SHA-256 |
| --- | ---: | --- |
| `app\build\outputs\apk\biometricTest\app-biometricTest.apk` | 50680183 | `CEE030C92C5EC39AD439D59C23F2E746FCF682C15154AF50A067ACA931DDD15B` |
| `app\build\outputs\apk\debug\app-debug.apk` | 12069612 | `99FBB26672BF0E5E92C2A1E21C20A183FC086E59E39211A77C516819CE90F2F5` |

Ambos declaran versión 0.3.1/code 6, min 26, target 35, orientación vertical
y `ADJUST_RESIZE`. La variante normal es `co.solventa.mobile.pf2`; la aislada,
`co.solventa.mobile.pf2.biometrictest`. No se instaló ninguno durante este corte.
`apksigner verify` confirmó V2 y certificado SHA-256
`46650A68B4EF84A64511635598FC412917D8F80717653EA367440CBC3E70AE9A`;
`zipalign -c -P 16 4` terminó con código 0 en ambos.
La normal no contiene CAMERA, DEX CameraX/ML Kit/guía selfie ni assets.
La aislada incluye CAMERA, esos tipos y 14 assets `models_bundled`.
Sus ocho ELF64 tienen 22 segmentos `PT_LOAD`, alineación mínima de 16384 bytes
y congruencia offset/dirección; sus ELF32 conservan mínimo 4096.
La normal contiene dos ELF64 y seis segmentos `PT_LOAD`, mínimo 16384.
Estos controles estáticos no acreditan ejecución en páginas de 16 KB.

No se modificaron `SolventaPage`, `MainActivity`, huella, restore, manifiestos,
dependencias ni políticas de insets para intentar compensar la fuente IME total.
No se aplicó una corrección especulativa y no se afirma que el problema físico
esté resuelto. El primer intento autorizado de contraste de la app habitual
se abortó antes de abrirla porque otra app tenía el foco; no aporta baseline.
El coordinador completó después el contraste autorizado: en la habitual
`CD08…F835`, el login pasó de 15 nodos y dos inputs vacíos a tres nodos
sin inputs en dos lecturas estables al enfocar el correo. El PID y el foco
permanecieron iguales; cerrar el IME recuperó 23 nodos y dos inputs vacíos.
No escribió ni envió nada, no inició o cerró sesión, no instaló un APK y
volvió a la aislada `281EAD…F82A5E`. Ambos hashes instalados permanecieron iguales.

La habitual estaba en landscape, ventana `[0,0][1600,720]` y fuente IME
`[0,25][1532,720]`, de 695 px; la aislada estaba en portrait, 720×1600,
con fuente de 1532 px. Es el mismo patrón de agotamiento de altura, no un
A/B de geometría idéntica. En ambas ventanas se comprobaron `ADJUST_RESIZE`
y los flags `EDGE_TO_EDGE_ENFORCED`/`FIT_INSETS_CONTROLLED`.
Con esa imposición, Android 16 ignora cambios a `setDecorFitsSystemWindows`
(`PhoneWindow.java:4155-4158`); no se añadió un parche al splash.

El usuario decidió dejar de lado el teclado y continuar con la entrega de
interfaz. El diagnóstico queda registrado, sin presentarlo como resuelto
y sin más comprobaciones de teclado en este alcance. No se cambió ninguna
preferencia del SO ni se reinició el teléfono.

#### Actualización aislada del candidato de interfaz, 25 de octubre UTC

Después del cierre del editor, el coordinador actualizó únicamente
`co.solventa.mobile.pf2.biometrictest` mediante `adb install -r`. El hash del
APK instalado se confirmó contra el artefacto congelado:
`CEE030C92C5EC39AD439D59C23F2E746FCF682C15154AF50A067ACA931DDD15B`,
50680183 bytes. La verificación final terminó el
`2026-10-25T07:49:43Z`, con proceso activo y login vacío.
No se reconstruyó la aplicación ni se repitió la instalación.

La app habitual conservó su APK `CD08…F835`, ruta, versión 0.2.2/code 4
y fechas de instalación y actualización. No se instaló el APK normal.

El recorrido físico acotado comprobó dos campos vacíos en login, navegación
a «Crear cuenta», cuatro campos vacíos antes de cualquier casilla y regreso
al login. Se verificó un control de visibilidad en login y dos en registro,
con sus áreas interactivas dentro de los campos de contraseña. El primer
selector suponía que descripción y acción estaban en el mismo nodo; la
inspección corregida enlazó el icono descrito con su ancestro pulsable.
Esto no requirió cambios de producto ni otra instalación.

No se enfocaron campos, escribieron datos, enviaron formularios, tomaron
fotos ni capturas de pantalla. No hubo cambios de preferencias del SO ni
reinicio. Este recorrido no acredita el envío real del alta, las casillas
en el teléfono, la fidelidad visual, calibración del SDK, PAD/KYC,
TalkBack ni ejecución con páginas de 16 KB. El teclado sigue aplazado:
no se realizó la prueba temporal del ajuste y no se presenta este APK
como su solución.

#### Alta y acceso biométrico: corte de regresión integrado

Se revisó el recorrido existente sin cambiar código de producto ni entorno:
la variante aislada usa `http://127.0.0.1:18717/movil/v1` para alta, contraseña
y biometría, con issuer exacto `https://identidad.solventa.test`.
El coordinador prepara BFF e Identidad locales con Redis real temporal en Ally.
No es una validación de AWS ni de Postgres. La cuenta sintética única debe
crearse desde el Android físico; estas pruebas no crearon usuarios en servidores.

Se corrigió `espresso/AuthJourney.register`: el helper todavía intentaba enviar
«Crear cuenta» desde el formulario y omitía la nueva pantalla de casillas.
Ahora pulsa «Continuar», comprueba que los términos bloquean el siguiente paso,
los selecciona y continúa. Los formularios inválidos siguen sin enviar el alta.
Se compiló `:app:compileDebugAndroidTestKotlin`; no se ejecutó instrumentación.

`BiometricFlowUiTest.newAccountUsesOneRegistrationThenExplicitPasswordAndOptionalBiometricLogin`
recorre formulario, términos, un único alta sin sesión, correo preparado y
contraseña vacía, login explícito, rechazo voluntario de habilitar huella,
habilitación, logout que conserva la credencial y nuevo login biométrico.
`PhotoFlowUiTest.registrationCanCancelRetryAndFinishWithOneExplicitPostAndSeparateLogin`
amplía el recorrido aislado: cancelación/reintento de fotos locales, revisión,
confirmación previa al único POST, login y, en portrait claro/oscuro, habilitación
opcional y login firmado. El doble verifica firmas P-256/SHA256 sobre el desafío;
no sustituye al backend real ni acredita AndroidKeyStore/BiometricPrompt físico.
Las fotos siguen siendo revisión local, no PAD/KYC, y las casillas provisionales
no registran consentimiento legal ni conceden acceso financiero.

Validación focalizada final: **44 debug + 72 biometricTest**, cero fallos,
errores u omisiones. Se seleccionaron `BiometricFlowUiTest`, `AuthViewModelTest`
y `AuthRepositoryTest` en ambas variantes; en la aislada también `PhotoFlowUiTest`.
No se suman a históricos como pruebas nuevas únicas.

Durante la ampliación se corrigió un selector que intentaba desplazar el botón
fijo «Ahora no». El diálogo biométrico en el fixture artificial landscape a
200% provocó falta de reposo de Compose y agotamiento del heap; no se atribuyó
a un fallo físico ni se parcheó producto por ese resultado. Las comprobaciones
landscape conservan el recorrido de fotos/alta/login; la extensión biométrica
se ejecuta en portrait, la orientación fijada por el manifiesto instalado.
No se afirma cobertura del diálogo biométrico landscape a 200%.

No se reconstruyó ni reinstaló ningún APK: solo cambiaron pruebas y este
registro. El aislado vigente conserva SHA-256
`CEE030C92C5EC39AD439D59C23F2E746FCF682C15154AF50A067ACA931DDD15B`
y 50680183 bytes.

Pendiente físico, a cargo del coordinador cuando Redis Ally responda:
crear una sola cuenta sintética desde formulario/términos/fotos locales y
confirmación; comprobar alta sin sesión; entrar con contraseña usando el correo
preparado; comprobar que rechazar la huella conserva acceso convencional;
habilitarla explícitamente confirmando contraseña y prompt del SO; salir;
entrar con huella y comprobar autorización del backend. No ejecutar
`AuthBackendE2eTest` en esta ronda: su cuenta aleatoria crearía un usuario
adicional. No se ejecutó `BiometricLocalBackendTest` con otra cuenta.
El coordinador informó que Redis Ally no responde por LAN/Tailscale: este
corte no acredita todavía el recorrido real completo en dispositivo.
No se usó ADB desde el editor, no se modificaron ajustes del SO y el teclado
permanece fuera del alcance por decisión del usuario.
