package dev.amatv.build;

import java.io.IOException;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

public abstract class VerifyMusicKitSdkTask extends DefaultTask {
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSdkAars();

    @TaskAction
    public void verify() throws IOException {
        var aars = getSdkAars().getFiles();
        if (aars.isEmpty()) return; // Keep the SDK-free demo build available.
        if (aars.size() != 1) {
            throw new IllegalStateException("Expected one official MusicKit playback AAR, found " + aars.size());
        }
        MusicKitSongIdPatch.verifySdk(aars.iterator().next().toPath());
    }
}
