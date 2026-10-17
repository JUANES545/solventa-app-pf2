# Comprobaciones de Android

## Resultados visibles en GitHub

- **Cobertura**: check publicado por la App existente de Jenkins. Muestra líneas/ramas,
  conteo JUnit actual y alcance JVM. Su enlace abre la pestaña Checks de GitHub, no localhost.
- **CI / Validación (Jenkins)**: nombre nuevo de presentación. Durante la transición también
  se publica **Solventa Android Jenkins**, que sigue siendo el obligatorio.
- Actions se agrupa como **CI · Actions**. Sus nombres de jobs obligatorios no cambian aún.
- JUnit sigue disponible dentro de Jenkins, sin publicar el redundante
  `Tests / Declarative: Post Actions`. Cada build limpia su workspace antes del checkout.
- Si faltan reportes, la cobertura se marca incompleta. Si el pipeline iba bien, ese problema
  lo hace fallar; si ya falló otra etapa, el reporte parcial se publica como neutral.
  Una cobertura generada no significa que todas las pruebas o el pipeline hayan pasado.

Los resúmenes se construyen desde XML, no desde porcentajes escritos manualmente.
`python3 -m unittest discover -s scripts -p test_coverage_summary.py` prueba el publicador
sin red ni credenciales. Requiere Python 3.10+ (ya disponible en el agente compartido).

### Migración administrativa pendiente

1. Verificar ambos checks en el mismo SHA y que provengan de la App `5247357`.
2. Sustituir el contexto obligatorio antiguo por `CI / Validación (Jenkins)` en develop/master,
   preservando todos los demás checks, revisión, actualización estricta y restricciones.
3. Tras integrar esta preparación, retirar la publicación automática antigua del controlador,
   manteniendo el publicador explícito del Jenkinsfile. No modificar su nombre a ciegas.
4. `infra/jenkins/configure-reporting.groovy` desactiva solo el status legado
   `continuous-integration/jenkins/pr-merge`; ejecutar como administrador del controlador
   después de verificar que no sea requerido. No ejecutarlo desde el pipeline ni reiniciar Jenkins.
5. Renombrar la App visible a **Solventa CI Android**, conservando ID, instalación y credenciales.

La configuración del controlador y el nombre de la App no cambian por integrar este PR.
Los nombres futuros de Actions (`CI / Calidad (Actions)`, `CI / Interfaz Android`) requieren
la misma migración coordinada; los checks de política de backport se mantienen por seguridad.

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
