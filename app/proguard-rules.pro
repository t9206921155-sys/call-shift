# --- CallShift: R8/ProGuard rules ---

# Telecom-сервисы вызываются системой по имени — их нельзя обфусцировать/удалить.
-keep class fi.callshift.app.screening.** { *; }
-keep class fi.callshift.app.telecom.** { *; }
-keep class fi.callshift.app.ui.BootReceiver { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class fi.callshift.app.** {
    *** Companion;
}
-keepclasseswithmembers class fi.callshift.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class fi.callshift.app.**$$serializer { *; }

# libphonenumber использует reflection для загрузки metadata
-keep class com.google.i18n.phonenumbers.** { *; }

# ViewBinding
-keep class * implements androidx.viewbinding.ViewBinding {
    public static *** inflate(android.view.LayoutInflater);
    public static *** inflate(android.view.LayoutInflater, android.view.ViewGroup, boolean);
    public static *** bind(android.view.View);
}
