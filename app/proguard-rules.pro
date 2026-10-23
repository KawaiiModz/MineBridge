# MineBridge ProGuard Rules

# 1. DISABLE OBFUSCATION (Do not rename any classes, fields, or methods)
-dontobfuscate

# 2. Keep line numbers for clean error stack traces
-keepattributes SourceFile,LineNumberTable

# 3. Preserve JNI Native C++ methods
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# 4. Preserve View Binding classes
-keep class me.kawaiimodz.minebridge.databinding.** { *; }

# 5. Preserve all MineBridge core application classes and symbols
-keep class me.kawaiimodz.minebridge.** { *; }
