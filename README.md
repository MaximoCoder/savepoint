# savePoint

Gestor de contraseñas para Android, local y cifrado. Tus contraseñas viven solo en tu teléfono, cifradas con una contraseña maestra que nunca sale del dispositivo. No hay servidor, cuenta ni sincronización en la nube.

Construido con Ionic + Angular para la interfaz y Kotlin nativo para todo lo que toca criptografía.

## Funcionalidades

- **Bóveda cifrada** con contraseña maestra (mínimo 12 caracteres).
- **Desbloqueo con huella** usando una llave del Android Keystore atada a biometría fuerte.
- **Autocompletado del sistema**: rellena usuario y contraseña directo en otras apps y en Chrome, sin pasar por el portapapeles ni por el teclado.
- **Guardar desde otras apps**: al iniciar sesión con una cuenta nueva, Android ofrece guardarla en savePoint; si ya existe, actualiza la contraseña.
- **Generador de contraseñas** con longitud ajustable (12 a 64), números y símbolos, sin caracteres ambiguos.
- **Copia endurecida**: marcada como sensible y borrada del portapapeles a los 30 segundos, aunque la app esté en segundo plano.
- **Respaldo cifrado** exportable a Drive o a la computadora, con restauración en un teléfono nuevo o combinación sin borrar datos.
- **Bloqueo automático** configurable: al salir de la app, 1, 5 o 15 minutos.
- **Protección contra fuerza bruta**: espera creciente después de 5 intentos fallidos (de 30 s hasta 30 min), persistente entre reinicios de la app.
- **Cambio de contraseña maestra** sin volver a cifrar las entradas.
- Bloqueo de capturas de pantalla y de la vista previa en apps recientes (`FLAG_SECURE`).
- Interfaz en modo oscuro, pensada para teléfono.

## Diseño de seguridad

| Elemento | Implementación |
| --- | --- |
| Derivación de llave | Argon2id (64 MiB, 3 iteraciones, 1 hilo) con sal aleatoria de 16 bytes |
| Llave de la bóveda | 256 bits aleatorios, envuelta con la llave derivada de la contraseña maestra |
| Cifrado de entradas | AES-256-GCM por entrada, con el id como dato autenticado (AAD) |
| Huella | Llave AES del Android Keystore que exige biometría fuerte en cada uso y se invalida si se registra una huella nueva |
| Almacenamiento | `noBackupFilesDir`, fuera de los respaldos automáticos de Google; escritura atómica |
| Respaldo | El mismo archivo cifrado de la bóveda dentro de un sobre con metadatos; nunca se descifra para exportar |

La llave de la bóveda solo existe en memoria mientras está desbloqueada y se borra al bloquear. Toda la criptografía ocurre en Kotlin: la interfaz en Angular nunca recibe la lista completa de contraseñas, y copiar o autocompletar se hace del lado nativo.

### Limitaciones conocidas

- La contraseña maestra viaja como texto de la interfaz web al código nativo al desbloquear con contraseña. Con la huella activada, eso no ocurre en el uso diario.
- Algunos teclados (por ejemplo, Samsung Keyboard) ignoran la marca de contenido sensible y muestran la contraseña copiada en su barra de sugerencias. El autocompletado evita ese problema.
- El ofrecimiento de guardar depende de cómo cada app construye su pantalla de inicio de sesión; algunos inicios de sesión en dos pasos no lo disparan.
- Si olvidas la contraseña maestra, no hay forma de recuperar la bóveda. Por diseño.

## Stack

- Angular 21 + Ionic 9 (componentes standalone, signals)
- Capacitor 8
- Tailwind CSS 4 + daisyUI 5
- Kotlin (Android): Bouncy Castle (Argon2id), AndroidX Biometric, Autofill Framework, JobScheduler

## Estructura

```
src/app/
├── core/                 # servicios, guard, utilidades (generador, fortaleza)
├── pages/
│   ├── lock/             # crear, desbloquear, restaurar respaldo
│   ├── vault/            # lista y búsqueda
│   ├── entry/            # ver, editar, generar
│   └── settings/         # huella, bloqueo, autocompletado, respaldo
└── plugins/vault.ts      # interfaz TypeScript del plugin nativo

android/app/src/main/java/com/savepoint/
├── MainActivity.java
├── vault/
│   ├── VaultStore.kt     # núcleo criptográfico y almacenamiento
│   ├── VaultPlugin.kt    # puente Capacitor
│   ├── BiometricVault.kt
│   └── SecureClipboard.kt
└── autofill/
    ├── SavePointAutofillService.kt
    ├── AutofillFields.kt       # detección de campos
    ├── AutofillResponses.kt    # sugerencias y SaveInfo
    ├── AutofillSave.kt         # guardado de credenciales
    ├── BaseUnlockActivity.kt
    ├── AutofillAuthActivity.kt
    └── AutofillSaveActivity.kt
```

## Requisitos

- Node.js LTS y npm
- JDK 21
- Android SDK (Platform, Build-Tools, Platform-Tools)
- Teléfono con Android 8 o superior (Android 9+ para guardar desde otras apps con la bóveda bloqueada)

## Desarrollo

```bash
npm install
ionic build
npx cap sync android
npx cap run android
```

Con recarga en vivo (teléfono y computadora en la misma red):

```bash
ionic cap run android -l --external
```

Los cambios en Kotlin requieren volver a ejecutar `npx cap run android`.

## Build de release

1. Crea una llave de firma fuera del repositorio:

```bash
   keytool -genkeypair -v -keystore ~/keys/savepoint-release.jks -alias savepoint \
     -keyalg RSA -keysize 4096 -validity 10000
```

2. Crea `android/keystore.properties` (está en `.gitignore`):

```properties
   storeFile=/ruta/a/savepoint-release.jks
   storePassword=...
   keyAlias=savepoint
   keyPassword=...
```

3. Compila e instala:

```bash
   ionic build && npx cap sync android
   cd android && ./gradlew assembleRelease
   adb install -r app/build/outputs/apk/release/app-release.apk
```

Pasar de la versión de depuración a la de release requiere desinstalar la app, y eso borra la bóveda. Exporta un respaldo antes y restáuralo después.

## Configuración en el teléfono

- **Autocompletado:** Ajustes de savePoint → Activar autocompletado. En Samsung también está en Administración general → Contraseñas y autorrelleno.
- **Chrome:** Configuración → Servicios de autocompletar → Autocompletar con otro servicio.
- **Huella:** Ajustes de savePoint → Desbloqueo con huella.

## Licencia

Uso personal.
