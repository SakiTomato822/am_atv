package dev.amatv.build;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import com.android.build.api.instrumentation.InstrumentationParameters;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

public abstract class MusicKitSongIdVisitorFactory
        implements AsmClassVisitorFactory<MusicKitSongIdVisitorFactory.Parameters> {
    public interface Parameters extends InstrumentationParameters {
        @Input
        SetProperty<String> getFrameRepairClasses();
    }

    @Override
    public boolean isInstrumentable(ClassData classData) {
        return MusicKitSongIdPatch.CLASS_NAME.equals(classData.getClassName())
                || getParameters().get().getFrameRepairClasses().get().contains(classData.getClassName());
    }

    @Override
    public ClassVisitor createClassVisitor(ClassContext classContext, ClassVisitor nextClassVisitor) {
        return new ClassNode(Opcodes.ASM9) {
            @Override
            public void visitEnd() {
                super.visitEnd();
                // Every other selected SDK class is replayed without opcode edits.
                // AGP's INSTRUMENTED_CLASSES mode supplies only frames and maxima.
                if (MusicKitSongIdPatch.INTERNAL_NAME.equals(name)) MusicKitSongIdPatch.apply(this);
                accept(nextClassVisitor);
            }
        };
    }

}
