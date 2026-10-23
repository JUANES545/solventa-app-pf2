# Pruebas y cobertura Android

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
de `feature/auth`, `MainActivity` y el manifiesto (solo permiso INTERNET).

| Requisito de la fuente | Estado en Android / evidencia |
| --- | --- |
| Acceso del cliente móvil (enunciado p. 9) | Registro, login, cuenta, sesión cifrada y logout disponibles; suites anteriores y nuevos recorridos HTTP/E2E |
| Cámara, video, evidencia geoetiquetada y escaneo (p. 9) | Ausentes: sin pantalla, permiso ni integración. No cubiertos |
| Biometría y selfie/prueba de vida (p. 9) | Botón de acceso biométrico deshabilitado; no hay integración. Se verifica el estado deshabilitado, no autenticación biométrica |
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
