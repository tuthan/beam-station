# Readable stack traces in the log; the app is small and nothing in it is secret.
-dontobfuscate

# The shell helper's entry point: app_process starts it by name from this APK (helper.HelperLauncher).
-keep class io.github.tuthan.stationar.helper.Main {
    public static void main(java.lang.String[]);
}
