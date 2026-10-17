# Comprobaciones de Android

| Proceso | Cuándo | Qué comprueba |
| --- | --- | --- |
| Actions: `Android verification` | PR y push a develop/master | Unitarias, lint y compilación |
| Actions: `Android UI tests` | Después de verification | Pruebas instrumentadas en emulador |
| Actions: `Backport logic tests` | Según su workflow | Política de sincronización de releases |
| Jenkins: `Solventa Android Jenkins` | Comentario nuevo `/test-jenkins` de un colaborador | Unitarias con cobertura, lint y compilación |

`develop` es la rama predeterminada; `master` conserva las entregas estables. Cambiar la
rama predeterminada no integra código ni altera las protecciones. Los PRs normales siguen
requiriendo los checks configurados y aprobación. Después de nuevos commits, pedir Jenkins
otra vez; no duplicar solicitudes mientras hay trabajo para el mismo commit.

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
