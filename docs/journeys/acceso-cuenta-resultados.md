# Journey: SOL-332 — Registro, acceso y cuenta Android

## Results

Dispositivo físico: **Samsung A21s**, conectado por USB. Backend: **BFF móvil desplegado en AWS**.
El recorrido utilizó una cuenta aislada, cerró su sesión y no publicó credenciales o tokens.
Las entradas sensibles de los comandos se omiten deliberadamente.

### Action: Open Solventa on the connected physical device ✅
- **Commands:** `adb -s RF8N739C9SJ shell am start -n co.solventa.mobile.pf2/co.solventa.mobile.MainActivity`
- **Comment:** Inicio de la compilación instalada con firma compatible y sin borrar datos.

### Action: Tap Crear cuenta ✅
- **Commands:** `adb shell input tap` sobre el centro obtenido con `android layout --device=RF8N739C9SJ --flat --full`.

### Action: Verify the registration form is displayed ✅
- **Screenshot:** [Registro en dispositivo físico](../evidence/sol-332/registro-samsung-a21s.png).
- **Comment:** Nombre, correo, contraseña, confirmación y acciones accesibles.

### Action: Submit the empty form ✅
- **Commands:** Tap de Crear cuenta con campos vacíos.

### Action: Verify the required-name validation is displayed ✅
- **Comment:** Validación visible; no se anunció una cuenta creada.

### Action: Enter isolated account data using keyboard Next actions ✅
- **Commands:** `adb shell input text` con valores generados en memoria; `adb shell input keyevent 66` para Siguiente.
- **Comment:** Se comprobó el foco antes de introducir datos; entradas no incluidas en el informe.

### Action: Submit using the keyboard Done action ✅
- **Commands:** `adb shell input keyevent 66` en la confirmación.

### Action: Verify confirmation without opening an authenticated account ✅
- **Comment:** Tu cuenta está lista visible, sin Mi cuenta ni Cerrar sesión.

### Action: Tap Ir a iniciar sesión ✅
- **Commands:** Tap sobre el centro de la acción obtenido del árbol de interfaz.

### Action: Verify prepared email and empty password ✅
- **Comment:** Comprobación en memoria; datos no publicados.

### Action: Enter the isolated password and submit login ✅
- **Commands:** Entrada privada mediante ADB y acción IME Hecho.

### Action: Verify authenticated account ✅
- **Screenshot:** [Mi cuenta con correo oculto](../evidence/sol-332/cuenta-samsung-a21s.png).
- **Comment:** El usuario mostrado coincide con la cuenta creada en el backend real.

### Action: Close the process and open Solventa again ✅
- **Commands:** `adb shell am force-stop co.solventa.mobile.pf2` y `adb shell am start` de MainActivity, siempre sobre el dispositivo seleccionado.
- **Comment:** No se borró almacenamiento.

### Action: Verify the same account is restored without credentials ✅
- **Comment:** Mi cuenta reaparece con el mismo usuario, sin introducir de nuevo la contraseña.

### Action: Tap Cerrar sesión ✅
- **Commands:** Tap sobre la acción encontrada en el árbol de interfaz.

### Action: Verify protected account information is removed ✅
- **Comment:** Se muestra login y no aparece Mi cuenta.

### Action: Submit the same account registration again ✅
- **Commands:** Navegación y entrada privada en el mismo formulario.

### Action: Verify existing-email error without registration or authentication ✅
- **Comment:** Mensaje de correo existente; sin confirmación de cuenta nueva ni acceso protegido.

### Action: Return to login and clear the isolated email ✅
- **Commands:** Tap Volver a iniciar sesión, selección del correo, `adb shell input keycombination 113 29`, `adb shell input keyevent 67`.

### Action: Verify login is left without credentials ✅
- **Comment:** Final del recorrido con sesión cerrada. La cuenta aislada permanece en el backend; no se modificaron otras cuentas ni infraestructura.

## Batería complementaria

- **15 pruebas instrumentadas correctas en el dispositivo físico**, incluidos texto grande,
  teclado, carga, confirmación, correo existente, contraseña distinta, error de servicio y expiración.
- **20 pruebas JVM correctas**, incluidas validaciones Unicode y tratamiento de sesión local.
- **136 pruebas de scripts correctas**; lint y compilaciones debug/preview/instrumentación correctos.
- Permanencia temporal de pantalla por USB restaurada a su valor original al terminar.
