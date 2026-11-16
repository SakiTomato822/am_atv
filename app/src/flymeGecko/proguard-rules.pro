# Gecko preserves annotation metadata, including JavaCPP's unused Maven build
# annotations bundled by the native MusicKit SDK. Maven is never run on Android.
-dontwarn org.apache.maven.plugins.annotations.LifecyclePhase

# StationPlaybackEngine.create() loads the Flyme station engine by name.
-keep class dev.amatv.integration.GeckoStationEngine { <init>(android.content.Context); }
