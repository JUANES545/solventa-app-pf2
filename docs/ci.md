# Comprobaciones de Android

## Resultados visibles en GitHub

- **Cobertura**: check publicado por la App existente de Jenkins. Muestra líneas/ramas,
  conteo JUnit actual y alcance JVM. Su enlace abre la pestaña Checks de GitHub, no localhost.
- **CI / Validación (Jenkins)**: check opcional, publicado automáticamente
  por el controlador. El Jenkinsfile ya no publica un alias duplicado.
- El workflow del PR define **CI · Validación**; GitHub puede mostrar todavía el nombre de
  la rama predeterminada hasta integrarlo. Sus jobs obligatorios no cambian.
- JUnit sigue disponible dentro de Jenkins, sin publicar el redundante
  `Tests / Declarative: Post Actions`. Cada build limpia su workspace antes del checkout.
- Si faltan reportes, la cobertura se marca incompleta. Si el pipeline iba bien, ese problema
  lo hace fallar; si ya falló otra etapa, el reporte parcial se publica como neutral.
  Una cobertura generada no significa que todas las pruebas o el pipeline hayan pasado.
- Si JUnit lanza una excepción (por ejemplo, existe el directorio pero no contiene XML)
  o no se puede generar o leer el resumen, el bloque `finally` publica **Cobertura incompleta**
  con conclusión `FAILURE` y estado `COMPLETED`. Conserva la excepción y el fallo del pipeline,
  sin inventar porcentajes. Intenta archivar los reportes incluso si falla la publicación del check.

El resumen de **Cobertura** publica el SHA compilado, el número de ejecución y el resultado
observado junto a los conteos y porcentajes. No incluye enlaces al controlador privado ni
a sus reportes: cualquier persona con acceso al PR puede leer esa evidencia en GitHub.
Los XML y HTML completos siguen archivados en Jenkins para los operadores autorizados.
Los artefactos de Actions corresponden a otra ejecución y no sustituyen los de Jenkins.

El botón **Details** de **CI / Validación (Jenkins)** lo genera el publicador automático
del controlador. El inicializador confiable
`infra/jenkins/configure-check-links.groovy` adapta su destino a la pestaña Checks del PR
para los tres proyectos Solventa aprobados. Delega al mismo publicador de GitHub, que
conserva credenciales, SHA e ID del check; solo sustituye Details del check principal,
sin modificar nombre, estado, conclusión, tiempos, acciones ni resultados.

**Operación 2026-10-24:** el adaptador está instalado en el controlador privado para
Android, web y backend. Se comprobó el registro de la fábrica, las tres fuentes y las
rutas disponibles con `checks-api` 415.vf022234a_931d y `github-checks`
679.v74133da_b_435a_. Las pruebas con esas bibliotecas cubren todas las conclusiones,
cola/en curso y el aislamiento de otros checks. Además, se corrigieron los enlaces del
[PR40 reportado](https://github.com/JUANES545/solventa-app-pf2/pull/40/checks), conservando
App, IDs, SHA, tiempos, resultados y métricas originales. No se repitieron esas pruebas.
No se ejecutó un pipeline nuevo; la próxima ejecución debe confirmar el adaptador en
el flujo completo. Los cambios del Jenkinsfile requieren integración por separado.

Para reinstalar, ejecutar primero `test-check-links.groovy` con la ruta del inicializador
como argumento usando el classpath de Jenkins y sus plugins. Esa prueba compila el
adaptador sin registrarlo ni publicar checks. Copiar después el archivo revisado al
directorio confiable `init.groovy.d/13-github-evidence-links.groovy`, fuera de workspaces,
y reiniciar solo con cola y ejecutores vacíos. Verificar `ci-evidence-links.json` en
Jenkins home y el check de una ejecución real. La instalación es idempotente y rechaza
una prioridad de fábrica incompatible. Para revertir, retirar solo ese inicializador y
reiniciar en reposo: vuelve el publicador original, sin cambiar las protecciones.
La reparación puntual del PR40 no reescribe otros checks históricos.

La comprobación de rutas elige solo jobs de PR con una ejecución previa;
ignora `develop`/`master`. La ausencia de un PR construido no impide registrar
la fábrica. `verifiedPrRoutes` enumera las rutas que sí se comprobaron y puede
quedar vacío; `verifiedProjects` acredita la comprobación de las fuentes.
`test-check-link-selection.groovy` ejecuta ese selector con tipos dobles en cinco
escenarios, sin cargar plugins ni registrar la fábrica. La integración con los
tipos reales de Jenkins corresponde a `test-check-links.groovy`.

No abrir Jenkins al público, cambiar su URL base por la de GitHub ni desactivar checks.
Una IP privada tampoco permite consultar la evidencia desde fuera.

Los resúmenes se construyen desde XML, no desde porcentajes escritos manualmente.
`python3 -m unittest discover -s scripts -p 'test_*summary.py'` prueba los resúmenes
sin red ni credenciales. Requiere Python 3.10+ (ya disponible en el agente compartido).

La tarea Gradle `testCoveragePost` ejecuta `infra/jenkins/test-coverage-post.groovy`
con el Groovy incluido en Gradle, sin instalar herramientas ni añadir librerías a la app.
Es una dependencia de `testDebugUnitTest`: la ejecutan los comandos existentes de Actions
y de Jenkins (`jacocoDebugUnitTestReport` depende de esas pruebas).
Para ejecutarla sola: `./gradlew --no-daemon testCoveragePost`.
La regresión ejecuta el bloque `post` del Jenkinsfile con pasos simulados: JUnit sin XML,
errores de resumen, reportes parciales, éxito y fallo de publicación. Comprueba la conclusión
final y la propagación del error; no sustituye una ejecución en el controlador Jenkins.

En Jenkins, la etapa de pruebas compila primero con `compileDebugUnitTestSources` y después
ejecuta `jacocoDebugUnitTestReport` en otra invocación `--no-daemon`. Cada invocación conserva
el límite de 1536 MiB, dos workers y Kotlin `in-process`. El primer proceso termina antes
de iniciar las pruebas: evita retener el compilador Kotlin junto al worker Robolectric.
No se excluyen pruebas ni reportes, ni se cambian límites de la app o de Actions.
La regresión también comprueba que esos dos comandos permanezcan separados y ordenados.

### Configuración administrativa verificada

**Operación 2026-10-17:** la App es **Solventa CI Android** (`solventa-ci-android`),
conservando ID `5247357`, instalación y credencial. En esa fecha, `develop` y `master` requerían
`Android verification` y `Android UI tests` de Actions (`15368`), más
`CI / Validación (Jenkins)` de la App `5247357`.

La nueva política conserva ambos checks de Actions y la aprobación; Jenkins
queda opcional. El especialista de configuración aplica las reglas de GitHub
por separado. `verified-backport.cjs` también deja de exigir Jenkins al verificar
la release de origen, sin omitir identidad, SHA, aprobación ni ascendencia/árbol.
Esa automatización conservará la política anterior hasta integrar su código
en las ramas desde las que se ejecuta.

La migración añadió el requisito nuevo antes de retirar el anterior y cambió el publicador
automático del controlador. Por eso también funciona con Jenkinsfiles antiguos. El bootstrap
y su metadato conservan el nombre nuevo tras un reinicio. No se cambió capacidad ni seguridad.
Se preservaron una aprobación, revisión obsoleta descartada, conversaciones resueltas,
actualización estricta, prohibición de bypass y bloqueo de force-push/borrado.

`Cobertura` sigue informativa con un **objetivo recomendado del 80 % en líneas y ramas**.
Es una referencia de mejora, no un requisito nuevo de integración. Una prueba de interfaz fallida sigue
bloqueando el PR: no se elimina, flexibiliza ni sustituye por el porcentaje de cobertura.

El scanner ahora difiere solicitudes de un PR que ya esté en cola o ejecutándose,
sin consumir su comentario. Evita que Jenkins agrupe solicitudes con revisiones distintas
y valide solo la anterior. Los tests de `infra/jenkins/test-comment-policy.groovy` cubren
los estados ocupado/libre. La corrección ya se desplegó y recargó de forma controlada;
integrar el PR no reemplaza los temporizadores ya cargados. No ejecutar otra copia del
inicializador encima del timer existente.

`skipNotifications=true` suprime el status legado `continuous-integration/jenkins/pr-merge`.
JUnit se conserva en Jenkins sin otro check. El pipeline publica **Cobertura** y el
controlador publica el resultado global, sin dos emisores del mismo nombre.

La verificación de backport exige `Android verification` y `Android UI tests`
de Actions (`15368`); rechaza checks ausentes, pendientes, fallidos o de otra App.
Jenkins ausente, pendiente o fallido no bloquea esa verificación. Se conservan
las comprobaciones de autor, PR de origen, SHA y aprobación humana.

La configuración del controlador y el nombre de la App no cambian por integrar este PR.
Los nombres futuros de jobs de Actions (`CI / Calidad (Actions)`, `CI / Interfaz Android`) requieren
la misma migración coordinada. La política de backport se verifica dentro de
`Android verification`, solo para ramas candidatas; no crea checks separados en PRs normales.

| Proceso | Cuándo | Qué comprueba |
| --- | --- | --- |
| Actions: `Android verification` | PR y push a develop/master | Scripts de automatización, unitarias, lint y compilación |
| Actions: `Android UI tests` | Después de verification | Pruebas instrumentadas en emulador |
| Jenkins opcional: `CI / Validación (Jenkins)` | Comentario nuevo `/test-jenkins` de un colaborador | Unitarias con cobertura, lint y compilación |

`develop` es la rama predeterminada; `master` conserva las entregas estables. Cambiar la
rama predeterminada no integra código ni altera las protecciones. Los PRs normales siguen
requiriendo los checks de Actions y aprobación. Para repetir Jenkins tras un nuevo
commit, pedir otra ejecución; no duplicar solicitudes para el mismo commit.

Las pruebas rápidas de los scripts, incluidas las de backport, se ejecutan en el mismo
job de verificación. No hay otro workflow `Backport validation` ni jobs `backport_policy`.
Solo un backport exacto verificado por API reutiliza la evidencia de la release y omite
compilación e instrumentación repetidas. Un candidato no válido ejecuta CI normal.

## Leer Jenkins

### Resúmenes de validación y cobertura

Cada job de Actions añade un resumen a la ejecución con los resultados reales de sus pasos,
conteos JUnit y revisión validada. Verificación incluye el conteo de errores y advertencias
de lint; interfaz utiliza únicamente los reportes del emulador. Los pasos omitidos, fallos
e informes ausentes se identifican explícitamente, sin inventar resultados correctos.

Cobertura desglosa líneas y ramas, elementos cubiertos, pendientes y distancia al objetivo
recomendado del 80 %. 🟢 indica objetivo alcanzado; 🟠, mejora pendiente; ⚪, métrica no
aplicable; 🔴, informe que requiere revisión. Los colores llevan texto para no depender
solo de la percepción visual. La tabla de pruebas y la revisión compilada completan el
detalle. No se cambian conclusiones de checks ni protecciones por estar debajo del objetivo.

Etapas: Código → Pruebas unitarias y cobertura → Android Lint → Compilación debug.
Los resultados JUnit aparecen en **Tests** y los reportes en **Artifacts**:

- `app/build/reports/coverage/unit/coverage.xml`: JaCoCo XML.
- `app/build/reports/coverage/unit/html/index.html`: cobertura por archivo/línea.
  Descargar la carpeta HTML completa si Jenkins restringe su visualización.
- Reportes de lint y pruebas existentes se conservan. Un fallo no se convierte en éxito
  para publicar cobertura; el objetivo del 80 % es una recomendación, no un requisito obligatorio.

Comando local: `./gradlew jacocoDebugUnitTestReport`. Mide el código de la app mediante
las pruebas JVM; excluye clases generadas de recursos/Hilt, no clases reales de negocio.
Código de UI, Android Keystore o red que no ejecuten las unitarias puede aparecer sin
cobertura; eso no significa que haya fallado una prueba instrumentada. Esta primera fase
no combina cobertura del teléfono/emulador ni incluye dependencias externas en el total.

Jenkins no usa emulador; Actions sí ejecuta instrumentación. Las pruebas físicas son otra
evidencia y no se deducen de un build. Cobertura mide ejecución, no calidad de los asserts.
El agente tiene un ejecutor compartido: esperar en cola no equivale a una prueba fallida.

Appetize, releases y despliegue de backend son procesos de publicación separados. Un check
verde no demuestra que haya una nueva versión publicada o un servicio disponible en AWS.

En Android, `android-ci.yml` contiene solo verificación y pruebas UI. El workflow
`android-publish.yml` publica después del CI exitoso de master, recuperando el mismo APK
por ID de ejecución. Los PRs no muestran jobs de publicación omitidos. Los reintentos de
  una versión publicada se solicitan en **CD · Publicación**, no en CI.
