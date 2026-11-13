package mcp.remastered.engine.pipeline.patch.bytecode.fixes;

import mcp.remastered.engine.pipeline.patch.bytecode.BytecodePatch;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.tree.*;

import java.util.HashSet;
import java.util.Set;

/**
 * Preserves an unchecked self conversion for simple generic methods returning this.
 */
public final class GenericSelfReturn implements BytecodePatch {

    @Override
    public boolean apply(ClassNode clazz) {
        boolean changed = false;
        for (MethodNode method : clazz.methods) {
            Type returnType = Type.getReturnType(method.desc);
            if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_BRIDGE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || returnType.getSort() != Type.OBJECT || method.signature == null)
                continue;
            String target = returnType.getInternalName();
            if (!(target.equals(clazz.name) || clazz.interfaces.contains(target)) || !returnsSelfWithMethodTypeVariable(method.signature, target))
                continue;
            AbstractInsnNode load = opcode(method.instructions.getFirst());
            AbstractInsnNode ret = load == null ? null : opcode(load.getNext());
            if (!(load instanceof VarInsnNode var) || var.getOpcode() != Opcodes.ALOAD || var.var != 0 || ret == null || ret.getOpcode() != Opcodes.ARETURN || opcode(ret.getNext()) != null)
                continue;
            int local = Math.max(1, method.maxLocals);
            InsnList replacement = new InsnList();
            if (!target.equals(clazz.name)) replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, target));
            replacement.add(new VarInsnNode(Opcodes.ASTORE, local));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, local));
            replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, target));
            method.instructions.insertBefore(ret, replacement);
            method.maxLocals = local + 1;
            changed = true;
        }
        return changed;
    }

    private static boolean returnsSelfWithMethodTypeVariable(String signature, String owner) {
        Set<String> formals = new HashSet<>();
        boolean[] self = {false};
        boolean[] usesFormal = {false};
        new SignatureReader(signature).accept(new SignatureVisitor(Opcodes.ASM9) {
            @Override
            public void visitFormalTypeParameter(String name) {
                formals.add(name);
            }

            @Override
            public SignatureVisitor visitReturnType() {
                return new SignatureVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitClassType(String name) {
                        self[0] = owner.equals(name);
                    }

                    @Override
                    public SignatureVisitor visitTypeArgument(char wildcard) {
                        return new SignatureVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitTypeVariable(String name) {
                                if (formals.contains(name)) usesFormal[0] = true;
                            }
                        };
                    }
                };
            }
        });
        return self[0] && usesFormal[0];
    }

    private static AbstractInsnNode opcode(AbstractInsnNode node) {
        while (node != null && node.getOpcode() < 0) node = node.getNext();
        return node;
    }
}
