# Prueba de rotación de la clave de firma

`tumbada.lineage` enlaza la clave antigua (CN=Pitipo, SHA-256 0c3931…53ea25) con la nueva
(SHA-256 e5b3db…486d07). Contiene solo certificados públicos y la firma de la clave antigua
sobre la nueva: **no contiene ninguna clave privada** y puede estar en el repositorio.

Gracias a ella, un móvil con una versión anterior de Tumbada acepta la nueva como actualización
(sin perder datos), y a partir de ahí rechaza cualquier APK firmado solo con la clave antigua.
