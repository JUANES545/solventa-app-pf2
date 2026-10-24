# Publicación de Solventa

Documentación operativa de las versiones Android y de Solventa App en Appetize.
El plan Free consultado ofrece 30 minutos mensuales, 2 dispositivos simultáneos y
3 minutos por sesión. No habilitar pagos, cambiar de plan ni añadir tarjeta como
parte de esta configuración. Confirmar las condiciones en la cuenta antes de activar.

## Identificación de las entregas

La versión Android sigue **X.Y.Z**, según el acuerdo de versionamiento del cierre de
Sprint 1 (semana 2, página 2 de `PF2-ENTREGABLE-2-SPRINT-1`). El equipo elige el nivel
antes de preparar cada entrega; no se deduce del mensaje de commit ni del calendario.

| Nivel elegido | Cuándo se usa | Desde `1.2.0` |
| --- | --- | --- |
| `patch` (Z) | Corrección, ajuste o aporte pequeño | `1.2.1` |
| `minor` (Y) | Consolidación de los cambios de la semana | `1.3.0` |
| `major` (X) | Cierre de sprint | `2.0.0` |

Cada incremento suma uno al nivel elegido y reinicia los niveles inferiores en cero.
Si el cierre semanal coincide con el cierre de sprint, se elige `major`, no ambos.
Estar en semana 3 no incrementa una versión ni publica automáticamente.
`VERSION_CODE` es independiente: aumenta en cada entrega y no se reinicia.

Se conserva la elección manual del equipo, pero los tags y Releases se generan mediante
el CD existente después del CI exitoso de `master`, no mediante una publicación manual.
La aprobación humana del PR y el artefacto exacto de CI se mantienen.

La fecha y el consecutivo siguen identificando cada entrega, sin sustituir X.Y.Z.

| Elemento | Convención | Ejemplo |
| --- | --- | --- |
| Identificador de entrega | `AAAA-MM-DD-N`, fecha UTC y consecutivo desde 1 | `2026-10-19-1` |
| Rama | `release/<identificador>` | `release/2026-10-19-1` |
| Título del PR | `Publica Solventa · <identificador>` | `Publica Solventa · 2026-10-19-1` |
| Nombre de GitHub Release | `Solventa · <identificador> · v<versión>` | `Solventa · 2026-10-19-1 · v0.3.1` |
| Tag y versión Android | `X.Y.Z` | `0.3.1` |
| Código Android | Entero creciente | `6` |
| Incremento elegido | `RELEASE_LEVEL=patch`, `minor` o `major` | `patch` |

La versión publicada `1.2.0` es la referencia para las próximas entregas, aunque
`version.properties` todavía identifique la entrega anterior `0.3.1`, código `6`.
No se cambian esos valores históricos ni los APK, tags o Releases ya publicados.
La preparación usa la mayor versión entre el archivo y los tags numéricos disponibles;
CI vuelve a contrastarla con los tags remotos, sin depender de una copia local actualizada.

`version.properties` conserva los cuatro valores de la próxima entrega una vez preparada.
Por ejemplo, un parche desde `1.2.0`:

```properties
VERSION_CODE=7
VERSION_NAME=1.2.1
RELEASE_ID=2026-10-24-1
RELEASE_LEVEL=patch
```

El ejemplo no prepara ni publica esa versión por sí solo.
El consecutivo distingue varias entregas del mismo día y se reinicia en 1 al cambiar de fecha.
La fecha y el consecutivo se fijan en el PR: un reintento de CD no genera otro identificador.

Antes de abrir el PR, comprobar que no exista otra rama o PR con ese identificador. Usar:

```sh
git fetch origin --tags
git switch -c release/2026-10-24-1 origin/develop
node .github/scripts/prepare_release.cjs patch 2026-10-24-1
# Revisar version.properties, validar y guardar el commit de preparación.
git push -u origin release/2026-10-24-1
gh pr create --base master --head release/2026-10-24-1 --title "Publica Solventa · 2026-10-24-1" --assignee JUANES545
```

Sustituir `patch` por `minor` o `major` según lo acordado, y usar la fecha UTC real y
un consecutivo disponible. El comando solo modifica `version.properties`: no crea ramas,
commits, tags, Releases ni despliegues. Incrementa el código Android en uno y valida
todos los valores antes de escribir; los errores se informan sin cambiar el archivo.

CI valida la fecha real, el consecutivo, la coincidencia con la rama `release/*` y el
incremento exacto de versión según `RELEASE_LEVEL`, tomando como referencia la mayor
versión entre `master` y los tags remotos. También exige aumentar el código Android y el
identificador respecto a `master`. Los hotfixes conservan su prefijo y también declaran
el nivel y actualizan el identificador. Los PR de feature a `develop` no necesitan
preparar una entrega ni aumentar estos números. La aprobación humana sigue siendo requerida.

CD genera el nombre visible y añade `release_id` y `release_level` a `release.json`. Los tags, enlaces de
descarga y entradas de recuperación de Appetize siguen utilizando la versión Android,
por ejemplo `0.3.1`, no el identificador por fecha.

### Entregas anteriores

Los nombres visibles se alinean usando la fecha UTC de publicación y su orden dentro de
ese día. No se renombran ramas históricas, no se mueven tags y no se modifican archivos publicados.

| Tag conservado | Nombre visible |
| --- | --- |
| `0.1.0` | Solventa · 2026-10-12-1 · v0.1.0 |
| `0.2.0` | Solventa · 2026-10-17-1 · v0.2.0 |
| `0.2.1` | Solventa · 2026-10-18-1 · v0.2.1 |
| `0.2.2` | Solventa · 2026-10-18-2 · v0.2.2 |
| `0.3.0` | Solventa · 2026-10-18-3 · v0.3.0 |

Los metadatos antiguos sin `release_id` continúan siendo válidos para recuperar su APK original.

## Publicación controlada

- Integrar una entrega revisada en `master` dispara el flujo completo; aprobar un PR sin
  integrarlo no publica nada. `version.properties` debe aumentar `VERSION_NAME` (X.Y.Z)
  y `VERSION_CODE` antes de aprobar la entrega. La validación del PR a master lo comprueba.
- Después de CI y pruebas UI, se crea el tag de esa versión sobre el SHA validado de master
  y una GitHub Release con `Solventa.apk`, `release.json` y `SHA256SUMS`.
  Es la misma APK de esa ejecución, no otra compilación para cada destino.
- El APK `preview` usa la configuración release con firma de pruebas y sin herramientas
  de inspección de Compose de debug. No usa ni requiere una clave de distribución.
- CI conserva su artefacto temporal 7 días; las descargas versionadas permanecen en Releases.
- La publicación automática sigue al CI exitoso de un push a **master**. Los PRs,
  develop y los builds obsoletos no actualizan la URL compartida.
- `develop` es la rama predeterminada de colaboración, no la de publicación. Integrar
  este PR en develop no publica nada ni sustituye una entrega revisada a master.
- Se actualiza una app existente; nunca se crea una distinta en cada ejecución. La URL
  representa la última entrega desplegada; las APK anteriores se conservan en GitHub,
  no como emuladores independientes por versión.
- La subida por API no inicia un emulador ni ejecuta pruebas de streaming.

`android-ci.yml` (**CI · Validación**) solo valida y conserva `android-apk` con el APK compilado. Al completar
CI de master, `android-publish.yml` recibe `workflow_run` y verifica por API el workflow
de origen, evento push, repositorio, SHA, pertenencia a master y éxito de ambos jobs de
Android. Descarga exclusivamente el artefacto del ID de ejecución verificado: no usa el
último artefacto disponible ni recompila. El código se obtiene del mismo SHA validado,
no del SHA de develop que GitHub asigna al contexto del evento.

Crear el tag o la Release no dispara otra publicación. Cada SHA de master conserva su
validación y publicación de Release; solo
la actualización de Appetize se serializa. El workflow reutilizable `appetize-deploy.yml`
selecciona la última entrega dentro de ese bloqueo, antes de crear el registro de despliegue.
Así una entrega antigua no anuncia un despliegue falso ni sustituye una versión más reciente.

## Visibilidad y permisos mínimos

El environment **Solventa App** registra el resultado y el enlace del despliegue, sin revisores
adicionales ni tiempos de espera. La aprobación sigue siendo la del PR. El token de Appetize
permanece en el secret existente del repositorio. Solo el job de Release recibe
`contents: write`; el resto no obtiene permiso para escribir código o crear tags.

El registro de Deployment se crea con el SHA del APK, también al reintentar desde un workflow
de master más reciente. Se desactiva únicamente el registro automático del job (`deployment: false`)
para no duplicarlo ni atribuir a la app el SHA del workflow de recuperación. La API de
Deployment usa `auto_merge: false`: no integra ramas ni sustituye la aprobación del PR.

El README enlaza directamente a Solventa y a la última versión mediante imágenes locales.
About → Website conserva el acceso directo a la app. La sección Deployments de GitHub es
el historial técnico; su nombre de sección es propio de GitHub, mientras que el environment
se muestra como Solventa App y su enlace abre la aplicación. No se generan commits de README
en cada publicación.

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
6. Ejecutar manualmente **CD · Publicación** en **master**, indicando esa versión en `release_version`,
   para la primera validación de CD sin recompilar. Revisar la publicación, el registro del
   environment y el enlace del resumen. Probar una sesión breve sin autologin.

La URL es `https://appetize.io/app/<publicKey>`. Usar la misma app/public key conserva
el enlace en actualizaciones futuras. El script omite respuestas privadas del proveedor
y envía la clave solo al host fijo `api.appetize.io`, sin seguir redirects.

## Fallos y recuperación

- Un fallo de CI impide crear la Release y actualizar la app.
- Los artefactos se suben primero a una Release borrador; solo se publica cuando están completos.
  Un reintento reutiliza los assets coincidentes y completa los faltantes. Un tag o un archivo
  con contenido distinto no se sobrescribe. Un fallo de permisos no se interpreta como ausencia.
- Si Appetize falla, la Release válida conserva su APK y el despliegue queda fallido.
  Comprobar el estado remoto antes de reintentar: un timeout no demuestra que la API no actualizó.
  Si el runner termina abruptamente, el registro puede quedar en progreso: revisarlo y cerrarlo
  al recuperar, sin tratarlo como una publicación exitosa.
- Para reintentar, usar **Actions → CD · Publicación → Run workflow → master** e introducir
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
node --test .github/scripts/release_android.test.cjs .github/scripts/prepare_release.test.cjs
python -m unittest discover -s .github/scripts -p test_deploy_appetize.py
```

Cubren rama/evento, destino fijo, APK, redirecciones, identidad de la app, errores HTTP
sin exposición de respuestas y límites de tamaño del resultado. Pasarlas no verifica
las credenciales reales ni demuestra que una app esté publicada.

Los PRs no contienen jobs de publicación, ni siquiera omitidos. CI conserva los checks
obligatorios de Actions y **CI / Validación (Jenkins)**; CD aparece como ejecución separada.

## Compatibilidad y transición

- Las nuevas versiones usan `Solventa.apk`. Las anteriores pueden contener
  `solventa-preview.apk`: su recuperación valida los checksums originales y normaliza
  únicamente el nombre de la copia local. No cambia binarios ni assets publicados.
- Al integrar este ajuste en develop, los PRs ya usarán CI separado. La publicación
  automática cambia cuando la configuración alcance master mediante una release revisada.
  El flujo anterior de master permanece intacto mientras tanto.
  CD escucha únicamente el nuevo nombre CI · Validación; una ejecución de CI · Actions
  del master anterior no dispara además el nuevo publicador.
- El environment histórico `appetize` se conserva para no borrar registros. La siguiente
  publicación real registrará Solventa App, sin duplicar deployments automáticos del job.
- La versión 0.2.0 ya fue publicada y su actualización por API comprobada. La primera
  publicación mediante el nuevo workflow separado debe verificarse en una entrega real.

## Uso y privacidad

Compartir la URL permite ejecutar la app a quien tenga acceso al enlace. No configurar
credenciales, tokens o autologin en parámetros de la URL. Mantener logs, ADB, proxy e
intercepción de red limitados a usuarios autenticados en Appetize. La app habla con el
backend real: usar solo cuentas de prueba y nunca datos personales o financieros reales.

Cada actualización conserva un timeout de inactividad de 60 segundos, máximo 180
segundos por sesión y hasta 2 sesiones simultáneas. El servidor aplica además los límites
del plan. No ejecutar sesiones automatizadas que consuman minutos en cada commit.

El agotamiento de minutos impide usar la app en el navegador; no sustituye pruebas físicas ni CI.
Configurar un workflow no equivale a comprobar una publicación: verificar su ejecución real.
