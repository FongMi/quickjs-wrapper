# JNI and Function JAR callers resolve the public API by name.
-keep class com.fongmi.quickjs.* { *; }

# Java bindings are constructed and their annotated public methods are invoked
# through reflection. Method names become JavaScript property names.
-keepattributes RuntimeVisibleAnnotations
-keepclasseswithmembers class * {
    @com.fongmi.quickjs.JSMethod public *** *(...);
}

-if class ** {
    @com.fongmi.quickjs.JSMethod public *** *(...);
}
-keep class <1> {
    <init>();
    @com.fongmi.quickjs.JSMethod public *** *(...);
}

# getMethods() also exposes inherited bindings on a supplied subclass.
-if class ** {
    @com.fongmi.quickjs.JSMethod public *** *(...);
}
-keepclassmembers class * extends <1> {
    <init>();
}
