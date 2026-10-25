# Login móvil con backend

Las variantes normales llaman al BFF de AWS.
Su base actual es `https://65qgzhody9.execute-api.us-east-2.amazonaws.com/movil/v1`.
La variante aislada `biometricTest` usa `http://127.0.0.1:18717/movil/v1`,
issuer exacto `https://identidad.solventa.test` y un applicationId separado:
`co.solventa.mobile.pf2.biometrictest`. No usa el entorno compartido ni cuentas reales.
El contrato de referencia está en `contracts/openapi/bff-movil-v1.yaml` de la rama
`feature/api-login` del backend; su despliegue manual fue informado por Migue.

## Flujo

1. Validar correo y contraseña; bloquear envíos repetidos mientras se procesa.
2. `POST /sesiones` con `correo` y `contrasena`; no modificar la contraseña.
3. Guardar el grant cifrado con AES-GCM y clave no exportable en Android Keystore.
   Access y refresh rotados se guardan juntos; no se guardan contraseñas.
4. Mostrar una pantalla mínima con el usuario autenticado y cierre de sesión. No es
   el Home funcional de pólizas ni presenta datos inventados.
5. Al volver a primer plano o recrear la actividad, comprobar la sesión mediante
   `GET /sesiones/actual`, con `Authorization: Bearer`. Renovar si vence o recibe 401,
   con un único reintento de la consulta y acceso serializado a tokens en toda la app.
6. Cerrar con `DELETE /sesiones/actual`, enviando el refresh token para revocarlo y
   limpiando almacenamiento local. Si no se confirma el cierre remoto, comunicarlo.

Las respuestas 503/fallos de red no eliminan el grant persistido al restaurar una
sesión. Un refresh rechazado/expirado sí exige nuevo ingreso. No se siguen redirects
ni se permite HTTP claro en release, caché de respuestas o logging de credenciales/tokens.
Solo las variantes debug y aislada permiten HTTP a los hosts loopback declarados.

## Alcance y límites

- El alcance original dejó registro, recuperación de contraseña y biometría
  deshabilitados. El registro actual, la consulta informativa y el flujo local de clave
  biométrica se describen en [Registro, acceso y cuenta móvil](acceso-cuenta-movil.md).
  La consulta no autentica. La firma biométrica solo autoriza acceso después de la
  validación del backend; las variantes normales aún no habilitan ese flujo.
  La recuperación de contraseña sigue deshabilitada.
- La sesión se valida al volver a primer plano; no se agregó polling periódico ni
  un interceptor para futuros servicios de negocio que todavía no existen.
- Cerrar sesión revoca el refresh, pero no promete revocación inmediata de un JWT
  ya emitido; esa política corresponde al servidor.
- La API es un entorno compartido reportado como producción: no ejecutar pruebas
  automatizadas con cuentas reales ni registrar usuarios sin autorización.
- Este cambio conserva el diseño móvil existente; no afirma una nueva certificación
  de fidelidad de todos los tokens/tipografías respecto de Figma.

## Validación

- Prueba directa autorizada contra AWS: login 200, sesión 200, renovación 200 con
  rotación, logout 204. Los tokens no se imprimieron ni persistieron.
- Unitarias: validación, persistencia, rotación, concurrencia, 401/503 y logout.
- Instrumentación: formulario vacío, campos, teclado, controles deshabilitados,
  round-trip de almacenamiento cifrado y rechazo de tipo/rol inesperado.
- No incluir credenciales del entorno en tests, variables versionadas o comandos CI.
  Las pruebas instrumentadas usan datos aislados de prueba, no cuentas AWS.
- SOL-169 cuenta con un recorrido físico separado contra el stack local:
  alta y acceso con huella del usuario, cancelación, revocación y login convencional
  posterior. Ver [la evidencia y sus límites](testing.md#comprobación-física-de-sol-169-25-de-octubre-de-2026-utc).
  Las variantes normales mantienen deshabilitado el acceso biométrico.

Comandos: `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`.
Con dispositivo/emulador disponible: `./gradlew connectedDebugAndroidTest`.
El emulador local requiere soporte de virtualización; compilar androidTest no equivale
a haber ejecutado sus pruebas. Actions debe aportar la ejecución en Android.
