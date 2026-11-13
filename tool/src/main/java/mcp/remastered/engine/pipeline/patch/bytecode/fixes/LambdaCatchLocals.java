package mcp.remastered.engine.pipeline.patch.bytecode.fixes;

import mcp.remastered.engine.pipeline.patch.bytecode.BytecodePatch;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.HashSet;
import java.util.Set;

/**
 * Avoids stale local-variable names when Vineflower inlines lambda exception handlers.
 */
public final class LambdaCatchLocals implements BytecodePatch {

    @Override
    public boolean apply(ClassNode clazz) {
        boolean changed = false;
        for (MethodNode method : clazz.methods) {
            if ((method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)) != (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC) || !method.name.startsWith("lambda$") || method.tryCatchBlocks.isEmpty() || method.localVariables == null || method.localVariables.isEmpty())
                continue;
            Set<LocalVariableNode> caught = new HashSet<>();
            for (TryCatchBlockNode block : method.tryCatchBlocks) {
                if (block.type == null) continue;
                AbstractInsnNode store = block.handler;
                while (store != null && store.getOpcode() < 0) store = store.getNext();
                if (!(store instanceof VarInsnNode var) || var.getOpcode() != Opcodes.ASTORE) continue;
                AbstractInsnNode next = store.getNext();
                while (next != null && next.getOpcode() < 0) next = next.getNext();
                int from = method.instructions.indexOf(store);
                int to = next == null ? method.instructions.size() : method.instructions.indexOf(next);
                for (LocalVariableNode local : method.localVariables) {
                    int start = method.instructions.indexOf(local.start);
                    int end = method.instructions.indexOf(local.end);
                    if (local.index == var.var && local.desc.equals("L" + block.type + ";") && start > from && start <= to && end > start)
                        caught.add(local);
                }
            }
            if (!caught.isEmpty()) {
                method.localVariables.removeAll(caught);
                changed = true;
            }
        }
        return changed;
    }
}
