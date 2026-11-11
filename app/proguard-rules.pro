# Tesseract and Leptonica are called from native code through JNI; keep their Java side intact.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
