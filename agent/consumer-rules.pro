# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.muzhou.droidagent.agent.** {
    *** Companion;
}
-keepclasseswithmembers class com.muzhou.droidagent.agent.** {
    kotlinx.serialization.KSerializer serializer(...);
}
