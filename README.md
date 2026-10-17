# Solventa Android — Proyecto Final II

Aplicación Android de Solventa, desarrollada por el Grupo 2 para MISW4501 en la Universidad de los Andes.

## Comprobaciones y cobertura

En cada [pull request](https://github.com/JUANES545/solventa-app-pf2/pulls), abre **Checks → Cobertura**
para ver líneas, ramas, pruebas y revisión compilada. El reporte corresponde a ese commit;
no es un porcentaje fijo de la portada. En Android mide pruebas JVM, no instrumentación.

Actions se ejecuta automáticamente. Un colaborador solicita Jenkins con un comentario nuevo
`/test-jenkins`; después de subir cambios debe solicitarlo para la revisión nueva.
Los reportes HTML/XML se conservan en Jenkins privado. No se exige un porcentaje mínimo nuevo.
Consulta [la guía de CI](docs/ci.md) para ver alcance, nombres y lectura de resultados.


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

Una vez que el proyecto Android esté incorporado, documenta y valida los comandos reales de compilación, pruebas y ejecución.

Como mínimo, la validación deberá confirmar:

1. Gradle sincroniza sin errores.
2. La aplicación compila.
3. La aplicación inicia en un emulador o dispositivo.
4. Las pruebas disponibles se ejecutan correctamente.


## Equipo

- Juan Mejía — `JUANES545`
- Miguel Gómez — `Migue765`
- Jazmin Córdoba — `JazminCorAndes`
- Natalia Arandio — `nataliaarandio`

## Colaboración y Git

- `master` contiene versiones estables.
- `develop` contiene el trabajo integrado para la siguiente versión.
- Los cambios a cualquiera de estas ramas requieren pull request, al menos una aprobación y las comprobaciones exigidas por sus protecciones.

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
