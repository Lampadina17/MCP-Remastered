package mcp.remastered.engine.pipeline.patch.bytecode.fixes;

import mcp.remastered.engine.pipeline.patch.bytecode.BytecodePatch;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * Preserves an unchecked generic conversion in simple superclass-returning overrides.
 */
public final class GenericSuperReturn implements BytecodePatch {

    @Override
    public boolean apply(ClassNode clazz) {
        boolean changed = false;
        for (MethodNode method : clazz.methods) {
            if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_BRIDGE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || Type.getArgumentTypes(method.desc).length != 0)
                continue;
            Type retType = Type.getReturnType(method.desc);
            if (retType.getSort() != Type.OBJECT) continue;
            String type = retType.getInternalName();
            // Only the exact self-parameterized return, not arbitrary generic methods.
            if (!("()L" + type + "<L" + clazz.name + ";>;").equals(method.signature)) continue;
            AbstractInsnNode load = opcode(method.instructions.getFirst());
            AbstractInsnNode call = load == null ? null : opcode(load.getNext());
            AbstractInsnNode ret = call == null ? null : opcode(call.getNext());
            if (!(load instanceof VarInsnNode var) || var.getOpcode() != Opcodes.ALOAD || var.var != 0 || !(call instanceof MethodInsnNode invoke) || invoke.getOpcode() != Opcodes.INVOKESPECIAL || !invoke.owner.equals(clazz.superName) || !invoke.name.equals(method.name) || !invoke.desc.equals(method.desc) || ret == null || ret.getOpcode() != Opcodes.ARETURN || opcode(ret.getNext()) != null)
                continue;
            int local = Math.max(1, method.maxLocals);
            InsnList instructions = new InsnList();
            instructions.add(new VarInsnNode(Opcodes.ASTORE, local));
            instructions.add(new VarInsnNode(Opcodes.ALOAD, local));
            instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, type));
            method.instructions.insertBefore(ret, instructions);
            method.maxLocals = local + 1;
            changed = true;
        }
        return changed;
    }

    private static AbstractInsnNode opcode(AbstractInsnNode insn) {
        while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
        return insn;
    }
}
