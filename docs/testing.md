# Pruebas y cobertura Android

## Batería JVM

JUnit 4 ejecuta las pruebas del repositorio de autenticación y configuración.
Las pruebas del ViewModel usan `kotlinx-coroutines-test` para controlar el hilo principal.
Robolectric permite comprobar formularios Compose, navegación y contratos Android en la JVM,
sin sustituir la batería instrumentada existente.

`MobileAuthApiTest` utiliza MockWebServer en loopback para verificar métodos, rutas, payloads,
cabeceras, respuestas inválidas y errores HTTP. La base inyectada de prueba no cambia el
constructor de producción ni su URL HTTPS. Estas pruebas no consumen AWS ni crean cuentas.

## Medición

```sh
./gradlew --no-daemon jacocoDebugUnitTestReport
```

- XML: `app/build/reports/coverage/unit/coverage.xml`.
- HTML: `app/build/reports/coverage/unit/html/index.html`.
- JUnit: `app/build/test-results/testDebugUnitTest/`.

JaCoCo incluye las clases cargadas por el sandbox de Robolectric. Se mantienen las exclusiones
existentes de código generado; no se excluyen pantallas, ViewModel, cliente HTTP o almacenamiento.
El objetivo es alcanzar al menos el 80 % mediante pruebas de comportamiento, no reduciendo
el código medido. Jenkins publica los contadores y la revisión compilada en el check Cobertura.

## Dispositivo y compilación

```sh
./gradlew --no-daemon lintDebug assembleDebug assembleDebugAndroidTest assemblePreview
./gradlew --no-daemon connectedDebugAndroidTest
```

La segunda orden requiere un dispositivo o emulador. La batería de `androidTest` conserva
las comprobaciones de teclado, layouts, registro, sesión y Android Keystore. Las pruebas de
Keystore en dispositivo no se presentan como cubiertas por la JVM.

## Gráfico consolidado

[Cobertura de código](https://github.com/Migue765/backend-solventa/wiki/Cobertura-de-codigo)
identifica la revisión medida y la fecha de cada corte. Una medición de PR debe señalarse como
tal hasta integrarse; no se presenta como perteneciente a una versión estable diferente.
