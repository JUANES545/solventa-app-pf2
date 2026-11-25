# Android setup

## Requirements

- JDK 17
- Android SDK platform 35 and build tools 35.0.0
- Android Studio or the Android command-line tools
- An Android 8.0 (API 26) or newer device or emulator for device tests

Open the repository root in Android Studio and synchronize Gradle. The project uses the checked-in Gradle Wrapper; a separate Gradle installation is not required. Android Studio may create `local.properties` with the local SDK path. That file is not versioned.

For PowerShell command-line builds, configure `JAVA_HOME` and `ANDROID_HOME` for your own machine.

```powershell
./gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew.bat connectedDebugAndroidTest
```

The second command requires a dedicated test emulator or device without real accounts:
instrumented authentication journeys clear this app's local session. Compiling instrumentation
tests does not execute them. See [testing.md](testing.md) for the Espresso + Compose HTTP
suite and the separately selected E2E against a disposable real backend.

## Gateway de `release` y `preview`

El cliente usa un solo BFF HTTPS. Gradle acepta estas propiedades (`-P`) o variables de entorno con el mismo nombre:

| Entrada | Fuente y uso |
| --- | --- |
| `SOLVENTA_API_ENDPOINT` | Salida `api_endpoint` de `terraform/environments/prod/primary`, o `deployment_context.api_endpoint` en `nueva-cuenta/primary`. No incluir `/movil/v1`: Android lo añade una vez. |
| `SOLVENTA_JWT_ISSUER` | `jwt_issuer` del mismo entorno (`deployment_context.jwt_issuer` en nueva cuenta). Debe coincidir exactamente, no se deriva del Gateway. |
| `RECOVERY_WEB_URL` | Opcional. URL HTTPS con el origen y la ruta de `identidad-config` usados por el correo. Sin ella no se ofrece recuperación. |
| `BIOMETRIC_LOGIN_ENABLED` | Opcional, `false` por defecto. Usar `true` solo si el BFF del mismo entorno admite acceso biométrico. Con `false` se conserva el acceso por contraseña y no se ofrece enrolamiento biométrico. |

Sin Gateway o issuer no se genera el APK `release` ni `preview`. CI toma estas entradas de variables de repositorio. No son credenciales. Una prueba AWS en dispositivo requiere además `approvedAwsBaseUrl` como argumento de instrumentación, con la base completa autorizada para esa ejecución.

El alta completa y la recuperación cifran la credencial mediante JWE. El enlace se pega en la app y debe coincidir con `<RECOVERY_WEB_URL>/?recuperacion=1#token=…`. No hay App Links ni supuesto de entrega del correo. Contratar no cobra: no se ofrece pago simulado en `release` ni `preview`. La emisión solo se muestra cuando la saga la confirma y existe una póliza propia para esa suscripción. Los avisos consultan el buzón; no se incorpora FCM.

En `release` y `preview`, abrir un aviso de pago muestra el título y el texto del buzón autenticado. No se consulta un detalle de pago ni se confirma una transferencia. Solo `videoTest` consulta el detalle simulado de su entorno.

## Variantes locales aisladas

Para probar **Recuperar contraseña** sin reemplazar otras instalaciones, inicia el BFF local de recuperación y compila `:app:assembleRecoveryTest` con `-PrecoveryTestCa=<ruta absoluta al certificado PEM público del entorno>`. La variante `co.solventa.mobile.pf2.recoverytest` conecta con `https://localhost:45445` por túnel USB (`adb reverse --no-rebind tcp:45445 tcp:45445`). La CA solo se acepta para `localhost`; no se instala en el sistema.

En el login, selecciona **Olvidé mi contraseña**. Tras solicitar el correo, introduce el enlace completo recibido y la nueva contraseña. **Ya tengo un enlace** permite volver al formulario sin pedir otro. El enlace es de un solo uso. No lo guardes en logs ni capturas. La apertura automática desde el correo requiere un dominio con Android App Links verificados; no está habilitada en esta variante ni en release.

The initial versions come from the previous Android repository: Gradle 8.11.1, Android Gradle Plugin 8.9.2, Kotlin 2.1.20, Compose BOM 2025.03.01 and Hilt 2.56.1. The version catalog contains only the dependencies used by this scaffold.

The application ID is `co.solventa.mobile.pf2`, so this app can coexist with the previous prototype. Version information is maintained in `version.properties`. No signed release or store publication is configured.

The single `app` module contains an application class, an activity and a minimal Compose entry point. Feature packages, repository contracts and the design system will be added with the corresponding approved work. The entry point and the temporary Android framework icon are not business designs or replacements for the Figma design. App backup and data transfer are disabled in this initial setup.

## Continuous integration

Para el recorrido físico de Viaje y billetera, usa `:app:assembleTravelTest` con `-PtravelTestCa=<ruta al PEM público del BFF móvil local>`. El paquete `co.solventa.mobile.pf2.traveltest` está aislado de recuperación y siniestros. Necesita `adb reverse --no-rebind tcp:45446 tcp:45446`. El cliente verifica la emisión consultando la suscripción y el detalle de la póliza propia. La descarga de HTML es explícita y se verifica contra `X-Content-SHA256`.

En `videoTest`, conserva los túneles USB `45449` y `45450`. El login y el enlace de correo usan `https://localhost:45449`; solo solicitud, desafío y confirmación de recuperación usan `https://localhost:45450/movil/v1`. Ambos servicios comparten la identidad del vídeo y su CA local.

El alta completa necesita además `adb reverse --no-rebind tcp:45451 tcp:45451`. Solo el desafío y el registro se envían a `https://localhost:45451/movil/v1`; la contraseña se envía dentro del sobre JWE del desafío. Crear la cuenta no inicia sesión. La aceptación de términos y la autorización opcional de perfilamiento son decisiones separadas; el teléfono se conserva para cotizar aun si el cliente decide autorizar el perfilamiento más tarde.

En esta configuración de `videoTest`, asistencia requiere el auxiliar HTTPS `45453` y `adb reverse --no-rebind tcp:45453 tcp:45453`. Solo la consulta de prestadores (5 km) y el registro de asistencia usan ese origen, con la sesión de `45449`. Un recibo `registrada` no acredita despacho. No se presupone que el lanzador principal arranque ese auxiliar.

`.github/workflows/android-ci.yml` runs on pull requests to `develop` and `master`, and on pushes to those branches. It uses GitHub-hosted Linux runners, JDK 17 and the Gradle Wrapper.

- `Android verification` runs unit tests, Android Lint, debug compilation and instrumentation-test compilation.
- `Android UI tests` runs the existing layout/storage tests and the Espresso + Compose
  authentication HTTP journeys on an Android 35 emulator after verification succeeds.
- Both jobs upload their available test reports, including on failure.

Lint errors fail verification. Dependency-update advisories remain visible as warnings because the initial toolchain is deliberately kept consistent with the previous project; updating it requires a compatibility review.

The instrumented HTTP suite now covers registration, login, session restoration/rotation,
logout and error handling through the real application. Its HTTP server is a test double,
not a real backend. The opt-in real-backend E2E and the requirements still missing from
Android are documented in [testing.md](testing.md); a successful default CI run does not
claim E2E or full business-requirement coverage.

The workflow has read-only repository permissions and does not use signing credentials. Actions are pinned to commit hashes, and the Gradle distribution is checked against its published SHA-256 checksum.

`Android verification` and `Android UI tests` are required in branch protection for `develop` and `master`, along with one approval from a team member with write access and conversation resolution.

## Jira scope

Repository setup is associated with SOL-146. CI work is related to SOL-151, but that story currently names Jenkins in its acceptance criterion. GitHub Actions is the chosen Android approach for now; this does not resolve or complete that criterion for backend and frontend. No Jira fields are changed by this setup.
