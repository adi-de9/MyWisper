# MyWisper release shrinking (R8).
# Services and activities are kept automatically via the manifest. Firebase, Credential Manager and
# OkHttp ship their own consumer rules; these cover reflection we rely on.
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keep class com.google.android.libraries.identity.googleid.** { *; }
-keep class androidx.credentials.playservices.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
