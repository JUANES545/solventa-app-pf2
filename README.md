# Solventa

Tu acceso a Solventa desde Android.

<a href="https://appetize.io/app/b_gtzqvlruilrgvlrll4wschl6wy"><img src="docs/assets/abrir-solventa.svg" alt="Abrir Solventa" width="260" height="72"></a>
<a href="https://github.com/JUANES545/solventa-app-pf2/releases/latest"><img src="docs/assets/descargar-solventa.svg" alt="Descargar la última versión de Solventa para Android" width="260" height="72"></a>

## Desarrollo

<details>
<summary>Preparar el proyecto y ejecutar las pruebas</summary>

### Requisitos

- Android Studio y JDK 17.
- Android SDK 35.
- Emulador Android o dispositivo físico.

### Instalación

```bash
git clone https://github.com/JUANES545/solventa-app-pf2.git
cd solventa-app-pf2
```

Abre el proyecto en Android Studio y sincroniza Gradle.

### Compilación y pruebas

```bash
./gradlew assembleDebug lintDebug testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

El segundo comando requiere un emulador o dispositivo conectado. En Windows, usa `gradlew.bat`.

### Ramas

- `master`: versiones publicadas.
- `develop`: integración de la siguiente versión.

Los cambios se revisan mediante pull requests. Las sincronizaciones exactas de versiones
aprobadas se integran mediante la automatización de backport.

</details>

## Equipo

- Juan Mejía — `JUANES545`
- Miguel Gómez — `Migue765`
- Jazmin Córdoba — `JazminCorAndes`
- Natalia Arandio — `nataliaarandio`
