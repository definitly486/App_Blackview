# ───────────────────────────────
# BouncyCastle (нужен только OpenPGP: GpgDecryptor)
# ───────────────────────────────
# BouncyCastleProvider грузит реализации алгоритмов по строковым именам
# (Class.forName("...$Mappings"), provider.put("Cipher.AES", "...AES$ECB")),
# поэтому R8 не видит эти классы. Оставляем только пакеты провайдера, а
# bcpg/openpgp и остальное R8 найдёт по прямым ссылкам и вырежет лишнее.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**

# ───────────────────────────────
# JGit (клонирование репозиториев)
# ───────────────────────────────
# Локализованные сообщения читаются рефлексией по имени класса/полей
-keep class org.eclipse.jgit.internal.JGitText { *; }
-keep class * extends org.eclipse.jgit.nls.TranslationBundle { *; }
-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**
# Если клонирование в release начнёт падать с NoSuchFieldError/MissingResourceException,
# верните полный keep на время отладки:
# -keep class org.eclipse.jgit.** { *; }

# --- Shizuku / shell-сервис (release с isMinifyEnabled = true) ---
# ShellService создаётся Shizuku по имени класса, AIDL-заглушки вызываются через binder
-keep class com.example.app.shell.** { *; }
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# Отсутствующие на Android классы JDK, на которые ссылаются библиотеки
-dontwarn javax.management.**
-dontwarn java.lang.management.**
-dontwarn java.lang.ProcessHandle
-dontwarn org.ietf.jgss.**
