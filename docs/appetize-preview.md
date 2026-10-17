# Preview compartida con Appetize

Publicación de una demo interactiva de Android, no de una release de Play Store.
El plan Free consultado ofrece 30 minutos mensuales, 2 dispositivos simultáneos y
3 minutos por sesión. No habilitar pagos, cambiar de plan ni añadir tarjeta como
parte de esta configuración. Confirmar las condiciones en la cuenta antes de activar.

## Publicación controlada

- El APK `preview` usa la configuración release con firma de pruebas y sin herramientas
  de inspección de Compose de debug. No usa ni requiere una clave de distribución.
- CI conserva ese APK por 7 días y solo publica después de pasar verificación y pruebas UI.
- La publicación se limita a push o ejecución manual de CI en **master**. Los PRs,
  develop y los builds obsoletos no actualizan la URL compartida.
- `develop` es la rama predeterminada de colaboración, no la de publicación. Integrar
  este PR en develop no publica nada ni sustituye una entrega revisada a master.
- Se actualiza una app existente; nunca se crea una distinta en cada ejecución.
- La subida por API no inicia un emulador ni ejecuta pruebas de streaming.

## Configuración inicial privada

1. Crear la cuenta gratuita y verificar el plan Free en Plan & Billing.
2. Subir una vez un APK que corresponda al código aprobado de master. Appetize acepta
   APK/APKS; no recibe AAB directamente. No etiquetar un APK de feature como estable.
3. Guardar el public key de esa app como variable Actions `APPETIZE_PUBLIC_KEY`.
4. Generar un token con permisos mínimos de publicación (Developer si está disponible)
   en Organization → API Tokens; guardarlo privadamente como secret Actions
   `APPETIZE_API_TOKEN`. No pegarlo en PRs, chat o archivos versionados.
5. Mantener la variable `APPETIZE_ENABLED=false` hasta revisar el workflow e integrarlo
   mediante PR y release a master. Activar con `true` cuando la cuenta y el destino
   estén comprobados. Las protecciones existentes no cambian.
6. Ejecutar el workflow de CI Android manualmente en master para la primera validación de CD. Revisar
   la publicación y el enlace del resumen. Probar una sesión breve sin autologin.

La URL es `https://appetize.io/app/<publicKey>`. Usar la misma app/public key conserva
el enlace en actualizaciones futuras. El script omite respuestas privadas del proveedor
y envía la clave solo al host fijo `api.appetize.io`, sin seguir redirects.

## Comprobación antes de activar

El APK preview debe compilar también con el login real y la cobertura JVM de develop.
La variante conserva la configuración release y su firma de pruebas; JaCoCo sigue
limitado a las pruebas debug y no se convierte en una clave de distribución.

Pruebas locales del publicador, sin token real ni llamadas a Appetize:

```bash
python -m unittest discover -s .github/scripts -p test_deploy_appetize.py
```

Cubren rama/evento, destino fijo, APK, redirecciones, identidad de la app, errores HTTP
sin exposición de respuestas y límites de tamaño del resultado. Pasarlas no verifica
las credenciales reales ni demuestra que una app esté publicada.

En un PR, **Publicar preview Android** debe quedar omitido por diseño. Los checks
obligatorios de Actions y **CI / Validación (Jenkins)** siguen siendo necesarios.

## Uso y privacidad

Compartir la URL permite ejecutar la app a quien tenga acceso al enlace. No configurar
credenciales, tokens o autologin en parámetros de la URL. Mantener logs, ADB, proxy e
intercepción de red limitados a usuarios autenticados en Appetize. La app habla con el
backend real: usar solo cuentas de prueba y nunca datos personales o financieros reales.

Cada actualización conserva un timeout de inactividad de 60 segundos, máximo 180
segundos por sesión y hasta 2 sesiones simultáneas. El servidor aplica además los límites
del plan. No ejecutar sesiones automatizadas que consuman minutos en cada commit.

El agotamiento de minutos impide usar la demo; no sustituye pruebas físicas ni CI.
La verificación de la app en Appetize y de la API con su token es pendiente hasta realizarla:
configurar un workflow no equivale a un despliegue comprobado.
