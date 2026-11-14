# Bájalo · Swoam

**Bájalo** es parte del ecosistema **Swoam**. Es una app de Android para descargar videos, audio y fotos de TikTok, YouTube, Instagram, X, Facebook y [más de mil sitios](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md). Pegas enlaces, eliges la calidad y se guardan en el teléfono.

## Funciones

- **Varios enlaces a la vez**: pega uno por línea, o usa **Pegar** para tomar lo que haya en el portapapeles.
- **Calidad por enlace o para toda la lista**: mejor calidad, hasta 1080p/720p/480p, **audio MP3** o **audio M4A** (el original, sin recomprimir).
- **Menú Compartir**: desde TikTok, YouTube o cualquier app, *Compartir → Guardar con Bájalo* y empieza a descargar solo.
- **Cuentas opcionales** (YouTube, Instagram, Facebook, X, TikTok) para contenido privado o con restricción. Inicias sesión en la web oficial dentro de la app, y la app guarda solo las cookies de la sesión, nunca la contraseña. También puedes **importar un `cookies.txt`**.
- **Carpetas separadas**: `Descargas/Bajalo/Video`, `Audio` y `Fotos`, o la carpeta que elijas (mantén presionado *Elegir carpeta* para volver a la predeterminada).
- **Descargas en segundo plano** con notificación de progreso (2 a la vez).
- **Lista limpia**: lo que termina desaparece de la cola; los errores se quedan con un mensaje y el botón *Reintentar*.
- **Mis descargas**: abrir, compartir o borrar lo descargado.
- **Actualizar motor**: actualiza yt-dlp dentro de la app cuando un sitio deja de funcionar.
- **Se actualiza sola desde Swoam**: cada 12 horas mira los Releases de GitHub de su repositorio. Si hay una versión nueva, te la ofrece, la descarga y abre el instalador sin salir de la app. También puedes buscarla con *Buscar actualización de la app*.
- **Registro** persistente (*Ver registro*; mantén presionado para borrarlo).
- Tema claro, oscuro o del sistema.

## Cómo está hecha

| | |
|---|---|
| Lenguaje | Kotlin, Android nativo (Views + Material 3) |
| Motor | [youtubedl-android](https://github.com/yausername/youtubedl-android) (yt-dlp + Python + ffmpeg + QuickJS) |
| Android | 8.0 (API 26) o superior, `arm64-v8a` |
| Tamaño | ~60 MB (casi todo es ffmpeg y Python) |

```
app/src/main/java/com/swoam/bajalo/
├── MainActivity.kt      Pantalla principal y lista de la cola
├── Engine.kt            Cola de descargas sobre yt-dlp
├── Basics.kt            Calidades (Preset), ajustes, registro, extracción de enlaces
├── Cookies.kt           Cuentas y cookies.txt
├── LoginActivity.kt     Inicio de sesión en navegador interno
├── Saver.kt             Guardado en Descargas / carpeta elegida + historial
├── DownloadsActivity.kt "Mis descargas"
├── Updater.kt           Autoactualización desde los Releases de Swoam
└── Service.kt           Application, notificaciones y servicio en primer plano
```

## Repositorio en Swoam

La app busca sus actualizaciones en `swoam/bajalo` (`UPDATE_REPO` en `app/build.gradle.kts`). Cuando se compila con GitHub Actions, usa automáticamente el repositorio desde el que se compila.

Para moverla a su propio repositorio:

1. Crea en GitHub el repositorio **`swoam/bajalo`** (vacío, sin README).
2. Sube este código:
   ```bash
   git remote add bajalo https://github.com/swoam/bajalo.git
   git push bajalo HEAD:main
   ```
3. Configura la llave de firma (ver abajo) y publica la primera versión con `git tag v1.0 && git push bajalo v1.0`.

## Conseguir el APK

### Opción A: GitHub Actions (sin instalar nada)

Cada push a `main` compila el APK. Lo encuentras en **Actions → Compilar APK → (la ejecución) → Artifacts → Bajalo-apk**.
Si creas una etiqueta `v1.0`, `v1.1`, etc., se publica también como **Release** con su SHA-256:

```bash
git tag v1.0 && git push origin v1.0
```

### Opción B: compilar en tu PC

Necesitas JDK 17 y el SDK de Android (o Android Studio):

```bash
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

## Firma (importante para poder actualizar)

Android solo deja instalar una versión nueva encima de la anterior si ambas están firmadas con **la misma llave**. Sin llave propia, cada build de Actions usa una llave de depuración distinta y tendrías que desinstalar antes de actualizar.

1. Crea la llave una sola vez (y guárdala bien, sin ella no podrás publicar actualizaciones):
   ```bash
   keytool -genkeypair -v -keystore bajalo.jks -alias bajalo -keyalg RSA -keysize 4096 -validity 10000
   ```
2. En GitHub: **Settings → Secrets and variables → Actions** y añade:
   - `KEYSTORE_BASE64`: el resultado de `base64 -w0 bajalo.jks`
   - `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`bajalo`) y `KEY_PASSWORD`

## Limitaciones conocidas

- **Publicaciones de Instagram solo con fotos**: yt-dlp descarga reels, videos y carruseles con video, pero no siempre las publicaciones que son únicamente imágenes.
- **Inicio de sesión de Google** en navegadores internos: a veces Google lo bloquea. Si pasa, exporta `cookies.txt` desde el navegador del PC (con una extensión tipo *Get cookies.txt LOCALLY*) e impórtalo.
- Si un sitio deja de funcionar, toca **Actualizar motor**: las plataformas cambian a menudo y yt-dlp se actualiza igual de seguido.

## Aviso

Bájalo es un proyecto de Swoam.

Descarga solo contenido sobre el que tengas derecho, y respeta los términos de cada plataforma.

## Licencias de terceros

La app incluye yt-dlp (Unlicense), youtubedl-android (GPL-3.0), ffmpeg (GPL/LGPL), Python (PSF) y QuickJS (MIT). Como youtubedl-android es GPL-3.0, si distribuyes el APK tienes que ofrecer también el código fuente bajo GPL-3.0. Este repositorio ya publica el código; falta añadir un archivo `LICENSE` con la GPL-3.0.
