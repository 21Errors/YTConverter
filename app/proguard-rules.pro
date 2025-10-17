# Keep Rhino classes and suppress warnings about missing java.beans classes
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn org.mozilla.javascript.**
-keep class org.mozilla.javascript.** { *; }
```

This tells R8 (Android's code optimizer) to:
1. Not warn about the missing Java Desktop API classes
2. Keep all Rhino classes so they work at runtime

Your `build.gradle` already has minification enabled with ProGuard, so these rules will be applied during the build.

**If that doesn't work**, try being more aggressive and add this to `proguard-rules.pro`:
```
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.**
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
```

After adding these rules, clean and rebuild your project:
```
./gradlew clean build