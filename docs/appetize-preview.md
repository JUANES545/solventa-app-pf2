# Preview compartida con Appetize

Publicación de una demo interactiva de Android, no de una release de Play Store.
El plan Free consultado ofrece 30 minutos mensuales, 2 dispositivos simultáneos y
3 minutos por sesión. No habilitar pagos, cambiar de plan ni añadir tarjeta como
parte de esta configuración. Confirmar las condiciones en la cuenta antes de activar.

## Publicación controlada

- Integrar una entrega revisada en `master` dispara el flujo completo; aprobar un PR sin
  integrarlo no publica nada. `version.properties` debe aumentar `VERSION_NAME` (X.Y.Z)
  y `VERSION_CODE` antes de aprobar la entrega. La validación del PR a master lo comprueba.
- Después de CI y pruebas UI, se crea el tag de esa versión sobre el SHA validado de master
  y una GitHub Release con `solventa-preview.apk`, `release.json` y `SHA256SUMS`.
  Es la misma APK de esa ejecución, no otra compilación para cada destino.
- El APK `preview` usa la configuración release con firma de pruebas y sin herramientas
  de inspección de Compose de debug. No usa ni requiere una clave de distribución.
- CI conserva su artefacto temporal 7 días; las descargas versionadas permanecen en Releases.
- La publicación se limita a push o ejecución manual de CI en **master**. Los PRs,
  develop y los builds obsoletos no actualizan la URL compartida.
- `develop` es la rama predeterminada de colaboración, no la de publicación. Integrar
  este PR en develop no publica nada ni sustituye una entrega revisada a master.
- Se actualiza una app existente; nunca se crea una distinta en cada ejecución. La URL
  representa la última entrega desplegada; las APK anteriores se conservan en GitHub,
  no como emuladores independientes por versión.
- La subida por API no inicia un emulador ni ejecuta pruebas de streaming.

El único disparador automático es el push a master. Crear el tag o la Release no dispara
otro pipeline. Cada SHA de master conserva su validación y publicación de Release; solo
la actualización de Appetize se serializa. El workflow reutilizable `appetize-deploy.yml`
selecciona la última entrega dentro de ese bloqueo, antes de crear el registro de despliegue.
Así una entrega antigua no anuncia un despliegue falso ni pisa una demo más reciente.

## Visibilidad y permisos mínimos

El environment **appetize** registra el resultado y el enlace del despliegue, sin revisores
adicionales ni tiempos de espera. La aprobación sigue siendo la del PR. El token de Appetize
permanece en el secret existente del repositorio. Solo el job de Release recibe
`contents: write`; el resto no obtiene permiso para escribir código o crear tags.

El registro de Deployment se crea con el SHA del APK, también al reintentar desde un workflow
de master más reciente. Se desactiva únicamente el registro automático del job (`deployment: false`)
para no duplicarlo ni atribuir a la app el SHA del workflow de recuperación. La API de
Deployment usa `auto_merge: false`: no integra ramas ni sustituye la aprobación del PR.

El README enlaza la demo desde Deployments y la última Release. El enlace real de Appetize
se guarda también en el campo Website del repositorio al completar la primera publicación.
El badge se filtra por master porque la rama predeterminada es develop; indica CI, no salud
de la app ni cuota de streaming. No se generan commits de README en cada publicación.

## Configuración inicial privada

1. Crear la cuenta gratuita y verificar el plan Free en Plan & Billing.
2. Publicar la primera Release desde master con Appetize todavía desactivado. Subir una vez
   su APK aprobada para crear el destino. Appetize acepta
   APK/APKS; no recibe AAB directamente. No etiquetar un APK de feature como estable.
3. Guardar el public key de esa app como variable Actions `APPETIZE_PUBLIC_KEY`.
4. Generar un token con permisos mínimos de publicación (Developer si está disponible)
   en Organization → API Tokens; guardarlo privadamente como secret Actions
   `APPETIZE_API_TOKEN`. No pegarlo en PRs, chat o archivos versionados.
5. Mantener la variable `APPETIZE_ENABLED=false` hasta revisar el workflow e integrarlo
   mediante PR y release a master. Activar con `true` cuando la cuenta y el destino
   estén comprobados. Las protecciones existentes no cambian.
6. Ejecutar manualmente el workflow en **master**, indicando esa versión en `release_version`,
   para la primera validación de CD sin recompilar. Revisar la publicación, el registro del
   environment y el enlace del resumen. Probar una sesión breve sin autologin.

La URL es `https://appetize.io/app/<publicKey>`. Usar la misma app/public key conserva
el enlace en actualizaciones futuras. El script omite respuestas privadas del proveedor
y envía la clave solo al host fijo `api.appetize.io`, sin seguir redirects.

## Fallos y recuperación

- Un fallo de CI impide crear la Release y actualizar la demo.
- Los artefactos se suben primero a una Release borrador; solo se publica cuando están completos.
  Un reintento reutiliza los assets coincidentes y completa los faltantes. Un tag o un archivo
  con contenido distinto no se sobrescribe. Un fallo de permisos no se interpreta como ausencia.
- Si Appetize falla, la Release válida conserva su APK y el despliegue queda fallido.
  Comprobar el estado remoto antes de reintentar: un timeout no demuestra que la API no actualizó.
  Si el runner termina abruptamente, el registro puede quedar en progreso: revisarlo y cerrarlo
  al recuperar, sin tratarlo como una publicación exitosa.
- Para reintentar, usar **Actions → CI · Actions → Run workflow → master** e introducir
  la versión publicada en `release_version`, por ejemplo `0.2.0`. Se descargan y comprueban
  el APK original, su checksum, su tag y su pertenencia a master; no se recompila ni se retaguea.
- Una versión superada se omite antes de registrar un despliegue. No hay rollback automático.
  Si falta un asset de una Release ya publicada, se detiene para revisión, no se reemplaza a ciegas.
- La firma es de pruebas; no se promete actualización sobre una instalación previa con otra
  firma ni se presenta el APK como distribución de Play Store.

## Comprobación antes de activar

El APK preview debe compilar también con el login real y la cobertura JVM de develop.
La variante conserva la configuración release y su firma de pruebas; JaCoCo sigue
limitado a las pruebas debug y no se convierte en una clave de distribución.

Pruebas locales del publicador, sin token real ni llamadas a Appetize:

```bash
node --test .github/scripts/release_android.test.cjs
python -m unittest discover -s .github/scripts -p test_deploy_appetize.py
```

Cubren rama/evento, destino fijo, APK, redirecciones, identidad de la app, errores HTTP
sin exposición de respuestas y límites de tamaño del resultado. Pasarlas no verifica
las credenciales reales ni demuestra que una app esté publicada.

En un PR, **Publicar entrega Android** y **Publicar preview Android** quedan omitidos por diseño. Los checks
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
