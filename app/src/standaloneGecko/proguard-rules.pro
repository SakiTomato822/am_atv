# Gecko preserves annotation metadata, including JavaCPP's unused Maven build
# annotations bundled by the native MusicKit SDK. Maven is never run on Android.
-dontwarn org.apache.maven.plugins.annotations.LifecyclePhase
