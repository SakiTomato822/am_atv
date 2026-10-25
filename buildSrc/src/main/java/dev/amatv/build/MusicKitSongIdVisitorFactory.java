package dev.amatv.build;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import com.android.build.api.instrumentation.InstrumentationParameters;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

public abstract class MusicKitSongIdVisitorFactory
        implements AsmClassVisitorFactory<InstrumentationParameters.None> {
    @Override
    public boolean isInstrumentable(ClassData classData) {
        return MusicKitSongIdPatch.CLASS_NAME.equals(classData.getClassName());
    }

    @Override
    public ClassVisitor createClassVisitor(ClassContext classContext, ClassVisitor nextClassVisitor) {
        return new ClassNode(Opcodes.ASM9) {
            @Override
            public void visitEnd() {
                super.visitEnd();
                MusicKitSongIdPatch.apply(this);
                accept(nextClassVisitor);
            }
        };
    }
}
