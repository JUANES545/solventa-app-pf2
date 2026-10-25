# Solventa

Tu acceso a Solventa desde Android.

<a href="https://appetize.io/app/b_gtzqvlruilrgvlrll4wschl6wy"><img src="docs/assets/abrir-solventa.svg" alt="Abrir Solventa" width="260" height="72"></a>
<a href="https://github.com/JUANES545/solventa-app-pf2/releases/latest"><img src="docs/assets/descargar-solventa.svg" alt="Descargar la última versión de Solventa para Android" width="260" height="72"></a>

[📊 Cobertura de código](https://github.com/Migue765/backend-solventa/wiki/Cobertura-de-codigo)

Aplicación Android de Solventa, desarrollada por el Grupo 2 para MISW4501 en la Universidad de los Andes.


## Requisitos previos

- Git
- Android Studio
- JDK compatible con la versión de Gradle seleccionada
- Android SDK y componentes requeridos por el proyecto
- Emulador Android o dispositivo físico para ejecutar la aplicación


## Instalación del repositorio

```bash
git clone https://github.com/JUANES545/solventa-app-pf2.git
```

Abre el repositorio en Android Studio una vez que se haya agregado el proyecto Android.

## Preparación del entorno Android


1. Abre el proyecto desde Android Studio.
2. Instala los componentes de Android SDK que solicite la configuración del proyecto.
3. Configura el JDK requerido por el Gradle Wrapper del proyecto.
4. Sincroniza Gradle.
5. Resuelve los errores de sincronización antes de ejecutar la aplicación.
6. Selecciona un emulador o dispositivo físico.



## Ejecución y pruebas

Con JDK 17 y Android SDK 35:

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
.\gradlew.bat --no-daemon connectedDebugAndroidTest
```

La instrumentación requiere un emulador dedicado sin cuentas reales y limpia su sesión
local de Solventa. La suite [Espresso + Compose](docs/testing.md#suite-espresso-integrada)
ejercita la actividad real, HTTP controlado y Android Keystore. El
[E2E contra backend local real](docs/testing.md#e2e-con-backend-real-y-persistencia-temporal)
se activa por separado; el mismo documento identifica las funcionalidades cubiertas
y las brechas respecto al enunciado y la arquitectura.


## Equipo

- Juan Mejía — `JUANES545`
- Miguel Gómez — `Migue765`
- Jazmin Córdoba — `JazminCorAndes`
- Natalia Arandio — `nataliaarandio`

## Colaboración y Git

- `master` contiene versiones estables.
- `develop` contiene el trabajo integrado para la siguiente versión.
- Los cambios a cualquiera de estas ramas requieren pull request, al menos una aprobación y las comprobaciones exigidas por sus protecciones.
- Las entregas usan X.Y.Z: parche por ajuste pequeño, menor por cierre semanal y mayor
  por cierre de sprint. El equipo elige el incremento; el CI valida la versión y el CD
  publica desde `master`. Ver [preparación de entregas](docs/android-publication.md#identificación-de-las-entregas).

## Referencias

- [Backlog de Jira](https://proyectointegradorgrupo2.atlassian.net/jira/software/projects/SOL/boards/2/backlog)
- [Historia de configuración del repositorio: SOL-146](https://proyectointegradorgrupo2.atlassian.net/browse/SOL-146)
- [Documentación del proyecto](https://github.com/Migue765/proyecto-final-uniandes)
- [Wiki del proyecto](https://github.com/Migue765/proyecto-final-uniandes/wiki)
- [Repositorio Android anterior, solo como referencia](https://github.com/JUANES545/solventa-app)

El repositorio anterior es una referencia; el caso del curso, los criterios de aceptación aprobados en Jira y el Figma vigente son la fuente de verdad para el alcance actual.

## Solución de problemas

### Android Studio no puede sincronizar Gradle
- Confirma que el proyecto Android y el Gradle Wrapper ya estén incluidos.
- Comprueba la versión de JDK requerida por el proyecto.
- Revisa los mensajes de sincronización y los componentes de SDK faltantes.

### No se puede ejecutar la aplicación
- Confirma que el proyecto base ya haya sido agregado.
- Comprueba que haya un emulador iniciado o un dispositivo conectado.
- Documenta el error concreto y la solución verificada para que el resto del equipo pueda reproducirla.
