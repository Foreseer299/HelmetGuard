# Room and Compose publish consumer rules. Keep Room entities used by generated code.
-keep class com.helmet.guard.data.db.** { *; }
-keepattributes *Annotation*

# EmbeddedSherpaTtsSynthesizer loads the optional bundled provider via reflection.
-keep class com.k2fsa.sherpa.onnx.** { *; }
