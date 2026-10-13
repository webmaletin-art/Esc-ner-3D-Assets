# kotlinx.serialization (solo se usa el árbol JsonElement; sin clases @Serializable)
-dontwarn kotlinx.serialization.**
# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# Interfaz JavaScript no se usa (se emplea WebMessageListener); se conservan los modelos de núcleo por reflexión nula.
-keep class app.universal3d.core.** { *; }
