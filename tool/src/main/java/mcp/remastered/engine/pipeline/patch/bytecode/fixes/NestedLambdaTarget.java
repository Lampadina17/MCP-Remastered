package mcp.remastered.engine.pipeline.patch.bytecode.fixes;

import mcp.remastered.engine.pipeline.patch.bytecode.BytecodePatch;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * Retains a nested functional interface as the target type of a lambda.
 */
public final class NestedLambdaTarget implements BytecodePatch {

    @Override
    public boolean apply(ClassNode clazz) {
        boolean changed = false;
        java.util.List<MethodNode> helpers = new java.util.ArrayList<>();
        for (MethodNode method : clazz.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                // Serializable/marker-interface lambda protocols need a separate review;
                // moving altMetafactory sites can affect generated deserialization support.
                if (!(insn instanceof InvokeDynamicInsnNode indy) || !indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") || !indy.bsm.getName().equals("metafactory"))
                    continue;
                Type target = Type.getReturnType(indy.desc);
                if (target.getSort() != Type.OBJECT || !target.getInternalName().startsWith(clazz.name + "$")) continue;
                AbstractInsnNode next = indy.getNext();
                while (next != null && next.getOpcode() < 0) next = next.getNext();
                boolean returned = next != null && next.getOpcode() == Opcodes.ARETURN && Type.getReturnType(method.desc).equals(Type.getObjectType(clazz.name));
                boolean stored = next instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC && field.owner.equals(clazz.name) && field.desc.equals("L" + clazz.name + ";");
                if (stored && method.name.equals("<clinit>") && Type.getArgumentTypes(indy.desc).length == 0) {
                    String name = "mcp$lambdaTarget$" + helpers.size();
                    while (hasMethod(clazz, helpers, name)) name += "$";
                    MethodNode helper = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, name, indy.desc, null, null);
                    helper.instructions.add(new InvokeDynamicInsnNode(indy.name, indy.desc, indy.bsm, indy.bsmArgs));
                    helper.instructions.add(new InsnNode(Opcodes.ARETURN));
                    helper.maxStack = 1;
                    helpers.add(helper);
                    MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, clazz.name, name, indy.desc, (clazz.access & Opcodes.ACC_INTERFACE) != 0);
                    method.instructions.set(indy, call);
                    insn = call;
                    changed = true;
                    continue;
                }
                if (!returned) continue;
                int local = method.maxLocals++;
                InsnList sequence = new InsnList();
                sequence.add(new VarInsnNode(Opcodes.ASTORE, local));
                sequence.add(new VarInsnNode(Opcodes.ALOAD, local));
                sequence.add(new TypeInsnNode(Opcodes.CHECKCAST, target.getInternalName()));
                method.instructions.insert(indy, sequence);
                changed = true;
            }
        }
        clazz.methods.addAll(helpers);
        return changed;
    }

    private static boolean hasMethod(ClassNode clazz, java.util.List<MethodNode> helpers, String name) {
        return clazz.methods.stream().anyMatch(method -> method.name.equals(name)) || helpers.stream().anyMatch(method -> method.name.equals(name));
    }
}
