# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class io.github.romanvht.byedpi.core.ByeDpiProxy { *; }

-keep,allowoptimization class io.github.romanvht.byedpi.core.TProxyService { *; }
-keep,allowoptimization class io.github.romanvht.byedpi.activities.** { *; }
-keep,allowoptimization class io.github.romanvht.byedpi.services.** { *; }
-keep,allowoptimization class io.github.romanvht.byedpi.receiver.** { *; }

-keep class io.github.romanvht.byedpi.fragments.** {
    <init>();
}

-keep,allowoptimization class io.github.romanvht.byedpi.data.** {
    <fields>;
}

-keep,allowoptimization class io.github.romanvht.byedpi.vpn.VpnProfile {
    <fields>;
}

# snakeyaml тянет java.beans, которых в Android нет — на разбор YAML это не влияет
-dontwarn java.beans.**
-keep class org.yaml.snakeyaml.** { *; }

# zxing (сканер QR) обращается к классам по имени
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }

-keepattributes Signature
-keepattributes *Annotation*

-repackageclasses 'ru.romanvht'
-renamesourcefileattribute ''
-keepattributes SourceFile,InnerClasses,EnclosingMethod,Signature,RuntimeVisibleAnnotations,*Annotation*,*Parcelable*
-allowaccessmodification
-overloadaggressively
-optimizationpasses 5
-verbose
-dontusemixedcaseclassnames
-adaptclassstrings
-adaptresourcefilecontents **.xml,**.json
-adaptresourcefilenames **.xml,**.json