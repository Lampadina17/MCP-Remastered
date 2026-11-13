package mcp.remastered.engine.pipeline.patch.bytecode.fixes;

import mcp.remastered.engine.pipeline.patch.bytecode.BytecodePatch;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Retains the input type of an unbound method reference used to build a static comparator.
 * Only a straight-line comparator initializer ending in a field with an explicit generic
 * signature is considered; the field type must agree with the erased lambda input type.
 */
public final class ComparatorMethodReference implements BytecodePatch {
    private static final String COMPARATOR = "java/util/Comparator";
    private static final String FUNCTION = "()Ljava/util/function/Function;";
    private static final String INT_FUNCTION = "()Ljava/util/function/ToIntFunction;";

    @Override
    public boolean apply(ClassNode clazz) {
        if ((clazz.access & Opcodes.ACC_INTERFACE) != 0 && (clazz.version & 0xffff) < Opcodes.V9) return false;
        boolean changed = false;
        List<MethodNode> helpers = new ArrayList<>();
        for (MethodNode method : clazz.methods) {
            if (!method.name.equals("<clinit>") || !method.desc.equals("()V")) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof InvokeDynamicInsnNode indy) || !validLambda(indy)) continue;
                AbstractInsnNode next = opcode(indy.getNext());
                if (next instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                        && field.desc.equals("Ljava/util/Comparator;")) next = opcode(next.getNext());
                if (!(next instanceof MethodInsnNode comparing) || comparing.getOpcode() != Opcodes.INVOKESTATIC
                        || !comparing.owner.equals(COMPARATOR) || !comparing.itf || !isComparing(indy, comparing))
                    continue;
                FieldInsnNode destination = destination(opcode(next.getNext()), clazz.name);
                if (destination == null) continue;
                FieldNode field = clazz.fields.stream().filter(f -> f.name.equals(destination.name) && f.desc.equals(destination.desc)).findFirst().orElse(null);
                String input = field == null ? null : comparatorInput(field.signature);
                Type methodType = (Type) indy.bsmArgs[2];
                if (input == null || !rawOwner(input).equals(methodType.getArgumentTypes()[0].getInternalName()))
                    continue;
                Type result = methodType.getReturnType();
                if (indy.desc.equals(FUNCTION) && result.getSort() != Type.OBJECT && result.getSort() != Type.ARRAY)
                    continue;
                String signature = indy.desc.equals(INT_FUNCTION)
                        ? "()Ljava/util/function/ToIntFunction<" + input + ">;"
                        : "()Ljava/util/function/Function<" + input + result.getDescriptor() + ">;";
                String name = "mcp$comparatorTarget$" + helpers.size();
                while (hasMethod(clazz, helpers, name)) name += "$";
                MethodNode helper = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, name, indy.desc, signature, null);
                helper.instructions.add(new InvokeDynamicInsnNode(indy.name, indy.desc, indy.bsm, indy.bsmArgs));
                helper.instructions.add(new InsnNode(Opcodes.ARETURN));
                helper.maxStack = 1;
                helpers.add(helper);
                MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, clazz.name, name, indy.desc, (clazz.access & Opcodes.ACC_INTERFACE) != 0);
                method.instructions.set(indy, call);
                insn = call;
                changed = true;
            }
        }
        clazz.methods.addAll(helpers);
        return changed;
    }

    private static boolean validLambda(InvokeDynamicInsnNode indy) {
        if (!(indy.desc.equals(FUNCTION) && indy.name.equals("apply") || indy.desc.equals(INT_FUNCTION) && indy.name.equals("applyAsInt")))
            return false;
        if (!indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") || !indy.bsm.getName().equals("metafactory")
                || indy.bsmArgs.length != 3 || !(indy.bsmArgs[1] instanceof Handle handle)
                || !(indy.bsmArgs[2] instanceof Type type) || type.getSort() != Type.METHOD) return false;
        if (handle.getTag() != Opcodes.H_INVOKEVIRTUAL && handle.getTag() != Opcodes.H_INVOKEINTERFACE) return false;
        Type[] args = type.getArgumentTypes();
        return args.length == 1 && args[0].getSort() == Type.OBJECT
                && (!indy.desc.equals(INT_FUNCTION) || type.getReturnType().equals(Type.INT_TYPE));
    }

    private static boolean isComparing(InvokeDynamicInsnNode indy, MethodInsnNode call) {
        if (indy.desc.equals(INT_FUNCTION)) return call.name.equals("comparingInt")
                && call.desc.equals("(Ljava/util/function/ToIntFunction;)Ljava/util/Comparator;");
        return call.name.equals("comparing") && (call.desc.equals("(Ljava/util/function/Function;)Ljava/util/Comparator;")
                || call.desc.equals("(Ljava/util/function/Function;Ljava/util/Comparator;)Ljava/util/Comparator;"));
    }

    private static FieldInsnNode destination(AbstractInsnNode insn, String owner) {
        int limit = 0;
        while (insn != null && limit++ < 12) {
            if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC)
                return field.owner.equals(owner) && field.desc.equals("Ljava/util/Comparator;") ? field : null;
            if (insn instanceof InvokeDynamicInsnNode indy && validLambda(indy)) {
                insn = opcode(insn.getNext());
                continue;
            }
            if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC && field.desc.equals("Ljava/util/Comparator;")) {
                insn = opcode(insn.getNext());
                continue;
            }
            if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEINTERFACE && call.owner.equals(COMPARATOR)
                    && (call.name.equals("reversed") && call.desc.equals("()Ljava/util/Comparator;")
                    || call.name.equals("thenComparing") && (call.desc.equals("(Ljava/util/function/Function;)Ljava/util/Comparator;")
                    || call.desc.equals("(Ljava/util/function/Function;Ljava/util/Comparator;)Ljava/util/Comparator;")))) {
                insn = opcode(insn.getNext());
                continue;
            }
            return null;
        }
        return null;
    }

    private static String comparatorInput(String signature) {
        String prefix = "Ljava/util/Comparator<";
        if (signature == null || !signature.startsWith(prefix) || !signature.endsWith(">;")) return null;
        String input = signature.substring(prefix.length(), signature.length() - 2);
        if (!input.startsWith("L") || !input.endsWith(";") || input.indexOf('.') >= 0) return null;
        boolean[] containsVariable = {false};
        try {
            new SignatureReader(input).acceptType(new SignatureVisitor(Opcodes.ASM9) {
                @Override
                public void visitTypeVariable(String name) {
                    containsVariable[0] = true;
                }

                @Override
                public SignatureVisitor visitTypeArgument(char wildcard) {
                    return this;
                }

                @Override
                public SignatureVisitor visitArrayType() {
                    return this;
                }
            });
        } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
            return null;
        }
        if (containsVariable[0]) return null;
        return input;
    }

    private static String rawOwner(String signature) {
        int generic = signature.indexOf('<');
        return signature.substring(1, generic < 0 ? signature.length() - 1 : generic);
    }

    private static AbstractInsnNode opcode(AbstractInsnNode insn) {
        while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
        return insn;
    }

    private static boolean hasMethod(ClassNode clazz, List<MethodNode> helpers, String name) {
        return clazz.methods.stream().anyMatch(m -> m.name.equals(name)) || helpers.stream().anyMatch(m -> m.name.equals(name));
    }
}
