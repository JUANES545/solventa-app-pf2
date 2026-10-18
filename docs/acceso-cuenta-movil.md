# Registro, acceso y cuenta móvil

Historia: [SOL-332](https://proyectointegradorgrupo2.atlassian.net/browse/SOL-332).

## Alcance

- Crear cuenta con nombre, correo, contraseña y confirmación.
- Confirmar el registro sin abrir una sesión implícita y preparar el correo para ingresar.
- Mostrar Inicio/Mi cuenta con nombre y correo del usuario autorizado.
- Conservar recuperación de sesión, renovación de tokens y cierre seguro.

No incluye edición de perfil, recuperación de contraseña, biometría, selfie, pólizas o pagos.
No modifica el alcance cerrado de SOL-160 ni completa SOL-169 o SOL-189.

## Integración

Se reutiliza el BFF móvil desplegado en AWS, no el BFF web:

| Operación | Endpoint |
| --- | --- |
| Registro | `POST /movil/v1/usuarios` |
| Login | `POST /movil/v1/sesiones` |
| Sesión actual | `GET /movil/v1/sesiones/actual` |
| Renovación | `POST /movil/v1/sesiones/renovar` |
| Cierre | `DELETE /movil/v1/sesiones/actual` |

El registro solo devuelve un usuario. No almacena tokens ni inicia sesión automáticamente.
Después de un registro válido, se descarta cualquier identidad anterior almacenada localmente.
El usuario inicia sesión explícitamente; la sesión se conserva cifrada mediante Android Keystore.

Se mantienen la serialización de renovaciones y el reemplazo conjunto de tokens rotados.
La recuperación comprueba la sesión al reanudar la aplicación. Un rechazo definitivo retira
el acceso; una sesión vencida se diferencia de credenciales incorrectas.

## Validaciones y errores

- Nombre de 1 a 120 caracteres y contraseña de 8 a 128, contando caracteres Unicode como el backend.
- Correo obligatorio y válido; la contraseña no se recorta ni normaliza.
- La confirmación solo se valida en el cliente: no se envía al BFF.
- Solicitud inválida, correo existente, credenciales incorrectas, límite de intentos y red
  tienen resultados recuperables. Un registro de resultado incierto no se anuncia como creado.
- Durante el envío se bloquean acciones repetidas y navegación atrás del formulario.
- Contraseñas transitorias en memoria: no se guardan mediante estado restaurable ni en disco.

## Comprobación en dispositivo físico

Samsung A21s, por USB. Actualización de APK con firma compatible, sin desinstalar ni borrar datos.
La permanencia de pantalla por USB se habilitó con autorización y se restauró al terminar.

| Comprobación | Resultado |
| --- | --- |
| Registro real y confirmación sin sesión automática | Correcto |
| Correo preparado y contraseña vacía al volver al login | Correcto |
| Login y datos reales en Mi cuenta | Correcto |
| Recuperación tras cerrar el proceso y abrir la aplicación | Correcto |
| Cierre y retirada de información protegida | Correcto |
| Correo duplicado rechazado, sin sobrescribir la cuenta | Correcto |
| Batería instrumentada completa | 15 pruebas correctas |
| Pruebas JVM | 20 pruebas correctas |
| Scripts de automatización existentes | 136 pruebas correctas |

El recorrido real utiliza una cuenta aislada con credenciales generadas en memoria y deja
su sesión cerrada. No se publican credenciales o tokens en el repositorio, informes o capturas.
El correo se oculta en la evidencia de Mi cuenta. Los casos instrumentados de error usan
servicios en memoria, sin provocar fallos ni modificar configuración del backend real.

| Pantalla | Evidencia en dispositivo físico |
| --- | --- |
| Crear cuenta | ![Registro en Samsung A21s](evidence/sol-332/registro-samsung-a21s.png) |
| Inicio/Mi cuenta | ![Cuenta en Samsung A21s, correo oculto](evidence/sol-332/cuenta-samsung-a21s.png) |
