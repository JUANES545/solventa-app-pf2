# Comprobaciones de Android

## Resultados visibles en GitHub

- **Cobertura**: check publicado por la App existente de Jenkins. Muestra líneas/ramas,
  conteo JUnit actual y alcance JVM. Su enlace abre la pestaña Checks de GitHub, no localhost.
- **CI / Validación (Jenkins)**: check principal obligatorio, publicado automáticamente
  por el controlador. El Jenkinsfile ya no publica un alias duplicado.
- El workflow del PR define **CI · Actions**; GitHub puede mostrar todavía el nombre de
  la rama predeterminada hasta integrarlo. Sus jobs obligatorios no cambian.
- JUnit sigue disponible dentro de Jenkins, sin publicar el redundante
  `Tests / Declarative: Post Actions`. Cada build limpia su workspace antes del checkout.
- Si faltan reportes, la cobertura se marca incompleta. Si el pipeline iba bien, ese problema
  lo hace fallar; si ya falló otra etapa, el reporte parcial se publica como neutral.
  Una cobertura generada no significa que todas las pruebas o el pipeline hayan pasado.

Los resúmenes se construyen desde XML, no desde porcentajes escritos manualmente.
`python3 -m unittest discover -s scripts -p test_coverage_summary.py` prueba el publicador
sin red ni credenciales. Requiere Python 3.10+ (ya disponible en el agente compartido).

### Configuración administrativa verificada

**Operación 2026-10-17:** la App es **Solventa CI Android** (`solventa-ci-android`),
conservando ID `5247357`, instalación y credencial. `develop` y `master` requieren
`Android verification` y `Android UI tests` de Actions (`15368`), más
`CI / Validación (Jenkins)` de la App `5247357`.

La migración añadió el requisito nuevo antes de retirar el anterior y cambió el publicador
automático del controlador. Por eso también funciona con Jenkinsfiles antiguos. El bootstrap
y su metadato conservan el nombre nuevo tras un reinicio. No se cambió capacidad ni seguridad.
Se preservaron una aprobación, revisión obsoleta descartada, conversaciones resueltas,
actualización estricta, prohibición de bypass y bloqueo de force-push/borrado.

`Cobertura` sigue informativa y sin mínimo nuevo. Una prueba de interfaz fallida sigue
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

La verificación de evidencia de backport y sus fixtures usan el nuevo nombre y la misma
App publicadora; un check antiguo o de otra App no satisface esa verificación. Esto no
activa la automatización de backport ni modifica sus condiciones de seguridad.

La configuración del controlador y el nombre de la App no cambian por integrar este PR.
Los nombres futuros de Actions (`CI / Calidad (Actions)`, `CI / Interfaz Android`) requieren
la misma migración coordinada. La política de backport se verifica dentro de
`Android verification`, solo para ramas candidatas; no crea checks separados en PRs normales.

| Proceso | Cuándo | Qué comprueba |
| --- | --- | --- |
| Actions: `Android verification` | PR y push a develop/master | Scripts de automatización, unitarias, lint y compilación |
| Actions: `Android UI tests` | Después de verification | Pruebas instrumentadas en emulador |
| Jenkins: `CI / Validación (Jenkins)` | Comentario nuevo `/test-jenkins` de un colaborador | Unitarias con cobertura, lint y compilación |

`develop` es la rama predeterminada; `master` conserva las entregas estables. Cambiar la
rama predeterminada no integra código ni altera las protecciones. Los PRs normales siguen
requiriendo los checks configurados y aprobación. Después de nuevos commits, pedir Jenkins
otra vez; no duplicar solicitudes mientras hay trabajo para el mismo commit.

Las pruebas rápidas de los scripts, incluidas las de backport, se ejecutan en el mismo
job de verificación. No hay otro workflow `Backport validation` ni jobs `backport_policy`.
Solo un backport exacto verificado por API reutiliza la evidencia de la release y omite
compilación e instrumentación repetidas. Un candidato no válido ejecuta CI normal.

## Leer Jenkins

Etapas: Código → Pruebas unitarias y cobertura → Android Lint → Compilación debug.
Los resultados JUnit aparecen en **Tests** y los reportes en **Artifacts**:

- `app/build/reports/coverage/unit/coverage.xml`: JaCoCo XML.
- `app/build/reports/coverage/unit/html/index.html`: cobertura por archivo/línea.
  Descargar la carpeta HTML completa si Jenkins restringe su visualización.
- Reportes de lint y pruebas existentes se conservan. Un fallo no se convierte en éxito
  para publicar cobertura; no se añade un porcentaje mínimo nuevo.

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
