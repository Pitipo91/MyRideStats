# Publicar MyRideStats en Google Play

Guía paso a paso para la primera publicación (fase 1: app sin cuentas). Todo lo que hay que pegar en
Play Console está en esta carpeta.

| Archivo | Para qué |
|---|---|
| `MyRideStats-1.8.1.aab` (se entrega aparte) | Lo que se sube a Play. Firmado con la clave de subida |
| `upload_certificate.pem` | Certificado público de la clave de subida |
| `graficos/icono-512.png` | Icono de la ficha (512×512) |
| `graficos/grafico-destacado-1024x500.png` | Gráfico destacado |
| `ficha/es-ES/*.txt`, `ficha/en-US/*.txt` | Título, descripción breve y descripción completa |
| `../docs/privacy/index.html` | Política de privacidad (se publica con GitHub Pages) |

## Las dos claves

- **Clave de firma de la app** (`tumbada-firma.p12`, alias `tumbada2`). Es la identidad de la app: la
  que ya tienen instalada tus amigos. Se entrega a Google **una sola vez**, cifrada, y desde entonces
  Google firma con ella lo que se descarga desde Play. Así, el APK que ya tenéis y la versión de Play
  son la misma app y se actualizan entre sí sin perder rutas.
- **Clave de subida** (`subida.p12`, alias `upload`). Con ella firmas cada AAB que subes. Si la pierdes o
  se filtra, Google te deja cambiarla desde Play Console; la de firma no se puede cambiar.

Las dos viven fuera del proyecto (`~/.tumbada-firma/` y tu copia `firma-myridestats.zip`). Guarda las
contraseñas en un gestor de contraseñas.

## 0. Publicar la política de privacidad

Play exige una URL pública. La app enlaza a `https://pitipo91.github.io/MyRideStats/privacy/`.

1. Crea en GitHub el repositorio `MyRideStats` (en tu usuario `Pitipo91`, público, para que Pages
   sea gratis) y sube el proyecto.
2. Antes, sustituye `CONTACTO` en `docs/privacy/index.html` por tu email de contacto (4 apariciones).
   Recomendado: un Gmail dedicado a la app, porque Play también lo muestra en la ficha.
3. En el repositorio: **Settings → Pages → Build and deployment → Deploy from a branch →
   `main` / `docs`**. A los pocos minutos la URL funciona.

## 1. Cuenta de desarrollador

1. Entra en <https://play.google.com/console/signup> con la cuenta de Google que quieras usar para
   siempre (no se puede cambiar de dueño fácilmente).
2. Elige **cuenta personal**, paga los 25 $ y verifica tu identidad (DNI) y tu teléfono.
3. Google tarda de horas a pocos días en validar la identidad.

## 2. Crear la app

**Crear aplicación** →
- Nombre: `MyRideStats`
- Idioma predeterminado: Español (España) – es-ES
- Aplicación (no juego) · Gratuita
- Acepta las declaraciones.

## 3. Firma de la app (lo más importante)

**Prueba y publica → Configuración → Integridad de la app → Firma de apps** (o te lo pedirá al subir el
primer AAB). Elige **«Usar una clave diferente» → «Exportar y subir una clave de un almacén de claves
Java»**. Play Console te da dos descargas: `pepk.jar` y la clave pública de cifrado
(`encryption_public_key.pem`).

Ejecuta (necesitas Java 11 o superior) en la carpeta donde estén `tumbada-firma.p12` y esos dos archivos:

```bash
java -jar pepk.jar \
  --keystore=tumbada-firma.p12 \
  --alias=tumbada2 \
  --output=clave-cifrada.zip \
  --include-cert \
  --rsa-aes-encryption \
  --encryption-key-path=encryption_public_key.pem
```

Te pide la contraseña de `tumbada-firma.p12` dos veces (almacén y clave: es la misma). Sube
`clave-cifrada.zip`.

En la misma pantalla, marca la opción de **usar una clave de subida distinta** y sube
`upload_certificate.pem`.

> Si prefieres no hacerlo tú, pásame `pepk.jar` y `encryption_public_key.pem` (son públicos) y te
> devuelvo `clave-cifrada.zip`.

## 4. Contenido de la app (Política → Contenido de la app)

### Política de privacidad
`https://pitipo91.github.io/MyRideStats/privacy/`

### Anuncios
**No**, la app no contiene anuncios.

### Acceso a la app
**Todas las funciones están disponibles sin restricciones de acceso** (no hay inicio de sesión).

### Clasificación de contenido
- Categoría: **Todas las demás tipos de apps** (utilidad).
- Responde **No** a todo (violencia, sexo, lenguaje, drogas, juegos de azar, interacción entre usuarios,
  compartir ubicación con otros usuarios, compras digitales).
- Resultado esperado: PEGI 3 / Todos.

### Público objetivo
- Edad: **18 años o más** únicamente. Evita la política de familias y encaja con conducir moto.
- ¿Atrae a niños?: **No**.

### Seguridad de los datos (fase 1)
- ¿Recoge o comparte datos de usuario de los tipos requeridos? **Sí**. Es por las teselas del mapa: los
  servidores de OpenStreetMap/CARTO reciben qué zona se pide. Declararlo es lo más prudente.
- ¿Todos los datos se cifran en tránsito? **Sí** (HTTPS).
- ¿Ofreces una forma de solicitar la eliminación? **No** hace falta en esta fase: no guardamos nada en
  servidores. Si te obliga a responder, elige que los usuarios pueden borrar los datos desde la app
  (borrar ruta / desinstalar).
- Tipos de datos:
  - **Ubicación → Ubicación aproximada**: *recogida* · **no** compartida (es una transferencia iniciada
    por el usuario, al mirar el mapa) · **procesada de forma efímera: sí** · obligatoria (no opcional) ·
    finalidad: **funciones de la app**.
  - Nada más. La ubicación precisa, los sensores y las rutas **no salen del móvil**, así que según la
    definición de Google no cuentan como «recogidos».

> En la fase 3 (cuentas y grupos) habrá que actualizar esto: email, nombre, ID de usuario y estadísticas
> de rutas pasarán a ser «recogidos», y será obligatorio un enlace web para borrar la cuenta.

### Servicios en primer plano (Android 14+)
Play pide declarar el tipo `location`:
- Caso de uso: elige el más cercano a **seguimiento de actividad / navegación iniciado por el usuario**
  (los nombres exactos de la lista cambian de vez en cuando).
- Descripción (pégala tal cual):

  > Cuando el usuario pulsa «Empezar ruta», la app inicia un servicio en primer plano de tipo
  > ubicación que registra el GPS y los sensores de movimiento durante la ruta en moto para calcular la
  > velocidad y el ángulo de inclinación. Sigue funcionando con la pantalla apagada, muestra siempre una
  > notificación y se detiene cuando el usuario pulsa «Parar y guardar». El usuario espera que la ruta
  > se grabe completa aunque bloquee el móvil y lo guarde en el bolsillo.

- Vídeo: Play pide un enlace a un vídeo corto (YouTube oculto o Drive público). Grábalo con la
  grabación de pantalla del Nothing: abrir la app → Empezar ruta → bajar la persiana para que se vea
  la notificación → bloquear y desbloquear → Parar y guardar. Con 30 segundos basta; puede ser parado
  en la calle.

### Permisos de ubicación
La app **no** usa `ACCESS_BACKGROUND_LOCATION`: no hay que rellenar la declaración de ubicación en
segundo plano. Si Play pregunta, la ubicación solo se usa con el servicio en primer plano iniciado por
el usuario.

### Otros
- App de noticias: No · Apps de salud: No · App gubernamental: No · Funciones financieras: No.
- ID de publicidad: **No** se usa.

## 5. Ficha de Play Store (Hacer crecer → Presencia en Play Store → Ficha principal)

- Pega `ficha/es-ES/*.txt`. Luego **Añadir traducción → Inglés (Estados Unidos) – en-US** y pega
  `ficha/en-US/*.txt`.
- Icono: `graficos/icono-512.png` · Gráfico destacado: `graficos/grafico-destacado-1024x500.png`.
- **Capturas de teléfono: entre 2 y 8, hazlas tú en el móvil.** Propuesta: En ruta (grabando), Historial,
  detalle de una ruta con mapa, Análisis (simetría + progreso), mapa de calor, una curva de siempre.
  Usa la tecla de captura del Nothing tal cual, sin marcos.
- Categoría: **Auto & Vehicles** (en español aparece como la de automóviles/vehículos; alternativa: Deportes). Email de contacto: el mismo de la
  política.

## 6. Prueba cerrada (obligatoria: 12 testers × 14 días)

1. **Prueba y publica → Pruebas → Prueba cerrada → Crear pista** (o usa «Alpha»).
2. **Testers**: crea una lista de emails con al menos **12 personas** (sus cuentas de Google de Play).
   Mejor 15 o más, por si alguien se borra.
3. **Crear versión** → sube `MyRideStats-1.8.1.aab` → nombre `1.8.1` → notas de la versión:
   ```
   <es-ES>
   Primera versión en Google Play.
   </es-ES>
   <en-US>
   First release on Google Play.
   </en-US>
   ```
4. Países: España (y los que quieras).
5. **Enviar a revisión**. La primera tarda unos días.
6. Cuando esté aprobada, copia el **enlace para unirse** y pásalo a los testers. Cada uno debe:
   aceptar desde el enlace → instalar desde Play. Quien ya tenga el APK instalado lo verá como
   actualización y **no pierde sus rutas**.
7. Los 12 testers tienen que **seguir apuntados 14 días seguidos**. Pídeles que la abran de vez en
   cuando: Google pregunta después cómo se usó la prueba.

## 7. Acceso a producción

Pasados los 14 días aparece **Solicitar acceso a producción** en el Panel. Te pregunta cómo
reclutaste testers, qué feedback recibiste y qué cambiaste. Respóndelo con lo que haya pasado (lo
redactamos juntos). Tras la aprobación, promociona la versión de la prueba cerrada a producción.

## Cada actualización

1. Subir `versionCode` (y `versionName`) en `app/build.gradle.kts`.
2. Compilar el AAB y firmarlo con `scripts/firmar-aab.sh`:
   - en GitHub Actions, con los secrets `MRS_UPLOAD_KEYSTORE_B64` y `MRS_UPLOAD_KEYSTORE_PASS`;
   - o en local: `./gradlew bundleRelease` y
     `scripts/firmar-aab.sh app/build/outputs/bundle/release/app-release.aab MyRideStats.aab`.
3. Subirlo a la pista que toque en Play Console.

El APK firmado con `scripts/firmar-apk.sh` sigue funcionando para instalar a mano: es la misma clave
que usa Google, así que se puede alternar entre APK y Play.
