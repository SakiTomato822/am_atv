# The licensed MusicKit adapter uses reflection and the SDK loads JNI entrypoints.
# Keep that binary boundary intact while optimizing the app and Compose runtime.
-keep class com.apple.** { *; }
-keep class dev.amatv.integration.MusicKitTokenProvider { *; }

# Optional desktop JavaCPP build-tool/logging integrations bundled in the SDK;
# Android playback does not load Maven plugins or the SLF4J backend.
-dontwarn org.apache.maven.plugins.annotations.Mojo
-dontwarn org.slf4j.Logger
-dontwarn org.slf4j.LoggerFactory
