# MyRideStats

App Android que registra la inclinación de la moto en cada ruta, con el móvil en el bolsillo.

- Máximo de inclinación a **izquierda** y **derecha** por ruta y récords absolutos
- **Tiempo inclinado** hacia cada lado (cuenta a partir de 5°) y reparto en %
- **Historial** de rutas, gráfica de inclinación de toda la ruta, lista de curvas
  (ordenable por más tumbadas o cronológica) e histograma de curvas por tramos de 10°
- Graba en segundo plano con la pantalla apagada (servicio en primer plano + notificación con botón "Parar y guardar")

## Cómo mide (y por qué el bolsillo no importa)

Un acelerómetro no sirve para medir la inclinación en moto: en una curva bien trazada la
gravedad + la fuerza centrífuga apuntan justo a lo largo del eje de la moto, así que el móvil
"cree" que va recto aunque vayas a 40°.

La app usa la física de la curva: `tan(φ) = v·ω / g`
- `v` = velocidad del GPS
- `ω` = velocidad de giro del giroscopio, proyectada sobre el eje vertical de la moto
  (que da el acelerómetro filtrado, venga el móvil como venga)

Con esa proyección la fórmula queda `sin(φ) = v·ω / g`, y **la postura del móvil en el
bolsillo se cancela sola**: no hace falta calibrar el ángulo ni ponerlo en una posición concreta.
Lo único que se calibra es el sesgo del giroscopio, automáticamente, en los primeros ~3 s parado.

Limitaciones:
- No mide por debajo de 15 km/h (rotondas lentas, maniobras).
- Mide la inclinación "de curva estable". En la entrada brusca a una curva va ~0,3 s por detrás,
  pero el máximo, que se da en mitad de la curva, sale bien.
- Es la inclinación del **conjunto moto + piloto** (la que dicta la física). Si cuelgas el cuerpo,
  la moto va algo menos tumbada que eso; si vas "empujando" la moto, algo más.
- Curvas de menos de 0,7 s o 10° se ignoran (baches, cambios de carril).

## Compilar y firmar

El proyecto **no contiene ninguna clave de firma**. La clave de release vive fuera
(por defecto `~/.tumbada-firma/tumbada-firma.p12` + `clave.txt`; el nombre de la carpeta se mantiene por compatibilidad) y se aplica con
`scripts/firmar-apk.sh`, junto con la prueba de rotación `signing-lineage/tumbada.lineage`.
Gradle genera el APK de release **sin firmar**; las compilaciones debug usan la clave debug de Android.

### Opción A: línea de comandos / Android Studio
```
./gradlew assembleRelease
scripts/firmar-apk.sh app/build/outputs/apk/release/app-release-unsigned.apk MyRideStats.apk
adb install -r MyRideStats.apk
```

### Opción B: GitHub Actions
1. Repo **privado**. En *Settings → Secrets and variables → Actions* crea:
   - `TUMBADA_KEYSTORE_B64`: el resultado de `base64 -w0 tumbada-firma.p12`
   - `TUMBADA_KEYSTORE_PASS`: la contraseña
2. Cada push compila, firma y deja el artefacto `tumbada-apk`.

### Si pierdes la clave
No se podrá actualizar encima: habría que desinstalar (se pierden las rutas) e instalar con una
clave nueva. Guarda `tumbada-firma.p12` y su contraseña en dos sitios seguros.

## Primer uso en el Nothing Phone
1. Pulsa **Empezar ruta** y concede ubicación y notificaciones.
2. Pulsa **Quitar restricción** en el aviso de batería (si no, Nothing OS puede matar la grabación).
3. Arranca con la moto parada unos segundos, guarda el móvil y a rodar.
4. Para al terminar desde la app o desde la notificación. Las rutas de menos de 1 minuto no se guardan.

Los datos se guardan en local (JSON en el almacenamiento interno de la app).

## Google Play

Todo lo necesario para publicar está en [`play/`](play/PLAY.md): guía paso a paso, textos de la ficha en
español e inglés, gráficos y respuestas a los formularios. La política de privacidad está en
[`docs/privacy/`](docs/privacy/index.html) y se publica con GitHub Pages. Los AAB para Play se firman con
la clave de subida (`scripts/firmar-aab.sh`); los APK para instalar a mano, con `scripts/firmar-apk.sh`.

## Ajustes

Engranaje arriba a la derecha en «En ruta», «Historial» y «Análisis»:
- **Tema:** sistema, oscuro (Glifo) o claro.
- **Idioma:** sistema, español o inglés. En Android 13+ se guarda como idioma de la app del sistema
  (también se puede cambiar en Ajustes del sistema → Apps → MyRideStats → Idioma).
- **Mapa:** OpenStreetMap (sin clave) o CARTO (con clave gratuita).

Los textos están en `app/src/main/res/values/strings.xml` (inglés, por defecto) y `values-es/` (español).

Nota: el identificador interno del paquete sigue siendo `com.pitipo.tumbada` para que las
actualizaciones se instalen encima sin perder datos. No es visible para los usuarios.
